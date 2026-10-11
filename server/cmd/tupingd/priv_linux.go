//go:build linux

package main

import (
	"fmt"
	"io/fs"
	"log/slog"
	"os"
	"path/filepath"
	"syscall"
)

// dataUID and dataGID are the account the server serves traffic as: the unprivileged user
// the image creates (`adduser -D -H -u 10001 tuping` in server/Dockerfile). A test asserts
// the two sides agree, because a silent disagreement here is a container that either runs
// as root or cannot write its database.
const (
	dataUID = 10001
	dataGID = 10001
)

// preparePrivileges is the whole privileged window of this program: it starts as root
// inside the container, takes ownership of the directories it has to write into, and then
// gives root up for good.
//
// This exists because of one collision that has nothing to do with this project and
// everything to do with how bind mounts work (docs/tuping-design.md §17.21): the process
// has a uid picked by the image, and the mounted directory has an owner picked by the
// *host* — Docker creates a missing one as root. Permission checks use the number, so the
// two disagree and SQLite cannot create its file. Asking every deployer to chown is asking
// them to know what a uid is; the postgres and redis images settled this the same way.
func preparePrivileges(log *slog.Logger, dirs []string) {
	if os.Geteuid() != 0 {
		// Already unprivileged: a platform that forced a user (compose `user:`), or a
		// developer running the binary by hand. Nothing to adopt, nothing to drop.
		return
	}

	if err := adoptDataDirs(dirs...); err != nil {
		// Not fatal, and deliberately not a refusal to start: a filesystem that will not
		// take a chown (an SMB or CIFS mount) is a real deployment, and root can still
		// write where the unprivileged uid cannot. Loud, because the process will now
		// serve traffic as root.
		log.Warn("could not take ownership of the data directory; staying root",
			"err", err, "dirs", dirs)
		return
	}

	if err := dropPrivileges(dataUID, dataGID); err != nil {
		log.Warn("could not drop root; staying root", "err", err, "uid", dataUID)
		return
	}
	log.Info("running unprivileged", "uid", dataUID, "gid", dataGID)
}

// adoptDataDirs hands [dirs] (and everything inside them) to dataUID.
func adoptDataDirs(dirs ...string) error {
	for _, dir := range dirs {
		if dir == "" {
			continue
		}
		info, err := os.Stat(dir)
		switch {
		case os.IsNotExist(err):
			// Created by root below, so it is already ours to give away.
			if err := os.MkdirAll(dir, 0o755); err != nil {
				return fmt.Errorf("create %s: %w", dir, err)
			}
		case err != nil:
			return fmt.Errorf("stat %s: %w", dir, err)
		case !needsAdopt(info, dataUID):
			// The fast path: after one successful start the directory is already
			// right, and walking a thumbnail cache to learn that is wasted work.
			continue
		}

		err = filepath.WalkDir(dir, func(path string, _ fs.DirEntry, err error) error {
			if err != nil {
				return err
			}
			if err := os.Lchown(path, dataUID, dataGID); err != nil {
				return fmt.Errorf("chown %s: %w", path, err)
			}
			return nil
		})
		if err != nil {
			return err
		}
	}
	return nil
}

// needsAdopt reports whether a directory still belongs to somebody else.
func needsAdopt(info os.FileInfo, uid int) bool {
	stat, ok := info.Sys().(*syscall.Stat_t)
	if !ok {
		// Not a Unix stat: nothing to compare, so try the chown and let it speak.
		return true
	}
	return int(stat.Uid) != uid
}

// dropPrivileges becomes dataUID for good. Groups go first: dropping the uid while still
// holding root's supplementary groups would leave half the privilege behind, and gid has
// to change before uid because only a privileged process may call setgid.
func dropPrivileges(uid, gid int) error {
	if err := syscall.Setgroups([]int{gid}); err != nil {
		return fmt.Errorf("setgroups: %w", err)
	}
	if err := syscall.Setgid(gid); err != nil {
		return fmt.Errorf("setgid: %w", err)
	}
	if err := syscall.Setuid(uid); err != nil {
		return fmt.Errorf("setuid: %w", err)
	}
	if os.Geteuid() != uid {
		return fmt.Errorf("setuid reported success but euid is still %d", os.Geteuid())
	}
	return nil
}
