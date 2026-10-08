package com.tvphoto.data

import android.content.Context
import android.util.Log
import androidx.media3.datasource.DataSource
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.tvphoto.data.fn.FnAccessCodeClient
import com.tvphoto.data.fn.FnApi
import com.tvphoto.data.fn.FnAuthClient
import com.tvphoto.data.fn.FnCertificateTrust
import com.tvphoto.data.fn.FnCookieJar
import com.tvphoto.data.fn.FnHttp
import com.tvphoto.data.fn.FnHttpClient
import com.tvphoto.data.fn.SignMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import okio.Path.Companion.toPath

/**
 * Hand-rolled dependency container.
 *
 * The app is small enough that a DI framework would cost more than it saves; the
 * one requirement is that the media client and the session share a single token
 * source, which is why they are built together here.
 */
class AppContainer(context: Context) {

    private val appContext: Context = context.applicationContext

    val settings: SettingsStore = SettingsStore(appContext)

    /** Records an uncaught exception so a crash on the TV can be read back. */
    val crashLog: CrashLog = CrashLog(appContext.filesDir)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Shared by every client so the access-code authorisation cookie obtained during
     * sign-in is replayed on the login WebSocket, the JSON API and media requests.
     */
    private val cookieJar = FnCookieJar()

    /**
     * The NAS serves a self-signed certificate that no trust store can validate, so
     * every connection goes through this: first contact pins the fingerprint, a later
     * change is refused once and reported. See [FnCertificateTrust].
     */
    private val certificateTrust = FnCertificateTrust(settings)

    private val apiHttp: OkHttpClient = FnHttp.apiClient(cookieJar, certificateTrust)

    val sessions: SessionRepository = SessionRepository(
        settings = settings,
        authClient = FnAuthClient(apiHttp),
        accessCodeClient = FnAccessCodeClient(apiHttp),
        certificateTrust = certificateTrust,
        httpClient = apiHttp,
        scope = scope,
    )

    private val signedHttp = FnHttpClient(apiHttp, settings)

    private val api = FnApi(signedHttp)

    val photos = PhotoRepository(sessions, api)

    /**
     * The slideshow's background music. Owned here rather than by the viewer: the
     * viewer is recomposed, and can be left and re-entered, while the player is one
     * audio stack for the process.
     */
    val slideshowMusic: SlideshowMusicPlayer = SlideshowMusicPlayer(appContext)

    /** Shared by Coil and Media3 so thumbnails and video carry the token and cookie. */
    val mediaHttp: OkHttpClient =
        FnHttp.mediaClient(cookieJar, { sessions.token }, certificateTrust)

    /**
     * Where the viewer's video player reads from.
     *
     * The same client the images go through, rather than a connection of the player's
     * own: the NAS certificate is pinned rather than issued by a trusted root, the
     * 访问码 travels as a cookie and the stream is authorised by the `AccessToken`
     * header. See [OkHttpDataSource].
     */
    val videoStreams: DataSource.Factory = OkHttpDataSource.Factory(mediaHttp)

    val signMode: SignMode get() = signedHttp.currentSignMode

    fun forceSignMode(mode: SignMode) = signedHttp.forceSignMode(mode)

    /**
     * Points Coil at the authenticated client. Thumbnails need the `AccessToken`
     * header but no signature, so plain image URLs work.
     *
     * The disk cache is a journaled LRU: once it exceeds [DISK_CACHE_BYTES] the least
     * recently used entries are evicted automatically, which is exactly the "keep 5 GB,
     * drop the oldest" behaviour. Video never reaches this cache — Media3 streams with
     * no cache configured, and the prefetcher is only ever handed stills.
     *
     * The disk cache is also the one part of start-up that touches storage the app does
     * not control: a cache directory the system has half-cleaned, a full volume, a
     * journal left behind by a kill. Any of those failing used to mean the first image
     * request threw inside composition, which is a crash on launch; now it costs the
     * disk cache and nothing else, and the app runs memory-only.
     */
    fun installImageLoader() {
        SingletonImageLoader.setSafe { context ->
            runCatching { imageLoader(context, buildDiskCache()) }
                .recoverCatching { error ->
                    Log.w(TAG, "image disk cache unavailable; continuing without it", error)
                    imageLoader(context, diskCache = null)
                }
                .getOrThrow()
        }
    }

    private fun buildDiskCache(): DiskCache = DiskCache.Builder()
        .directory(appContext.cacheDir.resolve("image_cache").absolutePath.toPath())
        .maxSizeBytes(DISK_CACHE_BYTES)
        .build()

    private fun imageLoader(context: Context, diskCache: DiskCache?): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { mediaHttp }))
            }
            .apply {
                if (diskCache != null) {
                    diskCache { diskCache }
                }
            }
            .build()

    /** Warms the image cache for fast full-screen viewing. */
    val prefetcher: ImagePrefetcher = ImagePrefetcher(appContext) {
        SingletonImageLoader.get(appContext)
    }

    private companion object {
        /** 5 GB of image cache, LRU-evicted. */
        const val DISK_CACHE_BYTES = 5L * 1024 * 1024 * 1024

        const val TAG = "AppContainer"
    }
}
