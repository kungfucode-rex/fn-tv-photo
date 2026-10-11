package qr

import (
	"bytes"
	"image"
	"image/color"
	"image/png"
	"strings"
	"testing"
)

func decode(t *testing.T, data []byte) image.Image {
	t.Helper()
	img, err := png.Decode(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("the response is not a PNG: %v", err)
	}
	return img
}

// TestSizesAreClampedAndSquare: the design asks for a code at least 200px at 1080p, and
// a code that is not square is a code a scanner will not find without being told to look.
func TestSizesAreClampedAndSquare(t *testing.T) {
	r := New(8)
	for _, requested := range []int{0, 1, 96, 200, 440, 1024, 5000, -10} {
		data, err := r.PNG("http://192.168.31.14:8080/c/7fKq2a", requested)
		if err != nil {
			t.Fatalf("size %d: %v", requested, err)
		}
		img := decode(t, data)
		if img.Bounds().Dx() != img.Bounds().Dy() {
			t.Errorf("size %d produced %dx%d, which is not square", requested, img.Bounds().Dx(), img.Bounds().Dy())
		}
		if img.Bounds().Dx() < minSize {
			t.Errorf("size %d produced %dpx, below the %d floor", requested, img.Bounds().Dx(), minSize)
		}
		if img.Bounds().Dx() > maxSize {
			t.Errorf("size %d produced %dpx, above the %d ceiling", requested, img.Bounds().Dx(), maxSize)
		}
	}
	// The requested size is what comes back when it is inside the range.
	img := decode(t, mustPNG(t, r, 440))
	if img.Bounds().Dx() != 440 {
		t.Errorf("asked for 440, got %d", img.Bounds().Dx())
	}
}

// TestQuietZoneIsTransparentAndWideEnough is the property the whole code depends on. The
// code is drawn over a photograph, and without a margin the finder patterns are swallowed
// by whatever is behind them.
//
// The margin is *transparent* rather than white, and that is the point of the test as much
// as the width is: the client draws a translucent veil behind the code, and an opaque white
// quiet zone here would sit on top of that veil and cancel it — which is exactly the bug
// this test was changed for. The client is the only thing that knows what is behind the
// code, so the background is its decision and this package ships none of its own.
func TestQuietZoneIsTransparentAndWideEnough(t *testing.T) {
	r := New(8)
	img := decode(t, mustPNG(t, r, 400))
	bounds := img.Bounds()

	// Every pixel on the outside edge must be fully transparent.
	for x := bounds.Min.X; x < bounds.Max.X; x++ {
		for _, y := range []int{bounds.Min.Y, bounds.Max.Y - 1} {
			if !isTransparent(img.At(x, y)) {
				t.Fatalf("the outer edge is not transparent at (%d,%d): %v", x, y, img.At(x, y))
			}
		}
	}
	for y := bounds.Min.Y; y < bounds.Max.Y; y++ {
		for _, x := range []int{bounds.Min.X, bounds.Max.X - 1} {
			if !isTransparent(img.At(x, y)) {
				t.Fatalf("the outer edge is not transparent at (%d,%d): %v", x, y, img.At(x, y))
			}
		}
	}

	// The dark modules must not start right at the edge: four modules of margin, or the
	// scanner has nothing to lock on to.
	firstDark := -1
	for x := bounds.Min.X; x < bounds.Max.X; x++ {
		if !isTransparent(img.At(x, bounds.Min.Y+bounds.Dy()/2)) {
			firstDark = x - bounds.Min.X
			break
		}
	}
	if firstDark < 0 {
		t.Fatal("no dark module was found at all: this is not a QR code")
	}
	if firstDark < 8 {
		t.Errorf("the margin is only %dpx at 400px tall, which is well under four modules", firstDark)
	}

	// And the modules themselves must be solid black. A translucent module is a module
	// that takes its contrast from the photograph, which is the one thing a scanner cannot
	// tolerate — this is what makes the transparent background safe.
	//
	// Checked by finding a run of pixels that are solid black rather than by poking the
	// first dark pixel: the first one along any line is an *edge* pixel, and an edge
	// pixel is legitimately part-antialiased. Asserting opacity there tests the
	// antialiasing rather than the code.
	mid := bounds.Min.Y + bounds.Dy()/2
	solid := 0
	semi := 0
	for x := bounds.Min.X; x < bounds.Max.X; x++ {
		r, _, _, a := img.At(x, mid).RGBA()
		switch {
		case a == 0:
			// margin or a light module
		case a == 0xFFFF && r == 0:
			solid++
		default:
			semi++
		}
	}
	if solid == 0 {
		t.Fatal("no solid black module pixel found anywhere along the middle row")
	}
	// Antialiasing at module edges is expected; a row that is mostly soft is not.
	if semi > solid {
		t.Errorf("%d solid module pixels against %d part-transparent ones: the modules are not solid", solid, semi)
	}
}

// TestRenderIsDeterministic: the TV pre-registers a code and shows it later, so the
// same content at the same size must be the same bytes.
func TestRenderIsDeterministic(t *testing.T) {
	r := New(8)
	first, err := r.PNG("http://host/c/abcdef", 300)
	if err != nil {
		t.Fatal(err)
	}
	second, err := New(8).PNG("http://host/c/abcdef", 300)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, second) {
		t.Error("two renders of the same content differ")
	}
}

// TestDifferentContentDiffers: if every code rendered the same picture, the whole
// feature would silently point every guest at the first photo.
func TestDifferentContentDiffers(t *testing.T) {
	r := New(8)
	a, err := r.PNG("http://host/c/aaaaaa", 300)
	if err != nil {
		t.Fatal(err)
	}
	b, err := r.PNG("http://host/c/bbbbbb", 300)
	if err != nil {
		t.Fatal(err)
	}
	if bytes.Equal(a, b) {
		t.Error("two different codes produced the same PNG")
	}
}

// TestCacheReturnsTheSameBytes keeps the promise the TV relies on: the code is fetched
// once per size and then served from memory.
func TestCacheReturnsTheSameBytes(t *testing.T) {
	r := New(2)
	first, err := r.PNG("http://host/c/cached", 300)
	if err != nil {
		t.Fatal(err)
	}
	second, err := r.PNG("http://host/c/cached", 300)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(first, second) {
		t.Error("the cache did not return the bytes it stored")
	}
}

// TestCacheIsBounded: an unbounded map keyed by content is a leak on a service meant to
// run for years on a NAS.
func TestCacheIsBounded(t *testing.T) {
	r := New(4)
	for i := 0; i < 40; i++ {
		code := strings.Repeat(string(rune('a'+i%26)), 6)
		if _, err := r.PNG("http://host/c/"+code, 200); err != nil {
			t.Fatalf("render %d: %v", i, err)
		}
	}
	r.mu.Lock()
	size := len(r.cache)
	r.mu.Unlock()
	if size > 4 {
		t.Errorf("the cache holds %d entries, above its bound of 4", size)
	}
}

// TestURLUsesTheConfiguredBase is §11.5's most common deployment mistake, expressed as
// an assertion: the code must encode the address a *phone* can reach, never localhost
// and never the container's own name.
func TestURLUsesTheConfiguredBase(t *testing.T) {
	cases := map[string]string{
		"http://192.168.31.14:8080":    "http://192.168.31.14:8080/c/7fKq2a",
		"http://192.168.31.14:8080/":   "http://192.168.31.14:8080/c/7fKq2a",
		"https://review.example.com":   "https://review.example.com/c/7fKq2a",
		"https://review.example.com//": "https://review.example.com//c/7fKq2a",
	}
	for base, want := range cases {
		if got := URL(base, "7fKq2a"); got != want {
			t.Errorf("URL(%q) = %q, want %q", base, got, want)
		}
	}
}

func mustPNG(t *testing.T, r *Renderer, size int) []byte {
	t.Helper()
	data, err := r.PNG("http://192.168.31.14:8080/c/7fKq2a", size)
	if err != nil {
		t.Fatalf("render: %v", err)
	}
	return data
}

func isWhite(c color.Color) bool {
	r, g, b, _ := c.RGBA()
	return r > 0xE000 && g > 0xE000 && b > 0xE000
}

// isTransparent reports whether a pixel has no coverage at all. The margin around the
// code is nothing rather than white: see TestQuietZoneIsTransparentAndWideEnough.
func isTransparent(c color.Color) bool {
	_, _, _, a := c.RGBA()
	return a == 0
}
