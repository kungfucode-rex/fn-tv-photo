package com.tvphoto.data.music

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.StatFs
import com.tvphoto.data.SLIDESHOW_MUSIC
import com.tvphoto.data.SettingsStore
import com.tvphoto.data.customMusicId
import com.tvphoto.data.customMusicPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * The music the slideshow can play: the track inside the APK, and everything the phone sent.
 *
 * One directory is the whole of it. Every way music gets in — the upload page, a file
 * manager, `adb push` — ends with a file in that one place, so "why is it not in the list"
 * has exactly one answer, and the list is rebuilt by reading it rather than by keeping a
 * database in step with it.
 *
 * There was a second source until 1.5.3 — files already on the television or a USB stick,
 * found by walking `Music`, `Download` and every mounted volume and kept as *references*.
 * It is gone: pointing a television remote at a USB stick turned out to be something nobody
 * does, while its costs were real (a permission prompt, a scan-and-hope, a whole class of
 * rows whose file had been unplugged, and a distinction between "our copy" and "your file"
 * that the user never asked for). Nothing is lost by its removal — a file copied into the
 * library directory with a cable is picked up exactly like an upload.
 *
 * [state] is recomputed by [refresh] rather than mutated in place: every entry's title and
 * duration comes from reading the file, and a list assembled from a dozen partial reads
 * would be a list that disagrees with itself.
 */
class MusicLibrary(context: Context, private val settings: SettingsStore) {

    private val appContext: Context = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(MusicLibraryState())

    val state: StateFlow<MusicLibraryState> = _state.asStateFlow()

    /**
     * Read titles and durations per file, kept for this process's lifetime.
     *
     * Keyed by path *and* size *and* last-modified, so replacing a file under the same
     * name is picked up while scrolling the list is not a probe per row:
     * `MediaMetadataRetriever` opens and parses every one of these files, which is tens of
     * milliseconds on a television.
     */
    private val probes = HashMap<String, Probe>()

    /** The directory music lives in: app-specific external storage, internal as a fallback. */
    fun directory(): File {
        val base = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        return File(base, DIRECTORY_NAME)
    }

    /** Recomputes [state]; safe to call from anywhere, work happens on an IO thread. */
    fun refresh() {
        scope.launch { _state.value = readState() }
    }

    /** Adds one uploaded file. Called by the upload server, never by the UI directly. */
    fun addUpload(bytes: ByteArray, rawName: String): MusicAddResult {
        if (bytes.size > MusicUploadProtocol.MAX_UPLOAD_BYTES) return MusicAddResult.TooLarge

        val dir = directory()
        dir.mkdirs()
        val taken = dir.listFiles().orEmpty().mapTo(HashSet()) { it.name }
        val name = MusicUploadProtocol.sanitizeFileName(rawName, taken) ?: return MusicAddResult.NotAudio

        val used = usedBytes(dir)
        if (used + bytes.size > MusicUploadProtocol.LIBRARY_LIMIT_BYTES) return MusicAddResult.LibraryFull
        if (freeBytes(dir) < bytes.size + FREE_SPACE_MARGIN_BYTES) return MusicAddResult.NoSpace

        val target = File(dir, name)
        // Written beside the real name and renamed into place, so a connection that drops
        // halfway through leaves no half a song in the library — the list reads the
        // directory, and a `.part` file is not in it.
        val part = File(dir, "$name.part")
        return try {
            part.writeBytes(bytes)
            if (!part.renameTo(target)) {
                part.delete()
                return MusicAddResult.Failed("rename failed")
            }
            // The list and the size row are both read from [state], and a file that has just
            // landed is not in it yet. This is the one write into the directory that happens
            // while somebody is looking at the screen that shows it.
            //
            // It also goes to the *top* of the list: that is where somebody who has just
            // uploaded a song looks for it, and the page flashes its border so the answer to
            // "did it arrive" is on screen rather than inferred from a count.
            settings.musicOrder = listOf(customMusicId(target.absolutePath)) + settings.musicOrder
            refresh()
            MusicAddResult.Added(entryFor(target))
        } catch (error: Exception) {
            part.delete()
            MusicAddResult.Failed(error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Removes one track from the list.
     *
     * For an upload that means the file is deleted. For the bundled track it cannot: its
     * bytes are inside the APK and read-only, so 删除 means "not in my list any more" and is
     * recorded as such — see [restoreBuiltins] for the way back, which is why this is the
     * only kind of delete that can be undone.
     */
    fun delete(entry: MusicEntry) {
        when (entry.source) {
            MusicSource.UPLOAD -> customMusicPath(entry.id)?.let { File(it).delete() }
            MusicSource.BUILTIN -> settings.musicRemovedBuiltins =
                (settings.musicRemovedBuiltins + entry.id).distinct()
        }
        settings.musicOrder = settings.musicOrder.filterNot { it == entry.id }
        refresh()
    }

    /** Puts the bundled tracks the user deleted back in the list, at the end. */
    fun restoreBuiltins() {
        val restored = settings.musicRemovedBuiltins
        settings.musicRemovedBuiltins = emptyList()
        settings.musicOrder = settings.musicOrder + restored
        refresh()
    }

    /** Deletes every upload. The bundled track is untouched. */
    fun clearUploads() {
        val files = directory().listFiles().orEmpty()
        val removed = files.map { customMusicId(it.absolutePath) }.toSet()
        files.forEach { it.delete() }
        settings.musicOrder = settings.musicOrder.filterNot { it in removed }
        refresh()
    }

    /**
     * Moves one track by [delta] places and stores the new order.
     *
     * Every row moves, the bundled one included: the order is the playlist's, and a list
     * where one of the rows cannot be arranged is a list the user cannot actually arrange.
     */
    fun moveTrack(id: String, delta: Int) {
        if (delta == 0) return
        val tracks = _state.value.entries
        if (tracks.none { it.id == id }) return
        settings.musicOrder = movedWithinOrder(tracks.map { it.id }, id, delta)
        refresh()
    }

    private fun readState(): MusicLibraryState {
        val dir = directory()
        dir.mkdirs()

        val removed = settings.musicRemovedBuiltins.toSet()
        // One list of tracks, one order. A bundled track is a row here like any other — the
        // only difference left is where its bytes live, which is what decides what 删除 can
        // do to it (nothing, for that one) and nothing else.
        val builtin = SLIDESHOW_MUSIC
            .filterNot { it.asset in removed }
            .map { track ->
                MusicEntry(id = track.asset, title = track.title, source = MusicSource.BUILTIN)
            }

        val uploads = dir.listFiles().orEmpty()
            .filter { it.isFile && MusicUploadProtocol.isAudioFileName(it.name) }
            .map { entryFor(it) }
            .let { sortedByTitle(it) }

        // The fallback order — what a row the stored order does not name yet sorts by — is
        // this list's own order: uploads by title, then the bundled catalogue.
        val all = orderedByStoredOrder(uploads + builtin, settings.musicOrder)
        return MusicLibraryState(
            entries = all,
            usedBytes = usedBytes(dir),
            directory = dir.absolutePath,
            builtinCount = builtin.size,
            uploadCount = uploads.size,
            removedBuiltins = settings.musicRemovedBuiltins.size,
        )
    }

    private fun entryFor(file: File): MusicEntry {
        val probe = probe(file.absolutePath, file.length(), file.lastModified())
        return MusicEntry(
            id = customMusicId(file.absolutePath),
            title = probe.title,
            source = MusicSource.UPLOAD,
            durationMs = probe.durationMs,
            sizeBytes = file.length(),
        )
    }

    /** Reads a file's own title and duration, falling back to its name. Never throws. */
    private fun probe(path: String, size: Long, modified: Long): Probe {
        val key = "$path|$size|$modified"
        probes[key]?.let { return it }

        val fallback = Probe(title = musicTitleFromFileName(File(path).name), durationMs = 0L)
        val result = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(path)
                val title = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                    ?.trim()
                    .orEmpty()
                val duration = retriever
                    .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?: 0L
                Probe(
                    title = title.ifBlank { fallback.title }.take(120),
                    durationMs = duration,
                )
            } finally {
                runCatching { retriever.release() }
            }
        }.getOrDefault(fallback)

        probes[key] = result
        return result
    }

    private fun usedBytes(dir: File): Long =
        dir.listFiles().orEmpty().sumOf { if (it.isFile) it.length() else 0L }

    private fun freeBytes(dir: File): Long = runCatching {
        val stat = StatFs(dir.absolutePath)
        stat.availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private data class Probe(val title: String, val durationMs: Long)

    private companion object {
        const val DIRECTORY_NAME = "music"

        /** Kept free after an upload so a full volume does not break the app's own writes. */
        const val FREE_SPACE_MARGIN_BYTES = 8L * 1024 * 1024
    }
}

/** One reading of the library, as the screens see it. */
data class MusicLibraryState(
    val entries: List<MusicEntry> = emptyList(),
    val usedBytes: Long = 0L,
    val directory: String = "",
    val builtinCount: Int = 0,
    /** How many tracks are copies this app owns: the ones 清空我的音乐 would delete. */
    val uploadCount: Int = 0,
    /** Bundled tracks the user has deleted, which [MusicLibrary.restoreBuiltins] brings back. */
    val removedBuiltins: Int = 0,
) {
    /**
     * The uploaded tracks that can be played right now, in list order.
     *
     * The bundled track is not in here: it cannot go missing, so a stored selection naming it
     * is always valid, and the caller uses this list to find the *custom* half of a selection
     * to prune. See `normalizeMusicSelection`.
     */
    val availableUploadIds: List<String>
        get() = entries.filter { it.source == MusicSource.UPLOAD }.map { it.id }
}

/** What happened to one uploaded file. */
sealed interface MusicAddResult {
    data class Added(val entry: MusicEntry) : MusicAddResult

    data object NotAudio : MusicAddResult

    data object TooLarge : MusicAddResult

    data object LibraryFull : MusicAddResult

    data object NoSpace : MusicAddResult

    data class Failed(val reason: String) : MusicAddResult
}
