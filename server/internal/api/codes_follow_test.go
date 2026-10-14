package api

import (
	"testing"
)

/*
The address on a short code when the *same television* registers the photo again from a new
address (docs/tuping-design.md §17.31).

This is the half of "we have moved" that a household can actually perform. `rebind` needs a
shell, an admin token and the knowledge that the endpoint exists; the television that owns
the photo knows all three things already — the photo, its code, and the address it is
talking to right now.

What is pinned here is the rule's narrowness, because the invariant it relaxes is real: a
guest may be holding a printed or screenshotted link, and two televisions that hold the same
photo (the layered fingerprint merges them on purpose) must not flip one code's address
between their two networks. So:

  - the minting device moves it, and the code itself does not change;
  - another device does **not**, and
  - a device that reports no address does **not** (an old client's silence is not evidence).

The fourth case — a code minted before the owner column existed — is at the store level,
where a row can be forged into that shape: see `TestCodeWithoutAnOwnerIsClaimedOnce` in
`store_test.go`.
*/

func TestRegisteringAgainFromANewAddressMovesTheCode(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	fixture := photoFixture(t, 61)

	first := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", oldCodeAddress)
	if got, want := h.qrPage(first.Code), oldCodeAddress+"/c/"+first.Code; got != want {
		t.Fatalf("X-Page-Url = %q, want %q", got, want)
	}

	// The same television, the same photo, another address — a tunnel that changed, a
	// deployment that moved, or the same server reached at a second address.
	again := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", newCodeAddress)

	if again.Code != first.Code {
		t.Errorf("the code changed: %q -> %q; a guest's printed link must keep working", first.Code, again.Code)
	}
	if got, want := h.qrPage(again.Code), newCodeAddress+"/c/"+again.Code; got != want {
		t.Errorf("X-Page-Url = %q, want %q", got, want)
	}
	// The reply's own URLs have to agree with the code, or the television fetches a picture
	// from one host for a code that lives on another.
	if want := newCodeAddress + "/v1/qr/" + again.Code + ".png?size=440"; again.QRURL != want {
		t.Errorf("qrUrl = %q, want %q", again.QRURL, want)
	}
}

func TestAnotherDeviceCannotMoveTheCode(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	firstID, firstToken, firstOwner := h.issueDevice("TV")
	fixture := photoFixture(t, 62)
	registered := h.registerPhotoWithBase(firstID, firstToken, firstOwner, "1", fixture, "", oldCodeAddress)

	// A second television with the same photo: the fingerprint layer merges it into the same
	// identity and the same thread, which is the point — and it must not re-point the link.
	secondID, secondToken, secondOwner := h.issueDevice("TV-2")
	second := h.registerPhotoWithBase(secondID, secondToken, secondOwner, "9", fixture, "", "http://elsewhere.example.com")

	if second.Code != registered.Code {
		t.Fatalf("the second device minted a new code: %q vs %q", second.Code, registered.Code)
	}
	if got, want := h.qrPage(second.Code), oldCodeAddress+"/c/"+registered.Code; got != want {
		t.Errorf("X-Page-Url = %q, want the owner's address %q", got, want)
	}
}

func TestADeviceThatReportsNoAddressMovesNothing(t *testing.T) {
	h := newHarnessWithBaseURL(t, "")
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	fixture := photoFixture(t, 63)
	registered := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", oldCodeAddress)

	// "" is what a client older than 1.5.2 reports, and it means "no evidence" rather than
	// "clear the address": the pinned TUPING_BASE_URL and the caller's own address remain in
	// the fallback chain behind whatever the code already recorded.
	again := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", "")

	if got, want := h.qrPage(again.Code), oldCodeAddress+"/c/"+registered.Code; got != want {
		t.Errorf("X-Page-Url = %q, want the recorded address kept (%q)", got, want)
	}
}

// TestRebindTakesTheCodeAwayFromTheDevice is the other half of the rule, and the half that
// keeps the two acts from taking turns.
//
// `rebind` is an operator saying "phones must use this address" — typically because the
// television sits on the LAN while the guests do not. If the minting device could still move
// that code, the next press of 扫码评论 on the television would put the LAN address back and
// the operator would rebind again, for as long as either of them kept at it.
func TestRebindTakesTheCodeAwayFromTheDevice(t *testing.T) {
	// The admin door is the whole point of this case, so the harness is given the token the
	// other rebind tests use (`newHarness` sets it; the unpinned one does not).
	h := newHarnessWithBaseURL(t, "", func(c *Config) { c.AdminToken = "admin-token" })
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	fixture := photoFixture(t, 64)
	registered := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", oldCodeAddress)

	if status, _ := h.rebindJSON(newCodeAddress); status != 200 {
		t.Fatalf("rebind: status %d", status)
	}

	// The same television registers the same photo from the address it is actually using.
	again := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", "http://192.168.31.14:8080")
	if again.Code != registered.Code {
		t.Fatalf("the code changed: %q -> %q", registered.Code, again.Code)
	}
	if got, want := h.qrPage(again.Code), newCodeAddress+"/c/"+registered.Code; got != want {
		t.Errorf("X-Page-Url = %q, want the operator's address kept (%q)", got, want)
	}
}

// TestAPinnedDeploymentDoesNotFollowADevice: `TUPING_BASE_URL` is the operator saying where
// phones go, and the deployment that needs it is the one whose device reports cannot be
// believed — behind a proxy that rewrites Host. Following the device anyway would overwrite
// the address the pin exists to state.
func TestAPinnedDeploymentDoesNotFollowADevice(t *testing.T) {
	pinned := "https://photos.example.com"
	h := newHarnessWithBaseURL(t, pinned)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	fixture := photoFixture(t, 65)

	first := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", oldCodeAddress)
	second := h.registerPhotoWithBase(deviceID, deviceToken, ownerToken, "1", fixture, "", "http://198.51.100.9:9000")

	if second.Code != first.Code {
		t.Fatalf("the same photo minted two codes: %q then %q", first.Code, second.Code)
	}
	if got, want := h.qrPage(second.Code), oldCodeAddress+"/c/"+first.Code; got != want {
		t.Errorf("X-Page-Url = %q, want it left at %q on a pinned deployment", got, want)
	}
}
