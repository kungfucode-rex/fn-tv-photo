package com.tvphoto.data

import com.tvphoto.domain.TimelineDay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A person's photos are only queryable one day at a time, so the flat grid is
 * reassembled from per-day counts. These cases pin the paging arithmetic.
 */
class PersonDayPagingTest {

    private fun day(d: Int, count: Int) = TimelineDay(year = 2026, month = 10, day = d, count = count)

    /** Three days, one photo each — the shape the mock produces. */
    private val sparse = listOf(day(5, 1), day(4, 1), day(3, 1))

    /** Two days with several photos each — the shape a real library produces. */
    private val dense = listOf(day(5, 2), day(4, 3), day(3, 1))

    @Test
    fun `a window smaller than the library leaves more behind`() {
        val (slices, hasMore) = personDayWindow(sparse, limit = 2, offset = 0)
        assertEquals(listOf(5, 4), slices.map { it.day.day })
        assertTrue(slices.all { it.skip == 0 })
        assertTrue(hasMore)
    }

    @Test
    fun `a window covering everything reports no more`() {
        val (slices, hasMore) = personDayWindow(sparse, limit = 5, offset = 0)
        assertEquals(3, slices.size)
        assertFalse(hasMore)
    }

    @Test
    fun `an offset that lands mid-day carries the remainder into that day`() {
        // Two photos on day 5; skipping 1 means taking the second one.
        val (slices, _) = personDayWindow(dense, limit = 3, offset = 1)
        assertEquals(listOf(5, 4), slices.map { it.day.day })
        assertEquals(1, slices[0].skip)
        assertEquals(0, slices[1].skip)
    }

    @Test
    fun `an offset landing exactly on a day boundary skips whole days`() {
        val (slices, _) = personDayWindow(dense, limit = 3, offset = 2)
        assertEquals(listOf(4), slices.map { it.day.day })
        assertEquals(0, slices[0].skip)
    }

    @Test
    fun `an offset past the end yields nothing rather than throwing`() {
        val (slices, hasMore) = personDayWindow(sparse, limit = 10, offset = 3)
        assertTrue(slices.isEmpty())
        assertFalse(hasMore)
    }

    @Test
    fun `empty days are skipped without consuming the window`() {
        val withGaps = listOf(day(5, 0), day(4, 2), day(3, 0), day(2, 1))
        val (slices, _) = personDayWindow(withGaps, limit = 2, offset = 0)
        assertEquals(listOf(4), slices.map { it.day.day })
    }

    @Test
    fun `a page ending exactly on the last day reports no more`() {
        val (slices, hasMore) = personDayWindow(sparse, limit = 3, offset = 0)
        assertEquals(3, slices.size)
        assertFalse(hasMore)
    }

    @Test
    fun `degenerate inputs are rejected`() {
        assertTrue(personDayWindow(sparse, limit = 0, offset = 0).first.isEmpty())
        assertTrue(personDayWindow(sparse, limit = 5, offset = -1).first.isEmpty())
        assertTrue(personDayWindow(emptyList(), limit = 5, offset = 0).first.isEmpty())
    }
}
