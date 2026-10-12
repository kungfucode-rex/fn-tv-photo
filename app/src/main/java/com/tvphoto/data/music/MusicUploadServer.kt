package com.tvphoto.data.music

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * The phone's way in: a small HTTP server the television runs while the QR code is on
 * screen.
 *
 * It exists so that "add music" does not require a second machine. The alternative
 * designs all push the work onto the user — a file manager on a television remote, or a
 * Docker container they have to deploy before they can pick a song — and this one asks
 * for nothing but a phone that is already on the same Wi-Fi.
 *
 * The lifetime is deliberately the screen's: [start] when the panel appears, [stop] when
 * it is closed. A listener that outlived the panel would be a port open on somebody's
 * television for no reason, and the token in the URL — the only credential there is —
 * would keep working.
 *
 * Concurrency is one connection at a time and one request per connection. A phone uploads
 * several files by sending several requests, so nothing is lost, and a single-threaded
 * server has no interleaving to reason about when it writes into a directory.
 */
class MusicUploadServer(private val context: Context, private val library: MusicLibrary) {

    /** One file the phone sent, as the panel reports it. */
    data class Line(val name: String, val sizeBytes: Long, val failure: UploadFailure?)

    /** Why one file did not make it. The panel renders each as a sentence. */
    enum class UploadFailure { TOO_LARGE, NOT_AUDIO, LIBRARY_FULL, NO_SPACE, FAILED }

    /**
     * What the panel needs to draw, and nothing else.
     *
     * [url] is null until the socket is listening: the QR code is drawn from it, and a code
     * for an address that is not accepting connections yet is a code that fails.
     */
    data class State(
        val running: Boolean = false,
        val url: String? = null,
        val token: String? = null,
        val received: Int = 0,
        val bytes: Long = 0L,
        val limitBytes: Long = MusicUploadProtocol.LIBRARY_LIMIT_BYTES,
        val lines: List<Line> = emptyList(),
        /** Set when the server could not start at all, for the panel to show. */
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var running = false

    private var socket: ServerSocket? = null

    @Volatile
    private var connection: Socket? = null

    private var worker: Thread? = null

    private var page: String? = null

    /** Starts listening, or records why it could not. Idempotent. */
    fun start() {
        if (running) return
        running = true
        worker = thread(name = "music-upload", isDaemon = true) { serve() }
    }

    /** Stops listening and drops whatever is in flight. Safe to call twice. */
    fun stop() {
        running = false
        runCatching { connection?.close() }
        runCatching { socket?.close() }
        socket = null
        connection = null
        _state.value = State()
    }

    private fun serve() {
        val token = newToken()
        val server = openSocket()
        if (server == null) {
            _state.value = State(error = "no port")
            running = false
            return
        }
        socket = server
        val address = detectLanAddress()
        val url = address?.let { "http://$it:${server.localPort}${MusicUploadProtocol.uploadRoot(token)}" }
        _state.value = State(
            running = true,
            url = url,
            token = token,
            limitBytes = MusicUploadProtocol.LIBRARY_LIMIT_BYTES,
            // No address is not a crash: the panel says so and the user can type the
            // address by hand from a phone that is on the same network.
            error = if (url == null) "no address" else null,
        )

        while (running) {
            val client = try {
                server.accept()
            } catch (error: IOException) {
                break
            }
            connection = client
            runCatching { handle(client, token) }
            runCatching { client.close() }
            connection = null
        }
        runCatching { server.close() }
        socket = null
        if (running) running = false
    }

    /**
     * Tries the agreed port first and then a few neighbours.
     *
     * A fixed port is what makes the address the same on a second visit, which matters
     * because the address is also written on screen for anyone who would rather type it.
     * When every one of them is taken the system picks, and the URL that is shown and
     * encoded is built from the socket's real port either way.
     */
    private fun openSocket(): ServerSocket? {
        for (offset in 0 until MusicUploadProtocol.PORT_ATTEMPTS) {
            val candidate = MusicUploadProtocol.DEFAULT_PORT + offset
            try {
                return ServerSocket(candidate).apply { reuseAddress = true }
            } catch (_: IOException) {
                continue
            }
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun handle(client: Socket, token: String) {
        client.soTimeout = READ_TIMEOUT_MILLIS
        val input = client.getInputStream()
        val output = BufferedOutputStream(client.getOutputStream())

        val requestLine = MusicUploadProtocol.readAsciiLine(input)?.let(MusicUploadProtocol::parseRequestLine)
        val headers = MusicUploadProtocol.readHeaderLines(input)
        if (requestLine == null || headers == null) {
            respond(output, 400, "text/plain; charset=utf-8", "bad request")
            return
        }

        if (MusicUploadProtocol.tokenOf(requestLine.path) != token) {
            // Also the answer to every scanner and every browser that guessed the port.
            respond(output, 404, "text/plain; charset=utf-8", "not found")
            return
        }

        when (MusicUploadProtocol.routeOf(requestLine.path)) {
            "page" -> {
                if (requestLine.method != "GET") return respond(output, 405, TEXT, "method not allowed")
                respond(output, 200, "text/html; charset=utf-8", loadPage())
            }

            "state" -> {
                val state = _state.value
                respond(
                    output,
                    200,
                    "application/json; charset=utf-8",
                    """{"ok":true,"received":${state.received},"bytes":${state.bytes},"limit":${state.limitBytes}}""",
                )
            }

            "up" -> {
                if (requestLine.method != "POST") return respond(output, 405, TEXT, "method not allowed")
                upload(output, input, headers, requestLine.query["name"].orEmpty())
            }

            else -> respond(output, 404, TEXT, "not found")
        }
    }

    private fun upload(
        output: OutputStream,
        input: InputStream,
        headers: List<String>,
        rawName: String,
    ) {
        val name = rawName.ifBlank { "upload.mp3" }
        when (val body = MusicUploadProtocol.readBody(input, headers)) {
            is MusicUploadProtocol.Body.TooLarge -> {
                record(Line(name, 0L, UploadFailure.TOO_LARGE))
                respond(output, 413, JSON, """{"ok":false,"code":"too_large"}""")
            }

            is MusicUploadProtocol.Body.Malformed -> {
                record(Line(name, 0L, UploadFailure.FAILED))
                respond(output, 400, JSON, """{"ok":false,"code":"malformed"}""")
            }

            is MusicUploadProtocol.Body.Ok -> when (val added = library.addUpload(body.bytes, name)) {
                is MusicAddResult.Added -> {
                    record(Line(added.entry.title, body.bytes.size.toLong(), null))
                    respond(output, 200, JSON, """{"ok":true}""")
                }

                MusicAddResult.TooLarge -> {
                    record(Line(name, body.bytes.size.toLong(), UploadFailure.TOO_LARGE))
                    respond(output, 413, JSON, """{"ok":false,"code":"too_large"}""")
                }

                MusicAddResult.NotAudio -> {
                    record(Line(name, body.bytes.size.toLong(), UploadFailure.NOT_AUDIO))
                    respond(output, 415, JSON, """{"ok":false,"code":"not_audio"}""")
                }

                MusicAddResult.LibraryFull -> {
                    record(Line(name, body.bytes.size.toLong(), UploadFailure.LIBRARY_FULL))
                    respond(output, 507, JSON, """{"ok":false,"code":"library_full"}""")
                }

                MusicAddResult.NoSpace -> {
                    record(Line(name, body.bytes.size.toLong(), UploadFailure.NO_SPACE))
                    respond(output, 507, JSON, """{"ok":false,"code":"no_space"}""")
                }

                is MusicAddResult.Failed -> {
                    record(Line(name, body.bytes.size.toLong(), UploadFailure.FAILED))
                    respond(output, 500, JSON, """{"ok":false,"code":"failed"}""")
                }
            }
        }
    }

    /** Adds one line to the panel's log, keeping the list bounded. */
    private fun record(line: Line) {
        val current = _state.value
        val ok = line.failure == null
        _state.value = current.copy(
            received = current.received + if (ok) 1 else 0,
            bytes = current.bytes + if (ok) line.sizeBytes else 0L,
            lines = (current.lines + line).takeLast(MAX_REPORTED_LINES),
        )
    }

    private fun loadPage(): String {
        page?.let { return it }
        val text = runCatching {
            context.assets.open(PAGE_ASSET).bufferedReader().use { it.readText() }
        }.getOrDefault(FALLBACK_PAGE)
        page = text
        return text
    }

    private fun respond(output: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        respond(output, status, contentType, bytes)
    }

    private fun respond(output: OutputStream, status: Int, contentType: String, body: ByteArray) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append(CRLF)
            append("Content-Type: ").append(contentType).append(CRLF)
            append("Content-Length: ").append(body.size).append(CRLF)
            append("Cache-Control: no-store").append(CRLF)
            append("Connection: close").append(CRLF)
            append(CRLF)
        }
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
        output.flush()
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        413 -> "Payload Too Large"
        415 -> "Unsupported Media Type"
        507 -> "Insufficient Storage"
        else -> "Internal Server Error"
    }

    /**
     * This television's address on the local network.
     *
     * Read from the active network's own link properties rather than by picking an
     * interface: a television can be on Wi-Fi and Ethernet at once, and only one of them
     * has a default route — the other's address is one a phone on the same Wi-Fi cannot
     * reach.
     */
    private fun detectLanAddress(): String? {
        val candidates = ArrayList<String>()
        runCatching {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val network = manager?.activeNetwork
            val properties = network?.let { manager.getLinkProperties(it) }
            properties?.linkAddresses?.forEach { link ->
                link.address.takeIf { it is Inet4Address }?.hostAddress?.let { candidates += it }
            }
        }
        if (candidates.isEmpty()) {
            runCatching {
                NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
                    nif.inetAddresses?.toList()?.forEach { address ->
                        address.takeIf { it is Inet4Address }?.hostAddress?.let { candidates += it }
                    }
                }
            }
        }
        return MusicUploadProtocol.preferredLanAddress(candidates)
    }

    /** A short, unambiguous token: no I, L, O or U to mistype if it is ever read aloud. */
    private fun newToken(): String {
        val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        val random = SecureRandom()
        return buildString { repeat(TOKEN_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private companion object {
        const val PAGE_ASSET = "music-upload.html"
        const val JSON = "application/json; charset=utf-8"
        const val TEXT = "text/plain; charset=utf-8"
        const val CRLF = "\r\n"
        const val TOKEN_LENGTH = 8
        const val MAX_REPORTED_LINES = 20
        const val READ_TIMEOUT_MILLIS = 120_000

        /** Shown only if the packaged page is missing, which a build check prevents. */
        const val FALLBACK_PAGE =
            "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>" +
                "<p>upload page missing from this build"
    }
}
