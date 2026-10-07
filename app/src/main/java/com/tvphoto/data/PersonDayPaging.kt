package com.tvphoto.data

import com.tvphoto.domain.TimelineDay

/** One day to fetch, plus how many of that day's photos to skip. */
internal data class DaySlice(val day: TimelineDay, val skip: Int)

/**
 * Chooses which days cover the requested [limit]/[offset] window of a person's
 * flattened photo stream, and reports whether anything follows.
 *
 * The person gallery can only be queried one day at a time, so a flat, paged grid has
 * to be reassembled from per-day counts. That arithmetic is where off-by-one errors
 * hide, so it is kept pure and away from the network in order to be tested directly.
 */
internal fun personDayWindow(
    days: List<TimelineDay>,
    limit: Int,
    offset: Int,
): Pair<List<DaySlice>, Boolean> {
    if (limit <= 0 || offset < 0) return emptyList<DaySlice>() to false

    val slices = mutableListOf<DaySlice>()
    var skip = offset
    var wanted = limit
    var hasMore = false

    for (day in days) {
        if (wanted <= 0) {
            // The window filled with days still left over.
            hasMore = true
            break
        }
        if (day.count <= 0) continue
        if (skip >= day.count) {
            skip -= day.count
            continue
        }

        slices += DaySlice(day, skip)
        wanted -= (day.count - skip)
        skip = 0
    }

    return slices to hasMore
}
