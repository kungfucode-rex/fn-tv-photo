package com.tvphoto.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `getList` silently drops everything after `end_time`, so the end of a month has to
 * be the last second of its real last day. Getting this wrong loses photos rather
 * than erroring, which is why it is pinned here.
 */
class TimelineMonthTest {

    @Test
    fun `start of month is the first second`() {
        assertEquals("2026:10:01 00:00:00", TimelineMonth(2026, 10, 0).startParam)
        assertEquals("2026:01:01 00:00:00", TimelineMonth(2026, 1, 0).startParam)
    }

    @Test
    fun `end of month uses the real month length`() {
        assertEquals("2026:01:31 23:59:59", TimelineMonth(2026, 1, 0).endParam)
        assertEquals("2026:04:30 23:59:59", TimelineMonth(2026, 4, 0).endParam)
        assertEquals("2026:12:31 23:59:59", TimelineMonth(2026, 12, 0).endParam)
    }

    @Test
    fun `february accounts for leap years`() {
        assertEquals("2026:02:28 23:59:59", TimelineMonth(2026, 2, 0).endParam)
        assertEquals("2024:02:29 23:59:59", TimelineMonth(2024, 2, 0).endParam)
        assertEquals("2000:02:29 23:59:59", TimelineMonth(2000, 2, 0).endParam)
        assertEquals("1900:02:28 23:59:59", TimelineMonth(1900, 2, 0).endParam)
    }

    @Test
    fun `month numbers are zero padded for the api`() {
        assertEquals("2026:01:01 00:00:00", TimelineMonth(2026, 1, 0).startParam)
        assertEquals("2026-01", TimelineMonth(2026, 1, 0).id)
        assertEquals("2026-10", TimelineMonth(2026, 10, 0).id)
    }
}
