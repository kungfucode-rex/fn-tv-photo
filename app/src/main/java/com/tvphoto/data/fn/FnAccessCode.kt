package com.tvphoto.data.fn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Handles fnOS's 访问码 (access code): a gate placed in front of the login entry.
 *
 * It is not part of the photo API. The gateway intercepts *every* path on the web
 * port and serves a standalone challenge page instead, advertising itself with
 * `X-Trim-Safe-Code-Challenge: 1`. The WebSocket login endpoint then refuses the
 * upgrade with `401`, so a client that ignores the gate cannot log in at all —
 * which is exactly what happened before this existed.
 *
 * The exchange is taken from that challenge page's own script:
 *
 * ```
 * GET /access_code_verify
 * x-access-code: <base64 of the code, UTF-8>
 * x-access-source: web
 * ```
 *
 * A 2xx response installs the authorising cookie; after that the same client is let
 * through. Note this is an official, documented feature
 * (https://help.fnnas.com/articles/v1/safety/access-code) that covers the web UI,
 * the official apps and the TV clients — but deliberately not WebDAV/SMB/FTP.
 */
class FnAccessCodeClient(private val httpClient: OkHttpClient) {

    enum class Result {
        /** The code was accepted; the cookie jar now carries the authorisation. */
        AUTHORIZED,

        /** The server said the code is wrong (401/403/429). */
        REJECTED,

        /** The endpoint is absent, so this server has no access code enabled. */
        NOT_REQUIRED,

        /** Could not reach the server at all. */
        UNREACHABLE,
    }

    suspend fun authorize(baseUrl: String, code: String): Result =
        withContext(Dispatchers.IO) {
            val url = baseUrl.trimEnd('/') + VERIFY_PATH
            val request = Request.Builder()
                .url(url)
                .header("x-access-code", encode(code))
                .header("x-access-source", "web")
                .get()
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> Result.AUTHORIZED
                        // 404 means this fnOS build has no such gate to pass.
                        response.code == 404 -> Result.NOT_REQUIRED
                        response.code == 401 || response.code == 403 || response.code == 429 ->
                            Result.REJECTED
                        else -> Result.UNREACHABLE
                    }
                }
            } catch (io: java.io.IOException) {
                Result.UNREACHABLE
            }
        }

    /** Standard base64 of the UTF-8 bytes, matching the page's `btoa` over `TextEncoder`. */
    private fun encode(code: String): String = base64Encode(code.toByteArray(Charsets.UTF_8))

    companion object {
        const val VERIFY_PATH = "/access_code_verify"

        /** Header the gateway uses to announce the challenge. */
        const val CHALLENGE_HEADER = "X-Trim-Safe-Code-Challenge"
    }
}

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/**
 * Padded standard base64.
 *
 * Written out rather than delegating to `android.util.Base64` so the protocol layer
 * stays free of Android types and can be covered by plain JVM unit tests. It matches
 * `btoa` exactly, including the `=` padding the server expects.
 */
internal fun base64Encode(bytes: ByteArray): String {
    val out = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0

    while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
            ((bytes[i + 1].toInt() and 0xFF) shl 8) or
            (bytes[i + 2].toInt() and 0xFF)
        out.append(BASE64_ALPHABET[(n ushr 18) and 63])
            .append(BASE64_ALPHABET[(n ushr 12) and 63])
            .append(BASE64_ALPHABET[(n ushr 6) and 63])
            .append(BASE64_ALPHABET[n and 63])
        i += 3
    }

    when (bytes.size - i) {
        1 -> {
            val n = (bytes[i].toInt() and 0xFF) shl 16
            out.append(BASE64_ALPHABET[(n ushr 18) and 63])
                .append(BASE64_ALPHABET[(n ushr 12) and 63])
                .append("==")
        }

        2 -> {
            val n = ((bytes[i].toInt() and 0xFF) shl 16) or
                ((bytes[i + 1].toInt() and 0xFF) shl 8)
            out.append(BASE64_ALPHABET[(n ushr 18) and 63])
                .append(BASE64_ALPHABET[(n ushr 12) and 63])
                .append(BASE64_ALPHABET[(n ushr 6) and 63])
                .append('=')
        }
    }

    return out.toString()
}
