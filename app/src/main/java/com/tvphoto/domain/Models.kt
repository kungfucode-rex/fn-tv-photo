package com.tvphoto.domain

import java.util.Calendar

/** A single photo, video or Live Photo entry in the NAS gallery. */
data class MediaItem(
    val id: Long,
    val name: String,
    val isVideo: Boolean,
    val isLivePhoto: Boolean,
    val thumbnailUrl: String?,
    val fullUrl: String?,
    val videoUrl: String?,
    val takenAt: String?,
    val width: Int,
    val height: Int,
    val fileSize: Long,
) {
    /** Live Photos only get their motion badge when the clip is actually indexed. */
    val hasMotionClip: Boolean get() = isLivePhoto && !videoUrl.isNullOrBlank()

    /**
     * The still the viewer draws for this entry.
     *
     * [original] picks the file itself over the server's ~1920px thumbnail, which is
     * what the preview setting decides — see `PreviewOriginal`. Each falls back to the
     * other, because a server that sent only one of the two still has a picture to show.
     *
     * A clip has no still of its own: the player draws the video over this, so its
     * answer is the thumbnail either way.
     */
    fun stillUrl(original: Boolean): String? = when {
        isVideo -> thumbnailUrl
        original -> fullUrl ?: thumbnailUrl
        else -> thumbnailUrl ?: fullUrl
    }

    val bestVideoUrl: String? get() = videoUrl ?: fullUrl

    /**
     * Whether [url] is this entry's own file rather than a thumbnail of it.
     *
     * A clip never is: what plays for one is the video itself, not a still, so there is no
     * still to call the original.
     */
    fun isOriginalStill(url: String?): Boolean = !isVideo && url != null && url == fullUrl

    /**
     * True when there is an original worth fetching — one the server sent, and one that is
     * not the thumbnail itself.
     *
     * A NAS that answers with the same URL for both tiers has nothing to move to, and a
     * button offering to fetch it would do nothing when pressed.
     */
    val hasDistinctOriginal: Boolean
        get() = !isVideo && fullUrl != null && fullUrl != thumbnailUrl

    val aspectRatio: Float
        get() = if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 16f / 9f
}

/**
 * One row of the timeline: a year and the months inside it.
 *
 * The row renders its own months rather than pushing a second screen, so the year
 * label is all that is left of the old year list.
 */
data class TimelineYear(
    val year: Int,
    val count: Int,
    val months: List<TimelineMonth>,
) {
    val id: String get() = year.toString()
}

/**
 * Builds the timeline's year rows: one per year, newest first, with that year's
 * months newest first inside it.
 *
 * Months with no items are dropped. The endpoint sums day buckets, so a zero-count
 * month can come back, and a card for it would be a dead end with no cover image —
 * the row simply skips it.
 */
fun timelineYears(months: List<TimelineMonth>): List<TimelineYear> = months
    .filter { it.count > 0 }
    .groupBy { it.year }
    .map { (year, inYear) ->
        TimelineYear(
            year = year,
            count = inYear.sumOf { it.count },
            months = inYear.sortedByDescending { it.month },
        )
    }
    .sortedByDescending { it.year }

/**
 * One day bucket.
 *
 * Not used by the main timeline (which aggregates to months), but required by the
 * per-person gallery, whose list endpoint only accepts a single day at a time.
 */
data class TimelineDay(
    val year: Int,
    val month: Int,
    val day: Int,
    val count: Int,
) {
    val id: String get() = "%04d-%02d-%02d".format(year, month, day)

    /** The `YYYY:MM:DD` prefix the gallery API expects (note the colons). */
    val apiDate: String get() = "%04d:%02d:%02d".format(year, month, day)

    val startParam: String get() = "$apiDate 00:00:00"
    val endParam: String get() = "$apiDate 23:59:59"
}

/**
 * One month bucket on the timeline.
 *
 * The gallery exposes its timeline as individual days; they are aggregated into
 * months here because a day-per-row list is dozens of rows of scrolling on a TV.
 */
data class TimelineMonth(
    val year: Int,
    val month: Int,
    val count: Int,
) {
    val id: String get() = "%04d-%02d".format(year, month)

    /** First instant of the month, in the `YYYY:MM:DD HH:MM:SS` form the API wants. */
    val startParam: String get() = "%04d:%02d:01 00:00:00".format(year, month)

    /**
     * Last instant of the month.
     *
     * `getList` silently drops everything past `end_time`, so this has to be the last
     * second of the last day — which needs the real month length rather than a
     * hard-coded 31, or February and 30-day months would lose their tail.
     */
    val endParam: String
        get() {
            val calendar = Calendar.getInstance().apply {
                clear()
                set(year, month - 1, 1)
            }
            val lastDay = calendar.getActualMaximum(Calendar.DAY_OF_MONTH)
            return "%04d:%02d:%02d 23:59:59".format(year, month, lastDay)
        }
}

/** A face cluster from the gallery's on-device AI ("人物"). */
data class Person(
    val id: Int,
    val name: String,
    val faceId: Int,
    val count: Int,
    val avatarUrl: String?,
)

/**
 * A user-created album, or one another account shared with the signed-in one.
 *
 * Both come back in the same shape — `album/list` and `album_grant/list_to_me` differ
 * only in that the granted one carries [ownerName] — so one model serves both and the
 * existing album grid opens either.
 */
data class AlbumItem(
    val id: Long,
    val name: String,
    val photoCount: Int,
    val videoCount: Int,
    val coverUrl: String?,
    val startDate: String?,
    val endDate: String?,
    /** Who shared this album with us; blank for the account's own albums. */
    val ownerName: String = "",
) {
    val totalCount: Int get() = photoCount + videoCount

    val isShared: Boolean get() = ownerName.isNotBlank()
}

/** A folder the gallery app is configured to manage. */
data class FolderItem(
    val path: String,
    val name: String,
    val photoCount: Int,
    val videoCount: Int,
) {
    val totalCount: Int get() = photoCount + videoCount
}

/** A directory inside a managed folder. */
data class SubFolder(val path: String, val name: String)

/** Library totals. */
data class PhotoStats(val photoCount: Int, val videoCount: Int)

/** One page of gallery results. */
data class MediaPage(
    val items: List<MediaItem>,
    val hasMore: Boolean,
)

/**
 * Ids of the months whose item count moved between two timeline snapshots.
 *
 * A month's cover is that month's first photo, so it goes stale the moment the count
 * changes. Comparing counts finds exactly those months without re-fetching every cover.
 */
fun changedMonthIds(previous: List<TimelineYear>, fresh: List<TimelineYear>): Set<String> {
    val before = previous.flatMap { it.months }.associate { it.id to it.count }
    return fresh.flatMap { it.months }
        .filter { before[it.id] != null && before[it.id] != it.count }
        .mapTo(mutableSetOf()) { it.id }
}

/**
 * Merges a freshly-read page into the list already on screen.
 *
 * New items go on the **end**, not back at the top where the server's newest-first
 * order would put them. A slideshow walks the list by index, so re-sorting underneath
 * it would swap the photo on screen mid-display; appending also means every arrival is
 * shown exactly once per pass instead of being skipped as already-passed.
 *
 * Items already present are dropped, so a server offset that has shifted under us —
 * exactly what happens when photos are uploaded mid-show — cannot produce duplicates.
 */
fun appendNewItems(loaded: List<MediaItem>, fresh: List<MediaItem>): List<MediaItem> {
    // Returning the same list (rather than a copy) for a no-op poll keeps the
    // slideshow from recomposing on every tick that finds nothing.
    if (fresh.isEmpty()) return loaded
    if (loaded.isEmpty()) return fresh
    val known = loaded.mapTo(HashSet()) { it.id }
    val additions = fresh.filterNot { it.id in known }
    return if (additions.isEmpty()) loaded else loaded + additions
}

/**
 * Drops the items a slideshow is holding that a freshly-read page no longer lists.
 *
 * [appendNewItems] covers arrivals; this covers the other half of "the list changed",
 * which a party album needs just as much: a photo the host deletes while the slideshow
 * runs used to stay in the rotation until the app restarted, showing a picture that is
 * no longer in the library — and, because the show walks the list by index, one
 * deletion also shifted everything after it.
 *
 * How much the page can be trusted to speak for is read from the page itself:
 *
 *  - **a page that did not fill up reached the end of the library**, so it is a complete
 *    statement and anything missing from it is gone. This is the usual case for the
 *    party albums this exists for: a slideshow holds one page of photos, and that page
 *    is the whole album.
 *  - **a full page only covers its own window.** Everything down to the last item it
 *    still contains can be judged; anything past that is older than the window and
 *    cannot be checked from one page. A deletion there is noticed on a later pass, once
 *    the list has shrunk enough for the window to reach it.
 *
 * [arrivals] are the ids this show appended itself. They are by definition among the
 * newest photos in the album, so they belong in the head of a fresh page however short
 * the window is — which is what makes a photo that was uploaded *and then deleted*
 * removable, instead of sitting at the tail where the window never reaches.
 *
 * The order of what survives is untouched. Re-sorting to the server's newest-first
 * position would swap the photo on screen mid-display, which is exactly what
 * [appendNewItems] exists to avoid.
 */
fun pruneRemovedItems(
    loaded: List<MediaItem>,
    page: MediaPage,
    arrivals: Set<Long> = emptySet(),
): List<MediaItem> {
    val fresh = page.items
    // Nothing to compare against: an empty page is not evidence that the library is
    // empty, and an empty list has nothing to lose.
    if (loaded.isEmpty() || fresh.isEmpty()) return loaded

    val freshIds = fresh.mapTo(HashSet(fresh.size)) { it.id }
    val lastJudged = if (page.hasMore) {
        val boundary = loaded.indexOfLast { it.id in freshIds }
        if (boundary < 0) return loaded
        boundary
    } else {
        loaded.lastIndex
    }

    val removed = loaded.filterIndexed { index, item ->
        item.id !in freshIds && (index <= lastJudged || item.id in arrivals)
    }
    // Handing back the very same list when nothing went keeps an idle poll free: the
    // slideshow publishes this list, and an unchanged instance recomposes nothing.
    if (removed.isEmpty()) return loaded

    val removedIds = removed.mapTo(HashSet(removed.size)) { it.id }
    return loaded.filterNot { it.id in removedIds }
}
