package api

import (
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

// tlsServer puts the harness's handler behind a listener that really is TLS.
//
// httptest.NewServer cannot express this: r.TLS is nil inside the handler there, so every
// "was this connection encrypted" branch reads as false and a test written against it would be
// checking the plain-http server twice. This is also the only way to prove the scheme in a
// link comes off the connection rather than out of a constant.
func tlsServer(t *testing.T, h *harness) *httptest.Server {
	t.Helper()
	ts := httptest.NewTLSServer(h.server.Handler())
	t.Cleanup(ts.Close)
	return ts
}

// tlsClient is the test server's client — it trusts that server's certificate — but stops at
// the first response, because on these endpoints the Set-Cookie header is on the redirect.
func tlsClient(ts *httptest.Server) *http.Client {
	client := ts.Client()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	return client
}

func cookieNamed(t *testing.T, resp *http.Response, name string) *http.Cookie {
	t.Helper()
	for _, c := range resp.Cookies() {
		if c.Name == name {
			return c
		}
	}
	t.Fatalf("no %s cookie in the response", name)
	return nil
}

// TestGuestCookiesAreSecureOnlyOverTLS: the flag has to follow the connection in both
// directions. Off over plain http, because a Secure cookie is never sent back there and the
// guest would silently get a new identity on every page view; on under TLS, where it is the
// only thing stopping the same identity from being read off the LAN.
func TestGuestCookiesAreSecureOnlyOverTLS(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("tv")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	plain := h.do(http.MethodGet, "/c/"+registered.Code, nil, nil)
	defer plain.Body.Close()
	if c := cookieNamed(t, plain, anonCookie); c.Secure {
		t.Error("the guest cookie is Secure over plain http, where the browser never sends it back")
	}

	ts := tlsServer(t, h)
	resp, err := tlsClient(ts).Get(ts.URL + "/c/" + registered.Code)
	if err != nil {
		t.Fatalf("guest page over TLS: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("guest page over TLS: %d", resp.StatusCode)
	}
	if c := cookieNamed(t, resp, anonCookie); !c.Secure {
		t.Error("the guest cookie is not Secure over https, so a LAN sniffer can take the guest's identity")
	}
}

// TestAdminCookieIsSecureOnlyOverTLS is the same rule on the cookie that matters most: it is a
// bearer token for every comment on the instance (§17.15).
func TestAdminCookieIsSecureOnlyOverTLS(t *testing.T) {
	h := newHarness(t)
	form := url.Values{"token": {"admin-token"}}

	plain := h.postForm(noRedirect(), "/admin", form)
	defer plain.Body.Close()
	if c := cookieNamed(t, plain, adminCookie); c.Secure {
		t.Error("the admin cookie is Secure over plain http, where it can never come back")
	}

	ts := tlsServer(t, h)
	resp, err := tlsClient(ts).PostForm(ts.URL+"/admin", form)
	if err != nil {
		t.Fatalf("admin login over TLS: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusSeeOther {
		t.Fatalf("admin login over TLS: %d, want a 303 with the cookie on it", resp.StatusCode)
	}
	if c := cookieNamed(t, resp, adminCookie); !c.Secure {
		t.Error("the admin cookie is not Secure over https, so the moderation password's session rides in the clear")
	}
}

// TestLinksFollowTheConnection: nothing in this package hardcodes a scheme — it is read off
// r.TLS — so pointing the listener at a certificate has to flip every link the server hands
// out, including the one encoded into the QR code, which is the entire reason a deployer puts
// TLS in front of this thing.
func TestLinksFollowTheConnection(t *testing.T) {
	// Nothing pinned: the address is the caller's, which is the shape a deployment has when it
	// has just been switched to https.
	h := newHarnessWithBaseURL(t, "")
	deviceID, deviceToken, ownerToken := h.issueDevice("tv")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1", photoFixture(t, 33), "")

	ts := tlsServer(t, h)
	req, err := http.NewRequest(http.MethodGet, ts.URL+"/v1/qr/"+registered.Code+".png", nil)
	if err != nil {
		t.Fatal(err)
	}
	// The address a phone would have typed. Loopback is refused by design (§17.20), so a test
	// that left the Host alone would be asserting the refusal instead of the scheme.
	req.Host = "192.168.31.51:8443"

	resp, err := ts.Client().Do(req)
	if err != nil {
		t.Fatalf("qr over TLS: %v", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("qr over TLS: %d %s", resp.StatusCode, readAll(resp.Body))
	}
	if got, want := resp.Header.Get("X-Page-Url"), "https://192.168.31.51:8443/c/"+registered.Code; got != want {
		t.Errorf("X-Page-Url = %q, want %q", got, want)
	}
}

// TestHealthzReportsTLSAndWarnsAboutAnHTTPPin covers the one combination TLS introduces that
// produces dead links: the certificate is in place, so nothing answers http on that port any
// more, while TUPING_BASE_URL still pins an http:// address into every QR code.
func TestHealthzReportsTLSAndWarnsAboutAnHTTPPin(t *testing.T) {
	h := newHarnessWithBaseURL(t, "http://106.52.237.179:53347", func(c *Config) { c.TLS = true })

	resp := h.do(http.MethodGet, "/healthz", nil, nil)
	defer resp.Body.Close()
	var out struct {
		TLS     bool   `json:"tls"`
		Warning string `json:"warning"`
	}
	decode(t, resp.Body, &out)
	if !out.TLS {
		t.Error("healthz does not say this server terminates TLS, so a deployer cannot confirm the variables took effect")
	}
	if !strings.Contains(out.Warning, "https") {
		t.Errorf("an http:// pin under TLS produced no warning, so the QR codes stay broken quietly: %q", out.Warning)
	}

	// The other half, and the one that keeps the warning worth reading: a correct https
	// deployment must not be nagged.
	fine := newHarnessWithBaseURL(t, "https://photos.example.com", func(c *Config) { c.TLS = true })
	if got := fine.healthzWarning(); got != "" {
		t.Errorf("a correct https deployment was warned about: %q", got)
	}
}
