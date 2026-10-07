package com.tvphoto.data

import android.content.Context
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Warms the image cache ahead of the user so opening a photo is instant.
 *
 * Two details make this cheap and effective:
 *
 *  - The request decodes at **1x1**. Coil's network fetcher writes the *full*
 *    response body to the disk cache *before* decoding, so the whole original lands
 *    on disk while only a single pixel is ever held in memory. Asking for the
 *    original size instead would decode fifty multi-megapixel bitmaps.
 *  - The memory cache is disabled, because these results are never drawn. They exist
 *    only to populate the disk cache that the viewer later reads from.
 *
 * Videos are never passed in: Media3 streams them with no cache configured, and the
 * caller only offers stills, so the disk cache stays image-only.
 */
class ImagePrefetcher(
    private val context: Context,
    private val imageLoaderProvider: () -> ImageLoader,
) {

    /**
     * URLs already fetched, so re-anchoring the window (which happens as the user
     * moves through a grid) costs nothing instead of re-downloading the overlap.
     * Only successful fetches are recorded, so a cancelled one stays eligible.
     */
    private val fetched = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Drops the record, e.g. after the cache has been cleared. */
    fun forgetAll() {
        fetched.clear()
    }

    /**
     * Fetches up to [limit] of [urls] into the disk cache, [concurrency] at a time.
     *
     * Cancelling the calling coroutine cancels the outstanding downloads, which is
     * what happens when the user leaves the screen.
     */
    suspend fun prefetch(
        urls: List<String>,
        limit: Int = DEFAULT_LIMIT,
        concurrency: Int = DEFAULT_CONCURRENCY,
    ) {
        val targets = urls.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .filterNot { it in fetched }
            .take(limit)
            .toList()
        if (targets.isEmpty()) return

        val gate = Semaphore(concurrency)
        val loader = imageLoaderProvider()

        coroutineScope {
            targets.forEach { url ->
                launch(Dispatchers.IO) {
                    gate.withPermit {
                        // A single failure must not abort the rest of the batch.
                        runCatching { loader.execute(request(url)) }
                            .onSuccess { fetched.add(url) }
                    }
                }
            }
        }
    }

    private fun request(url: String): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .size(PREFETCH_PIXELS, PREFETCH_PIXELS)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.ENABLED)
        .build()

    companion object {
        /** How many images to warm when a grid opens. */
        const val DEFAULT_LIMIT = 50

        /** Parallel downloads; a NAS on a LAN handles this comfortably. */
        const val DEFAULT_CONCURRENCY = 4

        private const val PREFETCH_PIXELS = 1
    }
}
