package com.tvphoto.domain

/**
 * The queue of photos between the one on screen and the pointer the user has walked to,
 * after one press of left ([delta] `-1`) or right (`+1`).
 *
 * A press moves a *pointer* along the list rather than appending work to a queue. What
 * comes back is the stretch of list that pointer now spans, so pressing right and then
 * left puts it back where it started — the stretch empties from its end — instead of
 * queueing a photo in each direction and reading as two leftward steps for a pointer
 * that never moved left at all.
 *
 * The photos in between are steps that were walked over, not photos that will be shown:
 * the viewer fetches the still at the **end** of this list and goes straight there. That
 * is why the whole queue comes back rather than a single target — how far the pointer
 * has been walked is what the corner arrows are made of, and three presses right are
 * three arrows, even though only one photo is on its way.
 *
 * Walking is always by position, even while the slideshow is shuffling: left and right
 * mean the neighbouring photo in the list, and a random neighbour would make the arrow
 * count a lie.
 *
 * The same list instance comes back when there is nowhere to walk — a library of one, or
 * an id the list no longer holds — so a press that cannot move anything does not
 * recompose the viewer.
 */
fun walkPagingPointer(
    items: List<MediaItem>,
    onScreen: Long,
    pending: List<Long>,
    rightward: Boolean,
    delta: Int,
): List<Long> {
    if (items.size <= 1 || delta == 0) return pending
    val from = pending.lastOrNull() ?: onScreen
    val at = items.indexOfFirst { it.id == from }
    if (at < 0) return pending

    // Walking back over a step the pointer had taken gives that step up, rather than
    // queueing the photo behind it: the pointer is where the queue ends.
    if (pending.isNotEmpty() && (delta > 0) != rightward) return pending.dropLast(1)

    val next = ((at + delta) % items.size + items.size) % items.size
    return pending + items[next].id
}
