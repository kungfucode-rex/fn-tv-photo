package api

import (
	"errors"
	"net/http"
	"net/url"
	"strings"
	"time"

	"tuping/internal/guard"
	"tuping/internal/store"
)

// commentJSON is the one comment shape every endpoint returns (§6.3). The TV's
// danmaku layer, the HTTP history call and the WebSocket push all use it, so the
// renderer has exactly one input to understand.
type commentJSON struct {
	ID        string `json:"id"`
	ImageID   string `json:"imageId,omitempty"`
	Nickname  string `json:"nickname"`
	Content   string `json:"content"`
	CreatedAt int64  `json:"createdAt"`
	Status    int    `json:"status"`
}

func toCommentJSON(c store.Comment) commentJSON {
	return commentJSON{
		ID:        c.ID,
		ImageID:   c.ImageID,
		Nickname:  c.Nickname,
		Content:   c.Content,
		CreatedAt: c.CreatedAt,
		Status:    c.Status,
	}
}

// handleComments is GET /v1/comments?imageId=…&since=…&limit=…
func (s *Server) handleComments(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.requireDevice(w, r); !ok {
		return
	}

	imageID := r.URL.Query().Get("imageId")
	if imageID == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 imageId")
		return
	}

	limit := intParam(r, "limit", 50)
	items, err := s.store.Comments(r.Context(), store.CommentQuery{
		ImageID: imageID,
		Since:   int64Param(r, "since", 0),
		Limit:   limit,
	})
	if err != nil {
		s.serverError(w, err)
		return
	}

	out := make([]commentJSON, 0, len(items))
	for _, c := range items {
		out = append(out, toCommentJSON(c))
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"serverTime": time.Now().UnixMilli(),
		"items":      out,
	})
}

// commentRequest is POST /v1/comments.
type commentRequest struct {
	Code     string `json:"code"`
	ImageID  string `json:"imageId"`
	Content  string `json:"content"`
	Nickname string `json:"nickname"`
	IdemKey  string `json:"idemKey"`
	AnonID   string `json:"anonId"`
	Client   string `json:"client"`
}

type commentResponse struct {
	ID        string `json:"id"`
	CreatedAt int64  `json:"createdAt"`
	Status    int    `json:"status"`
	// Message is what the page shows for status 0. The design is explicit that a
	// held comment must not be reported as a success (§6.4).
	Message string `json:"message,omitempty"`
}

func (s *Server) handleCommentCreate(w http.ResponseWriter, r *http.Request) {
	var req commentRequest
	if err := decodeJSON(r, &req); err != nil {
		writeError(w, http.StatusBadRequest, "bad_request", "请求读不出来")
		return
	}
	if req.ImageID == "" && req.Code == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少 code 或 imageId")
		return
	}

	imageID := req.ImageID
	scope := store.ScopeImage
	scopeID := ""
	if req.Code != "" {
		target, err := s.store.ResolveCode(r.Context(), req.Code)
		if err != nil {
			if errors.Is(err, store.ErrNotFound) {
				writeError(w, http.StatusNotFound, "not_found", "这个短码不认得了")
				return
			}
			s.serverError(w, err)
			return
		}
		imageID = target.Photo.ID
		scope = target.Scope
		scopeID = target.ScopeID
	}

	anon := s.anonID(r, req.AnonID)
	ip := s.hmacIP(clientIP(r))

	allowed, key, err := s.store.AllowAll(r.Context(),
		// The endpoint's own budget first, so a refusal that is the whole service's is not
		// also charged to a caller who did nothing to cause it.
		store.RateLimit{Key: "cmt-service:min", Limit: store.LimitPerService.Limit, Window: store.LimitPerService.Window},
		store.RateLimit{Key: "cmt-anon:" + anon + ":min", Limit: store.LimitPerAnon.Limit, Window: store.LimitPerAnon.Window},
		store.RateLimit{Key: "cmt-anon:" + anon + ":day", Limit: store.LimitPerAnonDay.Limit, Window: store.LimitPerAnonDay.Window},
		store.RateLimit{Key: "cmt-ip:" + ip + ":min", Limit: store.LimitPerIP.Limit, Window: store.LimitPerIP.Window},
		store.RateLimit{Key: "cmt-img:" + imageID + ":hour", Limit: store.LimitPerPhoto.Limit, Window: store.LimitPerPhoto.Window},
	)
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !allowed {
		s.log.Info("comment rate limited", "key", key, "imageId", imageID)
		writeError(w, http.StatusTooManyRequests, "rate_limited", "发得太快了，歇一会儿")
		return
	}

	if err := s.lockedForComments(r, imageID, w); err != nil {
		return
	}

	verdict := s.screen.Screen(req.Content)
	if verdict.Verdict == guard.Reject {
		s.log.Info("comment rejected", "reason", verdict.Reason, "matched", verdict.Matched, "imageId", imageID)
		writeError(w, http.StatusBadRequest, verdict.Reason, verdict.Detail)
		return
	}

	status := store.StatusVisible
	if verdict.Verdict == guard.Review {
		status = store.StatusPending
	}

	client := req.Client
	if client == "" {
		client = "h5/1"
	}

	comment, created, err := s.store.AddComment(r.Context(), store.NewComment{
		ImageID:  imageID,
		Scope:    scope,
		ScopeID:  scopeID,
		Content:  strings.TrimSpace(req.Content),
		Nickname: guard.NormalizeNickname(req.Nickname),
		AnonID:   anon,
		IPHMAC:   ip,
		Client:   client,
		IdemKey:  req.IdemKey,
		Status:   status,
	})
	if err != nil {
		s.serverError(w, err)
		return
	}

	if created && comment.Status == store.StatusVisible {
		s.publishComment(r.Context(), comment)
	}

	resp := commentResponse{ID: comment.ID, CreatedAt: comment.CreatedAt, Status: comment.Status}
	if comment.Status == store.StatusPending {
		resp.Message = "已提交，审核后显示"
	}
	writeJSON(w, http.StatusOK, resp)
}

// lockedForComments returns an error response when a photo has comments turned
// off. It writes the response itself so every caller cannot forget to.
func (s *Server) lockedForComments(r *http.Request, imageID string, w http.ResponseWriter) error {
	photo, err := s.store.PhotoByID(r.Context(), imageID)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这张照片不在图评里")
			return err
		}
		s.serverError(w, err)
		return err
	}
	switch photo.Visibility {
	case store.VisibilityDisabled:
		writeError(w, http.StatusForbidden, "photo_locked", "这张照片关闭了评论")
		return errors.New("photo locked")
	case store.VisibilityPrivate:
		writeError(w, http.StatusForbidden, "photo_locked", "这张照片的评论不公开")
		return errors.New("photo private")
	}
	return nil
}

// anonID is the H5 device identity: the cookie's value, hashed. Only the hash is
// ever stored, so the database cannot be used to follow one phone around (§12.6).
func (s *Server) anonID(r *http.Request, supplied string) string {
	if supplied != "" {
		return store.HashToken("anon", supplied)
	}
	if c, err := r.Cookie(anonCookie); err == nil && c.Value != "" {
		return store.HashToken("anon", c.Value)
	}
	return store.HashToken("anon", "ip:"+clientIP(r))
}

const anonCookie = "tuping_anon"

/*
nameCookie remembers the nickname a guest typed, so the form is filled in for them next
time. §10.1 has always said the page remembers it, and the page has no JavaScript at all —
a cookie is the only way that can be true. It is also what keeps the blank field, and
therefore the 匿名 default, from being the normal case for somebody who has already told
the page their name.

The value is percent-encoded because a nickname is usually Chinese: RFC 6265 cookie values
are ASCII, and net/http drops a value it cannot encode rather than failing loudly, so an
unencoded 小王 would produce a cookie with no value at all.
*/
const nameCookie = "tuping_name"

// setNameCookie remembers a nickname worth remembering: one the guest actually typed. A
// blank submission leaves whatever was there alone, so clearing the field and posting does
// not freeze the default into the box.
func setNameCookie(w http.ResponseWriter, r *http.Request, nickname string) {
	if nickname == "" {
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     nameCookie,
		Value:    url.QueryEscape(nickname),
		Path:     "/",
		MaxAge:   365 * 24 * 60 * 60,
		HttpOnly: true,
		Secure:   cookieSecure(r),
		SameSite: http.SameSiteLaxMode,
		// Secure only when the connection was: see cookieSecure for why both directions of
		// this flag are wrong on the deployment the other one belongs to.
	})
}

// rememberedNickname is the nickname setNameCookie stored, or "" when there is none.
//
// Normalized on the way out as well as in: a cookie is caller-supplied text like any other,
// and it lands in the page's `value` attribute.
func rememberedNickname(r *http.Request) string {
	c, err := r.Cookie(nameCookie)
	if err != nil || c.Value == "" {
		return ""
	}
	decoded, err := url.QueryUnescape(c.Value)
	if err != nil {
		return ""
	}
	return guard.NormalizeNickname(decoded)
}

// ensureAnonCookie gives a first-time visitor an identity, so rate limiting and
// "the comment I just wrote" have something stable to hang on.
func (s *Server) ensureAnonCookie(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(anonCookie); err == nil && c.Value != "" {
		return
	}
	http.SetCookie(w, &http.Cookie{
		Name:     anonCookie,
		Value:    randomToken(16),
		Path:     "/",
		MaxAge:   365 * 24 * 60 * 60,
		HttpOnly: true,
		Secure:   cookieSecure(r),
		SameSite: http.SameSiteLaxMode,
		// Secure only over TLS: on the plain-http LAN deployment a Secure cookie would simply
		// never be sent back, and the guest would get a new identity on every page view
		// (§10.1, §17.23).
	})
}

// handleCommentReport records a report. One press, no login (§10.1).
func (s *Server) handleCommentReport(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if id == "" {
		writeError(w, http.StatusBadRequest, "bad_request", "缺少评论 id")
		return
	}
	ip := s.hmacIP(clientIP(r))
	allowed, err := s.store.Allow(r.Context(), store.RateLimit{
		Key: "report:" + ip + ":hour", Limit: 30, Window: time.Hour,
	})
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !allowed {
		writeError(w, http.StatusTooManyRequests, "rate_limited", "举报得太频繁了")
		return
	}

	count, status, err := s.store.ReportComment(r.Context(), id)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这条评论不在了")
			return
		}
		s.serverError(w, err)
		return
	}
	s.log.Info("comment reported", "id", id, "count", count, "status", status)
	// The third report takes a visible comment out of the thread. A television that is
	// already showing this photo has that comment in its cache, so it is told — otherwise
	// it keeps replaying words a moderator has just taken down (§17.16).
	if status != store.StatusVisible {
		s.publishCommentByID(r.Context(), id)
	}
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "reportCount": count, "status": status})
}

// ------------------------------------------------------------------ admin

func (s *Server) requireAdmin(w http.ResponseWriter, r *http.Request) bool {
	if s.cfg.AdminToken == "" {
		writeError(w, http.StatusNotFound, "not_found", "没有管理员接口")
		return false
	}
	token := r.Header.Get("X-Admin-Token")
	if token == "" {
		token = r.URL.Query().Get("token")
	}
	if constantTimeEquals(token, s.cfg.AdminToken) {
		return true
	}
	// The browser's door (§17.15): an admin signed in on the H5 page can open the
	// moderation endpoints from that same browser. The header and ?token= paths stay for
	// the TV and for curl, and all three arrive at the same data.
	if s.isAdmin(r) {
		return true
	}
	writeError(w, http.StatusUnauthorized, "unauthorized", "管理员令牌不对")
	return false
}

func (s *Server) handleAdminList(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	items, err := s.store.ModerationQueue(r.Context(), intParam(r, "limit", 100))
	if err != nil {
		s.serverError(w, err)
		return
	}
	// The queue is the one place an operator sees raw content, so the IP hash is
	// included here and nowhere else.
	out := make([]commentJSON, 0, len(items))
	for _, c := range items {
		out = append(out, toCommentJSON(c))
	}
	writeJSON(w, http.StatusOK, map[string]any{"items": out})
}

func (s *Server) handleAdminDecide(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdmin(w, r) {
		return
	}
	id := r.PathValue("id")
	action := r.URL.Query().Get("action")
	if action == "" {
		action = r.FormValue("action")
	}

	status := store.StatusVisible
	switch action {
	case "approve", "":
		status = store.StatusVisible
	case "block":
		status = store.StatusBlocked
	case "delete":
		status = store.StatusDeleted
	default:
		writeError(w, http.StatusBadRequest, "bad_request", "action 只能是 approve / block / delete")
		return
	}

	if err := s.store.SetCommentStatus(r.Context(), id, status); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "这条评论不在了")
			return
		}
		s.serverError(w, err)
		return
	}
	s.log.Info("moderation decision", "id", id, "status", status)
	// Approving, blocking and deleting all change what a television may draw with this
	// comment, so all three say so on the socket (§17.16). Approving matters as much as
	// deleting: a held comment that is let through has to start appearing.
	s.publishCommentByID(r.Context(), id)
	writeJSON(w, http.StatusOK, map[string]any{"id": id, "status": status})
}
