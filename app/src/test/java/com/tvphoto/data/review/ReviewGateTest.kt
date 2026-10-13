package com.tvphoto.data.review

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the album end does about 图评 for the photo on screen.
 *
 * Nothing here is visible on a television — that is the whole point of asserting it. The
 * three answers are the difference between an album that stays in somebody's own library and
 * one that arrives in a stranger's database without anybody asking: `HIDDEN` never speaks to
 * the server, `LOOKUP` asks and shows whatever comes back, and `REGISTER` is the only one
 * that uploads a photo nobody pressed anything on (docs/tuping-design.md §17.28).
 *
 * The case that must *not* change is the screensaver: it is an album under 弹幕, and 弹幕
 * cannot exist without the photo being on the server — so a screensaver asks, it just does
 * not upload by itself.
 *
 * Since §17.29 the viewer's corner has one button — 扫码评论 — which is the act that
 * registers the photo on screen; the danmaku's amount, `off` included, is a preference in
 * 设置 → 图评设置. One of the facts this function used to take (`showDanmaku`) is gone with it.
 */
class ReviewGateTest {

    /** Every fact at its shipping default, so each test changes exactly one of them. */
    private fun decision(
        isVideo: Boolean = false,
        mode: ReviewMode = ReviewMode.SLIDESHOW,
        serverUrl: String = "http://192.168.1.4:8080",
        danmakuLevel: DanmakuLevel = DanmakuLevel.FEW,
        screenSaver: Boolean = false,
        slideshow: Boolean = false,
        slideshowEnabled: Boolean = false,
    ) = reviewDecision(
        isVideo, mode, serverUrl, danmakuLevel,
        screenSaver, slideshow, slideshowEnabled,
    )

    /**
     * The ordinary answer, and the whole of §17.28: looking at a photo asks the server about
     * it and uploads nothing.
     */
    @Test
    fun `a photo opened by hand is looked up, never uploaded`() {
        assertEquals(ReviewDecision.LOOKUP, decision())
        // And the danmaku's amount changes nothing about that: the switches decide what is
        // *drawn*, and the act that puts a picture on the server is a person pressing
        // 扫码评论 on it.
        assertEquals(ReviewDecision.LOOKUP, decision(danmakuLevel = DanmakuLevel.MANY))
        assertEquals(ReviewDecision.LOOKUP, decision(danmakuLevel = DanmakuLevel.OFF))
    }

    /** A fresh install's show is the same: playing photos does not publish them. */
    @Test
    fun `a show whose 图评 switch is off registers nothing`() {
        assertEquals(ReviewDecision.LOOKUP, decision(slideshow = true, slideshowEnabled = false))
    }

    /** The party rotation: the host asked for the whole show, once. */
    @Test
    fun `a show that carries 图评 registers`() {
        assertEquals(ReviewDecision.REGISTER, decision(slideshow = true, slideshowEnabled = true))
    }

    /** A screensaver is a show, so its own switch governs it too. */
    @Test
    fun `a screensaver follows the show switch`() {
        assertEquals(
            ReviewDecision.REGISTER,
            decision(screenSaver = true, slideshow = true, slideshowEnabled = true),
        )
        assertEquals(
            ReviewDecision.LOOKUP,
            decision(screenSaver = true, slideshow = true, slideshowEnabled = false),
        )
    }

    /**
     * A screensaver draws no code — a code is addressed to somebody standing in front of the
     * television with a phone, and a screensaver is on because nobody is there — so with the
     * danmaku off there is nothing to ask about, and the photo is not even looked up.
     *
     * `off` is the amount's word for it now, and the only one: the boolean that used to sit
     * beside the amount was deleted in §17.29, because two switches could both mean "draw
     * nothing" and only one of them was visible on the row.
     */
    @Test
    fun `a screensaver with nothing to draw does not ask at all`() {
        assertEquals(
            ReviewDecision.HIDDEN,
            decision(screenSaver = true, danmakuLevel = DanmakuLevel.OFF),
        )
    }

    /** The same screen with a danmaku layer *does* ask: that is what an album of comments is. */
    @Test
    fun `a screensaver with danmaku asks`() {
        assertEquals(ReviewDecision.LOOKUP, decision(screenSaver = true))
    }

    /** A viewer keeps looking a photo up even with the danmaku off: its code may be armed. */
    @Test
    fun `a viewer asks even with the danmaku off`() {
        assertEquals(ReviewDecision.LOOKUP, decision(danmakuLevel = DanmakuLevel.OFF))
    }

    @Test
    fun `the mode, the address and the media kind are the outer gate`() {
        assertEquals(ReviewDecision.HIDDEN, decision(mode = ReviewMode.OFF))
        assertEquals(ReviewDecision.HIDDEN, decision(serverUrl = ""))
        assertEquals(ReviewDecision.HIDDEN, decision(serverUrl = "   "))
        assertEquals(ReviewDecision.HIDDEN, decision(isVideo = true))
        // Even a party show cannot publish anything with no server to publish it to.
        assertEquals(
            ReviewDecision.HIDDEN,
            decision(mode = ReviewMode.OFF, slideshow = true, slideshowEnabled = true),
        )
    }

    /** 仅幻灯片 still leaves a photo opened by hand eligible: the menu governs the display. */
    @Test
    fun `a photo opened by hand is eligible in slideshow mode`() {
        assertEquals(ReviewDecision.LOOKUP, decision(mode = ReviewMode.SLIDESHOW))
        assertEquals(ReviewDecision.LOOKUP, decision(mode = ReviewMode.ALL))
    }

    /** One switch, three words: `off` is "draw nothing", and the amount is how much. */
    @Test
    fun `danmaku is drawn unless the amount is off`() {
        assertEquals(true, danmakuDrawn(DanmakuLevel.FEW))
        assertEquals(true, danmakuDrawn(DanmakuLevel.MANY))
        assertEquals(false, danmakuDrawn(DanmakuLevel.OFF))
    }
}
