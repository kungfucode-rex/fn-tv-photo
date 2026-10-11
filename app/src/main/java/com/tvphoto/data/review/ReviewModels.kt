package com.tvphoto.data.review

import com.tvphoto.domain.MediaItem
import org.json.JSONObject

/**
 * One comment as the server sends it.
 *
 * [status] is the whole of "what the screen may do with this": `1` is visible, `0` is
 * waiting for a moderator, `2` and `3` are blocked and deleted. The danmaku layer only
 * ever draws `1`, and the H5 page is the only place the other values are explained to a
 * person (docs/tuping-design.md §6.4).
 */
data class ReviewComment(
    val id: String,
    val nickname: String,
    val content: String,
    val createdAt: Long,
    val status: Int,
) {
    /** How long this comment takes to cross the screen at [DanmakuFeed.SPEED_PX_PER_SECOND]. */
    fun widthPx(textPx: Float): Float {
        // A CJK character is about one em wide and a Latin one about half. Estimating
        // here keeps `TextMeasurer` out of the hot path; the overlay measures properly
        // once it has a real layout.
        var units = 0f
        for (ch in content) {
            units += if (ch.code > 0x2E80) 1f else 0.55f
        }
        return units * textPx
    }

    companion object {
        fun from(json: JSONObject): ReviewComment = ReviewComment(
            id = json.optString("id"),
            nickname = json.optString("nickname"),
            content = json.optString("content"),
            createdAt = json.optLong("createdAt"),
            status = json.optInt("status", 1),
        )

        /** Parses the `items` array of a `/v1/comments` response. */
        fun listFrom(json: JSONObject): List<ReviewComment> {
            val items = json.optJSONArray("items") ?: return emptyList()
            return (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.let(::from)
            }
        }
    }
}

/**
 * Where one photo stands with 图评.
 *
 * [code] is the short code the QR image encodes, and [imageId] is the server's identity
 * for the picture — deliberately not derived from anything local, because the whole
 * point of the layered fingerprint is that two albums can arrive at the same one.
 */
data class ReviewIdentity(
    val localId: String,
    val imageId: String,
    val code: String,
    val commentCount: Int,
    val matched: String,
    val qrUrl: String,
    val thumbUrl: String,
    /** When this answer was received, so a cache entry can be judged stale. */
    val fetchedAt: Long,
)

/** What the server said when a registration was refused, if anything. */
sealed interface ReviewError {
    /** The server answered with the protocol's error envelope. */
    data class Api(val code: String, val message: String) : ReviewError

    /** Nothing answered, or the answer was unreadable. */
    data class Unreachable(val reason: String) : ReviewError

    /** The bytes we were about to upload were not something the server could read. */
    data class BadThumbnail(val reason: String) : ReviewError
}

/**
 * The protocol error a failed client call carries, if any.
 *
 * `ReviewClient` answers with a `Result` whose failure is a [ReviewException] — the one
 * place a `Throwable` had to be used, because `Result` needs one — and the `ReviewError`
 * itself is a property of it. Every caller that wants to tell "the server said no" apart
 * from "nothing answered" goes through here rather than repeating the unwrapping.
 *
 * It used to read `error.cause`, which could never be right: a `ReviewError` is a value,
 * not a `Throwable`, so the cause it looked for was always null and every refusal was
 * reported as a Java `toString` instead of the sentence the server wrote.
 */
internal fun reviewErrorOf(error: Throwable?): ReviewError? = (error as? ReviewException)?.error

/**
 * Whether a failure is the server saying "this device token is not one of mine".
 *
 * The protocol's own code rather than the HTTP status, because that is what the envelope
 * carries and what the three album-end endpoints agree on (`server/internal/api/server.go`
 * answers `unauthorized` for a device token that does not authenticate).
 *
 * The distinction this draws is load-bearing in both directions. Treating a refusal as
 * something else leaves the app stuck for good — `/healthz` keeps answering, so the
 * settings row keeps saying 已连接 while every call that carries the token is refused, and
 * the viewer's 图评 button goes grey with nothing on screen to explain it. Treating
 * anything else as a refusal is just as bad the other way: a dropped packet would make the
 * app register a brand new device on the server for nothing.
 */
internal fun isRefusedToken(error: Throwable?): Boolean =
    (reviewErrorOf(error) as? ReviewError.Api)?.code == REFUSED_TOKEN_CODE

/** The error code a refused device token comes back as (docs/tuping-design.md §6.5). */
private const val REFUSED_TOKEN_CODE = "unauthorized"

/**
 * Whether a failure is the server saying "I do not hold that photo at all".
 *
 * The history read — `GET /v1/comments?imageId=…` — answers `not_found` for an image id the
 * server has no row for, and that is the second way a cached identity can go stale. The
 * first is the device token, and the two are independent: a server restored from a backup
 * taken *after* this device registered but *before* a photo was registered knows the device
 * and not the photo, so no repair fires and the television keeps a code that scans to
 * **这张照片不在图评里** for as long as the app process lives. The answer is the same in both
 * cases — the identity is worthless, register the photo again — so it is read here rather
 * than left to the one path that happens to notice.
 *
 * Only the history read is read this way. `not_found` also means "that comment is gone" on
 * the comment endpoints, and the album end never calls those.
 */
internal fun isUnknownPhoto(error: Throwable?): Boolean =
    (reviewErrorOf(error) as? ReviewError.Api)?.code == UNKNOWN_PHOTO_CODE

/** The error code a photo the server has no row for comes back as (`api/comments.go`). */
private const val UNKNOWN_PHOTO_CODE = "not_found"

/**
 * The connection state the settings page shows.
 *
 * A television has no console, so "cannot reach the review server" has to be readable
 * on the screen — the same reason the last crash is printed there
 * (docs/tuping-design.md §9.4).
 *
 * Every label says *why*, never just that something is off. The first version of this
 * reported `关` for "no server address is set", which is the one sentence a person can do
 * nothing with: it neither says the layer is switched off on purpose nor that an address
 * is missing. `关` now only ever means the mode setting.
 */
sealed interface ReviewStatus {
    data object Off : ReviewStatus

    data object NoServer : ReviewStatus

    data class Registering(val server: String) : ReviewStatus

    data class Online(val server: String, val lastAt: Long) : ReviewStatus

    data class Failed(val server: String, val reason: String, val at: Long) : ReviewStatus

    /** The one line the settings row shows. */
    fun label(): String = when (this) {
        Off -> "关（模式设为「关」）"
        NoServer -> "未设置服务器地址"
        is Registering -> "正在注册 $server"
        is Online -> "已连接 $server"
        is Failed -> "连不上 $server · $reason"
    }
}

/** What the album offers to register: a photo and the bytes the server fingerprints. */
data class ReviewCandidate(
    val item: MediaItem,
    /** The album's own identifier for this photo, stable across launches. */
    val localId: String,
    /** The 320px-equivalent thumbnail bytes; see [PhotoIdentity.canonicalBytes]. */
    val thumb: ByteArray,
    /** sha256 of the original file, when it was already on disk. Null otherwise. */
    val sha256Original: String?,
) {
    // ByteArray is compared by identity in a data class, which is a trap: two
    // candidates with equal bytes would compare unequal and a cache would miss.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReviewCandidate) return false
        return localId == other.localId &&
            item.id == other.item.id &&
            sha256Original == other.sha256Original &&
            thumb.contentEquals(other.thumb)
    }

    override fun hashCode(): Int {
        var result = localId.hashCode()
        result = 31 * result + item.id.hashCode()
        result = 31 * result + (sha256Original?.hashCode() ?: 0)
        result = 31 * result + thumb.contentHashCode()
        return result
    }
}
