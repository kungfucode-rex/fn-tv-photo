package api

import (
	"bytes"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"tuping/internal/qr"
	"tuping/internal/store"
	"tuping/internal/thumbs"
)

// newHarnessWithBaseURL is [newHarness] with the one setting most likely to be wrong left
// to the caller.
func newHarnessWithBaseURL(t *testing.T, baseURL string) *harness {
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

	server, err := New(Options{
		Config: Config{
			BaseURL:                baseURL,
			Secret:                 "test-secret-that-is-long-enough",
			DeviceRegistrationOpen: true,
			Version:                "test",
		},
		Store:  st,
		Thumbs: thumbStore,
		// io.Discard would hide the very warning this file is about, and a test that cannot
		// see the log cannot assert on it. Nothing is printed: the handler writes nowhere.
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

// healthzWarning returns the `warning` field /healthz reports, or "" when it reports none.
func (h *harness) healthzWarning() string {
	h.t.Helper()
	resp := h.do(http.MethodGet, "/healthz", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		h.t.Fatalf("healthz: %d", resp.StatusCode)
	}
	var out struct {
		BaseURL string `json:"baseUrl"`
		Warning string `json:"warning"`
	}
	if err := json.NewDecoder(resp.Body).Decode(&out); err != nil {
		h.t.Fatalf("decode healthz: %v", err)
	}
	if out.BaseURL == "" {
		h.t.Fatal("healthz reported no baseUrl at all")
	}
	return out.Warning
}

// TestHostIsPrivate is the classification the warning rests on.
func TestHostIsPrivate(t *testing.T) {
	cases := []struct {
		raw  string
		want bool
	}{
		// The addresses this deployment actually uses. 192.168.31.51:8080 is the default in
		// docker-compose.yml, and leaving it there while the TV is pointed at a public
		// address is the exact mistake the warning exists for.
		{"http://192.168.31.51:8080", true},
		{"http://192.168.31.14:8080", true},
		{"http://10.0.0.5:8080", true},
		{"http://172.16.4.4:8080", true},
		{"http://127.0.0.1:8080", true},
		{"http://localhost:8080", true},
		{"http://nas.local:8080", true},
		{"http://[::1]:8080", true},

		// The case the user hit: a public address reached through a port forward or a
		// tunnel. This one is correct and must not be warned about, or the warning is noise
		// that teaches people to ignore it.
		{"http://106.52.237.179:53347", false},
		{"https://106.52.237.179:53347", false},
		{"http://203.0.113.7:8080", false},
		{"https://photos.example.com", false},

		// Not a URL at all: the server refuses to start on an empty BaseURL, so anything
		// reaching here is already odd, and guessing at it would be worse than silence.
		{"", false},
		{"not a url at all", false},
		{"192.168.31.51:8080", false}, // no scheme: url.Parse gives an empty hostname
	}

	for _, tc := range cases {
		if got := hostIsPrivate(tc.raw); got != tc.want {
			t.Errorf("hostIsPrivate(%q) = %v, want %v", tc.raw, got, tc.want)
		}
	}
}

// TestHealthzWarnsAboutAPrivateBaseURL: the warning has to be reachable without a shell on
// the server, because the person who needs it is standing in front of a television.
func TestHealthzWarnsAboutAPrivateBaseURL(t *testing.T) {
	h := newHarnessWithBaseURL(t, "http://192.168.31.51:8080")
	if got := h.healthzWarning(); got == "" {
		t.Error("a private base URL produced no warning, so the misconfiguration is still invisible")
	}
}

// TestHealthzIsQuietAboutAPublicBaseURL is the other half, and the one that keeps the
// warning worth reading: a correct deployment must not be nagged.
func TestHealthzIsQuietAboutAPublicBaseURL(t *testing.T) {
	h := newHarnessWithBaseURL(t, "http://106.52.237.179:53347")
	if got := h.healthzWarning(); got != "" {
		t.Errorf("a public base URL was warned about anyway: %q", got)
	}
}

// ---------------------------------------------------------------- the address comes from the caller

// doAsHostWith sends a request the way a television on 192.168.31.51 would: the address it
// reached the server on is in the Host header, which is the only place the server can learn
// it from when nothing is pinned and the client reports nothing.
func (h *harness) doAsHostWith(method, path, host string, body io.Reader, headers map[string]string) *http.Response {
	h.t.Helper()
	req, err := http.NewRequest(method, h.http.URL+path, body)
	if err != nil {
		h.t.Fatalf("request: %v", err)
	}
	req.Host = host
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	resp, err := h.http.Client().Do(req)
	if err != nil {
		h.t.Fatalf("%s %s as %s: %v", method, path, host, err)
	}
	return resp
}

func (h *harness) doAsHost(method, path, host string) *http.Response {
	h.t.Helper()
	return h.doAsHostWith(method, path, host, nil, nil)
}

// TestIsLoopbackHost is the guard that keeps "the server guessed 127.0.0.1" out of QR codes.
func TestIsLoopbackHost(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"", true},
		{"127.0.0.1", true},
		{"127.0.0.1:8080", true},
		{"127.5.5.5:9", true},
		{"localhost", true},
		{"LOCALHOST:8080", true},
		{"[::1]:8080", true},
		{"0.0.0.0:8080", true},

		// The addresses this deployment actually uses, and the ones a guest's phone can
		// open. A container name is neither loopback nor something the server may second
		// guess: the television that asked for the QR code is the authority on it.
		{"192.168.31.51:8080", false},
		{"106.52.237.179:53347", false},
		{"nas.local:8080", false},
		{"tuping:8080", false},
	}

	for _, tc := range cases {
		if got := isLoopbackHost(tc.host); got != tc.want {
			t.Errorf("isLoopbackHost(%q) = %v, want %v", tc.host, got, tc.want)
		}
	}
}

// TestQrCodeUsesTheAddressTheCallerUsed: with nothing pinned and nothing reported, the only
// evidence is where the request arrived. A relative QR code is a dead end, so this is the
// case that must not degrade quietly.
func TestQrCodeUsesTheAddressTheCallerUsed(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	deviceID, deviceToken, ownerToken := h.issueDevice("tv")
	// registerPhoto reports no address at all, which is the old client §17.20 keeps alive.
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	resp := h.doAsHost(http.MethodGet, "/v1/qr/"+registered.Code+".png", "192.168.31.51:8080")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("qr: %d %s", resp.StatusCode, readAll(resp.Body))
	}
	if got, want := resp.Header.Get("X-Page-Url"), "http://192.168.31.51:8080/c/"+registered.Code; got != want {
		t.Errorf("X-Page-Url = %q, want %q", got, want)
	}
	// One caller's answer is not the code's answer: `public` here would let a shared cache
	// hand one hostname's QR code to another hostname's guests.
	if cc := resp.Header.Get("Cache-Control"); !strings.Contains(cc, "no-store") {
		t.Errorf("Cache-Control = %q, want no-store when the address came from the caller", cc)
	}
}

// TestLoopbackHostIsNotAnAddress: a request that arrived on 127.0.0.1 proves nothing about
// what a guest's phone can open. The QR endpoint refuses rather than encoding a dead end,
// while the guest page — which is always opened on an address that works — keeps working.
func TestLoopbackHostIsNotAnAddress(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	deviceID, deviceToken, ownerToken := h.issueDevice("tv")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	// The harness's own server listens on 127.0.0.1, which is exactly the case under test.
	resp := h.do(http.MethodGet, "/v1/qr/"+registered.Code+".png", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusBadRequest {
		t.Fatalf("qr requested over loopback: %d, want 400", resp.StatusCode)
	}
	if body := readAll(resp.Body); !strings.Contains(body, "TUPING_BASE_URL") {
		t.Errorf("the refusal does not name either way out: %s", body)
	}

	status, page := h.text(h.http.Client(), http.MethodGet, "/c/"+registered.Code)
	if status != http.StatusOK {
		t.Fatalf("guest page: %d", status)
	}
	// A relative URL is resolved against the page's own origin, which is the one address
	// known to work — so the picture and the comments survive having no base URL at all.
	if !strings.Contains(page, `src="/t/`) {
		t.Error("the guest page did not fall back to a relative thumbnail URL")
	}
}

// TestPinnedAddressBeatsTheCaller: setting TUPING_BASE_URL is how a deployment behind a
// proxy that rewrites Host says where its guests actually are.
func TestPinnedAddressBeatsTheCaller(t *testing.T) {
	h := newHarnessWithBaseURL(t, "http://106.52.237.179:53347")
	deviceID, deviceToken, ownerToken := h.issueDevice("tv")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	resp := h.doAsHost(http.MethodGet, "/v1/qr/"+registered.Code+".png", "192.168.31.51:8080")
	defer resp.Body.Close()
	if got, want := resp.Header.Get("X-Page-Url"), "http://106.52.237.179:53347/c/"+registered.Code; got != want {
		t.Errorf("X-Page-Url = %q, want the pinned address %q", got, want)
	}
	if cc := resp.Header.Get("Cache-Control"); !strings.Contains(cc, "immutable") {
		t.Errorf("Cache-Control = %q, want it cacheable when the address is not caller-derived", cc)
	}
}

// TestNewDeviceIsToldTheAddressItJustUsed: the registration reply's baseUrl is a default for
// the television's own 图评服务器 row. With nothing pinned, "the address you just used" is the
// only honest answer, and the one the client can act on.
func TestNewDeviceIsToldTheAddressItJustUsed(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	body, _ := json.Marshal(map[string]string{"name": "tv"})

	resp := h.doAsHostWith(http.MethodPost, "/v1/devices", "192.168.31.51:8080",
		bytes.NewReader(body), map[string]string{"Content-Type": "application/json"})
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("device registration: %d %s", resp.StatusCode, readAll(resp.Body))
	}
	var out struct {
		BaseURL string `json:"baseUrl"`
	}
	decode(t, resp.Body, &out)
	if out.BaseURL != "http://192.168.31.51:8080" {
		t.Errorf("baseUrl = %q, want the address the caller used", out.BaseURL)
	}
}

// TestHealthzSaysWhereTheAddressComesFrom: `baseUrl` is empty when nothing is pinned, and ""
// is not a URL — so the field next to it is what a person reads to tell "not configured"
// from "configured to nothing".
func TestHealthzSaysWhereTheAddressComesFrom(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	resp := h.do(http.MethodGet, "/healthz", nil, nil)
	defer resp.Body.Close()

	var out struct {
		BaseURL string `json:"baseUrl"`
		Source  string `json:"baseUrlSource"`
	}
	decode(t, resp.Body, &out)
	if out.BaseURL != "" {
		t.Errorf("baseUrl = %q, want it empty when nothing is pinned", out.BaseURL)
	}
	if !strings.Contains(out.Source, "caller") {
		t.Errorf("baseUrlSource = %q, want it to name the caller", out.Source)
	}
}
