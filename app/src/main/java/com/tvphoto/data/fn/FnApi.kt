package com.tvphoto.data.fn

import com.tvphoto.domain.AlbumItem
import com.tvphoto.domain.FolderItem
import com.tvphoto.domain.MediaItem
import com.tvphoto.domain.MediaPage
import com.tvphoto.domain.Person
import com.tvphoto.domain.PhotoStats
import com.tvphoto.domain.SubFolder
import com.tvphoto.domain.TimelineDay
import com.tvphoto.domain.TimelineMonth
import org.json.JSONArray
import org.json.JSONObject

/**
 * Typed access to the fnOS photo gallery endpoints.
 *
 * Response envelopes are not uniform across the API (`app/version` answers with
 * `errno`/`result` where the rest use `code`/`data`), so each parser takes the
 * payload it expects and tolerates missing fields rather than failing a whole page
 * because one entry is incomplete.
 */
class FnApi(private val http: FnHttpClient) {

    /**
     * Photos grouped by month.
     *
     * The endpoint returns one entry per *day*; those are summed into months here so
     * the UI does not have to offer a day-per-row list that takes forever to scroll.
     */
    suspend fun months(session: FnSession): List<TimelineMonth> {
        val data = http.getJson(session, "/p/api/v1/gallery/timeline").optJSONObject("data")

        val days = data.array("list").mapNotNull { item ->
            val year = item.optInt("year", 0)
            val month = item.optInt("month", 0)
            if (year <= 0 || month <= 0) return@mapNotNull null
            (year to month) to item.optInt("itemCount", 0)
        }

        return days
            .groupBy({ it.first }, { it.second })
            .map { (yearMonth, counts) ->
                TimelineMonth(yearMonth.first, yearMonth.second, counts.sum())
            }
            .sortedWith(compareByDescending<TimelineMonth> { it.year }.thenByDescending { it.month })
    }

    /**
     * Photos taken in one month.
     *
     * `end_time` must reach the final second of the month or the tail is dropped.
     */
    suspend fun photosInMonth(
        session: FnSession,
        month: TimelineMonth,
        limit: Int,
        offset: Int,
    ): MediaPage = galleryList(
        session = session,
        startTime = month.startParam,
        endTime = month.endParam,
        limit = limit,
        offset = offset,
    )

    /** Everything, newest first, by asking for a range wider than any real library. */
    suspend fun allPhotos(session: FnSession, limit: Int, offset: Int): MediaPage = galleryList(
        session = session,
        startTime = "1970:01:01 00:00:00",
        endTime = "2099:12:31 23:59:59",
        limit = limit,
        offset = offset,
    )

    /** Shared decoding for every `{data:{list:[...], hasNext}}` response. */
    private fun page(session: FnSession, json: JSONObject, limit: Int): MediaPage {
        val data = json.optJSONObject("data")
        val items = parseMediaList(session, data.array("list"))
        val hasMore = when {
            data == null -> false
            data.has("hasNext") && !data.isNull("hasNext") -> data.optBoolean("hasNext")
            else -> items.size >= limit
        }
        return MediaPage(items, hasMore)
    }

    private suspend fun galleryList(
        session: FnSession,
        startTime: String,
        endTime: String,
        limit: Int,
        offset: Int,
    ): MediaPage {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/gallery/getList",
            params = listOf(
                "start_time" to startTime,
                "end_time" to endTime,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "mode" to "index",
            ),
        )
        return page(session, json, limit)
    }

    /**
     * Face clusters from the gallery's AI indexing.
     *
     * Returns empty when the NAS has AI indexing switched off — that is a normal
     * state, not a failure, so the UI shows an explanatory empty state rather than an
     * error.
     */
    suspend fun people(session: FnSession): List<Person> {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/ai-person/list",
            params = listOf(
                "getAll" to "true",
                "limit" to "-1",
                "orderBy" to "0",
            ),
        )
        return json.optJSONObject("data").array("list").mapNotNull { item ->
            val id = item.optInt("id", 0)
            if (id <= 0) return@mapNotNull null
            if (item.optInt("isHide", 0) == 1) return@mapNotNull null
            val faceId = item.optInt("faceId", 0)
            Person(
                id = id,
                name = item.optString("name"),
                faceId = faceId,
                count = item.optInt("itemCount", 0),
                avatarUrl = if (faceId > 0) {
                    absoluteUrl(session.baseUrl, "/p/api/v1/stream/face/$faceId")
                } else {
                    null
                },
            )
        }.sortedByDescending { it.count }
    }

    /**
     * The days on which this person appears, newest first.
     *
     * The person gallery is a two-step API: this timeline, then one list call **per
     * day**. Asking the list endpoint for a wide range is rejected with HTTP 400, so
     * the days have to be walked.
     */
    suspend fun personDays(session: FnSession, personId: Int): List<TimelineDay> {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/ai-person/photoLibrary/timeLine",
            params = listOf("id" to personId.toString()),
        )
        return json.optJSONObject("data").array("list").mapNotNull { item ->
            val year = item.optInt("year", 0)
            val month = item.optInt("month", 0)
            val day = item.optInt("day", 0)
            if (year <= 0 || month <= 0 || day <= 0) return@mapNotNull null
            TimelineDay(year, month, day, item.optInt("itemCount", 0))
        }
    }

    /**
     * Photos of one person on one day.
     *
     * All eight parameters are sent deliberately. The endpoint takes the range under
     * both snake_case and camelCase names and an `album_id` that carries the person
     * id; omitting any of them is what produced a 400.
     */
    suspend fun personPhotosOnDay(
        session: FnSession,
        personId: Int,
        day: TimelineDay,
        limit: Int,
        offset: Int,
    ): MediaPage {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/ai-person/photoLibrary/list",
            params = listOf(
                "album_id" to personId.toString(),
                "endTime" to day.endParam,
                "end_time" to day.endParam,
                "limit" to limit.toString(),
                "offset" to offset.toString(),
                "personId" to personId.toString(),
                "startTime" to day.startParam,
                "start_time" to day.startParam,
            ),
        )
        return page(session, json, limit)
    }

    suspend fun albums(session: FnSession): List<AlbumItem> {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/album/list",
            params = listOf(
                "sort_direction" to "desc",
                "sort_by" to "date_time",
                "offset" to "0",
                "limit" to "300",
            ),
        )
        return json.optJSONObject("data").array("list").mapNotNull { albumItem(session, it) }
    }

    /**
     * Albums other accounts have shared with this one ("他人分享相册").
     *
     * A different route from `album/list`, not a filter on it: the entries are the
     * same shape plus `ownerName`, and the recipient opens one through the ordinary
     * album photo list. Taken from the fnOS web frontend's own `shareApi` bundle
     * (`album_grant/list_to_me`) and a capture from a live device — see
     * docs/fnos-photo-api.md.
     */
    suspend fun sharedAlbumsToMe(session: FnSession): List<AlbumItem> {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/album_grant/list_to_me",
            params = listOf(
                "offset" to "0",
                "limit" to "300",
                "sort_by" to "share_mod_time",
                "sort_direction" to "desc",
            ),
        )
        return json.optJSONObject("data").array("list").mapNotNull { albumItem(session, it) }
    }

    /** One album payload. `album/list` and `album_grant/list_to_me` share this shape. */
    private fun albumItem(session: FnSession, item: JSONObject): AlbumItem? {
        val id = item.optLong("albumId", 0L)
        if (id <= 0L) return null
        return AlbumItem(
            id = id,
            // Left blank on purpose: the UI substitutes a localised placeholder.
            name = item.optString("albumName"),
            photoCount = item.optInt("photoCount", 0),
            videoCount = item.optInt("videoCount", 0),
            coverUrl = absoluteUrl(
                session.baseUrl,
                item.optString("posterImgUrl").ifBlank { item.optString("posterUrl") },
            ),
            startDate = item.optString("startDateTime").ifBlank { null },
            endDate = item.optString("endDateTime").ifBlank { null },
            ownerName = item.optString("ownerName"),
        )
    }

    suspend fun albumPhotos(
        session: FnSession,
        albumId: Long,
        limit: Int,
        offset: Int,
    ): MediaPage {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/album/photos",
            params = listOf(
                "album_id" to albumId.toString(),
                "sort_by" to "date_time",
                "sort_direction" to "desc",
                "offset" to offset.toString(),
                "limit" to limit.toString(),
            ),
        )
        val items = parseMediaList(session, json.optJSONObject("data").array("list"))
        val data = json.optJSONObject("data")
        val hasMore = when {
            data == null -> false
            data.has("hasNext") && !data.isNull("hasNext") -> data.optBoolean("hasNext")
            else -> items.size >= limit
        }
        return MediaPage(items, hasMore)
    }

    suspend fun managedFolders(session: FnSession): List<FolderItem> {        val json = http.getJson(
            session = session,
            path = "/p/api/v1/photo/folder/list",
            params = listOf("desc" to "false", "orderBy" to "2"),
        )
        return json.optJSONObject("data").array("list").mapNotNull { item ->
            val path = item.optString("folderPath")
            if (path.isBlank()) return@mapNotNull null
            FolderItem(
                path = path,
                name = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
                photoCount = item.optInt("photoCount", 0),
                videoCount = item.optInt("videoCount", 0),
            )
        }
    }

    suspend fun subFolders(session: FnSession, folderPath: String): List<SubFolder> {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/folder_view/getFolderList",
            params = listOf(
                "folderPath" to folderPath,
                "desc" to "false",
                "orderBy" to "2",
            ),
        )
        return json.optJSONObject("data").array("list").mapNotNull { item ->
            val path = item.optString("path")
            if (path.isBlank()) return@mapNotNull null
            SubFolder(
                path = path,
                name = item.optString("name").ifBlank {
                    path.trimEnd('/').substringAfterLast('/').ifBlank { path }
                },
            )
        }
    }

    suspend fun folderFiles(
        session: FnSession,
        folderPath: String,
        limit: Int,
        offset: Int,
    ): MediaPage {
        val json = http.getJson(
            session = session,
            path = "/p/api/v1/folder_view/getFileList",
            params = listOf(
                "folderPath" to folderPath,
                "desc" to "false",
                "orderBy" to "2",
                "limit" to limit.toString(),
                "offset" to offset.toString(),
            ),
        )
        val items = parseMediaList(session, json.optJSONObject("data").array("list"))
        return MediaPage(items, items.size >= limit)
    }

    suspend fun stats(session: FnSession): PhotoStats? {
        val data = http.getJson(session, "/p/api/v1/user_photo/stat").optJSONObject("data")
            ?: return null
        return PhotoStats(
            photoCount = data.optInt("photoCount", 0),
            videoCount = data.optInt("videoCount", 0),
        )
    }

    private fun parseMediaList(session: FnSession, array: List<JSONObject>): List<MediaItem> =
        array.map { parseMedia(session.baseUrl, it) }

    private fun parseMedia(baseUrl: String, json: JSONObject): MediaItem {
        val thumbnail = json.optJSONObject("additional")?.optJSONObject("thumbnail")
        val isVideo = json.optString("category").equals("video", ignoreCase = true)

        // Prefer the medium tier: it is ~1920px, which is right for a TV grid and
        // still cheap on a LAN. `l` is not served by current firmware.
        val thumbRelative = thumbnail?.let { thumb ->
            listOf("mUrl", "sUrl", "xsUrl", "xxsUrl", "originalUrl")
                .firstNotNullOfOrNull { key -> thumb.optString(key).ifBlank { null } }
        }

        return MediaItem(
            id = json.optLong("id", 0L),
            name = json.optString("fileName"),
            isVideo = isVideo,
            isLivePhoto = json.optInt("isLive", 0) == 1,
            thumbnailUrl = absoluteUrl(baseUrl, thumbRelative),
            fullUrl = absoluteUrl(baseUrl, thumbnail?.optString("originalUrl")),
            videoUrl = absoluteUrl(baseUrl, thumbnail?.optString("videoUrl")),
            takenAt = json.optString("dateTime").ifBlank { json.optString("photoDateTime") }
                .ifBlank { null },
            width = json.optInt("width", 0),
            height = json.optInt("height", 0),
            fileSize = json.optLong("fileSize", 0L),
        )
    }

    private fun absoluteUrl(baseUrl: String, relative: String?): String? {
        if (relative.isNullOrBlank()) return null
        if (relative.startsWith("http://") || relative.startsWith("https://")) return relative
        return baseUrl.trimEnd('/') + "/" + relative.trimStart('/')
    }

    private fun JSONObject?.array(key: String): List<JSONObject> {
        val arr: JSONArray = this?.optJSONArray(key) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }
}
