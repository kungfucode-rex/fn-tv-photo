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
 * The tracks travel inside the APK rather than being read from the NAS: a slideshow's
 * soundtrack has to start the instant the show does, and a TV that had to authenticate,
 * download and buffer a track from the NAS first would put silence at the front of every
 * show. It also means the list is a fixed, known catalogue — the setting is stored as a
 * selection *from* this list, so a track that is renamed or dropped cannot leave a
 * dangling preference behind.
 *
 * Four tracks, and they are the ones that keep a room's attention on a wall of pictures:
 * the catalogue used to carry nine, of which the three largest were slow instrumental
 * pieces — 35 MB of the 66 MB package, for music that a slideshow's own pace works
 * against. The files are gone from `assets/music` rather than merely unlisted, because a
 * track nobody can select is still a track every install pays for.
 */
val SLIDESHOW_MUSIC: List<MusicTrack> = listOf(
    MusicTrack(
        asset = "music/yuehui-kikujiro-piano.mp3",
        title = "悦荟 - 菊次郎的夏天(钢琴版)",
    ),
    MusicTrack(
        asset = "music/thomas-greenberg-easy-breeze.mp3",
        title = "Thomas Greenberg - Easy Breeze",
    ),
    MusicTrack(
        asset = "music/tangyi-moon-over-mountains-live.mp3",
        title = "唐艺 - 月亮照山川 (Live)",
    ),
    MusicTrack(
        asset = "music/yingzhefeng-xiangqian-chong.mp3",
        title = "迎着风 向前冲 (万妖图录传)",
    ),
)

/**
 * Reduces a selection to what can actually be played: tracks the catalogue knows,
 * de-duplicated, in catalogue order.
 *
 * Catalogue order rather than tick order, because the tracks loop in the order they are
 * played and a tick-ordered list would reshuffle the show every time somebody re-picked
 * the same two tracks. Unknown paths are dropped rather than kept: they can only come
 * from a version of the app that shipped different music.
 */
fun normalizeMusicSelection(assets: Collection<String>): List<String> {
    val wanted = assets.toSet()
    return SLIDESHOW_MUSIC.map { it.asset }.filter { it in wanted }
}

/**
 * Serialises the selection for `SharedPreferences`.
 *
 * One asset path per line: the values are ASCII paths the app itself wrote, so there is
 * nothing for a JSON layer to escape, and a stored value stays readable in a bug report.
 */
internal fun encodeMusicSelection(assets: Collection<String>): String =
    normalizeMusicSelection(assets).joinToString("\n")

/** Reads back what [encodeMusicSelection] wrote; anything unplayable is dropped. */
internal fun decodeMusicSelection(raw: String?): List<String> =
    normalizeMusicSelection(raw.orEmpty().lineSequence().filter { it.isNotBlank() }.toList())
