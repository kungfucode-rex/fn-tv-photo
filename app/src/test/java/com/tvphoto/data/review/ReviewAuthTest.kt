package com.tvphoto.data.review

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference

/**
 * The one decision the device repair hangs on, and the call that feeds it.
 *
 * Two states look identical from the outside and must never be confused:
 *
 *  - the server *refusing* this device's token, which is the state the app used to be stuck
 *    in for good — `/healthz` answers, the settings row says 已连接, every upload is 401,
 *    and the viewer's 图评 button greys out with nothing to explain it. The cure is to
 *    register again;
 *  - the answer *never arriving*, where registering again would mint a spare device on the
 *    server for nothing.
 *
 * The protocol side of this — that `requireDevice` runs before the parameters are read, and
 * that a wrong token comes back as `{"error":{"code":"unauthorized",…}}` — is asserted
 * against the real server in `server/internal/api/api_test.go` and by the independent
 * reference client in `tools/mock-review/verify.js`. What is asserted here is that the TV
 * end reads it the same way, against a socket that actually answers.
 */
class ReviewAuthTest {

    // -------------------------------------------------------------- decisions

    @Test
    fun `a refused device token is a refusal`() {
        val refused = ReviewException(ReviewError.Api("unauthorized", "设备令牌不对"))
        assertTrue(isRefusedToken(refused))
    }

    /**
     * A refusal is the server's credential answer and nothing else. Every other answer is a
     * statement about the *request* — too fast, too long, unreadable bytes — and minting a
     * new device for one of those would lose a working identity over a mistake that repeating
     * the call cannot fix anyway.
     */
    @Test
    fun `no other protocol answer is a refusal`() {
        assertFalse(isRefusedToken(ReviewException(ReviewError.Api("rate_limited", "请求太频繁了"))))
        assertFalse(isRefusedToken(ReviewException(ReviewError.Api("bad_request", "缺少 thumb"))))
        assertFalse(isRefusedToken(ReviewException(ReviewError.Api("content_rejected", "内容不合适"))))
        assertFalse(isRefusedToken(ReviewException(ReviewError.BadThumbnail("not a jpeg"))))
    }

    /** The state that must never cost this television its identity: nobody answered. */
    @Test
    fun `a dropped packet is not a refusal`() {
        assertFalse(isRefusedToken(ReviewException(ReviewError.Unreachable("timed out"))))
        assertFalse(isRefusedToken(IOException("connection reset")))
        assertFalse(isRefusedToken(null))
    }

    /**
     * The unwrapping that made every refusal unreadable.
     *
     * `ReviewError` is a value, not a `Throwable`, so a caller that reached for `cause`
     * always got null and reported the wrapper's `toString` — `Api(code=unauthorized,
     * message=设备令牌不对)` — where the server had written 设备令牌不对.
     */
    @Test
    fun `the protocol error is read out of the wrapper, and only out of the wrapper`() {
        val api = ReviewError.Api("unauthorized", "设备令牌不对")
        assertEquals(api, reviewErrorOf(ReviewException(api)))
        assertNull(reviewErrorOf(IOException("timed out")))
        assertNull(reviewErrorOf(null))
    }

    // ------------------------------------------------------------- the probe

    @Test
    fun `the probe asks about this device, and a 401 is the refusal`() = runBlocking {
        probe(
            status = 401,
            body = """{"error":{"code":"unauthorized","message":"设备令牌不对"}}""",
        ).use { server ->
            val built = ReviewClient(server.base, DEVICE_ID, STALE_TOKEN)

            val result = built.deviceAccepted()

            assertTrue("a refused token has to come back as a failure", result.isFailure)
            assertEquals(
                ReviewError.Api("unauthorized", "设备令牌不对"),
                reviewErrorOf(result.exceptionOrNull()),
            )
            assertTrue(isRefusedToken(result.exceptionOrNull()))

            // Two things the request must be. It must carry the device headers, or the
            // server would refuse it for the wrong reason and the app would go off to
            // register a device it does not need. And it must carry **no image id**: the
            // token is checked before the parameters are, so the 400 that a good token earns
            // is the "accepted" half of the answer, while asking about a photo would make
            // this a question about photos — and one the real server answers 500 for a photo
            // it does not hold.
            assertEquals("/v1/comments", server.target.get())
            assertEquals(DEVICE_ID, server.header("X-Device-Id"))
            assertEquals(STALE_TOKEN, server.header("X-Device-Token"))
        }
    }

    /**
     * The other half of the same answer: a good token does not get a 200 here, it gets the
     * complaint about the missing image id — which is proof that it got past the check.
     */
    @Test
    fun `the 400 a good token earns is an accepted device`() = runBlocking {
        probe(
            status = 400,
            body = """{"error":{"code":"bad_request","message":"缺少 imageId"}}""",
        ).use { server ->
            val result = ReviewClient(server.base, DEVICE_ID, "good").deviceAccepted()

            assertTrue("a token that got past the check is accepted", result.isSuccess)
        }
    }

    @Test
    fun `an accepted device is a success even though the photo does not exist`() = runBlocking {
        probe(status = 200, body = """{"serverTime":1,"items":[]}""").use { server ->
            val built = ReviewClient(server.base, DEVICE_ID, "good")

            val result = built.deviceAccepted()

            assertTrue(result.isSuccess)
            assertFalse(isRefusedToken(result.exceptionOrNull()))
        }
    }

    /** The other half of the pair: a server that is not there is not a refusal either. */
    @Test
    fun `a probe that nobody answers is a failure that is not a refusal`() = runBlocking {
        val gone = probe(status = 200, body = "{}")
        val base = gone.base
        gone.close()

        val result = ReviewClient(base, DEVICE_ID, STALE_TOKEN).deviceAccepted()

        assertTrue(result.isFailure)
        assertFalse(isRefusedToken(result.exceptionOrNull()))
    }

    // ----------------------------------------------------------------- setup

    /**
     * A real socket answering one canned response, remembering what it was asked.
     *
     * A hand-written one rather than a server from the standard library or a new test
     * dependency: `com.sun.net.httpserver` is not on the classpath an Android unit test
     * compiles against, and the question here is what this app puts on the wire and how it
     * reads what comes back — neither of which survives being stubbed.
     */
    private class Probe(private val status: Int, private val body: String) : AutoCloseable {
        /** The request target, e.g. `/v1/comments?imageId=…&limit=1`. */
        val target = AtomicReference("")
        private val received = AtomicReference<Map<String, String>>(emptyMap())
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

        val base: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread({ serve() }, "probe").apply { isDaemon = true; start() }
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
                                "HTTP/1.1 $status ${if (status == 200) "OK" else "Unauthorized"}\r\n" +
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
        /** The device id the emulator's install carried when this was reproduced. */
        const val DEVICE_ID = "01M4G56ATSJGDPV49VZQPWZN0X"

        /** A token from a server that has since been reinstalled. */
        const val STALE_TOKEN = "dc7eh_DnaX5yNF1K2NHq8Uuqq5u3X3e1"
    }
}
