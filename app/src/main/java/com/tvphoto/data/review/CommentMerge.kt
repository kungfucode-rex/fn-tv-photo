package com.tvphoto.data.review

/**
 * Merges what the server just said into the copy this app holds for one photo.
 *
 * **By id, replacing rather than only adding**, and that is the whole point: a comment is
 * not immutable. An admin edits it, a moderator holds it or deletes it, and the server says
 * so down the same live socket a new comment arrives on (docs/tuping-design.md §17.16).
 *
 * Adding only what was not already known — which is what this did at first — made every one
 * of those changes invisible on the television. The deleted comment stayed in the cache
 * with `status = 1`, so [DanmakuFeed] went on replaying it for as long as the photo was on
 * screen, and the history call would never correct it either: that call asks for
 * `since = the newest comment already held`, and an edit or a delete does not move that.
 * Reported as "网页上删了评论，但是电视应用端正在显示的评论不会消失，还在一直展示".
 *
 * The order is by `createdAt`, because that is the order the layer replays in, and a
 * replaced comment keeps its place in it. The window is trimmed from the front for the
 * reason the design keeps a window rather than a transcript (§9.5): the layer replays the
 * last few, and the rest is what the phone page is for.
 *
 * Pure on purpose — a list in, the same list changed, no Android and no coroutines — so the
 * rule can be asserted without a screen or a server (`CommentMergeTest`).
 */
internal fun mergeCommentStates(
    known: MutableList<ReviewComment>,
    fresh: List<ReviewComment>,
    limit: Int,
) {
    if (fresh.isEmpty()) return

    // Where each id sits, built once: the socket can deliver one comment at a time, and a
    // scan per arrival would make a busy thread quadratic.
    val positions = HashMap<String, Int>(known.size * 2)
    known.forEachIndexed { index, comment -> positions[comment.id] = index }

    for (comment in fresh) {
        val at = positions[comment.id]
        if (at != null) {
            // The same comment, as the server now describes it — new words, or a status
            // that says the screen may no longer draw it.
            known[at] = comment
        } else {
            positions[comment.id] = known.size
            known.add(comment)
        }
    }

    known.sortBy { it.createdAt }
    while (known.size > limit) known.removeAt(0)
}
