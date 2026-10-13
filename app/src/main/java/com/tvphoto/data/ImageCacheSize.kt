package com.tvphoto.data

/**
 * How large the image disk cache may be, given what the volume can spare.
 *
 * [IMAGE_CACHE_CEILING_BYTES] is the figure the product promises and it is still the
 * *ceiling* — but a television is not a disk with a NAS on the end of it, and a flat
 * budget is a budget the box cannot keep.
 *
 * **The measurement this comes from.** Three televisions looping one slideshow album spent
 * 50 GB in two days while the settings row showed a cache that never filled. A cache of a
 * fixed 5 GB on a box whose internal storage has one or two GB free does not sit at 5 GB
 * and stop: it grows until the volume is nearly full, at which point Android's own
 * low-storage handling deletes cache directories — oldest files first, which is exactly the
 * album the show is about to ask for again. Every purge costs a full pass over the album
 * (a 217-photo album at 8 seconds is ~29 minutes, ~50 passes a day), so the traffic is what
 * the cache was supposed to remove. The same reasoning the music library already follows:
 * a TV box's internal storage is small (docs/custom-music-design.md, D5).
 *
 * **A quarter of what is free, not of what exists.** What a cache may use is decided by
 * what is left, not by the size of the volume: a 32 GB box with 30 GB free can hold the
 * whole 5 GB, while the same box three albums later can hold almost none of it. Three
 * quarters stay free on purpose — the app's own uploads and the downloaded update APK live
 * on the same volume, and a cache that pushed the system into a cleanup would lose more
 * than it saved. Losing a quarter of a *cache* costs a re-download; losing the volume costs
 * everything on it.
 *
 * [IMAGE_CACHE_FLOOR_BYTES] is the smallest cache worth building. Below it the box has
 * bigger problems than caching, and a cache of a few megabytes would only churn.
 *
 * The value is decided once, when the loader is built: `DiskCache` takes its size at
 * construction and there is no supported way to resize a journal in place. Free space
 * falling later is answered by the LRU evicting, which is the behaviour this ceiling was
 * already relying on.
 */
fun imageCacheBudgetBytes(
    freeSpaceBytes: Long,
    ceilingBytes: Long = IMAGE_CACHE_CEILING_BYTES,
    fraction: Double = IMAGE_CACHE_FREE_FRACTION,
    floorBytes: Long = IMAGE_CACHE_FLOOR_BYTES,
): Long {
    // A platform that cannot answer, or answers nonsense, must not be read as "the whole
    // disk is free": that is the one answer that fills the volume. The floor is the safe
    // direction, and the log line in `AppContainer` reports what was decided.
    if (freeSpaceBytes <= 0L) return floorBytes

    val share = (freeSpaceBytes.toDouble() * fraction).toLong()
    // min-then-max rather than `coerceIn`: the range is only non-degenerate because the
    // constants say so, and a caller passing its own floor above the ceiling should get a
    // cache rather than an exception.
    return maxOf(floorBytes, minOf(ceilingBytes, share))
}

/** The most the image cache may ever hold, on a box that can afford it. */
const val IMAGE_CACHE_CEILING_BYTES: Long = 5L * 1024 * 1024 * 1024

/** The smallest cache worth building at all. */
const val IMAGE_CACHE_FLOOR_BYTES: Long = 32L * 1024 * 1024

/** The share of the *free* space the cache may take; see the note on the function. */
const val IMAGE_CACHE_FREE_FRACTION: Double = 0.25
