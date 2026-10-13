package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"testing"
)

/*
The address on a short code, after the deployment moves (docs/tuping-design.md §17.27).

This is the endpoint a person runs when the tunnel address changes and every QR code on the
television starts opening a host nobody can reach. What is being pinned here is not only
that it rewrites the address, but the three properties that make it safe to hand to an
operator at all:

  - **the code does not change.** A rebind must not break `/c/<code>` — a guest's history, a
    television's cached identity and a link somebody kept all name that code;
  - **nothing else changes.** Comments hang off the photo, not the code, so a rebind cannot
    lose a thread — which is the entire reason this exists rather than "delete the codes and
    re-register every photo";
  - **it is the admin's act and only the admin's.** A client that could rewrite the address
    on every code could hand every guest somebody else's hostname.
*/

// The addresses these tests move between. The first is the shape a tunnel hands out and the
// one from the report that started this (`baseurl_test.go` uses the same host); the second is
// a domain, because that is where a deployment that has settled down ends up.
const (
	oldCodeAddress = "http://106.52.237.179:53347"
	newCodeAddress = "https://photos.example.com"
)

// qrResponse fetches a code's picture and answers the two things a caller can read off it.
func (h *harness) qrResponse(code string) *http.Response {
	h.t.Helper()
	resp := h.do(http.MethodGet, "/v1/qr/"+code+".png", nil, nil)
	if resp.StatusCode != http.StatusOK {
		defer resp.Body.Close()
		h.t.Fatalf("qr %s: %d %s", code, resp.StatusCode, readAll(resp.Body))
	}
	return resp
}

// qrPage is the address the code currently encodes, read off the QR endpoint's own header.
func (h *harness) qrPage(code string) string {
	h.t.Helper()
	resp := h.qrResponse(code)
	defer resp.Body.Close()
	return resp.Header.Get("X-Page-Url")
}

// rebind posts to the admin endpoint and hands back the status and the decoded body.
func (h *harness) rebind(body, contentType string, headers map[string]string) (int, map[string]any) {
	h.t.Helper()
	sent := map[string]string{}
	for k, v := range headers {
		sent[k] = v
	}
	if _, ok := sent["X-Admin-Token"]; !ok {
		sent["X-Admin-Token"] = "admin-token"
	}
	if contentType != "" {
		sent["Content-Type"] = contentType
	}

	var reader io.Reader
	if body != "" {
		reader = strings.NewReader(body)
	}
	resp := h.do(http.MethodPost, "/v1/admin/codes/rebind", reader, sent)
	defer resp.Body.Close()

	out := map[string]any{}
	if resp.StatusCode == http.StatusOK {
		decode(h.t, resp.Body, &out)
	}
	return resp.StatusCode, out
}

func (h *harness) rebindJSON(address string) (int, map[string]any) {
	h.t.Helper()
	return h.rebind(`{"baseUrl":`+mustJSON(h.t, address)+`}`, "application/json", nil)
}

func mustJSON(t *testing.T, value string) string {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	return string(encoded)
}

// ------------------------------------------------------------------ the operation

// TestRebindMovesEveryCodeAndKeepsIt is the whole feature in one reading: the same code, a
// new address.
func TestRebindMovesEveryCodeAndKeepsIt(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1", photoFixture(t, 41), "", oldCodeAddress)

	want := oldCodeAddress + "/c/" + registered.Code
	if got := h.qrPage(registered.Code); got != want {
		t.Fatalf("X-Page-Url = %q, want %q", got, want)
	}

	status, out := h.rebindJSON(newCodeAddress)
	if status != http.StatusOK {
		t.Fatalf("rebind: %d", status)
	}
	if out["updated"] != float64(1) || out["total"] != float64(1) {
		t.Errorf("rebind reported %v/%v, want 1/1", out["updated"], out["total"])
	}
	if out["baseUrl"] != newCodeAddress {
		t.Errorf("baseUrl echoed as %v", out["baseUrl"])
	}
	// The sentence that makes the operation finish on the television rather than at the
	// shell: a count of changed rows looks like success while the code on screen still opens
	// the old address.
	if next, _ := out["next"].(string); next == "" {
		t.Error("the reply does not say what to do on the television, so 217 changed rows reads as done")
	}
	// A public address is not the mistake the warning exists for.
	if _, warned := out["warning"]; warned {
		t.Errorf("a public address was warned about: %v", out["warning"])
	}

	// The code is the same one, and it now opens the new address.
	if got, want := h.qrPage(registered.Code), newCodeAddress+"/c/"+registered.Code; got != want {
		t.Errorf("after the rebind X-Page-Url = %q, want %q", got, want)
	}
}

// TestRebindCanClearTheRecordedAddress: an empty body means "stop overriding the deployment",
// which hands every code back to the pin or to the address the caller reached the server on.
// It is the form a deployment behind a proxy that rewrites Host wants.
func TestRebindCanClearTheRecordedAddress(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1", photoFixture(t, 42), "", oldCodeAddress)

	status, out := h.rebind("", "", nil)
	if status != http.StatusOK {
		t.Fatalf("rebind with no body: %d", status)
	}
	if out["baseUrl"] != "" || out["updated"] != float64(1) {
		t.Errorf("clearing reported %v with baseUrl %v", out["updated"], out["baseUrl"])
	}

	// The harness pins TUPING_BASE_URL, and that is what the code follows now.
	pinned := "http://127.0.0.1:8080"
	if got, want := h.qrPage(registered.Code), pinned+"/c/"+registered.Code; got != want {
		t.Errorf("after clearing X-Page-Url = %q, want the pinned %q", got, want)
	}

	// The picture is the same for every caller again, so it may be kept by any cache — the
	// header the QR endpoint already used to say so.
	resp := h.qrResponse(registered.Code)
	defer resp.Body.Close()
	if cc := resp.Header.Get("Cache-Control"); !strings.Contains(cc, "immutable") {
		t.Errorf("Cache-Control = %q, want it cacheable once the address is not caller-derived", cc)
	}
}

// TestRebindKeepsEveryComment is why this is not "delete the codes and let the album
// register again": the threads are the thing a deployment must not lose.
func TestRebindKeepsEveryComment(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1", photoFixture(t, 43), "", oldCodeAddress)

	resp := h.postComment(registered.Code, "这算什么", "客人", "rebind-idem-1")
	var posted commentResponse
	decode(t, resp.Body, &posted)
	resp.Body.Close()
	if posted.ID == "" {
		t.Fatal("the comment was not created, so this test has nothing to protect")
	}

	if status, _ := h.rebindJSON(newCodeAddress); status != http.StatusOK {
		t.Fatalf("rebind: %d", status)
	}

	list := h.do(http.MethodGet, "/v1/comments?imageId="+url.QueryEscape(registered.ImageID), nil,
		map[string]string{"X-Device-Id": deviceID, "X-Device-Token": deviceToken})
	defer list.Body.Close()
	var items struct {
		Items []commentJSON `json:"items"`
	}
	decode(t, list.Body, &items)
	if len(items.Items) != 1 || items.Items[0].ID != posted.ID {
		t.Errorf("the thread did not survive the rebind: %+v", items.Items)
	}

	// And the guest's page still resolves through the same code.
	status, _ := h.text(h.http.Client(), http.MethodGet, "/c/"+registered.Code)
	if status != http.StatusOK {
		t.Errorf("the guest page after a rebind: %d", status)
	}
}

// TestRebindReportsWhatChangedOutOfWhatExists: running it twice is not a failure, and the
// operator can tell "nothing needed changing" from "the endpoint did nothing".
func TestRebindReportsWhatChangedOutOfWhatExists(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	// 33 and 54 are the pair `TestAdminEditCannotReachAnotherPhoto` uses: two fixtures far
	// enough apart that the matcher sees two photographs rather than one re-encoded twice.
	h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "", oldCodeAddress)
	h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "2", photoFixture(t, 54), "", oldCodeAddress)

	status, first := h.rebindJSON(newCodeAddress)
	if status != http.StatusOK {
		t.Fatalf("first rebind: %d", status)
	}
	if first["updated"] != float64(2) || first["total"] != float64(2) {
		t.Errorf("first rebind reported %v/%v, want 2/2", first["updated"], first["total"])
	}

	status, second := h.rebindJSON(newCodeAddress)
	if status != http.StatusOK {
		t.Fatalf("second rebind: %d", status)
	}
	if second["updated"] != float64(0) || second["total"] != float64(2) {
		t.Errorf("second rebind reported %v/%v, want 0/2", second["updated"], second["total"])
	}
}

// TestRebindRefusesAnAddressNoPhoneCouldOpen: the one input that would put a dead link into
// every code on the instance, which is the state this endpoint exists to get out of.
func TestRebindRefusesAnAddressNoPhoneCouldOpen(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1", photoFixture(t, 46), "", oldCodeAddress)

	for _, bad := range []string{
		"not a url",
		"照片服务器",
		"ftp://photos.example.com",
		"http://user:secret@photos.example.com",
		strings.Repeat("http://photos.example.com/", 40),
	} {
		status, _ := h.rebindJSON(bad)
		if status != http.StatusBadRequest {
			t.Errorf("rebind(%q) answered %d, want 400", bad, status)
		}
	}

	// Nothing moved: a refused rebind must leave every code exactly where it was.
	if got, want := h.qrPage(registered.Code), oldCodeAddress+"/c/"+registered.Code; got != want {
		t.Errorf("a refused rebind moved the code anyway: %q, want %q", got, want)
	}
}

// TestRebindWarnsAboutAPrivateAddress: the same mistake §11.5 and §17.20 keep having to
// explain — a LAN address is fine until guests are on the other side of a tunnel.
func TestRebindWarnsAboutAPrivateAddress(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 47), "", oldCodeAddress)

	status, out := h.rebindJSON("http://192.168.31.14:8080")
	if status != http.StatusOK {
		t.Fatalf("rebind to a LAN address: %d", status)
	}
	if out["warning"] == nil || out["warning"] == "" {
		t.Error("a private address was accepted in silence, which is how a wall of unscannable codes gets made")
	}
}

// TestRebindAcceptsTheAddressWhereverTheOperatorPutIt: three spellings, because this is a
// command somebody types by hand and `curl -d '{"…"}'` sends JSON as a form.
func TestRebindAcceptsTheAddressWhereverTheOperatorPutIt(t *testing.T) {
	cases := []struct {
		name        string
		body        string
		contentType string
		query       string
	}{
		{name: "a JSON body", body: `{"baseUrl":"` + newCodeAddress + `"}`, contentType: "application/json"},
		{name: "a form body", body: "baseUrl=" + url.QueryEscape(newCodeAddress), contentType: "application/x-www-form-urlencoded"},
		{name: "JSON sent as a form, which is what curl -d does", body: `{"baseUrl":"` + newCodeAddress + `"}`, contentType: "application/x-www-form-urlencoded"},
		{name: "the query string", query: "?baseUrl=" + url.QueryEscape(newCodeAddress)},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			deviceID, deviceToken, ownerToken := h.issueDevice("TV")
			registered := h.registerPhotoWithBase(
				deviceID, deviceToken, ownerToken, "1", photoFixture(t, 48), "", oldCodeAddress)

			// The query-string case cannot go through `rebind`, which posts to the bare path.
			var status int
			if tc.query != "" {
				resp := h.do(http.MethodPost, "/v1/admin/codes/rebind"+tc.query, nil,
					map[string]string{"X-Admin-Token": "admin-token"})
				defer resp.Body.Close()
				status = resp.StatusCode
			} else {
				status, _ = h.rebind(tc.body, tc.contentType, nil)
			}
			if status != http.StatusOK {
				t.Fatalf("rebind: %d", status)
			}
			if got, want := h.qrPage(registered.Code), newCodeAddress+"/c/"+registered.Code; got != want {
				t.Errorf("X-Page-Url = %q, want %q", got, want)
			}
		})
	}
}

// ------------------------------------------------------------------ the door

func TestRebindNeedsTheAdminToken(t *testing.T) {
	h := newHarness(t)

	for _, headers := range []map[string]string{
		{"X-Admin-Token": ""},
		{"X-Admin-Token": "not-the-token"},
	} {
		status, _ := h.rebind(`{"baseUrl":"`+newCodeAddress+`"}`, "application/json", headers)
		if status != http.StatusUnauthorized {
			t.Errorf("rebind with %v answered %d, want 401", headers, status)
		}
	}

	// The same token the moderation endpoints take, in the spelling an operator's curl uses.
	status, _ := h.rebind(`{"baseUrl":"`+newCodeAddress+`"}`, "application/json", nil)
	if status != http.StatusOK {
		t.Fatalf("rebind with the token: %d", status)
	}
}

// TestRebindIs404WithoutAnAdminToken: with no admin configured there is no admin surface at
// all, the same answer the moderation endpoints give.
func TestRebindIs404WithoutAnAdminToken(t *testing.T) {
	h := newHarnessWith(t, func(c *Config) { c.AdminToken = "" })

	resp := h.do(http.MethodPost, "/v1/admin/codes/rebind",
		strings.NewReader(`{"baseUrl":"`+newCodeAddress+`"}`), nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("rebind on an instance with no admin token: %d, want 404", resp.StatusCode)
	}
}
