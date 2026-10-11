package api

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"image"
	"image/color"
	"image/jpeg"
	"io"
	"log/slog"
	"math/rand"
	"mime/multipart"
	"net"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/coder/websocket/wsjson"

	"tuping/internal/guard"
	"tuping/internal/qr"
	"tuping/internal/store"
	"tuping/internal/thumbs"
)

// harness is one running server with a real database on disk and a real
// thumbnail directory, driven through its real router. Nothing here stubs the
// HTTP layer: a test that called the handlers directly would not be checking the
// routing table, the multipart parsing or the redirects.
type harness struct {
	t      *testing.T
	server *Server
	http   *httptest.Server
	store  *store.Store
	thumbs *thumbs.Store
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	return newHarnessWith(t, nil)
}

// newHarnessWith is [newHarness] with a chance to change the configuration first — the
// only way to test what this server does when a knob is *not* set, which for the admin
// token is a whole behaviour (no login link, 404 instead of a login form).
func newHarnessWith(t *testing.T, mutate func(*Config)) *harness {
	t.Helper()
	dir := t.TempDir()

	st, err := store.Open(store.Options{Path: filepath.Join(dir, "tuping.db")})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	thumbStore, err := thumbs.Open(filepath.Join(dir, "thumbs"))
	if err != nil {
		t.Fatalf("open thumbs: %v", err)
	}

	cfg := Config{
		BaseURL:                "http://127.0.0.1:8080",
		Secret:                 "test-secret-that-is-long-enough",
		AdminToken:             "admin-token",
		DeviceRegistrationOpen: true,
		Version:                "test",
	}
	if mutate != nil {
		mutate(&cfg)
	}

	server, err := New(Options{
		Config: cfg,
		Store:  st,
		Thumbs: thumbStore,
		Logger: slog.New(slog.NewTextHandler(io.Discard, nil)),
		QR:     qr.New(8),
	})
	if err != nil {
		t.Fatalf("api.New: %v", err)
	}

	ts := httptest.NewServer(server.Handler())
	t.Cleanup(func() {
		ts.Close()
		st.Close()
	})

	return &harness{t: t, server: server, http: ts, store: st, thumbs: thumbStore}
}

// issueDevice is the first thing any album does.
func (h *harness) issueDevice(name string) (deviceID, deviceToken, ownerToken string) {
	h.t.Helper()
	body, _ := json.Marshal(map[string]string{"name": name})
	resp := h.do(http.MethodPost, "/v1/devices", bytes.NewReader(body), map[string]string{
		"Content-Type": "application/json",
	})
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		h.t.Fatalf("device registration: %d %s", resp.StatusCode, readAll(resp.Body))
	}
	var out struct {
		DeviceID    string `json:"deviceId"`
		DeviceToken string `json:"deviceToken"`
		OwnerToken  string `json:"ownerToken"`
	}
	decode(h.t, resp.Body, &out)
	return out.DeviceID, out.DeviceToken, out.OwnerToken
}

func (h *harness) do(method, path string, body io.Reader, headers map[string]string) *http.Response {
	h.t.Helper()
	return h.doWith(h.http.Client(), method, path, body, headers)
}

// doNoRedirect is for the endpoints whose whole contract is a 303: following the
// redirect would turn every assertion into "the page it landed on looked fine".
func (h *harness) doNoRedirect(method, path string, body io.Reader, headers map[string]string) *http.Response {
	h.t.Helper()
	client := &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
	return h.doWith(client, method, path, body, headers)
}

func (h *harness) doWith(client *http.Client, method, path string, body io.Reader, headers map[string]string) *http.Response {
	h.t.Helper()
	req, err := http.NewRequest(method, h.http.URL+path, body)
	if err != nil {
		h.t.Fatalf("request: %v", err)
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := client.Do(req)
	if err != nil {
		h.t.Fatalf("%s %s: %v", method, path, err)
	}
	return resp
}

// postForm is a browser submitting a form.
func (h *harness) postForm(client *http.Client, path string, form url.Values) *http.Response {
	h.t.Helper()
	return h.doWith(client, http.MethodPost, path, strings.NewReader(form.Encode()), map[string]string{
		"Content-Type": "application/x-www-form-urlencoded",
	})
}

// text reads a response all the way and closes it, which is what most page assertions want.
func (h *harness) text(client *http.Client, method, path string) (int, string) {
	h.t.Helper()
	resp := h.doWith(client, method, path, nil, nil)
	defer resp.Body.Close()
	return resp.StatusCode, readAll(resp.Body)
}

// browser is a client that keeps its cookies and does not follow redirects. A login is a
// 303 and the whole feature is "the cookie comes back on the next request", so a harness
// that invented the Cookie header itself would be testing the test.
func (h *harness) browser() *http.Client {
	h.t.Helper()
	jar, err := cookiejar.New(nil)
	if err != nil {
		h.t.Fatalf("cookie jar: %v", err)
	}
	client := noRedirect()
	client.Jar = jar
	return client
}

// noRedirect is a client that stops at the first response. Redirects are part of the
// contract on every page the admin touches — PRG after a submit, the login bounce, and
// "this admin URL without a session" — so a client that followed them would turn each of
// those assertions into "the page it landed on looked fine".
func noRedirect() *http.Client {
	return &http.Client{
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// browserWith is a browser carrying one hand-made admin cookie, for the tampering checks.
func (h *harness) browserWith(value string) *http.Client {
	h.t.Helper()
	client := h.browser()
	u, err := url.Parse(h.http.URL)
	if err != nil {
		h.t.Fatal(err)
	}
	client.Jar.SetCookies(u, []*http.Cookie{{Name: adminCookie, Value: value, Path: "/"}})
	return client
}

// liveSocket opens a device-authenticated live socket for one photo.
//
// The short sleep is the subscription being registered: the server subscribes
// synchronously on accept, so a moment is enough, and the alternative — polling the hub —
// would be reaching into the server's guts.
func (h *harness) liveSocket(t *testing.T, ctx context.Context, imageID, deviceID, deviceToken string) *websocket.Conn {
	t.Helper()
	wsURL := "ws" + strings.TrimPrefix(h.http.URL, "http") + "/v1/live/" + imageID
	conn, _, err := websocket.Dial(ctx, wsURL, &websocket.DialOptions{
		HTTPHeader: http.Header{
			"X-Device-Id":    []string{deviceID},
			"X-Device-Token": []string{deviceToken},
		},
	})
	if err != nil {
		t.Fatalf("dial live socket: %v", err)
	}
	t.Cleanup(func() { conn.CloseNow() })
	time.Sleep(150 * time.Millisecond)
	return conn
}

// readLive reads the next push, failing the test if none arrives in time.
func readLive(t *testing.T, ctx context.Context, conn *websocket.Conn) liveMessage {
	t.Helper()
	readCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	var msg liveMessage
	if err := wsjson.Read(readCtx, conn, &msg); err != nil {
		t.Fatalf("reading the push: %v", err)
	}
	return msg
}

// registerPhoto posts a multipart registration exactly as an album would.
func (h *harness) registerPhoto(deviceID, deviceToken, ownerToken, localID string, raw []byte, sha string) photoResponse {
	h.t.Helper()
	return h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, localID, raw, sha, "")
}

// registerPhotoWithBase is [registerPhoto] with the device's own base URL included, which
// is how a real album tells the server what address its QR codes should use.
func (h *harness) registerPhotoWithBase(deviceID, deviceToken, ownerToken, localID string, raw []byte, sha, baseURL string) photoResponse {
	h.t.Helper()

	var buf bytes.Buffer
	w := multipart.NewWriter(&buf)
	meta := map[string]any{
		"localId": localID, "source": "fnos", "sha256Original": sha,
	}
	if baseURL != "" {
		meta["baseUrl"] = baseURL
	}
	metaJSON, _ := json.Marshal(meta)
	if err := w.WriteField("meta", string(metaJSON)); err != nil {
		h.t.Fatal(err)
	}
	part, err := w.CreateFormFile("thumb", "thumb.jpg")
	if err != nil {
		h.t.Fatal(err)
	}
	if _, err := part.Write(raw); err != nil {
		h.t.Fatal(err)
	}
	w.Close()

	resp := h.do(http.MethodPost, "/v1/photos", &buf, map[string]string{
		"Content-Type":   w.FormDataContentType(),
		"X-Device-Id":    deviceID,
		"X-Device-Token": deviceToken,
		"X-Owner-Token":  ownerToken,
	})
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		h.t.Fatalf("register photo: %d %s", resp.StatusCode, readAll(resp.Body))
	}
	var out photoResponse
	decode(h.t, resp.Body, &out)
	return out
}

func (h *harness) postComment(code, content, nickname, idem string) *http.Response {
	h.t.Helper()
	body, _ := json.Marshal(map[string]string{
		"code": code, "content": content, "nickname": nickname,
		"idemKey": idem, "client": "h5/1",
	})
	return h.do(http.MethodPost, "/v1/comments", bytes.NewReader(body), map[string]string{
		"Content-Type": "application/json",
	})
}

// ------------------------------------------------------------------ tests

func TestFullFlow(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")

	raw := photoFixture(t, 0)
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1015", raw, "")

	if registered.ImageID == "" || registered.Code == "" {
		t.Fatalf("registration returned %+v", registered)
	}
	if registered.Matched != "new" {
		t.Errorf("matched %q, want new", registered.Matched)
	}
	if !strings.Contains(registered.QRURL, "/v1/qr/"+registered.Code+".png") {
		t.Errorf("qrUrl %q does not point at the code", registered.QRURL)
	}
	if !strings.Contains(registered.ThumbURL, "/t/"+registered.Code) {
		t.Errorf("thumbUrl %q does not point at the thumbnail", registered.ThumbURL)
	}

	// The same photo again must not become a second identity, and the second
	// answer must be served from the stored row rather than by uploading again.
	again := h.registerPhoto(deviceID, deviceToken, ownerToken, "1015", raw, "")
	if again.ImageID != registered.ImageID {
		t.Errorf("the same photo registered twice produced %s and %s", registered.ImageID, again.ImageID)
	}

	// A second album sending the same bytes joins the same thread.
	otherID, otherToken, otherOwner := h.issueDevice("phone")
	fromOther := h.registerPhoto(otherID, otherToken, otherOwner, "other", raw, "")
	if fromOther.ImageID != registered.ImageID {
		t.Errorf("a second album produced a second identity: %s vs %s", fromOther.ImageID, registered.ImageID)
	}

	// The QR PNG is a PNG.
	qrResp := h.do(http.MethodGet, "/v1/qr/"+registered.Code+".png?size=200", nil, nil)
	defer qrResp.Body.Close()
	if qrResp.StatusCode != http.StatusOK {
		t.Fatalf("qr: %d", qrResp.StatusCode)
	}
	if ct := qrResp.Header.Get("Content-Type"); ct != "image/png" {
		t.Errorf("qr content type %q", ct)
	}
	qrBytes := []byte(readAll(qrResp.Body))
	if len(qrBytes) < 100 || qrBytes[1] != 'P' || qrBytes[2] != 'N' || qrBytes[3] != 'G' {
		t.Errorf("qr response is not a PNG (%d bytes)", len(qrBytes))
	}

	// The thumbnail URL is signed, and a tampered one is refused.
	thumbResp := h.do(http.MethodGet, pathOf(t, registered.ThumbURL), nil, nil)
	defer thumbResp.Body.Close()
	if thumbResp.StatusCode != http.StatusOK {
		t.Fatalf("thumbnail: %d %s", thumbResp.StatusCode, readAll(thumbResp.Body))
	}
	if ct := thumbResp.Header.Get("Content-Type"); ct != "image/jpeg" {
		t.Errorf("thumbnail content type %q", ct)
	}
	thumbBytes := []byte(readAll(thumbResp.Body))
	if _, err := jpeg.Decode(bytes.NewReader(thumbBytes)); err != nil {
		t.Errorf("the served thumbnail does not decode: %v", err)
	}
	// EXIF must be gone: the server re-encoded it, so there is no APP1 segment.
	if bytes.Contains(thumbBytes, []byte("Exif")) {
		t.Error("the served thumbnail still carries an Exif segment")
	}

	tampered := strings.Replace(pathOf(t, registered.ThumbURL), "sig=", "sig=x", 1)
	if resp := h.do(http.MethodGet, tampered, nil, nil); resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("a tampered signature was accepted: %d", resp.StatusCode)
		resp.Body.Close()
	} else {
		resp.Body.Close()
	}

	// Comments: post one, read it back, and see it counted.
	commentResp := h.postComment(registered.Code, "this one is good", "wang", "idem-1")
	if commentResp.StatusCode != http.StatusOK {
		t.Fatalf("comment: %d %s", commentResp.StatusCode, readAll(commentResp.Body))
	}
	var posted commentResponse
	decode(t, commentResp.Body, &posted)
	commentResp.Body.Close()
	if posted.Status != store.StatusVisible {
		t.Errorf("a clean comment was held (status %d)", posted.Status)
	}

	// The same idempotency key must not produce a second comment.
	replay := h.postComment(registered.Code, "this one is good", "wang", "idem-1")
	var replayed commentResponse
	decode(t, replay.Body, &replayed)
	replay.Body.Close()
	if replayed.ID != posted.ID {
		t.Errorf("idempotent replay produced %s, want %s", replayed.ID, posted.ID)
	}

	listResp := h.do(http.MethodGet,
		"/v1/comments?imageId="+url.QueryEscape(registered.ImageID), nil,
		map[string]string{"X-Device-Id": deviceID, "X-Device-Token": deviceToken})
	defer listResp.Body.Close()
	if listResp.StatusCode != http.StatusOK {
		t.Fatalf("comment list: %d", listResp.StatusCode)
	}
	var list struct {
		ServerTime int64         `json:"serverTime"`
		Items      []commentJSON `json:"items"`
	}
	decode(t, listResp.Body, &list)
	if len(list.Items) != 1 || list.Items[0].Content != "this one is good" {
		t.Errorf("comment list is %+v", list.Items)
	}
	if list.ServerTime == 0 {
		t.Error("the list did not report serverTime")
	}

	// The photo detail endpoint reports the same count.
	detailResp := h.do(http.MethodGet, "/v1/photos/"+registered.Code, nil, nil)
	defer detailResp.Body.Close()
	var detail photoDetail
	decode(t, detailResp.Body, &detail)
	if detail.CommentCount != 1 {
		t.Errorf("detail comment count %d, want 1", detail.CommentCount)
	}
	if detail.Scope != "image" {
		t.Errorf("scope %q, want image", detail.Scope)
	}

	// And the H5 page shows all of it without a line of JavaScript.
	pageResp := h.do(http.MethodGet, "/c/"+registered.Code, nil, nil)
	defer pageResp.Body.Close()
	if pageResp.StatusCode != http.StatusOK {
		t.Fatalf("page: %d", pageResp.StatusCode)
	}
	page := readAll(pageResp.Body)
	for _, want := range []string{"this one is good", "wang", "提交", "/t/" + registered.Code} {
		if !strings.Contains(page, want) {
			t.Errorf("the H5 page is missing %q", want)
		}
	}
	if strings.Contains(page, "<script") {
		t.Error("the H5 page has a script tag; it is meant to be script-free")
	}
	if strings.Contains(page, "https://") {
		t.Error("the H5 page references an https resource; on a LAN that is blocked as mixed content")
	}
}

func TestCommentScreeningAndRateLimit(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	// An advertisement is refused, with a reason the page can show.
	resp := h.postComment(registered.Code, "加微信 13800138000 优惠券", "spam", "s1")
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("an advertisement was accepted: %d", resp.StatusCode)
	}
	var apiErr struct {
		Error apiError `json:"error"`
	}
	decode(t, resp.Body, &apiErr)
	resp.Body.Close()
	if apiErr.Error.Code != "content_rejected" {
		t.Errorf("error code %q, want content_rejected", apiErr.Error.Code)
	}
	if apiErr.Error.Message == "" {
		t.Error("the refusal carried no message for the person who typed it")
	}

	// Too long is its own code, because the page says something different for it.
	long := strings.Repeat("字", 81)
	resp = h.postComment(registered.Code, long, "n", "s2")
	if resp.StatusCode != http.StatusBadRequest {
		t.Errorf("an 81-character comment was accepted: %d", resp.StatusCode)
	}
	decode(t, resp.Body, &apiErr)
	resp.Body.Close()
	if apiErr.Error.Code != "too_long" {
		t.Errorf("error code %q, want too_long", apiErr.Error.Code)
	}

	// The per-identity budget is five a minute.
	_ = deviceID
	var limited bool
	for i := 0; i < 8; i++ {
		r := h.postComment(registered.Code, fmt.Sprintf("comment number %d", i), "n", fmt.Sprintf("k%d", i))
		if r.StatusCode == http.StatusTooManyRequests {
			limited = true
			r.Body.Close()
			break
		}
		r.Body.Close()
	}
	if !limited {
		t.Error("eight comments in a minute were all accepted; the budget is five")
	}
}

// The comment endpoint has a budget of its own, and this is the only shape in which it
// can be seen: every caller inside every one of its own budgets, and the endpoint full
// anyway.
//
// One address runs out of its 30/min long before the service reaches 120, which is why
// the test dials from five — 127.0.0.1 through 127.0.0.5, all loopback, so this stays on
// one machine. Every request also arrives as a *new* guest (`anonId`), so no per-guest
// budget is anywhere near its 5/min either. What is left is the ceiling the deployment
// itself carries, and the count it stops at is the number in the design doc.
func TestCommentBudgetIsWholeService(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 41), "")

	callers := make([]*http.Client, 0, 5)
	for i := 0; i < 5; i++ {
		callers = append(callers, callerFrom(t, fmt.Sprintf("127.0.0.%d", i+1)))
	}

	limit := store.LimitPerService.Limit
	accepted, refused := 0, 0
	for i := 0; i < limit+2; i++ {
		body, _ := json.Marshal(map[string]string{
			"code": registered.Code, "content": fmt.Sprintf("第 %d 条评论", i),
			"nickname": "n", "idemKey": fmt.Sprintf("svc-%d", i),
			"anonId": fmt.Sprintf("guest-%d", i), "client": "h5/1",
		})
		resp := h.doWith(
			callers[i%len(callers)],
			http.MethodPost, "/v1/comments", bytes.NewReader(body),
			map[string]string{"Content-Type": "application/json"},
		)
		switch resp.StatusCode {
		case http.StatusOK:
			accepted++
		case http.StatusTooManyRequests:
			refused++
		default:
			status, detail := resp.StatusCode, readAll(resp.Body)
			resp.Body.Close()
			t.Fatalf("comment %d: %d %s", i, status, detail)
		}
		resp.Body.Close()
	}

	if accepted != limit {
		t.Errorf("the endpoint took %d comments, want exactly %d", accepted, limit)
	}
	if refused == 0 {
		t.Errorf("%d comments in a minute were all accepted; the endpoint has no budget of its own", limit+2)
	}
}

// callerFrom is an HTTP client that dials from one loopback address, so one test can be
// several callers with several per-IP budgets without leaving the machine.
func callerFrom(t *testing.T, ip string) *http.Client {
	t.Helper()
	dialer := &net.Dialer{LocalAddr: &net.TCPAddr{IP: net.ParseIP(ip)}}
	return &http.Client{Transport: &http.Transport{DialContext: dialer.DialContext}}
}

func TestLockedPhotoRefusesComments(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 14), "")

	if err := h.store.SetVisibility(context.Background(), registered.ImageID, store.VisibilityDisabled); err != nil {
		t.Fatal(err)
	}

	resp := h.postComment(registered.Code, "hello", "n", "k")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Fatalf("a comment on a locked photo: %d", resp.StatusCode)
	}
	var apiErr struct {
		Error apiError `json:"error"`
	}
	decode(t, resp.Body, &apiErr)
	if apiErr.Error.Code != "photo_locked" {
		t.Errorf("error code %q, want photo_locked", apiErr.Error.Code)
	}
}

func TestPageSubmitIsPostRedirectGet(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	form := url.Values{"content": {"from the phone"}, "nickname": {"jia"}, "idem": {"form-1"}}
	resp := h.doNoRedirect(http.MethodPost, "/c/"+registered.Code, strings.NewReader(form.Encode()), map[string]string{
		"Content-Type": "application/x-www-form-urlencoded",
	})
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("form submit answered %d, want 303 (PRG)", resp.StatusCode)
	}
	location := resp.Header.Get("Location")
	if !strings.HasPrefix(location, "/c/"+registered.Code+"?ok=1") {
		t.Errorf("redirect goes to %q", location)
	}

	// Following the redirect shows the comment, so a refresh cannot resubmit it.
	follow := h.do(http.MethodGet, location, nil, nil)
	defer follow.Body.Close()
	page := readAll(follow.Body)
	if !strings.Contains(page, "from the phone") || !strings.Contains(page, "已提交") {
		t.Errorf("the page after the redirect does not show the comment")
	}
}

func TestPageKeepsTypedTextOnFailure(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	typed := "这句话里有 加微信 之类的东西"
	form := url.Values{"content": {typed}, "nickname": {"jia"}, "idem": {"form-2"}}
	resp := h.doNoRedirect(http.MethodPost, "/c/"+registered.Code, strings.NewReader(form.Encode()), map[string]string{
		"Content-Type": "application/x-www-form-urlencoded",
	})
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("a rejected form answered %d, want 400", resp.StatusCode)
	}
	page := readAll(resp.Body)
	// The whole point: an 80-character comment typed on a phone must survive a
	// refusal, or the person has to type it again.
	if !strings.Contains(page, "加微信") {
		t.Error("the refused comment was not kept in the form")
	}
	if !strings.Contains(page, "jia") {
		t.Error("the nickname was not kept in the form")
	}
}

func TestPageReportAndModeration(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 14), "")

	resp := h.postComment(registered.Code, "something reportable", "n", "k")
	var posted commentResponse
	decode(t, resp.Body, &posted)
	resp.Body.Close()

	report := h.doNoRedirect(http.MethodGet, "/c/"+registered.Code+"/report/"+posted.ID, nil, nil)
	report.Body.Close()
	if report.StatusCode != http.StatusSeeOther {
		t.Fatalf("report answered %d, want 303", report.StatusCode)
	}

	// The queue needs the admin token.
	unauth := h.do(http.MethodGet, "/v1/admin/comments", nil, nil)
	unauth.Body.Close()
	if unauth.StatusCode != http.StatusUnauthorized {
		t.Errorf("the moderation queue without a token: %d", unauth.StatusCode)
	}

	queue := h.do(http.MethodGet, "/v1/admin/comments", nil, map[string]string{"X-Admin-Token": "admin-token"})
	defer queue.Body.Close()
	if queue.StatusCode != http.StatusOK {
		t.Fatalf("the moderation queue: %d", queue.StatusCode)
	}

	decide := h.do(http.MethodPost, "/v1/admin/comments/"+posted.ID+"?action=block", nil,
		map[string]string{"X-Admin-Token": "admin-token"})
	defer decide.Body.Close()
	if decide.StatusCode != http.StatusOK {
		t.Fatalf("moderation decision: %d %s", decide.StatusCode, readAll(decide.Body))
	}

	// A blocked comment disappears from the list the TV plays.
	listResp := h.do(http.MethodGet, "/v1/comments?imageId="+registered.ImageID, nil,
		map[string]string{"X-Device-Id": deviceID, "X-Device-Token": deviceToken})
	defer listResp.Body.Close()
	var list struct {
		Items []commentJSON `json:"items"`
	}
	decode(t, listResp.Body, &list)
	if len(list.Items) != 0 {
		t.Errorf("a blocked comment is still delivered: %+v", list.Items)
	}
}

// ---------------------------------------------------------------- admin (§17.15)

/*
The admin door: a browser session instead of a header, and 编辑 / 删除 on the comment
page. Three things are being pinned down here, and the third is the point of the whole
feature rather than its decoration:

  - a guest's page is exactly the page it was, with no admin control on it;
  - everything an admin may do needs the login, and the login is the token;
  - the session cookie is signed over its own expiry, so "logged in" is not something a
    caller can write on their own cookie.
*/
func TestAdminLoginAndCommentEditing(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 21), "")
	page := "/c/" + registered.Code

	resp := h.postComment(registered.Code, "原来那句话", "小王", "k1")
	var posted commentResponse
	decode(t, resp.Body, &posted)
	resp.Body.Close()

	// ------------------------------------------------------------ as a guest
	_, guest := h.text(noRedirect(), http.MethodGet, page)
	if strings.Contains(guest, "/edit/") || strings.Contains(guest, "/delete/") {
		t.Error("a guest page is offering the admin's controls")
	}
	if !strings.Contains(guest, "管理员登录") {
		t.Error("a guest page does not offer the admin login at all")
	}

	// Every admin URL without a session is the login form, told where to come back to —
	// not a 401, because a 401 in a browser is a dead end with no way forward.
	denied := h.doWith(noRedirect(), http.MethodGet, page+"/edit/"+posted.ID, nil, nil)
	denied.Body.Close()
	if denied.StatusCode != http.StatusSeeOther {
		t.Fatalf("an unauthenticated edit page answered %d, want 303 to the login", denied.StatusCode)
	}
	want := "/admin?next=" + url.QueryEscape(page+"/edit/"+posted.ID)
	if loc := denied.Header.Get("Location"); loc != want {
		t.Errorf("the login redirect goes to %q, want %q", loc, want)
	}

	deniedDelete := h.postForm(noRedirect(), page+"/delete/"+posted.ID, url.Values{})
	deniedDelete.Body.Close()
	if deniedDelete.StatusCode != http.StatusSeeOther {
		t.Errorf("an unauthenticated delete answered %d, want 303 to the login", deniedDelete.StatusCode)
	}

	// ------------------------------------------------------------ logging in
	wrong := h.postForm(noRedirect(), "/admin", url.Values{"token": {"not-the-token"}})
	wrongBody := readAll(wrong.Body)
	wrong.Body.Close()
	if wrong.StatusCode != http.StatusUnauthorized {
		t.Errorf("a wrong token answered %d, want 401", wrong.StatusCode)
	}
	if !strings.Contains(wrongBody, "令牌不对") {
		t.Error("a refused login does not say so")
	}
	if len(wrong.Cookies()) != 0 {
		t.Error("a refused login still handed out a session cookie")
	}

	browser := h.browser()
	login := h.postForm(browser, "/admin", url.Values{"token": {"admin-token"}, "next": {page}})
	login.Body.Close()
	if login.StatusCode != http.StatusSeeOther {
		t.Fatalf("a good login answered %d, want 303", login.StatusCode)
	}
	if loc := login.Header.Get("Location"); loc != page {
		t.Errorf("the login redirect goes to %q, want back to %q", loc, page)
	}
	if len(login.Cookies()) == 0 {
		t.Fatal("a successful login set no cookie")
	}

	// The same session opens the moderation JSON endpoints, so a logged-in admin can
	// look at the queue without pasting a token into the address bar.
	queue := h.doWith(browser, http.MethodGet, "/v1/admin/comments", nil, nil)
	queue.Body.Close()
	if queue.StatusCode != http.StatusOK {
		t.Errorf("the moderation queue with a session cookie answered %d, want 200", queue.StatusCode)
	}

	// ------------------------------------------------------------ as an admin
	_, adminView := h.text(browser, http.MethodGet, page)
	if !strings.Contains(adminView, "管理员模式") {
		t.Error("the admin does not see that they are logged in")
	}
	if !strings.Contains(adminView, `href="`+page+"/edit/"+posted.ID+`"`) {
		t.Error("the admin's page has no 编辑 link for the comment")
	}
	if !strings.Contains(adminView, `action="`+page+"/delete/"+posted.ID+`"`) {
		t.Error("the admin's page has no 删除 form for the comment")
	}

	_, editForm := h.text(browser, http.MethodGet, page+"/edit/"+posted.ID)
	if !strings.Contains(editForm, "原来那句话") {
		t.Error("the edit form does not carry the comment's current text")
	}

	save := h.postForm(browser, page+"/edit/"+posted.ID,
		url.Values{"nickname": {"小王"}, "content": {"改过的那句话"}})
	save.Body.Close()
	if save.StatusCode != http.StatusSeeOther {
		t.Fatalf("a saved edit answered %d, want 303", save.StatusCode)
	}
	if loc := save.Header.Get("Location"); loc != page+"?ok=edited&mine="+posted.ID {
		t.Errorf("after an edit the redirect goes to %q", loc)
	}

	// A browser follows the 303, and what it lands on is the point of the PRG: the page
	// now says what happened, and a refresh re-runs that GET rather than the edit.
	_, after := h.text(browser, http.MethodGet, save.Header.Get("Location"))
	if !strings.Contains(after, "改过的那句话") || strings.Contains(after, "原来那句话") {
		t.Error("the page still shows the old text after an edit")
	}
	if !strings.Contains(after, "已保存") {
		t.Error("the page does not say the edit was saved")
	}

	// The television reads the same row, so an edit is not a second copy of the comment
	// that only the browser can see.
	list := h.do(http.MethodGet, "/v1/comments?imageId="+registered.ImageID, nil, map[string]string{
		"X-Device-Id": deviceID, "X-Device-Token": deviceToken,
	})
	var listing struct {
		Items []commentJSON `json:"items"`
	}
	decode(t, list.Body, &listing)
	list.Body.Close()
	if len(listing.Items) != 1 || listing.Items[0].Content != "改过的那句话" {
		t.Errorf("the danmaku history still has %+v", listing.Items)
	}

	// A refused edit keeps the typed text, exactly as the guest form does.
	tooLong := strings.Repeat("字", guard.MaxContentRunes+5)
	refused := h.postForm(browser, page+"/edit/"+posted.ID, url.Values{"content": {tooLong}})
	refusedBody := readAll(refused.Body)
	refused.Body.Close()
	if refused.StatusCode != http.StatusBadRequest {
		t.Errorf("an over-long edit answered %d, want 400", refused.StatusCode)
	}
	if !strings.Contains(refusedBody, tooLong) {
		t.Error("a refused edit did not keep the text that was typed")
	}

	// An empty one is refused too: a comment that says nothing is not a comment.
	empty := h.postForm(browser, page+"/edit/"+posted.ID, url.Values{"content": {"   "}})
	empty.Body.Close()
	if empty.StatusCode != http.StatusBadRequest {
		t.Errorf("an empty edit answered %d, want 400", empty.StatusCode)
	}

	// ------------------------------------------------------------ deleting
	del := h.postForm(browser, page+"/delete/"+posted.ID, url.Values{})
	del.Body.Close()
	if del.StatusCode != http.StatusSeeOther {
		t.Fatalf("a delete answered %d, want 303", del.StatusCode)
	}
	if loc := del.Header.Get("Location"); loc != page+"?ok=deleted" {
		t.Errorf("after a delete the redirect goes to %q", loc)
	}

	_, gone := h.text(browser, http.MethodGet, del.Header.Get("Location"))
	if strings.Contains(gone, "改过的那句话") {
		t.Error("a deleted comment is still listed on the page")
	}
	if !strings.Contains(gone, "已删除") || !strings.Contains(gone, "还没有人评论过这张照片") {
		t.Error("the page does not report the comment as gone")
	}

	list = h.do(http.MethodGet, "/v1/comments?imageId="+registered.ImageID, nil, map[string]string{
		"X-Device-Id": deviceID, "X-Device-Token": deviceToken,
	})
	listing.Items = nil
	decode(t, list.Body, &listing)
	list.Body.Close()
	if len(listing.Items) != 0 {
		t.Errorf("a deleted comment is still in the danmaku history: %+v", listing.Items)
	}

	// Deleting is a soft delete: the row is still there for the operator, which is what
	// makes a mis-tap on a phone recoverable.
	if row, err := h.store.Comment(context.Background(), posted.ID); err != nil || row.Status != store.StatusDeleted {
		t.Errorf("the deleted row: %+v %v", row, err)
	}

	// ------------------------------------------------------------ logging out
	logout := h.postForm(browser, "/admin/logout", url.Values{})
	logout.Body.Close()
	if logout.StatusCode != http.StatusSeeOther {
		t.Errorf("a logout answered %d, want 303", logout.StatusCode)
	}
	_, back := h.text(browser, http.MethodGet, page)
	if strings.Contains(back, "管理员模式") {
		t.Error("the page is still in admin mode after 退出")
	}
}

// TestAdminEditCannotReachAnotherPhoto is the URL's own promise: /c/{code}/edit/{id} is
// about one photo's comments, and the ids are global. Without the check, an operator could
// edit a comment on a photo they were not looking at, and the page they were sent back to
// would be showing a thread that does not contain it.
func TestAdminEditCannotReachAnotherPhoto(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	first := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")
	second := h.registerPhoto(deviceID, deviceToken, ownerToken, "2", photoFixture(t, 54), "")

	resp := h.postComment(second.Code, "别人的照片上的话", "n", "k")
	var other commentResponse
	decode(t, resp.Body, &other)
	resp.Body.Close()

	browser := h.browser()
	login := h.postForm(browser, "/admin", url.Values{"token": {"admin-token"}})
	login.Body.Close()

	crossed := h.doWith(browser, http.MethodGet, "/c/"+first.Code+"/edit/"+other.ID, nil, nil)
	crossed.Body.Close()
	if crossed.StatusCode != http.StatusNotFound {
		t.Errorf("editing another photo's comment through this code answered %d, want 404", crossed.StatusCode)
	}

	save := h.postForm(browser, "/c/"+first.Code+"/edit/"+other.ID,
		url.Values{"content": {"被改掉了"}})
	save.Body.Close()
	if save.StatusCode != http.StatusNotFound {
		t.Errorf("saving through the wrong code answered %d, want 404", save.StatusCode)
	}

	row, err := h.store.Comment(context.Background(), other.ID)
	if err != nil {
		t.Fatal(err)
	}
	if row.Content != "别人的照片上的话" {
		t.Errorf("a comment was edited through another photo's URL: %q", row.Content)
	}
}

// TestAdminLoginRefusesToLeaveTheHost pins down safeNext. `//evil.example` is the one that
// matters: it starts with a slash and is a host, not a path.
func TestAdminLoginRefusesToLeaveTheHost(t *testing.T) {
	h := newHarness(t)

	for _, next := range []string{
		"https://evil.example/steal",
		"//evil.example/steal",
		"/\\evil.example",
		"http://evil.example",
		"",
		"/c/abcdef",
		"/admin",
	} {
		resp := h.postForm(noRedirect(), "/admin",
			url.Values{"token": {"admin-token"}, "next": {next}})
		resp.Body.Close()
		loc := resp.Header.Get("Location")
		if !strings.HasPrefix(loc, "/") || strings.HasPrefix(loc, "//") || strings.Contains(loc, "evil.example") {
			t.Errorf("next=%q produced Location %q — that is off this host", next, loc)
		}
	}
}

// TestAdminSessionCookieIsSigned: the expiry travels with the cookie, so the signature has
// to cover it. This is the check that says a caller cannot mint their own session by
// editing the number.
func TestAdminSessionCookieIsSigned(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 21), "")
	page := "/c/" + registered.Code

	browser := h.browser()
	login := h.postForm(browser, "/admin", url.Values{"token": {"admin-token"}})
	cookies := login.Cookies()
	login.Body.Close()
	if len(cookies) == 0 {
		t.Fatal("no session cookie to tamper with")
	}
	session := cookies[0]

	// The real one works.
	_, ok := h.text(h.browserWith(session.Value), http.MethodGet, page)
	if !strings.Contains(ok, "管理员模式") {
		t.Fatal("the session the login handed out does not work")
	}

	for _, bad := range []string{
		// A far-future expiry with the signature that was minted for the real one.
		"99999999999999." + strings.SplitN(session.Value, ".", 2)[1],
		// A signature from nowhere.
		strings.SplitN(session.Value, ".", 2)[0] + ".AAAAAAAAAAAAAAAAAAAAAA",
		"garbage",
		"",
	} {
		_, view := h.text(h.browserWith(bad), http.MethodGet, page)
		if strings.Contains(view, "管理员模式") {
			t.Errorf("a tampered cookie (%q) was accepted as an admin session", bad)
		}
	}
}

// TestNoAdminTokenMeansNoAdminSurface: with TUPING_ADMIN_TOKEN unset there is no admin,
// and the deployment must not pretend otherwise — no login link, no login form, and the
// JSON endpoints 404 exactly as they always did.
func TestNoAdminTokenMeansNoAdminSurface(t *testing.T) {
	h := newHarnessWith(t, func(c *Config) { c.AdminToken = "" })
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 21), "")
	page := "/c/" + registered.Code

	loginPage := h.do(http.MethodGet, "/admin", nil, nil)
	loginPage.Body.Close()
	if loginPage.StatusCode != http.StatusNotFound {
		t.Errorf("the login page without an admin token answered %d, want 404", loginPage.StatusCode)
	}

	login := h.postForm(noRedirect(), "/admin", url.Values{"token": {"anything"}})
	login.Body.Close()
	if login.StatusCode != http.StatusNotFound {
		t.Errorf("logging in without an admin token answered %d, want 404", login.StatusCode)
	}

	_, guest := h.text(noRedirect(), http.MethodGet, page)
	if strings.Contains(guest, "管理员登录") {
		t.Error("a deployment with no admin token advertises a login")
	}

	queue := h.do(http.MethodGet, "/v1/admin/comments", nil, nil)
	queue.Body.Close()
	if queue.StatusCode != http.StatusNotFound {
		t.Errorf("the moderation queue without an admin token answered %d, want 404", queue.StatusCode)
	}
}

func TestUnknownCodeIsNotFound(t *testing.T) {
	h := newHarness(t)

	for _, path := range []string{"/v1/photos/abcdef", "/v1/qr/abcdef.png", "/c/abcdef"} {
		resp := h.do(http.MethodGet, path, nil, nil)
		resp.Body.Close()
		if resp.StatusCode != http.StatusNotFound {
			t.Errorf("%s answered %d, want 404", path, resp.StatusCode)
		}
	}

	// And a code that cannot be one at all is refused before any lookup.
	resp := h.do(http.MethodGet, "/v1/photos/nope!", nil, nil)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("a malformed code answered %d, want 404", resp.StatusCode)
	}
}

func TestPhotoRegistrationNeedsAToken(t *testing.T) {
	h := newHarness(t)

	req, err := http.NewRequest(http.MethodPost, h.http.URL+"/v1/photos", bytes.NewReader(nil))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "multipart/form-data; boundary=x")
	resp, err := h.http.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusUnauthorized {
		t.Errorf("an unauthenticated registration answered %d, want 401", resp.StatusCode)
	}

	// A device token that was never issued is refused too.
	resp2 := h.do(http.MethodPost, "/v1/comments", strings.NewReader(`{"code":"abcdef","content":"hi"}`),
		map[string]string{"Content-Type": "application/json"})
	resp2.Body.Close()
	// Comments are open to the H5 page, which has no device token, so this is a
	// 404 for the code rather than a 401 — the point is that it is not a 500.
	if resp2.StatusCode >= 500 {
		t.Errorf("an unauthenticated comment answered %d", resp2.StatusCode)
	}
}

func TestDeleteRequiresTheOwnerToken(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	wrong := h.do(http.MethodDelete, "/v1/photos/"+registered.ImageID, nil, map[string]string{
		"X-Device-Id": deviceID, "X-Device-Token": deviceToken, "X-Owner-Token": "not-the-owner",
	})
	wrong.Body.Close()
	if wrong.StatusCode != http.StatusUnauthorized {
		t.Errorf("a wrong owner token was accepted: %d", wrong.StatusCode)
	}

	ok := h.do(http.MethodDelete, "/v1/photos/"+registered.ImageID, nil, map[string]string{
		"X-Device-Id": deviceID, "X-Device-Token": deviceToken, "X-Owner-Token": ownerToken,
	})
	ok.Body.Close()
	if ok.StatusCode != http.StatusOK {
		t.Errorf("the owner's delete answered %d", ok.StatusCode)
	}

	if _, err := h.store.ResolveCode(context.Background(), registered.Code); err == nil {
		t.Error("the code still resolves after the delete")
	}
}

func TestLiveSocketDeliversNewComments(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 72), "")

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn := h.liveSocket(t, ctx, registered.ImageID, deviceID, deviceToken)

	resp := h.postComment(registered.Code, "live danmaku", "wang", "live-1")
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("posting the comment: %d", resp.StatusCode)
	}

	msg := readLive(t, ctx, conn)
	if msg.Type != "comment" || msg.Content != "live danmaku" || msg.Nickname != "wang" {
		t.Errorf("the push was %+v", msg)
	}
	if msg.CreatedAt == 0 {
		t.Error("the push carried no timestamp")
	}
}

// TestLiveSocketPushesCommentStateChanges is the report §17.16 was written for: a comment
// deleted on the web page kept crossing the television, because nothing ever told the TV
// that the comment had changed. The socket carries a comment's *state*, so an edit
// corrects the words on screen and a delete takes them down.
func TestLiveSocketPushesCommentStateChanges(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 72), "")
	page := "/c/" + registered.Code

	resp := h.postComment(registered.Code, "会被删掉的那句", "wang", "state-1")
	var posted commentResponse
	decode(t, resp.Body, &posted)
	resp.Body.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn := h.liveSocket(t, ctx, registered.ImageID, deviceID, deviceToken)

	browser := h.browser()
	login := h.postForm(browser, "/admin", url.Values{"token": {"admin-token"}})
	login.Body.Close()

	// An edit is a state change too: the television has to stop replaying the words that
	// were just corrected, and its history fetch would never bring the new ones, because
	// `since` is the newest comment it already holds.
	save := h.postForm(browser, page+"/edit/"+posted.ID,
		url.Values{"nickname": {"wang"}, "content": {"改过的那句"}})
	save.Body.Close()
	edited := readLive(t, ctx, conn)
	if edited.Type != "comment" || edited.ID != posted.ID || edited.Content != "改过的那句" {
		t.Errorf("the edit push was %+v", edited)
	}
	if edited.Status == nil || *edited.Status != store.StatusVisible {
		t.Errorf("the edit push carried status %v, want %d", edited.Status, store.StatusVisible)
	}

	// The delete: this is the one a person watched keep crossing the screen.
	del := h.postForm(browser, page+"/delete/"+posted.ID, url.Values{})
	del.Body.Close()
	gone := readLive(t, ctx, conn)
	if gone.Type != "comment" || gone.ID != posted.ID {
		t.Errorf("the delete push was %+v", gone)
	}
	if gone.Status == nil || *gone.Status != store.StatusDeleted {
		t.Errorf("the delete push carried status %v, want %d", gone.Status, store.StatusDeleted)
	}
}

// TestLiveSocketCarriesHeldStatus: 0 is a real status (待审), so it has to be on the wire.
// `omitempty` on an int would drop it, and a client that defaults an absent status to
// "visible" would go on drawing a comment a moderator has just taken down.
func TestLiveSocketCarriesHeldStatus(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	resp := h.postComment(registered.Code, "会被举报三次的那句", "wang", "reported-1")
	var posted commentResponse
	decode(t, resp.Body, &posted)
	resp.Body.Close()

	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	conn := h.liveSocket(t, ctx, registered.ImageID, deviceID, deviceToken)

	// Only the report that crosses the threshold changes anything, so the first message
	// on this socket is that one.
	for i := 0; i < store.ReportThreshold; i++ {
		r := h.do(http.MethodPost, "/v1/comments/"+posted.ID+"/report",
			strings.NewReader("{}"), map[string]string{"Content-Type": "application/json"})
		r.Body.Close()
	}

	held := readLive(t, ctx, conn)
	if held.Type != "comment" || held.ID != posted.ID {
		t.Errorf("the held push was %+v", held)
	}
	if held.Status == nil {
		t.Fatal("the held push carried no status at all, which a client reads as visible")
	}
	if *held.Status != store.StatusPending {
		t.Errorf("the held push carried status %d, want %d", *held.Status, store.StatusPending)
	}
}

// TestThumbnailWaitIsRefused: a photo whose thumbnail has expired must answer 404
// rather than serve nothing or crash.
func TestExpiredThumbnailIsNotFound(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	keys, err := h.store.ThumbKeys(context.Background(), registered.ImageID)
	if err != nil || len(keys) == 0 {
		t.Fatalf("thumbnail keys: %v %v", keys, err)
	}
	if err := h.thumbs.Delete(keys[0]); err != nil {
		t.Fatal(err)
	}

	resp := h.do(http.MethodGet, pathOf(t, registered.ThumbURL), nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("a deleted thumbnail answered %d, want 404", resp.StatusCode)
	}
}

// ------------------------------------------------------------------ helpers

// photoFixture returns a real photograph from the mock NAS set, re-encoded so the
// test does not depend on the exact bytes of an asset.
//
// The generated fallback matters more than it looks. `tools/mock-nas/assets/photos/` is
// gitignored -- those are photographs from a real NAS -- so CI always takes this path, and the
// fallback therefore has to depend on [id]. The first version did not: every id produced the
// same bytes, the server correctly matched them as one photograph, and
// TestAdminEditCannotReachAnotherPhoto quietly lost its subject (two codes pointing at the same
// photo, so "edit through the wrong code" was no longer wrong). The first CI run failed on
// exactly that, on 2026-10-11.
func photoFixture(t *testing.T, id int) []byte {
	t.Helper()
	path := filepath.Join("..", "..", "..", "tools", "mock-nas", "assets", "photos",
		fmt.Sprintf("%d", id), "m.jpg")
	data, err := os.ReadFile(path)
	if err != nil {
		// A gradient whose phase depends on id, plus seeded blocks: structured enough for a
		// stable pHash, and far enough apart between ids that the matcher sees two different
		// photographs rather than one re-encoded twice.
		img := image.NewRGBA(image.Rect(0, 0, 640, 360))
		for y := 0; y < 360; y++ {
			for x := 0; x < 640; x++ {
				img.Set(x, y, color.RGBA{
					uint8((x + id*37) % 256),
					uint8((y*3 + id*11) % 256),
					uint8((x + y + id*23) % 256),
					255,
				})
			}
		}
		blocks := rand.New(rand.NewSource(int64(id)))
		for i := 0; i < 48; i++ {
			x0, y0 := blocks.Intn(600), blocks.Intn(320)
			w, h := 24+blocks.Intn(64), 24+blocks.Intn(48)
			shade := uint8(blocks.Intn(256))
			for y := y0; y < y0+h && y < 360; y++ {
				for x := x0; x < x0+w && x < 640; x++ {
					img.Set(x, y, color.RGBA{shade, shade, shade, 255})
				}
			}
		}
		var buf bytes.Buffer
		if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 85}); err != nil {
			t.Fatal(err)
		}
		return buf.Bytes()
	}
	return data
}

func decode(t *testing.T, r io.Reader, v any) {
	t.Helper()
	if err := json.NewDecoder(r).Decode(v); err != nil {
		t.Fatalf("decode: %v", err)
	}
}

func readAll(r io.Reader) string {
	data, _ := io.ReadAll(r)
	return string(data)
}

// pathOf turns an absolute URL that came back from the server into a path with
// its query, so it can be replayed against the test server.
func pathOf(t *testing.T, raw string) string {
	t.Helper()
	u, err := url.Parse(raw)
	if err != nil {
		t.Fatalf("parse %q: %v", raw, err)
	}
	return u.Path + "?" + u.RawQuery
}
