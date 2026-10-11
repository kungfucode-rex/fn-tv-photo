package api

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"tuping/internal/guard"
	"tuping/internal/store"
)

/*
The admin door on the web page (docs/tuping-design.md §17.15).

§7 has always listed 管理员令牌 as an identity, but the only way in was a header or a
`?token=` parameter, which means curl. This is the same token behind a door a browser can
use: one login page, one signed cookie, and 编辑 / 删除 on the comment page itself.

Three properties are load-bearing and worth stating before the code:

  - **The page is still script-free.** Login is a plain form POST, editing is a plain form
    POST, deleting is a plain form POST, and every one of them answers 303 back to a GET
    (§10.1). Nothing here needs a line of JavaScript, and nothing here may add one.
  - **The session is a signed cookie, not a session table.** There is exactly one admin,
    a restart must not sign them out, and a cookie whose MAC covers its own expiry cannot
    be edited into a longer one. The MAC key is derived from the secret *and* the token, so
    rotating TUPING_ADMIN_TOKEN logs every existing session out.
  - **SameSite=Lax is the CSRF answer.** A browser does not attach a Lax cookie to a
    cross-site POST, and every mutating route here is POST — so a stranger's page can
    neither delete a comment nor log the admin out with an <img>. That is also why 退出 is
    a form button rather than a link: a link would be a GET.
*/

const (
	// adminCookie carries the admin session. Named alongside the guest cookies.
	adminCookie = "tuping_admin"

	// adminSessionTTL is how long one login lasts. Long enough for an evening of
	// tidying up a party album on a phone, short enough that a phone left on a table
	// is not a permanent key to the moderation endpoints.
	adminSessionTTL = 12 * time.Hour

	// adminLoginLimit is how many attempts one address gets per window. The token is a
	// long random string (§11.5), so this is not what makes guessing hopeless — it is
	// what keeps a broken script from filling the log and the rate-limit table.
	adminLoginLimit  = 20
	adminLoginWindow = 10 * time.Minute
)

// adminConfigured reports whether this instance has an admin token at all. With none set,
// every admin surface answers 404 instead of offering a login that cannot succeed — and
// the comment page does not show the footer link either.
func (s *Server) adminConfigured() bool { return s.cfg.AdminToken != "" }

// adminSession is the cookie value: the expiry it was minted with, and a MAC over exactly
// that number. The expiry travels in the clear because it is not a secret — changing it
// invalidates the MAC.
func (s *Server) adminSession(exp int64) string {
	mac := hmac.New(sha256.New, s.adminSecret)
	fmt.Fprintf(mac, "%d", exp)
	return strconv.FormatInt(exp, 10) + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)[:16])
}

// isAdmin reports whether this request carries a valid, unexpired admin session.
func (s *Server) isAdmin(r *http.Request) bool {
	if !s.adminConfigured() {
		return false
	}
	c, err := r.Cookie(adminCookie)
	if err != nil || c.Value == "" {
		return false
	}
	expPart, _, ok := strings.Cut(c.Value, ".")
	if !ok {
		return false
	}
	exp, err := strconv.ParseInt(expPart, 10, 64)
	if err != nil || time.Now().UnixMilli() > exp {
		return false
	}
	return constantTimeEquals(s.adminSession(exp), c.Value)
}

func (s *Server) setAdminCookie(w http.ResponseWriter) {
	exp := time.Now().Add(adminSessionTTL).UnixMilli()
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    s.adminSession(exp),
		Path:     "/",
		MaxAge:   int(adminSessionTTL.Seconds()),
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
		// Not Secure, for the same reason the guest cookies are not: this page is served
		// over plain http on a LAN and a Secure cookie would never come back (§10.1).
	})
}

func (s *Server) clearAdminCookie(w http.ResponseWriter) {
	http.SetCookie(w, &http.Cookie{
		Name:     adminCookie,
		Value:    "",
		Path:     "/",
		MaxAge:   -1,
		HttpOnly: true,
		SameSite: http.SameSiteLaxMode,
	})
}

// safeNext keeps the post-login redirect on this site.
//
// This is the one page in the whole service where somebody types a secret, and an open
// redirect there is the classic way to collect it: the link says `/admin?next=…`, the
// person types the token, and the browser is handed to whatever page the link's author
// chose at exactly the moment they expect to be back on their own photo. Only a path is
// accepted — `//evil.example` is a host, not a path, and that is the whole trick — and a
// CR/LF is refused because it lands in a Location header.
func safeNext(next string) string {
	if next == "" {
		return ""
	}
	if !strings.HasPrefix(next, "/") || strings.HasPrefix(next, "//") || strings.HasPrefix(next, "/\\") {
		return ""
	}
	if strings.ContainsAny(next, "\r\n") {
		return ""
	}
	return next
}

// adminLoginData is the login page. One field beyond the notice, because the only thing
// this page has to remember is where the person was aiming.
type adminLoginData struct {
	Next       string
	LoggedIn   bool
	Notice     string
	NoticeKind string // "ok" or "bad"
}

// handleAdminLoginPage is GET /admin.
func (s *Server) handleAdminLoginPage(w http.ResponseWriter, r *http.Request) {
	if !s.adminConfigured() {
		s.renderError(w, http.StatusNotFound, "没有管理员接口",
			"这个实例没有设置 TUPING_ADMIN_TOKEN，所以没有可以登录的管理员。")
		return
	}
	s.renderAdminLogin(w, http.StatusOK, adminLoginData{
		Next:     safeNext(r.URL.Query().Get("next")),
		LoggedIn: s.isAdmin(r),
	})
}

// handleAdminLogin is POST /admin.
func (s *Server) handleAdminLogin(w http.ResponseWriter, r *http.Request) {
	if !s.adminConfigured() {
		s.renderError(w, http.StatusNotFound, "没有管理员接口",
			"这个实例没有设置 TUPING_ADMIN_TOKEN，所以没有可以登录的管理员。")
		return
	}
	if err := r.ParseForm(); err != nil {
		s.renderError(w, http.StatusBadRequest, "提交读不出来", "再试一次。")
		return
	}
	next := safeNext(r.PostFormValue("next"))
	ip := s.hmacIP(clientIP(r))

	allowed, err := s.store.Allow(r.Context(), store.RateLimit{
		Key: "admin-login:" + ip, Limit: adminLoginLimit, Window: adminLoginWindow,
	})
	if err != nil {
		s.serverError(w, err)
		return
	}
	if !allowed {
		s.log.Warn("admin login rate limited", "ip", ip)
		s.renderAdminLogin(w, http.StatusTooManyRequests, adminLoginData{
			Next: next, Notice: "试得太频繁了，过一会儿再来", NoticeKind: "bad",
		})
		return
	}

	// Trimmed, because a token pasted out of a compose file arrives with a newline on it,
	// and refusing that would read as "the token is wrong" — which is the one message
	// that must never be wrong.
	if !constantTimeEquals(strings.TrimSpace(r.PostFormValue("token")), s.cfg.AdminToken) {
		s.log.Warn("admin login refused", "ip", ip)
		s.renderAdminLogin(w, http.StatusUnauthorized, adminLoginData{
			Next: next, Notice: "令牌不对", NoticeKind: "bad",
		})
		return
	}

	s.log.Info("admin logged in", "ip", ip)
	s.setAdminCookie(w)
	if next == "" {
		// /admin itself, which now says 已经登录了 and offers the way back out. Bouncing
		// to /healthz would answer a login with a JSON document.
		next = "/admin"
	}
	http.Redirect(w, r, next, http.StatusSeeOther)
}

// handleAdminLogout is POST /admin/logout. POST and not GET: a GET logout is something a
// stray <img> on another page can trigger, and it is also what a browser prefetches.
func (s *Server) handleAdminLogout(w http.ResponseWriter, r *http.Request) {
	s.clearAdminCookie(w)
	s.log.Info("admin logged out", "ip", s.hmacIP(clientIP(r)))
	http.Redirect(w, r, "/admin", http.StatusSeeOther)
}

func (s *Server) renderAdminLogin(w http.ResponseWriter, status int, data adminLoginData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	if err := s.tmpl.ExecuteTemplate(w, "login.html", data); err != nil {
		s.log.Error("render admin login", "err", err)
	}
}

// requireAdminPage is the browser's version of requireAdmin: a session that is missing or
// expired is sent to the login page, with this same URL as `next`, so the person lands
// back where they were aiming.
//
// requireAdmin (header, ?token=, or the same cookie) is untouched: the TV and an
// operator's curl calls use that door, and both lead to the same moderation data.
func (s *Server) requireAdminPage(w http.ResponseWriter, r *http.Request) bool {
	if s.isAdmin(r) {
		return true
	}
	if !s.adminConfigured() {
		s.renderError(w, http.StatusNotFound, "没有管理员接口",
			"这个实例没有设置 TUPING_ADMIN_TOKEN，所以没有可以登录的管理员。")
		return false
	}
	next := r.URL.Path
	if r.URL.RawQuery != "" {
		next += "?" + r.URL.RawQuery
	}
	http.Redirect(w, r, "/admin?next="+url.QueryEscape(next), http.StatusSeeOther)
	return false
}

// commentTarget resolves {code} + {id} for an admin action, and answers the error itself
// when it cannot.
//
// The id has to be a comment on *this* code's photo. The moderation endpoints take any id
// — that is the global delete §7 promises — but a URL that reads /c/{code}/edit/{id} must
// not edit a comment from another photo, because the page it redirects back to would then
// be showing somebody else's thread.
func (s *Server) commentTarget(w http.ResponseWriter, r *http.Request) (*store.CodeTarget, *store.Comment, bool) {
	code := r.PathValue("code")
	if !store.ValidCode(code) {
		s.renderError(w, http.StatusNotFound, "短码无效", "二维码可能不完整，回到电视上再扫一次。")
		return nil, nil, false
	}

	target, err := s.store.ResolveCode(r.Context(), code)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这张照片不在图评里", "可能它已经被删除了。")
			return nil, nil, false
		}
		s.serverError(w, err)
		return nil, nil, false
	}

	comment, err := s.store.Comment(r.Context(), r.PathValue("id"))
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这条评论不在了", "可能它已经被删除了。")
			return nil, nil, false
		}
		s.serverError(w, err)
		return nil, nil, false
	}

	// Membership, not equality: a comment written before a merge still belongs to the
	// photo it was merged into, and this page lists exactly those (§3.4).
	group, err := s.store.GroupIDs(r.Context(), target.ImageID)
	if err != nil {
		s.serverError(w, err)
		return nil, nil, false
	}
	for _, g := range group {
		if comment.ImageID == g {
			return target, comment, true
		}
	}
	s.renderError(w, http.StatusNotFound, "这条评论不属于这张照片", "地址里的评论 id 和短码对不上。")
	return nil, nil, false
}

// adminEditData is the edit page.
type adminEditData struct {
	Code       string
	ID         string
	Nickname   string
	Content    string
	When       string
	Notice     string
	NoticeKind string // "ok" or "bad"
}

// handleCommentEditPage is GET /c/{code}/edit/{id}.
func (s *Server) handleCommentEditPage(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminPage(w, r) {
		return
	}
	target, comment, ok := s.commentTarget(w, r)
	if !ok {
		return
	}
	s.renderCommentEdit(w, http.StatusOK, target, comment, adminEditData{
		Nickname: comment.Nickname,
		Content:  comment.Content,
	})
}

// handleCommentEdit is POST /c/{code}/edit/{id}.
//
// The new text is not put through the screen. Screening exists to decide whether a
// stranger's words may be published, and the person typing here is the one that queue
// escalates to — a moderation tool that refused the moderator's own correction would be
// useless. What is still enforced is the length: 20 runes is what a television can draw
// on one danmaku line (§9.5), and an edit that broke it would produce a comment nobody
// can read at the place it is going.
func (s *Server) handleCommentEdit(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminPage(w, r) {
		return
	}
	target, comment, ok := s.commentTarget(w, r)
	if !ok {
		return
	}
	if err := r.ParseForm(); err != nil {
		s.renderError(w, http.StatusBadRequest, "提交读不出来", "再试一次。")
		return
	}

	nickname := guard.NormalizeNickname(r.PostFormValue("nickname"))
	content := strings.TrimSpace(r.PostFormValue("content"))

	// Re-render with what the caller typed: the same rule as the guest form (§10.1).
	fail := func(status int, notice string) {
		s.renderCommentEdit(w, status, target, comment, adminEditData{
			Nickname: nickname, Content: content,
			Notice: notice, NoticeKind: "bad",
		})
	}

	switch {
	case content == "":
		fail(http.StatusBadRequest, "说点什么吧")
		return
	case utf8.RuneCountInString(content) > guard.MaxContentRunes:
		fail(http.StatusBadRequest, fmt.Sprintf("最多 %d 个字，现在有 %d 个",
			guard.MaxContentRunes, utf8.RuneCountInString(content)))
		return
	}

	if err := s.store.SetCommentContent(r.Context(), comment.ID, content, nickname); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这条评论不在了", "可能它刚刚被别的地方删掉了。")
			return
		}
		s.serverError(w, err)
		return
	}
	s.log.Info("comment edited by admin", "id", comment.ID, "imageId", comment.ImageID)

	// The television is told the new words on the same socket a new comment arrives on.
	// Without this it would keep replaying the text that was just corrected, because its
	// history fetch is `since = the newest it has` and an edit does not move that (§17.16).
	s.publishCommentByID(r.Context(), comment.ID)

	// PRG again, and `mine` points at the comment that just changed, so the page says
	// which one it was rather than only 已保存.
	http.Redirect(w, r, fmt.Sprintf("/c/%s?ok=edited&mine=%s", target.Code, comment.ID), http.StatusSeeOther)
}

// handleCommentDelete is POST /c/{code}/delete/{id} — the admin's 删除.
//
// A soft delete (status=3), the same decision POST /v1/admin/comments/{id}?action=delete
// makes, so every reader of "is this comment gone" keeps one answer: it leaves the page,
// the danmaku history and the photo's comment count, and the row stays in the database
// where the operator can put it back.
func (s *Server) handleCommentDelete(w http.ResponseWriter, r *http.Request) {
	if !s.requireAdminPage(w, r) {
		return
	}
	target, comment, ok := s.commentTarget(w, r)
	if !ok {
		return
	}
	if err := s.store.SetCommentStatus(r.Context(), comment.ID, store.StatusDeleted); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			s.renderError(w, http.StatusNotFound, "这条评论不在了", "可能它已经被删除了。")
			return
		}
		s.serverError(w, err)
		return
	}
	s.log.Info("comment deleted by admin", "id", comment.ID, "imageId", comment.ImageID)

	// **This is the line that makes a comment leave a television** (§17.16). The row is
	// only soft-deleted, and the client's cached copy is what the danmaku layer replays;
	// until it is told, the deleted comment keeps crossing the photo it was written about.
	s.publishCommentByID(r.Context(), comment.ID)
	http.Redirect(w, r, "/c/"+target.Code+"?ok=deleted", http.StatusSeeOther)
}

func (s *Server) renderCommentEdit(w http.ResponseWriter, status int, target *store.CodeTarget, comment *store.Comment, data adminEditData) {
	data.Code = target.Code
	data.ID = comment.ID
	data.When = relativeTime(time.Now(), time.UnixMilli(comment.CreatedAt))

	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	if err := s.tmpl.ExecuteTemplate(w, "edit.html", data); err != nil {
		s.log.Error("render comment edit", "err", err)
	}
}
