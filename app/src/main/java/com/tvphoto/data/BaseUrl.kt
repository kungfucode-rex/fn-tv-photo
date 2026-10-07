package com.tvphoto.data

/**
 * Maps full-width characters a Chinese IME readily produces for an address.
 *
 * Typing `10.0.2.2` with a Chinese keyboard can yield ideographic full stops
 * (`。`), and the same applies to full-width digits and colons. Relying on the URL
 * stack's IDN nameprep to fold these back is incidental and hides what the user
 * actually entered, so they are normalised explicitly here.
 */
internal fun foldFullWidth(input: String): String = buildString(input.length) {
    for (ch in input) {
        append(
            when (ch) {
                '\u3002', '\uFF0E', '\uFF61' -> '.'   // 。 ． ｡
                '\uFF1A' -> ':'                        // ：
                '\uFF0F' -> '/'                        // ／
                '\uFF0D', '\u2010', '\u2013' -> '-'    // － ‐ –
                in '\uFF10'..'\uFF19' -> ch - 0xFEE0   // full-width digits
                in '\uFF21'..'\uFF3A' -> ch - 0xFEE0   // full-width uppercase
                in '\uFF41'..'\uFF5A' -> ch - 0xFEE0   // full-width lowercase
                else -> ch
            },
        )
    }
}

/** The fnOS secure web port, used when the user types an address with no port. */
private const val DEFAULT_HTTPS_PORT = 5667

/**
 * Normalises whatever the user typed into a base URL, without a trailing slash.
 *
 * **HTTPS only.** `192.168.1.10` becomes `https://192.168.1.10:5667`; an explicit port is
 * kept. A scheme the user typed is folded away rather than honoured, because there is no
 * longer a cleartext path to select: `http://nas:50316` becomes `https://nas:50316`, which
 * simply fails to complete a handshake if that port is not the secure one.
 */
fun normalizeBaseUrl(input: String): String {
    val trimmed = foldFullWidth(input).trim()
    if (trimmed.isEmpty()) return ""

    // Strip the scheme before touching trailing slashes: trimming "/" off the end of
    // "https://" would otherwise leave the bare string "https:".
    val rest = trimmed
        .substringAfter("://", trimmed)
        .substringBefore('/')
        .trim()
    if (rest.isBlank()) return ""

    val hasPort = if (rest.startsWith("[")) {
        // IPv6 literal, e.g. [fd00::1]:5667
        rest.substringAfter("]", "").startsWith(":")
    } else {
        rest.contains(':')
    }
    if (hasPort) return "https://$rest"

    return "https://$rest:$DEFAULT_HTTPS_PORT"
}

/** The `host:port` form shown in the UI, without a scheme. */
fun displayHost(baseUrl: String): String =
    baseUrl.substringAfter("://", baseUrl).trimEnd('/')
