package main

import "path/filepath"

// writableDirs are the directories the server must be able to write into: the database's
// directory, which also holds the minted signing key, and the thumbnail directory. They are
// what preparePrivileges hands to the serving uid, in one place so that a test can pin what
// gets adopted rather than leaving it to whoever edits the call site.
func writableDirs(dbPath, thumbDir string) []string {
	dbDir := filepath.Dir(dbPath)
	dirs := []string{dbDir}
	// Compared cleaned, not raw: "/data" and `filepath.Dir("/data/tuping.db")` are the same
	// directory that spell themselves differently on Windows, and a duplicate entry here is
	// a second walk of the whole thumbnail cache for nothing.
	if thumbDir != "" && filepath.Clean(thumbDir) != dbDir {
		dirs = append(dirs, thumbDir)
	}
	return dirs
}
