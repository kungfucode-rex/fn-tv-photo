package com.tvphoto.data.review

/**
 * What the album end should do about 图评 for the photo on screen.
 *
 * Three answers, and the middle one is the whole of §17.28: **showing a photo is not asking
 * for it to be commentable.** Before this, every photo the viewer showed was uploaded — a
 * fingerprint, a thumbnail, a short code — whether or not anybody wanted comments on it, and
 * on a screensaver album that meant an entire library arriving in somebody else's database
 * with nobody in the room. Now the only thing that puts a picture on the server is a person
 * asking for one (the viewer's 图评 row) or a show whose own 图评 switch is on.
 */
enum class ReviewDecision {
    /** Nothing at all: no lookup, no code, no danmaku, no request. */
    HIDDEN,

    /**
     * Ask the server whether **it** knows this picture, and draw whatever comes back — a
     * code and its comments, or nothing at all.
     *
     * This is the ordinary answer, and a miss is not a failure: it means nobody has ever
     * opened 图评 on this photo, so it is not on the server, so there is nothing to show.
     * The lookup is what lets a photo that *was* opened on an earlier evening still show its
     * code and its danmaku without being uploaded again.
     */
    LOOKUP,

    /**
     * A show is carrying 图评, so a picture the server does not know yet is registered as it
     * appears. That is what a party rotation is: the host decided once, in 幻灯片 settings,
     * that the whole show is commentable, and nobody is holding the remote.
     */
    REGISTER,
}

/**
 * The decision, as a pure function of the facts the screen has.
 *
 * - **a video** is never eligible: it has its own rhythm and no still to comment on;
 * - **[mode] `关`** is the one value that means "stop talking to the server at all";
 * - **a blank address** means there is nowhere to ask;
 * - **a screensaver** never draws the code — a code is addressed to somebody standing in
 *   front of the television with a phone, and a screensaver is on because nobody is there —
 *   so on that screen the danmaku is the only thing that could appear, and with the danmaku
 *   off there is nothing worth a request;
 * - **[slideshow] with [slideshowEnabled]** is the one case that registers by itself. Its
 *   default is 关, so a fresh install uploads nothing until somebody asks it to.
 *
 * Deliberately *not* consulted: whether this photo happens to be armed already. That is not
 * a fact about the screen — it lives on the server (and in this session's answer cache), and
 * the caller finds out by looking it up, which is what [LOOKUP] means.
 *
 * The danmaku has one switch, not two: [danmakuLevel] at `off` *is* "no danmaku", and since
 * the viewer's 扫码评论 button replaced the 图评 menu (§17.29) there is nowhere left that
 * could hold a second, overlapping one.
 */
fun reviewDecision(
    isVideo: Boolean,
    mode: ReviewMode,
    serverUrl: String,
    danmakuLevel: DanmakuLevel,
    screenSaver: Boolean = false,
    slideshow: Boolean = false,
    slideshowEnabled: Boolean = false,
): ReviewDecision {
    if (isVideo) return ReviewDecision.HIDDEN
    if (mode == ReviewMode.OFF) return ReviewDecision.HIDDEN
    if (serverUrl.isBlank()) return ReviewDecision.HIDDEN

    if (screenSaver && !danmakuDrawn(danmakuLevel)) return ReviewDecision.HIDDEN

    if (slideshow && slideshowEnabled) return ReviewDecision.REGISTER
    return ReviewDecision.LOOKUP
}

/** Whether the danmaku layer would put anything on screen. */
fun danmakuDrawn(level: DanmakuLevel): Boolean = level != DanmakuLevel.OFF
