package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// TestWritableDirs pins what preparePrivileges adopts. The thumbnail directory is normally
// inside the database's directory, so the second entry is usually redundant — it is there
// for the deployment that moves TUPING_THUMBS somewhere else, which is exactly the one that
// would otherwise fail at the first upload.
func TestWritableDirs(t *testing.T) {
	cases := []struct {
		name     string
		dbPath   string
		thumbDir string
		want     []string
	}{
		{
			name:     "the default layout",
			dbPath:   filepath.Join("/data", "tuping.db"),
			thumbDir: filepath.Join("/data", "thumbs"),
			want:     []string{"/data", filepath.Join("/data", "thumbs")},
		},
		{
			name:     "thumbnails moved next to the database is the same directory",
			dbPath:   filepath.Join("/data", "tuping.db"),
			thumbDir: "/data",
			want:     []string{"/data"},
		},
		{
			name:     "no thumbnail directory configured",
			dbPath:   filepath.Join("/data", "tuping.db"),
			thumbDir: "",
			want:     []string{"/data"},
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := writableDirs(tc.dbPath, tc.thumbDir)
			if len(got) != len(tc.want) {
				t.Fatalf("writableDirs = %v, want %v", got, tc.want)
			}
			for i := range got {
				if filepath.Clean(got[i]) != filepath.Clean(tc.want[i]) {
					t.Fatalf("writableDirs = %v, want %v", got, tc.want)
				}
			}
		})
	}
}

// TestDockerfileAgreesWithDataUID is the guard on a duplicated number. The uid the server
// drops to lives in Go (priv_linux.go) and in the image (the adduser line); if they drift,
// the container either serves as root without saying so or cannot write its database, and
// neither shows up until somebody deploys it.
func TestDockerfileAgreesWithDataUID(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "Dockerfile"))
	if err != nil {
		t.Fatalf("read the Dockerfile: %v", err)
	}
	dockerfile := string(raw)

	if want := "-u 10001"; !strings.Contains(dockerfile, want) {
		t.Errorf("the Dockerfile no longer creates the account as %s, so dataUID in priv_linux.go is stale", want)
	}
	// The image must start as root: that is the only reason preparePrivileges can adopt a
	// bind mount whose owner the host chose. An earlier version pinned `USER tuping` here,
	// which is why the deployer had to chown by hand. Checked as an instruction, not as a
	// substring, so that the comment above it may discuss the line that must not come back.
	for _, line := range strings.Split(dockerfile, "\n") {
		if strings.HasPrefix(strings.TrimSpace(line), "USER ") {
			t.Errorf("the Dockerfile pins %q again; the deployer would have to chown the data directory by hand", strings.TrimSpace(line))
		}
	}
}
