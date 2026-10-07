package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A running slideshow re-reads its album every 30 s, and what a party album does is
 * exactly two things: photos turn up, and photos are taken away again. The arrivals are
 * [AppendNewItemsTest]'s job; these pin the departures — and, just as importantly, pin
 * what a *window* of a page may and may not be read as saying, so that a photo which
 * merely slid out of the window is never mistaken for a deleted one.
 */
class PruneRemovedItemsTest {

    private fun photo(id: Long) = MediaItem(
        id = id,
        name = "IMG_$id.jpg",
        isVideo = false,
        isLivePhoto = false,
        thumbnailUrl = null,
        fullUrl = null,
        videoUrl = null,
        takenAt = null,
        width = 0,
        height = 0,
        fileSize = 0,
    )

    private fun photos(vararg ids: Long) = ids.map(::photo)

    /** A page that ran out of items: it reached the end of the album. */
    private fun wholePage(vararg ids: Long) = MediaPage(photos(*ids), hasMore = false)

    /** A page that filled up: it says nothing about anything past its own window. */
    private fun windowPage(vararg ids: Long) = MediaPage(photos(*ids), hasMore = true)

    @Test
    fun `a photo missing from a complete page leaves the list`() {
        val loaded = photos(5, 4, 3, 2, 1)
        // 3 was deleted on the NAS, so the page comes back without it.
        val pruned = pruneRemovedItems(loaded, wholePage(5, 4, 2, 1))

        assertEquals(listOf(5L, 4L, 2L, 1L), pruned.map { it.id })
    }

    @Test
    fun `several deletions are all dropped`() {
        val pruned = pruneRemovedItems(photos(6, 5, 4, 3, 2), wholePage(6, 4, 2))

        assertEquals(listOf(6L, 4L, 2L), pruned.map { it.id })
    }

    @Test
    fun `what survives keeps its order`() {
        val pruned = pruneRemovedItems(photos(9, 8, 7, 6, 5), wholePage(9, 7, 5))

        assertEquals(listOf(9L, 7L, 5L), pruned.map { it.id })
    }

    @Test
    fun `deleting the newest photo drops it`() {
        val pruned = pruneRemovedItems(photos(5, 4, 3), wholePage(4, 3))

        assertEquals(listOf(4L, 3L), pruned.map { it.id })
    }

    @Test
    fun `an arrival alone removes nothing`() {
        val loaded = photos(5, 4, 3)
        val fresh = wholePage(6, 5, 4, 3)

        assertSame(loaded, pruneRemovedItems(loaded, fresh))
    }

    @Test
    fun `a full page cannot judge what is past its window`() {
        // Only the first page is re-read, so 2 and 1 are outside the window: they may
        // still exist, and may not be dropped on this page's word.
        val loaded = photos(5, 4, 3, 2, 1)
        val pruned = pruneRemovedItems(loaded, windowPage(5, 4, 3))

        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), pruned.map { it.id })
    }

    @Test
    fun `a photo deleted below a full page's window waits for a later pass`() {
        val loaded = photos(5, 4, 3, 2, 1)
        // 1 is gone on the NAS, but this window stops at 3.
        val pruned = pruneRemovedItems(loaded, windowPage(5, 4, 3))

        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), pruned.map { it.id })
    }

    @Test
    fun `a deletion inside a full page's window is still dropped`() {
        val loaded = photos(6, 5, 4, 3)
        // A full window that still reaches down to the oldest item on screen: 5 is gone.
        val pruned = pruneRemovedItems(loaded, windowPage(6, 4, 3))

        assertEquals(listOf(6L, 4L, 3L), pruned.map { it.id })
    }

    @Test
    fun `a photo the show appended and the host deleted is dropped`() {
        // The 100 uploaded during the party is at the tail, which a full window never
        // reaches — but it is one of the newest photos in the album, so its absence from
        // a fresh page is conclusive.
        val loaded = photos(102, 101, 100)
        val pruned = pruneRemovedItems(
            loaded = loaded,
            page = windowPage(102, 101),
            arrivals = setOf(100L),
        )

        assertEquals(listOf(102L, 101L), pruned.map { it.id })
    }

    @Test
    fun `an arrival that is still in the album is kept`() {
        val loaded = photos(102, 101, 100)
        val pruned = pruneRemovedItems(
            loaded = loaded,
            page = windowPage(102, 101, 100),
            arrivals = setOf(100L),
        )

        assertSame(loaded, pruned)
    }

    @Test
    fun `an unchanged page is the same list, so an idle poll costs nothing`() {
        val loaded = photos(3, 2, 1)

        assertSame(loaded, pruneRemovedItems(loaded, wholePage(3, 2, 1)))
    }

    @Test
    fun `an empty page is not evidence that the album was emptied`() {
        val loaded = photos(3, 2)

        assertSame(loaded, pruneRemovedItems(loaded, wholePage()))
    }

    @Test
    fun `a page sharing nothing with the list removes nothing`() {
        // A different album, or a server that answered something unexpected: not a
        // reason to empty a slideshow that is playing.
        val loaded = photos(3, 2, 1)

        assertSame(loaded, pruneRemovedItems(loaded, windowPage(30, 20, 10)))
    }

    @Test
    fun `an empty list stays empty`() {
        assertSame(emptyList<MediaItem>(), pruneRemovedItems(emptyList(), wholePage(1)))
    }

    @Test
    fun `pruned then appended is the whole pass the slideshow makes`() {
        val loaded = photos(5, 4, 3, 2, 1)
        // 3 was deleted and 6 was uploaded between two polls.
        val page = wholePage(6, 5, 4, 2, 1)

        val merged = appendNewItems(pruneRemovedItems(loaded, page), page.items)

        // The arrival still goes on the end — that is what keeps the photo currently on
        // screen at its index — while the deleted one is gone.
        assertEquals(listOf(5L, 4L, 2L, 1L, 6L), merged.map { it.id })
    }
}
