// Package phash turns an uploaded thumbnail into the layered fingerprint the
// design leans on: a 64-bit pHash and a 64-bit dHash, kept as one 16-byte value.
//
// Three things in here are not obvious and each of them was measured before it
// was written (docs/tuping-design.md §3.3, §15.1, §15.2):
//
//   - The canonical thumbnail is scaled, never cropped. Cropping to 16:9 pushed
//     one photo 54 bits away from itself, which is as far as a different photo.
//   - Borders are only stripped when they are near-black or near-white *and*
//     symmetric, and the result is an *additional* candidate, never a
//     replacement. A naive stripper ate a photo's own pale edge (mean 243) and
//     moved a 0-bit match to 17 bits.
//   - Flat images are not "randomly hashed", they collide with each other: pure
//     white and #F8F8F8 are 0 bits apart. That is what Variance is for; it is a
//     guard rail, not a nicety.
package phash

import (
	"bytes"
	"errors"
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"math"
	"sort"

	"github.com/corona10/goimagehash"
	xdraw "golang.org/x/image/draw"
)

// Version identifies the algorithm that produced a fingerprint. It is stored per
// photo because the algorithm will be upgraded and an old row must stay readable
// under the rules that produced it (docs/tuping-design.md §3.1).
const Version = 1

const (
	// CanonicalWidth is the width of the canonical thumbnail the server keeps and
	// serves. pHash normalizes to 32×32 internally, so the size itself does not
	// matter — that the aspect ratio is preserved is the part that matters.
	CanonicalWidth = 320

	// VarianceSize is the square the variance is measured on. §3.3's thresholds
	// (500) were measured against a 64×64 grayscale, so this is not free to change.
	VarianceSize = 64

	// AutoMergeDistance is the pHash and dHash threshold that permits an automatic
	// merge. Real matches scored at worst 4; the nearest genuinely different sample
	// was 12. 6 sits between them with room on both sides.
	AutoMergeDistance = 6

	// SuspectDistance caps the "probably the same photo" band. Inside it the two are
	// only *related*, never merged: a merge is a data accident and has to be resolved
	// by a sha256 arriving, or by a human.
	SuspectDistance = 11

	// RatioTolerance is how far apart two aspect ratios may be for one to be
	// considered a candidate for the other. A cropped photo must not get in.
	RatioTolerance = 0.02

	// MinVariance is the 64×64 grayscale variance below which a photo does not take
	// part in automatic merging. Real photographs measured 4377–6804; flat images 0.
	MinVariance = 500.0

	// edgeSearchLimit is how far in from each side the border stripper will look.
	edgeSearchLimit = 64

	// edgeFlatness is the per-column/row standard deviation under which a band of
	// pixels counts as uniform — the property padding has and a photograph's own
	// pale edge (mean 243, and varying) does not.
	edgeFlatness = 2.0

	// nearBlack / nearWhite are the means a uniform edge band must fall inside to be
	// treated as padding.
	nearBlack = 8.0
	nearWhite = 247.0

	// minStrippedSide keeps the stripper from eating a photo whole: 20% of the source
	// on any one side is the most it will take.
	maxStripFraction = 0.2
)

// Fingerprint is pHash64 ‖ dHash64 — the 16 bytes stored in photos.phash.
type Fingerprint [16]byte

// PHash returns the first 8 bytes, most significant bit first.
func (f Fingerprint) PHash() uint64 { return beUint64(f[0:8]) }

// DHash returns the last 8 bytes, most significant bit first.
func (f Fingerprint) DHash() uint64 { return beUint64(f[8:16]) }

// Bytes returns the stored representation.
func (f Fingerprint) Bytes() []byte {
	out := make([]byte, 16)
	copy(out, f[:])
	return out
}

// FingerprintFromBytes reads the stored representation. A short or empty slice
// yields the zero fingerprint rather than an error: rows written before a column
// existed must not make a query panic.
func FingerprintFromBytes(b []byte) Fingerprint {
	var f Fingerprint
	copy(f[:], b)
	return f
}

// Distance is the combined Hamming distance over both halves, 0–128.
func (f Fingerprint) Distance(other Fingerprint) int {
	return popcount64(f.PHash()^other.PHash()) + popcount64(f.DHash()^other.DHash())
}

// PHashDistance is the Hamming distance of the pHash halves alone.
func (f Fingerprint) PHashDistance(other Fingerprint) int {
	return popcount64(f.PHash() ^ other.PHash())
}

// DHashDistance is the Hamming distance of the dHash halves alone.
func (f Fingerprint) DHashDistance(other Fingerprint) int {
	return popcount64(f.DHash() ^ other.DHash())
}

// String renders the fingerprint as 32 hex characters — the two halves separated,
// because "pHash 2, dHash 0" is the form the measurements are written in.
func (f Fingerprint) String() string {
	return fmt.Sprintf("%016x:%016x", f.PHash(), f.DHash())
}

// Image is everything the store needs to know about one uploaded thumbnail.
type Image struct {
	// Canonical is the re-encoded, EXIF-free thumbnail. It is what gets stored and
	// served, and it is what the fingerprints were computed from.
	Canonical []byte

	// Width and Height are the canonical thumbnail's dimensions.
	Width, Height int

	// Variance of the 64×64 grayscale.
	Variance float64

	// Plain is the fingerprint of the canonical thumbnail as uploaded.
	Plain Fingerprint

	// Stripped is the fingerprint after symmetric near-black / near-white borders
	// were removed, or nil when there was nothing to strip. Held as an *additional*
	// candidate: either one matching is a match.
	Stripped *Fingerprint

	// StrippedWidth and StrippedHeight are the trimmed image's dimensions. They
	// matter: comparing the padded thumbnail's aspect ratio against an unpadded
	// photo rejects the very case stripping exists to rescue.
	StrippedWidth, StrippedHeight int

	// StrippedEdges records which edges were removed, for the log line.
	StrippedEdges string
}

// Candidates returns every fingerprint that may be compared against the library:
// the plain one always, the stripped one when stripping changed anything. Each
// carries its own aspect ratio, because they are different shapes.
func (im *Image) Candidates() []Candidate {
	out := []Candidate{{Fingerprint: im.Plain, Width: im.Width, Height: im.Height}}
	if im.Stripped != nil {
		out = append(out, Candidate{
			Fingerprint: *im.Stripped,
			Width:       im.StrippedWidth,
			Height:      im.StrippedHeight,
		})
	}
	return out
}

// Candidate is one fingerprint together with the shape it was computed from.
type Candidate struct {
	Fingerprint Fingerprint
	Width       int
	Height      int
}

// Ratio is the candidate's aspect ratio.
func (c Candidate) Ratio() float64 {
	if c.Height == 0 {
		return 0
	}
	return float64(c.Width) / float64(c.Height)
}

// ErrTooLarge is returned for an upload that decodes to more pixels than the
// decoder is willing to allocate.
var ErrTooLarge = errors.New("phash: image is larger than the decoder allows")

// MaxPixels caps what one upload may decode into. A 25 KB JPEG can declare
// 100000×100000 in its header; nothing legitimate is anywhere near this.
const MaxPixels = 80 << 20 // 80 megapixels

// Compute decodes an uploaded thumbnail, normalizes it, and fingerprints it.
//
// Decoding and re-encoding is not an optimization: it is how EXIF — including the
// GPS tag that "send original" leaks — is guaranteed to be gone, because Go's JPEG
// encoder does not write one (docs/tuping-design.md §2.2, §11.1).
func Compute(uploaded []byte) (*Image, error) {
	src, err := decodeOriented(uploaded)
	if err != nil {
		return nil, err
	}

	canonical := Normalize(src)
	png, err := encodeJPEG(canonical)
	if err != nil {
		return nil, err
	}

	plain, err := fingerprint(canonical)
	if err != nil {
		return nil, err
	}

	im := &Image{
		Canonical: png,
		Width:     canonical.Bounds().Dx(),
		Height:    canonical.Bounds().Dy(),
		Variance:  Variance(canonical),
		Plain:     plain,
	}

	if trimmed, edges := StripBorders(canonical); edges != "" {
		if stripped, err := fingerprint(trimmed); err == nil {
			im.Stripped = &stripped
			im.StrippedEdges = edges
			im.StrippedWidth = trimmed.Bounds().Dx()
			im.StrippedHeight = trimmed.Bounds().Dy()
		}
	}

	return im, nil
}

// Normalize scales an image down to CanonicalWidth, preserving the aspect ratio.
// Images already narrower than that are left alone: the canonical thumbnail may
// never be cropped, and upscaling one gains nothing.
func Normalize(src image.Image) image.Image {
	b := src.Bounds()
	if b.Dx() <= 0 || b.Dy() <= 0 {
		return image.NewRGBA(image.Rect(0, 0, 1, 1))
	}

	w, h := CanonicalWidth, int(math.Round(float64(b.Dy())*CanonicalWidth/float64(b.Dx())))
	if h < 1 {
		h = 1
	}
	if b.Dx() <= CanonicalWidth {
		w, h = b.Dx(), b.Dy()
	}

	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	xdraw.CatmullRom.Scale(dst, dst.Bounds(), src, b, draw.Src, nil)
	return dst
}

// Variance is the variance of the 64×64 grayscale the thresholds were measured on.
func Variance(img image.Image) float64 {
	gray := toGray(img, VarianceSize, VarianceSize)
	var sum, sumSq float64
	for _, v := range gray {
		f := float64(v)
		sum += f
		sumSq += f * f
	}
	n := float64(len(gray))
	if n == 0 {
		return 0
	}
	mean := sum / n
	return sumSq/n - mean*mean
}

// StripBorders removes uniform, near-black or near-white, symmetric borders —
// the padding a chat app adds when it fits a photo to a fixed aspect ratio — and
// reports which edges it took as a string such as "left+right".
//
// It returns the original image and an empty string when there is nothing to do.
// The caller uses the result as an extra candidate, never as a replacement,
// because §15.2 showed a naive stripper can eat a photo's own pale edge and turn
// a 0-bit match into a 17-bit one.
func StripBorders(img image.Image) (image.Image, string) {
	gray := toGray(img, img.Bounds().Dx(), img.Bounds().Dy())
	w, h := img.Bounds().Dx(), img.Bounds().Dy()
	if w < 8 || h < 8 {
		return img, ""
	}

	left := uniformBand(gray, w, h, edgeLeft)
	right := uniformBand(gray, w, h, edgeRight)
	top := uniformBand(gray, w, h, edgeTop)
	bottom := uniformBand(gray, w, h, edgeBottom)

	// Symmetry is the signature of padding; a photograph with a pale left edge
	// rarely has the identical pale edge on the right.
	var trimL, trimR, trimT, trimB int
	if left > 0 && right > 0 {
		trimL, trimR = left, right
	}
	if top > 0 && bottom > 0 {
		trimT, trimB = top, bottom
	}
	if trimL == 0 && trimR == 0 && trimT == 0 && trimB == 0 {
		return img, ""
	}

	var edges []string
	if trimL > 0 {
		edges = append(edges, "left")
	}
	if trimR > 0 {
		edges = append(edges, "right")
	}
	if trimT > 0 {
		edges = append(edges, "top")
	}
	if trimB > 0 {
		edges = append(edges, "bottom")
	}

	rect := image.Rect(
		img.Bounds().Min.X+trimL,
		img.Bounds().Min.Y+trimT,
		img.Bounds().Max.X-trimR,
		img.Bounds().Max.Y-trimB,
	)
	if rect.Dx() < w/4 || rect.Dy() < h/4 {
		return img, ""
	}

	dst := image.NewRGBA(image.Rect(0, 0, rect.Dx(), rect.Dy()))
	draw.Draw(dst, dst.Bounds(), img, rect.Min, draw.Src)

	// The trimmed region is returned as-is, not re-normalized to the canonical
	// width: scaling it again is one more resample between two pictures that are
	// supposed to be identical, and the fingerprint is scale-invariant anyway.
	return dst, joinEdges(edges)
}

type side int

const (
	edgeLeft side = iota
	edgeRight
	edgeTop
	edgeBottom
)

// uniformBand measures, from one side inward, how many leading columns (or rows)
// are uniform and near-black or near-white.
func uniformBand(gray []uint8, w, h int, s side) int {
	limit := edgeSearchLimit
	switch s {
	case edgeLeft, edgeRight:
		if max := int(float64(w) * maxStripFraction); limit > max {
			limit = max
		}
	case edgeTop, edgeBottom:
		if max := int(float64(h) * maxStripFraction); limit > max {
			limit = max
		}
	}

	run := 0
	for i := 0; i < limit; i++ {
		band := bandValues(gray, w, h, s, i)
		if len(band) == 0 {
			break
		}
		mean, std := meanStd(band)
		if std > edgeFlatness || (mean > nearBlack && mean < nearWhite) {
			break
		}
		run = i + 1
	}
	return run
}

// bandValues returns the pixels of the i-th line in from side s.
func bandValues(gray []uint8, w, h int, s side, i int) []uint8 {
	switch s {
	case edgeLeft:
		if i >= w {
			return nil
		}
		out := make([]uint8, h)
		for y := 0; y < h; y++ {
			out[y] = gray[y*w+i]
		}
		return out
	case edgeRight:
		x := w - 1 - i
		if x < 0 {
			return nil
		}
		out := make([]uint8, h)
		for y := 0; y < h; y++ {
			out[y] = gray[y*w+x]
		}
		return out
	case edgeTop:
		if i >= h {
			return nil
		}
		return append([]uint8(nil), gray[i*w:(i+1)*w]...)
	default:
		y := h - 1 - i
		if y < 0 {
			return nil
		}
		return append([]uint8(nil), gray[y*w:(y+1)*w]...)
	}
}

func meanStd(vals []uint8) (float64, float64) {
	if len(vals) == 0 {
		return 0, 0
	}
	var sum, sumSq float64
	for _, v := range vals {
		f := float64(v)
		sum += f
		sumSq += f * f
	}
	n := float64(len(vals))
	mean := sum / n
	variance := sumSq/n - mean*mean
	if variance < 0 {
		variance = 0
	}
	return mean, math.Sqrt(variance)
}

func joinEdges(edges []string) string {
	sort.Strings(edges)
	out := ""
	for i, e := range edges {
		if i > 0 {
			out += "+"
		}
		out += e
	}
	return out
}

// fingerprint computes the two halves of a Fingerprint for one image.
func fingerprint(img image.Image) (Fingerprint, error) {
	ph, err := goimagehash.PerceptionHash(img)
	if err != nil {
		return Fingerprint{}, fmt.Errorf("phash: perception hash: %w", err)
	}
	dh, err := goimagehash.DifferenceHash(img)
	if err != nil {
		return Fingerprint{}, fmt.Errorf("phash: difference hash: %w", err)
	}

	var f Fingerprint
	putUint64(f[0:8], ph.GetHash())
	putUint64(f[8:16], dh.GetHash())
	return f, nil
}

// toGray converts to an n×m grayscale using the same Rec. 601 luma weights the
// hash library uses internally, so variance and hashing look at one picture.
func toGray(img image.Image, n, m int) []uint8 {
	if n <= 0 || m <= 0 {
		return nil
	}
	scaled := image.NewRGBA(image.Rect(0, 0, n, m))
	xdraw.ApproxBiLinear.Scale(scaled, scaled.Bounds(), img, img.Bounds(), draw.Src, nil)

	out := make([]uint8, n*m)
	for y := 0; y < m; y++ {
		for x := 0; x < n; x++ {
			r, g, b, _ := scaled.At(x, y).RGBA()
			luma := (19595*uint32(r) + 38470*uint32(g) + 7471*uint32(b) + 1<<15) >> 24
			out[y*n+x] = uint8(luma)
		}
	}
	return out
}

func encodeJPEG(img image.Image) ([]byte, error) {
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 80}); err != nil {
		return nil, fmt.Errorf("phash: encode: %w", err)
	}
	return buf.Bytes(), nil
}

// Ensure the color model we rely on is actually the one image/jpeg reads.
var _ = color.RGBA{}
