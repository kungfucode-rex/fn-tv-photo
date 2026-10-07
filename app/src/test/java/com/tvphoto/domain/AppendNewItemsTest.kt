package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The slideshow's party mode hangs on this merge, so the rules that keep a running
 * show stable are pinned here: arrivals go on the end, nothing is ever listed twice,
 * and a poll that finds nothing new is a no-op.
 */
class AppendNewItemsTest {

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

    @Test
    fun `an arrival is appended, not put back at the front`() {
        // The server lists newest first, so photo 6 arrives ahead of 5; appending keeps
        // the photo already on screen at its index instead of shifting it.
        val loaded = listOf(photo(5), photo(4))
        val merged = appendNewItems(loaded, listOf(photo(6), photo(5), photo(4)))

        assertEquals(listOf(5L, 4L, 6L), merged.map { it.id })
    }

    @Test
    fun `several arrivals keep the order they came in`() {
        val merged = appendNewItems(listOf(photo(9)), listOf(photo(11), photo(10), photo(9)))

        assertEquals(listOf(9L, 11L, 10L), merged.map { it.id })
    }

    @Test
    fun `photos already on screen are never duplicated`() {
        val loaded = listOf(photo(3), photo(2), photo(1))
        val merged = appendNewItems(loaded, listOf(photo(3), photo(2), photo(1)))

        assertEquals(listOf(3L, 2L, 1L), merged.map { it.id })
    }

    @Test
    fun `a poll with nothing new leaves the list untouched`() {
        val loaded = listOf(photo(3), photo(2))
        val merged = appendNewItems(loaded, listOf(photo(3), photo(2)))

        assertSame(loaded, merged)
    }

    @Test
    fun `an empty page changes nothing`() {
        val loaded = listOf(photo(3))
        assertSame(loaded, appendNewItems(loaded, emptyList()))
    }

    @Test
    fun `the first page of a screen is taken as-is`() {
        assertEquals(listOf(8L, 7L), appendNewItems(emptyList(), listOf(photo(8), photo(7))).map { it.id })
    }
}
