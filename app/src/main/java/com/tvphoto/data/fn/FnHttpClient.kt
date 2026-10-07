package com.tvphoto.data.fn

/** Persistence boundary so the protocol layer stays free of Android types. */
interface SignModeStore {
    fun load(): SignMode
    fun save(mode: SignMode)
}

/**
 * Signed HTTP access to the `/p/api/v1/...` endpoints.
 *
 * Every request carries `AccessToken` plus the `authx` signature. When the server
 * rejects a signature the client flips [SignMode], replays the request once and
 * remembers whichever representation worked, so an incorrect initial guess costs a
 * single extra round trip and never comes back.
 */
class FnHttpClient(
    private val httpClient: okhttp3.OkHttpClient,
    private val signModeStore: SignModeStore,
) {

    @Volatile
    private var signMode: SignMode = signModeStore.load()

    val currentSignMode: SignMode get() = signMode

    /** Overrides the negotiated mode, e.g. from the settings screen. */
    fun forceSignMode(mode: SignMode) {
        signMode = mode
        signModeStore.save(mode)
    }

    suspend fun getJson(
        session: FnSession,
        path: String,
        params: List<Pair<String, String>> = emptyList(),
    ): org.json.JSONObject = try {
        execute(session, path, params, signMode)
    } catch (rejected: FnSignatureException) {
        val fallback = rejected.let { signMode.next() }
        val json = execute(session, path, params, fallback)
        // Only persist once the alternative has actually been accepted.
        signMode = fallback
        signModeStore.save(fallback)
        json
    }

    private suspend fun execute(
        session: FnSession,
        path: String,
        params: List<Pair<String, String>>,
        mode: SignMode,
    ): org.json.JSONObject = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val encoded = FnSigner.encodedQuery(params)
        val raw = FnSigner.rawQuery(params)

        val url = buildString {
            append(session.baseUrl)
            append(path)
            if (encoded.isNotEmpty()) {
                append('?')
                append(encoded)
            }
        }

        val request = okhttp3.Request.Builder()
            .url(url)
            .header("AccessToken", session.token)
            .header(
                "authx",
                FnSigner.authX(
                    path = path,
                    method = "GET",
                    mode = mode,
                    rawQuery = raw.ifEmpty { null },
                    encodedQuery = encoded.ifEmpty { null },
                ),
            )
            .get()
            .build()

        val body = try {
            httpClient.newCall(request).execute().use { response ->
                val text = response.body.string()
                if (text.isBlank()) {
                    if (!response.isSuccessful) {
                        throw FnApiException(response.code, response.message)
                    }
                    throw FnApiException(-1, "empty response body")
                }
                text
            }
        } catch (io: java.io.IOException) {
            throw FnNetworkException("network request failed: ${io.message}", io)
        }

        val json = runCatching { org.json.JSONObject(body) }.getOrElse {
            throw FnApiException(-1, "response was not JSON")
        }
        json.throwIfError()
        json
    }

    private fun org.json.JSONObject.throwIfError() {
        // Most endpoints answer with `code`; a few (`app/version`) use `errno`.
        val code = if (has("code")) optInt("code") else optInt("errno", 0)
        if (code == 0) return
        val message = optString("msg").ifBlank { optString("result") }
        throw when (code) {
            401, 5001 -> FnSessionExpiredException()
            5000 -> FnSignatureException()
            else -> FnApiException(code, message)
        }
    }
}
