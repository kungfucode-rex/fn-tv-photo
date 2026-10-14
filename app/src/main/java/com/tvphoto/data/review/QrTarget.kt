package com.tvphoto.data.review

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.security.MessageDigest

/**
 * Where the QR picture on screen actually sends a phone, versus where this television is
 * talking.
 *
 * `GET /v1/qr/<code>.png` answers with an `X-Page-Url` header carrying the address the
 * picture encodes — the server added it so that a machine with no camera can ask "which
 * URL will my guests open?" (`server/internal/api/photos.go`). The app threw that header
 * away for a year, and the cost was a failure that no screen in the app could explain:
 * after a deployment moved, or after the codes were re-pointed with
 * `POST /v1/admin/codes/rebind`, the television kept drawing whatever picture it had
 * already cached, and the only way to find out was to scan the code with a phone
 * (docs/tuping-design.md §17.31).
 *
 * [qrPointsElsewhere] is the comparison, and it is deliberately a *string* rather than a
 * boolean: the useful sentence is "this code opens photos.example.com", not "mismatch".
 *
 * A difference is not by itself a fault. A deployment may pin `TUPING_BASE_URL` to a public
 * address for phones while the television reaches the same server over the LAN, and then
 * the code *should* point somewhere other than the address the app knows. The row says
 * what the code points at in that case too — that is the fact that was missing, and it does
 * not claim anything is broken.
 */

/**
 * The host and port of the address this app is configured for, or "" when there is none.
 *
 * A scheme is added when one is missing: the settings row is typed by hand and a bare
 * `192.168.31.14:8080` is what a person produces (see [ReviewClient.normalize]).
 */
internal fun reviewHostOf(raw: String?): String {
    val url = raw?.let { ReviewClient.normalize(it) } ?: return ""
    return url.host.lowercase() + ":" + url.port
}

/**
 * The host and port of an `X-Page-Url`, or "" when it is not an absolute address.
 *
 * Stricter than [reviewHostOf] on purpose, and the difference is not cosmetic: OkHttp reads
 * `http:///c/WxQJsA` as host `c`, so a header that is a bare path — a mistake on the other
 * end, or a hand-rolled response — would otherwise become the sentence "this code opens
 * `c:80`" on the settings row. Only a string that already names a scheme is a page address
 * here; anything else is "nothing to compare" and stays quiet.
 */
internal fun pageHostOf(raw: String?): String {
    val cleaned = cleanServerAddress(raw.orEmpty()).trim()
    if (!cleaned.startsWith("http://") && !cleaned.startsWith("https://")) return ""
    val url = cleaned.toHttpUrlOrNull() ?: return ""
    if (url.host.isBlank()) return ""
    return url.host.lowercase() + ":" + url.port
}

/**
 * The `host:port` a phone will open, when it is not the server this app is configured for.
 *
 * Null means "the same place, or nothing to compare": an answer without the header (an
 * older server, or a mock that never learned to send it) must not be read as a fault.
 *
 * The port is part of the comparison and the scheme is not. The port because this
 * deployment has twice moved to a new address that differed only in its port — the same
 * host behind another door — and the scheme because it is the one part of a pair that a
 * phone's own `http` → `https` upgrade may legitimately change. `ReviewClient.normalize`
 * supplies the default port for a scheme, so `host` and `https://host` compare equal.
 */
internal fun qrPointsElsewhere(pageUrl: String?, serverUrl: String): String? {
    val page = pageHostOf(pageUrl)
    if (page.isEmpty()) return null
    val server = reviewHostOf(serverUrl)
    if (server.isEmpty()) return null
    return if (page == server) null else page
}

/**
 * The file name for one QR picture: the code, plus the first four bytes of the picture's
 * own SHA-256.
 *
 * The name carries the content for two reasons, and both of them are bugs that reading the
 * code would not have caught.
 *
 *  1. **`StateFlow` drops an equal value.** Publishing the refreshed picture as
 *     `ReviewView.Ready(identity, sameFile, …)` produces a value equal to the one on
 *     screen, so `MutableStateFlow` conflates it away and the viewer is never told. The
 *     picture has to *be* a different file for the redraw to happen at all.
 *  2. **Coil keys a `File` by its path.** `AsyncImage(model = file)` looks the decoded
 *     bitmap up in Coil's memory cache under that path, so overwriting `<code>.png` in
 *     place can leave the old picture on screen with the new bytes already on disk. A new
 *     file name is a new key, and the new picture is decoded.
 *
 * Eight hex characters is 32 bits of a picture that is a few kilobytes and comes from one
 * server: the cost of a collision is one stale drawing, and the cost of a longer name is
 * paid on every file in the cache directory.
 */
internal fun qrFileName(code: String, png: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(png)
    val hash = buildString {
        for (i in 0 until 4) {
            val byte = digest[i].toInt() and 0xFF
            append("0123456789abcdef"[byte shr 4])
            append("0123456789abcdef"[byte and 0x0F])
        }
    }
    return "$code-$hash.png"
}

/**
 * The newest picture on disk for a code, or null when there is none.
 *
 * A code can have more than one file for a moment — the fetch that replaces a picture
 * writes the new one before deleting the old ones — and the newest is the one to draw.
 * Nothing else in this app spells a QR file's name, so the prefix is the only contract:
 * `code` is six characters of `[A-Za-z0-9]`, and the dash is what keeps `abc123` from
 * matching `abc1234-…`.
 */
internal fun qrFileFor(dir: File, code: String): File? =
    dir.listFiles { file ->
        file.isFile && file.name.startsWith("$code-") && file.name.endsWith(".png")
    }?.maxByOrNull { it.lastModified() }
