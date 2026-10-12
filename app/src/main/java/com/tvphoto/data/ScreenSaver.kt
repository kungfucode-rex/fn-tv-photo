package com.tvphoto.data

/**
 * The 屏保相册: an album this television starts playing by itself when nobody has touched
 * the remote for a while.
 *
 * What lives here is what the feature decides that is worth pinning in a test — how long
 * "a while" is, and whether a given album is the one that plays. The playing itself is the
 * viewer's: see `Screen.ScreenSaverAlbum` for why it draws that screen rather than a second
 * slideshow of its own, and the 屏保相册 row in 设置 → 通用 for where the album is chosen.
 */

/**
 * How long the app may sit untouched before the screensaver album starts playing.
 *
 * Five minutes is the point where a television showing a still menu has stopped being a menu
 * and started being a light in the room. Short enough that nobody reaches for the remote to
 * make it go away, long enough that it cannot interrupt somebody reading a grid of covers.
 *
 * A debug build overrides this value — see `BuildConfig.SCREEN_SAVER_IDLE_MILLIS`. Nothing
 * else about the feature changes with the build type, deliberately: what the emulator
 * verifies has to be the behaviour a television gets, or the verification proves nothing.
 */
const val SCREEN_SAVER_IDLE_MILLIS = 5 * 60 * 1000L

/**
 * Whether [albumId] is the screensaver album.
 *
 * The ids arrive from the NAS as numbers while the preference is stored as a string, so the
 * comparison is made in one spelling and this is the only place that decides which. Null and
 * blank both mean "none set", which is the default and also what a user who has cleared the
 * setting has: a card must not read either as "the album with an empty id".
 */
fun isScreenSaverAlbum(albumId: Long, screenSaverAlbumId: String?): Boolean =
    !screenSaverAlbumId.isNullOrBlank() && screenSaverAlbumId == albumId.toString()
