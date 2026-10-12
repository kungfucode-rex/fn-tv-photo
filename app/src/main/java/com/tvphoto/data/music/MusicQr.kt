package com.tvphoto.data.music

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The QR code that gets a phone onto the upload page.
 *
 * The review feature gets its codes from its server as PNGs — that was the right call
 * there, because the server already draws them and a television should not carry a
 * library it can avoid. This one has no server to ask: the whole point of the upload page
 * is that it works with nothing deployed, so the code is drawn here instead.
 *
 * Only the matrix is built here; turning it into a bitmap is the Compose layer's job, so
 * this half is a plain JVM function that a unit test can call.
 */
object MusicQr {

    /**
     * The module count a URL of this shape needs, at the correction level below.
     *
     * `http://192.168.31.20:8123/u/AB3X9Q7M` is 41 characters; version 4 holds 46 bytes at
     * level M. Drawn at 320 px that is about 12 px per module, which is the size that
     * scans from a sofa at three metres — the same reading the review code's 440 px PNG
     * rests on.
     */
    const val DEFAULT_SIZE_PX = 320

    /**
     * Encodes [text] as a QR matrix of [size] × [size] pixels, with no quiet zone.
     *
     * No quiet zone because the panel draws its own white card around the code: a margin
     * baked into the bitmap would be white pixels the panel then pads again, and a code
     * whose finder patterns are further from the edge than the scanner expects is the one
     * failure mode this whole feature cannot afford.
     *
     * Level M rather than L: the code is on a glass screen a phone is pointing at from an
     * angle, and a few reconstruction bits are far cheaper than a retry.
     */
    fun matrix(text: String, size: Int = DEFAULT_SIZE_PX): BitMatrix = QRCodeWriter().encode(
        text,
        BarcodeFormat.QR_CODE,
        size,
        size,
        mapOf(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
        ),
    )
}
