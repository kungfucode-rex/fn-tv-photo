// Package store is the whole persistence layer: SQLite in WAL mode, the schema
// from docs/tuping-design.md §5, and the queries the API is built out of.
//
// Two things in here are load-bearing and easy to get wrong:
//
//   - Merges are pointers, not moves (merged_into + follow-the-pointer on read).
//     A merge that copies comments across loses them again the moment it is
//     unbound; a pointer is undone by clearing one column.
//   - The image id is always the server's ULID. Both fingerprints are indexes.
//     A pHash is lossy and reversible, so it is never a key and never a
//     credential (docs/tuping-design.md §3.1).
package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"

	_ "modernc.org/sqlite" // pure Go driver: cross-compiles with CGO_ENABLED=0
)

// Scope is a comment thread's domain. Both values exist from the first version;
// only ScopeImage is ever written (docs/tuping-design.md §4, decision D5).
type Scope int

const (
	// ScopeImage is "everyone with this photo in this instance shares one thread".
	ScopeImage Scope = 1
	// ScopeEvent is a wedding, an annual party, a reunion. Second phase.
	ScopeEvent Scope = 2
)

func (s Scope) String() string {
	if s == ScopeEvent {
		return "event"
	}
	return "image"
}

// Visibility is a photo's comment policy.
const (
	VisibilityPrivate  = 0 // 私密
	VisibilityImage    = 1 // 图片域
	VisibilityDisabled = 2 // 关闭评论
)

// Comment status values.
const (
	StatusPending = 0 // 待审
	StatusVisible = 1 // 可见
	StatusBlocked = 2 // 屏蔽
	StatusDeleted = 3 // 删除
)

// MatchResult says how a registration was resolved. The album end only logs it.
type MatchResult string

const (
	MatchSHA256 MatchResult = "sha256"
	MatchPHash  MatchResult = "phash"
	MatchNew    MatchResult = "new"
)

// ErrNotFound is returned for a code or id that is not in the database.
var ErrNotFound = errors.New("store: not found")

// Store owns one SQLite connection pool.
type Store struct {
	db  *sql.DB
	now func() time.Time

	// lastSuspects is written by resolveIdentity and read by Register, both while
	// holding registerLock, so it needs no lock of its own.
	lastSuspects []Suspect
}

// Options configures Open.
type Options struct {
	// Path is the SQLite file. A leading ":memory:" is honoured for tests.
	Path string

	// Now is injectable so the retention and rate-limit tests do not have to sleep.
	Now func() time.Time
}

// Open opens (and creates, and migrates) the database.
func Open(opts Options) (*Store, error) {
	if opts.Path == "" {
		return nil, errors.New("store: no database path")
	}
	if opts.Now == nil {
		opts.Now = time.Now
	}

	if !strings.HasPrefix(opts.Path, ":memory:") {
		if err := os.MkdirAll(filepath.Dir(opts.Path), 0o755); err != nil {
			return nil, fmt.Errorf("store: create data directory: %w", err)
		}
	}

	dsn := opts.Path
	if !strings.Contains(dsn, "?") {
		dsn += "?_pragma=busy_timeout(5000)&_pragma=journal_mode(WAL)&_pragma=synchronous(NORMAL)&_pragma=foreign_keys(1)"
	}

	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return nil, fmt.Errorf("store: open: %w", err)
	}

	// One writer. SQLite serialises writes anyway, and a pool of writers only buys
	// SQLITE_BUSY under contention.
	db.SetMaxOpenConns(1)
	db.SetConnMaxLifetime(0)
	db.SetMaxIdleConns(1)

	s := &Store{db: db, now: opts.Now}
	if err := s.migrate(context.Background()); err != nil {
		db.Close()
		return nil, err
	}
	return s, nil
}

// Close releases the database.
func (s *Store) Close() error { return s.db.Close() }

// DB exposes the handle for health checks and tests.
func (s *Store) DB() *sql.DB { return s.db }

// schema is the DDL from the design, plus the two tables the implementation
// turned out to need:
//
//	device_local  — "the same device registering the same photo again" (§6.2) has
//	                to be answerable, and local ids are the album's, not ours.
//	rate_limits   — §5 declared it; the columns are window_start/count.
//
// Everything is IF NOT EXISTS and the migration is idempotent, so an existing
// database is upgraded by starting the new binary.
const schema = `
CREATE TABLE IF NOT EXISTS photos (
  id               TEXT PRIMARY KEY,
  sha256           TEXT,
  phash            BLOB NOT NULL,
  phash_version    INTEGER NOT NULL,
  phash_stripped   BLOB,
  stripped_width   INTEGER NOT NULL DEFAULT 0,
  stripped_height  INTEGER NOT NULL DEFAULT 0,
  width            INTEGER NOT NULL,
  height           INTEGER NOT NULL,
  variance         INTEGER NOT NULL,
  thumb_key        TEXT,
  thumb_expire_at  INTEGER,
  comment_count    INTEGER NOT NULL DEFAULT 0,
  visibility       INTEGER NOT NULL DEFAULT 1,
  owner_token_hash TEXT,
  device_id        TEXT,
  merged_into      TEXT REFERENCES photos(id),
  first_seen_at    INTEGER NOT NULL,
  last_seen_at     INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_photos_sha256 ON photos(sha256);
CREATE INDEX IF NOT EXISTS idx_photos_ratio  ON photos(width, height);

CREATE TABLE IF NOT EXISTS codes (
  code       TEXT PRIMARY KEY,
  image_id   TEXT NOT NULL REFERENCES photos(id),
  scope_type INTEGER NOT NULL,
  scope_id   TEXT,
  created_at INTEGER NOT NULL,
  expires_at INTEGER
);
CREATE INDEX IF NOT EXISTS idx_codes_image ON codes(image_id);

CREATE TABLE IF NOT EXISTS comments (
  id           TEXT PRIMARY KEY,
  image_id     TEXT NOT NULL REFERENCES photos(id),
  scope_type   INTEGER NOT NULL,
  scope_id     TEXT,
  content      TEXT NOT NULL,
  nickname     TEXT NOT NULL,
  avatar_url   TEXT,
  openid_hmac  TEXT,
  anon_id      TEXT,
  status       INTEGER NOT NULL DEFAULT 1,
  report_count INTEGER NOT NULL DEFAULT 0,
  ip_hmac      TEXT,
  client       TEXT,
  idem_key     TEXT,
  created_at   INTEGER NOT NULL,
  UNIQUE(image_id, idem_key)
);
CREATE INDEX IF NOT EXISTS idx_comments_image ON comments(image_id, created_at);

CREATE TABLE IF NOT EXISTS merges (
  from_image_id TEXT PRIMARY KEY REFERENCES photos(id),
  to_image_id   TEXT NOT NULL REFERENCES photos(id),
  reason        TEXT NOT NULL,
  score         INTEGER,
  created_at    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_merges_to ON merges(to_image_id);

CREATE TABLE IF NOT EXISTS events (
  id TEXT PRIMARY KEY, name TEXT,
  created_at INTEGER NOT NULL, expires_at INTEGER,
  owner_token_hash TEXT
);

CREATE TABLE IF NOT EXISTS rate_limits (
  key          TEXT PRIMARY KEY,
  window_start INTEGER NOT NULL,
  count        INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS device_local (
  device_id  TEXT NOT NULL,
  source     TEXT NOT NULL,
  local_id   TEXT NOT NULL,
  image_id   TEXT NOT NULL REFERENCES photos(id),
  updated_at INTEGER NOT NULL,
  created_at INTEGER NOT NULL DEFAULT 0,
  matched    TEXT,
  PRIMARY KEY (device_id, source, local_id)
);
`

func (s *Store) migrate(ctx context.Context) error {
	if _, err := s.db.ExecContext(ctx, schema); err != nil {
		return fmt.Errorf("store: migrate: %w", err)
	}
	if _, err := s.db.ExecContext(ctx, deviceSchema); err != nil {
		return fmt.Errorf("store: migrate devices: %w", err)
	}
	// Columns added after the first release. "IF NOT EXISTS" on the table does
	// nothing for an existing database, so each one is added on its own and a
	// duplicate-column error is the expected outcome on a second start.
	for _, stmt := range []string{
		`ALTER TABLE photos ADD COLUMN stripped_width  INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE photos ADD COLUMN stripped_height INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE device_local ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0`,
		`ALTER TABLE device_local ADD COLUMN matched TEXT`,
		// The address the *registering device* uses to reach this server, stored per code.
		//
		// It lives on `codes` rather than on `device_local` because the code is what the QR
		// points at, and a code can be shared: the URL has to be a property of the link, not
		// of whoever happened to ask for it first. Empty means "the device said nothing",
		// which falls back to the pinned TUPING_BASE_URL and then to the address of the
		// request being answered (§17.20).
		`ALTER TABLE codes ADD COLUMN base_url TEXT NOT NULL DEFAULT ''`,
	} {
		if _, err := s.db.ExecContext(ctx, stmt); err != nil && !strings.Contains(err.Error(), "duplicate column") {
			return fmt.Errorf("store: migrate %q: %w", stmt, err)
		}
	}
	return nil
}

// nowMS is the one clock the whole store uses: milliseconds since the epoch,
// which is what the protocol puts on the wire.
func (s *Store) nowMS() int64 { return s.now().UnixMilli() }
