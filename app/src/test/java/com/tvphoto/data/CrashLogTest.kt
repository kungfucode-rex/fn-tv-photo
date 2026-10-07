package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The recorder only ever runs after something has already gone wrong, which is the one
 * moment it cannot be checked by hand on a TV. So it is checked here instead: that the
 * trace survives, that the summary is short enough to read off a screen, and that
 * having a recorder installed does not change what a crash does.
 */
class CrashLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `nothing recorded reads back as no crash`() {
        assertNull(CrashLog(folder.root).summary())
    }

    @Test
    fun `a crash on another thread is recorded and rethrown to the previous handler`() {
        val log = CrashLog(folder.root)
        var forwarded: Throwable? = null
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            // Whatever handler the platform had installed, the app must not take its
            // place: the process still has to die the way Android expects.
            Thread.setDefaultUncaughtExceptionHandler { _, error -> forwarded = error }

            log.install()
            val error = IllegalStateException("start-up exploded")
            val thread = Thread.currentThread()
            Thread.getDefaultUncaughtExceptionHandler().uncaughtException(thread, error)

            assertEquals(error, forwarded)

            val summary = log.summary()
            assertTrue("summary was $summary", summary!!.contains("IllegalStateException"))
            assertTrue(summary.contains("start-up exploded"))
            assertTrue(summary.length <= 220)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `the full trace is kept, not just its first line`() {
        val log = CrashLog(folder.root)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            log.install()
            Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), IllegalStateException("boom"))

            val text = File(folder.root, "last-crash.txt").readText()
            assertTrue(text.contains("boom"))
            // A stack frame, so the report says where as well as what.
            assertTrue(text.contains("CrashLogTest"))
            assertTrue(text.contains("\n"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `the newest crash replaces the last one`() {
        val log = CrashLog(folder.root)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
            log.install()

            Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), IllegalStateException("first"))
            Thread.getDefaultUncaughtExceptionHandler()
                .uncaughtException(Thread.currentThread(), IllegalArgumentException("second"))

            val summary = log.summary()!!
            assertTrue(summary.contains("second"))
            assertTrue(!summary.contains("first"))
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
