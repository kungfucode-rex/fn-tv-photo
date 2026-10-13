package store

import (
	"context"
	"crypto/subtle"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"strings"
	"sync"

	"tuping/internal/phash"
)

// Photo is one row of photos, with merged_into already followed: callers never
// see a photo that has been folded into another one.
type Photo struct {
	ID             string
	SHA256         string
	Fingerprint    phash.Fingerprint
	Stripped       *phash.Fingerprint
	StrippedWidth  int
	StrippedHeight int
	PhashVersion   int
	Width          int
	Height         int
	Variance       int
	ThumbKey       string
	ThumbExpireAt  int64
	CommentCount   int
	Visibility     int
	DeviceID       string
	FirstSeenAt    int64
	LastSeenAt     int64
}

// AspectRatio is width/height, the filter the candidate set uses.
func (p *Photo) AspectRatio() float64 {
	if p.Height == 0 {
		return 0
	}
	return float64(p.Width) / float64(p.Height)
}

// RegisterInput is one POST /v1/photos.
type RegisterInput struct {
	DeviceID string
	Source   string
	LocalID  string

	// Thumb is the re-encoded canonical thumbnail, already stripped of EXIF.
	Thumb []byte

	// ThumbKey and ThumbExpiresAt tell the sweep when the bytes may go.
	ThumbKey       string
	ThumbExpiresAt int64

	SHA256 string // sha256 of the original file, when the album had it

	Image *phash.Image

	OwnerTokenHash string
	Scope          Scope
	ScopeID        string
	Visibility     int

	// FreshnessDays is how long a "same device, same photo" answer is reused
	// without touching the stored thumbnail (§6.2).
	FreshnessDays int

	// BaseURL is the address the registering device uses to reach this server, and it
	// becomes the address its QR codes point at. Empty means the device did not say, and
	// the caller's own configured base URL is used instead.
	//
	// It is recorded per code rather than per device because that is what a QR code is: a
	// link somebody else's phone will open. See the note on the `codes.base_url` migration.
	BaseURL string
}

// RegisterResult is what the album needs back.
type RegisterResult struct {
	Photo   *Photo
	Code    string
	Matched MatchResult

	// Reused is true when the album already registered this photo recently and the
	// server answered from the stored row.
	Reused bool

	// Suspects are photos that came close but were not merged. Empty in the
	// overwhelming majority of registrations; the album only logs them.
	Suspects []Suspect

	// BaseURL is the address recorded on the code that was actually used, which is the
	// *existing* code's address when one was found rather than the one in the input. It is
	// what the reply's URLs must be built from, or a client is handed URLs for a host its
	// code does not live on.
	BaseURL string
}

// registerLock serialises the read-then-write in Register. Two photos arriving
// at once could otherwise both decide they are new, and the second insert would
// be a duplicate identity that only a human could ever unpick.
var registerLock sync.Mutex

// Register resolves an uploaded thumbnail to an image id and hands back a code.
//
// The order is the one in docs/tuping-design.md §3.2: sha256 first when the album
// had the original bytes, then the layered pHash, and only then a new identity.
func (s *Store) Register(ctx context.Context, in RegisterInput) (*RegisterResult, error) {
	if in.Image == nil {
		return nil, errors.New("store: register without a fingerprint")
	}
	// The zero value of Scope is "unset", not "image domain", and a row with
	// scope_type 0 would be a thread no query could find. Both scopes exist from
	// the first version (§4) but only image is ever written (D5).
	if in.Scope != ScopeEvent {
		in.Scope = ScopeImage
	}
	if in.Visibility != VisibilityPrivate && in.Visibility != VisibilityDisabled {
		in.Visibility = VisibilityImage
	}

	registerLock.Lock()
	defer registerLock.Unlock()

	now := s.nowMS()

	// Repeat registration from the same device: answer from the row we already
	// have instead of uploading the thumbnail again. This is how "pre-registering"
	// a photo on the TV stays cheap (§6.2).
	if in.DeviceID != "" && in.LocalID != "" {
		if photo, code, codeBase, matched, ok, err := s.recentDevicePhoto(ctx, in, now); err != nil {
			return nil, err
		} else if ok {
			// `matched` is the layer that resolved this photo the first time, kept on
			// the mapping. Reporting the zero value here would put an empty string on
			// the wire where the protocol promises sha256 | phash | new — and a client
			// that switches on it would read "no answer" rather than "already known".
			//
			// The code's own address is looked up rather than assumed: this path answers
			// from a row written earlier, possibly on a different network, and the reply's
			// URLs have to point at wherever that code actually lives.
			return &RegisterResult{Photo: photo, Code: code, Matched: matched, Reused: true, BaseURL: codeBase}, nil
		}
	}

	photoID, matched, err := s.resolveIdentity(ctx, in, now)
	if err != nil {
		return nil, err
	}
	// Read the suspects out immediately: they belong to the registration that just
	// happened, and the next one overwrites them.
	suspects := s.takeSuspects()

	// A photo that already exists keeps its first_seen_at and gains a
	// last_seen_at; the canonical thumbnail is only rewritten if it expired.
	if matched != MatchNew {
		if err := s.touchPhoto(ctx, photoID, in, now); err != nil {
			return nil, err
		}
	}

	if in.DeviceID != "" && in.LocalID != "" {
		if err := s.rememberDevicePhoto(ctx, in, photoID, matched, now); err != nil {
			return nil, err
		}
	}

	code, codeBase, err := s.ensureCode(ctx, photoID, in.Scope, in.ScopeID, now, in.BaseURL)
	if err != nil {
		return nil, err
	}

	photo, err := s.PhotoByID(ctx, photoID)
	if err != nil {
		return nil, err
	}
	return &RegisterResult{Photo: photo, Code: code, Matched: matched, Suspects: suspects, BaseURL: codeBase}, nil
}

// takeSuspects reads and clears the suspects left by the most recent
// resolveIdentity. Register holds registerLock across both calls, so there is no
// race in which one registration could read another's suspects.
func (s *Store) takeSuspects() []Suspect {
	suspects := s.lastSuspects
	s.lastSuspects = nil
	return suspects
}

// Suspect is a photo that looked similar but was not merged. It is only ever
// reported, never acted on: the party holding the original bytes is the one that
// gets to decide, by sending a sha256 (§3.2).
type Suspect struct {
	ImageID  string
	Distance int
}

// resolveIdentity is §3.2's decision tree, in order.
func (s *Store) resolveIdentity(ctx context.Context, in RegisterInput, now int64) (string, MatchResult, error) {
	if in.SHA256 != "" {
		id, err := s.photoIDBySHA256(ctx, in.SHA256)
		if err != nil && !errors.Is(err, ErrNotFound) {
			return "", "", err
		}
		if id != "" {
			root, err := s.RootID(ctx, id)
			if err != nil {
				return "", "", err
			}
			return root, MatchSHA256, nil
		}
	}

	candidates, err := s.phashCandidates(ctx)
	if err != nil {
		return "", "", err
	}

	mine := in.Image.Candidates()
	myVariance := in.Image.Variance
	var suspects []Suspect

	for i := range candidates {
		c := &candidates[i]
		if c.Height == 0 || c.Width == 0 {
			continue
		}

		theirs := make([]phash.Candidate, 0, 2)
		theirs = append(theirs, phash.Candidate{
			Fingerprint: c.Fingerprint, Width: c.Width, Height: c.Height,
		})
		if c.Stripped != nil {
			theirs = append(theirs, phash.Candidate{
				Fingerprint: *c.Stripped, Width: c.StrippedWidth, Height: c.StrippedHeight,
			})
		}

		for _, a := range mine {
			if a.Height == 0 || a.Ratio() == 0 {
				continue
			}
			for _, b := range theirs {
				if b.Height == 0 || b.Ratio() == 0 {
					continue
				}
				// A cropped photo must not even be considered: its aspect ratio is
				// the cheapest proof that it is a different picture (§3.3). This is
				// checked per candidate, not once per photo — the stripped
				// candidate has a different shape from the padded one it came from,
				// and using the padded shape is exactly what makes the padding case
				// fail.
				if math.Abs(b.Ratio()-a.Ratio())/a.Ratio() > phash.RatioTolerance {
					continue
				}

				pd, dd := a.Fingerprint.PHashDistance(b.Fingerprint), a.Fingerprint.DHashDistance(b.Fingerprint)

				// Flat images collide with one another rather than hashing
				// randomly (§15.1), so a low variance on either side forbids an
				// automatic merge no matter how close the bits are.
				lowVariance := myVariance < phash.MinVariance || float64(c.Variance) < phash.MinVariance
				if pd <= phash.AutoMergeDistance && dd <= phash.AutoMergeDistance && !lowVariance {
					return c.ID, MatchPHash, nil
				}
				// Close, but not close enough to act on. The relationship is
				// reported rather than merged: the party holding the original
				// bytes is the one that gets to decide, by sending a sha256 (§3.2).
				if maxInt(pd, dd) <= phash.SuspectDistance {
					suspects = append(suspects, Suspect{ImageID: c.ID, Distance: pd + dd})
				}
			}
		}
	}

	result, _, err := s.insertPhoto(ctx, in, now)
	if err != nil {
		return "", "", err
	}
	s.lastSuspects = suspects
	return result, MatchNew, nil
}

// insertPhoto writes a brand-new identity.
func (s *Store) insertPhoto(ctx context.Context, in RegisterInput, now int64) (string, MatchResult, error) {
	id := NewID()
	im := in.Image

	var variance int
	if im.Variance < 0 {
		variance = 0
	} else {
		variance = int(im.Variance)
	}
	if variance > math.MaxInt32 {
		variance = math.MaxInt32
	}

	_, err := s.db.ExecContext(ctx, `
		INSERT INTO photos (
			id, sha256, phash, phash_version, phash_stripped,
			stripped_width, stripped_height,
			width, height, variance, thumb_key, thumb_expire_at,
			comment_count, visibility, owner_token_hash, device_id,
			first_seen_at, last_seen_at
		) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?)`,
		id,
		nullableString(in.SHA256),
		im.Plain.Bytes(),
		phash.Version,
		nullableBytes(strippedBytes(im)),
		im.StrippedWidth, im.StrippedHeight,
		im.Width, im.Height, variance,
		in.ThumbKey, in.ThumbExpiresAt,
		in.Visibility,
		nullableString(in.OwnerTokenHash),
		nullableString(in.DeviceID),
		now, now,
	)
	if err != nil {
		return "", "", fmt.Errorf("store: insert photo: %w", err)
	}
	return id, MatchNew, nil
}

// touchPhoto refreshes a photo that was recognised again.
func (s *Store) touchPhoto(ctx context.Context, id string, in RegisterInput, now int64) error {
	im := in.Image
	var variance = clampVariance(im.Variance)

	// The sha256 is filled in when the album finally has the original bytes: this
	// is the "a party that holds the original claims the identity" step.
	_, err := s.db.ExecContext(ctx, `
		UPDATE photos SET
			last_seen_at = ?,
			sha256 = COALESCE(sha256, ?),
			thumb_key = CASE WHEN thumb_expire_at IS NULL OR thumb_expire_at < ? THEN ? ELSE thumb_key END,
			thumb_expire_at = CASE WHEN thumb_expire_at IS NULL OR thumb_expire_at < ? THEN ? ELSE thumb_expire_at END,
			variance = CASE WHEN variance = 0 THEN ? ELSE variance END,
			device_id = COALESCE(device_id, ?),
			owner_token_hash = COALESCE(owner_token_hash, ?)
		WHERE id = ?`,
		now,
		nullableString(in.SHA256),
		now, in.ThumbKey,
		now, in.ThumbExpiresAt,
		variance,
		nullableString(in.DeviceID),
		nullableString(in.OwnerTokenHash),
		id,
	)
	if err != nil {
		return fmt.Errorf("store: touch photo: %w", err)
	}
	return nil
}

// photoIDBySHA256 finds the photo whose original bytes hash to this value.
func (s *Store) photoIDBySHA256(ctx context.Context, sha string) (string, error) {
	var id string
	err := s.db.QueryRowContext(ctx,
		`SELECT id FROM photos WHERE sha256 = ? ORDER BY first_seen_at LIMIT 1`, sha).Scan(&id)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	if err != nil {
		return "", fmt.Errorf("store: lookup sha256: %w", err)
	}
	return id, nil
}

// RootID follows merged_into until it stops. This is the union-find read that
// makes a merge reversible: nothing moves, one pointer is written.
func (s *Store) RootID(ctx context.Context, id string) (string, error) {
	const maxHops = 64
	for i := 0; i < maxHops; i++ {
		var next sql.NullString
		err := s.db.QueryRowContext(ctx, `SELECT merged_into FROM photos WHERE id = ?`, id).Scan(&next)
		if errors.Is(err, sql.ErrNoRows) {
			return "", ErrNotFound
		}
		if err != nil {
			return "", fmt.Errorf("store: follow merge: %w", err)
		}
		if !next.Valid || next.String == "" {
			return id, nil
		}
		id = next.String
	}
	return id, nil
}

// GroupIDs returns every image id that has been merged into this one, root first.
// Comment counts are summed across the group, because a merge never moves rows.
func (s *Store) GroupIDs(ctx context.Context, id string) ([]string, error) {
	root, err := s.RootID(ctx, id)
	if err != nil {
		return nil, err
	}
	rows, err := s.db.QueryContext(ctx, `SELECT from_image_id FROM merges WHERE to_image_id = ?`, root)
	if err != nil {
		return nil, fmt.Errorf("store: group: %w", err)
	}
	defer rows.Close()

	out := []string{root}
	for rows.Next() {
		var from string
		if err := rows.Scan(&from); err != nil {
			return nil, err
		}
		out = append(out, from)
	}
	return out, rows.Err()
}

// photoByIDWithGroup loads a photo with its comment count summed over the group.
func (s *Store) photoByIDWithGroup(ctx context.Context, id string) (*Photo, error) {
	root, err := s.RootID(ctx, id)
	if err != nil {
		return nil, err
	}

	p, err := s.scanPhoto(ctx, `SELECT `+photoColumns+` FROM photos WHERE id = ?`, root)
	if err != nil {
		return nil, err
	}

	group, err := s.GroupIDs(ctx, root)
	if err != nil {
		return nil, err
	}
	if len(group) > 1 {
		placeholders := strings.TrimSuffix(strings.Repeat("?,", len(group)), ",")
		args := make([]any, len(group))
		for i, g := range group {
			args[i] = g
		}
		var total int
		err := s.db.QueryRowContext(ctx,
			`SELECT COUNT(*) FROM comments WHERE image_id IN (`+placeholders+`) AND status = ?`,
			append(args, StatusVisible)...).Scan(&total)
		if err != nil {
			return nil, fmt.Errorf("store: group comment count: %w", err)
		}
		p.CommentCount = total
	}
	return p, nil
}

// PhotoByID loads one photo, following merges and summing the group's comments.
func (s *Store) PhotoByID(ctx context.Context, id string) (*Photo, error) {
	return s.photoByIDWithGroup(ctx, id)
}

const photoColumns = `id, COALESCE(sha256,''), phash, phash_version, phash_stripped,
	stripped_width, stripped_height,
	width, height, variance, COALESCE(thumb_key,''), COALESCE(thumb_expire_at,0),
	comment_count, visibility, COALESCE(device_id,''), first_seen_at, last_seen_at`

func (s *Store) scanPhoto(ctx context.Context, query string, args ...any) (*Photo, error) {
	var (
		p            Photo
		phashBlob    []byte
		strippedBlob []byte
	)
	err := s.db.QueryRowContext(ctx, query, args...).Scan(
		&p.ID, &p.SHA256, &phashBlob, &p.PhashVersion, &strippedBlob,
		&p.StrippedWidth, &p.StrippedHeight,
		&p.Width, &p.Height, &p.Variance, &p.ThumbKey, &p.ThumbExpireAt,
		&p.CommentCount, &p.Visibility, &p.DeviceID, &p.FirstSeenAt, &p.LastSeenAt,
	)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("store: scan photo: %w", err)
	}
	p.Fingerprint = phash.FingerprintFromBytes(phashBlob)
	if len(strippedBlob) > 0 {
		f := phash.FingerprintFromBytes(strippedBlob)
		p.Stripped = &f
	}
	return &p, nil
}

// phashCandidates returns every identity that could still be merged into:
// §5.1 keeps this a full table scan, because 16 bytes × a million rows is 16 MB.
func (s *Store) phashCandidates(ctx context.Context) ([]Photo, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT `+photoColumns+` FROM photos WHERE merged_into IS NULL`)
	if err != nil {
		return nil, fmt.Errorf("store: candidates: %w", err)
	}
	defer rows.Close()

	var out []Photo
	for rows.Next() {
		var (
			p            Photo
			phashBlob    []byte
			strippedBlob []byte
		)
		if err := rows.Scan(
			&p.ID, &p.SHA256, &phashBlob, &p.PhashVersion, &strippedBlob,
			&p.StrippedWidth, &p.StrippedHeight,
			&p.Width, &p.Height, &p.Variance, &p.ThumbKey, &p.ThumbExpireAt,
			&p.CommentCount, &p.Visibility, &p.DeviceID, &p.FirstSeenAt, &p.LastSeenAt,
		); err != nil {
			return nil, fmt.Errorf("store: scan candidate: %w", err)
		}
		p.Fingerprint = phash.FingerprintFromBytes(phashBlob)
		if len(strippedBlob) > 0 {
			f := phash.FingerprintFromBytes(strippedBlob)
			p.Stripped = &f
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// ---------------------------------------------------------------- device-local

func (s *Store) recentDevicePhoto(ctx context.Context, in RegisterInput, now int64) (*Photo, string, string, MatchResult, bool, error) {
	var (
		imageID string
		matched sql.NullString
	)
	var updatedAt int64
	err := s.db.QueryRowContext(ctx,
		`SELECT image_id, COALESCE(matched,''), updated_at FROM device_local
		 WHERE device_id=? AND source=? AND local_id=?`,
		in.DeviceID, in.Source, in.LocalID).Scan(&imageID, &matched, &updatedAt)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, "", "", "", false, nil
	}
	if err != nil {
		return nil, "", "", "", false, fmt.Errorf("store: device photo: %w", err)
	}

	days := in.FreshnessDays
	if days <= 0 {
		days = 30
	}
	if now-updatedAt > int64(days)*24*60*60*1000 {
		return nil, "", "", "", false, nil
	}

	photo, err := s.PhotoByID(ctx, imageID)
	if err != nil {
		// The photo was deleted; forget the mapping and register afresh.
		_, _ = s.db.ExecContext(ctx,
			`DELETE FROM device_local WHERE device_id=? AND source=? AND local_id=?`,
			in.DeviceID, in.Source, in.LocalID)
		return nil, "", "", "", false, nil
	}

	// The album may have given us the original bytes this time round, which is
	// strictly more identity than we had before.
	if in.SHA256 != "" && photo.SHA256 == "" {
		if _, err := s.db.ExecContext(ctx, `UPDATE photos SET sha256=? WHERE id=? AND sha256 IS NULL`,
			in.SHA256, photo.ID); err != nil {
			return nil, "", "", "", false, err
		}
	}

	code, codeBase, err := s.ensureCode(ctx, photo.ID, in.Scope, in.ScopeID, now, in.BaseURL)
	if err != nil {
		return nil, "", "", "", false, err
	}
	_, _ = s.db.ExecContext(ctx,
		`UPDATE device_local SET updated_at=? WHERE device_id=? AND source=? AND local_id=?`,
		now, in.DeviceID, in.Source, in.LocalID)
	_, _ = s.db.ExecContext(ctx, `UPDATE photos SET last_seen_at=? WHERE id=?`, now, photo.ID)

	// A mapping written before the column existed has no reason recorded. The photo's
	// own row still says which layer claimed it — a non-empty sha256 means the exact
	// bytes won — so the answer stays inside the protocol's vocabulary rather than
	// becoming an empty string.
	reason := MatchNew
	switch {
	case matched.Valid && matched.String != "":
		reason = MatchResult(matched.String)
	case photo.SHA256 != "":
		reason = MatchSHA256
	}
	return photo, code, codeBase, reason, true, nil
}

func (s *Store) rememberDevicePhoto(
	ctx context.Context,
	in RegisterInput,
	imageID string,
	matched MatchResult,
	now int64,
) error {
	_, err := s.db.ExecContext(ctx, `
		INSERT INTO device_local (device_id, source, local_id, image_id, updated_at, created_at, matched)
		VALUES (?,?,?,?,?,?,?)
		ON CONFLICT(device_id, source, local_id) DO UPDATE SET
			image_id=excluded.image_id,
			updated_at=excluded.updated_at,
			-- The reason is decided once and then kept: it records how this photo was
			-- first resolved, not how the device happened to describe it later.
			matched=COALESCE(device_local.matched, excluded.matched)`,
		in.DeviceID, in.Source, in.LocalID, imageID, now, now, string(matched))
	if err != nil {
		return fmt.Errorf("store: remember device photo: %w", err)
	}
	return nil
}

// ------------------------------------------------------------------- codes

// ensureCode returns the existing code for this photo and scope, or mints one — along with
// the address recorded on the code that came back.
//
// That second return is not [baseURL] echoed: when an existing code is found, its own
// recorded address is what the caller must build URLs from, and it may differ from the one
// just supplied. Returning the input instead is how a reply ends up advertising URLs for a
// host the code does not live on.
//
// [baseURL] only applies to a code being minted. An existing code keeps the address it was
// created with, and that is deliberate: a guest may already be holding a printed or
// screenshotted link, and silently re-pointing it because somebody registered the same
// photo from a different network would break the one thing a link has to be, which is
// stable. To move a deployment to a new address, delete and re-register the photos.
func (s *Store) ensureCode(ctx context.Context, imageID string, scope Scope, scopeID string, now int64, baseURL string) (string, string, error) {
	var code, existing string
	err := s.db.QueryRowContext(ctx,
		`SELECT code, base_url FROM codes WHERE image_id=? AND scope_type=? AND COALESCE(scope_id,'')=? LIMIT 1`,
		imageID, int(scope), scopeID).Scan(&code, &existing)
	if err == nil {
		return code, existing, nil
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return "", "", fmt.Errorf("store: find code: %w", err)
	}

	// Collisions are ~1 in 5×10^10 per attempt, but "practically never" is not
	// never: retry a few times and then give up honestly.
	for attempt := 0; attempt < 8; attempt++ {
		code = NewCode()
		_, err := s.db.ExecContext(ctx,
			`INSERT INTO codes (code, image_id, scope_type, scope_id, created_at, base_url) VALUES (?,?,?,?,?,?)`,
			code, imageID, int(scope), nullableString(scopeID), now, baseURL)
		if err == nil {
			return code, baseURL, nil
		}
		if !strings.Contains(err.Error(), "UNIQUE") && !strings.Contains(err.Error(), "constraint") {
			return "", "", fmt.Errorf("store: insert code: %w", err)
		}
	}
	return "", "", errors.New("store: could not allocate a unique code")
}

// DevicePhoto answers the one question the album end asks before it decides to upload
// anything: *has this device already registered this picture?*
//
// It is [recentDevicePhoto] with the two halves that make a registration a registration
// taken out — no freshness window and no writes. The window exists to decide whether a
// thumbnail has to be sent again (§6.2), which is a question about bytes; this one is about
// identity, and a picture this device registered a year ago is exactly as known as one it
// registered a minute ago. Nothing is touched on the way through: no `updated_at`, no
// `last_seen_at`, no thumbnail. A lookup that wrote would make "how recently was this
// really seen" depend on how often somebody *looked* at it.
//
// The code is read back too, and only if both halves exist: a mapping whose code was never
// minted (a registration that died between the two writes) is answered as unknown, which
// sends the album end down the path that mints one. The image id is not returned — the code
// is what the caller acts on, and it is the code that resolves to the root of a merge group.
func (s *Store) DevicePhoto(ctx context.Context, deviceID, source, localID string) (code string, ok bool, err error) {
	if deviceID == "" || source == "" || localID == "" {
		return "", false, nil
	}
	var imageID string
	err = s.db.QueryRowContext(ctx,
		`SELECT image_id FROM device_local WHERE device_id=? AND source=? AND local_id=?`,
		deviceID, source, localID).Scan(&imageID)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("store: device photo: %w", err)
	}

	err = s.db.QueryRowContext(ctx,
		`SELECT code FROM codes WHERE image_id=? AND scope_type=? AND COALESCE(scope_id,'')='' LIMIT 1`,
		imageID, int(ScopeImage)).Scan(&code)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, nil
	}
	if err != nil {
		return "", false, fmt.Errorf("store: device photo code: %w", err)
	}
	return code, true, nil
}

// RebindCodes rewrites the address recorded on every short code, and reports how many rows
// that changed out of how many exist.
//
// [baseURL] is the address every code should point at from now on, or "" to **clear** what
// each code recorded — which hands every code back to the deployment's own answer: the
// pinned TUPING_BASE_URL, or the address the caller reached the server on (the chain is in
// `api.pageBase`).
//
// This is the operation `ensureCode` explicitly refuses to do on its own. A code keeps the
// address it was minted with because a guest may be holding a printed or screenshotted
// link, and silently re-pointing it because somebody registered the same photo from another
// network would break the one thing a link has to be. But a deployment that has *moved* —
// a new tunnel address, a new port forward, a domain at last — has codes that open a host
// nobody can reach, and the alternative was handwritten SQL against a live database
// (docs/tuping-design.md §17.27). The two are different acts and they are asked for
// differently: one is a side effect of registering a photo, the other is an operator
// saying "we have moved".
//
// Nothing else moves. Comments hang off `photos.id`, not off the code, so every thread
// survives a rebind — and the code itself survives too, which is what keeps `/c/<code>`
// links, the H5 page's own history entries and the television's cached identity valid.
func (s *Store) RebindCodes(ctx context.Context, baseURL string) (updated, total int64, err error) {
	if err := s.db.QueryRowContext(ctx, `SELECT COUNT(*) FROM codes`).Scan(&total); err != nil {
		return 0, 0, fmt.Errorf("store: count codes: %w", err)
	}
	res, err := s.db.ExecContext(ctx,
		`UPDATE codes SET base_url = ? WHERE base_url <> ?`, baseURL, baseURL)
	if err != nil {
		return 0, 0, fmt.Errorf("store: rebind codes: %w", err)
	}
	updated, err = res.RowsAffected()
	if err != nil {
		return 0, 0, fmt.Errorf("store: rebind codes: %w", err)
	}
	return updated, total, nil
}

// CodeTarget is what a short code resolves to.
type CodeTarget struct {
	Code      string
	ImageID   string
	Scope     Scope
	ScopeID   string
	CreatedAt int64
	Photo     *Photo

	// BaseURL is the address this code's QR points at, as recorded when it was minted, or
	// "" when the registering device did not say and the caller's own setting applies.
	BaseURL string
}

// ResolveCode looks a short code up.
func (s *Store) ResolveCode(ctx context.Context, code string) (*CodeTarget, error) {
	var (
		t       CodeTarget
		scopeID sql.NullString
	)
	err := s.db.QueryRowContext(ctx,
		`SELECT code, image_id, scope_type, scope_id, created_at, base_url FROM codes WHERE code = ?`, code).
		Scan(&t.Code, &t.ImageID, &t.Scope, &scopeID, &t.CreatedAt, &t.BaseURL)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, fmt.Errorf("store: resolve code: %w", err)
	}
	t.ScopeID = scopeID.String

	photo, err := s.PhotoByID(ctx, t.ImageID)
	if err != nil {
		return nil, err
	}
	t.Photo = photo
	t.ImageID = photo.ID
	return &t, nil
}

// ------------------------------------------------------------------ merges

// Merge folds from into to, recording why. Comments do not move.
func (s *Store) Merge(ctx context.Context, from, to, reason string, score int) error {
	if from == to {
		return nil
	}
	root, err := s.RootID(ctx, to)
	if err != nil {
		return err
	}
	if root == from {
		return fmt.Errorf("store: merge would create a cycle (%s -> %s)", from, to)
	}
	_, err = s.db.ExecContext(ctx, `
		INSERT INTO merges (from_image_id, to_image_id, reason, score, created_at)
		VALUES (?,?,?,?,?)
		ON CONFLICT(from_image_id) DO UPDATE SET to_image_id=excluded.to_image_id,
			reason=excluded.reason, score=excluded.score, created_at=excluded.created_at`,
		from, root, reason, score, s.nowMS())
	if err != nil {
		return fmt.Errorf("store: merge: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE photos SET merged_into=? WHERE id=?`, root, from); err != nil {
		return fmt.Errorf("store: merge pointer: %w", err)
	}
	return s.refreshCommentCount(ctx, root)
}

// Unmerge undoes a merge. This is the "this is not the same photo" path of §3.4,
// and it is only cheap because nothing ever moved.
func (s *Store) Unmerge(ctx context.Context, from string) error {
	var to string
	err := s.db.QueryRowContext(ctx, `SELECT to_image_id FROM merges WHERE from_image_id=?`, from).Scan(&to)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrNotFound
	}
	if err != nil {
		return fmt.Errorf("store: unmerge lookup: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `DELETE FROM merges WHERE from_image_id=?`, from); err != nil {
		return fmt.Errorf("store: unmerge: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, `UPDATE photos SET merged_into=NULL WHERE id=?`, from); err != nil {
		return fmt.Errorf("store: unmerge pointer: %w", err)
	}
	return s.refreshCommentCount(ctx, to)
}

// Merges lists the merges that produced a root, newest first.
func (s *Store) Merges(ctx context.Context, root string) ([]Merge, error) {
	rows, err := s.db.QueryContext(ctx,
		`SELECT from_image_id, to_image_id, reason, COALESCE(score,0), created_at
		 FROM merges WHERE to_image_id=? ORDER BY created_at DESC`, root)
	if err != nil {
		return nil, fmt.Errorf("store: list merges: %w", err)
	}
	defer rows.Close()

	var out []Merge
	for rows.Next() {
		var m Merge
		if err := rows.Scan(&m.From, &m.To, &m.Reason, &m.Score, &m.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}

// Merge is one row of the merges table.
type Merge struct {
	From      string
	To        string
	Reason    string
	Score     int
	CreatedAt int64
}

// refreshCommentCount recomputes photos.comment_count from the comments table.
// The redundant counter exists so a list page never has to COUNT (§5).
func (s *Store) refreshCommentCount(ctx context.Context, imageID string) error {
	group, err := s.GroupIDs(ctx, imageID)
	if err != nil {
		return err
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(group)), ",")
	args := make([]any, len(group))
	for i, g := range group {
		args[i] = g
	}
	var total int
	err = s.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM comments WHERE image_id IN (`+placeholders+`) AND status = ?`,
		append(args, StatusVisible)...).Scan(&total)
	if err != nil {
		return fmt.Errorf("store: refresh count: %w", err)
	}

	root, err := s.RootID(ctx, imageID)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `UPDATE photos SET comment_count=? WHERE id=?`, total, root)
	if err != nil {
		return fmt.Errorf("store: write count: %w", err)
	}
	return nil
}

// ------------------------------------------------------------------ deletion

// DeletePhoto removes a photo, its codes, its device mappings and every comment
// on its whole merge group. This is the album owner's one-press delete (§12.5).
func (s *Store) DeletePhoto(ctx context.Context, id string) ([]string, error) {
	group, err := s.GroupIDs(ctx, id)
	if err != nil {
		return nil, err
	}

	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()

	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(group)), ",")
	args := make([]any, len(group))
	for i, g := range group {
		args[i] = g
	}

	if _, err := tx.ExecContext(ctx,
		`DELETE FROM comments WHERE image_id IN (`+placeholders+`)`, args...); err != nil {
		return nil, fmt.Errorf("store: delete comments: %w", err)
	}
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM codes WHERE image_id IN (`+placeholders+`)`, args...); err != nil {
		return nil, fmt.Errorf("store: delete codes: %w", err)
	}
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM device_local WHERE image_id IN (`+placeholders+`)`, args...); err != nil {
		return nil, fmt.Errorf("store: delete device photos: %w", err)
	}
	// A merge pointing at a deleted photo has to go, or the next registration
	// would follow a pointer into nothing.
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM merges WHERE from_image_id IN (`+placeholders+`) OR to_image_id IN (`+placeholders+`)`,
		append(append([]any{}, args...), args...)...); err != nil {
		return nil, fmt.Errorf("store: delete merges: %w", err)
	}
	if _, err := tx.ExecContext(ctx,
		`DELETE FROM photos WHERE id IN (`+placeholders+`)`, args...); err != nil {
		return nil, fmt.Errorf("store: delete photos: %w", err)
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return group, nil
}

// ClearThumbKey drops the pointer to a thumbnail that the retention sweep has
// deleted, so a later request answers 404 instead of reading a missing file.
func (s *Store) ClearThumbKey(ctx context.Context, key string) error {
	_, err := s.db.ExecContext(ctx,
		`UPDATE photos SET thumb_key=NULL, thumb_expire_at=NULL WHERE thumb_key=?`, key)
	if err != nil {
		return fmt.Errorf("store: clear thumb key: %w", err)
	}
	return nil
}

// SetVisibility sets a photo's comment policy.
func (s *Store) SetVisibility(ctx context.Context, id string, visibility int) error {
	root, err := s.RootID(ctx, id)
	if err != nil {
		return err
	}
	_, err = s.db.ExecContext(ctx, `UPDATE photos SET visibility=? WHERE id=?`, visibility, root)
	return err
}

// HashOwnerToken derives the stored form of an owner token. The token itself is
// never persisted: it authorises deleting a photo, so a database read must not
// hand anyone the ability to use it (§5, §12.6).
func HashOwnerToken(token string) string {
	if token == "" {
		return ""
	}
	return HashToken(deviceSecret+"/owner", token)
}

// ThumbKeys lists the thumbnail keys of a photo's whole merge group. It is read
// before a delete, because afterwards nothing knows which files were theirs.
func (s *Store) ThumbKeys(ctx context.Context, id string) ([]string, error) {
	group, err := s.GroupIDs(ctx, id)
	if err != nil {
		return nil, err
	}
	placeholders := strings.TrimSuffix(strings.Repeat("?,", len(group)), ",")
	args := make([]any, len(group))
	for i, g := range group {
		args[i] = g
	}
	rows, err := s.db.QueryContext(ctx,
		`SELECT COALESCE(thumb_key,'') FROM photos WHERE id IN (`+placeholders+`)`, args...)
	if err != nil {
		return nil, fmt.Errorf("store: thumb keys: %w", err)
	}
	defer rows.Close()

	var out []string
	for rows.Next() {
		var key string
		if err := rows.Scan(&key); err != nil {
			return nil, err
		}
		if key != "" {
			out = append(out, key)
		}
	}
	return out, rows.Err()
}

// OwnerTokenMatches reports whether the stored owner hash matches, which is what
// authorises deleting a photo or unbinding a merge.
func (s *Store) OwnerTokenMatches(ctx context.Context, id, token string) (bool, error) {
	root, err := s.RootID(ctx, id)
	if err != nil {
		return false, err
	}
	var stored sql.NullString
	err = s.db.QueryRowContext(ctx, `SELECT owner_token_hash FROM photos WHERE id=?`, root).Scan(&stored)
	if errors.Is(err, sql.ErrNoRows) {
		return false, ErrNotFound
	}
	if err != nil {
		return false, err
	}
	// No owner recorded means no one can claim it: never "anyone may".
	if !stored.Valid || stored.String == "" || token == "" {
		return false, nil
	}
	return constantTimeEqual(stored.String, HashOwnerToken(token)), nil
}

// constantTimeEqual compares two secrets without leaking where they differ.
func constantTimeEqual(a, b string) bool {
	return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1
}

// ------------------------------------------------------------------ helpers

func nullableString(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func nullableBytes(b []byte) any {
	if len(b) == 0 {
		return nil
	}
	return b
}

func strippedBytes(im *phash.Image) []byte {
	if im.Stripped == nil {
		return nil
	}
	return im.Stripped.Bytes()
}

func clampVariance(v float64) int {
	if v < 0 {
		return 0
	}
	if v > math.MaxInt32 {
		return math.MaxInt32
	}
	return int(v)
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
