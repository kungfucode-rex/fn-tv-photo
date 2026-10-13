package api

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	"tuping/internal/phash"
	"tuping/internal/qr"
	"tuping/internal/store"
)

// maxThumbUpload caps one thumbnail. The canonical thumbnail is 320px wide at
// q80 — about 25 KB — so this is two orders of magnitude of headroom for an album
// that sends a larger one because it had it to hand.
const maxThumbUpload = 8 << 20

// defaultFreshnessDays is how long "this device already registered this photo"
// is answered from the stored row (§6.2).
const defaultFreshnessDays = 30

// photoMeta is the JSON `meta` part of a registration.
type photoMeta struct {
	LocalID        string `json:"localId"`
	Source         string `json:"source"`
	SHA256Original string `json:"sha256Original"`
	Width          int    `json:"width"`
	Height         int    `json:"height"`
	Scope          string `json:"scope"`
	ScopeID        string `json:"scopeId"`
	Client         string `json:"client"`

	// BaseURL is the address the registering device uses to reach this server — the
	// address it just uploaded to. When present it becomes the address this photo's QR
	// code points at, because it is the only party that knows which of this server's
	// addresses actually works from where the television is.
	//
	// This is not the same thing as trusting the Host header: the value a device reports
	// is one it chose and proved it could reach, whereas a Host header is whatever the
	// caller typed. Since §17.20 the header *is* used, but only as the last resort and
	// only for the response it arrived on; what is stored on the code is still this
	// device-supplied value, validated below, and the fallback chain is
	// device → pinned TUPING_BASE_URL → the address of the request being answered.
	BaseURL string `json:"baseUrl"`
}

type photoResponse struct {
	ImageID      string `json:"imageId"`
	Code         string `json:"code"`
	Matched      string `json:"matched"`
	QRURL        string `json:"qrUrl"`
	ThumbURL     string `json:"thumbUrl"`
	CommentCount int    `json:"commentCount"`
	Scope        string `json:"scope"`
	ServerTime   int64  `json:"serverTime"`
}

// handlePhotoRegister is POST /v1/photos: upload a canonical thumbnail, get back
// an image id, a short code and the two URLs the TV needs.
func (s *Server) handlePhotoRegister(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := s.requireDevice(w, r)
	if !ok {
		return
	}

	ip := s.hmacIP(clientIP(r))
	allowed, key, err := s.store.AllowAll(r.Context(),
		store.RateLimit{Key: "reg:" + deviceID + ":hour", Limit: store.LimitPerDevice.Limit, Window: store.LimitPerDevice.Window},
		store.RateLimit{Key: "reg-ip:" + ip + ":hour", Limit: 600, Window: time.Hour},
	)
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !allowed {
		s.log.Warn("registration rate limited", "key", key, "device", deviceID)
		writeError(w, http.StatusTooManyRequests, "rate_limited", "注册太频繁了")
		return
	}

	r.Body = http.MaxBytesReader(w, r.Body, maxThumbUpload+64*1024)
	reader, err := r.MultipartReader()
	if err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "需要 multipart/form-data")
		return
	}

	var (
		meta    photoMeta
		thumb   []byte
		sawMeta bool
	)
	for {
		part, err := reader.NextPart()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			writeError(w, http.StatusBadRequest, "bad_request", "上传中断了")
			return
		}
		switch part.FormName() {
		case "meta":
			raw, err := io.ReadAll(io.LimitReader(part, 64*1024))
			if err != nil {
				writeError(w, http.StatusBadRequest, "bad_request", "meta 读不出来")
				return
			}
			if err := json.Unmarshal(raw, &meta); err != nil {
				writeError(w, http.StatusBadRequest, "bad_request", "meta 不是合法 JSON")
				return
			}
			sawMeta = true
		case "thumb":
			data, err := readLimited(part, maxThumbUpload)
			if err != nil {
				writeError(w, http.StatusBadRequest, "bad_request", "缩略图太大了")
				return
			}
			thumb = data
		default:
			_, _ = io.Copy(io.Discard, part)
		}
		part.Close()
	}

	if !sawMeta && len(thumb) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 thumb 和 meta")
		return
	}
	if len(thumb) == 0 {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 thumb")
		return
	}

	// Decode, normalize, re-encode, fingerprint. Everything after this point works
	// on bytes this server produced, which is what removes EXIF and GPS for free.
	image, err := phash.Compute(thumb)
	if err != nil {
		s.log.Warn("thumbnail rejected", "err", err, "device", deviceID)
		writeError(w, http.StatusBadRequest, "bad_request", "缩略图读不出来")
		return
	}

	sha := strings.ToLower(strings.TrimSpace(meta.SHA256Original))
	if sha != "" && !isHex64(sha) {
		writeError(w, http.StatusBadRequest, "bad_request", "sha256Original 不是 64 位十六进制")
		return
	}

	scope := store.ScopeImage
	if strings.EqualFold(meta.Scope, "event") {
		// Accepted from day one and stored, but the first version only ever writes
		// image scope (decision D5). An event code still resolves and still plays;
		// what is missing is the second QR source and the album-side selector.
		scope = store.ScopeEvent
	}

	source := meta.Source
	if source == "" {
		source = "unknown"
	}

	thumbKey := store.NewID()[:16] + "-" + randomToken(6)
	if s.thumbs != nil {
		if err := s.thumbs.Put(thumbKey, image.Canonical); err != nil {
			s.serverError(w, err)
			return
		}
	}

	ttl := s.cfg.ThumbTTL
	if ttl <= 0 {
		ttl = 30 * 24 * time.Hour
	}

	result, err := s.store.Register(r.Context(), store.RegisterInput{
		DeviceID:       deviceID,
		Source:         source,
		LocalID:        meta.LocalID,
		Thumb:          image.Canonical,
		ThumbKey:       thumbKey,
		ThumbExpiresAt: time.Now().Add(ttl).UnixMilli(),
		SHA256:         sha,
		Image:          image,
		OwnerTokenHash: store.HashOwnerToken(r.Header.Get("X-Owner-Token")),
		Scope:          scope,
		ScopeID:        meta.ScopeID,
		Visibility:     store.VisibilityImage,
		FreshnessDays:  defaultFreshnessDays,
		BaseURL:        normalizeBaseURL(meta.BaseURL),
	})
	if err != nil {
		s.serverError(w, err)
		return
	}

	if image.StrippedEdges != "" {
		s.log.Info("stripped padding from thumbnail", "device", deviceID, "edges", image.StrippedEdges)
	}
	s.log.Info("photo registered",
		"device", deviceID,
		"imageId", result.Photo.ID,
		"code", result.Code,
		"matched", result.Matched,
		"reused", result.Reused,
		"variance", int(image.Variance),
	)

	s.writePhotoResponse(w, result.Photo, result.Code, string(result.Matched), scope, result.BaseURL, s.callerBase(r))
}

// writePhotoResponse is the registration reply.
//
// [codeBase] is the address recorded for this code and [caller] the address this request
// arrived on; the two URLs in the reply are built from the same rule the QR endpoint and the
// H5 page follow. A reply whose qrUrl pointed at one host while the code itself lived at
// another is how a client ends up unable to fetch a code the server just told it about.
func (s *Server) writePhotoResponse(w http.ResponseWriter, photo *store.Photo, code, matched string, scope store.Scope, codeBase, caller string) {
	target := &store.CodeTarget{Code: code, BaseURL: codeBase}
	writeJSON(w, http.StatusOK, photoResponse{
		ImageID:      photo.ID,
		Code:         code,
		Matched:      matched,
		QRURL:        fmt.Sprintf("%s/v1/qr/%s.png?size=%d", s.pageBase(target, caller), code, 440),
		ThumbURL:     s.ThumbURL(code, phash.CanonicalWidth, target, caller),
		CommentCount: photo.CommentCount,
		Scope:        scope.String(),
		ServerTime:   time.Now().UnixMilli(),
	})
}

// photoDetail is GET /v1/photos/{code}.
type photoDetail struct {
	ImageID      string `json:"imageId"`
	Code         string `json:"code"`
	Scope        string `json:"scope"`
	CommentCount int    `json:"commentCount"`
	ThumbURL     string `json:"thumbUrl"`
	QRURL        string `json:"qrUrl"`
	Width        int    `json:"width"`
	Height       int    `json:"height"`
	Visibility   int    `json:"visibility"`
	ServerTime   int64  `json:"serverTime"`
}

func (s *Server) handlePhotoGet(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !store.ValidCode(code) {
		writeError(w, http.StatusNotFound, "not_found", "短码无效")
		return
	}
	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
			return
		}
		s.serverError(w, err)
		return
	}

	caller := s.callerBase(r)
	writeJSON(w, http.StatusOK, photoDetail{
		ImageID:      target.Photo.ID,
		Code:         target.Code,
		Scope:        target.Scope.String(),
		CommentCount: target.Photo.CommentCount,
		ThumbURL:     s.ThumbURL(target.Code, phash.CanonicalWidth, target, caller),
		QRURL:        fmt.Sprintf("%s/v1/qr/%s.png?size=%d", s.pageBase(target, caller), target.Code, 440),
		Width:        target.Photo.Width,
		Height:       target.Photo.Height,
		Visibility:   target.Photo.Visibility,
		ServerTime:   time.Now().UnixMilli(),
	})
}

// handlePhotoByLocal is GET /v1/photos/by-local?source=…&localId=…: "have I already
// registered this picture?" answered as a read (docs/tuping-design.md §17.28).
//
// This is what makes an album end able to show a photo's code and its comments **without
// uploading anything**. The device's own local id is the only handle it has before the
// server has spoken — `image_id` is a ULID it cannot compute — and `device_local` is where
// the server recorded the answer the first time somebody asked for this photo to be
// commentable. A miss is a 404 and means exactly one thing: nobody has ever opened 图评 on
// this picture from this device, so it is not on the server at all.
//
// Deliberately a *read*: no row is created, no thumbnail is stored, no counter moves. The
// alternative — registering on every photo the viewer happens to show — is what filled a
// database with an album nobody had looked at, and the whole point of §17.28 is that the
// only thing that puts a picture here is somebody asking for one.
func (s *Server) handlePhotoByLocal(w http.ResponseWriter, r *http.Request) {
	deviceID, ok := s.requireDevice(w, r)
	if !ok {
		return
	}

	source := strings.TrimSpace(r.URL.Query().Get("source"))
	localID := strings.TrimSpace(r.URL.Query().Get("localId"))
	if source == "" || localID == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "需要 source 和 localId")
		return
	}

	code, found, err := s.store.DevicePhoto(r.Context(), deviceID, source, localID)
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !found {
		writeError(w, http.StatusNotFound, "not_found", "这台设备还没给这张照片开过图评")
		return
	}

	// Resolved through the code rather than straight from the image id, so this answer and
	// `GET /v1/photos/{code}` are built by the same path: merges followed, comment count
	// summed over the group, and the address taken from wherever the code actually lives.
	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
			return
		}
		s.serverError(w, err)
		return
	}

	caller := s.callerBase(r)
	writeJSON(w, http.StatusOK, photoDetail{
		// The root of the merge group, exactly as `GET /v1/photos/{code}` answers it: the
		// album end keys its cache by local id and then fetches comments by this id, so two
		// endpoints disagreeing about it would be two threads for one picture.
		ImageID:      target.Photo.ID,
		Code:         target.Code,
		Scope:        target.Scope.String(),
		CommentCount: target.Photo.CommentCount,
		ThumbURL:     s.ThumbURL(target.Code, phash.CanonicalWidth, target, caller),
		QRURL:        fmt.Sprintf("%s/v1/qr/%s.png?size=%d", s.pageBase(target, caller), target.Code, 440),
		Width:        target.Photo.Width,
		Height:       target.Photo.Height,
		Visibility:   target.Photo.Visibility,
		ServerTime:   time.Now().UnixMilli(),
	})
}

// handlePhotoDelete is the album owner's one-press delete: the photo, its codes,
// its thumbnail and every comment on it (§12.5).
func (s *Server) handlePhotoDelete(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireDevice(w, r); !ok {
		return
	}
	id := r.PathValue("id")
	ownerToken := r.Header.Get("X-Owner-Token")

	matches, err := s.store.OwnerTokenMatches(r.Context(), id, ownerToken)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
			return
		}
		s.serverError(w, err)
		return
	}
	if !matches {
		writeError(w, http.StatusUnauthorized, "unauthorized", "只有注册这张照片的设备能删除它")
		return
	}

	// The thumbnail keys have to be read before the rows go: once the photos are
	// deleted there is nothing left that knows which files belonged to them.
	keys, err := s.store.ThumbKeys(r.Context(), id)
	if err != nil {
		s.serverError(w, err)
		return
	}

	group, err := s.store.DeletePhoto(r.Context(), id)
	if err != nil {
		s.serverError(w, err)
		return
	}
	for _, key := range keys {
		if s.thumbs != nil && key != "" {
			_ = s.thumbs.Delete(key)
		}
	}
	s.log.Info("photo deleted", "imageId", id, "group", len(group))
	writeJSON(w, http.StatusOK, map[string]any{"deleted": id, "group": group})
}

// handleQR renders the code as a PNG the TV can draw.
func (s *Server) handleQR(w http.ResponseWriter, r *http.Request) {
	code := strings.TrimSuffix(r.PathValue("code"), ".png")
	if !store.ValidCode(code) {
		writeError(w, http.StatusNotFound, "not_found", "短码无效")
		return
	}
	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
			return
		}
		s.serverError(w, err)
		return
	}

	// A QR code is a picture of an address, so there is nothing to render when no address
	// anybody can open is known: an old client that reported none, on a deployment that
	// pinned none, asked with a Host the server will not hand on. Refusing says so; encoding
	// "/c/<code>" would produce a code that scans into a dead end (§17.20).
	page := s.pageURL(target.Code, target, s.callerBase(r))
	if page == "" {
		writeError(w, http.StatusBadRequest, "no_address",
			"服务器不知道手机该用哪个地址：这张照片注册时没有上报地址，而这次请求用的地址手机打不开。"+
				"把 TUPING_BASE_URL 设成手机能访问的地址，或者在电视上重新注册这张照片。")
		return
	}
	png, err := s.qr.PNG(page, intParam(r, "size", qr.DefaultSize))
	if err != nil {
		s.serverError(w, err)
		return
	}

	// What the code actually contains, on the response.
	//
	// Two reasons, and the second is the one that matters. First, a QR code is a picture of
	// an address and there is otherwise no way to read it without a phone: this makes
	// `curl -I` answer "which URL will my guests open?" on a machine with no camera.
	// Second, it is what a test can assert on — `skip2/go-qrcode` encodes and does not
	// decode, and the bug this header was added for was exactly a code quietly pointing at
	// an address nobody could reach.
	w.Header().Set("X-Page-Url", page)

	w.Header().Set("Content-Type", "image/png")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if target.BaseURL != "" || s.cfg.BaseURL != "" {
		// The address is recorded on the code or pinned in the configuration, so this PNG
		// is the same for everybody and the TV may keep it: that is what makes
		// pre-registering worthwhile.
		w.Header().Set("Cache-Control", "public, max-age=86400, immutable")
	} else {
		// The address came from this request's Host header, so the picture is one caller's
		// answer, not the code's. `public` here would let any shared cache hand one
		// hostname's QR code to another hostname's guests.
		w.Header().Set("Cache-Control", "private, no-store")
	}
	_, _ = w.Write(png)
}

// pageURL is the address the code opens — the H5 page, not the API — or "" when no address
// anybody could open is known. [target] may carry the address the registering device used;
// when it does, that wins over the pinned TUPING_BASE_URL, which wins over the address this
// request arrived on. See the note on `photoMeta.BaseURL` and on pageBase.
func (s *Server) pageURL(code string, target *store.CodeTarget, caller string) string {
	base := s.pageBase(target, caller)
	if base == "" {
		return ""
	}
	return base + "/c/" + code
}

// maxBaseURLLen bounds a client-supplied base URL. Long enough for a hostname, a port and
// a path prefix; short enough that a QR code cannot be stuffed with padding.
const maxBaseURLLen = 200

// normalizeBaseURL validates an address a device says it used, returning "" when it is not
// something a phone could open.
//
// Only http and https, only with a host, and the path/query/fragment are dropped: the
// server appends "/c/<code>" itself, so anything already on the path is either a mistake
// or an attempt to point the code somewhere else. Returning "" rather than an error is
// deliberate — a device that sends nonsense still gets a working code, just at the
// address the server was configured with.
func normalizeBaseURL(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" || len(raw) > maxBaseURLLen {
		return ""
	}
	u, err := url.Parse(raw)
	if err != nil {
		return ""
	}
	if u.Scheme != "http" && u.Scheme != "https" {
		return ""
	}
	if u.Host == "" {
		return ""
	}
	if u.User != nil {
		// Credentials in a QR code are a way to leak them to anybody who scans it.
		return ""
	}
	return u.Scheme + "://" + u.Host
}

// readLimited reads a part, refusing rather than truncating when it is too big.
func readLimited(r io.Reader, max int64) ([]byte, error) {
	data, err := io.ReadAll(io.LimitReader(r, max+1))
	if err != nil {
		return nil, err
	}
	if int64(len(data)) > max {
		return nil, fmt.Errorf("api: part exceeds %d bytes", max)
	}
	return data, nil
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c >= '0' && c <= '9':
		case c >= 'a' && c <= 'f':
		case c >= 'A' && c <= 'F':
		default:
			return false
		}
	}
	return true
}
