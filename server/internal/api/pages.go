package api

import (
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strings"
	"time"

	"tuping/internal/guard"
	"tuping/internal/phash"
	"tuping/internal/store"
)

// pageData is the H5 page. Every field is computed here, at the moment the
// response is built: the page has no script, so "3 分钟前" has to be decided by
// the server (§10.1).
type pageData struct {
	Code         string
	ThumbURL     string
	ThumbWidth   int
	ThumbHeight  int
	CommentCount int
	Comments     []pageComment
	Nickname     string
	Content      string
	IdemKey      string
	Notice       string
	NoticeKind   string // "ok" or "bad"

	// Admin is whether this request carries an admin session, and AdminAvailable
	// whether this instance has an admin token at all. The two are separate so a
	// deployment without TUPING_ADMIN_TOKEN does not advertise a login that can only
	// fail (§17.15).
	Admin          bool
	AdminAvailable bool
	// AdminLoginURL is where the footer's 管理员登录 link goes: the login page, told
	// to come back to this photo. Safe to put in the page as it is built here — the
	// next parameter is a path this server just made up.
	AdminLoginURL string
}

type pageComment struct {
	ID       string
	Nickname string
	Content  string
	When     string
	Mine     bool
}

// recentForPage is how many comments the H5 page lists.
const recentForPage = 20

// handlePage is GET /c/{code}: thumbnail, existing comments and the form, in one
// response, with no external resource of any kind.
func (s *Server) handlePage(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !store.ValidCode(code) {
		s.renderError(w, http.StatusNotFound, "短码无效", "二维码可能不完整，回到电视上再扫一次。")
		return
	}

	s.ensureAnonCookie(w, r)

	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这张照片不在图评里",
				"可能是它已经被删除了，或者电视连的是另一个图评服务器。")
			return
		}
		s.serverError(w, err)
		return
	}

	data, err := s.buildPage(r, target, "")
	if err != nil {
		s.serverError(w, err)
		return
	}
	// The name they used last time, so the field is not blank for a returning guest —
	// which is also the only reason the 匿名 default is ever the answer.
	data.Nickname = rememberedNickname(r)

	switch {
	case r.URL.Query().Get("ok") == "1":
		data.Notice = "已提交"
		data.NoticeKind = "ok"
	case r.URL.Query().Get("ok") == "held":
		// §6.4: a held comment is reported as held, not as shown.
		data.Notice = "已提交，审核后显示"
		data.NoticeKind = "ok"
	case r.URL.Query().Get("ok") == "edited":
		data.Notice = "已保存"
		data.NoticeKind = "ok"
	case r.URL.Query().Get("ok") == "deleted":
		data.Notice = "已删除"
		data.NoticeKind = "ok"
	}
	data.Comments = markMine(data.Comments, r.URL.Query().Get("mine"))

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	if err := s.tmpl.ExecuteTemplate(w, "tuping.html", data); err != nil {
		s.log.Error("render page", "err", err)
	}
}

// handlePageSubmit is POST /c/{code} — plain form, 303 back to the page.
//
// The redirect is the whole of the "submitted twice" defence on the client side:
// a refresh re-runs a GET, never the POST (§10.1).
func (s *Server) handlePageSubmit(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	if !store.ValidCode(code) {
		s.renderError(w, http.StatusNotFound, "短码无效", "二维码可能不完整，回到电视上再扫一次。")
		return
	}
	if err := r.ParseForm(); err != nil {
		s.renderError(w, http.StatusBadRequest, "提交读不出来", "再试一次。")
		return
	}

	s.ensureAnonCookie(w, r)

	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这张照片不在图评里", "可能它已经被删除了。")
			return
		}
		s.serverError(w, err)
		return
	}

	content := strings.TrimSpace(r.PostFormValue("content"))
	nickname := strings.TrimSpace(r.PostFormValue("nickname"))
	idem := strings.TrimSpace(r.PostFormValue("idem"))
	if idem == "" {
		idem = randomToken(12)
	}

	// Re-render with what the caller typed when anything goes wrong. Keeping the
	// text is the difference between "too fast, try again" and retyping 80
	// characters with a phone keyboard (§10.1).
	fail := func(status int, notice string) {
		data, err := s.buildPage(r, target, content)
		if err != nil {
			s.serverError(w, err)
			return
		}
		data.Nickname = nickname
		data.IdemKey = idem
		data.Notice = notice
		data.NoticeKind = "bad"
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Header().Set("Cache-Control", "no-store")
		w.WriteHeader(status)
		if err := s.tmpl.ExecuteTemplate(w, "tuping.html", data); err != nil {
			s.log.Error("render page", "err", err)
		}
	}

	anon := s.anonID(r, "")
	ip := s.hmacIP(clientIP(r))

	allowed, _, err := s.store.AllowAll(r.Context(),
		store.RateLimit{Key: "cmt-anon:" + anon + ":min", Limit: store.LimitPerAnon.Limit, Window: store.LimitPerAnon.Window},
		store.RateLimit{Key: "cmt-anon:" + anon + ":day", Limit: store.LimitPerAnonDay.Limit, Window: store.LimitPerAnonDay.Window},
		store.RateLimit{Key: "cmt-ip:" + ip + ":min", Limit: store.LimitPerIP.Limit, Window: store.LimitPerIP.Window},
		store.RateLimit{Key: "cmt-img:" + target.ImageID + ":hour", Limit: store.LimitPerPhoto.Limit, Window: store.LimitPerPhoto.Window},
	)
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !allowed {
		fail(http.StatusTooManyRequests, "发得太快了，歇一会儿再发")
		return
	}

	if target.Photo.Visibility == store.VisibilityDisabled {
		fail(http.StatusForbidden, "这张照片关闭了评论")
		return
	}

	verdict := s.screen.Screen(content)
	if verdict.Verdict == guard.Reject {
		s.log.Info("comment rejected", "reason", verdict.Reason, "matched", verdict.Matched, "imageId", target.ImageID)
		fail(http.StatusBadRequest, verdict.Detail)
		return
	}

	status := store.StatusVisible
	if verdict.Verdict == guard.Review {
		status = store.StatusPending
	}

	comment, created, err := s.store.AddComment(r.Context(), store.NewComment{
		ImageID:  target.ImageID,
		Scope:    target.Scope,
		ScopeID:  target.ScopeID,
		Content:  content,
		Nickname: guard.NormalizeNickname(nickname),
		AnonID:   anon,
		IPHMAC:   ip,
		Client:   "h5/1",
		IdemKey:  idem,
		Status:   status,
	})
	if err != nil {
		s.serverError(w, err)
		return
	}

	if created && comment.Status == store.StatusVisible {
		s.publishComment(r.Context(), comment)
	}

	// PRG: the browser is sent back to a GET, so a refresh cannot resubmit.
	outcome := "ok=1"
	if comment.Status == store.StatusPending {
		outcome = "ok=held"
	}
	// Remember the name for next time, before the redirect writes its own headers.
	setNameCookie(w, nickname)
	http.Redirect(w, r, fmt.Sprintf("/c/%s?%s&mine=%s", code, outcome, comment.ID), http.StatusSeeOther)
}

// handlePageReport is the one-press 举报 link.
func (s *Server) handlePageReport(w http.ResponseWriter, r *http.Request) {
	code := r.PathValue("code")
	id := r.PathValue("id")

	ip := s.hmacIP(clientIP(r))
	if allowed, err := s.store.Allow(r.Context(), store.RateLimit{
		Key: "report:" + ip + ":hour", Limit: 30, Window: time.Hour,
	}); err != nil {
		s.serverError(w, err)
		return
	} else if !allowed {
		s.renderError(w, http.StatusTooManyRequests, "举报得太频繁了", "过一会儿再试。")
		return
	}

	if _, status, err := s.store.ReportComment(r.Context(), id); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这条评论不在了", "可能是它已经被删除了。")
			return
		}
		s.serverError(w, err)
		return
	} else if status != store.StatusVisible {
		// The third report holds the comment. This route has no JSON answer to carry that
		// news, so the socket is the only way the television showing this photo hears it
		// before it re-reads the history (§17.16).
		s.publishCommentByID(r.Context(), id)
	}
	http.Redirect(w, r, "/c/"+code+"?reported=1", http.StatusSeeOther)
}

func (s *Server) buildPage(r *http.Request, target *store.CodeTarget, content string) (*pageData, error) {
	items, err := s.store.RecentComments(r.Context(), target.ImageID, recentForPage)
	if err != nil {
		return nil, err
	}
	now := time.Now()
	comments := make([]pageComment, 0, len(items))
	for _, c := range items {
		comments = append(comments, pageComment{
			ID:       c.ID,
			Nickname: c.Nickname,
			Content:  c.Content,
			When:     relativeTime(now, time.UnixMilli(c.CreatedAt)),
		})
	}

	data := &pageData{
		Code:         target.Code,
		CommentCount: target.Photo.CommentCount,
		Comments:     comments,
		Content:      content,
		IdemKey:      randomToken(12),
		ThumbWidth:   target.Photo.Width,
		ThumbHeight:  target.Photo.Height,

		Admin:          s.isAdmin(r),
		AdminAvailable: s.adminConfigured(),
		AdminLoginURL:  "/admin?next=" + url.QueryEscape("/c/"+target.Code),
	}
	if target.Photo.ThumbKey != "" {
		// Passed the target so the picture is fetched from the same address the page was
		// opened on. A page whose own <img> points at a different host is a broken image.
		// With no address at all (no device report, no pinned value, a loopback Host) this
		// comes out relative — "/t/<code>?…" — which the browser resolves against the page's
		// own origin and therefore loads anyway (§17.20).
		data.ThumbURL = s.ThumbURL(target.Code, phash.CanonicalWidth, target, s.callerBase(r))
	}
	return data, nil
}

func markMine(comments []pageComment, id string) []pageComment {
	if id == "" {
		return comments
	}
	for i := range comments {
		if comments[i].ID == id {
			comments[i].Mine = true
		}
	}
	return comments
}

// relativeTime is the server's answer to "how long ago", because the page has no
// script to compute it. Past 24 hours it gives a date (§10.1).
func relativeTime(now, then time.Time) string {
	delta := now.Sub(then)
	switch {
	case delta < time.Minute:
		return "刚刚"
	case delta < time.Hour:
		return fmt.Sprintf("%d 分钟前", int(delta.Minutes()))
	case delta < 24*time.Hour:
		return fmt.Sprintf("%d 小时前", int(delta.Hours()))
	case now.Year() == then.Year():
		return then.Format("1月2日")
	default:
		return then.Format("2006年1月2日")
	}
}

func (s *Server) renderError(w http.ResponseWriter, status int, title, detail string) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.WriteHeader(status)
	err := s.tmpl.ExecuteTemplate(w, "error.html", map[string]any{
		"Title":  title,
		"Detail": detail,
	})
	if err != nil {
		s.log.Error("render error page", "err", err)
	}
}
