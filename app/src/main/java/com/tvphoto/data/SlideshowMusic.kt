package com.tvphoto.data

/** One piece of music that can play under a slideshow. */
data class MusicTrack(
    /** Path inside the APK's `assets`, which is also how the selection is stored. */
    val asset: String,
    /** What the list shows. Song titles are proper nouns and are not translated. */
    val title: String,
)

/**
 * The music that ships with the app, in the order it is offered and played.
 *
 * **One track, since 1.5.2.** The catalogue used to carry four because a slideshow's
 * soundtrack had to be there before the user had done anything; now that a phone can put
 * music on the television in ten seconds, four commercial recordings shipped inside every
 * APK are 10.7 MB that most installs never select — and the one thing the package still has
 * to guarantee is that a *fresh* install has something to play. One track does that.
 *
 * The track travels inside the APK rather than being read from the NAS for the original
 * reason, which has not changed: a soundtrack has to start the instant the show does, and a
 * television that had to authenticate, download and buffer a track from the NAS first would
 * put silence at the front of every show.
 *
 * The file is deleted rather than merely unlisted when a track goes, because a track nobody
 * can select is still a track every install pays for. `SlideshowMusicTest` enforces the same
 * rule in both directions: every name here is a file in `assets/music`, and every file in
 * `assets/music` is named here.
 *
 * Keeping one also settles the licensing note this project has carried since 1.2: one
 * commercial recording is redistributed instead of four, and everything else a user hears is
 * music they chose and supplied.
 */
val SLIDESHOW_MUSIC: List<MusicTrack> = listOf(
    MusicTrack(
        asset = "music/yuehui-kikujiro-piano.mp3",
        title = "悦荟 - 菊次郎的夏天(钢琴版)",
    ),
)

/**
 * Marks a track that lives outside the APK — an uploaded file or a referenced one.
 *
 * The prefix rather than a bare path is what makes one stored list able to hold both
 * kinds: `music/easy-breeze.mp3` is an asset the package carries, `file:/…/月光.mp3` is a
 * file on the television. Without it, a stored selection could not be told apart from a
 * catalogue that had been renamed, and the drop-unknown rule below would delete every
 * custom track the moment the app started.
 */
const val MUSIC_FILE_PREFIX = "file:"

/** The stored id of a track on disk. */
fun customMusicId(path: String): String = MUSIC_FILE_PREFIX + path

/** The file behind a stored id, or null when the id names an asset. */
fun customMusicPath(id: String): String? = id
    .takeIf { it.startsWith(MUSIC_FILE_PREFIX) }
    ?.removePrefix(MUSIC_FILE_PREFIX)
    ?.takeIf { it.isNotBlank() }

/** Whether [id] names a file on the television rather than a track in the APK. */
fun isCustomMusic(id: String): Boolean = id.startsWith(MUSIC_FILE_PREFIX)

/**
 * Reduces a selection to what can actually be played: the custom tracks that still exist,
 * in the order [customIds] gives them, then the tracks the catalogue knows, in catalogue
 * order, de-duplicated.
 *
 * Position is one order for two things — the list the upload page draws and the order the
 * playlist plays — so the user's own music comes first and carries the row numbers 1..N,
 * and the bundled four sit under it keeping the catalogue's order. A hidden second order
 * ("bundled first when playing") would make the numbers on that page a lie.
 *
 * Within each half the ordering rule is "the caller's order, not the stored one": for the
 * custom half that is the library's arrangement ([MusicLibrary], which is the only thing
 * that knows what is on disk), and for the bundled half it is the catalogue's, because a
 * tick-ordered list would reshuffle the show every time somebody re-picked the same two
 * tracks.
 *
 * Unknown paths are dropped rather than kept: for the bundled half they can only come from
 * a version of the app that shipped different music, and for the custom half from a file
 * that has been deleted — which is exactly what "it is no longer in [customIds]" means.
 * [customIds] is therefore the caller's answer to "which files are there, and in what
 * order", and a caller that has not looked (storage reads `decodeMusicSelection`) passes the
 * stored ids themselves, which keeps their order and drops only what could never play.
 */
fun normalizeMusicSelection(
    assets: Collection<String>,
    customIds: Collection<String> = emptyList(),
): List<String> {
    val wanted = assets.toSet()
    val seen = HashSet<String>()
    val custom = ArrayList<String>()
    for (id in customIds) {
        if (id !in wanted || customMusicPath(id) == null) continue
        if (seen.add(id)) custom += id
    }
    val builtin = SLIDESHOW_MUSIC.map { it.asset }.filter { it in wanted && it !in seen }
    return custom + builtin
}

/**
 * Serialises the selection for `SharedPreferences`.
 *
 * One path per line: the values are paths the app itself wrote — ASCII asset names, or
 * absolute file paths that may be any language — so there is nothing for a JSON layer to
 * escape, a newline can never appear inside one, and a stored value stays readable in a
 * bug report. [normalizeMusicSelection] is deliberately *not* given the file system here:
 * this is the storage layer, and a preference read at start-up must not depend on an
 * unmounted volume to be correct (see [MusicLibrary], which prunes against the files it
 * can actually see).
 */
internal fun encodeMusicSelection(assets: Collection<String>): String =
    normalizeMusicSelection(assets, assets).joinToString("\n")

/** Reads back what [encodeMusicSelection] wrote; anything that could never play is dropped. */
internal fun decodeMusicSelection(raw: String?): List<String> {
    val stored = raw.orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
    return normalizeMusicSelection(stored, stored)
}
