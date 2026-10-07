package com.tvphoto.data.fn

/**
 * Which representation of the query string feeds the `authx` payload hash.
 *
 * Two public reverse-engineered clients disagree here and both appear to work
 * against real servers:
 *
 *  - `RAW_VALUES` hashes the parameters sorted by key with their decoded values
 *    (`start_time=2026:01:30 00:00:00`).
 *  - `ENCODED_QUERY` hashes the exact percent-encoded query that goes on the wire
 *    (`start_time=2026%3A01%3A30+00%3A00%3A00`).
 *
 * The two only diverge when a value contains characters that need escaping, which
 * `gallery/getList` hits immediately because of its `YYYY:MM:DD HH:MM:SS` format.
 * Rather than guess, the client starts on [RAW_VALUES] and transparently switches
 * to [ENCODED_QUERY] the first time the server answers with a signature error.
 * See [SignModeNegotiator].
 */
enum class SignMode(val id: String) {
    RAW_VALUES("raw"),
    ENCODED_QUERY("encoded"),
    ;

    fun next(): SignMode = if (this == RAW_VALUES) ENCODED_QUERY else RAW_VALUES

    companion object {
        fun fromId(id: String?): SignMode? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Builds the `authx` header: `nonce=<6 digits>&timestamp=<ms>&sign=<md5>`.
 *
 * The salt and secret are global constants baked into the fnOS web frontend, not
 * per-install values, and the login response's own `secret` plays no part here.
 */
internal object FnSigner {

    const val SALT = FnConfig.SALT
    const val SECRET = FnConfig.SIGN_SECRET

    /** Sorts by key and joins as `k=v`, dropping JS-style null/undefined values. */
    fun rawQuery(params: List<Pair<String, String>>): String =
        sorted(params).joinToString("&") { (key, value) -> "$key=$value" }

    /** Same ordering, but percent-encoded the way `URLSearchParams` would render it. */
    fun encodedQuery(params: List<Pair<String, String>>): String =
        sorted(params).joinToString("&") { (key, value) ->
            "${FnUrlEncoder.encode(key)}=${FnUrlEncoder.encode(value)}"
        }

    private fun sorted(params: List<Pair<String, String>>): List<Pair<String, String>> =
        params
            .filter { it.second != "null" && it.second != "undefined" }
            .sortedBy { it.first }

    fun authX(
        path: String,
        method: String,
        mode: SignMode,
        rawQuery: String?,
        encodedQuery: String?,
    ): String {
        val nonce = (100_000 + FnCrypto.secureRandom.nextInt(900_000)).toString()
        val timestamp = System.currentTimeMillis().toString()

        // GET hashes the query; everything else hashes the body verbatim.
        val payload = if (method.equals("GET", ignoreCase = true)) {
            when (mode) {
                SignMode.ENCODED_QUERY -> encodedQuery.orEmpty()
                SignMode.RAW_VALUES -> rawQuery.orEmpty()
            }
        } else {
            rawQuery.orEmpty()
        }

        val paramHash = FnCrypto.md5(payload)
        val sign = FnCrypto.md5(
            listOf(SALT, path, nonce, timestamp, paramHash, SECRET).joinToString("_"),
        )
        return "nonce=$nonce&timestamp=$timestamp&sign=$sign"
    }
}
