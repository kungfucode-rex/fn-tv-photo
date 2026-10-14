package com.tvphoto.data.review

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * The wire to the 图评 server.
 *
 * **This client is deliberately not the NAS client.** Every other outbound request in
 * this app goes through `FnHttp`, which pins the NAS's self-signed certificate on first
 * use. A review server on a LAN has an ordinary certificate or none at all, and pinning
 * would reject it for the one reason that has nothing to do with whether it is the right
 * server (docs/tuping-design.md §9.1).
 *
 * Nothing here throws for a network problem. A failure is a value, because every caller
 * has to make the layer disappear quietly and the one thing that must never happen is
 * 图评 taking the viewer down with it.
 */
class ReviewClient(
    serverUrl: String,
    private val deviceId: String,
    private val deviceToken: String,
    client: OkHttpClient = defaultClient(),
) {

    private val base: HttpUrl? = normalize(serverUrl)

    val server: String get() = base?.toString().orEmpty()

    /**
     * True when there is an address to talk to at all.
     *
     * Deliberately *not* a statement about credentials. The first version of this also
     * required a device token, which made the whole feature a deadlock: an address could
     * be saved, and then the very next check rejected the server as unusable "because it
     * has no token" — before `ensureDevice` had been given the chance to ask for one. The
     * token is obtained *over this client*, so the client has to be usable without it.
     */
    val configured: Boolean get() = base != null

    private val http: OkHttpClient = client

    // ------------------------------------------------------------- endpoints

    /**
     * Registers a thumbnail and gets back the identity, the short code and both URLs.
     *
     * `freshnessDays` is not sent: it is the server's window for "this device already
     * registered this photo", and the album has no opinion about it.
     *
     * **`baseUrl` is sent, and it is the address this client is talking to.** The server
     * cannot work that out for itself: it knows what it was configured with, not which of
     * its addresses the television actually reaches, and those differ the moment somebody
     * puts the deployment behind a port forward or a tunnel. Leaving it to the server is how
     * a QR code came out pointing at 192.168… while the TV was happily uploading to a public
     * address, and the failure is invisible on the television — it says 已连接 either way,
     * and only the guest's phone notices.
     *
     * `base` is already the normalized address this client reaches the server on, so it is
     * the one value that is known to work from here. The server validates it and falls back
     * to its own setting if it is somehow unusable.
     */
    suspend fun registerPhoto(candidate: ReviewCandidate, source: String): Result<ReviewIdentity> =
        call {
            val meta = JSONObject().apply {
                put("localId", candidate.localId)
                put("source", source)
                put("sha256Original", candidate.sha256Original ?: JSONObject.NULL)
                put("width", candidate.item.width)
                put("height", candidate.item.height)
                put("scope", SCOPE_IMAGE)
                put("baseUrl", base!!.toString())
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("meta", meta.toString())
                .addFormDataPart(
                    "thumb",
                    "thumb.jpg",
                    candidate.thumb.toRequestBody(JPEG),
                )
                .build()

            val response = http.newCall(
                Request.Builder()
                    .url(base!!.newBuilder().addPathSegments("v1/photos").build())
                    .header("X-Device-Id", deviceId)
                    .header("X-Device-Token", deviceToken)
                    .post(body)
                    .build(),
            ).executeSync()

            response.use { parseIdentity(it, candidate.localId) }
        }

    /**
     * Asks whether this device has already registered this picture, and uploads nothing.
     *
     * The album end's only handle on a photo before the server has spoken is the id its own
     * library uses, and this is the call that turns it into a code, an image id and a
     * comment count — or into null, which means "nobody has ever opened 图评 on this one"
     * (docs/tuping-design.md §17.28).
     *
     * **A 404 is success with a null answer, not a failure.** It is the ordinary state of
     * most of a library, and treating it as an error would put "图评连不上" on the settings
     * row of a deployment that is working perfectly. Only a real failure — nothing answered,
     * or the device token was refused — comes back as a failed `Result`.
     */
    suspend fun lookupPhoto(source: String, localId: String): Result<ReviewIdentity?> = call {
        val url = base!!.newBuilder()
            .addPathSegments("v1/photos/by-local")
            .addQueryParameter("source", source)
            .addQueryParameter("localId", localId)
            .build()

        val response = http.newCall(
            Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .header("X-Device-Token", deviceToken)
                .get()
                .build(),
        ).executeSync()

        response.use { result ->
            if (result.code == 404) return@use Result.success(null)
            parseIdentity(result, localId)
        }
    }

    /** The comments on a photo, oldest first. [since] is exclusive, in milliseconds. */
    suspend fun comments(imageId: String, since: Long = 0, limit: Int = 50): Result<List<ReviewComment>> =
        call {
            val url = base!!.newBuilder()
                .addPathSegments("v1/comments")
                .addQueryParameter("imageId", imageId)
                .addQueryParameter("since", since.toString())
                .addQueryParameter("limit", limit.toString())
                .build()

            val response = http.newCall(
                Request.Builder()
                    .url(url)
                    .header("X-Device-Id", deviceId)
                    .header("X-Device-Token", deviceToken)
                    .get()
                    .build(),
            ).executeSync()

            response.use { parseJson(it) { body -> ReviewComment.listFrom(body) } }
        }

    /**
     * Deletes the photo this device registered, its comments and its thumbnail.
     *
     * Carries the owner token, not the device token: the device token says which album
     * end is asking, the owner token says it is entitled to this photo.
     */
    suspend fun deletePhoto(imageId: String, ownerToken: String): Result<Unit> = call {
        val response = http.newCall(
            Request.Builder()
                .url(base!!.newBuilder().addPathSegments("v1/photos/$imageId").build())
                .header("X-Device-Id", deviceId)
                .header("X-Device-Token", deviceToken)
                .header("X-Owner-Token", ownerToken)
                .delete()
                .build(),
        ).executeSync()

        response.use { parseJson(it) { } }
    }

    /**
     * Fetches the QR PNG for a code, and the address the picture encodes with it.
     *
     * The server renders the picture, so the TV never needs a QR library — and the address
     * inside it is the one recorded on the *short code* when it was minted, which is not
     * necessarily the address this client is talking to (docs/tuping-design.md §6.1,
     * §17.31). That difference is invisible in a PNG, which is why the server puts it on
     * the response as `X-Page-Url` and why [QrImage] carries it back: it is the only way
     * the app can notice that the code in front of the guest opens somewhere else.
     */
    suspend fun qrPng(code: String, size: Int = QR_SIZE): Result<QrImage> = call {
        val url = base!!.newBuilder()
            .addPathSegments("v1/qr/$code.png")
            .addQueryParameter("size", size.toString())
            .build()

        val response = http.newCall(Request.Builder().url(url).get().build()).executeSync()
        response.use { result ->
            if (!result.isSuccessful) return@use Result.failure(httpFailure(result))
            val bytes = result.body?.bytes() ?: return@use Result.failure(
                ReviewException(ReviewError.Unreachable("empty QR response")),
            )
            Result.success(QrImage(png = bytes, pageUrl = result.header("X-Page-Url")))
        }
    }

    /** The health line, used by the settings page to answer "is it there at all". */
    suspend fun health(): Result<String> = call {
        val response = http.newCall(
            Request.Builder().url(base!!.newBuilder().addPathSegments("healthz").build()).get().build(),
        ).executeSync()
        response.use { parseJson(it) { body -> body.optString("version") } }
    }

    /**
     * The other half of the health line: does this server know *this device*?
     *
     * `/healthz` is unauthenticated, so it answers for anybody. That is the whole trap this
     * call exists for: a server that has been reinstalled, restored from a backup older
     * than this device's registration, or replaced at the same address answers `/healthz`
     * with a cheerful 200 — the settings row reads 已连接 — while refusing every request
     * that carries a token it has never heard of. Nothing on the television can tell those
     * two states apart from the outside, which is how the 图评 button ends up greyed out
     * under a green 已连接.
     *
     * It is a comment read **with no image id**, and that is the whole trick: the device
     * token is checked before the parameters are (`requireDevice` runs first in
     * `handleComments`, and the mock does the same), so the answer splits cleanly in two —
     * 401 is "this token is not mine", and everything else, the 400 that a *good* token earns
     * here, is "it is". Asking about an image id instead would make this a question about
     * photos rather than about credentials, and the answers for a photo the server does not
     * hold are exactly the ones worth not depending on: 404 from the mock, and a 500 from
     * `tupingd` (which maps its store's "not found" to a server error — see
     * docs/tuping-design.md §17.7).
     */
    suspend fun deviceAccepted(): Result<Unit> = call {
        val url = base!!.newBuilder().addPathSegments("v1/comments").build()

        val response = http.newCall(
            Request.Builder()
                .url(url)
                .header("X-Device-Id", deviceId)
                .header("X-Device-Token", deviceToken)
                .get()
                .build(),
        ).executeSync()

        response.use { result ->
            // Only 401 is the server saying "not this device". Every other status — the 400
            // above, or anything a later protocol adds — was reached *after* the token was
            // checked, and is therefore an accepted device. A refusal keeps the server's own
            // sentence so the status row can put it on screen.
            if (result.code == 401) Result.failure(httpFailure(result)) else Result.success(Unit)
        }
    }

    /** Issues this device a token, storing nothing — the caller persists the result. */
    suspend fun issueDevice(
        id: String,
        name: String,
        existingToken: String,
    ): Result<DeviceCredentials> = call {
        val payload = JSONObject().apply {
            put("deviceId", id)
            put("name", name)
            if (existingToken.isNotBlank()) put("token", existingToken)
        }
        val response = http.newCall(
            Request.Builder()
                .url(base!!.newBuilder().addPathSegments("v1/devices").build())
                .post(payload.toString().toRequestBody(JSON))
                .build(),
        ).executeSync()

        response.use { result ->
            parseJson(result) { body ->
                DeviceCredentials(
                    deviceId = body.optString("deviceId", id),
                    deviceToken = body.optString("deviceToken"),
                    ownerToken = body.optString("ownerToken"),
                )
            }
        }
    }

    // -------------------------------------------------------------- websocket

    /**
     * Subscribes to a photo's new comments.
     *
     * [onComment] is called for each arriving comment and [onClosed] when the socket
     * goes away for any reason — the caller's job is to fall back to polling, and then
     * to stop. Nothing here retries by itself: a layer that reconnects on its own is a
     * layer that keeps talking to a server that is switched off.
     */
    fun live(
        imageId: String,
        onComment: (ReviewComment) -> Unit,
        onClosed: (String?) -> Unit,
    ): WebSocket? {
        val origin = base ?: return null

        // The scheme is left as http/https and OkHttp upgrades it itself.
        //
        // Writing `ws` here is what the first version did, by setting the scheme on
        // `HttpUrl.Builder` — which accepts only http and https and throws
        // `IllegalArgumentException: unexpected scheme: ws`. That exception was thrown
        // inside the registration coroutine, where nothing caught it, so **the app was
        // killed** the moment a slideshow started and 图评 tried to subscribe. On a
        // television that reads as "the app disappears when the slideshow begins", which
        // is about the worst symptom this feature could have had.
        val url = origin.newBuilder()
            .addPathSegments("v1/live/$imageId")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("X-Device-Id", deviceId)
            .header("X-Device-Token", deviceToken)
            .build()

        // A socket that cannot be opened is a missing danmaku layer, never a crash.
        return runCatching { this.http.newWebSocket(request, listener(onComment, onClosed)) }
            .getOrElse { error ->
                onClosed(error.message ?: error.javaClass.simpleName)
                null
            }
    }

    private fun listener(
        onComment: (ReviewComment) -> Unit,
        onClosed: (String?) -> Unit,
    ) = object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    // The one shape the design promises for both the history call and the
                    // live push, so the danmaku layer has a single input (§6.3).
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when (json.optString("t")) {
                        "comment" -> onComment(ReviewComment.from(json))
                        // "ping" and anything unrecognised are ignored on purpose: a
                        // newer server must not be able to break an older TV.
                        else -> Unit
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    onClosed(t.message ?: t.javaClass.simpleName)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    onClosed(reason.ifBlank { "closed" })
                }
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun <T> call(block: () -> Result<T>): Result<T> = withContext(Dispatchers.IO) {
        runCatching { block() }.getOrElse { error ->
            Result.failure(
                when (error) {
                    is ReviewException -> error
                    else -> ReviewException(ReviewError.Unreachable(describe(error)))
                },
            )
        }
    }

    /** Resolves a call; this runs on [Dispatchers.IO] through [call]. */
    private fun Call.executeSync(): Response = execute()

    private fun parseIdentity(response: Response, localId: String): Result<ReviewIdentity> =
        parseJson(response) { body ->
            ReviewIdentity(
                localId = localId,
                imageId = body.optString("imageId"),
                code = body.optString("code"),
                commentCount = body.optInt("commentCount"),
                matched = body.optString("matched"),
                qrUrl = body.optString("qrUrl"),
                thumbUrl = body.optString("thumbUrl"),
                fetchedAt = System.currentTimeMillis(),
            )
        }

    private fun <T> parseJson(response: Response, read: (JSONObject) -> T): Result<T> {
        if (!response.isSuccessful) return Result.failure(httpFailure(response))
        val text = response.body?.string().orEmpty()
        val json = runCatching { JSONObject(text) }.getOrElse {
            return Result.failure(
                ReviewException(ReviewError.Unreachable("unreadable response (${text.take(80)})")),
            )
        }
        return runCatching { Result.success(read(json)) }.getOrElse { error ->
            Result.failure(ReviewException(ReviewError.Unreachable(describe(error))))
        }
    }

    /**
     * Turns an error response into the protocol's own error, when it carries one.
     *
     * The message is the server's, because it was written for the person who will read
     * it — "发得太快了，歇一会儿" is actionable and "HTTP 429" is not.
     */
    private fun httpFailure(response: Response): ReviewException {
        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val envelope = runCatching { JSONObject(text).optJSONObject("error") }.getOrNull()
        if (envelope != null) {
            return ReviewException(
                ReviewError.Api(
                    envelope.optString("code", "server_error"),
                    envelope.optString("message", response.message),
                ),
            )
        }
        return ReviewException(ReviewError.Unreachable("HTTP ${response.code}"))
    }

    private fun describe(error: Throwable): String = when (error) {
        is SocketTimeoutException -> "timed out"
        is IOException -> error.message ?: "network unreachable"
        else -> error.message ?: error.javaClass.simpleName
    }

    companion object {
        /** The QR size the design asks for: at 1080p a code smaller than this is not readable at 3 m. */
        const val QR_SIZE = 440

        private const val SCOPE_IMAGE = "image"

        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val JPEG = "image/jpeg".toMediaType()

        /**
         * The review server's client.
         *
         * Short timeouts on purpose: this layer is an ornament on a photo viewer, and a
         * request that has not answered in a few seconds is worth abandoning so the next
         * photo can be registered. A long timeout would queue up work nobody will ever
         * look at.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        /**
         * Accepts `192.168.1.4:8080`, `http://host:8080`, `https://host`, or with a trailing
         * slash.
         *
         * A bare `host:port` is what a person types, and refusing it would mean the
         * settings row demands punctuation nobody can produce accurately on a phone
         * keyboard — the same reasoning as `BaseUrl`, but this one defaults to http
         * rather than https: most review servers are one container on a LAN with no
         * certificate. An address typed with `https://` is honoured exactly as typed, which
         * is how a deployment that terminates TLS is reached (server §17.23).
         */
        fun normalize(raw: String): HttpUrl? {
            // Cleaned again here rather than only in the store: this is the last thing
            // between an address and the wire, and a caller can hand it one that never went
            // through the store. See `cleanServerAddress` for the bytes at issue — a NUL on
            // the end of an otherwise perfect address used to switch 图评 off with nothing on
            // screen to explain it.
            val trimmed = cleanServerAddress(raw).trimEnd('/')
            if (trimmed.isEmpty()) return null
            val withScheme = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
                else -> "http://$trimmed"
            }
            return withScheme.toHttpUrlOrNull() ?: "http://$trimmed".toHttpUrlOrNull()
        }
    }
}

/**
 * One QR picture and the address it encodes.
 *
 * [pageUrl] is the server's own `X-Page-Url`, or null on a server that does not send it.
 * See [qrPointsElsewhere] for what the app does with it.
 */
data class QrImage(
    val png: ByteArray,
    val pageUrl: String?,
)

/** What `POST /v1/devices` answers with. */
data class DeviceCredentials(
    val deviceId: String,
    val deviceToken: String,
    val ownerToken: String,
)

/**
 * The `Throwable` a failed call has to carry, with the protocol's own answer inside it.
 *
 * `Result` needs a failure to be a `Throwable`, and `ReviewError` is a value — so this
 * wrapper is where the two meet. [ReviewError] is *not* the cause: an error is not a
 * throwable, so `cause` could never hold it, and a caller that reached for one (as this
 * repository did) silently fell back to reading `message` and reported
 * `Api(code=unauthorized, message=设备令牌不对)` where the server had said 设备令牌不对.
 * [reviewErrorOf] is the way in.
 */
internal class ReviewException(val error: ReviewError) : Exception(error.toString())

/**
 * Retries [block] with exponential backoff, returning the last failure.
 *
 * Used for the one call where a retry is worth it — registering a photo ahead of time,
 * where nobody is waiting and the server may just be starting — and not for anything a
 * person is watching.
 */
suspend fun <T> retry(times: Int = 3, firstDelayMs: Long = 400, block: suspend () -> Result<T>): Result<T> {
    var last: Result<T> = Result.failure(IllegalStateException("retry called with no attempts"))
    var delayMs = firstDelayMs
    repeat(times) { attempt ->
        last = block()
        if (last.isSuccess) return last
        if (attempt < times - 1) delay(delayMs)
        delayMs *= 2
    }
    return last
}
