package com.tvphoto.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which album plays itself when the television is left alone.
 *
 * The comparison this pins is between two spellings of the same number: the NAS sends album
 * ids as numbers, the preference stores a string, and the album card asks whether *this* card
 * is the screensaver. Getting it wrong is not a crash — it is a badge on the wrong album, or a
 * long press that offers 设为屏保相册 on the album that is already set — which is exactly the
 * kind of fault that survives a build and a screenshot.
 */
class ScreenSaverTest {

    @Test
    fun `the stored id matches the album it names`() {
        assertTrue(isScreenSaverAlbum(albumId = 1234, screenSaverAlbumId = "1234"))
    }

    @Test
    fun `a different album is not the screensaver`() {
        assertFalse(isScreenSaverAlbum(albumId = 1234, screenSaverAlbumId = "1235"))
    }

    @Test
    fun `nothing set means no album is the screensaver`() {
        assertFalse(isScreenSaverAlbum(albumId = 1234, screenSaverAlbumId = null))
        // Blank is what a preference that was written badly looks like, and it must not match
        // an album whose id could be spelled that way — the empty album id does not exist, but
        // "no album is set" answering true for one would put a badge on a card nobody chose.
        assertFalse(isScreenSaverAlbum(albumId = 0, screenSaverAlbumId = ""))
        assertFalse(isScreenSaverAlbum(albumId = 1234, screenSaverAlbumId = "  "))
    }

    /**
     * The id is compared as text, not parsed, so a value that is not a number at all is simply
     * not this album. Parsing it would be a crash waiting for a preference written by a future
     * version — and `toLongOrNull` would answer "no match" anyway, which is what this says.
     */
    @Test
    fun `a stored value that is not an id matches nothing`() {
        assertFalse(isScreenSaverAlbum(albumId = 1234, screenSaverAlbumId = "album-1234"))
    }

    /**
     * Five minutes, and the number is asserted rather than referenced: a test that reads the
     * constant it is testing can never fail, and this is the one value in the feature a user
     * would notice changing.
     */
    @Test
    fun `the idle timeout is five minutes`() {
        assertTrue(SCREEN_SAVER_IDLE_MILLIS == 300_000L)
    }
}
