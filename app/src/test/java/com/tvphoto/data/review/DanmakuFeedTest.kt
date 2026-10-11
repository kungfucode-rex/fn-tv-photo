package com.tvphoto.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The danmaku layer's arithmetic, asserted without a screen.
 *
 * Everything here is about the numbers the layer fixes — a constant speed, a 45%-of-screen
 * width ceiling, a lane that carries several comments with a readable gap between them, how
 * many lanes a thread is laid out in, and the five-second floor on a comment coming round
 * again — because those are what decide whether a television can draw this at all, and
 * whether the layer reads as a few lines of commentary or as a wall of text.
 */
class DanmakuFeedTest {

    private val screenWidth = 1920f

    private fun comment(id: String, text: String = "这张好看") = ReviewComment(
        id = id,
        nickname = "小王",
        content = text,
        createdAt = 1_730_000_000_000,
        status = 1,
    )

    private fun feed(level: DanmakuLevel = DanmakuLevel.FEW) = DanmakuFeed(level, screenWidth)

    @Test
    fun `off draws nothing`() {
        val feed = feed(DanmakuLevel.OFF)
        assertFalse(feed.enabled)
        assertFalse(feed.offer(comment("a"), 0))
        assertTrue(feed.onScreen.isEmpty())
    }

    @Test
    fun `a hidden comment is never drawn`() {
        val feed = feed()
        val pending = comment("held").copy(status = 0)
        assertFalse(feed.offer(pending, 0))
        assertTrue(feed.onScreen.isEmpty())
    }

    @Test
    fun `an empty comment is never drawn`() {
        val feed = feed()
        assertFalse(feed.offer(comment("blank", text = "   "), 0))
    }

    @Test
    fun `a line starts at the right edge and moves left at the design's speed`() {
        val feed = feed()
        assertTrue(feed.offer(comment("a"), 0))

        val line = feed.onScreen.single()
        assertEquals(screenWidth, line.leftX(0, screenWidth), 0.5f)

        // One second of travel is exactly the constant the design fixes.
        val afterOneSecond = line.leftX(1_000_000_000L, screenWidth)
        assertEquals(screenWidth - DanmakuFeed.SPEED_PX_PER_SECOND, afterOneSecond, 0.5f)

        // And it is still moving at the same rate a second later: constant, not eased.
        val afterTwoSeconds = line.leftX(2_000_000_000L, screenWidth)
        assertEquals(
            afterOneSecond - DanmakuFeed.SPEED_PX_PER_SECOND,
            afterTwoSeconds,
            0.5f,
        )
    }

    @Test
    fun `a line survives a full crossing plus its own width`() {
        val feed = feed()
        feed.offer(comment("a"), 0)
        val line = feed.onScreen.single()

        // 8 727 ms rather than the design's 6 000: the speed came down from 320 px/s to 220
        // when 稍慢一点 was asked for (twice), and this is the screen crossing that bought.
        val crossingMs = (screenWidth / DanmakuFeed.SPEED_PX_PER_SECOND * 1000).toLong()
        assertEquals(8727L, crossingMs)

        // The line is finished once its *tail* has left, which is one line-width later.
        val tailNanos = (line.widthPx / DanmakuFeed.SPEED_PX_PER_SECOND * 1e9f).toLong()
        assertFalse(line.finished(crossingMs * 1_000_000 + tailNanos - 1_000_000, screenWidth))
        assertTrue(line.finished(crossingMs * 1_000_000 + tailNanos + 1_000_000, screenWidth))
    }

    @Test
    fun `a long line is clamped to 45 percent of the screen`() {
        val feed = feed(DanmakuLevel.MANY)
        // Longer than any comment the protocol now accepts (20 characters, which fits — see
        // the server's `guard.MaxContentRunes`). The clamp is what keeps a comment stored
        // before that limit came down from running across the picture.
        val long = comment("long", text = "字".repeat(80))
        feed.offer(long, 0)

        val line = feed.onScreen.single()
        assertEquals(screenWidth * DanmakuFeed.MAX_WIDTH_FRACTION, line.widthPx, 1f)
    }

    @Test
    fun `only one line enters a row at a time, and a third waits`() {
        val feed = feed(DanmakuLevel.FEW)
        // Two rows on 少: the first two lines take one each.
        repeat(DanmakuLevel.FEW.tracks) { index -> feed.offer(comment("c$index"), 0) }
        assertEquals(DanmakuLevel.FEW.tracks, feed.onScreen.size)

        // The third has nowhere to go *yet* and waits rather than sharing a row on top of
        // the line already there.
        feed.offer(comment("third"), 0)
        assertEquals(DanmakuLevel.FEW.tracks, feed.onScreen.size)
        assertEquals(1, feed.queued)
    }

    /**
     * The ask this answers: "多的时候就显示 4 行，每行会有多个评论" — and the complaint behind
     * it, "5 个评论就会用 5 行".
     */
    @Test
    fun `a thread longer than the band shares the rows it has`() {
        val visible = 8
        val feed = DanmakuFeed(DanmakuLevel.MANY, screenWidth)
        repeat(visible) { index -> feed.offer(comment("c$index"), 0) }

        // Eight comments, four rows in use, four waiting — never eight rows.
        assertEquals(DanmakuLevel.MANY.tracks, feed.onScreen.size)
        assertEquals(visible - DanmakuLevel.MANY.tracks, feed.queued)
        assertTrue(feed.onScreen.all { it.track < DanmakuLevel.MANY.tracks })

        // And the waiting ones join rows that already carry a comment, rather than opening a
        // fifth row: which is what "每行会有多个评论" means.
        feed.advance(6_000_000_000L)
        assertEquals(visible, feed.onScreen.size)
        assertEquals(DanmakuLevel.MANY.tracks, feed.onScreen.map { it.track }.distinct().size)
    }

    /**
     * The rule, as asked for: "图评的弹幕数量设置为少，则最多显示 2 行，设置为多，最多显示 4 行".
     *
     * A three-comment thread is the case this was reported on — three rows meant the screen
     * looked exactly like the per-comment layout the change was meant to replace.
     */
    @Test
    fun `the setting is the ceiling on rows, two or four`() {
        assertEquals(0, DanmakuLevel.OFF.tracks)
        assertEquals(2, DanmakuLevel.FEW.tracks)
        assertEquals(4, DanmakuLevel.MANY.tracks)

        // And the feed draws on that many rows, not on the grid's four.
        assertEquals(2, DanmakuFeed(DanmakuLevel.FEW, screenWidth).tracks)
        assertEquals(4, DanmakuFeed(DanmakuLevel.MANY, screenWidth).tracks)
    }

    /**
     * The shape behind that rule: with two rows, three comments are two lines of text — the
     * first and third share a row, 2.4 seconds apart, and nothing waits.
     */
    @Test
    fun `three comments in two rows are two lines, not three`() {
        val feed = DanmakuFeed(DanmakuLevel.FEW, screenWidth)
        val beat = DanmakuFeed.REPLAY_STAGGER_MS * 1_000_000
        repeat(3) { index -> feed.offer(comment("c$index"), index * beat) }

        assertEquals(3, feed.onScreen.size)
        assertEquals(0, feed.queued)
        assertEquals(listOf(0, 1, 0), feed.onScreen.map { it.track })
        assertEquals(2, feed.onScreen.map { it.track }.distinct().size)
    }

    @Test
    fun `the row count is clamped to the grid`() {
        assertEquals(DanmakuSpec.MAX_TRACKS, DanmakuFeed(DanmakuLevel.FEW, screenWidth, 99).tracks)
        assertEquals(1, DanmakuFeed(DanmakuLevel.FEW, screenWidth, 0).tracks)
    }

    /**
     * The gap is what keeps two comments in one lane from reading as one run-on sentence:
     * "不能挨得太紧，不能重叠".
     *
     * Both lines move at the same speed, so the distance between them is fixed the moment
     * the second one enters — which is why the entry test *is* the overlap test.
     */
    @Test
    fun `two comments in one lane hold their gap for the whole crossing`() {
        val feed = DanmakuFeed(DanmakuLevel.FEW, screenWidth, tracks = 1)
        feed.offer(comment("first", text = "第一句"), 0)
        val first = feed.onScreen.single()

        // A lane is free again once its tail has cleared the entry point by the gap.
        val entryNanos = ((first.widthPx + DanmakuFeed.TRACK_GAP_PX) /
            DanmakuFeed.SPEED_PX_PER_SECOND * 1e9f).toLong()

        // One millisecond early it waits, however long the queue then takes to drain.
        feed.offer(comment("second", text = "第二句"), entryNanos - 1_000_000)
        feed.advance(entryNanos - 1_000_000)
        assertEquals(1, feed.onScreen.size)

        // On time, it enters, and the clear picture between the two is exactly the gap.
        feed.advance(entryNanos)
        assertEquals(2, feed.onScreen.size)
        val second = feed.onScreen.first { it.comment.id == "second" }
        assertEquals(0, second.track)
        assertEquals(
            DanmakuFeed.TRACK_GAP_PX,
            second.leftX(entryNanos, screenWidth) - (first.leftX(entryNanos, screenWidth) + first.widthPx),
            0.5f,
        )

        // Still exactly the gap a second later: no drift, no closing up.
        val later = entryNanos + 1_000_000_000L
        assertEquals(
            DanmakuFeed.TRACK_GAP_PX,
            second.leftX(later, screenWidth) - (first.leftX(later, screenWidth) + first.widthPx),
            0.5f,
        )
    }

    /**
     * The budget, as asked for: "2 行最多在顶部占全屏 1/4 的高度，4 行的话最多占全屏 2/5 的
     * 高度" — with the rows packed inside that budget rather than spread down the picture, and
     * each line centred in its own row.
     *
     * A 1080p screen, because every number in this layer is written in those pixels.
     */
    @Test
    fun `two rows stay in the top quarter and four in the top two fifths`() {
        val screen = 1080f

        // 少: two rows inside the top 270px.
        val quietPitch = DanmakuSpec.trackPitchPx(screen, DanmakuLevel.FEW.tracks)
        val quietLine = DanmakuSpec.lineHeightPx(DanmakuLevel.FEW.textPx, screen)
        val bandTop = DanmakuSpec.band(screen, DanmakuLevel.FEW.tracks).start
        val lastQuiet = DanmakuSpec.trackTop(1, quietPitch, bandTop, quietLine) + quietLine
        assertTrue("two rows reached ${lastQuiet}px of 1080", lastQuiet <= screen / 4)
        // And they are not pinned to the top edge either.
        assertTrue(DanmakuSpec.trackTop(0, quietPitch, bandTop, quietLine) > bandTop + 10f)

        // 多: four rows inside the top 432px.
        val busyPitch = DanmakuSpec.trackPitchPx(screen, DanmakuLevel.MANY.tracks)
        val busyLine = DanmakuSpec.lineHeightPx(DanmakuLevel.MANY.textPx, screen)
        val busyTop = DanmakuSpec.band(screen, DanmakuLevel.MANY.tracks).start
        val lastBusy = DanmakuSpec.trackTop(3, busyPitch, busyTop, busyLine) + busyLine
        assertTrue("four rows reached ${lastBusy}px of 1080", lastBusy <= screen * 0.4f)

        // Four rows are given more room than two, and both blocks start at the safe area.
        assertTrue(busyPitch < quietPitch * 1.1f)
        assertEquals(bandTop, busyTop, 0.01f)
    }

    /**
     * Comments take the lanes in turn rather than piling onto the lowest one.
     *
     * The report behind this: choosing the lowest lane with room put every comment on lane 0
     * the moment the one before it had cleared the entry point — "六条轨道，一行文字". Taking
     * them in turn gives a thread the band's shape, and the comment *after* the last lane
     * comes back round to lane 0 — the sharing the band exists for.
     */
    @Test
    fun `comments take the rows in turn`() {
        val feed = feed(DanmakuLevel.MANY)
        val beat = DanmakuFeed.REPLAY_STAGGER_MS * 1_000_000
        repeat(DanmakuLevel.MANY.tracks) { index -> feed.offer(comment("c$index"), index * beat) }

        assertEquals(listOf(0, 1, 2, 3), feed.onScreen.map { it.track })
    }

    @Test
    fun `a queue that overflows drops the oldest`() {
        val feed = feed(DanmakuLevel.MANY)
        // Fill every row, then the queue, then one more.
        repeat(DanmakuLevel.MANY.tracks) { feed.offer(comment("fill$it"), 0) }
        repeat(DanmakuFeed.MAX_QUEUE) { index -> feed.offer(comment("q$index"), 0) }

        assertEquals(DanmakuFeed.MAX_QUEUE, feed.queued)
        feed.offer(comment("overflow"), 0)

        assertEquals(DanmakuFeed.MAX_QUEUE, feed.queued)
        assertEquals(1, feed.dropped)
    }

    @Test
    fun `the same comment is not drawn twice in one visit`() {
        val feed = feed()
        val once = comment("same")
        assertTrue(feed.offer(once, 0))
        assertFalse(feed.offer(once, 0))
        assertEquals(1, feed.onScreen.size)
    }

    /**
     * The floor the loop asked for: "同一个弹幕间隔时间不能小于 5s".
     *
     * A narrow screen, because that is the only way the cooldown is the reason a line was
     * refused: on a 1920px screen a crossing already takes longer than five seconds, so the
     * line would still be on screen. 400px at 260 px/s is just over two.
     */
    @Test
    fun `a comment that has left the screen still waits out its cooldown`() {
        val feed = DanmakuFeed(DanmakuLevel.FEW, 400f)
        val once = comment("same")
        assertTrue(feed.offer(once, 0))

        // Gone by about 2.1s, and refused at 3s all the same.
        feed.advance(3_000_000_000L)
        assertTrue("the line should have crossed by now", feed.onScreen.isEmpty())
        assertFalse("3s after it appeared is inside the 5s floor", feed.offer(once, 3_000_000_000L))

        // And allowed again the moment the floor is up.
        val fiveSeconds = DanmakuFeed.REPEAT_COOLDOWN_MS * 1_000_000
        assertFalse(feed.canShow(once, fiveSeconds - 1))
        assertTrue(feed.canShow(once, fiveSeconds))
        assertTrue(feed.offer(once, fiveSeconds))
    }

    /** The floor is a property of one photo's layer, not of the visit. */
    @Test
    fun `changing the photo clears the repeat cooldown`() {
        val feed = DanmakuFeed(DanmakuLevel.FEW, 400f)
        val once = comment("same")
        feed.offer(once, 0)
        assertFalse(feed.offer(once, 1_000_000_000L))

        feed.reset()
        assertTrue(feed.offer(once, 1_000_000_001L))
    }

    @Test
    fun `advancing retires finished lines and starts waiting ones`() {
        val feed = feed(DanmakuLevel.FEW)
        feed.offer(comment("a"), 0)
        assertTrue(feed.onScreen.isNotEmpty())

        // Long enough for everything on screen to have left it.
        val farFuture = 30_000_000_000L
        assertFalse(feed.advance(farFuture))
        assertTrue(feed.onScreen.isEmpty())
    }

    @Test
    fun `changing the photo clears everything`() {
        val feed = feed()
        feed.offer(comment("a"), 0)
        repeat(DanmakuSpec.MAX_TRACKS) { feed.offer(comment("b$it"), 0) }
        assertTrue(feed.onScreen.isNotEmpty())

        feed.reset()
        assertTrue(feed.onScreen.isEmpty())
        assertEquals(0, feed.queued)
    }
}

/**
 * The "hold the photo" arithmetic.
 *
 * This is the design's answer to the one real conflict in the layer: the slideshow's
 * shortest interval is three seconds, and a danmaku line takes seven and a half to cross
 * at the speed the layer actually runs at — twelve for the longest one the ceiling allows.
 * The ceiling is what stops a busy photo from never advancing.
 */
class StayExtensionTest {

    private val screenWidth = 1920f

    private fun comment(text: String = "这张好看") = ReviewComment(
        id = "c",
        nickname = "小王",
        content = text,
        createdAt = 0,
        status = 1,
    )

    @Test
    fun `a short comment needs one crossing plus the tail`() {
        // The width is clamped per line, so a one-character line is one screen crossing
        // plus a little: (1920 + 34) at 220 px/s, plus the settlement second.
        val needed = StayExtension.neededMs(comment("短"), DanmakuLevel.FEW, screenWidth)
        assertTrue("needed was $needed", needed in 9_700..10_000)
    }

    @Test
    fun `the longest allowed line needs nearly fourteen seconds`() {
        val longest = "字".repeat(80)
        val needed = StayExtension.neededMs(comment(longest), DanmakuLevel.MANY, screenWidth)
        // Eighty characters is past anything the protocol accepts now (20 fits on a line at
        // this font: 20 × 40 = 800px of the 864px ceiling), and it is kept as the worst case
        // the layer can still be handed — a comment stored before the limit came down. 3 200px
        // of text is clamped to 864: (1920 + 864) at 220 px/s is about 12.7 seconds, plus the
        // tail. It cannot fit a 3-second slideshow at the +6s ceiling, nor an 8-second one at
        // the very edge — the design's answer is unchanged: the photo advances and the line is
        // left unfinished.
        assertTrue("needed was $needed", needed in 13_500..13_800)
    }

    /**
     * What the protocol's own limit comes to, at the larger font: twenty characters fit on a
     * line, so a comment the server accepts is never truncated by the 45% ceiling.
     */
    @Test
    fun `a comment at the protocol's limit fits on one line`() {
        val atLimit = "字".repeat(20)
        val width = DanmakuSpec.clampWidth(
            comment(atLimit).widthPx(DanmakuLevel.MANY.textPx),
            screenWidth,
        )
        assertTrue(
            "20 characters measured ${width}px against a ${screenWidth * DanmakuFeed.MAX_WIDTH_FRACTION}px ceiling",
            width < screenWidth * DanmakuFeed.MAX_WIDTH_FRACTION,
        )
    }

    @Test
    fun `danmaku off means no extension at all`() {
        assertEquals(0L, StayExtension.neededMs(comment(), DanmakuLevel.OFF, screenWidth))
        assertEquals(
            0L,
            StayExtension.extraMs(comment(), DanmakuLevel.OFF, screenWidth, 0, 3000),
        )
    }

    @Test
    fun `a photo whose remaining time is already enough is not extended`() {
        // The slideshow means to keep this one for 15 seconds and it has only been up for
        // one: there is more time left than a danmaku needs, so nothing has to be held.
        val extra = StayExtension.extraMs(comment("短"), DanmakuLevel.FEW, screenWidth, 1_000, 15_000)
        assertEquals(0L, extra)
    }

    @Test
    fun `a photo with just enough time left is not extended`() {
        // The exact boundary: ten seconds left is more than one short line needs (9 881),
        // so the photo is left to advance on its own schedule.
        val extra = StayExtension.extraMs(comment("短"), DanmakuLevel.FEW, screenWidth, 0, 10_000)
        assertEquals(0L, extra)
    }

    @Test
    fun `an extension is clamped to the ceiling`() {
        // A photo with 100 ms left needs a whole crossing, which is more than the design
        // allows; the answer is the ceiling, not the true figure.
        val needed = StayExtension.neededMs(comment("短"), DanmakuLevel.FEW, screenWidth)
        val extra = StayExtension.extraMs(comment("短"), DanmakuLevel.FEW, screenWidth, 2_900, 3_000)
        assertEquals(StayExtension.MAX_EXTRA_MS, extra)
        assertTrue("the ceiling should be doing the work here", needed - 100 > StayExtension.MAX_EXTRA_MS)
    }

    @Test
    fun `a photo three seconds in is extended`() {
        val extra = StayExtension.extraMs(comment("短"), DanmakuLevel.FEW, screenWidth, 0, 3_000)
        assertTrue("extra was $extra", extra in 1..StayExtension.MAX_EXTRA_MS)
    }
}
