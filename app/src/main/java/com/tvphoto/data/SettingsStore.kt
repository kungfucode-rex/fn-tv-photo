package com.tvphoto.data

import android.content.Context
import com.tvphoto.data.fn.CertificatePinStore
import com.tvphoto.data.fn.SignMode
import com.tvphoto.data.fn.SignModeStore
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
     * Whether the slideshow picks each next photo at random rather than in order.
     *
     * Persisted like the interval: someone who chooses 随机 expects it to still be
     * random the next time they open the slideshow, not to silently revert.
     */
    var slideshowShuffled: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHUFFLE, value).apply()

    /**
     * Asset paths of the tracks ticked for the slideshow's background music, in the
     * order they play — which is the catalogue's order, not the order they were ticked.
     * An empty list means no music, which is the default.
     */
    var slideshowMusic: List<String>
        get() = decodeMusicSelection(prefs.getString(KEY_MUSIC, null))
        set(value) = prefs.edit().putString(KEY_MUSIC, encodeMusicSelection(value)).apply()

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
        private const val KEY_SHUFFLE = "slideshow_shuffled"
        private const val KEY_MUSIC = "slideshow_music"
        private const val DEFAULT_SLIDESHOW_SECONDS = 8
    }
}
