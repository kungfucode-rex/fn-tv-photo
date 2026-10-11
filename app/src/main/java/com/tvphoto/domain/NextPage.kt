package com.tvphoto.domain

/**
 * How close to the end of the loaded list the next page starts being fetched.
 *
 * Eight, and one number for both callers. The grid kept its own copy of it
 * (`PREFETCH_DISTANCE`) while the viewer had no such rule at all, and that asymmetry is how a
 * slideshow came to loop over the first page of a 217-photo album: "播放五六十张之后，又从第
 * 一张开始播放。后边其实还有很多照片".
 */
const val LOAD_MORE_DISTANCE = 8

/**
 * Whether the list behind a grid or a viewer has to fetch its next page now.
 *
 * [showRunning] is the one case position cannot decide. A shuffled show draws its next photo
 * from anywhere in the list, so "the pointer is near the end" is a test that fires by luck, and
 * what has to be loaded is the album the show is walking. A running show therefore asks for the
 * next page as soon as the previous one lands, until the list ends — the same pages, in the
 * same order, that the grid would have loaded had somebody scrolled that far.
 */
fun needsNextPage(currentIndex: Int, itemCount: Int, showRunning: Boolean): Boolean {
    if (itemCount <= 0) return false
    if (showRunning) return true
    return currentIndex >= itemCount - LOAD_MORE_DISTANCE
}
