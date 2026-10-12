package com.tvphoto.data

import android.content.Context
import com.tvphoto.data.fn.CertificatePinStore
import com.tvphoto.data.fn.SignMode
import com.tvphoto.data.fn.SignModeStore
import com.tvphoto.data.music.decodeMusicOrder
import com.tvphoto.data.music.encodeMusicOrder
import java.util.Locale

/**
 * Small preference store for connection settings.
 *
 * The password is kept alongside the host because the fnOS `AccessToken` has no
 * documented refresh flow — every client re-runs the full encrypted login when the
 * token is rejected, so the credentials have to survive a restart.
 */
class SettingsStore(context: Context) : SignModeStore, CertificatePinStore {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var host: String
        get() = prefs.getString(KEY_HOST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_HOST, value).apply()

    var userName: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    /**
     * Optional 访问码. fnOS can gate the whole login entry behind one
     * (https://help.fnnas.com/articles/v1/safety/access-code), in which case the
     * WebSocket upgrade is refused until the device presents it.
     */
    var accessCode: String
        get() = prefs.getString(KEY_ACCESS_CODE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_ACCESS_CODE, value).apply()

    /**
     * Dark or light. Persisted like the slideshow settings: a TV in a bright room is
     * set up once, and having it revert on every launch would be the whole feature
     * failing quietly.
     */
    var themeMode: ThemeMode
        get() = ThemeMode.fromId(prefs.getString(KEY_THEME, null)) ?: ThemeMode.DEFAULT
        set(value) = prefs.edit().putString(KEY_THEME, value.id).apply()

    /** Seconds each photo stays on screen while the slideshow runs. */
    var slideshowSeconds: Int
        get() = prefs.getInt(KEY_SLIDESHOW, DEFAULT_SLIDESHOW_SECONDS).coerceIn(2, 120)
        set(value) = prefs.edit().putInt(KEY_SLIDESHOW, value.coerceIn(2, 120)).apply()

    /**
     * Whether full-screen previews draw the original file or the large thumbnail.
     *
     * Persisted like the interval: this is a decision about the link to the NAS — a LAN
     * or a connection over the internet — and it has to survive a restart, because the
     * first preview after a restart is the one it exists for.
     */
    var previewOriginal: PreviewOriginal
        get() = PreviewOriginal.fromId(prefs.getString(KEY_PREVIEW_ORIGINAL, null))
            ?: PreviewOriginal.DEFAULT
        set(value) = prefs.edit().putString(KEY_PREVIEW_ORIGINAL, value.id).apply()

    /**
     * Whether the slideshow picks each next photo at random rather than in order.
     *
     * Persisted like the interval: someone who chooses 随机 expects it to still be
     * random the next time they open the slideshow, not to silently revert.
     */
    var slideshowShuffled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    /**
     * The album this television plays by itself after nobody has touched the remote for
     * a while — the 屏保相册 — or null when none is set, which is the default.
     *
     * Stored as two values rather than as the album object: an id is what asks the NAS for
     * the photos, and a **name** is what the settings row and the long-press menu can read
     * without listing every album on every screen — a television with a large library pays
     * a round trip for that list, and a name that only appeared once the list had loaded
     * would look like the setting had been lost.
     *
     * The name is therefore a remembered label, not a fact: an album renamed on the NAS
     * still screen-saves correctly (the id is what plays) and still reads as the old name
     * here until it is chosen again. That is the same trade `SavedAccount` makes with the
     * account it is a shortcut to, and the alternative — no name at all — leaves the row
     * saying "已指定" and nothing about which album.
     */
    var screenSaverAlbumId: String?
        get() = prefs.getString(KEY_SCREEN_SAVER_ALBUM, null)?.takeIf { it.isNotBlank() }
        set(value) = prefs.edit()
            .putString(KEY_SCREEN_SAVER_ALBUM, value?.takeIf { it.isNotBlank() })
            .apply()

    var screenSaverAlbumName: String
        get() = prefs.getString(KEY_SCREEN_SAVER_NAME, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SCREEN_SAVER_NAME, value).apply()

    /**
     * How the 文件夹 section lays its folders out.
     *
     * Persisted for the same reason as everything else here: a layout chosen once should
     * still be there after a restart, or the toggle is a thing you press every time.
     */
    var folderLayout: FolderLayout
        get() = FolderLayout.fromId(prefs.getString(KEY_FOLDER_LAYOUT, null)) ?: FolderLayout.DEFAULT
        set(value) = prefs.edit().putString(KEY_FOLDER_LAYOUT, value.id).apply()

    /**
     * Asset paths of the tracks ticked for the slideshow's background music, in the
     * order they play — which is the catalogue's order, not the order they were ticked.
     * An empty list means no music, which is the default.
     *
     * Since 1.5.0 the list also holds `file:` ids for music that is not in the APK:
     * uploaded files and files found on this device. What is dropped here is only what
     * could never play at all (a stray value, a renamed asset); whether a file is still
     * *there* is answered by [com.tvphoto.data.music.MusicLibrary], which is the only
     * thing that can look.
     */
    var slideshowMusic: List<String>
        get() = decodeMusicSelection(prefs.getString(KEY_MUSIC, null))
        set(value) = prefs.edit().putString(KEY_MUSIC, encodeMusicSelection(value)).apply()

    /**
     * The order the user arranged their music in, as ids.
     *
     * Stored rather than derived from the files, because the arrangement is a decision and
     * not a fact about them: the upload page's 排序 mode is the only thing that writes it,
     * and a new upload is prepended to it. It holds *every* kind of track — a bundled asset
     * path is just another id here, which is what lets the bundled track be moved around
     * like anything else. It is allowed to be incomplete in both directions:
     * `orderedByStoredOrder` skips ids it cannot place and appends tracks the list does not
     * name yet, so a deleted file cannot corrupt it.
     */
    var musicOrder: List<String>
        get() = decodeMusicOrder(prefs.getString(KEY_MUSIC_ORDER, null))
        set(value) = prefs.edit()
            .putString(KEY_MUSIC_ORDER, encodeMusicOrder(value))
            .apply()

    /**
     * Bundled tracks the user has deleted, by asset path.
     *
     * The one kind of delete that cannot touch a file: `assets/music` is inside the APK and
     * read-only, so 删除 on one of the four recorded here means "not in my list any more".
     * It is reversible on purpose ([MusicLibrary.restoreBuiltins]) — the bytes are still in
     * the package, and a delete that cannot be undone but also cannot free a byte would be
     * the worst of both.
     */
    var musicRemovedBuiltins: List<String>
        get() = decodeMusicOrder(prefs.getString(KEY_MUSIC_REMOVED, null))
        set(value) = prefs.edit()
            .putString(KEY_MUSIC_REMOVED, encodeMusicOrder(value))
            .apply()

    /**
     * Remembered logins, most recent first, keyed by host **and** user.
     *
     * `host`, `userName`, `password` and `accessCode` above stay as the last-used set:
     * that is what the app auto-signs-in with at start-up. This list is what the login
     * screen offers as one-press shortcuts, and it keeps each account's own password.
     */
    var savedAccounts: List<SavedAccount>
        get() = decodeAccounts(prefs.getString(KEY_ACCOUNTS, null))
        set(value) = prefs.edit().putString(KEY_ACCOUNTS, encodeAccounts(value)).apply()

    /** Adds or refreshes a login and makes it the most recent one. */
    fun saveAccount(account: SavedAccount) {
        savedAccounts = mergeAccount(savedAccounts, account)
    }

    /**
     * Certificate fingerprints pinned on first contact, per host.
     *
     * Kept even across sign-outs: the point is to notice a *change* since last time, and
     * forgetting it on sign-out would silently accept whatever appears next.
     */
    override fun pinnedFingerprint(host: String): String? = certPins()[pinKey(host)]

    override fun rememberFingerprint(host: String, fingerprint: String) {
        writeCertPins(certPins() + (pinKey(host) to fingerprint))
    }

    override fun forgetFingerprint(host: String) {
        writeCertPins(certPins() - pinKey(host))
    }

    private fun pinKey(host: String): String = host.trim().lowercase(Locale.US)

    private fun certPins(): Map<String, String> =
        prefs.getString(KEY_CERT_PINS, "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val at = line.indexOf('=')
                if (at <= 0) null else line.take(at) to line.substring(at + 1)
            }
            .toMap()

    private fun writeCertPins(pins: Map<String, String>) {
        prefs.edit()
            .putString(KEY_CERT_PINS, pins.entries.joinToString("\n") { "${it.key}=${it.value}" })
            .apply()
    }

    init {
        // An install from before per-account storage has only the single last-used set.
        // Fold it in once, so updating does not lose the login that was already saved.
        // The host-only `recent_hosts` list is not migrated: it has no user attached, and
        // guessing one is exactly the mistake this replaced.
        if (!prefs.contains(KEY_ACCOUNTS) && hasSavedCredentials) {
            savedAccounts = listOf(SavedAccount(host, userName, password, accessCode))
        }

        // FN ID sign-in has been removed, so a handle an earlier version saved
        // (`kungfucode`) can only ever fail now. Drop it from the shortcut list rather
        // than offer a login that never works, and stop prefilling it as the address —
        // while keeping the account and password, so only the address has to be retyped.
        val accounts = savedAccounts
        val addressable = accounts.filterNot { isLegacyFnIdHost(it.host) }
        if (addressable.size != accounts.size) savedAccounts = addressable
        if (isLegacyFnIdHost(host)) host = ""
    }

    val hasSavedCredentials: Boolean
        get() = host.isNotBlank() && userName.isNotBlank() && password.isNotBlank()

    override fun load(): SignMode =
        SignMode.fromId(prefs.getString(KEY_SIGN_MODE, null)) ?: SignMode.RAW_VALUES

    override fun save(mode: SignMode) {
        prefs.edit().putString(KEY_SIGN_MODE, mode.id).apply()
    }

    companion object {
        /**
         * The intervals the UI offers, shortest first.
         *
         * Shared by the settings row and the viewer's slideshow menu so the two
         * cannot drift apart.
         */
        val INTERVAL_OPTIONS = listOf(3, 5, 8, 15, 30)

        private const val PREFS = "tv_photo_settings"
        private const val KEY_HOST = "host"
        private const val KEY_USER = "user"
        private const val KEY_PASSWORD = "password"
        private const val KEY_ACCESS_CODE = "access_code"
        private const val KEY_ACCOUNTS = "saved_accounts"
        private const val KEY_CERT_PINS = "cert_pins"
        private const val KEY_SIGN_MODE = "sign_mode"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_SLIDESHOW = "slideshow_seconds"
        private const val KEY_PREVIEW_ORIGINAL = "preview_original"
        private const val KEY_SHUFFLE = "slideshow_shuffled"
        private const val KEY_FOLDER_LAYOUT = "folder_layout"
        private const val KEY_MUSIC = "slideshow_music"
        private const val KEY_MUSIC_ORDER = "music_order"
        private const val KEY_MUSIC_REMOVED = "music_removed_builtins"
        private const val KEY_SCREEN_SAVER_ALBUM = "screen_saver_album"
        private const val KEY_SCREEN_SAVER_NAME = "screen_saver_album_name"
        private const val DEFAULT_SLIDESHOW_SECONDS = 8
    }
}
