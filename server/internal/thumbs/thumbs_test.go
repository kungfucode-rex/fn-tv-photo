package thumbs

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	s, err := Open(filepath.Join(t.TempDir(), "thumbs"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	return s
}

func TestPutThenRead(t *testing.T) {
	s := newStore(t)
	if err := s.Put("abc123", []byte("jpeg bytes")); err != nil {
		t.Fatalf("put: %v", err)
	}

	got, err := s.Read("abc123")
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if string(got) != "jpeg bytes" {
		t.Errorf("read back %q", got)
	}
}

// TestReadMissingIsNotFound: the caller answers 404 for a missing thumbnail, so a
// missing file must be tellable apart from a broken one.
func TestReadMissingIsNotFound(t *testing.T) {
	s := newStore(t)
	if _, err := s.Read("nothing1"); err != ErrNotFound {
		t.Errorf("read of a missing key returned %v, want ErrNotFound", err)
	}
}

// TestUnsafeKeysAreRefused is the one security-relevant guard in this package: a key
// that could carry a path separator is the only way a thumbnail request could reach
// outside the directory. It is refused at the boundary rather than sanitised, so a
// caller cannot invent a key that silently lands somewhere else.
func TestUnsafeKeysAreRefused(t *testing.T) {
	s := newStore(t)

	unsafe := []string{
		"",
		"ab",
		"../../etc/passwd",
		"..\\..\\windows\\system32",
		"abc/def",
		"abc\\def",
		"abc def",
		"abc.def",
		"abc:def",
		"abc%2fdef",
		strings.Repeat("a", 65),
	}
	for _, key := range unsafe {
		if err := s.Put(key, []byte("x")); err == nil {
			t.Errorf("Put accepted the unsafe key %q", key)
		}
		if _, err := s.Read(key); err == nil {
			t.Errorf("Read accepted the unsafe key %q", key)
		}
		if _, err := s.Path(key); err == nil {
			t.Errorf("Path accepted the unsafe key %q", key)
		}
	}

	// And nothing escaped the directory while it was trying.
	var files []string
	_ = filepath.WalkDir(s.Dir(), func(path string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			files = append(files, path)
		}
		return nil
	})
	for _, f := range files {
		if filepath.Dir(f) != s.Dir() {
			t.Errorf("a file was written outside the store: %s", f)
		}
	}
}

func TestSafeKeysAreAccepted(t *testing.T) {
	s := newStore(t)
	for _, key := range []string{"abc", "ABC-123_x", strings.Repeat("a", 64)} {
		if err := s.Put(key, []byte("x")); err != nil {
			t.Errorf("Put refused the safe key %q: %v", key, err)
		}
	}
}

// TestDeleteRemovesBothFileAndRecord: a deleted thumbnail must not come back from the
// access index, or the sweep would keep reporting it.
func TestDeleteRemovesBothFileAndRecord(t *testing.T) {
	s := newStore(t)
	if err := s.Put("gone123", []byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := s.Delete("gone123"); err != nil {
		t.Fatalf("delete: %v", err)
	}
	if _, err := s.Read("gone123"); err != ErrNotFound {
		t.Errorf("a deleted thumbnail is still readable: %v", err)
	}
	for _, key := range s.Expired(time.Now().Add(365 * 24 * time.Hour)) {
		if key == "gone123" {
			t.Error("the access index still lists a deleted thumbnail")
		}
	}
}

// TestExpiredHonoursTheTTL is §12.4: thirty days without a visit and the thumbnail goes.
// The test walks the clock instead of waiting a month.
func TestExpiredHonoursTheTTL(t *testing.T) {
	s := newStore(t)
	if err := s.Put("old123", []byte("x")); err != nil {
		t.Fatal(err)
	}

	// Just inside the window: still wanted.
	if got := s.Expired(time.Now().Add(TTL - time.Hour)); len(got) != 0 {
		t.Errorf("a thumbnail inside the TTL is listed as expired: %v", got)
	}
	// Past it: gone.
	expired := s.Expired(time.Now().Add(TTL + time.Hour))
	if len(expired) != 1 || expired[0] != "old123" {
		t.Errorf("expired = %v, want [old123]", expired)
	}

	n, err := s.Sweep(time.Now().Add(TTL + time.Hour))
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if n != 1 {
		t.Errorf("sweep removed %d, want 1", n)
	}
	if _, err := s.Read("old123"); err != ErrNotFound {
		t.Errorf("the swept thumbnail is still there: %v", err)
	}
}

// TestASweepKeepsWhatIsStillRead: a thumbnail that is read every day must never expire
// no matter when it was uploaded. This is why the sweep is driven from the access index
// rather than from an upload timestamp.
func TestASweepKeepsWhatIsStillRead(t *testing.T) {
	s := newStore(t)
	if err := s.Put("busy123", []byte("x")); err != nil {
		t.Fatal(err)
	}

	// A month of daily reads.
	for day := 0; day < 30; day++ {
		if _, err := s.Read("busy123"); err != nil {
			t.Fatalf("day %d: %v", day, err)
		}
	}

	if expired := s.Expired(time.Now()); len(expired) != 0 {
		t.Errorf("a thumbnail read today is listed as expired: %v", expired)
	}
	if n, _ := s.Sweep(time.Now()); n != 0 {
		t.Errorf("the sweep took %d thumbnails that are in daily use", n)
	}
}

// TestTheIndexSurvivesAReopen: a NAS that reboots must not come back believing every
// thumbnail is cold, or the first sweep would delete the lot.
func TestTheIndexSurvivesAReopen(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "thumbs")
	s, err := Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Put("kept123", []byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := s.Flush(); err != nil {
		t.Fatalf("flush: %v", err)
	}

	reopened, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	if expired := reopened.Expired(time.Now()); len(expired) != 0 {
		t.Errorf("after a restart a fresh thumbnail is listed as expired: %v", expired)
	}
	if _, err := reopened.Read("kept123"); err != nil {
		t.Errorf("the file did not survive the restart: %v", err)
	}
}

// TestBytesCountsWhatIsThere is the figure /healthz and the settings status line show.
func TestBytesCountsWhatIsThere(t *testing.T) {
	s := newStore(t)
	if s.Bytes() != 0 {
		t.Errorf("an empty store reports %d bytes", s.Bytes())
	}
	if err := s.Put("one123", []byte("0123456789")); err != nil {
		t.Fatal(err)
	}
	if got := s.Bytes(); got < 10 {
		t.Errorf("store reports %d bytes after writing 10", got)
	}
}

// TestPathShardsSoNoDirectoryGrowsForever: a million thumbnails in one directory is a
// directory that takes a second to list on a spinning NAS disk.
func TestPathShardsSoNoDirectoryGrowsForever(t *testing.T) {
	s := newStore(t)
	if err := s.Put("zzabcdef", []byte("x")); err != nil {
		t.Fatal(err)
	}
	path, err := s.Path("zzabcdef")
	if err != nil {
		t.Fatal(err)
	}
	if filepath.Base(filepath.Dir(path)) != "zz" {
		t.Errorf("the file is not in a shard directory: %s", path)
	}
	if filepath.Ext(path) != ".jpg" {
		t.Errorf("the file does not carry the .jpg suffix: %s", path)
	}
}
