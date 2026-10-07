package com.tvphoto.data.fn

import okhttp3.CookieJar
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Attaches `AccessToken` to media requests.
 *
 * Thumbnails and video streams are the one part of the API that is authenticated by
 * the token alone — no `authx` signature — so an interceptor is all that is needed
 * and the images can be handed straight to Coil.
 */
class MediaAuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token.isNullOrBlank()) {
            chain.request()
        } else {
            chain.request().newBuilder().header("AccessToken", token).build()
        }
        return chain.proceed(request)
    }
}

object FnHttp {

    /**
     * Client for the JSON API and the login WebSocket.
     *
     * Both clients are wired with [FnCertificateTrust]: the NAS serves a self-signed
     * certificate with no usable `subjectAltName`, so the default trust rules cannot
     * connect to it at all.
     */
    fun apiClient(cookieJar: CookieJar, trust: FnCertificateTrust): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .tls(trust)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(25, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

    /**
     * Client for thumbnails and video. Kept separate because the login WebSocket
     * lives on [apiClient], and because full-size photos deserve a longer read
     * timeout. It shares the cookie jar, since the gateway challenge covers media
     * paths too.
     *
     * Its connection limits are raised deliberately. OkHttp lets five requests per
     * host run at once by default, and this client carries both the images on screen
     * and the prefetcher warming the next fifty — so a thumbnail for a card the user
     * has just focused used to wait behind full-size originals nobody is looking at
     * yet, which reads on a TV as the grid stuttering as focus moves. Eight per host
     * leaves room for the visible thumbnails and is still a trivial load for a NAS on
     * a LAN.
     */
    fun mediaClient(
        cookieJar: CookieJar,
        tokenProvider: () -> String?,
        trust: FnCertificateTrust,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .tls(trust)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .dispatcher(Dispatcher().apply {
                maxRequests = MEDIA_MAX_REQUESTS
                maxRequestsPerHost = MEDIA_MAX_REQUESTS_PER_HOST
            })
            .addInterceptor(MediaAuthInterceptor(tokenProvider))
            .build()

    /**
     * How many media requests may be in flight. The prefetcher itself runs four at a
     * time (see `ImagePrefetcher`), so these leave the rest of the budget to whatever
     * is actually on screen.
     */
    private const val MEDIA_MAX_REQUESTS = 16
    private const val MEDIA_MAX_REQUESTS_PER_HOST = 8

    /** The same trust decision on every connection the app makes to the NAS. */
    private fun OkHttpClient.Builder.tls(trust: FnCertificateTrust): OkHttpClient.Builder =
        sslSocketFactory(trust.sslContext().socketFactory, trust.trustManager)
            .hostnameVerifier(trust.hostnameVerifier)
}
