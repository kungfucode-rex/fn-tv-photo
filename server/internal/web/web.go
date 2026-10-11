// Package web holds the H5 comment page and embeds it into the binary.
//
// go:embed is the reason there is no static file route and no separate asset
// directory to forget to copy: the whole page — HTML, CSS and all — travels
// inside the one binary that gets scp'd to the NAS (docs/tuping-design.md §11.2).
package web

import "embed"

// Files is the page set: the comment page, the one error page, and the admin's two
// pages — the login form and the single-comment edit form.
//
//go:embed tuping.html error.html login.html edit.html
var Files embed.FS
