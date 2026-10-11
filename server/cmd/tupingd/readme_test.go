package main

import (
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"unicode/utf8"
)

// The paste-ready YAML blocks in the READMEs are the only part of this repository that a
// *renderer* can break. Text copied out of a rendered page is re-wrapped to the window width by
// some tools, and one deployment showed the exact shape of it: an unquoted scalar containing
// `:` or `-` is broken at that character (`kungfucode/tuping:0.1.0` into two lines,
// `unless-stopped` into `unless-` and `stopped`), while every quoted value on the same page
// survives intact. Either way the block stops parsing, with `expected <block end>` and no hint
// about which line moved.
//
// Two properties keep those blocks copyable, and these constants are them.
const (
	// pasteBlockWidth is the widest line allowed inside a ```yaml block.
	pasteBlockWidth = 60

	// pasteFlowKeys is the set of structural lines that carry no value of their own.
	pasteKeyOnly = `^\s*[\w.-]+:\s*$`
	pasteEntry   = `^\s*[\w.-]+:\s*(\S.*)$`
	pasteItem    = `^\s*-\s*(\S.*)$`

	pasteFence = "```"
)

// TestPasteBlocksStayNarrow is the width half: a wide line is what a re-wrapping copy splits.
// The widest block used to be 84 characters, which is what wrapped in a deployment and cost
// somebody an evening.
func TestPasteBlocksStayNarrow(t *testing.T) {
	for _, path := range readmePaths(t) {
		for _, line := range yamlBlockLines(t, path) {
			if w := utf8.RuneCountInString(line.text); w > pasteBlockWidth {
				t.Errorf("%s:%d is %d characters wide (limit %d), so a copy that rewraps lines can split it: %s",
					filepath.Base(path), line.number, w, pasteBlockWidth, line.text)
			}
		}
	}
}

// TestPasteBlocksQuoteTheirValues is the other half. A quoted scalar is treated as one unit by
// whatever re-wraps the text, so `"kungfucode/tuping:0.1.1"` and `"unless-stopped"` come through
// the copy path that split their unquoted forms.
func TestPasteBlocksQuoteTheirValues(t *testing.T) {
	entry := regexp.MustCompile(pasteEntry)
	item := regexp.MustCompile(pasteItem)
	keyOnly := regexp.MustCompile(pasteKeyOnly)

	for _, path := range readmePaths(t) {
		for _, line := range yamlBlockLines(t, path) {
			text := line.text
			trimmed := strings.TrimSpace(text)
			if trimmed == "" || strings.HasPrefix(trimmed, "#") || keyOnly.MatchString(text) {
				continue
			}
			var value string
			switch {
			case entry.MatchString(text):
				value = entry.FindStringSubmatch(text)[1]
			case item.MatchString(text):
				value = item.FindStringSubmatch(text)[1]
			default:
				t.Errorf("%s:%d is not a mapping entry, a sequence item or a comment: %s",
					filepath.Base(path), line.number, text)
				continue
			}
			if !strings.HasPrefix(value, `"`) || !strings.HasSuffix(value, `"`) {
				t.Errorf("%s:%d has an unquoted value, which a re-wrapping copy can split at `:` or `-`: %s",
					filepath.Base(path), line.number, text)
			}
		}
	}
}

type blockLine struct {
	number int
	text   string
}

// yamlBlockLines returns every line inside a ```yaml fence, with its 1-based line number.
func yamlBlockLines(t *testing.T, path string) []blockLine {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	var out []blockLine
	inFence := false
	for n, line := range strings.Split(string(raw), "\n") {
		trimmed := strings.TrimSpace(line)
		if strings.HasPrefix(trimmed, pasteFence) {
			if inFence {
				inFence = false
			} else {
				inFence = trimmed == pasteFence+"yaml"
			}
			continue
		}
		if inFence {
			out = append(out, blockLine{number: n + 1, text: line})
		}
	}
	return out
}

// readmePaths is the three READMEs a deployer is told to copy from. The design document is
// deliberately not here: its YAML is an explanation of a decision, not something to paste.
func readmePaths(t *testing.T) []string {
	t.Helper()
	return []string{
		filepath.Join("..", "..", "..", "README.md"),
		filepath.Join("..", "..", "..", "README.en.md"),
		filepath.Join("..", "..", "README.md"),
	}
}
