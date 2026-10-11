// Package thumbs is the thumbnail store: files in a directory, keyed by a
// server-chosen name, with a last-access record so unused ones can expire.
//
// Thumbnails live here and not in the database because a thumbnail is a property
// of the photo, not of a comment: storing one per comment row would be a hundred
// copies of one picture (docs/tuping-design.md §5).
package thumbs

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// ErrNotFound is returned when a key has no file.
var ErrNotFound = errors.New("thumbs: not found")

// Store is a directory of thumbnails plus an in-memory access table, flushed to
// a single index file. One file, not a sidecar per thumbnail: a million sidecars
// is a million inodes for something that only has to survive a restart.
type Store struct {
	dir   string
	index string

	mu     sync.Mutex
	touch  map[string]int64
	dirty  bool
	flush  time.Duration
	lastFl time.Time
}

// Open prepares the directory and loads the access index.
func Open(dir string) (*Store, error) {
	if dir == "" {
		return nil, errors.New("thumbs: no directory")
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("thumbs: create directory: %w", err)
	}
	s := &Store{
		dir:   dir,
		index: filepath.Join(dir, "access.tsv"),
		touch: map[string]int64{},
		flush: 30 * time.Second,
	}
	if err := s.loadIndex(); err != nil {
		return nil, err
	}
	return s, nil
}

// Dir is the directory the files are in, for the disk-space line in /healthz.
func (s *Store) Dir() string { return s.dir }

// Put writes a thumbnail atomically and records the access.
func (s *Store) Put(key string, data []byte) error {
	path, err := s.path(key)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("thumbs: create shard: %w", err)
	}

	// Write to a temporary file and rename: a reader must never see half a JPEG,
	// and a crash must not leave one behind under the real name.
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return fmt.Errorf("thumbs: temp file: %w", err)
	}
	tmpName := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(tmpName)
		return fmt.Errorf("thumbs: write: %w", err)
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return fmt.Errorf("thumbs: close: %w", err)
	}
	if err := os.Rename(tmpName, path); err != nil {
		os.Remove(tmpName)
		return fmt.Errorf("thumbs: rename: %w", err)
	}

	s.Touch(key)
	return nil
}

// Read returns a thumbnail's bytes.
func (s *Store) Read(key string) ([]byte, error) {
	path, err := s.path(key)
	if err != nil {
		return nil, err
	}
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	s.Touch(key)
	return data, nil
}

// Path returns the file's path, or ErrNotFound. Serving a file directly is what
// keeps the H5 page cheap on a weak link.
func (s *Store) Path(key string) (string, error) {
	path, err := s.path(key)
	if err != nil {
		return "", err
	}
	if _, err := os.Stat(path); err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return "", ErrNotFound
		}
		return "", err
	}
	s.Touch(key)
	return path, nil
}

// Delete removes a thumbnail and its access record.
func (s *Store) Delete(key string) error {
	path, err := s.path(key)
	if err != nil {
		return err
	}
	s.mu.Lock()
	delete(s.touch, key)
	s.dirty = true
	s.mu.Unlock()

	if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
		return err
	}
	return nil
}

// Touch records that a key was used now.
func (s *Store) Touch(key string) {
	s.mu.Lock()
	s.touch[key] = time.Now().UnixMilli()
	s.dirty = true
	dirty := s.dirty && time.Since(s.lastFl) > s.flush
	s.mu.Unlock()
	if dirty {
		_ = s.Flush()
	}
}

// TTL is how long a thumbnail may go unread before the sweep takes it back
// (docs/tuping-design.md §12.4: 30 days without a visit).
const TTL = 30 * 24 * time.Hour

// Expired lists keys not touched inside the TTL.
func (s *Store) Expired(now time.Time) []string {
	cutoff := now.Add(-TTL).UnixMilli()
	s.mu.Lock()
	defer s.mu.Unlock()

	var out []string
	for key, at := range s.touch {
		if at < cutoff {
			out = append(out, key)
		}
	}
	return out
}

// Sweep deletes every thumbnail past its TTL and returns how many went.
func (s *Store) Sweep(now time.Time) (int, error) {
	keys := s.Expired(now)
	for _, k := range keys {
		if err := s.Delete(k); err != nil {
			return 0, err
		}
	}
	if len(keys) > 0 {
		if err := s.Flush(); err != nil {
			return len(keys), err
		}
	}
	return len(keys), nil
}

// Flush writes the access index out.
func (s *Store) Flush() error {
	s.mu.Lock()
	if !s.dirty {
		s.mu.Unlock()
		return nil
	}
	lines := make([]string, 0, len(s.touch))
	for k, v := range s.touch {
		lines = append(lines, fmt.Sprintf("%s\t%d", k, v))
	}
	s.dirty = false
	s.lastFl = time.Now()
	s.mu.Unlock()

	tmp := s.index + ".tmp"
	if err := os.WriteFile(tmp, []byte(strings.Join(lines, "\n")+"\n"), 0o644); err != nil {
		return fmt.Errorf("thumbs: write index: %w", err)
	}
	return os.Rename(tmp, s.index)
}

func (s *Store) loadIndex() error {
	data, err := os.ReadFile(s.index)
	if errors.Is(err, os.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("thumbs: read index: %w", err)
	}
	for _, line := range strings.Split(string(data), "\n") {
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, "\t")
		if !ok {
			continue
		}
		var at int64
		if _, err := fmt.Sscanf(value, "%d", &at); err != nil {
			continue
		}
		s.touch[key] = at
	}
	return nil
}

// Bytes reports how much the directory holds, for the settings-page status line.
func (s *Store) Bytes() int64 {
	var total int64
	_ = filepath.WalkDir(s.dir, func(_ string, d os.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		if info, err := d.Info(); err == nil {
			total += info.Size()
		}
		return nil
	})
	return total
}

// path maps a key to a file, sharding the first two characters so no directory
// grows to a million entries.
func (s *Store) path(key string) (string, error) {
	if !validKey(key) {
		return "", fmt.Errorf("thumbs: unsafe key %q", key)
	}
	shard := key[:2]
	return filepath.Join(s.dir, shard, key+".jpg"), nil
}

// validKey accepts only what this server generates. A key that could contain a
// path separator is the one way a thumbnail request could reach outside the
// directory, so it is refused at the boundary rather than sanitised.
func validKey(key string) bool {
	if len(key) < 3 || len(key) > 64 {
		return false
	}
	for i := 0; i < len(key); i++ {
		c := key[i]
		switch {
		case c >= '0' && c <= '9':
		case c >= 'a' && c <= 'z':
		case c >= 'A' && c <= 'Z':
		case c == '-' || c == '_':
		default:
			return false
		}
	}
	return true
}
