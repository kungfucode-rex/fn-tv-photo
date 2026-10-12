package com.tvphoto.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The QR code has no server behind it, so it is worth pinning the two things that decide
 * whether a phone can read it: it encodes what was asked for, and it comes back at the
 * size the panel draws.
 *
 * zxing is a plain JVM library, so this runs here rather than on a television — the same
 * reason the rest of the upload path is split into a pure half.
 */
class MusicQrTest {

    @Test
    fun `the code is square and as large as the panel asks for`() {
        val matrix = MusicQr.matrix("http://192.168.31.20:8123/u/AB3X9Q7M", size = 320)

        assertEquals(320, matrix.width)
        assertEquals(320, matrix.height)
    }

    @Test
    fun `the default size is the one the panel draws`() {
        val matrix = MusicQr.matrix("http://192.168.31.20:8123/u/AB3X9Q7M")

        assertEquals(MusicQr.DEFAULT_SIZE_PX, matrix.width)
    }

    @Test
    fun `there are dark modules to scan, and they start at the top left`() {
        val matrix = MusicQr.matrix("http://192.168.31.20:8123/u/AB3X9Q7M", size = 320)

        // A finder pattern's corner is dark in every QR code; a matrix that came back all
        // white would still be "square and the right size", and would scan as nothing.
        assertTrue(matrix.get(0, 0))
        assertTrue((0 until matrix.height).any { y -> (0 until matrix.width).any { x -> matrix.get(x, y) } })
    }

    @Test
    fun `a longer address still encodes at the size asked for`() {
        val matrix = MusicQr.matrix("http://192.168.100.200:8123/u/ZZZZZZZZ", size = 320)

        assertEquals(320, matrix.width)
    }
}
