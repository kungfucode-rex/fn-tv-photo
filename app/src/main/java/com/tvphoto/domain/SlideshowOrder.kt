package com.tvphoto.domain

import kotlin.random.Random

/**
 * The picture a running slideshow moves to next, or null when there is nowhere to go.
 *
 * Videos are walked over rather than stopped on. A slideshow is a wall of pictures: a
 * clip in the middle of one halts the rotation — the viewer's timer does not run while a
 * video is on screen — and talks over the music playing underneath it. So the rotation
 * only ever holds photos, however many clips sit between them.
 *
 * [taken] holds what is already spoken for: the picture on screen, plus anything already
 * queued. On a slow link the interval can pass again before the queued picture has
 * landed, and advancing from the picture still on screen would queue the same one twice.
 *
 * [fromIndex] is where the walk starts — the end of the queue, not the screen — so
 * sequential playback keeps its order even while a page is in flight. Shuffled, the pick
 * is drawn from the photos rather than walked, so a run of clips in the library cannot
 * skew it towards the few photos around them; [random] is a parameter only so that a
 * test can pin the draw.
 *
 * A list whose only photo is the one on screen returns null rather than advancing to
 * itself for ever, and so does a library of nothing but clips.
 */
fun nextSlideshowItem(
    items: List<MediaItem>,
    fromIndex: Int,
    taken: Set<Long>,
    shuffled: Boolean,
    random: Random = Random.Default,
): MediaItem? {
    if (items.size <= 1) return null

    if (shuffled) {
        val pool = items.filterNot { it.isVideo || it.id in taken }
        if (pool.isEmpty()) return null
        return pool[random.nextInt(pool.size)]
    }

    for (step in 1 until items.size) {
        val index = ((fromIndex + step) % items.size + items.size) % items.size
        val item = items[index]
        if (!item.isVideo && item.id !in taken) return item
    }
    return null
}
