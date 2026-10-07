package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The timeline draws its rows straight from the month buckets, so the ordering and
 * the "months with no photos get no card" rule are pinned here rather than only
 * being visible on screen.
 */
class TimelineYearsTest {

    @Test
    fun `years and months are ordered newest first`() {
        val rows = timelineYears(
            listOf(
                TimelineMonth(2025, 12, 4),
                TimelineMonth(2026, 1, 7),
                TimelineMonth(2026, 10, 15),
                TimelineMonth(2025, 3, 2),
            ),
        )

        assertEquals(listOf(2026, 2025), rows.map { it.year })
        assertEquals(listOf(10, 1), rows.first().months.map { it.month })
        assertEquals(listOf(12, 3), rows.last().months.map { it.month })
    }

    @Test
    fun `a year counts every item in its months`() {
        val rows = timelineYears(
            listOf(
                TimelineMonth(2026, 10, 15),
                TimelineMonth(2026, 9, 90),
            ),
        )

        assertEquals(105, rows.single().count)
    }

    @Test
    fun `months with no items are skipped`() {
        val rows = timelineYears(
            listOf(
                TimelineMonth(2026, 10, 15),
                TimelineMonth(2026, 9, 0),
                TimelineMonth(2026, 8, 93),
            ),
        )

        assertEquals(listOf(10, 8), rows.single().months.map { it.month })
    }

    @Test
    fun `a year whose months are all empty disappears`() {
        assertEquals(
            emptyList<TimelineYear>(),
            timelineYears(listOf(TimelineMonth(2026, 9, 0))),
        )
    }

    @Test
    fun `no months means no rows`() {
        assertEquals(emptyList<TimelineYear>(), timelineYears(emptyList()))
    }
}
