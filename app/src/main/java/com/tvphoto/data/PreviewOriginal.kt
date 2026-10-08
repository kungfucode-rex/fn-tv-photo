package com.tvphoto.data

/**
 * Whether full-screen previews draw the original file or the server's large thumbnail.
 *
 * The original is the photograph as it was taken — 12 megapixels and several megabytes
 * over the wire on a NAS that is not on the same LAN. The large thumbnail is the same
 * picture at 1920px, which is what a 1080p screen can actually show, and it is already
 * on disk from the grid. On a NAS reached over the internet that difference is the one
 * between a preview that lands and one that is sat waiting for.
 *
 * The default is [SLIDESHOW_ONLY]: someone flicking through photos by hand wants the
 * next one now, while a slideshow on a timer has the seconds to spend on the original
 * and is the thing being watched closely enough to notice.
 *
 * Stored by [id] rather than by ordinal, like [ThemeMode], so that inserting an option
 * cannot silently change what an existing install is set to.
 */
enum class PreviewOriginal(val id: String) {
    /** Every preview is the original file. */
    ALWAYS("always"),

    /** Every preview is the large thumbnail; the original is never fetched. */
    NEVER("never"),

    /** Manual paging uses the large thumbnail; the slideshow uses the original. */
    SLIDESHOW_ONLY("slideshow"),

    ;

    /** The next option, in the order the settings row offers them. */
    fun next(): PreviewOriginal = entries[(ordinal + 1) % entries.size]

    /**
     * Whether the still wanted *right now* is the original.
     *
     * [slideshowRunning] is passed in rather than read from anywhere, so this stays a
     * property of the setting and not of the viewer: the same setting asked twice
     * during one slideshow answers the same way.
     */
    fun wantsOriginal(slideshowRunning: Boolean): Boolean = when (this) {
        ALWAYS -> true
        NEVER -> false
        SLIDESHOW_ONLY -> slideshowRunning
    }

    companion object {
        val DEFAULT = SLIDESHOW_ONLY

        fun fromId(id: String?): PreviewOriginal? = entries.firstOrNull { it.id == id }
    }
}
