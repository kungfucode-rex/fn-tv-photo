package com.tvphoto.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a list should fetch its next page.
 *
 * Both callers are pinned here, because the bug this replaced was exactly the asymmetry
 * between them: the grid asked for page two as its last rows came into view, and the viewer
 * never asked at all — so a slideshow over a 217-photo album played the first sixty and
 * started again.
 */
class NextPageTest {

    private val page = 60

    @Test
    fun `the grid asks eight from the end`() {
        // The first index that triggers it is itemCount - 8: at 52 of 60 the grid is eight
        // rows from the bottom, and this prefetch exists to arrive before the scroll does.
        assertFalse(needsNextPage(currentIndex = 51, itemCount = page, showRunning = false))
        assertTrue(needsNextPage(currentIndex = 52, itemCount = page, showRunning = false))
        assertTrue(needsNextPage(currentIndex = 59, itemCount = page, showRunning = false))
    }

    @Test
    fun `a show asks from anywhere in the list`() {
        // A shuffled show draws from the whole list, so position decides nothing: the album
        // it is walking is what has to be loaded.
        for (index in listOf(0, 7, 30, 59)) {
            assertTrue(
                "index $index",
                needsNextPage(currentIndex = index, itemCount = page, showRunning = true),
            )
        }
    }

    @Test
    fun `a list that cannot fill a screen asks at once`() {
        // Three photos in a grid, the first one visible: 0 >= 3 - 8, so the next page is
        // asked for immediately rather than when the user reaches a bottom they can see.
        assertTrue(needsNextPage(currentIndex = 0, itemCount = 3, showRunning = false))
    }

    @Test
    fun `an empty list never asks`() {
        assertFalse(needsNextPage(currentIndex = 0, itemCount = 0, showRunning = false))
        assertFalse(needsNextPage(currentIndex = 0, itemCount = 0, showRunning = true))
    }

    @Test
    fun `a page that lands moves the boundary out by exactly one page`() {
        // This is what makes the viewer's effect chain: re-keyed by itemCount, the photo that
        // needed page two at 51 of 60 needs page three at 51 of 120.
        assertFalse(needsNextPage(currentIndex = 51, itemCount = 2 * page, showRunning = false))
        assertTrue(needsNextPage(currentIndex = 2 * page - LOAD_MORE_DISTANCE, itemCount = 2 * page, showRunning = false))
    }
}
