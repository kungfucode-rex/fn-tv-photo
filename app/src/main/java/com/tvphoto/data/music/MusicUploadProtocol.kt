package com.tvphoto.data.music

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLDecoder
import java.util.Locale

/**
 * Everything the phone-upload server decides, with no socket and no Android in it.
 *
 * The server class is then only plumbing — accept, read, route, write — and every rule
 * that could be got wrong (how long a name may be, what counts as audio, where a path
 * might escape to, what happens to a body that is too big) is a plain function a JVM test
 * can pin down. This is the same split `docs/DEVELOPMENT.md` describes for the session
 * logic: if it is a decision, it belongs where a test can reach it.
 */
object MusicUploadProtocol {

    /** One file may not exceed this. Measured against the four bundled tracks: 3.1–4.4 MB. */
    const val MAX_UPLOAD_BYTES: Long = 40L * 1024 * 1024

    /** The whole library may not exceed this; the management screen shows the same number. */
    const val LIBRARY_LIMIT_BYTES: Long = 1024L * 1024 * 1024

    /** The first port tried. Fixed so the same address works on a second visit. */
    const val DEFAULT_PORT = 8123

    /** How many ports to try before giving up and letting the system pick one. */
    const val PORT_ATTEMPTS = 12

    /**
     * What may be uploaded, by extension.
     *
     * A whitelist rather than "anything the player can decode": the server writes what it
     * receives into the library directory with the name it was given, so the extension is
     * also the promise that this file is music. Media3 plays all seven.
     */
    val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus")

    /** Longest filename accepted, before the `-2` a name collision adds. */
    const val MAX_FILE_NAME_CHARS = 80

    /** Longest request line or header line read. Anything longer is not a real request. */
    private const val MAX_LINE_CHARS = 2048

    /**
     * A parsed request line.
     *
     * [path] and [query] are split here rather than at every use: the routes are written
     * against a path without parameters, and the one parameter this protocol has — the
     * file's name — is decoded once, at the edge.
     */
    data class RequestLine(
        val method: String,
        val path: String,
        val query: Map<String, String>,
    )

    /** What the body reader decided. */
    sealed interface Body {
        data class Ok(val bytes: ByteArray) : Body

        /** Over the limit. The caller answers 413 without draining the rest. */
        data object TooLarge : Body

        /** Truncated, or a chunked body that does not add up. */
        data object Malformed : Body
    }

    /**
     * Parses `POST /u/ABCDEF/up?name=x.mp3 HTTP/1.1`.
     *
     * Returns null for anything that is not one, which the server answers with 400 and a
     * closed connection: a request line this app cannot read is not a request it should
     * try to serve.
     */
    fun parseRequestLine(line: String): RequestLine? {
        val parts = line.trim().split(' ')
        if (parts.size != 3) return null
        val method = parts[0].uppercase(Locale.US)
        if (method != "GET" && method != "POST") return null
        if (!parts[2].startsWith("HTTP/")) return null
        val target = parts[1]
        if (!target.startsWith("/")) return null
        val path = target.substringBefore('?')
        return RequestLine(method, path, parseQuery(target.substringAfter('?', "")))
    }

    /** The token in `/u/<token>/…`, or null when the path is not an upload path. */
    fun tokenOf(path: String): String? = path
        .removePrefix(UPLOAD_ROOT)
        .substringBefore('/')
        .takeIf { path.startsWith(UPLOAD_ROOT) && it.isNotBlank() }

    /**
     * Which route a path names under the token.
     *
     * Exactly three, and nothing else exists: the page, the upload, and the one-line
     * status. There is deliberately no directory listing and no read endpoint — the server
     * runs on a television in somebody's living room, and the only thing it is for is
     * receiving music.
     */
    fun routeOf(path: String): String? = when {
        !path.startsWith(UPLOAD_ROOT) -> null
        else -> {
            val rest = path.removePrefix(UPLOAD_ROOT).substringAfter('/', "")
            when (rest) {
                "" -> "page"
                "up" -> "up"
                "state" -> "state"
                else -> null
            }
        }
    }

    /** Case-insensitive header lookup over the raw lines the server read. */
    fun header(lines: List<String>, name: String): String? {
        val wanted = "$name:".lowercase(Locale.US)
        return lines.firstOrNull { it.lowercase(Locale.US).startsWith(wanted) }
            ?.substringAfter(':')
            ?.trim()
    }

    /** Whether the name is one this server will accept, by extension. */
    fun isAudioFileName(name: String): Boolean =
        extensionOf(name) in AUDIO_EXTENSIONS

    /**
     * The name a file will be stored under, or null when it may not be stored at all.
     *
     * Everything a filename can carry that would reach past the library directory is
     * removed rather than escaped: separators, `..`, control characters, and the
     * characters Windows and FAT refuse. The result is then de-duplicated against
     * [taken] — the names already in the library — because two phones uploading `1.mp3`
     * must not become one file.
     */
    fun sanitizeFileName(raw: String, taken: Set<String>): String? {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (ch in base) {
                when {
                    ch.code < 0x20 || ch.code == 0x7F -> Unit
                    ch in FORBIDDEN_CHARS -> append('_')
                    else -> append(ch)
                }
            }
        }
            .trim()
            .trim('.')
            .trim()

        if (!isAudioFileName(cleaned)) return null

        val extension = extensionOf(cleaned)
        val stem = cleaned.substringBeforeLast('.').trim().trimEnd('.')
        if (stem.isBlank()) return null

        val room = (MAX_FILE_NAME_CHARS - extension.length - 1).coerceAtLeast(1)
        val shortStem = stem.take(room)
        val takenLower = taken.mapTo(HashSet()) { it.lowercase(Locale.US) }
        val first = "$shortStem.$extension"
        if (first.lowercase(Locale.US) !in takenLower) return first

        var index = 2
        while (true) {
            val candidate = "$shortStem-$index.$extension"
            if (candidate.lowercase(Locale.US) !in takenLower) return candidate
            index++
        }
    }

    /**
     * Reads the declared body, stopping at [limit].
     *
     * Three shapes, all of which real clients send: `Content-Length` (what the page's
     * request sends, because the body is a `File` whose size the browser knows), chunked
     * encoding (what some Android browsers fall back to), and neither — an HTTP/1.0 body
     * read to the end of the stream. The cap is applied before any of the bytes are kept,
     * so a lying `Content-Length` cannot fill the television's storage.
     */
    fun readBody(input: InputStream, headers: List<String>, limit: Long = MAX_UPLOAD_BYTES): Body {
        val chunked = header(headers, "Transfer-Encoding")?.lowercase(Locale.US)?.contains("chunked") == true
        if (chunked) return readChunked(input, limit)

        val declared = header(headers, "Content-Length")?.toLongOrNull()
        if (declared != null) {
            if (declared < 0) return Body.Malformed
            if (declared > limit) return Body.TooLarge
            val out = ByteArrayOutputStream(declared.toInt())
            return if (copyExactly(input, out, declared)) Body.Ok(out.toByteArray()) else Body.Malformed
        }

        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return Body.Ok(out.toByteArray())
            if (out.size() + read > limit) return Body.TooLarge
            out.write(buffer, 0, read)
        }
    }

    /** Reads one CRLF- or LF-terminated ASCII line, or null at end of stream / over [max]. */
    fun readAsciiLine(input: InputStream, max: Int = MAX_LINE_CHARS): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (b == '\n'.code) return out.toString("ISO-8859-1").trimEnd('\r')
            if (out.size() >= max) return null
            out.write(b)
        }
    }

    /** The headers up to the blank line; null when the request never ends its head. */
    fun readHeaderLines(input: InputStream, maxLines: Int = 64): List<String>? {
        val lines = ArrayList<String>()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isEmpty()) return lines
            if (lines.size >= maxLines) return null
            lines += line
        }
    }

    /**
     * Which address to put in the QR code.
     *
     * The default route's own IPv4, preferred in the order a home network uses: the
     * private ranges first, then anything else that could still be reached from a phone on
     * the same wire. Loopback and link-local are dropped — a code pointing at 127.0.0.1 is
     * a code that works only on the television itself, which is the one place nobody is
     * holding the phone.
     */
    fun preferredLanAddress(candidates: List<String>): String? {
        val usable = candidates.filter { candidate ->
            candidate.isNotBlank() &&
                !candidate.startsWith("127.") &&
                !candidate.startsWith("169.254.") &&
                candidate.contains('.') &&
                !candidate.contains(':')
        }
        return usable.firstOrNull { it.startsWith("192.168.") }
            ?: usable.firstOrNull { it.startsWith("10.") }
            ?: usable.firstOrNull { address ->
                val second = address.split('.').getOrNull(1)?.toIntOrNull()
                address.startsWith("172.") && second != null && second in 16..31
            }
            ?: usable.firstOrNull()
    }

    /** The content type to serve a file under, by extension. */
    fun contentTypeFor(name: String): String = when (extensionOf(name)) {
        "mp3" -> "audio/mpeg"
        "m4a", "aac" -> "audio/mp4"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "ogg", "opus" -> "audio/ogg"
        else -> "application/octet-stream"
    }

    /** `/u/<token>` — the page, and the base every other route hangs off. */
    fun uploadRoot(token: String): String = "$UPLOAD_ROOT$token"

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in raw.split('&')) {
            if (pair.isBlank()) continue
            val name = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            out[decode(name)] = decode(value)
        }
        return out
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.US)

    private fun readChunked(input: InputStream, limit: Long): Body {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readAsciiLine(input, 64) ?: return Body.Malformed
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16) ?: return Body.Malformed
            if (size == 0L) {
                // The trailer, ended by a blank line; its contents are not used.
                while (true) {
                    val trailer = readAsciiLine(input, 64) ?: return Body.Malformed
                    if (trailer.isEmpty()) return Body.Ok(out.toByteArray())
                }
            }
            if (out.size() + size > limit) return Body.TooLarge
            if (!copyExactly(input, out, size)) return Body.Malformed
            if (readAsciiLine(input, 8) == null) return Body.Malformed
        }
    }

    /** Copies exactly [count] bytes, or reports a truncated stream. */
    private fun copyExactly(input: InputStream, out: ByteArrayOutputStream, count: Long): Boolean {
        val buffer = ByteArray(16 * 1024)
        var remaining = count
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return false
            out.write(buffer, 0, read)
            remaining -= read
        }
        return true
    }

    private const val UPLOAD_ROOT = "/u/"

    /** Characters that cannot survive a copy onto the television's own storage. */
    private val FORBIDDEN_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')
}
