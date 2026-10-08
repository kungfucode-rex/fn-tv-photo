package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The paging pointer: one step per press in the direction it is walking, the step given
 * back when the direction reverses, and the fact that the queue is allowed to be longer
 * than the one photo the viewer will fetch — it is how far the pointer went.
 */
class PagingQueueTest {

    private val a = photo(1)
    private val b = photo(2)
    private val c = photo(3)
    private val d = photo(4)
    private val items = listOf(a, b, c, d)

    private fun photo(id: Long) = MediaItem(
        id = id,
        name = "IMG_$id.jpg",
        isVideo = false,
        isLivePhoto = false,
        thumbnailUrl = "https://nas/thumb/$id",
        fullUrl = "https://nas/original/$id",
        videoUrl = null,
        takenAt = null,
        width = 4000,
        height = 2250,
        fileSize = 3_000_000,
    )

    @Test
    fun `a press right queues the photo after the one on screen`() {
        assertEquals(listOf(b.id), walkPagingPointer(items, a.id, emptyList(), true, 1))
    }

    @Test
    fun `pressing right three times queues three steps and keeps them in order`() {
        var pending = emptyList<Long>()
        repeat(3) { pending = walkPagingPointer(items, a.id, pending, true, 1) }

        // Three presses, three arrows — and the viewer only fetches the last of them.
        assertEquals(listOf(b.id, c.id, d.id), pending)
    }

    @Test
    fun `a press left after right gives the step back instead of queueing the other way`() {
        val right = walkPagingPointer(items, a.id, emptyList(), true, 1)

        assertEquals(emptyList<Long>(), walkPagingPointer(items, a.id, right, true, -1))
    }

    @Test
    fun `backing up unwinds the queue one step at a time`() {
        var pending = walkPagingPointer(items, a.id, emptyList(), true, 1)
        pending = walkPagingPointer(items, a.id, pending, true, 1)

        val back = walkPagingPointer(items, a.id, pending, true, -1)
        assertEquals(listOf(b.id), back)
        // Still walking rightwards and still holding a step: another left gives it back.
        assertEquals(emptyList<Long>(), walkPagingPointer(items, a.id, back, true, -1))
    }

    @Test
    fun `walking past either end of the list wraps round`() {
        assertEquals(listOf(a.id), walkPagingPointer(items, d.id, emptyList(), true, 1))
        assertEquals(listOf(d.id), walkPagingPointer(items, a.id, emptyList(), false, -1))
    }

    @Test
    fun `the walk follows the end of the queue rather than the photo on screen`() {
        // A second press right lands on the photo after the first step, not the photo
        // after the one on screen.
        assertEquals(listOf(c.id, d.id), walkPagingPointer(items, b.id, listOf(c.id), true, 1))
    }

    @Test
    fun `a one-photo library has nowhere to walk`() {
        val pending = emptyList<Long>()

        assertSame(pending, walkPagingPointer(listOf(a), a.id, pending, true, 1))
    }

    @Test
    fun `a queue holding an id the list no longer has cannot be walked from`() {
        val pending = listOf(99L)

        assertSame(pending, walkPagingPointer(items, a.id, pending, true, 1))
    }

    @Test
    fun `nothing is queued when the photo on screen is no longer in the list`() {
        val pending = emptyList<Long>()

        assertSame(pending, walkPagingPointer(items, 99L, pending, true, 1))
    }
}
