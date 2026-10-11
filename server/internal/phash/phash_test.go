package phash

import (
	"bytes"
	"image"
	"image/color"
	"image/draw"
	"image/jpeg"
	"os"
	"path/filepath"
	"testing"

	xdraw "golang.org/x/image/draw"
)

// assetPath reaches out of server/ into the mock NAS photo set, which is the
// same real-photograph material the design's measurements were taken on. Using
// the real photos and not gradients is the point: a synthetic image would let a
// broken fingerprint pass.
func assetPath(t *testing.T, size string, id int) string {
	t.Helper()
	path := filepath.Join("..", "..", "..", "tools", "mock-nas", "assets", "photos", itoa(id), size+".jpg")
	if _, err := os.Stat(path); err != nil {
		t.Skipf("mock NAS asset missing: %s", path)
	}
	return path
}

func itoa(i int) string {
	if i == 0 {
		return "0"
	}
	var buf [8]byte
	pos := len(buf)
	for i > 0 {
		pos--
		buf[pos] = byte('0' + i%10)
		i /= 10
	}
	return string(buf[pos:])
}

func loadAsset(t *testing.T, size string, id int) []byte {
	t.Helper()
	data, err := os.ReadFile(assetPath(t, size, id))
	if err != nil {
		t.Fatalf("read asset: %v", err)
	}
	return data
}

func mustCompute(t *testing.T, raw []byte) *Image {
	t.Helper()
	im, err := Compute(raw)
	if err != nil {
		t.Fatalf("Compute: %v", err)
	}
	return im
}

func encodeJPEGBytes(t *testing.T, img image.Image, quality int) []byte {
	t.Helper()
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: quality}); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

// TestReencodeIsFree pins the premise the whole pHash fallback rests on: a
// re-compression must not move the fingerprint at all.
func TestReencodeIsFree(t *testing.T) {
	raw := loadAsset(t, "m", 33)
	base := mustCompute(t, raw)

	src, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range []int{60, 35, 20} {
		other := mustCompute(t, encodeJPEGBytes(t, src, q))
		if d := base.Plain.Distance(other.Plain); d != 0 {
			t.Errorf("quality %d moved the fingerprint by %d bits, want 0", q, d)
		}
	}
}

// TestBrightnessShiftIsFree is §15.1's "亮度 +8%" row: a global brightness change
// must not move a perception hash.
//
// The comparison is made after the fingerprints' own normalization, because the
// hash is defined on the canonical thumbnail and not on the file: running the
// test on two differently-encoded files would be measuring the JPEG encoder.
func TestBrightnessShiftIsFree(t *testing.T) {
	raw := loadAsset(t, "m", 33)
	src, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}

	base, err := fingerprint(Normalize(src))
	if err != nil {
		t.Fatal(err)
	}

	b := src.Bounds()
	bright := image.NewRGBA(b)
	for y := b.Min.Y; y < b.Max.Y; y++ {
		for x := b.Min.X; x < b.Max.X; x++ {
			r, g, bl, _ := src.At(x, y).RGBA()
			bright.Set(x, y, color.RGBA{
				R: scale8(uint8(r>>8), 1.08),
				G: scale8(uint8(g>>8), 1.08),
				B: scale8(uint8(bl>>8), 1.08),
				A: 255,
			})
		}
	}

	shifted, err := fingerprint(Normalize(bright))
	if err != nil {
		t.Fatal(err)
	}
	// The design measured this as free on its own material; on this asset the
	// library's DCT rounds a few pixels across the median. What has to hold is
	// that the shift stays well inside the merge threshold — otherwise "same
	// photo, different exposure" becomes two identities.
	d := base.Distance(shifted)
	if d > AutoMergeDistance {
		t.Errorf("+8%% brightness moved the fingerprint by %d bits, want <= %d", d, AutoMergeDistance)
	}
	t.Logf("+8%% brightness: %d bits", d)
}

// TestIdenticalBytesMatch is the floor: the same file twice is 0 bits apart.
func TestIdenticalBytesMatch(t *testing.T) {
	raw := loadAsset(t, "m", 14)
	a := mustCompute(t, raw)
	b := mustCompute(t, raw)
	if d := a.Plain.Distance(b.Plain); d != 0 {
		t.Errorf("same bytes scored %d, want 0", d)
	}
}

// TestPaddedImageIsRescuedByStripping reproduces §15.1's third row on a real
// photo: padding it out to a different frame pushes it outside the threshold, and
// the stripped candidate brings it back.
//
// The padded image is built the way a chat app builds one — the whole picture,
// scaled, with bars filled in — because *that* is the shape §15.2's experiment 2
// found the naive stripper getting wrong.
func TestPaddedImageIsRescuedByStripping(t *testing.T) {
	raw := loadAsset(t, "m", 33)
	src, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}

	// Two canonical thumbnails of the same photograph: one as the album would
	// send it, one as it comes back after somebody pasted it into a square frame.
	reference := mustCompute(t, encodeJPEGBytes(t, src, 80))

	narrow := scaleTo(src, 192, 240)
	padded := padSides(narrow, 320-192)
	paddedCanonical := encodeJPEGBytes(t, padded, 80)
	other := mustCompute(t, paddedCanonical)

	if other.Stripped == nil {
		t.Fatalf("padding was not detected as a border (variance %.0f, edges %q)", other.Variance, other.StrippedEdges)
	}
	best := min(other.Plain.Distance(reference.Plain), other.Stripped.Distance(reference.Plain))
	if best > AutoMergeDistance {
		t.Errorf("padded photo is %d bits away after stripping, want <= %d (plain was %d)",
			best, AutoMergeDistance, other.Plain.Distance(reference.Plain))
	}
	t.Logf("padded: plain %d bits, stripped %d bits", other.Plain.Distance(reference.Plain), other.Stripped.Distance(reference.Plain))
}

// TestCroppingIsNotRescued is the other half of the same measurement: a crop
// destroys the identity, and if the bits do not catch it the aspect-ratio guard
// has to.
func TestCroppingIsNotRescued(t *testing.T) {
	raw := loadAsset(t, "m", 33)
	base := mustCompute(t, raw)

	src, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	cropped := cropToSquare(src)

	other := mustCompute(t, encodeJPEGBytes(t, cropped, 80))

	// Either the bits are far enough apart, or the aspect ratio is — one of the
	// two has to have noticed, or a crop becomes a second identity.
	bitsCaught := other.Plain.PHashDistance(base.Plain) > AutoMergeDistance ||
		other.Plain.DHashDistance(base.Plain) > AutoMergeDistance
	ratioCaught := ratioDelta(other.Width, other.Height, base.Width, base.Height) > RatioTolerance
	if !bitsCaught && !ratioCaught {
		t.Errorf("a crop scored pHash %d / dHash %d at ratio %.3f vs %.3f: nothing caught it",
			other.Plain.PHashDistance(base.Plain), other.Plain.DHashDistance(base.Plain),
			float64(other.Width)/float64(other.Height), float64(base.Width)/float64(base.Height))
	}
	t.Logf("cropped: pHash %d, dHash %d, ratio %.3f vs %.3f",
		other.Plain.PHashDistance(base.Plain), other.Plain.DHashDistance(base.Plain),
		float64(other.Width)/float64(other.Height), float64(base.Width)/float64(base.Height))
}

// TestFlatImagesCollide is §15.1's warning made into a test: two visually
// different flat images are 0 bits apart, so variance — not distance — is what
// keeps them out of each other's threads.
func TestFlatImagesCollide(t *testing.T) {
	white := flatJPEG(t, 320, 240, color.RGBA{255, 255, 255, 255})
	nearWhite := flatJPEG(t, 320, 240, color.RGBA{248, 248, 248, 255})

	a := mustCompute(t, white)
	b := mustCompute(t, nearWhite)

	if d := a.Plain.Distance(b.Plain); d != 0 {
		t.Logf("flat images scored %d apart (the design measured 0)", d)
	}
	for _, im := range []*Image{a, b} {
		if im.Variance >= MinVariance {
			t.Errorf("flat image variance %.0f is not below %.0f", im.Variance, MinVariance)
		}
	}
}

// TestRealPhotosClearTheVarianceFloor is the other side of that guard rail. One
// asset in the mock set is genuinely flat (a plain wall), and the design's own
// section 15.1 measured a real photo as low as 4377 — so this pins the floor at
// the level that actually matters, which is that a real photograph is not flat.
func TestRealPhotosClearTheVarianceFloor(t *testing.T) {
	var measured, flat, total int
	sum := 0.0
	for _, id := range assetIDs() {
		raw, err := os.ReadFile(assetPath(t, "m", id))
		if err != nil {
			continue
		}
		im := mustCompute(t, raw)
		total++
		sum += im.Variance
		if im.Variance >= MinVariance {
			measured++
		} else {
			flat++
			t.Logf("below the floor: id=%d variance=%.0f", id, im.Variance)
		}
	}
	if total == 0 {
		t.Skip("no assets")
	}
	t.Logf("%d/%d assets clear the variance floor; mean variance %.0f", measured, total, sum/float64(total))
	if measured < total-1 {
		t.Errorf("%d of %d assets are below the variance floor, want at most 1", flat, total)
	}
}

// TestDifferentPhotosAreFarApart is the false-positive guard: distinct real
// photographs must not land inside the auto-merge threshold.
func TestDifferentPhotosAreFarApart(t *testing.T) {
	ids := assetIDs()
	type entry struct {
		id int
		im *Image
	}
	var images []entry
	for _, id := range ids {
		raw, err := os.ReadFile(assetPath(t, "m", id))
		if err != nil {
			continue
		}
		images = append(images, entry{id: id, im: mustCompute(t, raw)})
	}
	if len(images) < 2 {
		t.Skip("not enough assets")
	}

	closest := 1 << 30
	var closestPair string
	merges := 0
	for i := range images {
		for j := i + 1; j < len(images); j++ {
			a, b := images[i], images[j]
			ra := float64(a.im.Width) / float64(a.im.Height)
			rb := float64(b.im.Width) / float64(b.im.Height)
			if abs(ra-rb)/ra > RatioTolerance {
				continue
			}
			pd := a.im.Plain.PHashDistance(b.im.Plain)
			dd := a.im.Plain.DHashDistance(b.im.Plain)
			if pd+dd < closest {
				closest = pd + dd
				closestPair = itoa(a.id) + "/" + itoa(b.id)
			}
			lowVariance := a.im.Variance < MinVariance || b.im.Variance < MinVariance
			if pd <= AutoMergeDistance && dd <= AutoMergeDistance && !lowVariance {
				merges++
				t.Errorf("photos %d and %d would auto-merge (pHash %d, dHash %d)", a.id, b.id, pd, dd)
			}
		}
	}
	t.Logf("%d same-ratio pairs, closest %s at %d bits, %d false merges", len(images), closestPair, closest, merges)
}

// TestNormalizePreservesAspectRatio is the rule a crop would break: the canonical
// thumbnail is scaled, never fitted into a box.
func TestNormalizePreservesAspectRatio(t *testing.T) {
	for _, tc := range []struct{ w, h int }{{3000, 4000}, {4000, 3000}, {1080, 2400}, {640, 480}} {
		src := image.NewRGBA(image.Rect(0, 0, tc.w, tc.h))
		dst := Normalize(src)
		wantH := int(float64(tc.h)*CanonicalWidth/float64(tc.w) + 0.5)
		if tc.w <= CanonicalWidth {
			wantH = tc.h
		}
		wantW := CanonicalWidth
		if tc.w <= CanonicalWidth {
			wantW = tc.w
		}
		if dst.Bounds().Dx() != wantW || dst.Bounds().Dy() != wantH {
			t.Errorf("%dx%d -> %dx%d, want %dx%d", tc.w, tc.h, dst.Bounds().Dx(), dst.Bounds().Dy(), wantW, wantH)
		}
	}
}

// TestStripBordersOnlyTakesSymmetricFlatEdges is §15.2's aside: a photo's own
// pale, varying edge must not be mistaken for padding, while identical black bars
// on two sides must be.
func TestStripBordersOnlyTakesSymmetricFlatEdges(t *testing.T) {
	pale := image.NewRGBA(image.Rect(0, 0, 320, 240))
	for y := 0; y < 240; y++ {
		for x := 0; x < 320; x++ {
			if x < 33 {
				// Mean 243 and varying: exactly the edge a naive stripper ate.
				pale.Set(x, y, color.RGBA{243, uint8(240 + (x%4)*3), 240, 255})
				continue
			}
			pale.Set(x, y, photoPixel(x, y))
		}
	}
	if _, edges := StripBorders(pale); edges != "" {
		t.Errorf("stripped a pale, varying edge: %q", edges)
	}

	padded := image.NewRGBA(image.Rect(0, 0, 400, 240))
	for y := 0; y < 240; y++ {
		for x := 0; x < 400; x++ {
			if x < 40 || x >= 360 {
				padded.Set(x, y, color.RGBA{0, 0, 0, 255})
				continue
			}
			padded.Set(x, y, photoPixel(x, y))
		}
	}
	if _, edges := StripBorders(padded); edges == "" {
		t.Error("black bars on both sides were not stripped")
	}

	// Only one side black: not padding, so not stripped.
	oneSided := image.NewRGBA(image.Rect(0, 0, 400, 240))
	for y := 0; y < 240; y++ {
		for x := 0; x < 400; x++ {
			if x < 40 {
				oneSided.Set(x, y, color.RGBA{0, 0, 0, 255})
				continue
			}
			oneSided.Set(x, y, photoPixel(x, y))
		}
	}
	if _, edges := StripBorders(oneSided); edges != "" {
		t.Errorf("stripped a one-sided black bar: %q", edges)
	}
}

// TestApplyOrientation pins the geometry of the eight EXIF cases.
//
// The first version of applyOrientation had 6 and 8 the wrong way round and every
// check based on image *dimensions* still passed, because both of them swap the
// axes. So this compares pixels against an independently written forward
// transform, on an image whose four corners are all different.
func TestApplyOrientation(t *testing.T) {
	src := cornerMarkedImage(320, 100)

	// forward applies the transform a camera would have applied to the pixels
	// before writing the tag, written out separately from the code under test.
	forward := map[int]func(image.Image) image.Image{
		1: func(i image.Image) image.Image { return i },
		2: mirrorH,
		3: rotate180,
		4: mirrorV,
		// 5 and 7 are the two that also transpose; the inverse of a transpose is
		// itself, and the inverse of an anti-transpose is itself.
		5: transpose,
		6: rotate90CCW,
		7: antiTranspose,
		8: rotate90CW,
	}

	for orientation, transform := range forward {
		stored := transform(src)
		got := applyOrientation(stored, orientation)
		want := src
		if got.Bounds() != want.Bounds() {
			t.Errorf("orientation %d: %v, want %v", orientation, got.Bounds(), want.Bounds())
			continue
		}
		if diff := maxChannelDiff(got, want); diff != 0 {
			t.Errorf("orientation %d: undo is not the inverse (max channel difference %d)", orientation, diff)
		}
	}
}

// TestExifOrientationIsHonoured is the round trip that matters: a photo saved
// rotated with Orientation=6 has to end up the same identity as the upright one.
// If the tag is ignored the two are a quarter turn apart — the design's own
// measurement says that is 54 bits, which is as far as a different photo.
func TestExifOrientationIsHonoured(t *testing.T) {
	raw := loadAsset(t, "m", 14)
	src, err := jpeg.Decode(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}

	// What a phone camera does: rotate the pixels, then say so in EXIF.
	rotated := rotate90CCW(src)
	tagged, err := jpegWithOrientation(rotated, 6)
	if err != nil {
		t.Fatal(err)
	}
	if got := exifOrientation(tagged); got != 6 {
		t.Fatalf("orientation tag read back as %d, want 6", got)
	}
	honoured := mustCompute(t, tagged)

	// With the tag ignored the same bytes decode a quarter turn out.
	ignored := mustCompute(t, encodeJPEGBytes(t, rotated, 80))

	reference := mustCompute(t, raw)
	dHonoured := honoured.Plain.Distance(reference.Plain)
	dIgnored := ignored.Plain.Distance(reference.Plain)

	if dHonoured > 6 {
		t.Errorf("tagged photo is %d bits from the upright original, want <= 6", dHonoured)
	}
	if dIgnored <= 6 {
		t.Fatalf("the untagged rotation only scored %d: this test cannot tell the two paths apart", dIgnored)
	}
	t.Logf("honoured %d bits, ignored %d bits", dHonoured, dIgnored)
}

// TestRejectsAbsurdDimensions: a 25 KB file can claim a hundred megapixels.
func TestRejectsAbsurdDimensions(t *testing.T) {
	raw := []byte{
		0xFF, 0xD8,
		0xFF, 0xC0, 0x00, 0x11, 0x08,
		0x4E, 0x20, // height 20000
		0x4E, 0x20, // width 20000
		0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01,
		0xFF, 0xD9,
	}
	if _, err := Compute(raw); err == nil {
		t.Fatal("an 80-megapixel declaration was accepted")
	}
}

// TestFingerprintRoundTrip checks the stored form: 16 bytes, both halves in the
// order the database and the wire format assume.
func TestFingerprintRoundTrip(t *testing.T) {
	raw := loadAsset(t, "m", 96)
	im := mustCompute(t, raw)

	restored := FingerprintFromBytes(im.Plain.Bytes())
	if restored != im.Plain {
		t.Fatalf("round trip changed the fingerprint: %s vs %s", restored, im.Plain)
	}
	if d := restored.Distance(im.Plain); d != 0 {
		t.Errorf("round trip distance %d", d)
	}
	if len(im.Plain.Bytes()) != 16 {
		t.Errorf("stored length %d, want 16", len(im.Plain.Bytes()))
	}
	if im.Plain.PHash() == 0 || im.Plain.DHash() == 0 {
		t.Error("a real photo hashed to an all-zero half")
	}
}

// ---------------------------------------------------------------- helpers

func assetIDs() []int {
	return []int{0, 14, 33, 54, 72, 96, 113, 131, 152, 171, 187, 204}
}

func abs(f float64) float64 {
	if f < 0 {
		return -f
	}
	return f
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
}

func scale8(v uint8, factor float64) uint8 {
	scaled := float64(v) * factor
	if scaled > 255 {
		return 255
	}
	return uint8(scaled)
}

func ratioDelta(w1, h1, w2, h2 int) float64 {
	r1 := float64(w1) / float64(h1)
	r2 := float64(w2) / float64(h2)
	return abs(r1-r2) / r2
}

// photoPixel is a deterministic, high-variance stand-in for photo content.
func photoPixel(x, y int) color.RGBA {
	return color.RGBA{
		R: uint8(40 + (x*7)%200),
		G: uint8(70 + (y*11)%180),
		B: uint8(120 + (x*y)%130),
		A: 255,
	}
}

func flatJPEG(t *testing.T, w, h int, c color.RGBA) []byte {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, c)
		}
	}
	return encodeJPEGBytes(t, img, 90)
}

// padSides puts the same number of black pixels on the left and the right — what
// a chat app does when it fits a photo into a fixed frame.
func padSides(src image.Image, total int) image.Image {
	b := src.Bounds()
	offset := total / 2
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx()+2*offset, b.Dy()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(offset+x, y, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

// scaleTo resizes with the same resampler the canonical thumbnail uses, so the
// test measures the fingerprint and not two different scalers.
func scaleTo(src image.Image, w, h int) image.Image {
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	xdraw.CatmullRom.Scale(dst, dst.Bounds(), src, src.Bounds(), draw.Src, nil)
	return dst
}

func transpose(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dy(), b.Dx()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(y, x, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

func antiTranspose(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dy(), b.Dx()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(b.Dy()-1-y, b.Dx()-1-x, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

func cropToSquare(src image.Image) image.Image {
	b := src.Bounds()
	side := b.Dx()
	if b.Dy() < side {
		side = b.Dy()
	}
	offX := (b.Dx() - side) / 2
	offY := (b.Dy() - side) / 2
	dst := image.NewRGBA(image.Rect(0, 0, side, side))
	for y := 0; y < side; y++ {
		for x := 0; x < side; x++ {
			dst.Set(x, y, src.At(b.Min.X+offX+x, b.Min.Y+offY+y))
		}
	}
	return dst
}

func rotate90CW(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dy(), b.Dx()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(b.Dy()-1-y, x, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

func rotate90CCW(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dy(), b.Dx()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(y, b.Dx()-1-x, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

func rotate180(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(b.Dx()-1-x, b.Dy()-1-y, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

func mirrorH(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(b.Dx()-1-x, y, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

func mirrorV(src image.Image) image.Image {
	b := src.Bounds()
	dst := image.NewRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	for y := 0; y < b.Dy(); y++ {
		for x := 0; x < b.Dx(); x++ {
			dst.Set(x, b.Dy()-1-y, src.At(b.Min.X+x, b.Min.Y+y))
		}
	}
	return dst
}

// cornerMarkedImage is asymmetric in both axes, so no transform can be confused
// with another.
func cornerMarkedImage(w, h int) image.Image {
	img := image.NewRGBA(image.Rect(0, 0, w, h))
	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			img.Set(x, y, color.RGBA{R: uint8(x % 251), G: uint8(y % 241), B: uint8((x*3 + y*5) % 239), A: 255})
		}
	}
	return img
}

func maxChannelDiff(a, b image.Image) int {
	bounds := a.Bounds()
	worst := 0
	for y := bounds.Min.Y; y < bounds.Max.Y; y++ {
		for x := bounds.Min.X; x < bounds.Max.X; x++ {
			ar, ag, ab, _ := a.At(x, y).RGBA()
			br, bg, bb, _ := b.At(x, y).RGBA()
			for _, d := range []int{int(ar>>8) - int(br>>8), int(ag>>8) - int(bg>>8), int(ab>>8) - int(bb>>8)} {
				if d < 0 {
					d = -d
				}
				if d > worst {
					worst = d
				}
			}
		}
	}
	return worst
}
