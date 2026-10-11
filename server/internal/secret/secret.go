// Package secret provides the signing key behind thumbnail URLs, admin session
// cookies and IP hashes.
//
// The key has two requirements that pull in opposite directions: nobody outside
// this instance may know it, and it has to survive a restart. Neither is a
// decision a deployer should have to make, so `tupingd` does it:
//
//   - TUPING_SECRET set  -> use it and touch no file. This is the explicit
//     override, and the only way to rotate the key by hand.
//   - else the key file  -> read it. The normal case, every boot after the first.
//   - else               -> mint 32 random bytes and write them to the file.
//
// The file lives next to the database (TUPING_SECRET_FILE moves it), because
// that is the directory a deployment already has to keep across a rebuild and
// already has to back up.
//
// Two failure modes are handled rather than papered over: an unreadable file is
// an error, because a server that cannot read its own key must not start with a
// different one; an *empty* file is replaced, because a crash between create and
// write leaves exactly that, and nobody's session can have depended on a key that
// was never usable.
package secret

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// FileName is the file's name inside the data directory.
const FileName = "secret"

// DefaultFile is the key's path when TUPING_SECRET_FILE is not set.
func DefaultFile(dbPath string) string {
	return filepath.Join(filepath.Dir(dbPath), FileName)
}

// Source says where a key came from. It is reported in the startup log and by
// `tupingd -check`, because "why did everyone get signed out" has exactly three
// possible answers and this is the one that distinguishes them.
type Source string

const (
	// SourceEnv is TUPING_SECRET, the explicit override.
	SourceEnv Source = "TUPING_SECRET"
	// SourceFile is the key file, read as-is.
	SourceFile Source = "file"
	// SourceNew is a key minted on this boot, either the first one or a
	// replacement for a file that held nothing usable.
	SourceNew Source = "new"
)

// Result is a resolved signing key.
type Result struct {
	Value  string
	Source Source
	Path   string
}

// Resolve returns the signing key to run with, minting and storing one if there
// is none yet.
func Resolve(explicit, path string) (Result, error) {
	if v := strings.TrimSpace(explicit); v != "" {
		return Result{Value: v, Source: SourceEnv}, nil
	}

	value, err := read(path)
	if err == nil && value != "" {
		return Result{Value: value, Source: SourceFile, Path: path}, nil
	}
	if err != nil && !errors.Is(err, fs.ErrNotExist) {
		return Result{}, fmt.Errorf("read signing key %s: %w", path, err)
	}

	value, err = mint(path)
	if err != nil {
		return Result{}, err
	}
	return Result{Value: value, Source: SourceNew, Path: path}, nil
}

// Inspect describes where Resolve would take the key from, without creating
// anything. It exists for `tupingd -check`, which must be safe to run on a NAS
// that has never started the server.
func Inspect(explicit, path string) string {
	if strings.TrimSpace(explicit) != "" {
		return "(set by TUPING_SECRET)"
	}
	value, err := read(path)
	switch {
	case err == nil && value != "":
		return "(read from " + path + ")"
	case err == nil:
		return "(an empty file at " + path + " would be replaced on start)"
	case errors.Is(err, fs.ErrNotExist):
		return "(not set yet: it will be minted into " + path + " on first start)"
	default:
		return "(unreadable: " + path + ")"
	}
}

// read returns the trimmed contents of the key file.
func read(path string) (string, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return strings.TrimSpace(string(raw)), nil
}

// mint writes a fresh key to [path] and returns it. If the file appeared in the
// meantime, the value already in it wins -- two containers starting at once must
// not run with two different keys.
func mint(path string) (string, error) {
	value, err := newValue()
	if err != nil {
		return "", err
	}

	err = writeNew(path, value)
	if err == nil {
		return value, nil
	}
	if !errors.Is(err, fs.ErrExist) {
		// Actionable on purpose: this is the one failure a deployer can hit
		// without touching their configuration, so the message names both ways
		// out.
		return "", fmt.Errorf(
			"create signing key %s: %w (set TUPING_SECRET to supply one, or point TUPING_SECRET_FILE at a writable directory)",
			path, err,
		)
	}

	// Someone got there first. Use theirs unless it is the empty file a crash
	// between create and write would leave behind.
	existing, readErr := read(path)
	if readErr == nil && existing != "" {
		return existing, nil
	}
	if err := os.Remove(path); err != nil {
		return "", fmt.Errorf("replace unusable signing key %s: %w", path, err)
	}
	if err := writeNew(path, value); err != nil {
		return "", fmt.Errorf("create signing key %s: %w", path, err)
	}
	return value, nil
}

// writeNew creates [path] with O_EXCL so that a concurrent start cannot be
// overwritten, and fsyncs it so that a power cut leaves either nothing or a
// whole key.
func writeNew(path, value string) error {
	if dir := filepath.Dir(path); dir != "" && dir != "." {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return err
		}
	}
	f, err := os.OpenFile(path, os.O_WRONLY|os.O_CREATE|os.O_EXCL, 0o600)
	if err != nil {
		return err
	}
	if _, err := f.WriteString(value + "\n"); err != nil {
		f.Close()
		os.Remove(path)
		return err
	}
	if err := f.Sync(); err != nil {
		f.Close()
		os.Remove(path)
		return err
	}
	return f.Close()
}

// newValue is 32 random bytes, the same shape `openssl rand -base64 32` prints.
func newValue() (string, error) {
	var buf [32]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return "", fmt.Errorf("signing key: %w", err)
	}
	return base64.StdEncoding.EncodeToString(buf[:]), nil
}
