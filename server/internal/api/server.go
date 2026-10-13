package api

import (
	"bufio"
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"html/template"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"tuping/internal/guard"
	"tuping/internal/phash"
	"tuping/internal/qr"
	"tuping/internal/store"
	"tuping/internal/thumbs"
	"tuping/internal/web"
)

// Config is everything the server needs, all of it from the environment
// (docs/tuping-design.md §11.5).
type Config struct {
	// BaseURL is the address a *phone* can reach. It is what goes into the
	// QR code. Filling in the container's own name here is the single most
	// common way to break this deployment.
	BaseURL string

	// Secret is the HMAC key for signed thumbnail URLs, anon cookies and IPs.
	Secret string

	// AdminToken guards the moderation endpoints and is the password of the admin
	// login on the H5 page (§17.15). Empty disables both.
	AdminToken string

	// RegisterToken, when set, means registering a photo requires it. For an
	// office or a dormitory LAN where "everyone on the network" is too broad.
	RegisterToken string

	// ReviewWords go to the moderation queue; RejectWords are refused. Both are
	// comma-separated and add to the built-in advertisement patterns.
	ReviewWords string
	RejectWords string

	// ThumbTTL is how long a thumbnail may go unread.
	ThumbTTL time.Duration

	// Version is stamped in at link time.
	Version string

	// TLS says this process terminates TLS itself (docs/tuping-design.md §17.23). The
	// transport is the listener's business and nothing in the routing table changes, but two
	// things need to know: the cookies handed out get their Secure flag from the connection
	// they arrive on, and /healthz can answer "are my certificate variables actually in
	// effect" where a person is already looking.
	TLS bool

	// DeviceRegistrationOpen lets a device obtain a token without the register
	// token. On a LAN this is the friendly default; it is the one knob that makes
	// the instance private again.
	DeviceRegistrationOpen bool
}

// Server is the HTTP surface.
type Server struct {
	cfg    Config
	store  *store.Store
	thumbs *thumbs.Store
	qr     *qr.Renderer
	screen *guard.Screener
	live   *hub
	log    *slog.Logger
	tmpl   *template.Template

	// anonSecret and urlSecret are derived from Config.Secret so that a signature
	// for one purpose cannot be replayed as another.
	anonSecret []byte
	urlSecret  []byte
	ipSecret   []byte

	// adminSecret is derived from the secret *and* the admin token, so rotating
	// TUPING_ADMIN_TOKEN invalidates every session cookie already handed out.
	adminSecret []byte

	startedAt time.Time

	sweepOnce sync.Once
}

// Options wires a Server.
type Options struct {
	Config Config
	Store  *store.Store
	Thumbs *thumbs.Store
	Logger *slog.Logger
	QR     *qr.Renderer
}

// New builds the server.
func New(opts Options) (*Server, error) {
	if opts.Store == nil {
		return nil, errors.New("api: no store")
	}
	if opts.Config.Secret == "" {
		return nil, errors.New("api: TUPING_SECRET must be set")
	}
	// Optional since §17.20: with nothing pinned, every URL is built from the address
	// the request itself arrived on, which is what the caller knows and the server does
	// not. A value that is set still has to be usable.
	if opts.Config.BaseURL != "" &&
		!strings.HasPrefix(opts.Config.BaseURL, "http://") &&
		!strings.HasPrefix(opts.Config.BaseURL, "https://") {
		return nil, fmt.Errorf("api: TUPING_BASE_URL must start with http:// or https:// (got %q)", opts.Config.BaseURL)
	}
	logger := opts.Logger
	if logger == nil {
		logger = slog.Default()
	}
	renderer := opts.QR
	if renderer == nil {
		renderer = qr.New(256)
	}

	tmpl, err := template.New("tuping").ParseFS(web.Files, "tuping.html", "error.html", "login.html", "edit.html")
	if err != nil {
		return nil, fmt.Errorf("api: parse templates: %w", err)
	}

	secret := []byte(opts.Config.Secret)

	// Said at startup, not only in /healthz: this is the one misconfiguration whose whole
	// symptom appears on a different device, in a different room, to a different person.
	// The TV says 已连接, the server log says nothing, and the guest's phone opens a
	// 192.168 address that means nothing to it.
	if hostIsPrivate(opts.Config.BaseURL) {
		logger.Warn(
			"TUPING_BASE_URL points at a private address; QR codes will only work for "+
				"phones on this same network",
			"baseUrl", opts.Config.BaseURL,
		)
	}

	return &Server{
		cfg:         opts.Config,
		store:       opts.Store,
		thumbs:      opts.Thumbs,
		qr:          renderer,
		screen:      guard.New(splitWords(opts.Config.ReviewWords), splitWords(opts.Config.RejectWords)),
		live:        newHub(),
		log:         logger,
		tmpl:        tmpl,
		anonSecret:  derive(secret, "anon"),
		urlSecret:   derive(secret, "url"),
		ipSecret:    derive(secret, "ip"),
		adminSecret: derive(secret, "admin\x00"+opts.Config.AdminToken),
		startedAt:   time.Now(),
	}, nil
}

// Handler returns the router. The patterns are the method-and-path form Go 1.22
// added, so no third-party router is needed for a table this size.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /healthz", s.handleHealth)
	mux.HandleFunc("POST /v1/devices", s.handleDeviceRegister)
	mux.HandleFunc("POST /v1/photos", s.handlePhotoRegister)
	mux.HandleFunc("GET /v1/photos/{code}", s.handlePhotoGet)
	mux.HandleFunc("GET /v1/photos/by-local", s.handlePhotoByLocal)
	mux.HandleFunc("DELETE /v1/photos/{id}", s.handlePhotoDelete)
	mux.HandleFunc("GET /v1/qr/{code}", s.handleQR)
	mux.HandleFunc("GET /v1/comments", s.handleComments)
	mux.HandleFunc("POST /v1/comments", s.handleCommentCreate)
	mux.HandleFunc("POST /v1/comments/{id}/report", s.handleCommentReport)
	mux.HandleFunc("GET /v1/live/{imageId}", s.handleLive)

	mux.HandleFunc("GET /v1/admin/comments", s.handleAdminList)
	mux.HandleFunc("POST /v1/admin/comments/{id}", s.handleAdminDecide)

	// The one admin operation that is about the deployment rather than a comment: the
	// address recorded on every short code, for a server that has moved (§17.27).
	mux.HandleFunc("POST /v1/admin/codes/rebind", s.handleAdminRebindCodes)

	// The admin door on the web page (§17.15). Login and logout are POST-only, so a
	// stray <img> or a browser prefetch cannot sign anyone in or out.
	mux.HandleFunc("GET /admin", s.handleAdminLoginPage)
	mux.HandleFunc("POST /admin", s.handleAdminLogin)
	mux.HandleFunc("POST /admin/logout", s.handleAdminLogout)

	mux.HandleFunc("GET /t/{code}", s.handleThumb)
	mux.HandleFunc("GET /c/{code}", s.handlePage)
	mux.HandleFunc("POST /c/{code}", s.handlePageSubmit)
	mux.HandleFunc("GET /c/{code}/report/{id}", s.handlePageReport)
	mux.HandleFunc("GET /c/{code}/edit/{id}", s.handleCommentEditPage)
	mux.HandleFunc("POST /c/{code}/edit/{id}", s.handleCommentEdit)
	mux.HandleFunc("POST /c/{code}/delete/{id}", s.handleCommentDelete)
	mux.HandleFunc("GET /{$}", s.handleRoot)

	return s.withLogging(mux)
}

// Run starts the retention sweep. It is a no-op after the first call.
func (s *Server) Run(ctx context.Context) {
	s.sweepOnce.Do(func() {
		go s.sweepLoop(ctx)
	})
}

// ---------------------------------------------------------------- middleware

// statusWriter records the status code for the log line.
//
// It must forward *every* optional interface the underlying writer has, and
// Unwrap is what makes that stay true as the standard library grows new ones.
// Missing http.Hijacker here is not a cosmetic bug: without it the WebSocket
// upgrade answers 501 for every client, and the danmaku layer silently never
// connects. That is exactly what happened the first time this was written.
type statusWriter struct {
	http.ResponseWriter
	status int
}

func (w *statusWriter) WriteHeader(code int) {
	w.status = code
	w.ResponseWriter.WriteHeader(code)
}

// Unwrap lets http.ResponseController reach the real writer, and documents the
// intent for anyone reading this later.
func (w *statusWriter) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (w *statusWriter) Flush() {
	if f, ok := w.ResponseWriter.(http.Flusher); ok {
		f.Flush()
	}
}

func (w *statusWriter) Hijack() (net.Conn, *bufio.ReadWriter, error) {
	h, ok := w.ResponseWriter.(http.Hijacker)
	if !ok {
		return nil, nil, fmt.Errorf("api: %T does not implement http.Hijacker", w.ResponseWriter)
	}
	return h.Hijack()
}

func (s *Server) withLogging(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		start := time.Now()
		sw := &statusWriter{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(sw, r)
		// The IP is logged as an HMAC too: a log file is a file, and §12.6 applies
		// to it as much as to the database.
		s.log.Info("http",
			"method", r.Method,
			"path", r.URL.Path,
			"status", sw.status,
			"ms", time.Since(start).Milliseconds(),
			"ip", s.hmacIP(clientIP(r)),
		)
	})
}

// ---------------------------------------------------------------- helpers

func derive(secret []byte, purpose string) []byte {
	sum := sha256.Sum256(append(append([]byte{}, secret...), []byte("\x00"+purpose)...))
	return sum[:]
}

func splitWords(s string) []string {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	parts := strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == '\n' || r == ';' })
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// clientIP is the peer address. X-Forwarded-For is deliberately not consulted:
// this service is meant to sit directly on a LAN with nothing in front of it, and
// trusting a header nobody sets would let a caller pick their own rate-limit key.
func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}

// cookieSecure is the Secure flag for every cookie this server hands out.
//
// Off on the plain-http LAN deployment, and that is not laziness: a Secure cookie is simply
// never sent back over http, so setting the flag there would silently delete the features the
// cookies carry — the guest's remembered nickname, the guest's identity for rate limiting, the
// admin session (§10.1, §17.15). Under TLS the same argument runs the other way, and leaving
// it off is the bug: the admin cookie is a bearer token for every comment on the instance.
func cookieSecure(r *http.Request) bool {
	return r != nil && r.TLS != nil
}

func (s *Server) hmacIP(ip string) string {
	if ip == "" {
		return ""
	}
	mac := hmac.New(sha256.New, s.ipSecret)
	mac.Write([]byte(ip))
	return hex.EncodeToString(mac.Sum(nil)[:8])
}

func (s *Server) hmacAnon(id string) string {
	mac := hmac.New(sha256.New, s.anonSecret)
	mac.Write([]byte(id))
	return hex.EncodeToString(mac.Sum(nil))
}

func randomToken(bytes int) string {
	buf := make([]byte, bytes)
	if _, err := rand.Read(buf); err != nil {
		panic(fmt.Sprintf("api: crypto/rand: %v", err))
	}
	return base64.RawURLEncoding.EncodeToString(buf)
}

// constantTimeEquals compares two secrets without leaking where they differ.
func constantTimeEquals(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

type apiError struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	enc := json.NewEncoder(w)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(v)
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]apiError{"error": {Code: code, Message: message}})
}

func decodeJSON(r *http.Request, v any) error {
	defer r.Body.Close()
	body, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	if err != nil {
		return err
	}
	if len(bytes.TrimSpace(body)) == 0 {
		return errors.New("empty body")
	}
	return json.Unmarshal(body, v)
}

// wantsJSON reports whether the caller is an app rather than a browser form.
func wantsJSON(r *http.Request) bool {
	ct := r.Header.Get("Content-Type")
	if strings.Contains(ct, "application/json") {
		return true
	}
	if strings.Contains(ct, "form-urlencoded") || strings.Contains(ct, "multipart/form-data") {
		return false
	}
	return true
}

// ---------------------------------------------------------------- signatures

// signThumb builds the ?sig=&exp=&w= tail of a thumbnail URL. The design asks
// for a 10-minute signature: long enough for one page view, short enough that a
// URL copied out of a chat window is dead by the time it is opened.
const thumbSignatureTTL = 10 * time.Minute

func (s *Server) signThumb(code string, exp int64, width int) string {
	mac := hmac.New(sha256.New, s.urlSecret)
	fmt.Fprintf(mac, "%s|%d|%d", code, exp, width)
	return base64.RawURLEncoding.EncodeToString(mac.Sum(nil)[:16])
}

// pageBase is the address a code's page and its thumbnail URLs are built from.
//
// Three sources, in this order: the address the registering device reported, stored on the
// code and the best evidence there is (§17.6); a pinned TUPING_BASE_URL; and the address
// this very request arrived on. The last one is what makes the setting optional — a device
// that reports nothing still gets a QR code pointing at the address it just used, which is
// the same evidence it would have supplied itself. This has to be one rule for the page,
// the picture and the QR code: a page served over 10.0.2.2:8080 whose <img> points at
// 192.168.31.51 renders as a broken picture on the guest's phone, which looks like a server
// fault and is actually two addresses disagreeing.
//
// Returns "" when there is no evidence at all, and the callers decide what to do about that:
// the H5 page falls back to a relative URL (which a browser resolves against the page's own
// origin, so it works), while the QR endpoint refuses rather than encoding an address nobody
// can open.
func (s *Server) pageBase(target *store.CodeTarget, caller string) string {
	if target != nil && target.BaseURL != "" {
		return strings.TrimSuffix(target.BaseURL, "/")
	}
	if s.cfg.BaseURL != "" {
		return strings.TrimSuffix(s.cfg.BaseURL, "/")
	}
	return caller
}

// advertisedBase is the address handed to a brand-new device as a default for its own
// 图评服务器 row: the pinned value, else the address this request arrived on.
func (s *Server) advertisedBase(r *http.Request) string {
	return s.pageBase(nil, s.callerBase(r))
}

// callerBase is the address this request arrived on, or "" when that address cannot be one
// a phone opens.
//
// It is deliberately weaker evidence than a device's own report — a Host header is whatever
// the caller chose to type (§17.6) — and it is never stored: it answers "where did you reach
// me", not "where should this code live for good". Refusing loopback is what keeps "the
// server guessed 127.0.0.1" from ever becoming a QR code.
func (s *Server) callerBase(r *http.Request) string {
	if isLoopbackHost(r.Host) {
		return ""
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

// isLoopbackHost reports whether a Host header names this very machine.
func isLoopbackHost(host string) bool {
	if host == "" {
		return true
	}
	name := host
	if h, _, err := net.SplitHostPort(host); err == nil {
		name = h
	}
	name = strings.Trim(name, "[]")
	switch strings.ToLower(name) {
	case "localhost", "127.0.0.1", "::1", "0.0.0.0":
		return true
	}
	return strings.HasPrefix(name, "127.")
}

// ThumbURL builds a signed thumbnail URL for a code.
//
// [target] may be nil, and [caller] may be "", in which case the pinned address is used —
// the behaviour every caller had before codes could carry their own address.
func (s *Server) ThumbURL(code string, width int, target *store.CodeTarget, caller string) string {
	exp := time.Now().Add(thumbSignatureTTL).UnixMilli()
	sig := s.signThumb(code, exp, width)
	return fmt.Sprintf("%s/t/%s?w=%d&exp=%d&sig=%s", s.pageBase(target, caller), code, width, exp, sig)
}

func (s *Server) verifyThumb(code string, exp int64, width int, sig string) bool {
	if time.Now().UnixMilli() > exp {
		return false
	}
	return constantTimeEquals(s.signThumb(code, exp, width), sig)
}

// ---------------------------------------------------------------- thumbnail

// thumbWidth is the width the TV asks for. It is the canonical 320px by default;
// the viewer may ask for more, up to what was uploaded.
const maxThumbWidth = 1280

func (s *Server) handleThumb(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	width := intParam(r, "w", phash.CanonicalWidth)
	exp := int64Param(r, "exp", 0)
	sig := r.URL.Query().Get("sig")

	if !store.ValidCode(code) {
		writeError(w, http.StatusNotFound, "not_found", "短码无效")
		return
	}
	if !s.verifyThumb(code, exp, width, sig) {
		writeError(w, http.StatusUnauthorized, "unauthorized", "缩略图地址已过期")
		return
	}

	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
		return
	}
	if s.thumbs == nil || target.Photo.ThumbKey == "" {
		writeError(w, http.StatusNotFound, "not_found", "缩略图已过期")
		return
	}

	key := target.Photo.ThumbKey
	if width != phash.CanonicalWidth && width > 0 {
		key = fmt.Sprintf("%s-w%d", key, clampWidth(width))
	}
	data, err := s.thumbs.Read(key)
	if err != nil {
		// Fall back to the canonical thumbnail rather than failing: an old signed
		// URL for a width that was never rendered should still show something.
		data, err = s.thumbs.Read(target.Photo.ThumbKey)
		if err != nil {
			writeError(w, http.StatusNotFound, "not_found", "缩略图已过期")
			return
		}
	}

	w.Header().Set("Content-Type", "image/jpeg")
	w.Header().Set("Cache-Control", "private, max-age=600")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	_, _ = w.Write(data)
}

func clampWidth(w int) int {
	if w < 64 {
		return 64
	}
	if w > maxThumbWidth {
		return maxThumbWidth
	}
	return w
}

// ---------------------------------------------------------------- health

func (s *Server) handleHealth(w http.ResponseWriter, r *http.Request) {
	var thumbBytes int64
	if s.thumbs != nil {
		thumbBytes = s.thumbs.Bytes()
	}
	// `baseUrl` alone was not enough. The single most common way this deployment goes wrong
	// is TUPING_BASE_URL left at a LAN address while the TV is pointed at a public one, and
	// the symptom — a QR code that resolves to an address the guest's phone cannot reach —
	// is completely invisible on the television, which says 已连接 either way. So the
	// health check says it out loud, in the one place a person looks when something is
	// wrong (docs/tuping-design.md §11.5).
	//
	// Since §17.20 the setting is optional, and the common case is that it is empty because
	// the address comes from whoever is asking. `baseUrlSource` is what tells those two
	// states apart without pretending "" is a URL.
	body := map[string]any{
		"ok":            true,
		"version":       s.cfg.Version,
		"uptimeSec":     int(time.Since(s.startedAt).Seconds()),
		"thumbBytes":    thumbBytes,
		"baseUrl":       s.cfg.BaseURL,
		"baseUrlSource": "TUPING_BASE_URL",
		// Whether this process terminates TLS itself, which is the one thing a deployer cannot
		// confirm from the NAS panel: `curl -k https://…/healthz` answering at all is most of
		// the proof, and this field is the rest of it (§17.23).
		"tls": s.cfg.TLS,
	}
	if s.cfg.BaseURL == "" {
		body["baseUrlSource"] = "the address each caller used"
	}
	// Warnings are collected rather than assigned: two of them can be true at once (a private
	// address *and* an http:// pin under TLS), and the second one silently replacing the first
	// is how a health check stops being worth reading.
	var warnings []string
	if hostIsPrivate(s.cfg.BaseURL) {
		warnings = append(warnings, "TUPING_BASE_URL is a private address; a phone that is not on "+
			"this network cannot open the QR code. Set it to the address phones actually "+
			"reach, and restart.")
	}
	if s.cfg.TLS && strings.HasPrefix(s.cfg.BaseURL, "http://") {
		warnings = append(warnings, "TUPING_BASE_URL is http:// but this server speaks https, so "+
			"every QR code and every link points at an address nothing answers on; make it "+
			"https://, or drop it and let the address come from whoever is connecting.")
	}
	if len(warnings) > 0 {
		body["warning"] = strings.Join(warnings, " ")
	}
	writeJSON(w, http.StatusOK, body)
}

// hostIsPrivate reports whether a base URL points at an address only reachable from the
// same network: the 192.168/10/172.16 ranges, localhost, and .local names.
//
// Deliberately a warning and not a refusal. A household LAN deployment is a perfectly good
// way to run this, and refusing to start would break the case the design is written for
// (docs/tuping-design.md §11.5) — what is wrong is not the LAN address, it is a LAN address
// behind a TV that was told to use a public one.
func hostIsPrivate(raw string) bool {
	u, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return false
	}
	host := u.Hostname()
	if host == "" {
		return false
	}
	if host == "localhost" || strings.HasSuffix(host, ".local") {
		return true
	}
	ip := net.ParseIP(host)
	if ip == nil {
		return false
	}
	return ip.IsPrivate() || ip.IsLoopback() || ip.IsLinkLocalUnicast()
}

func (s *Server) handleRoot(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "/healthz", http.StatusFound)
}

// ---------------------------------------------------------------- devices

type deviceRequest struct {
	ID    string `json:"deviceId"`
	Name  string `json:"name"`
	Token string `json:"token"`
}

type deviceResponse struct {
	DeviceID    string `json:"deviceId"`
	DeviceToken string `json:"deviceToken"`
	OwnerToken  string `json:"ownerToken"`
	BaseURL     string `json:"baseUrl"`
	ServerTime  int64  `json:"serverTime"`
}

// handleDeviceRegister issues (or refreshes) an album end's token.
//
// A device that already holds a token may rotate it by presenting the old one;
// a device with no token gets one only when registration is open. That is the
// knob that decides whether anyone on the network may register photos.
func (s *Server) handleDeviceRegister(w http.ResponseWriter, r *http.Request) {
	var req deviceRequest
	if err := decodeJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "请求读不出来")
		return
	}
	if req.ID == "" {
		req.ID = store.NewID()
	}

	ip := s.hmacIP(clientIP(r))
	ok, key, err := s.store.AllowAll(r.Context(),
		store.RateLimit{Key: "dev:" + ip + ":min", Limit: 20, Window: time.Minute},
		store.RateLimit{Key: "dev:" + ip + ":day", Limit: 200, Window: 24 * time.Hour},
	)
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !ok {
		s.log.Warn("device registration rate limited", "key", key)
		writeError(w, http.StatusTooManyRequests, "rate_limited", "请求太频繁了")
		return
	}

	existing, err := s.store.DeviceExists(r.Context(), req.ID)
	if err != nil {
		s.serverError(w, err)
		return
	}

	if existing {
		authenticated, err := s.store.AuthenticateDevice(r.Context(), req.ID, req.Token)
		if err != nil {
			s.serverError(w, err)
			return
		}
		if !authenticated {
			writeError(w, http.StatusUnauthorized, "unauthorized", "设备令牌不对")
			return
		}
	} else if !s.cfg.DeviceRegistrationOpen && s.cfg.RegisterToken != "" {
		writeError(w, http.StatusUnauthorized, "unauthorized", "需要注册令牌")
		return
	}

	deviceToken := randomToken(24)
	ownerToken := randomToken(24)
	if err := s.store.IssueDevice(r.Context(), req.ID, deviceToken, ownerToken, req.Name); err != nil {
		s.serverError(w, err)
		return
	}

	s.log.Info("device issued", "device", req.ID, "name", req.Name)
	writeJSON(w, http.StatusOK, deviceResponse{
		DeviceID:    req.ID,
		DeviceToken: deviceToken,
		OwnerToken:  ownerToken,
		// A default for the television's own 图评服务器 row, not an instruction. Empty is
		// a legal answer when nothing is pinned and this caller used an address the
		// server will not hand on; the client already knows the address it just used.
		BaseURL:    s.advertisedBase(r),
		ServerTime: time.Now().UnixMilli(),
	})
}

// requireDevice authenticates an album-end request.
func (s *Server) requireDevice(w http.ResponseWriter, r *http.Request) (deviceID string, ok bool) {
	id := r.Header.Get("X-Device-Id")
	token := r.Header.Get("X-Device-Token")
	if id == "" || token == "" {
		writeError(w, http.StatusUnauthorized, "unauthorized", "缺少设备令牌")
		return "", false
	}
	if s.cfg.RegisterToken != "" && !constantTimeEquals(token, s.cfg.RegisterToken) {
		// A deployment may pin one shared token instead of issuing per-device ones.
		if exists, err := s.store.DeviceExists(r.Context(), id); err != nil || !exists {
			writeError(w, http.StatusUnauthorized, "unauthorized", "注册令牌不对")
			return "", false
		}
	}
	authenticated, err := s.store.AuthenticateDevice(r.Context(), id, token)
	if err != nil {
		s.serverError(w, err)
		return "", false
	}
	if !authenticated {
		writeError(w, http.StatusUnauthorized, "unauthorized", "设备令牌不对")
		return "", false
	}
	return id, true
}

func (s *Server) serverError(w http.ResponseWriter, err error) {
	s.log.Error("server error", "err", err)
	writeError(w, http.StatusInternalServerError, "server_error", "服务器出错了")
}

// ---------------------------------------------------------------- params

func intParam(r *http.Request, name string, def int) int {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil {
		return def
	}
	return n
}

func int64Param(r *http.Request, name string, def int64) int64 {
	v := r.URL.Query().Get(name)
	if v == "" {
		return def
	}
	n, err := strconv.ParseInt(v, 10, 64)
	if err != nil {
		return def
	}
	return n
}
