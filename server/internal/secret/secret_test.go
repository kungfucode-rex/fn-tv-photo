package secret

import (
	"encoding/base64"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
)

func TestExplicitSecretWinsAndWritesNothing(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")

	got, err := Resolve("my-own-key", path)
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if got.Value != "my-own-key" || got.Source != SourceEnv {
		t.Fatalf("got %+v, want the explicit key with SourceEnv", got)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("the key file must not be created when TUPING_SECRET is set (stat err = %v)", err)
	}
}

func TestFirstBootMintsAKeyAndKeepsIt(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")

	first, err := Resolve("", path)
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if first.Source != SourceNew {
		t.Fatalf("Source = %q, want %q", first.Source, SourceNew)
	}
	raw, err := base64.StdEncoding.DecodeString(first.Value)
	if err != nil || len(raw) != 32 {
		t.Fatalf("minted key %q is not 32 base64 bytes (err = %v)", first.Value, err)
	}

	// The whole point: a restart reads the same key rather than minting another.
	second, err := Resolve("", path)
	if err != nil {
		t.Fatalf("Resolve again: %v", err)
	}
	if second.Source != SourceFile || second.Value != first.Value {
		t.Fatalf("second boot got %+v, want the same value with SourceFile", second)
	}
}

func TestMintedFileIsPrivate(t *testing.T) {
	if runtime.GOOS == "windows" {
		// Windows reports 0666 for everything and enforces nothing, so this
		// assertion only carries information on the machine that matters: the
		// NAS. It is skipped here rather than weakened.
		t.Skip("POSIX modes are not enforced on Windows; checked on the NAS")
	}
	path := filepath.Join(t.TempDir(), "secret")
	if _, err := Resolve("", path); err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatalf("Stat: %v", err)
	}
	if perm := info.Mode().Perm(); perm != 0o600 {
		t.Errorf("mode = %o, want 600", perm)
	}
}

func TestExistingFileIsUsedVerbatim(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(path, []byte("  from-the-file\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := Resolve("", path)
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if got.Value != "from-the-file" || got.Source != SourceFile {
		t.Fatalf("got %+v, want the trimmed file contents with SourceFile", got)
	}
}

// An empty file is what a crash between create and write leaves behind, and it
// must not stop the server: nothing can depend on a key that was never usable.
func TestEmptyFileIsReplaced(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")
	if err := os.WriteFile(path, nil, 0o600); err != nil {
		t.Fatal(err)
	}

	got, err := Resolve("", path)
	if err != nil {
		t.Fatalf("Resolve: %v", err)
	}
	if got.Source != SourceNew || got.Value == "" {
		t.Fatalf("got %+v, want a freshly minted key", got)
	}
	again, err := Resolve("", path)
	if err != nil {
		t.Fatalf("Resolve again: %v", err)
	}
	if again.Value != got.Value {
		t.Fatalf("the replacement was not persisted: %q then %q", got.Value, again.Value)
	}
}

func TestUnreadableKeyFileIsAnError(t *testing.T) {
	// A directory is unreadable as a file on every platform, which keeps this
	// test independent of who is running it.
	path := filepath.Join(t.TempDir(), "secret")
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}

	if _, err := Resolve("", path); err == nil {
		t.Fatal("Resolve must fail rather than start with a key nobody can read")
	}
}

// Two containers starting against the same data directory must end up with one
// key, not two.
func TestConcurrentStartsAgreeOnOneKey(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "secret")

	const starts = 8
	values := make([]string, starts)
	var wg sync.WaitGroup
	for i := range values {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			got, err := Resolve("", path)
			if err != nil {
				t.Errorf("Resolve: %v", err)
				return
			}
			values[i] = got.Value
		}(i)
	}
	wg.Wait()

	for i, v := range values {
		if v != values[0] {
			t.Fatalf("start %d got %q, start 0 got %q", i, v, values[0])
		}
	}

	// And nothing else was left in the directory: the write goes through a temporary file
	// that has to be cleaned up, in the winning start and in the seven that lost.
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "secret" {
		names := make([]string, 0, len(entries))
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Fatalf("directory holds %v, want exactly [secret]", names)
	}
}

func TestInspectDescribesWithoutCreating(t *testing.T) {
	path := filepath.Join(t.TempDir(), "secret")

	if got := Inspect("", path); !strings.Contains(got, "first start") {
		t.Errorf("missing file: %q", got)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("Inspect created the file (stat err = %v)", err)
	}
	if got := Inspect("abc", path); !strings.Contains(got, "TUPING_SECRET") {
		t.Errorf("explicit key: %q", got)
	}
	if _, err := Resolve("", path); err != nil {
		t.Fatal(err)
	}
	if got := Inspect("", path); !strings.Contains(got, path) {
		t.Errorf("existing file: %q", got)
	}
}

func TestDefaultFileSitsNextToTheDatabase(t *testing.T) {
	if got, want := DefaultFile("/data/tuping.db"), filepath.Join("/data", "secret"); got != want {
		t.Fatalf("DefaultFile = %q, want %q", got, want)
	}
}
