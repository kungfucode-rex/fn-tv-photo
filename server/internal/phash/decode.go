package phash

import (
	"bytes"
	"encoding/binary"
	"fmt"
	"image"
	"image/draw"
	"io"
	"math/bits"

	// Formats a phone or a NAS may hand over. Registering them here means one
	// import site for the whole server.
	_ "image/gif"
	_ "image/jpeg"
	_ "image/png"

	_ "golang.org/x/image/bmp"
	_ "golang.org/x/image/tiff"
	_ "golang.org/x/image/webp"

	xdraw "golang.org/x/image/draw"
)

// decodeOriented decodes one uploaded image, applies its EXIF orientation, and
// refuses anything that would decode into an unreasonable amount of memory.
//
// The orientation matters because it is applied *before* the fingerprint: the
// same photo sent from a phone that writes Orientation=6 and from one that
// rotated the pixels itself would otherwise hash 90° apart and become two
// identities for one picture.
func decodeOriented(raw []byte) (image.Image, error) {
	orientation := exifOrientation(raw)

	cfg, _, err := image.DecodeConfig(bytes.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("phash: decode config: %w", err)
	}
	if cfg.Width <= 0 || cfg.Height <= 0 {
		return nil, fmt.Errorf("phash: image has no pixels")
	}
	if int64(cfg.Width)*int64(cfg.Height) > MaxPixels {
		return nil, ErrTooLarge
	}

	img, _, err := image.Decode(bytes.NewReader(raw))
	if err != nil {
		return nil, fmt.Errorf("phash: decode: %w", err)
	}
	return applyOrientation(img, orientation), nil
}

// applyOrientation rewrites the pixels so the stored image is upright and no
// EXIF is needed to read it.
//
// The eight values are the ones a phone or a camera actually writes; each names
// the transform the *stored* pixels need. Getting 6 and 8 the wrong way round
// does not look like a bug in a viewer — it looks like a photo that is a quarter
// turn out — which is exactly how the first version of this function was wrong
// until a test compared the pixels rather than the dimensions.
func applyOrientation(src image.Image, orientation int) image.Image {
	if orientation <= 1 || orientation > 8 {
		return src
	}

	b := src.Bounds()
	w, h := b.Dx(), b.Dy()

	var dst *image.RGBA
	switch orientation {
	case 5, 6, 7, 8:
		// The quarter turns swap the axes.
		dst = image.NewRGBA(image.Rect(0, 0, h, w))
	default:
		dst = image.NewRGBA(image.Rect(0, 0, w, h))
	}

	for y := 0; y < h; y++ {
		for x := 0; x < w; x++ {
			c := src.At(b.Min.X+x, b.Min.Y+y)
			var dx, dy int
			switch orientation {
			case 2: // mirrored horizontally
				dx, dy = w-1-x, y
			case 3: // rotated 180°
				dx, dy = w-1-x, h-1-y
			case 4: // mirrored vertically
				dx, dy = x, h-1-y
			case 5: // mirrored horizontally, then rotated 90° CCW
				dx, dy = y, x
			case 6: // rotated 90° CW — the usual "phone held sideways"
				dx, dy = h-1-y, x
			case 7: // mirrored horizontally, then rotated 90° CW
				dx, dy = h-1-y, w-1-x
			case 8: // rotated 90° CCW
				dx, dy = y, w-1-x
			default:
				dx, dy = x, y
			}
			dst.Set(dx, dy, c)
		}
	}
	return dst
}

// exifOrientation pulls the Orientation tag (0x0112) out of a JPEG's APP1/Exif
// segment. Anything it cannot read confidently yields 1, which means "already
// upright" — the same thing every viewer does with an unreadable tag.
func exifOrientation(raw []byte) int {
	const (
		markerAPP1 = 0xFFE1
	)

	r := bytes.NewReader(raw)
	var soi [2]byte
	if _, err := io.ReadFull(r, soi[:]); err != nil || soi[0] != 0xFF || soi[1] != 0xD8 {
		return 1
	}

	for {
		var marker [2]byte
		if _, err := io.ReadFull(r, marker[:]); err != nil {
			return 1
		}
		if marker[0] != 0xFF {
			return 1
		}
		// Skip fill bytes.
		for marker[1] == 0xFF {
			if _, err := io.ReadFull(r, marker[1:2]); err != nil {
				return 1
			}
		}
		if marker[1] == 0xDA || marker[1] == 0xD9 { // start of scan / end of image
			return 1
		}

		var sizeBuf [2]byte
		if _, err := io.ReadFull(r, sizeBuf[:]); err != nil {
			return 1
		}
		segLen := int(binary.BigEndian.Uint16(sizeBuf[:]))
		if segLen < 2 {
			return 1
		}
		segment := make([]byte, segLen-2)
		if _, err := io.ReadFull(r, segment); err != nil {
			return 1
		}
		if binary.BigEndian.Uint16(marker[:]) != markerAPP1 {
			continue
		}
		return orientationFromExif(segment)
	}
}

// orientationFromExif walks the TIFF header inside an APP1 segment looking for
// the one tag that matters. It is deliberately narrow: it reads tags it
// understands and gives up on everything else.
func orientationFromExif(segment []byte) int {
	if len(segment) < 14 || string(segment[0:6]) != "Exif\x00\x00" {
		return 1
	}
	tiff := segment[6:]

	var order binary.ByteOrder
	switch {
	case tiff[0] == 'I' && tiff[1] == 'I':
		order = binary.LittleEndian
	case tiff[0] == 'M' && tiff[1] == 'M':
		order = binary.BigEndian
	default:
		return 1
	}
	if order.Uint16(tiff[2:4]) != 42 {
		return 1
	}

	ifdOffset := int(order.Uint32(tiff[4:8]))
	if ifdOffset < 8 || ifdOffset+2 > len(tiff) {
		return 1
	}

	count := int(order.Uint16(tiff[ifdOffset : ifdOffset+2]))
	entry := ifdOffset + 2
	for i := 0; i < count; i++ {
		if entry+12 > len(tiff) {
			return 1
		}
		tag := order.Uint16(tiff[entry : entry+2])
		typ := order.Uint16(tiff[entry+2 : entry+4])
		if tag == 0x0112 && typ == 3 { // SHORT
			value := int(order.Uint16(tiff[entry+8 : entry+10]))
			if value >= 1 && value <= 8 {
				return value
			}
			return 1
		}
		entry += 12
	}
	return 1
}

// ScaleToWidth shrinks an image to exactly maxWidth, preserving the aspect
// ratio, and never enlarges it. It exists for the thumbnail endpoint's ?w=
// parameter; the canonical thumbnail itself comes from Normalize.
func ScaleToWidth(src image.Image, maxWidth int) image.Image {
	if maxWidth <= 0 || src.Bounds().Dx() <= maxWidth {
		return src
	}
	b := src.Bounds()
	w := maxWidth
	h := int(float64(b.Dy())*float64(maxWidth)/float64(b.Dx()) + 0.5)
	if h < 1 {
		h = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, w, h))
	xdraw.CatmullRom.Scale(dst, dst.Bounds(), src, b, draw.Src, nil)
	return dst
}

func putUint64(b []byte, v uint64) {
	binary.BigEndian.PutUint64(b, v)
}

func beUint64(b []byte) uint64 { return binary.BigEndian.Uint64(b) }

// popcount64 is the Hamming weight the distances are built from.
func popcount64(v uint64) int { return bits.OnesCount64(v) }
