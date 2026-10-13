package com.tvphoto.data.review

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * The call that lets a television show a photo's code and its comments without uploading it.
 *
 * Two things are asserted here that the server's own tests cannot see, because they are
 * decisions about *this* end of the wire:
 *
 *  - **a 404 is a success with the answer "nobody has opened 图评 on this photo".** Most of a
 *    library is in that state, and reading it as a failure would light up the settings row's
 *    error state — "图评连不上" — on a deployment that is working perfectly;
 *  - **the question is asked about the right photo.** `source` and `localId` are the only
 *    handle this end has, and a lookup that asked with the wrong one would answer "not
 *    found" for a photo that is on the server, which looks exactly like a photo that is not.
 */
class ReviewLookupTest {

    @Test
    fun `a photo the server knows comes back as an identity`() = runBlocking {
        probe(
            status = 200,
            body = """
                {"imageId":"006GK9MHVJZW2VWDX9W889A2TE","code":"ok5th7","scope":"image",
                 "commentCount":3,"thumbUrl":"http://host/t/ok5th7?w=320",
                 "qrUrl":"http://host/v1/qr/ok5th7.png?size=440","width":4000,"height":2250,
                 "visibility":1,"serverTime":1}
            """.trimIndent(),
        ).use { server ->
            val result = ReviewClient(server.base, DEVICE_ID, TOKEN).lookupPhoto("fnos-album", "1002")

            assertTrue("a known photo has to come back as a success", result.isSuccess)
            val identity = result.getOrNull()
            assertEquals("006GK9MHVJZW2VWDX9W889A2TE", identity?.imageId)
            assertEquals("ok5th7", identity?.code)
            assertEquals(3, identity?.commentCount)
            // The local id travels back with the answer, so the caller can key its cache by it.
            assertEquals("1002", identity?.localId)

            // The question itself: the endpoint, and the two halves of the key, with the
            // source escaped as a query parameter rather than pasted into a path.
            assertEquals("/v1/photos/by-local?source=fnos-album&localId=1002", server.target.get())
            assertEquals(DEVICE_ID, server.header("X-Device-Id"))
            assertEquals(TOKEN, server.header("X-Device-Token"))
        }
    }

    /** The ordinary state of most of a library, and it must not read as a fault. */
    @Test
    fun `a photo nobody opened is a null answer, not a failure`() = runBlocking {
        probe(
            status = 404,
            body = """{"error":{"code":"not_found","message":"这台设备还没给这张照片开过图评"}}""",
        ).use { server ->
            val result = ReviewClient(server.base, DEVICE_ID, TOKEN).lookupPhoto("fnos", "1002")

            assertTrue("a 404 is the answer 'not on the server', not an error", result.isSuccess)
            assertNull(result.getOrNull())
        }
    }

    /** A real refusal still has to arrive as one, with the server's own sentence. */
    @Test
    fun `a refusal is still a failure`() = runBlocking {
        probe(
            status = 401,
            body = """{"error":{"code":"unauthorized","message":"设备令牌不对"}}""",
        ).use { server ->
            val result = ReviewClient(server.base, DEVICE_ID, "stale").lookupPhoto("fnos", "1002")

            assertTrue(result.isFailure)
            assertEquals(
                ReviewError.Api("unauthorized", "设备令牌不对"),
                reviewErrorOf(result.exceptionOrNull()),
            )
            assertTrue(isRefusedToken(result.exceptionOrNull()))
        }
    }

    // ----------------------------------------------------------------- setup

    /**
     * A real socket answering one canned response, remembering what it was asked.
     *
     * The same shape as `ReviewAuthTest`'s probe, and for the same reason: what is under test
     * is what this end puts on the wire and how it reads what comes back, and neither
     * survives being stubbed.
     */
    private class Probe(private val status: Int, private val body: String) : AutoCloseable {
        /** The request target, query string included. */
        val target = AtomicReference("")
        private val received = AtomicReference<Map<String, String>>(emptyMap())
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

        val base: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread({ serve() }, "lookup-probe").apply { isDaemon = true; start() }
        }

        /** Case-insensitively, because HTTP header names have no case in the protocol. */
        fun header(name: String): String? = received.get()[name.lowercase()]

        private fun serve() {
            runCatching {
                socket.accept().use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    target.set(requestLine.split(' ').getOrNull(1).orEmpty())

                    val lines = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine().orEmpty()
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            lines[line.take(colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }
                    received.set(lines)

                    val payload = body.toByteArray()
                    connection.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                                    "Content-Type: application/json\r\n" +
                                    "Content-Length: ${payload.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(),
                        )
                        write(payload)
                        flush()
                    }
                }
            }.onFailure { error ->
                // `close()` unblocks accept() with a SocketException; no client ever arriving
                // is a passing test's business, not this thread's.
                if (error !is IOException) throw error
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private fun probe(status: Int, body: String) = Probe(status, body)

    private companion object {
        const val DEVICE_ID = "01M4G56ATSJGDPV49VZQPWZN0X"
        const val TOKEN = "dc7eh_DnaX5yNF1K2NHq8Uuqq5u3X3e1"
    }
}
