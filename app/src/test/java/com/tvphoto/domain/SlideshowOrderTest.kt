package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * A slideshow's rotation holds photos only — a clip in the middle of one stops the
 * advance and talks over the music — and a picture already on screen or already on its
 * way is never queued twice. These pin both, plus the degenerate lists that would
 * otherwise spin: nothing but clips, or a single photo.
 */
class SlideshowOrderTest {

    private var nextId = 1L

    private fun photo(): MediaItem = MediaItem(
        id = nextId++,
        name = "IMG_$nextId.jpg",
        isVideo = false,
        isLivePhoto = false,
        thumbnailUrl = "https://nas/thumb/$nextId",
        fullUrl = null,
        videoUrl = null,
        takenAt = null,
        width = 4000,
        height = 2250,
        fileSize = 3_000_000,
    )

    private fun video(): MediaItem = MediaItem(
        id = nextId++,
        name = "VID_$nextId.mp4",
        isVideo = true,
        isLivePhoto = false,
        thumbnailUrl = "https://nas/thumb/$nextId",
        fullUrl = null,
        videoUrl = "https://nas/video/$nextId",
        takenAt = null,
        width = 1920,
        height = 1080,
        fileSize = 7_000_000,
    )

    /** The picked id, so a failure reads as a number rather than as object identity. */
    private fun pick(
        items: List<MediaItem>,
        fromIndex: Int,
        taken: Set<Long> = emptySet(),
        shuffled: Boolean = false,
        seed: Int = 0,
    ): Long? = nextSlideshowItem(items, fromIndex, taken, shuffled, Random(seed))?.id

    @Test
    fun `a clip in the middle of the list is stepped over`() {
        val a = photo()
        val clip = video()
        val b = photo()

        assertEquals(b.id, pick(listOf(a, clip, b), fromIndex = 0))
    }

    @Test
    fun `several clips in a row are stepped over`() {
        val a = photo()
        val one = video()
        val two = video()
        val three = video()
        val b = photo()

        assertEquals(b.id, pick(listOf(a, one, two, three, b), fromIndex = 0))
    }

    @Test
    fun `the walk wraps round the end of the list`() {
        val a = photo()
        val b = photo()
        val clip = video()

        // From the last photo the next picture is the first one, past the clip.
        assertEquals(a.id, pick(listOf(a, b, clip), fromIndex = 1))
    }

    @Test
    fun `nothing is picked when every photo is already taken`() {
        val a = photo()
        val b = photo()

        // b is on screen and a is queued: there is nowhere left to go, and returning
        // either of them again would show one picture twice per pass.
        assertNull(pick(listOf(a, b), fromIndex = 0, taken = setOf(a.id, b.id)))
    }

    @Test
    fun `a library of nothing but clips has nowhere to go`() {
        val clip = video()
        val other = video()

        assertNull(pick(listOf(clip, other), fromIndex = 0))
        assertNull(pick(listOf(clip, other), fromIndex = 0, shuffled = true))
    }

    @Test
    fun `a single photo does not advance to itself`() {
        val only = photo()

        assertNull(pick(listOf(only), fromIndex = 0))
        assertNull(pick(listOf(only), fromIndex = 0, shuffled = true))
    }

    @Test
    fun `shuffling never picks a clip or anything already taken`() {
        val a = photo()
        val clip = video()
        val b = photo()
        val c = photo()
        val items = listOf(a, clip, b, c)

        // Every seed, because a shuffle that can return a clip will eventually do so.
        val drawn = (0 until 200).map { seed ->
            pick(items, fromIndex = 0, taken = setOf(a.id), shuffled = true, seed = seed)
        }

        assertEquals(setOf(b.id, c.id), drawn.toSet())
    }

    @Test
    fun `shuffling returns nothing rather than a repeat when only taken photos are left`() {
        val a = photo()
        val b = photo()

        assertNull(pick(listOf(a, b), fromIndex = 0, taken = setOf(a.id, b.id), shuffled = true))
    }
}
