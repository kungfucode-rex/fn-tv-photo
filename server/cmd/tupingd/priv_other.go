//go:build !linux

package main

import "log/slog"

// preparePrivileges does nothing away from Linux, and that is not a stub to be filled in
// later. The published server is a Linux binary (docs/tuping-design.md §11.6) and the only
// other build is the developer's own machine, where `go run ./cmd/tupingd` has no uid to
// adopt a directory for and no root to give up. See priv_linux.go for the real one.
func preparePrivileges(*slog.Logger, []string) {}
