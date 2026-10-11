package api

import (
	"bytes"
	"io"
	"net/http"
	"strings"
	"testing"
)

// fetchQRPageURL asks the server for a code's PNG and returns the two things worth knowing:
// that a PNG came back, and which address it encodes.
//
// The address is read from the `X-Page-Url` response header rather than by decoding the
// image, because `skip2/go-qrcode` cannot decode — and because the header is what a person
// debugging this on a server with no camera will read too.
func (h *harness) fetchQRPageURL(code string) (pageURL string, pngBytes int) {
	h.t.Helper()
	resp := h.do(http.MethodGet, "/v1/qr/"+code, nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		h.t.Fatalf("qr %s: %d", code, resp.StatusCode)
	}
	data, err := io.ReadAll(resp.Body)
	if err != nil {
		h.t.Fatalf("read qr: %v", err)
	}
	page := resp.Header.Get("X-Page-Url")
	if page == "" {
		h.t.Fatal("the qr endpoint reported no X-Page-Url, so nothing says what the code contains")
	}
	return page, len(data)
}

// TestPageThumbnailComesFromTheSameAddressAsThePage.
//
// A page served over one address whose own <img> points at another renders as a broken
// picture on the guest's phone — which looks like the server is down and is actually two
// addresses disagreeing. This was a real bug: the QR code followed the device's address and
// the thumbnail inside the page it opened did not.
func TestPageThumbnailComesFromTheSameAddressAsThePage(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")

	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1015", photoFixture(t, 0), "",
		"http://106.52.237.179:53347",
	)

	resp := h.do(http.MethodGet, "/c/"+registered.Code, nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("page: %d", resp.StatusCode)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatalf("read page: %v", err)
	}
	html := string(body)

	want := "http://106.52.237.179:53347/t/" + registered.Code
	if !strings.Contains(html, want) {
		t.Errorf("the page does not fetch its thumbnail from %q", want)
	}
	if strings.Contains(html, "127.0.0.1:8080/t/") {
		t.Error("the page points its thumbnail at the server's own base URL instead of the " +
			"address the device registered from — the picture would not load")
	}
}

// TestRegistrationReplyAdvertisesTheSameAddress is the same rule applied to the JSON the
// album reads: a reply whose qrUrl names a host the code does not live on sends the client
// after a code it cannot fetch.
func TestRegistrationReplyAdvertisesTheSameAddress(t *testing.T) {
	h := newHarnessWithBaseURL(t, "http://192.168.31.51:8080")
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")

	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1015", photoFixture(t, 0), "",
		"http://106.52.237.179:53347",
	)

	for name, got := range map[string]string{
		"qrUrl":    registered.QRURL,
		"thumbUrl": registered.ThumbURL,
	} {
		if !strings.HasPrefix(got, "http://106.52.237.179:53347/") {
			t.Errorf("%s is %q, which is not the address the device registered from", name, got)
		}
	}
}

// TestNormalizeBaseURL is the validation standing between a client-supplied address and a
// QR code that somebody's phone will open.
func TestNormalizeBaseURL(t *testing.T) {
	cases := []struct {
		name string
		raw  string
		want string
	}{
		// The two addresses this user actually has: a public one behind a port forward, and
		// the LAN one the server was (wrongly) pointing at.
		{"public with port", "http://106.52.237.179:53347", "http://106.52.237.179:53347"},
		{"lan", "http://192.168.31.51:8080", "http://192.168.31.51:8080"},
		{"https hostname", "https://photos.example.com", "https://photos.example.com"},
		{"trailing slash", "http://192.168.31.51:8080/", "http://192.168.31.51:8080"},
		{"whitespace", "  http://192.168.31.51:8080  ", "http://192.168.31.51:8080"},

		// A path is dropped rather than honoured: the server appends /c/<code> itself, so a
		// path here is either a mistake or an attempt to point the code elsewhere.
		{"with path", "http://host:8080/some/prefix", "http://host:8080"},
		{"with query", "http://host:8080/?x=1", "http://host:8080"},

		// Refused outright. Each of these would produce a code that either does not work or
		// does something the person who scanned it did not expect.
		{"empty", "", ""},
		{"no scheme", "192.168.31.51:8080", ""},
		{"wrong scheme", "ftp://host:8080", ""},
		{"javascript", "javascript:alert(1)", ""},
		{"no host", "http://", ""},
		{"credentials", "http://user:pw@host:8080", ""},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := normalizeBaseURL(tc.raw); got != tc.want {
				t.Errorf("normalizeBaseURL(%q) = %q, want %q", tc.raw, got, tc.want)
			}
		})
	}

	// A very long one is refused too, checked separately because building the string inline
	// made the table unreadable.
	long := "http://host:8080/" + string(bytes.Repeat([]byte("a"), maxBaseURLLen))
	if got := normalizeBaseURL(long); got != "" {
		t.Errorf("an over-long base URL was accepted as %q", got)
	}
}

// TestQRUsesTheAddressTheDeviceRegisteredFrom is the fix for the report that a code kept
// pointing at 192.168 even though the TV was talking to a public address.
//
// The server's own TUPING_BASE_URL is deliberately a LAN-ish address here (the harness uses
// 127.0.0.1), so the test fails if the device's value is ignored.
func TestQRUsesTheAddressTheDeviceRegisteredFrom(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")

	registered := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1015", photoFixture(t, 0), "",
		"http://106.52.237.179:53347",
	)

	page, size := h.fetchQRPageURL(registered.Code)
	want := "http://106.52.237.179:53347/c/" + registered.Code
	if page != want {
		t.Errorf("the code opens %q, want %q — the device's own address was ignored", page, want)
	}
	if size == 0 {
		t.Error("no PNG came back")
	}
}

// TestQRFallsBackToTheServersOwnAddress: a device that says nothing, or says something
// unusable, still gets a working code. Every build before this one is in that position.
func TestQRFallsBackToTheServersOwnAddress(t *testing.T) {
	for _, tc := range []struct {
		name    string
		baseURL string
	}{
		{"device says nothing", ""},
		{"device says nonsense", "not-a-url"},
		{"device says the wrong scheme", "ftp://host:8080"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			h := newHarness(t)
			deviceID, deviceToken, ownerToken := h.issueDevice("TV")

			registered := h.registerPhotoWithBase(
				deviceID, deviceToken, ownerToken, "1015", photoFixture(t, 0), "", tc.baseURL,
			)

			page, _ := h.fetchQRPageURL(registered.Code)
			want := "http://127.0.0.1:8080/c/" + registered.Code
			if page != want {
				t.Errorf("the code opens %q, want the configured %q", page, want)
			}
		})
	}
}

// TestAnExistingCodeKeepsItsAddress: re-registering the same photo from a different network
// must not re-point a link that is already in somebody's hand.
func TestAnExistingCodeKeepsItsAddress(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	raw := photoFixture(t, 0)

	first := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1015", raw, "", "http://106.52.237.179:53347",
	)
	// Same photo, same device, same scope: `ensureCode` finds the existing code.
	second := h.registerPhotoWithBase(
		deviceID, deviceToken, ownerToken, "1015", raw, "", "http://198.51.100.9:9000",
	)
	if second.Code != first.Code {
		t.Fatalf("the same photo minted two codes: %q then %q", first.Code, second.Code)
	}

	page, _ := h.fetchQRPageURL(first.Code)
	want := "http://106.52.237.179:53347/c/" + first.Code
	if page != want {
		t.Errorf("an existing code was re-pointed to %q, want it left at %q", page, want)
	}
}
