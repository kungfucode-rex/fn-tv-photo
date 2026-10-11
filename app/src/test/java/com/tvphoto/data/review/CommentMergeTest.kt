package com.tvphoto.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that keeps a television's copy of a thread honest.
 *
 * A comment is not immutable: it can be edited, held for review, blocked or deleted, and the
 * server says so on the live socket (docs/tuping-design.md §17.16). The client's whole rule
 * is "replace the copy I hold by id", and the case the last test here pins is the one a
 * person reported: before this, a deleted comment stayed in the cache with `status = 1` and
 * the danmaku layer replayed it for as long as the photo was on screen.
 */
class CommentMergeTest {

    private val limit = 50

    private fun comment(
        id: String,
        text: String = "这张好看",
        createdAt: Long = 1_730_000_000_000,
        status: Int = DanmakuFeed.VISIBLE_STATUS,
    ) = ReviewComment(id = id, nickname = "小王", content = text, createdAt = createdAt, status = status)

    @Test
    fun `a comment nobody has seen is appended`() {
        val known = mutableListOf(comment("a"))
        mergeCommentStates(known, listOf(comment("b", createdAt = 1_730_000_001_000)), limit)

        assertEquals(listOf("a", "b"), known.map { it.id })
    }

    @Test
    fun `the same id is replaced, not duplicated`() {
        val known = mutableListOf(comment("a", text = "原来那句话"))
        mergeCommentStates(known, listOf(comment("a", text = "改过的那句话")), limit)

        assertEquals(1, known.size)
        assertEquals("改过的那句话", known[0].content)
    }

    /**
     * The reported bug, as an assertion. A deleted comment arrives as the same id with
     * `status = 3`; what the layer draws from is the status, so the replacement is what
     * takes the line off the screen.
     */
    @Test
    fun `a deleted copy replaces the visible one, so there is nothing left to draw`() {
        val known = mutableListOf(comment("a"), comment("b"))
        mergeCommentStates(
            known,
            listOf(comment("a", status = 3)),
            limit,
        )

        assertEquals(2, known.size)
        assertEquals(3, known.first { it.id == "a" }.status)
        assertEquals(
            listOf("b"),
            known.filter { it.status == DanmakuFeed.VISIBLE_STATUS }.map { it.id },
        )
    }

    /** A hidden copy of an old comment with a newer timestamp still sorts by the timestamp. */
    @Test
    fun `the list stays ordered by createdAt after a replacement`() {
        val known = mutableListOf(
            comment("a", createdAt = 1_000),
            comment("c", createdAt = 3_000),
        )
        mergeCommentStates(known, listOf(comment("b", createdAt = 2_000)), limit)

        assertEquals(listOf("a", "b", "c"), known.map { it.id })
    }

    @Test
    fun `the window keeps the newest and drops the oldest`() {
        val known = mutableListOf<ReviewComment>()
        mergeCommentStates(
            known,
            (1..5).map { comment("c$it", createdAt = it * 1_000L) },
            3,
        )

        assertEquals(listOf("c3", "c4", "c5"), known.map { it.id })
    }

    @Test
    fun `an empty answer changes nothing`() {
        val known = mutableListOf(comment("a"))
        mergeCommentStates(known, emptyList(), limit)

        assertEquals(listOf("a"), known.map { it.id })
    }

    /**
     * The other half of the reported symptom: a line already crossing finishes its pass.
     * Nothing in the cache can stop it — the overlay's replay loop notices the list, the
     * feed does not — so what is asserted here is only that the comment is gone from the
     * list a *new* replay would use.
     */
    @Test
    fun `a deleted comment is gone from the list a new replay is built from`() {
        val known = mutableListOf(comment("a"), comment("b"))
        mergeCommentStates(known, listOf(comment("a", status = 3)), limit)

        val replay = known.filter { it.status == DanmakuFeed.VISIBLE_STATUS }.takeLast(5)
        assertTrue(replay.none { it.id == "a" })
        assertEquals(1, replay.size)
    }
}
