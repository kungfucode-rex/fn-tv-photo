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
}
