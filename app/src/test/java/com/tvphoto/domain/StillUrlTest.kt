package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which still the viewer draws: the original file or the server's large thumbnail, each
 * falling back to the other when the server sent only one — and a clip always showing
 * its poster frame, since the player draws the video itself.
 */
class StillUrlTest {

    private fun photo(thumbnail: String?, full: String?) = MediaItem(
        id = 1,
        name = "IMG_1.jpg",
        isVideo = false,
        isLivePhoto = false,
        thumbnailUrl = thumbnail,
        fullUrl = full,
        videoUrl = null,
        takenAt = null,
        width = 4000,
        height = 2250,
        fileSize = 3_000_000,
    )

    private fun video(thumbnail: String?, full: String?) = photo(thumbnail, full).copy(
        id = 2,
        name = "VID_2.mp4",
        isVideo = true,
        videoUrl = "https://nas/video/2",
    )

    @Test
    fun `a photo is whichever of the two was asked for`() {
        val item = photo("https://nas/thumb/1", "https://nas/original/1")

        assertEquals("https://nas/original/1", item.stillUrl(original = true))
        assertEquals("https://nas/thumb/1", item.stillUrl(original = false))
    }

    @Test
    fun `a missing original falls back to the thumbnail`() {
        val item = photo("https://nas/thumb/1", full = null)

        assertEquals("https://nas/thumb/1", item.stillUrl(original = true))
        assertEquals("https://nas/thumb/1", item.stillUrl(original = false))
    }

    @Test
    fun `a missing thumbnail falls back to the original`() {
        val item = photo(thumbnail = null, full = "https://nas/original/1")

        assertEquals("https://nas/original/1", item.stillUrl(original = true))
        assertEquals("https://nas/original/1", item.stillUrl(original = false))
    }

    @Test
    fun `an entry the server sent no still for has none either way`() {
        val item = photo(thumbnail = null, full = null)

        assertEquals(null, item.stillUrl(original = true))
        assertEquals(null, item.stillUrl(original = false))
    }

    @Test
    fun `a clip shows its poster frame whatever the setting says`() {
        val item = video("https://nas/thumb/2", "https://nas/original/2")

        assertEquals("https://nas/thumb/2", item.stillUrl(original = true))
        assertEquals("https://nas/thumb/2", item.stillUrl(original = false))
    }

    @Test
    fun `only the original URL counts as the original`() {
        val item = photo("https://nas/thumb/1", "https://nas/original/1")

        assertEquals(true, item.isOriginalStill("https://nas/original/1"))
        assertEquals(false, item.isOriginalStill("https://nas/thumb/1"))
        assertEquals(false, item.isOriginalStill(null))
    }

    @Test
    fun `a URL that is both tiers at once is the original`() {
        // A server that answers with one URL for both sizes has sent the file itself, and
        // saying "thumbnail" about it would offer to fetch what is already on screen.
        val item = photo("https://nas/only/1", "https://nas/only/1")

        assertEquals(true, item.isOriginalStill("https://nas/only/1"))
        assertEquals(false, item.hasDistinctOriginal)
    }

    @Test
    fun `only a photo with a different original has one to move to`() {
        assertEquals(true, photo("https://nas/thumb/1", "https://nas/o/1").hasDistinctOriginal)
        assertEquals(false, photo("https://nas/thumb/1", null).hasDistinctOriginal)
        assertEquals(false, photo(null, null).hasDistinctOriginal)
        // A clip's own file is the video, which the player streams; there is no still to
        // fetch at full size.
        assertEquals(false, video("https://nas/thumb/2", "https://nas/o/2").hasDistinctOriginal)
    }
}
