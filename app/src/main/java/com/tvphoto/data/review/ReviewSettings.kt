package com.tvphoto.data.review

import android.content.Context
import com.tvphoto.data.foldFullWidth

/**
 * Whether 图评 runs at all, and where.
 *
 * [SLIDESHOW] is the useful default rather than "everything": a slideshow is the one
 * moment a QR code has a chance of being looked at, and it is also the mode where the
 * host is deliberately not holding the remote.
 *
 * Stored by [id] rather than by ordinal, like `ThemeMode` and `PreviewOriginal`, so
 * inserting an option cannot silently change what an existing install is set to.
 */
enum class ReviewMode(val id: String) {
    /** No code, no danmaku, no network. */
    OFF("off"),

    /** Only while the slideshow is running. */
    SLIDESHOW("slideshow"),

    /** On every full-screen photo. */
    ALL("all"),

    ;

    fun next(): ReviewMode = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = SLIDESHOW

        fun fromId(id: String?): ReviewMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * How much danmaku to draw.
 *
 * One value governs the text size, how much history is replayed and **how many rows the band
 * may use** — `少` two, `多` four ([tracks]) — because those are all the same question: how
 * much of the photograph is this layer allowed to cover. "图评的弹幕数量设置为少，则最多显示
 * 2 行，设置为多，最多显示 4 行."
 *
 * It is a *ceiling*, not a target. Rows are taken in turn and each one carries as many
 * comments as fit (see `DanmakuFeed.freeTrack`), so a quiet photo shows one or two lines
 * whichever setting is in force; the setting decides how much room a busy thread may take.
 *
 * Changed from the viewer's 图评 button, not from the settings screen: it is the kind of
 * thing somebody adjusts *while looking at a photo*, and a global row for it made the
 * settings page longer than a 1080p screen could scroll without help.
 */
enum class DanmakuLevel(val id: String) {
    OFF("off"),

    /** 34px, replay the last 3, at most two rows. The default: readable without shouting. */
    FEW("few"),

    /** 40px, replay the last 8, at most four rows. */
    MANY("many"),

    ;

    fun next(): DanmakuLevel = entries[(ordinal + 1) % entries.size]

    /**
     * The most rows this setting may lay danmaku out in.
     *
     * Two and four rather than a row per comment: the band is a commentary on a photograph,
     * and the earlier per-comment layout — five comments in five rows, "5 个评论就会用 5 行"
     * — covered the picture instead of annotating it. Never more than
     * `DanmakuSpec.MAX_TRACKS`, which is the grid the rows are placed on.
     */
    val tracks: Int
        get() = when (this) {
            OFF -> 0
            FEW -> 2
            MANY -> 4
        }

    /**
     * The text size in pixels at 1080p.
     *
     * Pixels, not `sp`: the overlay converts this through the density before drawing it,
     * and every other number in the layer — the speed, the tracks, the 45% width ceiling —
     * is in screen pixels as well. Used directly as `sp` it drew twice this size on a
     * 320dpi television and clipped the line to half its width (the note in
     * `ui/viewer/DanmakuOverlay.kt` is where that was diagnosed).
     *
     * The design asked for 38 and 44. Both came down by four once the unit was right and
     * the layer was watched at its true size: the ask was 小一点, and 34 at 1080p is still
     * comfortably above what a phone draws.
     */
    val textPx: Float
        get() = when (this) {
            OFF -> 0f
            FEW -> 34f
            MANY -> 40f
        }

    /** How many recent comments are replayed when a photo opens. */
    val replay: Int
        get() = when (this) {
            OFF -> 0
            FEW -> 3
            MANY -> 8
        }

    companion object {
        val DEFAULT = FEW

        fun fromId(id: String?): DanmakuLevel? = entries.firstOrNull { it.id == id }
    }
}

/**
 * The address as the app should hold it: what somebody meant, with nothing invisible left.
 *
 * Three kinds of character get in by accident, and all three fail the same way — the
 * settings row draws an address that looks perfectly usable, `HttpUrl` refuses to parse it,
 * and 图评 turns itself off with a log line calling the address "blank, or not a URL" about
 * something that is plainly neither:
 *
 *  - **invisible bytes.** The television this was found on held `106.52.237.179:53347` with
 *    a NUL on the end. `String.trim()` does not touch it, because a NUL is not whitespace.
 *    Zero-width spaces and a byte-order mark arrive the same way, from a paste;
 *  - **full-width punctuation**, which a Chinese IME produces for `.`, `:` and the digits.
 *    The NAS address row has folded these since 1.3.2 (`foldFullWidth`); this row silently
 *    did not;
 *  - **whitespace**, which a paste brings along.
 *
 * Applied on the way into the store *and* on the way out, so an address an older build
 * saved badly is healed by reading it rather than by asking somebody to type a
 * seventeen-character address on a television remote a second time.
 *
 * Only the *text* is repaired here. An address that is genuinely malformed still fails, in
 * `ReviewClient.normalize`, which is where the honest "this is not a URL" answer lives.
 */
internal fun cleanServerAddress(raw: String): String = buildString(raw.length) {
    for (ch in foldFullWidth(raw)) {
        val code = ch.code
        // C0 and C1 controls plus DEL: invisible, and no scheme, host or port can hold one.
        if (code < 0x20 || code in 0x7F..0x9F) continue
        // The other invisibles a paste can carry: zero-width space, zero-width joiner and
        // non-joiner, and the byte-order mark.
        if (ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF') continue
        append(ch)
    }
}.trim()

/**
 * The one place the app writes anything about 图评.
 *
 * A separate preferences file from the NAS settings on purpose: these are about a
 * second, unrelated server, and a "clear the NAS settings" path that also wiped the
 * review server's address would be a surprise. Following the house rule, no Room —
 * these are a string and four enums.
 */
class ReviewSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Where the review server is; blank means unset and nothing is attempted.
     *
     * Cleaned on both sides — see [cleanServerAddress] for what gets in and why reading is
     * enough to repair a value that an older build stored wrong.
     */
    var serverUrl: String
        get() = cleanServerAddress(prefs.getString(KEY_SERVER, "").orEmpty())
        set(value) = prefs.edit().putString(KEY_SERVER, cleanServerAddress(value)).apply()

    var mode: ReviewMode
        get() = ReviewMode.fromId(prefs.getString(KEY_MODE, null)) ?: ReviewMode.DEFAULT
        set(value) = prefs.edit().putString(KEY_MODE, value.id).apply()

    /**
     * How much danmaku to draw — and whether to draw any at all: `off` is the switch.
     *
     * One value rather than a boolean beside an amount, which is what this used to be. The
     * two overlapped (either one could mean "nothing on screen") and the settings screen has
     * always offered the three words, so the boolean was a second way to say what `off`
     * already says (docs/tuping-design.md §17.29).
     *
     * Changed from **设置 → 图评设置**, not from the viewer: since §17.29 the viewer's corner
     * has one button — 扫码评论 — which opens 图评 on the photo and turns this to `少` if it
     * was `off`. Turning the layer *off* is a preference, and preferences live in Settings.
     */
    var danmakuLevel: DanmakuLevel
        get() = DanmakuLevel.fromId(prefs.getString(KEY_DANMAKU, null)) ?: DanmakuLevel.DEFAULT
        set(value) = prefs.edit().putString(KEY_DANMAKU, value.id).apply()

    /**
     * Whether an arriving comment holds the current photo on screen a little longer.
     *
     * On by default, and it is the answer to the one real conflict in the design: the
     * slideshow's shortest interval is 3 seconds and the longest danmaku takes 8.7 to
     * cross. Compressing the speed instead would make a 3-second comment unreadable
     * (docs/tuping-design.md §9.5).
     */
    var extendStayOnComment: Boolean
        get() = prefs.getBoolean(KEY_EXTEND, true)
        set(value) = prefs.edit().putBoolean(KEY_EXTEND, value).apply()

    /**
     * Whether the *slideshow* carries 图评, so its photos are registered as they appear.
     *
     * The one question about 图评 that is genuinely about the *slideshow* rather than about a
     * photo, which is why it lives in the viewer's slideshow settings rather than in the
     * per-photo 图评 menu: a host running a party slideshow decides once that the whole
     * rotation is commentable, and nobody is holding the remote to press anything on each
     * picture (docs/tuping-design.md §17.28).
     *
     * **Off by default, and that is the change §17.28 made.** It used to be on, which meant
     * a show uploaded every photo it played without anybody asking — the behaviour that put
     * an entire album in the database of a room nobody was in. Pressing 图评 on a photo is
     * still the way to make *that* photo commentable, and this switch is the only thing that
     * ever registers a photo nobody pressed anything on.
     */
    var slideshowEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLIDESHOW, false)
        set(value) = prefs.edit().putBoolean(KEY_SLIDESHOW, value).apply()

    /** The device token this app was issued, or blank if it has never registered. */
    var deviceToken: String
        get() = prefs.getString(KEY_DEVICE_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_TOKEN, value).apply()

    /** The token that authorises deleting this device's photos. */
    var ownerToken: String
        get() = prefs.getString(KEY_OWNER_TOKEN, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OWNER_TOKEN, value).apply()

    /** This install's device id; generated once and then stable. */
    var deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DEVICE_ID, value).apply()

    /**
     * Whether the server address or the tokens changed, so the client must rebuild.
     *
     * The separator is a NUL, which [cleanServerAddress] guarantees the address cannot
     * contain — two fields cannot be made to look like three.
     */
    fun fingerprint(): String = listOf(serverUrl, deviceId, deviceToken).joinToString("\u0000")

    /** Forgets the session but keeps the settings, so signing out is not a reset. */
    fun forgetDevice() {
        prefs.edit()
            .remove(KEY_DEVICE_ID)
            .remove(KEY_DEVICE_TOKEN)
            .remove(KEY_OWNER_TOKEN)
            .apply()
    }

    private companion object {
        const val PREFS = "tv_photo_review"
        const val KEY_SERVER = "server_url"
        const val KEY_MODE = "mode"
        const val KEY_DANMAKU = "danmaku_level"
        const val KEY_EXTEND = "extend_stay"
        const val KEY_SLIDESHOW = "slideshow_enabled"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_TOKEN = "device_token"
        const val KEY_OWNER_TOKEN = "owner_token"
    }
}
