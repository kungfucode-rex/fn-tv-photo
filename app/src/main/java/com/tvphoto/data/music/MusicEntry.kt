package com.tvphoto.data.music

/**
 * Where a track's bytes come from.
 *
 * Two cases, and the difference is exactly one thing: what 删除 is allowed to do. An upload
 * is a copy this app made and may delete; the bundled track is inside the APK, so deleting
 * it can only mean "take it out of my list", recorded as such and reversible.
 *
 * This used to have a third case — a file on the television or a USB stick, played where it
 * lay — and the scan that found those files is gone (see [MusicLibrary]).
 */
enum class MusicSource {
    /** Carried inside the APK, in `assets/music`. */
    BUILTIN,

    /** A copy in the library directory: uploaded from a phone, or copied in by cable. */
    UPLOAD,
}

/**
 * One track the slideshow can play.
 *
 * [id] is both the identity and the address: an asset path for the bundled track, and
 * `file:` + an absolute path for an upload. That is what lets one stored selection list hold
 * both, and what makes a track's identity survive the list being rebuilt from the directory.
 */
data class MusicEntry(
    val id: String,
    val title: String,
    val source: MusicSource,
    /** Unknown duration is 0, which is also what a failed probe leaves behind. */
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
)

/**
 * The title to show when nothing better is known: the file's own name without its
 * extension.
 *
 * Underscores are left alone. A file called `my_song.mp3` is named that by the person who
 * made it, and rewriting it to "my song" would be the app editing a name it was only ever
 * asked to display.
 */
fun musicTitleFromFileName(name: String): String {
    val trimmed = name.substringBeforeLast('.', name).trim()
    return trimmed.ifBlank { name }.take(MAX_TITLE_CHARS)
}

/**
 * The library's own order: by title, case-insensitively, with the id as the tie-break.
 *
 * By title rather than by the order files happened to be uploaded, because the list is
 * shown to a person and a playlist that reshuffles when an unrelated track is added is one
 * they cannot learn. The id breaks ties so two files of the same name keep one stable
 * order between launches.
 *
 * This is now only the *fallback* order: a track the user has not placed — a file dropped
 * in with a cable, or one a scan just found — sorts here, after everything the stored order
 * names (see [orderedByStoredOrder]).
 */
fun sortedByTitle(entries: List<MusicEntry>): List<MusicEntry> =
    entries.sortedWith(compareBy({ it.title.lowercase() }, { it.id }))

/**
 * Puts the tracks in the order the user arranged, then whatever the order has not named yet,
 * in the order it was given.
 *
 * The stored order is a list of ids and is allowed to be wrong in both directions: it can
 * name a track that is no longer there (a deleted file, a bundled track that has been
 * removed from the list), and it can be missing one that is (an upload that happened before
 * this order existed, or a file copied in by hand). Both are normal, so neither is an error
 * — the first is skipped and the second is appended, keeping the caller's own order for
 * them: the caller is the one that knows that a bundled track belongs in catalogue order
 * and a user's upload does not.
 */
fun orderedByStoredOrder(entries: List<MusicEntry>, order: List<String>): List<MusicEntry> {
    val byId = entries.associateBy { it.id }
    val placed = ArrayList<MusicEntry>(entries.size)
    val seen = HashSet<String>()
    for (id in order) {
        val entry = byId[id] ?: continue
        if (seen.add(id)) placed += entry
    }
    return placed + entries.filterNot { it.id in seen }
}

/**
 * The order after moving [id] by [delta] places, or the same order when it cannot move.
 *
 * A pure list operation, so the reorder mode's rule (clamped at both ends rather than
 * wrapping, because the top and bottom of a list are where "move up" has to stop being
 * able to do anything) is pinned by a test instead of by watching a television.
 */
fun movedWithinOrder(order: List<String>, id: String, delta: Int): List<String> {
    val current = order.toMutableList()
    if (id !in current) {
        if (delta == 0) return current
        current += id
    }
    val from = current.indexOf(id)
    val to = (from + delta).coerceIn(0, current.lastIndex)
    if (from == to) return current
    current.removeAt(from)
    current.add(to, id)
    return current
}

/** Longest title the app will invent from a filename; far past any TV row's width. */
private const val MAX_TITLE_CHARS = 80
