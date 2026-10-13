package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How big the image cache is allowed to be on the box the app is running on.
 *
 * This is the decision that a television spent 50 GB in two days on, and it is invisible
 * from every angle except this one: the settings row shows what the cache *holds*, and a
 * cache built larger than the volume could spare is deleted by Android rather than
 * trimmed — after which every pass over an album is a fresh download. The numbers below are
 * the whole contract, so they are asserted rather than computed from the constants they
 * gate.
 */
class ImageCacheSizeTest {

    private val mib = 1024L * 1024L
    private val gib = 1024L * mib

    @Test
    fun `a box with room gets the product ceiling`() {
        assertEquals(5L * gib, imageCacheBudgetBytes(freeSpaceBytes = 30L * gib))
        assertEquals(5L * gib, imageCacheBudgetBytes(freeSpaceBytes = 500L * gib))
    }

    /**
     * The case the feature exists for: a quarter of what is free, not the whole 5 GB. A
     * box with 2 GB free is exactly the "电视盒子的内部存储常常只剩一两 GB" case.
     */
    @Test
    fun `a box with little room gets a quarter of it`() {
        assertEquals(512L * mib, imageCacheBudgetBytes(freeSpaceBytes = 2L * gib))
        assertEquals(256L * mib, imageCacheBudgetBytes(freeSpaceBytes = 1L * gib))
        assertEquals(64L * mib, imageCacheBudgetBytes(freeSpaceBytes = 256L * mib))
    }

    /** Exactly where the ceiling takes over: 20 GB free is the last quarter that is 5 GB. */
    @Test
    fun `the ceiling takes over at four times itself`() {
        assertEquals(5L * gib, imageCacheBudgetBytes(freeSpaceBytes = 20L * gib))
        assertEquals(5L * gib - 1L, imageCacheBudgetBytes(freeSpaceBytes = 20L * gib - 4L))
    }

    /**
     * A nearly full box gets the floor rather than a cache it cannot keep — and never a
     * larger share of what is left than the fraction says.
     */
    @Test
    fun `a nearly full box gets the floor`() {
        assertEquals(IMAGE_CACHE_FLOOR_BYTES, imageCacheBudgetBytes(freeSpaceBytes = 100L * mib))
        assertEquals(IMAGE_CACHE_FLOOR_BYTES, imageCacheBudgetBytes(freeSpaceBytes = 1L))
    }

    /**
     * A platform that cannot answer must not be read as "the whole disk is free": the
     * floor is the safe direction, because the other one is the bug being fixed.
     */
    @Test
    fun `an unreadable free space is the floor, not the ceiling`() {
        assertEquals(IMAGE_CACHE_FLOOR_BYTES, imageCacheBudgetBytes(freeSpaceBytes = 0L))
        assertEquals(IMAGE_CACHE_FLOOR_BYTES, imageCacheBudgetBytes(freeSpaceBytes = -1L))
        assertTrue(imageCacheBudgetBytes(freeSpaceBytes = 0L) < IMAGE_CACHE_CEILING_BYTES)
    }

    /**
     * The rule, over the whole range that matters: never above the ceiling, never above a
     * quarter of what is free, never below the floor. The middle one is the promise the
     * fixed 5 GB broke.
     */
    @Test
    fun `the budget is always within the ceiling, the floor and a quarter of the free space`() {
        for (free in 0L..40L * gib step 7L * mib) {
            val budget = imageCacheBudgetBytes(freeSpaceBytes = free)
            assertTrue("budget $budget over the ceiling at $free", budget <= IMAGE_CACHE_CEILING_BYTES)
            assertTrue("budget $budget under the floor at $free", budget >= IMAGE_CACHE_FLOOR_BYTES)
            if (free > 0) {
                assertTrue(
                    "budget $budget took more than a quarter of $free",
                    budget <= maxOf(IMAGE_CACHE_FLOOR_BYTES, free / 4 + 1),
                )
            }
        }
    }
}
