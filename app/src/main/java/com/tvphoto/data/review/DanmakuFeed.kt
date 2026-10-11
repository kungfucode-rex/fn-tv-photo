package com.tvphoto.data.review

/**
 * The danmaku layer's brain, with no Compose in it.
 *
 * Everything here is a pure function of elapsed time, which is the point: the overlay is
 * a `withFrameNanos` loop that only touches the draw phase, and the arithmetic that
 * decides *where* each line is has to be testable without a screen
 * (docs/tuping-design.md §9.5).
 *
 * The numbers that matter, and why:
 *
 *  - **Speed is constant at [SPEED_PX_PER_SECOND].** A line that speeds up or slows
 *    down is a line nobody can read, and the design forbids varying it with the
 *    slideshow interval.
 *  - **A line is at most [MAX_WIDTH_FRACTION] of the screen.** At this speed the longest
 *    allowed line takes about ten seconds to cross, so a thread longer than that outlives
 *    the photo it belongs to — which is why the photo is held, not the line truncated
 *    (see [StayExtension]).
 *  - **A track is a *lane*, not a slot.** A lane carries as many comments as fit one after
 *    another: the next one enters only once the line before it has cleared the entry point
 *    by [DanmakuFeed.TRACK_GAP_PX], which is what stops two comments in one lane from
 *    reading as a single run-on sentence.
 *  - **Lanes are taken in turn** (`freeTrack`), so a thread is spread across the band *and*
 *    each lane carries several comments, rather than one lane per comment.
 *  - **How many rows is the 少/多 setting's ceiling** — two, or four ([DanmakuLevel.tracks]) —
 *    laid out on a fixed [DanmakuSpec.MAX_TRACKS]-row grid. Laying a row out per comment put
 *    five comments in five rows of a photograph, which is what "5 个评论就会用 5 行" was.
 *  - **The same comment is not shown again inside [REPEAT_COOLDOWN_MS].** The layer loops,
 *    so this is the one rule that keeps a two-comment thread from becoming a two-frame
 *    stutter.
 */
class DanmakuFeed(
    private val level: DanmakuLevel = DanmakuLevel.DEFAULT,
    /** Screen width in pixels; every position is computed against it. */
    private val screenWidthPx: Float = 1920f,
    tracks: Int = level.tracks,
) {

    /**
     * How many rows the band may use — the 少/多 setting's ceiling, never the grid's four.
     *
     * Fixed for the life of the feed, because the feed is rebuilt when the setting changes:
     * a `var` here was the earlier design, when the row count followed the thread on screen,
     * and the setting is the rule that replaced it ("设置为少，则最多显示 2 行，设置为多，最多
     * 显示 4 行").
     */
    val tracks: Int = tracks.coerceIn(1, DanmakuSpec.MAX_TRACKS)

    /** One line currently crossing. */
    data class Active(
        val comment: ReviewComment,
        val track: Int,
        val widthPx: Float,
        /** When it entered the right edge, in the frame clock's own units. */
        val startedAtNanos: Long,
    ) {
        /** How far left of the right edge this line has travelled, in pixels. */
        fun travelled(elapsedNanos: Long): Float =
            elapsedNanos / 1_000_000_000f * SPEED_PX_PER_SECOND

        /** Where the line's left edge is, in screen coordinates. */
        fun leftX(nowNanos: Long, screenWidth: Float): Float {
            val travelled = travelled(nowNanos - startedAtNanos)
            return screenWidth - travelled
        }

        /** True once the whole line is off the left edge. */
        fun finished(nowNanos: Long, screenWidth: Float): Boolean =
            leftX(nowNanos, screenWidth) + widthPx < 0f

        /** True once the line is fully on screen — its right edge has cleared the left of the entry. */
        fun fullyEntered(nowNanos: Long): Boolean =
            travelled(nowNanos - startedAtNanos) >= widthPx
    }

    private val active = mutableListOf<Active>()

    /**
     * The lane the last line went to, so the next one starts after it rather than on top of
     * it. -1 before anything has been drawn, which puts the first line on lane 0.
     */
    private var lastTrack: Int = -1

    /**
     * One line waiting for a track, with the width it will be drawn at.
     *
     * The width travels with the comment because it is *measured*, not derived: the overlay
     * owns a `TextMeasurer`, and a queued line must keep the box that was measured for it
     * rather than have one estimated later from the text.
     */
    private data class Queued(val comment: ReviewComment, val widthPx: Float)

    private val queue = ArrayDeque<Queued>()

    /**
     * comment id -> the frame-clock stamp the line last *entered* the screen at.
     *
     * Entered rather than offered, because that is what "同一个弹幕的间隔" measures: the gap
     * between two appearances on screen, not between two attempts to schedule one. Cleared
     * with everything else on a photo change — the floor is a property of one picture's
     * layer, not of the visit.
     */
    private val lastShownAt = mutableMapOf<String, Long>()

    /** Lines dropped because the queue was full, for the settings page's diagnostics. */
    var dropped: Int = 0
        private set

    /** Lines currently crossing. */
    val onScreen: List<Active> get() = active

    /** Lines waiting for a track. */
    val queued: Int get() = queue.size

    val enabled: Boolean get() = level != DanmakuLevel.OFF

    /**
     * Whether [comment] may be drawn at [nowNanos]: visible, non-blank, not already
     * crossing or waiting, and not inside [REPEAT_COOLDOWN_MS] of its last appearance.
     *
     * Split out of [offer] so the overlay's loop can ask before it disturbs anything —
     * an offer that is going to be refused should not move a comment to the back of the
     * rotation.
     */
    fun canShow(comment: ReviewComment, nowNanos: Long): Boolean {
        if (!enabled || comment.status != VISIBLE_STATUS) return false
        if (comment.content.isBlank()) return false
        if (active.any { it.comment.id == comment.id }) return false
        if (queue.any { it.comment.id == comment.id }) return false
        val shown = lastShownAt[comment.id]
        return shown == null || nowNanos - shown >= REPEAT_COOLDOWN_NANOS
    }

    /**
     * Offers a comment to the layer.
     *
     * Returns true when it will be shown — either immediately or after waiting for a
     * track. The same comment is refused while it is still crossing, while it is waiting
     * for a track, and for [REPEAT_COOLDOWN_MS] after it last appeared: the layer loops
     * (see `ui/viewer/DanmakuOverlay.kt`), and a line that came round again the moment it
     * left would read as a stutter rather than as a comment being repeated.
     *
     * [widthPx] is the width the line will actually be drawn at, measured by the caller
     * that owns a `TextMeasurer`. The default is [ReviewComment.widthPx]'s estimate — one
     * em per CJK character, 0.55 for Latin — which is what this class can work out on its
     * own and is what the tests use; on screen the measured number wins, because the box a
     * line is given has to be the width of the text in it or the line ends in an ellipsis.
     */
    fun offer(
        comment: ReviewComment,
        nowNanos: Long,
        widthPx: Float = comment.widthPx(level.textPx),
    ): Boolean {
        if (!canShow(comment, nowNanos)) return false

        val width = DanmakuSpec.clampWidth(widthPx, screenWidthPx)

        val track = freeTrack(nowNanos)
        if (track < 0) {
            // Overflow goes to the queue, and the queue itself has a ceiling: a TV that
            // fell behind must lose danmaku, not accumulate them until it dies.
            if (queue.size >= MAX_QUEUE) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(Queued(comment, width))
            return true
        }

        enter(comment, track, width, nowNanos)
        return true
    }

    /**
     * Drops every line waiting and everything on screen.
     *
     * Called when the photo changes: the danmaku layer belongs to one picture, and a
     * comment about the previous one sliding across the next is simply wrong.
     */
    fun reset() {
        active.clear()
        queue.clear()
        lastShownAt.clear()
        lastTrack = -1
    }

    /** Advances the layer and returns whether anything is still moving. */
    fun advance(nowNanos: Long): Boolean {
        if (active.isEmpty() && queue.isEmpty()) return false

        active.removeAll { it.finished(nowNanos, screenWidthPx) }

        // A waiting line starts the moment a track frees up, not at a fixed cadence, so
        // a burst spreads out only as far as it has to.
        while (queue.isNotEmpty()) {
            val track = freeTrack(nowNanos)
            if (track < 0) break
            val next = queue.removeFirst()
            enter(next.comment, track, next.widthPx, nowNanos)
        }

        return active.isNotEmpty() || queue.isNotEmpty()
    }

    /** Puts one line on a lane, and records when it went up and where. */
    private fun enter(comment: ReviewComment, track: Int, widthPx: Float, nowNanos: Long) {
        lastShownAt[comment.id] = nowNanos
        lastTrack = track
        active.add(Active(comment, track, widthPx, nowNanos))
    }

    /**
     * The lane the next line goes to, or -1.
     *
     * **Lanes are taken in turn**, starting after the one the last line went to, and a lane
     * is only skipped when the line already in it has not cleared the entry point by
     * [TRACK_GAP_PX]. That is what makes the band carry several comments per line *and* stay
     * even: a thread of six in two lanes puts comments 1, 3 and 5 in the first and 2, 4 and 6
     * in the second, 2.4 seconds apart, instead of filling one lane until it is busy and only
     * then opening the next.
     *
     * Both earlier versions of this are recorded here because they are the two ways this
     * goes wrong, and the reports were one line each:
     *
     *  - *the lowest lane with room* put every comment on lane 0 the moment the line before
     *    it had cleared the entry point — "六条轨道，一行文字，文字重叠了";
     *  - *an empty lane before a reusable one* then spread a lane per comment, which fixed
     *    that and produced the opposite complaint: "5 个评论就会用 5 行，还是一行一个".
     *
     * Alternating is the rule that answers both: the rows below [DanmakuLevel.tracks] are a
     * fixed, small band — two or four, by the 少/多 setting — and every one of them is
     * expected to hold more than one comment.
     */
    private fun freeTrack(nowNanos: Long): Int {
        for (step in 1..tracks) {
            val track = ((lastTrack + step) % tracks + tracks) % tracks
            val last = active.filter { it.track == track }.maxByOrNull { it.startedAtNanos }
                ?: return track
            val lastLeft = last.leftX(nowNanos, screenWidthPx)
            if (lastLeft + last.widthPx + TRACK_GAP_PX <= screenWidthPx) return track
        }
        return -1
    }

    companion object {
        /**
         * Constant speed, in screen pixels per second.
         *
         * The design's number was 320, which crosses a 1080p screen in six seconds. On the
         * television this was actually watched on it read as too fast — 稍微慢一点 — and then
         * 再慢一点: 260 crossed in 7.4 seconds and was still quick, so it is 220, which is
         * 8.7 seconds for the screen and 12.7 for the longest line the 45% ceiling allows.
         * Slower than this runs into the slideshow's own interval, which is what
         * [StayExtension] exists to absorb.
         */
        const val SPEED_PX_PER_SECOND = 220f

        /**
         * How long the same comment has to stay away after appearing, in milliseconds.
         *
         * The layer loops (see [DanmakuOverlay]), and this is the floor on the loop: asked
         * for as "同一个弹幕间隔时间不能小于 5s". A full crossing already takes longer than
         * this, so what it actually guards is the moment a line leaves the screen — and the
         * moment the comment list changes, which re-runs the replay and used to be able to
         * put a line that had just crossed back up immediately.
         */
        const val REPEAT_COOLDOWN_MS = 5_000L

        /** [REPEAT_COOLDOWN_MS] in the frame clock's units. */
        private const val REPEAT_COOLDOWN_NANOS = REPEAT_COOLDOWN_MS * 1_000_000L

        /** A line may not be wider than this share of the screen. */
        const val MAX_WIDTH_FRACTION = 0.45f

        /** How many may wait before the oldest is dropped. */
        const val MAX_QUEUE = 50

        /**
         * The visible gap between two lines sharing a lane, in pixels.
         *
         * At [SPEED_PX_PER_SECOND] this is about **0.9 seconds** of clear picture between the
         * tail of one comment and the head of the next — "不能挨得太紧" is this number. It was
         * 32px, a seventh of a second, which was survivable while every comment had a lane of
         * its own and reads as one run-on line the moment two share one.
         *
         * It is a trade: a 45%-wide line now occupies its lane for about five seconds rather
         * than four and a half, so a lane carries slightly fewer comments per minute.
         */
        const val TRACK_GAP_PX = 200f

        /**
         * The beat between two comments being offered, in milliseconds.
         *
         * Both the opening replay and the loop below use it: it is the layer's idea of
         * "one at a time", and the tracks do the rest of the pacing.
         */
        const val REPLAY_STAGGER_MS = 1200L

        /** The status a comment must have to be drawn at all. */
        const val VISIBLE_STATUS = 1
    }
}

/** The geometry of one line, kept apart so both the feed and the overlay agree. */
object DanmakuSpec {

    /**
     * The most rows a setting may lay out — the four `多` allows.
     *
     * A ceiling for the settings rather than a grid: `少` uses two of the four, and the band is
     * shared between however many are in use (see [trackPitchPx]), so this is also the tallest
     * a thread can get. Four rather than the six and twelve this started at: past four the band
     * stops reading as a few lines of commentary over a photograph and starts covering the
     * photograph, and one row per comment put five comments in five rows ("5 个评论就会用 5 行").
     */
    const val MAX_TRACKS = 4

    /**
     * How tall one line of text is, relative to its font size.
     *
     * Only used to centre a line in its row and to keep the rows apart — see
     * [lineHeightPx].
     */
    const val LINE_HEIGHT_FACTOR = 1.25f

    /** The design's top safe area, in pixels at 1080p: nothing is drawn above it. */
    private const val SAFE_TOP_AT_1080 = 96f

    /** What a two-row thread may cover of the screen's height, from the top. */
    private const val TWO_ROW_SHARE = 0.25f

    /** What a four-row thread may cover: "4 行的话最多占全屏 2/5 的高度". */
    private const val FOUR_ROW_SHARE = 0.40f

    /**
     * How much of the screen's height a thread laid out in [tracks] rows may cover.
     *
     * The budget is the ask itself: "2 行最多在顶部占全屏 1/4 的高度，4 行的话最多占全屏 2/5
     * 的高度". It is a share of the *whole screen*, not of a band chosen first — the earlier
     * fixed band (96..700 at 1080p, 604px of 1080) let two rows reach 528px, "他们占了屏幕的
     * 一半高度", and no row count can be checked against the screen while the band is picked
     * independently of it.
     */
    fun bandFraction(tracks: Int): Float =
        if (tracks <= 2) TWO_ROW_SHARE else FOUR_ROW_SHARE

    /**
     * The height one line of text occupies, in the same units as the band.
     *
     * Scaled with the screen for the same reason the band is: the design's font sizes are
     * pixels *at 1080p*, and a layer whose rows shrink with the screen while its text does not
     * would overlap itself on a 720p television.
     */
    fun lineHeightPx(textPx: Float, heightPx: Float): Float =
        textPx * LINE_HEIGHT_FACTOR * (heightPx / 1080f)

    /**
     * Clamps a measured width to the design's ceiling, and reports what a truncation
     * would need: the overlay appends an ellipsis when the returned value is smaller
     * than what was asked for.
     */
    fun clampWidth(measuredPx: Float, screenWidthPx: Float): Float {
        val ceiling = screenWidthPx * DanmakuFeed.MAX_WIDTH_FRACTION
        return if (measuredPx > ceiling) ceiling else measuredPx.coerceAtLeast(1f)
    }

    /**
     * The y offset of a row's text, in pixels, from the top of the band.
     *
     * **The text sits in the middle of its row, not at the top of it.** A row is a share of
     * the band, and pinning the text to the top of that share is half of what put two rows up
     * against the top edge of the photograph and left the rest of the band empty. The other
     * half was the band itself — see [bandFraction].
     */
    fun trackTop(track: Int, pitchPx: Float, bandTopPx: Float, lineHeightPx: Float): Float =
        bandTopPx + track * pitchPx + (pitchPx - lineHeightPx) / 2f

    /**
     * The distance between two rows, in pixels: the band, shared between the rows in use.
     *
     * **The rows of a thread share its band, so they are packed rather than spread.** On a
     * 1080p screen `少` gives two rows 87px apart inside the top quarter, `多` four rows 84px
     * apart inside the top two fifths. The rows are not airier when there are fewer of them —
     * there are simply fewer, and the block stops higher up the picture.
     *
     * [tracks] is the setting's ceiling, never zero: the overlay does not draw this layer at
     * all when 图评 is off.
     */
    fun trackPitchPx(heightPx: Float, tracks: Int): Float {
        val band = band(heightPx, tracks)
        return (band.endInclusive - band.start) / tracks.coerceAtLeast(1)
    }

    /**
     * The band a thread laid out in [tracks] rows may draw in, in pixels.
     *
     * From the design's top safe area down to [bandFraction] of the screen's height: the top
     * quarter for the two rows `少` allows, the top two fifths for the four `多` allows. Both
     * ends are a share of the *screen*, which is the only way "2 行最多占全屏 1/4" can be
     * checked — the band this replaced was a fixed 604px of a 1080p screen (96..700), chosen
     * to clear the QR code and the information bar, and it let two rows reach 528px: half the
     * picture.
     *
     * The safe area is still honoured, so the ceiling is reached from 96px down rather than
     * from the very top edge. [tracks] defaults to the four-row case, which is also the
     * fullest the band ever gets.
     */
    fun band(heightPx: Float, tracks: Int = MAX_TRACKS): ClosedFloatingPointRange<Float> {
        val scale = heightPx / 1080f
        return (SAFE_TOP_AT_1080 * scale)..(heightPx * bandFraction(tracks))
    }
}

/**
 * How much longer a photo stays on screen because a comment arrived.
 *
 * The design's answer to the one real conflict in the layer: the slideshow's shortest
 * interval is 3 seconds and the longest danmaku takes 8.7. Slowing the danmaku down
 * instead would make a 3-second line unreadable; holding the photo is a small, visible,
 * explicable delay — "somebody said something about this one"
 * (docs/tuping-design.md §9.5).
 *
 * Pure, so the arithmetic is testable: the caller owns the timer.
 */
object StayExtension {

    /** The ceiling on one extension, in milliseconds. */
    const val MAX_EXTRA_MS = 6_000L

    /** The settlement time after a line finishes, in milliseconds. */
    const val TAIL_MS = 1_000L

    /**
     * How long [comment] needs to cross at [level] and [screenWidthPx], plus the tail.
     *
     * Deliberately *not* clamped: this is the honest figure, and clamping it here is what
     * made the first version of [extraMs] hand every photo less extra time than the ceiling
     * it was supposed to be allowed. The ceiling is applied once, where the decision is.
     */
    fun neededMs(comment: ReviewComment, level: DanmakuLevel, screenWidthPx: Float): Long {
        if (level == DanmakuLevel.OFF) return 0
        val width = DanmakuSpec.clampWidth(comment.widthPx(level.textPx), screenWidthPx)
        val crossingMs = ((screenWidthPx + width) / DanmakuFeed.SPEED_PX_PER_SECOND * 1000f).toLong()
        return crossingMs + TAIL_MS
    }

    /**
     * The extra time a photo should be held, given how long it has already been up and
     * how long the slideshow meant to keep it.
     *
     * Returns 0 when the photo has already had at least the time the comment needs —
     * extending a stay that is already long enough would make every photo wait for its
     * last comment forever.
     */
    fun extraMs(
        comment: ReviewComment,
        level: DanmakuLevel,
        screenWidthPx: Float,
        shownMs: Long,
        plannedMs: Long,
    ): Long {
        val needed = neededMs(comment, level, screenWidthPx)
        if (needed <= 0) return 0
        val remaining = plannedMs - shownMs
        // A photo with more time left than the comment needs is left alone. Clamping the
        // *difference* instead — which is what this did first — hands every photo without a
        // comment the full ceiling as "extra", and that is the whole slideshow slowing
        // down for nothing.
        if (remaining >= needed) return 0
        // The ceiling is applied here and only here: a comment longer than the design's
        // widest line needs more time than a photo is ever given, and the design's answer
        // is to let the photo advance and truncate the line.
        return (needed - remaining).coerceIn(0L, MAX_EXTRA_MS)
    }
}
