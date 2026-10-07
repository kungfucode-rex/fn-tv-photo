package com.tvphoto.data

import com.tvphoto.data.fn.FnApi
import com.tvphoto.data.fn.FnSession
import com.tvphoto.domain.AlbumItem
import com.tvphoto.domain.FolderItem
import com.tvphoto.domain.MediaItem
import com.tvphoto.domain.MediaPage
import com.tvphoto.domain.Person
import com.tvphoto.domain.PhotoStats
import com.tvphoto.domain.SubFolder
import com.tvphoto.domain.TimelineDay
import com.tvphoto.domain.TimelineMonth
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The single entry point the UI uses for gallery data.
 *
 * Every call goes through [SessionRepository.withSession], so a stale token is
 * refreshed and the request replayed without the UI ever seeing the failure.
 */
class PhotoRepository(
    private val sessions: SessionRepository,
    private val api: FnApi,
) {

    suspend fun months(): List<TimelineMonth> = sessions.withSession { api.months(it) }

    suspend fun allPhotos(limit: Int, offset: Int): MediaPage =
        sessions.withSession { api.allPhotos(it, limit, offset) }

    suspend fun photosInMonth(month: TimelineMonth, limit: Int, offset: Int): MediaPage =
        sessions.withSession { api.photosInMonth(it, month, limit, offset) }

    suspend fun albums(): List<AlbumItem> = sessions.withSession { api.albums(it) }

    /** Albums other accounts shared with this one; may legitimately be empty. */
    suspend fun sharedAlbumsToMe(): List<AlbumItem> =
        sessions.withSession { api.sharedAlbumsToMe(it) }

    suspend fun people(): List<Person> = sessions.withSession { api.people(it) }

    /**
     * Photos of one person, flattened across days so the UI can page a single grid.
     *
     * The person gallery has no "give me everything" query — its list endpoint only
     * accepts one day and rejects a wider range with HTTP 400 — so the day list is
     * fetched once and then walked.
     *
     * Which days cover the requested window is computed from the timeline's own
     * per-day counts *before* any fetching, so a page costs a handful of parallel
     * requests rather than one sequential request per day.
     */
    suspend fun personPhotos(personId: Int, limit: Int, offset: Int): MediaPage =
        sessions.withSession { session ->
            val days = personDaysCache.getOrPut(personId) { api.personDays(session, personId) }
            flattenPersonDays(session, personId, days, limit, offset)
        }

    private suspend fun flattenPersonDays(
        session: FnSession,
        personId: Int,
        days: List<TimelineDay>,
        limit: Int,
        offset: Int,
    ): MediaPage {
        // Which days cover the window is decided before any fetching, so a page costs
        // a handful of parallel requests rather than one request per day.
        val (window, hasMore) = personDayWindow(days, limit, offset)
        if (window.isEmpty()) return MediaPage(emptyList(), hasMore = false)

        val pages = coroutineScope {
            window
                .map { slice ->
                    async {
                        // One bad day must not sink the whole page.
                        runCatching {
                            api.personPhotosOnDay(
                                session = session,
                                personId = personId,
                                day = slice.day,
                                limit = slice.day.count.coerceAtLeast(1),
                                offset = slice.skip,
                            )
                        }.getOrNull()
                    }
                }
                .awaitAll()
        }

        val items = pages.filterNotNull().flatMap { it.items }
        return MediaPage(items.take(limit), hasMore || items.size > limit)
    }

    /** Drops per-session lookups, e.g. on sign-out. */
    fun clearCaches() {
        personDaysCache.clear()
    }

    private val personDaysCache = mutableMapOf<Int, List<TimelineDay>>()

    suspend fun albumPhotos(albumId: Long, limit: Int, offset: Int): MediaPage =
        sessions.withSession { api.albumPhotos(it, albumId, limit, offset) }

    suspend fun managedFolders(): List<FolderItem> = sessions.withSession { api.managedFolders(it) }

    suspend fun subFolders(path: String): List<SubFolder> =
        sessions.withSession { api.subFolders(it, path) }

    suspend fun folderFiles(path: String, limit: Int, offset: Int): MediaPage =
        sessions.withSession { api.folderFiles(it, path, limit, offset) }

    suspend fun stats(): PhotoStats? = sessions.withSession { api.stats(it) }
}
