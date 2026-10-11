package com.tvphoto.ui.viewer

/**
 * The viewer's 图评 menu rows, in the order the D-pad walks them.
 *
 * The menu is per-photo and per-moment, which is the whole reason it exists: what a host
 * wants during a party slideshow is not what they want while checking one picture on their
 * own, and a global settings page cannot express that.
 */
internal const val REVIEW_ROW_QR = 0
internal const val REVIEW_ROW_DANMAKU = 1
internal const val REVIEW_ROW_AMOUNT = 2
internal const val REVIEW_ROW_COUNT = 3

/** Which column the bottom-right corner has expanded, if any. */
internal enum class CornerMenu {
    /** The slideshow's own settings: play/pause, interval, order, music. */
    SLIDESHOW,

    /** 图评's display switches: the code, the danmaku layer, how much of it. */
    REVIEW,
}
