package phash

import (
	"bytes"
	"encoding/binary"
	"image"
	"image/jpeg"
)

// jpegWithOrientation encodes an image and injects an EXIF APP1 segment carrying
// an Orientation tag. It exists so the orientation path can be tested with a real
// JPEG rather than a hand-built byte string that only looks like one.
func jpegWithOrientation(img image.Image, orientation uint16) ([]byte, error) {
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, img, &jpeg.Options{Quality: 80}); err != nil {
		return nil, err
	}
	raw := buf.Bytes()
	if len(raw) < 2 || raw[0] != 0xFF || raw[1] != 0xD8 {
		return raw, nil
	}

	app1 := buildExifOrientation(orientation)
	out := make([]byte, 0, len(raw)+len(app1))
	out = append(out, raw[0], raw[1]) // SOI
	out = append(out, app1...)
	out = append(out, raw[2:]...)
	return out, nil
}

// buildExifOrientation builds APP1 > "Exif\0\0" > TIFF(BE) > IFD0 with one entry.
func buildExifOrientation(orientation uint16) []byte {
	var tiff bytes.Buffer

	// TIFF header, big endian: "MM", 42, offset to IFD0 = 8.
	tiff.WriteString("MM")
	_ = binary.Write(&tiff, binary.BigEndian, uint16(42))
	_ = binary.Write(&tiff, binary.BigEndian, uint32(8))

	// IFD0: one entry, then a zero next-IFD offset.
	_ = binary.Write(&tiff, binary.BigEndian, uint16(1))
	_ = binary.Write(&tiff, binary.BigEndian, uint16(0x0112)) // Orientation
	_ = binary.Write(&tiff, binary.BigEndian, uint16(3))      // SHORT
	_ = binary.Write(&tiff, binary.BigEndian, uint32(1))      // count
	_ = binary.Write(&tiff, binary.BigEndian, orientation)    // value, left-aligned
	_ = binary.Write(&tiff, binary.BigEndian, uint16(0))      // padding to 4 bytes
	_ = binary.Write(&tiff, binary.BigEndian, uint32(0))      // no next IFD

	payload := append([]byte("Exif\x00\x00"), tiff.Bytes()...)

	segLen := len(payload) + 2
	out := make([]byte, 0, segLen+2)
	out = append(out, 0xFF, 0xE1)
	out = append(out, byte(segLen>>8), byte(segLen))
	out = append(out, payload...)
	return out
}
