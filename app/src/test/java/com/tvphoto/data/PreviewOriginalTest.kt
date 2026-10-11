package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The preview setting: what each option means while paging by hand and during a
 * slideshow, that the default is the slideshow-only one, and that the stored id survives
 * the round trip so an install keeps its answer across a restart.
 */
class PreviewOriginalTest {

    @Test
    fun `the default is the original for the slideshow and not for paging by hand`() {
        assertEquals(PreviewOriginal.SLIDESHOW_ONLY, PreviewOriginal.DEFAULT)
        assertFalse(PreviewOriginal.DEFAULT.wantsOriginal(slideshowRunning = false))
        assertTrue(PreviewOriginal.DEFAULT.wantsOriginal(slideshowRunning = true))
    }

    @Test
    fun `yes and no are the same answer whatever the slideshow is doing`() {
        assertTrue(PreviewOriginal.ALWAYS.wantsOriginal(slideshowRunning = false))
        assertTrue(PreviewOriginal.ALWAYS.wantsOriginal(slideshowRunning = true))
        assertFalse(PreviewOriginal.NEVER.wantsOriginal(slideshowRunning = false))
        assertFalse(PreviewOriginal.NEVER.wantsOriginal(slideshowRunning = true))
    }

    @Test
    fun `pressing OK again and again reaches every option and comes back round`() {
        val cycle = generateSequence(PreviewOriginal.DEFAULT) { it.next() }.take(4).toList()

        assertEquals(PreviewOriginal.entries.toSet(), cycle.take(3).toSet())
        assertEquals(PreviewOriginal.DEFAULT, cycle[3])
    }

    @Test
    fun `the stored id round trips and an unknown one is not an option`() {
        PreviewOriginal.entries.forEach { assertEquals(it, PreviewOriginal.fromId(it.id)) }
        assertNull(PreviewOriginal.fromId("nonsense"))
        assertNull(PreviewOriginal.fromId(null))
    }

    /**
     * The cached original is the one thing that outvotes the setting, and only where the
     * setting was about *waiting*: 仅用于幻灯片播放 prefers the thumbnail because paging by
     * hand should be instant, and a file that is already on disk is instant.
     *
     * 否 is not about waiting — it says every preview is the large thumbnail, and it is also
     * how somebody keeps a 12-megapixel decode off a box that cannot afford one — so a cache
     * hit must not quietly overrule it.
     */
    @Test
    fun `a cached original wins while paging by hand, but never against 否`() {
        assertTrue(
            PreviewOriginal.SLIDESHOW_ONLY.wantsOriginal(slideshowRunning = false, originalCached = true),
        )
        assertFalse(
            PreviewOriginal.SLIDESHOW_ONLY.wantsOriginal(slideshowRunning = false, originalCached = false),
        )
        assertTrue(
            PreviewOriginal.SLIDESHOW_ONLY.wantsOriginal(slideshowRunning = true, originalCached = false),
        )
        assertFalse(
            PreviewOriginal.NEVER.wantsOriginal(slideshowRunning = false, originalCached = true),
        )
        assertFalse(
            PreviewOriginal.NEVER.wantsOriginal(slideshowRunning = true, originalCached = true),
        )
        assertTrue(
            PreviewOriginal.ALWAYS.wantsOriginal(slideshowRunning = false, originalCached = false),
        )
    }

    @Test
    fun `the cached argument defaults to no, so every existing caller means what it meant`() {
        PreviewOriginal.entries.forEach { mode ->
            assertEquals(
                mode.wantsOriginal(slideshowRunning = false),
                mode.wantsOriginal(slideshowRunning = false, originalCached = false),
            )
        }
    }
}
