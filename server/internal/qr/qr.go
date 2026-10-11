// Package qr renders the QR code the TV puts in the corner of a photo.
//
// The server makes the PNG, not the TV: the TV only downloads an image, so it
// needs no QR library and no camera-facing dependency of its own
// (docs/tuping-design.md §6.1).
package qr

import (
	"bytes"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"strings"
	"sync"

	qrcode "github.com/skip2/go-qrcode"
	xdraw "golang.org/x/image/draw"
)

// QuietZone is the margin, in modules, the spec asks for. The design says four
// modules and §15.2's experiment material was built that way, so it is not
// configurable: a code with a thin margin is a code that does not scan.
const QuietZone = 4

// DefaultSize is what a registration asks for unless it says otherwise: at
// 1080p the design wants ≥200px, and 440 covers a 2× box for a 220px slot.
const DefaultSize = 440

const (
	minSize = 96
	maxSize = 1024
)

// Renderer generates and caches QR PNGs. The cache matters because the TV
// pre-registers several photos ahead and then asks for the same code again when
// it shows one: encoding a QR is milliseconds, but the round trip is not.
type Renderer struct {
	mu    sync.Mutex
	cache map[string][]byte
	order []string
	max   int
}

// New returns a renderer that keeps at most cacheSize PNGs.
func New(cacheSize int) *Renderer {
	if cacheSize <= 0 {
		cacheSize = 256
	}
	return &Renderer{cache: map[string][]byte{}, max: cacheSize}
}

// PNG returns the QR code for content at the requested size.
func (r *Renderer) PNG(content string, size int) ([]byte, error) {
	size = clampSize(size)
	key := fmt.Sprintf("%d|%s", size, content)

	r.mu.Lock()
	if cached, ok := r.cache[key]; ok {
		r.mu.Unlock()
		return cached, nil
	}
	r.mu.Unlock()

	png, err := render(content, size)
	if err != nil {
		return nil, err
	}

	r.mu.Lock()
	if _, exists := r.cache[key]; !exists {
		r.cache[key] = png
		r.order = append(r.order, key)
		for len(r.order) > r.max {
			oldest := r.order[0]
			r.order = r.order[1:]
			delete(r.cache, oldest)
		}
	}
	r.mu.Unlock()

	return png, nil
}

func render(content string, size int) ([]byte, error) {
	// Medium recovery is the design's choice by implication: the code sits on top
	// of a photograph, and a photo is a noisy background.
	code, err := qrcode.New(content, qrcode.Medium)
	if err != nil {
		return nil, fmt.Errorf("qr: encode: %w", err)
	}

	// qrcode draws its own quiet zone; we draw the white plate ourselves so the
	// margin is exactly QuietZone modules at every size.
	code.DisableBorder = true
	bitmap := code.Bitmap()

	modules := len(bitmap)
	if modules == 0 {
		return nil, fmt.Errorf("qr: empty bitmap")
	}

	total := modules + 2*QuietZone
	// Integer scale where it fits: a code drawn with fractional module edges is a
	// code with soft edges, and soft edges are what fails at three metres.
	scale := size / total
	if scale < 1 {
		scale = 1
	}
	dim := total * scale

	// The plate is left fully transparent and only the modules are drawn.
	//
	// It used to be filled white, and that white was the whole reason the TV's own
	// translucent plate did nothing: the client composited a 28%-white veil over the
	// photograph and then pasted an *opaque* white square on top of it, so the corner
	// came out solid white whatever the client did. The quiet zone has to be
	// transparent for the client to be able to decide what the code sits on — a
	// photograph with a veil, or a plain white card, or anything else.
	//
	// Nothing about the code itself is transparent: every dark module is opaque black,
	// so the contrast a scanner reads is unchanged. What moved is only the decision
	// about the background, from here to the only place that knows what is behind it.
	img := image.NewRGBA(image.Rect(0, 0, dim, dim))
	black := &image.Uniform{color.Black}
	for y, row := range bitmap {
		for x, on := range row {
			if !on {
				continue
			}
			rect := image.Rect(
				(QuietZone+x)*scale,
				(QuietZone+y)*scale,
				(QuietZone+x+1)*scale,
				(QuietZone+y+1)*scale,
			)
			draw.Draw(img, rect, black, image.Point{}, draw.Src)
		}
	}

	// A caller that asks for a size no integer scale can hit gets a clean resize
	// as a last resort, rather than a code with jagged modules.
	if dim != size {
		scaled := image.NewRGBA(image.Rect(0, 0, size, size))
		xdraw.CatmullRom.Scale(scaled, scaled.Bounds(), img, img.Bounds(), draw.Src, nil)
		img = scaled
	}

	var buf bytes.Buffer
	if err := png.Encode(&buf, img); err != nil {
		return nil, fmt.Errorf("qr: encode png: %w", err)
	}
	return buf.Bytes(), nil
}

func clampSize(size int) int {
	if size <= 0 {
		return DefaultSize
	}
	if size < minSize {
		return minSize
	}
	if size > maxSize {
		return maxSize
	}
	return size
}

// URL is the address a code points at. It is built from the configured base URL,
// never from the request's Host header: the base URL is the address a *phone* can
// reach, and the Host header is whatever the caller typed (docs/tuping-design.md §11.5).
func URL(baseURL, code string) string {
	return strings.TrimSuffix(baseURL, "/") + "/c/" + code
}
