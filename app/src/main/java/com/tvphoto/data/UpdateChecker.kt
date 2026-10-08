package com.tvphoto.data

import com.tvphoto.BuildConfig
import com.tvphoto.domain.AppVersion
import com.tvphoto.domain.isUpdateAvailable
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the newest published release says about itself.
 *
 * [apkUrl] and [sha256] are what make an in-app upgrade possible at all: the file to
 * fetch, and a means of proving that what arrived is that file. A release that names
 * itself but carries neither - one published before `version.json` existed - is still
 * worth reporting, and [isInstallable] is how the settings row tells those two cases
 * apart.
 */
data class UpdateRelease(
    val versionName: String,
    val versionCode: Int? = null,
    val apkUrl: String? = null,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
) {
    /** True when this release can be downloaded and verified from inside the app. */
    val isInstallable: Boolean
        get() = !apkUrl.isNullOrBlank() && !sha256.isNullOrBlank()
}

/** The outcome of one check, as the settings row renders it. */
sealed interface UpdateCheckResult {

    /** A newer release exists on the release page. */
    data class Newer(val release: UpdateRelease) : UpdateCheckResult

    /** This build is the newest one published. */
    data object UpToDate : UpdateCheckResult

    /** The check could not be completed; [reason] is shown on the row and logged. */
    data class Failed(val reason: String) : UpdateCheckResult
}

/**
 * Asks where this app is published what the newest release is.
 *
 * There are two such places, and both are asked at once because they are reachable from
 * different halves of the world:
 *
 *  - **Gitee**, which is domestic to this app's users. A Chinese home network that
 *    blackholes github.com - the app's own author has one - answers Gitee normally.
 *  - **GitHub**, which is where the project lives and what everyone else can reach.
 *
 * Asking them in sequence produced the worst moment this feature has had: on a network
 * that drops packets to GitHub, the check sat on 检查中… for twenty-one seconds before
 * saying anything, because it waited out the first source's whole timeout before trying
 * the second. Asked together, whichever can answer does, and the other is cancelled once
 * a short grace window has passed - long enough for a slower source to report a *newer*
 * version, short enough that a dead one costs two seconds rather than ten.
 *
 * Neither host's *API* is used anywhere here: GitHub's answers 403 once the anonymous
 * quota of 60 requests an hour per address is gone - a quota this project's own network
 * had already spent - and a failure the user cannot act on is worse than no check.
 *
 * The client is its own, and that is not an oversight. The two clients in [FnHttp] trust a
 * pinned NAS certificate and attach an `AccessToken`; pointed at either host they would
 * refuse a perfectly valid certificate and leak a token to a third party. Here the system
 * trust store applies, as it does for any other HTTPS request on the device.
 */
class UpdateChecker(
    private val client: OkHttpClient = defaultClient(),
    private val installedVersion: String = BuildConfig.VERSION_NAME,
    private val installedCode: Int? = BuildConfig.VERSION_CODE,
) {

    /**
     * The page a user can open on a phone or a computer when the in-app route is blocked.
     *
     * The Gitee one, because that is the one this app's users can normally open; the
     * GitHub page is reachable from fewer of these networks than the app itself is.
     */
    val releasePageUrl: String get() = SOURCES.first().releasePageUrl

    suspend fun check(): UpdateCheckResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val startedAt = System.currentTimeMillis()

            // Both at once. `fetch` never throws: a source that cannot answer comes back
            // as a reason, which is what the row shows when nothing answers at all.
            val attempts = SOURCES.map { source -> async { fetch(source) } }

            val (firstIndex, first) = select<Pair<Int, Attempt<UpdateRelease>>> {
                attempts.forEachIndexed { index, attempt -> attempt.onAwait { index to it } }
            }

            // The grace window. The source that answered first is not necessarily the one
            // with the newest release - a manifest that has not been updated for this
            // release reads as an older version - so the slower answer gets this long to
            // arrive and be compared. Whatever is still running when it closes is
            // cancelled rather than left to finish on a thread nobody is waiting for.
            val later = withTimeoutOrNull(GRACE_MILLIS) {
                attempts.filterIndexed { index, _ -> index != firstIndex }.map { it.await() }
            }.orEmpty()

            val all = listOf(first) + later
            val usable = all.mapNotNull { it.value }

            val newest = newestRelease(usable, installedVersion, installedCode)

            when {
                newest != null -> UpdateCheckResult.Newer(newest)

                // A source answered and nothing it named is newer than this build.
                usable.isNotEmpty() -> UpdateCheckResult.UpToDate

                else -> UpdateCheckResult.Failed(
                    reason = "${(System.currentTimeMillis() - startedAt) / 1000.0}s · " +
                        SOURCES.mapIndexed { index, source ->
                            "${source.name}: ${all.getOrNull(index)?.why ?: "no answer"}"
                        }.joinToString(" · "),
                )
            }
        }
    }

    /**
     * One source's newest release.
     *
     * The manifest first, because it is the only thing that carries a hash. A manifest
     * that is merely *missing* - a release published before `version.json` existed, or a
     * raw file not yet written - falls through to the tag, which still names a version and
     * is therefore still enough to tell someone an update exists and point them at the
     * manual route. A source that could not be *reached* does not fall through: the same
     * dead network would answer the second request the same way.
     */
    private suspend fun fetch(source: Source): Attempt<UpdateRelease> {
        val manifest = fetchManifest(source)
        manifest.value?.let { return manifest }
        if (manifest.unreachable) return manifest

        val tag = fetchTag(source)
        val version = tag.value ?: return Attempt(null, tag.why, tag.unreachable)
        return Attempt(UpdateRelease(versionName = version), tag.why)
    }

    private suspend fun fetchManifest(source: Source): Attempt<UpdateRelease> {
        val request = Request.Builder()
            .url(source.manifestUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .build()
        return attempt {
            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    Attempt(null, "HTTP ${response.code}")
                } else {
                    val body = response.body?.string()
                    when {
                        body == null -> Attempt(null, "empty body")
                        else -> Attempt(parseUpdateManifest(body), "answered, but not a manifest")
                    }
                }
            }
        }
    }

    /**
     * The tag of the newest release, read from where `releases/latest` redirected to.
     *
     * Both hosts answer that with a redirect to `/releases/tag/<tag>`, and OkHttp follows
     * it, so the answer is in the URL finally requested - no header parsing, and no
     * dependence on the HTML body, which is never read.
     */
    private suspend fun fetchTag(source: Source): Attempt<String> {
        val request = Request.Builder()
            .url(source.latestUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        return attempt {
            client.newCall(request).awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    Attempt(null, "HTTP ${response.code}")
                } else {
                    Attempt(
                        releaseTagFrom(response.request.url.toString()),
                        "landed on ${response.request.url.encodedPath}",
                    )
                }
            }
        }
    }

    /** Runs [block], turning whatever it throws into a reason instead of a crash. */
    private suspend fun <T> attempt(block: suspend () -> Attempt<T>): Attempt<T> = try {
        block()
    } catch (error: Throwable) {
        Attempt(null, describe(error), unreachable = true)
    }

    /**
     * One line naming what went wrong, for the row and the log.
     *
     * The exception's own class and message, not a tidied summary: this is the text that
     * gets read off a television screen to somebody who can act on it, and "Unable to
     * resolve host" means something entirely different from "connect timed out" - one is
     * DNS, the other is a connection that went nowhere. Truncated because a television row
     * has room for about one line of it.
     */
    private fun describe(error: Throwable): String {
        val name = error.javaClass.simpleName
        val message = error.message?.takeIf { it.isNotBlank() } ?: return name
        return "$name: $message".take(MAX_REASON_CHARS)
    }

    /**
     * Runs one call and comes back when it is done, so that a source still waiting when
     * the grace window shuts can be cancelled rather than left running.
     *
     * OkHttp's blocking `execute` cannot be interrupted by a coroutine, which is what this
     * exists to avoid: without it, "give up on the slow source" would mean abandoning a
     * thread for the rest of its timeout instead of actually stopping the request.
     */
    private suspend fun Call.awaitResponse(): Response =
        suspendCancellableCoroutine { continuation: CancellableContinuation<Response> ->
            enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    if (continuation.isActive) continuation.resume(response) else response.close()
                }
            })
            continuation.invokeOnCancellation { runCatching { cancel() } }
        }

    /**
     * What one attempt at one source produced: either a value, or the reason there is
     * none. The reason is carried rather than logged and dropped, because the row that
     * shows the failure is the only place on a television it can be read.
     *
     * [unreachable] separates "the network would not deliver this" from "the network
     * delivered something unusable", which is what decides whether a second request to the
     * same source is worth making at all.
     */
    private class Attempt<out T>(
        val value: T?,
        val why: String,
        val unreachable: Boolean = false,
    )

    /** One place the app is published, and the two addresses that describe it. */
    private data class Source(
        val name: String,
        val manifestUrl: String,
        val latestUrl: String,
    ) {
        val releasePageUrl: String get() = latestUrl
    }

    companion object {
        /**
         * Where releases are published, most reachable first.
         *
         * Gitee carries `version.json` as a file in the repository, which is the one
         * address shape it serves anonymously and predictably; GitHub carries it as an
         * asset of the newest release, which is a single request and needs no commit.
         */
        private val SOURCES = listOf(
            Source(
                name = "Gitee",
                manifestUrl = "https://gitee.com/kungfucode/fn-tv-photo/raw/master/version.json",
                latestUrl = "https://gitee.com/kungfucode/fn-tv-photo/releases/latest",
            ),
            Source(
                name = "GitHub",
                manifestUrl = "https://github.com/kungfucode-rex/fn-tv-photo/releases/latest/download/version.json",
                latestUrl = "https://github.com/kungfucode-rex/fn-tv-photo/releases/latest",
            ),
        )

        /** The release asset the app reads; see tools/release-manifest.ps1. */
        const val MANIFEST_NAME = "version.json"

        /**
         * GitHub answers 403 to a request with no User-Agent, so this is required rather
         * than polite. It also names the caller in both repositories' traffic.
         */
        private const val USER_AGENT = "FN-Photo-TV"

        /** How much of a failure reason the settings row has room for. */
        private const val MAX_REASON_CHARS = 140

        /**
         * How long the source that answered first waits to be beaten by the other one.
         *
         * Measured rather than guessed: Gitee's raw file answers in about 0.25 s and
         * GitHub's release asset in 0.7-1.2 s, so three seconds is several times either of
         * them - while a source that is never going to answer costs three seconds instead
         * of its whole timeout. The first read of a file newly committed to Gitee took
         * 3.7 s once, against a cold CDN, which is the case this has to cover and a two
         * second window would have missed: it would have taken the other host's older
         * answer and reported this build as up to date.
         *
         * The price of the window closing too early is a release offered one check later
         * than it could have been; the price of it being too long is paid on every check
         * by everyone whose second source is unreachable.
         */
        private const val GRACE_MILLIS = 3_000L

        /**
         * The budget for one request, and it is deliberately short.
         *
         * A network that blackholes a host does not refuse the connection - it accepts the
         * packets and never answers - so the only thing that ends the attempt is a
         * timeout. OkHttp's retry-on-connection-failure then walks the addresses the name
         * resolves to, one connect timeout each, and 8 s apiece is how a real television
         * came to sit on 检查中… for twenty-one seconds. Ten seconds bounds the whole call
         * whatever the address count is, and the row names the reason when it gives up, so
         * the retry is the user's to make rather than something to sit through.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/**
 * The release to offer, given what every source answered.
 *
 * Two sources are asked at once, so their answers can disagree - GitHub can still be
 * serving the release before last while Gitee already carries the newest one, which is
 * exactly the state the two were in the first time this ran. Taking whichever answered
 * first would then depend on the weather: the slower source's answer is only slower, not
 * older, so the releases are compared and the highest one wins.
 *
 * Null when nothing on offer is newer than what is installed, which is the same answer as
 * "up to date" and is left to the caller to phrase.
 */
fun newestRelease(
    releases: List<UpdateRelease>,
    installedVersion: String?,
    installedCode: Int?,
): UpdateRelease? = releases
    .filter { release ->
        isUpdateAvailable(
            remoteVersion = release.versionName,
            remoteCode = release.versionCode,
            localVersion = installedVersion,
            localCode = installedCode,
        )
    }
    .maxWithOrNull(
        compareBy(
            { it.versionCode ?: 0 },
            { AppVersion.parse(it.versionName) ?: AppVersion(listOf(0)) },
        ),
    )

/**
 * Reads `version.json` as written by tools/release-manifest.ps1.
 *
 * Returns null rather than throwing when the document is not the shape this expects: a
 * captive portal, an error page and a release published before the manifest existed all
 * arrive here as "not a manifest", and none of them is an upgrade.
 */
fun parseUpdateManifest(json: String): UpdateRelease? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    val versionName = root.optString("versionName").trim()
    if (versionName.isEmpty()) return null

    val versionCode = root.optInt("versionCode", 0).takeIf { it > 0 }
    val apkUrl = root.optString("apkUrl").trim().takeIf { it.isNotEmpty() }
    // One spelling: the hash is compared against one computed locally, and a manifest
    // that wrote it in lower case, or with spaces, must not read as a mismatch.
    val sha256 = root.optString("sha256").trim().replace(" ", "").uppercase().takeIf { it.isNotEmpty() }
    val sizeBytes = root.optLong("sizeBytes", 0L).takeIf { it > 0 }

    return UpdateRelease(
        versionName = versionName,
        versionCode = versionCode,
        apkUrl = apkUrl,
        sha256 = sha256,
        sizeBytes = sizeBytes,
    )
}

/**
 * The tag out of a URL like `https://github.com/owner/repo/releases/tag/v1.4.0`.
 *
 * Null when the URL is not a tag URL at all - `releases/latest` lands on the releases
 * index when a repository has no published release, and that must read as "nothing to
 * report" rather than as some version scraped out of a path.
 */
fun releaseTagFrom(url: String): String? {
    val marker = "/releases/tag/"
    val index = url.indexOf(marker)
    if (index < 0) return null
    val tag = url.substring(index + marker.length)
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    return tag.takeIf { it.isNotEmpty() }
}
