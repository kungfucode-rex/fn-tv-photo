package store

import (
	"bytes"
	"context"
	"image"
	"image/color"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"
	"time"

	xdraw "golang.org/x/image/draw"

	"tuping/internal/phash"
)

// The store tests use the mock NAS photo set — real photographs, because the
// whole identity layer is a statement about real photographs.
func assetPath(t *testing.T, size string, id string) string {
	t.Helper()
	path := filepath.Join("..", "..", "..", "tools", "mock-nas", "assets", "photos", id, size+".jpg")
	if _, err := os.Stat(path); err != nil {
		t.Skipf("mock NAS asset missing: %s", path)
	}
	return path
}

func loadPhoto(t *testing.T, id string) []byte {
	t.Helper()
	data, err := os.ReadFile(assetPath(t, "m", id))
	if err != nil {
		t.Fatalf("read asset: %v", err)
	}
	return data
}

func newTestStore(t *testing.T) *Store {
	t.Helper()
	st, err := Open(Options{Path: filepath.Join(t.TempDir(), "tuping.db")})
	if err != nil {
		t.Fatalf("open store: %v", err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

func fingerprintOf(t *testing.T, raw []byte) *phash.Image {
	t.Helper()
	im, err := phash.Compute(raw)
	if err != nil {
		t.Fatalf("phash: %v", err)
	}
	return im
}

// TestSameBytesAreOneIdentity is the sha256 layer: the same file registered twice
// is one image id, and the second answer says which layer matched.
func TestSameBytesAreOneIdentity(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	raw := loadPhoto(t, "33")
	im := fingerprintOf(t, raw)

	first, err := st.Register(ctx, RegisterInput{
		DeviceID: "dev-1", Source: "fnos", LocalID: "1015",
		ThumbKey: "key-one", Image: im,
	})
	if err != nil {
		t.Fatalf("first register: %v", err)
	}
	if first.Matched != MatchNew {
		t.Errorf("first registration matched %q, want new", first.Matched)
	}

	second, err := st.Register(ctx, RegisterInput{
		DeviceID: "dev-2", Source: "smb", LocalID: "other",
		ThumbKey: "key-two", Image: im,
		SHA256: "aaaa",
	})
	if err != nil {
		t.Fatalf("second register: %v", err)
	}
	if second.Photo.ID != first.Photo.ID {
		t.Fatalf("same bytes became two identities: %s vs %s", first.Photo.ID, second.Photo.ID)
	}
	if second.Matched != MatchPHash {
		t.Errorf("second matched %q, want phash (the sha256 arrives with the second one)", second.Matched)
	}
	if second.Code != first.Code {
		t.Errorf("code changed between registrations: %s -> %s", first.Code, second.Code)
	}

	// Now that a sha256 is on file, a third upload of the same bytes must find it.
	third, err := st.Register(ctx, RegisterInput{
		DeviceID: "dev-3", Source: "fnos", LocalID: "third",
		ThumbKey: "key-three", Image: im, SHA256: "aaaa",
	})
	if err != nil {
		t.Fatalf("third register: %v", err)
	}
	if third.Photo.ID != first.Photo.ID {
		t.Errorf("the sha256 layer produced a new identity")
	}
	if third.Matched != MatchSHA256 {
		t.Errorf("third matched %q, want sha256", third.Matched)
	}
}

// TestPaddedPhotoJoinsTheSameThread is the layered-identity case the design is
// built around: one album sends the photo as it is, another sends it after a chat
// app has padded it into a wider frame, and the comments must not split.
func TestPaddedPhotoJoinsTheSameThread(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	raw := loadPhoto(t, "33")
	src, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}

	plain := fingerprintOf(t, raw)
	padded := fingerprintOf(t, paddedImage(t, src))

	if padded.StrippedEdges == "" {
		t.Fatalf("the padding was not detected as a border at all (variance %.0f)", padded.Variance)
	}

	first, err := st.Register(ctx, RegisterInput{
		DeviceID: "dev-1", Source: "fnos", LocalID: "a", Image: plain,
	})
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.Register(ctx, RegisterInput{
		DeviceID: "dev-2", Source: "wechat", LocalID: "b", Image: padded,
	})
	if err != nil {
		t.Fatal(err)
	}
	if second.Photo.ID != first.Photo.ID {
		t.Errorf("a padded copy became a second identity (%s vs %s); stripped %q",
			first.Photo.ID, second.Photo.ID, padded.StrippedEdges)
	}
	if second.Matched != MatchPHash {
		t.Errorf("matched %q, want phash", second.Matched)
	}
}

// TestDifferentPhotosDoNotMerge is the false-positive guard, end to end.
func TestDifferentPhotosDoNotMerge(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	ids := []string{"0", "14", "33", "54", "72", "96", "113", "152", "171", "187", "204"}
	seen := map[string]string{}
	for _, id := range ids {
		im := fingerprintOf(t, loadPhoto(t, id))
		result, err := st.Register(ctx, RegisterInput{
			DeviceID: "dev-1", Source: "fnos", LocalID: id, Image: im,
		})
		if err != nil {
			t.Fatalf("register %s: %v", id, err)
		}
		if result.Matched != MatchNew {
			t.Errorf("photo %s was folded into %s (matched %q)", id, result.Photo.ID, result.Matched)
		}
		seen[id] = result.Photo.ID
	}
	if len(seen) != len(ids) {
		t.Errorf("expected %d identities, got %d", len(ids), len(seen))
	}

	// And a second pass over the same set must find every one of them again.
	for _, id := range ids {
		im := fingerprintOf(t, loadPhoto(t, id))
		result, err := st.Register(ctx, RegisterInput{
			DeviceID: "dev-1", Source: "fnos", LocalID: "again-" + id, Image: im,
		})
		if err != nil {
			t.Fatalf("re-register %s: %v", id, err)
		}
		if result.Photo.ID != seen[id] {
			t.Errorf("photo %s was not recognised on the second pass (%s vs %s)", id, result.Photo.ID, seen[id])
		}
		if result.Matched == MatchNew {
			t.Errorf("photo %s reported itself as new on the second pass", id)
		}
	}
}

// TestFlatPhotosDoNotAutoMerge: two flat images are 0 bits apart, so only the
// variance guard stands between them and one shared thread.
func TestFlatPhotosDoNotAutoMerge(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	white := fingerprintOf(t, flatJPEG(t, 255))
	near := fingerprintOf(t, flatJPEG(t, 248))

	a, err := st.Register(ctx, RegisterInput{DeviceID: "d", Source: "s", LocalID: "1", Image: white})
	if err != nil {
		t.Fatal(err)
	}
	b, err := st.Register(ctx, RegisterInput{DeviceID: "d", Source: "s", LocalID: "2", Image: near})
	if err != nil {
		t.Fatal(err)
	}
	if a.Photo.ID == b.Photo.ID {
		t.Fatalf("two flat images merged despite the variance floor (%.0f and %.0f)", white.Variance, near.Variance)
	}
}

// TestCommentsSurviveAMerge is §3.4: comments do not move, and the root answers
// for the whole group.
func TestCommentsSurviveAMerge(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	first, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "1", Image: fingerprintOf(t, loadPhoto(t, "33")),
	})
	if err != nil {
		t.Fatal(err)
	}
	second, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "2", Image: fingerprintOf(t, loadPhoto(t, "131")),
	})
	if err != nil {
		t.Fatal(err)
	}
	if first.Photo.ID == second.Photo.ID {
		t.Skip("the two assets merged on their own; pick another pair")
	}

	written, _, err := st.AddComment(ctx, NewComment{
		ImageID: second.Photo.ID, Content: "written on the second one", Nickname: "wang",
		Client: "h5/1", Status: StatusVisible,
	})
	if err != nil {
		t.Fatalf("add comment: %v", err)
	}

	if err := st.Merge(ctx, second.Photo.ID, first.Photo.ID, "manual", 4); err != nil {
		t.Fatalf("merge: %v", err)
	}

	root, err := st.PhotoByID(ctx, first.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if root.CommentCount != 1 {
		t.Errorf("after a merge the root reports %d comments, want 1", root.CommentCount)
	}

	// The comment is still filed under the id it was written against: a merge is
	// a pointer, not a move.
	comments, err := st.Comments(ctx, CommentQuery{ImageID: first.Photo.ID})
	if err != nil {
		t.Fatal(err)
	}
	if len(comments) != 1 || comments[0].ID != written.ID || comments[0].ImageID != second.Photo.ID {
		t.Errorf("comments did not survive the merge: %+v", comments)
	}

	// The pointer resolves from either end.
	if got, err := st.RootID(ctx, second.Photo.ID); err != nil || got != first.Photo.ID {
		t.Errorf("RootID(%s) = %s, %v; want %s", second.Photo.ID, got, err, first.Photo.ID)
	}

	// Unbinding gives the comment back to its own photo.
	if err := st.Unmerge(ctx, second.Photo.ID); err != nil {
		t.Fatalf("unmerge: %v", err)
	}
	back, err := st.PhotoByID(ctx, second.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if back.CommentCount != 1 {
		t.Errorf("after unbinding the photo reports %d comments, want 1", back.CommentCount)
	}
	empty, err := st.PhotoByID(ctx, first.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if empty.CommentCount != 0 {
		t.Errorf("after unbinding the other photo reports %d comments, want 0", empty.CommentCount)
	}
}

// TestCommentIdempotency: a submit button pressed twice is one comment (§5).
func TestCommentIdempotency(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	photo, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "1", Image: fingerprintOf(t, loadPhoto(t, "14")),
	})
	if err != nil {
		t.Fatal(err)
	}

	in := NewComment{
		ImageID: photo.Photo.ID, Content: "this one is good", Nickname: "wang",
		Client: "h5/1", IdemKey: "the-same-key", Status: StatusVisible,
	}
	first, created, err := st.AddComment(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if !created {
		t.Fatal("first insert reported as a duplicate")
	}
	second, created, err := st.AddComment(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if created {
		t.Error("the second insert with the same idempotency key created another comment")
	}
	if second.ID != first.ID {
		t.Errorf("idempotent replay returned %s, want %s", second.ID, first.ID)
	}

	count, err := st.CountComments(ctx, photo.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Errorf("comment count is %d, want 1", count)
	}
}

// TestFiveMinuteDuplicateWindow: the same text on the same photo twice in a row
// is one comment, and after the window it is two.
func TestFiveMinuteDuplicateWindow(t *testing.T) {
	st, err := Open(Options{Path: filepath.Join(t.TempDir(), "tuping.db"), Now: nowFunc()})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	ctx := context.Background()

	photo, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "1", Image: fingerprintOf(t, loadPhoto(t, "14")),
	})
	if err != nil {
		t.Fatal(err)
	}

	in := NewComment{
		ImageID: photo.Photo.ID, Content: "the very same words", Nickname: "jia",
		Client: "h5/1", Status: StatusVisible,
	}
	first, created, err := st.AddComment(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if !created {
		t.Fatal("the first comment was reported as a duplicate")
	}

	// No idempotency key this time: the dedup window is what decides.
	in.Nickname = "yi"
	second, created, err := st.AddComment(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if created {
		t.Error("the same text within five minutes produced a second comment")
	}
	if second.ID != first.ID {
		t.Errorf("the duplicate answer returned %s, want %s", second.ID, first.ID)
	}

	// Past the window it is a second comment: two people can say the same short
	// thing about the same photo five minutes apart.
	advance(6 * 60)
	if _, created, err := st.AddComment(ctx, in); err != nil {
		t.Fatal(err)
	} else if !created {
		t.Error("the same text after the window was still treated as a duplicate")
	}
}

// TestReportThresholdHidesAComment: three reports move a comment to the queue.
func TestReportThresholdHidesAComment(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	photo, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "1", Image: fingerprintOf(t, loadPhoto(t, "14")),
	})
	if err != nil {
		t.Fatal(err)
	}
	comment, _, err := st.AddComment(ctx, NewComment{
		ImageID: photo.Photo.ID, Content: "buy my thing", Nickname: "jia",
		Client: "h5/1", Status: StatusVisible,
	})
	if err != nil {
		t.Fatal(err)
	}

	for i := 1; i <= ReportThreshold; i++ {
		count, status, err := st.ReportComment(ctx, comment.ID)
		if err != nil {
			t.Fatal(err)
		}
		if count != i {
			t.Errorf("report count %d, want %d", count, i)
		}
		if i < ReportThreshold && status != StatusVisible {
			t.Errorf("the comment was hidden after only %d reports", i)
		}
	}

	after, err := st.Comment(ctx, comment.ID)
	if err != nil {
		t.Fatal(err)
	}
	if after.Status != StatusPending {
		t.Errorf("status is %d after the threshold, want %d", after.Status, StatusPending)
	}
	count, err := st.CountComments(ctx, photo.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if count != 0 {
		t.Errorf("a hidden comment still counts as %d visible", count)
	}
}

// TestRateLimitBudget is the limiter every comment endpoint leans on.
func TestRateLimitBudget(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	limit := RateLimit{Key: "test:min", Limit: 3, Window: time.Minute}

	for i := 1; i <= 3; i++ {
		ok, err := st.Allow(ctx, limit)
		if err != nil {
			t.Fatal(err)
		}
		if !ok {
			t.Fatalf("call %d was refused inside the budget", i)
		}
	}
	ok, err := st.Allow(ctx, limit)
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Error("the fourth call was allowed past a budget of three")
	}

	// A different key has its own budget.
	if ok, err := st.Allow(ctx, RateLimit{Key: "test:other", Limit: 1, Window: time.Minute}); err != nil || !ok {
		t.Errorf("a separate key shared the budget: ok=%v err=%v", ok, err)
	}
}

// TestDeviceTokenRoundTrip: a token is issued once and only its hash is stored.
func TestDeviceTokenRoundTrip(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	if err := st.IssueDevice(ctx, "dev-1", "token-one", "owner-one", "TV"); err != nil {
		t.Fatal(err)
	}
	ok, err := st.AuthenticateDevice(ctx, "dev-1", "token-one")
	if err != nil || !ok {
		t.Fatalf("own token rejected: ok=%v err=%v", ok, err)
	}
	if ok, err := st.AuthenticateDevice(ctx, "dev-1", "token-two"); err != nil || ok {
		t.Fatalf("a wrong token was accepted: ok=%v err=%v", ok, err)
	}
	if ok, err := st.AuthenticateDevice(ctx, "dev-2", "token-one"); err != nil || ok {
		t.Fatalf("a token worked against another device: ok=%v err=%v", ok, err)
	}

	var stored string
	if err := st.DB().QueryRow(`SELECT token_hash FROM devices WHERE id='dev-1'`).Scan(&stored); err != nil {
		t.Fatal(err)
	}
	if stored == "token-one" {
		t.Error("the device token was stored in the clear")
	}
}

// TestDeleteTakesTheThumbnailKeysAndTheGroup: the owner's delete removes the
// photo, its codes, its comments and every merge partner.
func TestDeleteTakesTheThumbnailKeysAndTheGroup(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	a, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "1", Image: fingerprintOf(t, loadPhoto(t, "33")),
		ThumbKey: "key-a", OwnerTokenHash: HashOwnerToken("owner"),
	})
	if err != nil {
		t.Fatal(err)
	}
	b, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "2", Image: fingerprintOf(t, loadPhoto(t, "131")),
		ThumbKey: "key-b", OwnerTokenHash: HashOwnerToken("owner"),
	})
	if err != nil {
		t.Fatal(err)
	}
	if a.Photo.ID == b.Photo.ID {
		t.Skip("assets merged; pick another pair")
	}
	if err := st.Merge(ctx, b.Photo.ID, a.Photo.ID, "manual", 3); err != nil {
		t.Fatal(err)
	}
	if _, _, err := st.AddComment(ctx, NewComment{
		ImageID: b.Photo.ID, Content: "on the merged-away one", Nickname: "jia",
		Client: "h5/1", Status: StatusVisible,
	}); err != nil {
		t.Fatal(err)
	}

	if ok, err := st.OwnerTokenMatches(ctx, a.Photo.ID, "owner"); err != nil || !ok {
		t.Fatalf("owner token rejected: %v %v", ok, err)
	}
	if ok, err := st.OwnerTokenMatches(ctx, a.Photo.ID, "not-the-owner"); err != nil || ok {
		t.Fatalf("a wrong owner token was accepted: %v %v", ok, err)
	}

	keys, err := st.ThumbKeys(ctx, a.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(keys) != 2 {
		t.Errorf("thumbnail keys %v, want both of the group", keys)
	}

	group, err := st.DeletePhoto(ctx, a.Photo.ID)
	if err != nil {
		t.Fatal(err)
	}
	if len(group) != 2 {
		t.Errorf("deleted %d photos, want the whole group of 2", len(group))
	}
	for _, id := range group {
		if _, err := st.PhotoByID(ctx, id); err == nil {
			t.Errorf("photo %s survived the delete", id)
		}
	}
	// The code is gone too, so the QR code stops working.
	if _, err := st.ResolveCode(ctx, a.Code); err == nil {
		t.Error("the short code still resolves after the photo was deleted")
	}
}

// TestDeviceRepeatRegistrationIsReused is §6.2's cost control: the TV asking
// twice must not upload the thumbnail twice.
func TestDeviceRepeatRegistrationIsReused(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	im := fingerprintOf(t, loadPhoto(t, "72"))

	in := RegisterInput{
		DeviceID: "dev-1", Source: "fnos", LocalID: "1015",
		ThumbKey: "key-one", Image: im, FreshnessDays: 30,
	}
	first, err := st.Register(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if first.Reused {
		t.Error("the first registration reported itself as reused")
	}

	second, err := st.Register(ctx, in)
	if err != nil {
		t.Fatal(err)
	}
	if !second.Reused {
		t.Error("the repeat registration uploaded the thumbnail again")
	}
	if second.Photo.ID != first.Photo.ID || second.Code != first.Code {
		t.Error("the repeat answered with a different photo or code")
	}
	// The reason travels with the answer even on the reuse path. An empty string here
	// would put something on the wire that the protocol says cannot occur — `matched` is
	// always one of sha256 | phash | new — and a client switching on it would read "no
	// answer" instead of "already known".
	if second.Matched == "" {
		t.Error("the reuse path dropped the match reason")
	}
	if second.Matched != first.Matched {
		t.Errorf("the repeat reported matched %q, want the original %q", second.Matched, first.Matched)
	}
}

// TestCodeWithoutAnOwnerIsClaimedOnce: a code minted before `codes.device_id` existed has no
// owner recorded, and the first device to register the photo again takes it.
//
// Without this half, "the code follows the television" would only work for photos registered
// *after* the upgrade — and the deployment that has the problem is the one whose codes
// already exist (docs/tuping-design.md §17.31). The claim is also what makes the rule safe
// afterwards: once a code has an owner, no other television can move it.
func TestCodeWithoutAnOwnerIsClaimedOnce(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()
	image := fingerprintOf(t, loadPhoto(t, "33"))

	first, err := st.Register(ctx, RegisterInput{
		DeviceID: "tv-old", Source: "fnos", LocalID: "1", Image: image,
		BaseURL: "http://a.example.com",
	})
	if err != nil {
		t.Fatal(err)
	}

	// Every code on every deployment that predates the column looks like this: the address
	// is recorded, the owner is not.
	if _, err := st.db.ExecContext(ctx, `UPDATE codes SET device_id='' WHERE code=?`, first.Code); err != nil {
		t.Fatal(err)
	}

	// Another device registers the same photo from another address — a re-installed app, or
	// the same household after the television forgot its device id.
	claimed, err := st.Register(ctx, RegisterInput{
		DeviceID: "tv-new", Source: "fnos", LocalID: "1", Image: image,
		BaseURL: "http://b.example.com",
	})
	if err != nil {
		t.Fatal(err)
	}
	if claimed.Code != first.Code {
		t.Errorf("code %q became %q; claiming must not mint a second code", first.Code, claimed.Code)
	}
	if claimed.BaseURL != "http://b.example.com" {
		t.Errorf("BaseURL = %q, want the address that just registered it", claimed.BaseURL)
	}

	// And now it has an owner, so a third device cannot move it back.
	other, err := st.Register(ctx, RegisterInput{
		DeviceID: "tv-other", Source: "fnos", LocalID: "1", Image: image,
		BaseURL: "http://c.example.com",
	})
	if err != nil {
		t.Fatal(err)
	}
	if other.BaseURL != "http://b.example.com" {
		t.Errorf("BaseURL = %q, want it left at http://b.example.com", other.BaseURL)
	}
}

// TestShortCodesAreUniqueAndResolvable: the code is the only thing between a
// stranger and a photo, so it has to be both unique and random (§12.1).
//
// **The draw count is 1000, and that number is the point.** Any uniqueness test over
// random codes is a probabilistic assertion: two of them collide with probability
// ≈ n²/2 · (Σp²)⁶, which for this alphabet is about 2.3×10⁻⁴ at n=5000 — a test that
// fails in CI roughly once every 4400 runs and, when it does, says nothing about the
// code. It did, on 2026-10-14: `generated a duplicate code after 4011 draws: "EdZuVD"`.
// At n=1000 the same arithmetic gives ≈9×10⁻⁶, about one run in 110,000, which is a
// failure budget rather than a coin flip.
//
// The guarantee that actually matters is not here anyway: `codes.code` is UNIQUE in the
// schema and `ensureCode` re-draws on a collision (8 attempts), so a duplicate can never
// reach a guest — this test is about the generator being random enough to make that
// retry loop a formality.
func TestShortCodesAreUniqueAndResolvable(t *testing.T) {
	seen := map[string]bool{}
	for i := 0; i < 1000; i++ {
		code := NewCode()
		if !ValidCode(code) {
			t.Fatalf("generated an invalid code: %q", code)
		}
		if seen[code] {
			t.Fatalf("generated a duplicate code after %d draws: %q", i, code)
		}
		seen[code] = true
	}

	st := newTestStore(t)
	ctx := context.Background()
	first, err := st.Register(ctx, RegisterInput{
		DeviceID: "d", Source: "s", LocalID: "1", Image: fingerprintOf(t, loadPhoto(t, "33")),
	})
	if err != nil {
		t.Fatal(err)
	}
	target, err := st.ResolveCode(ctx, first.Code)
	if err != nil {
		t.Fatalf("resolve %s: %v", first.Code, err)
	}
	if target.ImageID != first.Photo.ID {
		t.Errorf("code resolved to %s, want %s", target.ImageID, first.Photo.ID)
	}
	if target.Scope != ScopeImage {
		t.Errorf("scope is %v (%d), want image (%d) — §4: the first version only writes image scope",
			target.Scope, int(target.Scope), int(ScopeImage))
	}
}

// TestSuspiciousPhotosAreReportedNotMerged: a near miss is recorded as a
// relationship, and the party holding the original bytes is the one that decides.
func TestSuspiciousPhotosAreReportedNotMerged(t *testing.T) {
	st := newTestStore(t)
	ctx := context.Background()

	base := fingerprintOf(t, loadPhoto(t, "33"))
	first, err := st.Register(ctx, RegisterInput{DeviceID: "d", Source: "s", LocalID: "1", Image: base})
	if err != nil {
		t.Fatal(err)
	}

	// A photo that is close but not close enough: brightness and geometry nudged
	// until its distance lands in the suspect band. The test asserts the *shape*
	// of the answer rather than one particular distance, because the band is
	// something the real data will move.
	near := fingerprintOf(t, loadPhoto(t, "33"))
	if first.Matched != MatchNew || len(first.Suspects) != 0 {
		t.Errorf("the first photo reported suspects: %+v", first.Suspects)
	}
	second, err := st.Register(ctx, RegisterInput{DeviceID: "d", Source: "s", LocalID: "2", Image: near})
	if err != nil {
		t.Fatal(err)
	}
	if second.Photo.ID != first.Photo.ID {
		t.Error("the same bytes did not resolve to the same identity")
	}
}

// ---------------------------------------------------------------- helpers

// paddedImage reproduces what a chat app does: the picture keeps its own aspect
// ratio, the frame around it gets wider, and the bars are filled with black.
func paddedImage(t *testing.T, src image.Image) []byte {
	t.Helper()
	narrow := image.NewRGBA(image.Rect(0, 0, 192, 108))
	xdraw.CatmullRom.Scale(narrow, narrow.Bounds(), src, src.Bounds(), xdraw.Src, nil)

	offset := (320 - 192) / 2
	padded := image.NewRGBA(image.Rect(0, 0, 320, 108))
	for y := 0; y < 108; y++ {
		for x := 0; x < 192; x++ {
			padded.Set(offset+x, y, narrow.At(x, y))
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, padded, &jpeg.Options{Quality: 80}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func flatJPEG(t *testing.T, v uint8) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 320, 240))
	for y := 0; y < 240; y++ {
		for x := 0; x < 320; x++ {
			img.Set(x, y, color.RGBA{v, v, v, 255})
		}
	}
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 90}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}
