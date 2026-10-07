package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A month's cover is that month's first photo, so it has to be dropped exactly when that
 * month's contents moved. Dropping too few leaves a stale thumbnail on the timeline;
 * dropping too many re-fetches covers for months that did not change.
 */
class ChangedMonthIdsTest {

    private fun year(y: Int, vararg months: Pair<Int, Int>) = TimelineYear(
        year = y,
        count = months.sumOf { it.second },
        months = months.map { (month, count) -> TimelineMonth(y, month, count) },
    )

    @Test
    fun `a month that gained photos is reported`() {
        val before = listOf(year(2026, 10 to 15, 9 to 90))
        val after = listOf(year(2026, 10 to 17, 9 to 90))

        assertEquals(setOf("2026-10"), changedMonthIds(before, after))
    }

    @Test
    fun `unchanged months are left alone`() {
        val before = listOf(year(2026, 10 to 15, 9 to 90))
        val after = listOf(year(2026, 10 to 15, 9 to 90))

        assertEquals(emptySet<String>(), changedMonthIds(before, after))
    }

    @Test
    fun `a brand new month is not a change`() {
        // Nothing was cached for it, so there is no cover to invalidate.
        val before = listOf(year(2026, 10 to 15))
        val after = listOf(year(2026, 10 to 15, 11 to 4))

        assertEquals(emptySet<String>(), changedMonthIds(before, after))
    }

    @Test
    fun `a month that disappeared is not a change either`() {
        val before = listOf(year(2026, 10 to 15, 11 to 4))
        val after = listOf(year(2026, 10 to 15))

        assertEquals(emptySet<String>(), changedMonthIds(before, after))
    }

    @Test
    fun `several months can change at once`() {
        val before = listOf(year(2026, 10 to 15, 9 to 90), year(2025, 12 to 93))
        val after = listOf(year(2026, 10 to 20, 9 to 90), year(2025, 12 to 94))

        assertEquals(setOf("2026-10", "2025-12"), changedMonthIds(before, after))
    }
}
