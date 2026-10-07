package com.tvphoto.data

import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps the last uncaught exception where the app can show it.
 *
 * A TV has no console: when this app dies on a device, the only evidence is that it
 * vanished, and the next launch starts from scratch. The one report a user can give is
 * "it closed itself", which is not enough to act on. So the crash is written to a file
 * the app owns and surfaced in Settings, where someone with a remote can read the first
 * line of it back.
 *
 * It deliberately does nothing clever: no uploading, no restarting, no swallowing. The
 * previous handler still runs, so the crash still ends the process the way Android
 * intends.
 *
 * The directory is injected rather than taken from a `Context` so that this can be
 * tested on the JVM like any other piece of logic — the one code path nobody wants to
 * exercise by hand is the one that only runs when something has already gone wrong.
 */
class CrashLog(private val directory: File) {

    private val file: File get() = File(directory, FILE_NAME)

    /** Installs the recorder, keeping whatever handler was there before. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Writing must never turn one crash into a different one.
            runCatching { record(thread, error) }
                .onFailure { Log.w(TAG, "could not record crash", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * A one-line description of the last crash, or null when there has not been one.
     *
     * Short on purpose: it is shown on a television, to someone reading it aloud.
     */
    fun summary(): String? {
        val lines = runCatching { file.readLines() }.getOrNull() ?: return null
        val meaningful = lines.filter { it.isNotBlank() }
        if (meaningful.isEmpty()) return null
        return meaningful.take(2).joinToString(" · ").take(MAX_SUMMARY)
    }

    /** Where the full trace lives, for anyone who can pull the file off the device. */
    fun path(): String = file.absolutePath

    private fun record(thread: Thread, error: Throwable) {
        val stamp = SimpleDateFormat(TIMESTAMP, Locale.US).format(Date())
        val trace = error.stackTraceToString()
            .lineSequence()
            .take(MAX_TRACE_LINES)
            .joinToString("\n")
        directory.mkdirs()
        file.writeText("$stamp · ${thread.name}\n$trace\n")
    }

    private companion object {
        const val TAG = "CrashLog"
        const val FILE_NAME = "last-crash.txt"
        const val TIMESTAMP = "yyyy-MM-dd HH:mm:ss"
        const val MAX_SUMMARY = 220
        const val MAX_TRACE_LINES = 40
    }
}
