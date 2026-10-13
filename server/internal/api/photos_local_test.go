package api

import (
	"context"
	"encoding/json"
	"net/http"
	"net/url"
	"testing"
)

/*
"Have I already registered this picture?" answered as a read (docs/tuping-design.md §17.28).

This endpoint exists so that the album end can show a photo's code and its comments without
uploading anything, and the property that matters is the one a *test* has to assert rather
than a comment: asking the question must leave no trace. A miss is the normal answer for
every photo nobody has ever opened 图评 on — which, with the viewer no longer registering
what it shows, is now most of a library — so "the miss created a row" or "the miss stored a
thumbnail" would undo the whole point of the change.
*/

// byLocal asks the question the way the television does.
func (h *harness) byLocal(deviceID, deviceToken, source, localID string) *http.Response {
	h.t.Helper()
	path := "/v1/photos/by-local?source=" + url.QueryEscape(source) + "&localId=" + url.QueryEscape(localID)
	headers := map[string]string{}
	if deviceID != "" {
		headers["X-Device-Id"] = deviceID
		headers["X-Device-Token"] = deviceToken
	}
	return h.do(http.MethodGet, path, nil, headers)
}

func (h *harness) byLocalPhoto(t *testing.T, deviceID, deviceToken, source, localID string) photoDetail {
	t.Helper()
	resp := h.byLocal(deviceID, deviceToken, source, localID)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		h.t.Fatalf("by-local %s/%s: %d %s", source, localID, resp.StatusCode, readAll(resp.Body))
	}
	var out photoDetail
	decode(t, resp.Body, &out)
	return out
}

// thumbBytes reads the one figure these tests assert on: how many bytes of thumbnails the
// server has stored. It is the only counter that moves when a registration happens.
func (h *harness) thumbBytes() int64 {
	h.t.Helper()
	resp := h.do(http.MethodGet, "/healthz", nil, nil)
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		h.t.Fatalf("healthz: %d", resp.StatusCode)
	}
	var out struct {
		ThumbBytes int64 `json:"thumbBytes"`
	}
	decode(h.t, resp.Body, &out)
	return out.ThumbBytes
}

// TestByLocalFindsWhatThisDeviceRegistered is the positive half: the same picture and the
// same code the registration handed out, without a second upload.
func TestByLocalFindsWhatThisDeviceRegistered(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1002", photoFixture(t, 33), "")

	found := h.byLocalPhoto(t, deviceID, deviceToken, "fnos", "1002")
	if found.ImageID != registered.ImageID {
		t.Errorf("imageId = %q, want the registered %q", found.ImageID, registered.ImageID)
	}
	if found.Code != registered.Code {
		t.Errorf("code = %q, want the registered %q", found.Code, registered.Code)
	}
	if found.CommentCount != registered.CommentCount {
		t.Errorf("commentCount = %d, want %d", found.CommentCount, registered.CommentCount)
	}
	// The URLs are part of the answer on purpose: the album end draws this code, and a
	// lookup that came back without a picture to fetch would make every re-entry a
	// registration again.
	if found.QRURL == "" || found.ThumbURL == "" {
		t.Errorf("the answer carries no URLs: %+v", found)
	}
}

// TestByLocalMissCreatesNothing is the property the whole design rests on: asking about a
// photo nobody has opened 图评 on leaves the server exactly as it was.
func TestByLocalMissCreatesNothing(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, _ := h.issueDevice("TV")

	before := h.thumbBytes()
	resp := h.byLocal(deviceID, deviceToken, "fnos", "never-opened")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatalf("a photo nobody opened answered %d, want 404", resp.StatusCode)
	}
	var envelope struct {
		Error struct {
			Code string `json:"code"`
		} `json:"error"`
	}
	decode(t, resp.Body, &envelope)
	if envelope.Error.Code != "not_found" {
		t.Errorf("error code = %q, want not_found", envelope.Error.Code)
	}

	// Nothing was stored: no thumbnail bytes, no photos, no codes. `thumbBytes` is the one
	// figure /healthz reports, and a registration would have moved it.
	after := h.thumbBytes()
	if after != before {
		t.Errorf("a lookup stored a thumbnail: %d -> %d bytes", before, after)
	}
	// And the same question asked twice is still a miss rather than a row that appeared.
	resp2 := h.byLocal(deviceID, deviceToken, "fnos", "never-opened")
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusNotFound {
		t.Errorf("the second lookup answered %d, so the first one created something", resp2.StatusCode)
	}
}

// TestByLocalIsPerDevice: the mapping is the *device's* memory of what it registered. A
// second television showing the same album has never opened 图评 on those photos, and the
// answer has to be "not from me" rather than somebody else's code.
func TestByLocalIsPerDevice(t *testing.T) {
	h := newHarness(t)
	firstID, firstToken, firstOwner := h.issueDevice("TV one")
	h.registerPhoto(firstID, firstToken, firstOwner, "1002", photoFixture(t, 33), "")

	secondID, secondToken, _ := h.issueDevice("TV two")
	resp := h.byLocal(secondID, secondToken, "fnos", "1002")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("the second device was handed the first device's code: %d", resp.StatusCode)
	}

	// The local id is part of the key too, so a different photo id is a different question.
	resp2 := h.byLocal(firstID, firstToken, "fnos", "1003")
	defer resp2.Body.Close()
	if resp2.StatusCode != http.StatusNotFound {
		t.Errorf("a local id that was never registered answered %d", resp2.StatusCode)
	}
}

// TestByLocalNeedsTheDeviceToken: a device token is what makes "this device's photos" a
// meaningful question at all.
func TestByLocalNeedsTheDeviceToken(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	h.registerPhoto(deviceID, deviceToken, ownerToken, "1002", photoFixture(t, 33), "")

	for name, headers := range map[string]map[string]string{
		"no headers at all": {},
		"an id but no token": {
			"X-Device-Id": deviceID,
		},
		"a token that is not this device's": {
			"X-Device-Id":    deviceID,
			"X-Device-Token": "not-the-token",
		},
	} {
		resp := h.do(http.MethodGet, "/v1/photos/by-local?source=fnos&localId=1002", nil, headers)
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("%s answered %d, want 401", name, resp.StatusCode)
		}
	}
}

// TestByLocalWantsBothHalvesOfTheKey: the question is meaningless without a photo to ask
// about, and answering it with "not found" would hide a caller's bug behind a 404.
func TestByLocalWantsBothHalvesOfTheKey(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, _ := h.issueDevice("TV")

	for _, path := range []string{
		"/v1/photos/by-local",
		"/v1/photos/by-local?source=fnos",
		"/v1/photos/by-local?localId=1002",
		"/v1/photos/by-local?source=%20&localId=%20",
	} {
		resp := h.do(http.MethodGet, path, nil, map[string]string{
			"X-Device-Id": deviceID, "X-Device-Token": deviceToken,
		})
		resp.Body.Close()
		if resp.StatusCode != http.StatusBadRequest {
			t.Errorf("%s answered %d, want 400", path, resp.StatusCode)
		}
	}
}

// TestByLocalFollowsAMergeAndCountsComments: after two registrations of the same picture are
// merged, both local ids have to answer with the same — surviving — identity and its whole
// thread. Anything else gives one picture two threads, which is the failure the layered
// fingerprint exists to prevent (§3.2).
func TestByLocalFollowsAMergeAndCountsComments(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	first := h.registerPhoto(deviceID, deviceToken, ownerToken, "1002", photoFixture(t, 33), "")
	second := h.registerPhoto(deviceID, deviceToken, ownerToken, "2007", photoFixture(t, 54), "")

	resp := h.postComment(second.Code, "被合并的那张上的话", "客人", "merge-idem-1")
	var posted commentResponse
	decode(t, resp.Body, &posted)
	resp.Body.Close()

	if err := h.store.Merge(context.Background(), second.ImageID, first.ImageID, "test", 0); err != nil {
		t.Fatalf("merge: %v", err)
	}

	later := h.byLocalPhoto(t, deviceID, deviceToken, "fnos", "2007")
	if later.ImageID != first.ImageID {
		t.Errorf("the merged photo's local id answers %q, want the root %q", later.ImageID, first.ImageID)
	}
	if later.CommentCount != 1 {
		t.Errorf("commentCount = %d, want 1 (the thread that came with the merge)", later.CommentCount)
	}
	if later.Code != second.Code {
		t.Errorf("code = %q, want the code this local id was registered under (%q)", later.Code, second.Code)
	}
}

// TestByLocalIsNotWalkedOverByPhotoGet is the routing question: `by-local` is a literal
// segment where `{code}` is a wildcard, and the more specific pattern has to win — otherwise
// the endpoint is silently unreachable and every lookup answers "短码无效".
func TestByLocalIsNotWalkedOverByPhotoGet(t *testing.T) {
	h := newHarness(t)
	deviceID, deviceToken, ownerToken := h.issueDevice("TV")
	registered := h.registerPhoto(deviceID, deviceToken, ownerToken, "1002", photoFixture(t, 33), "")

	// The wildcard route still works for a real code.
	byCode := h.do(http.MethodGet, "/v1/photos/"+registered.Code, nil, nil)
	defer byCode.Body.Close()
	if byCode.StatusCode != http.StatusOK {
		t.Fatalf("GET /v1/photos/{code}: %d", byCode.StatusCode)
	}

	resp := h.byLocal(deviceID, deviceToken, "fnos", "1002")
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		t.Fatalf("by-local answered %d — the wildcard route is swallowing it", resp.StatusCode)
	}
	var body map[string]any
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body["code"] != registered.Code {
		t.Errorf("by-local returned %v, want the registered code", body["code"])
	}
}
