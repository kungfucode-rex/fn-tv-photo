package com.tvphoto.data.review

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a television does when the server no longer holds what the television holds.
 *
 * There are two ways for that to happen, and they arrive by different doors:
 *
 *  - the server does not know this **device** any more, which is a refused token. It was
 *    reinstalled, restored from a backup taken before this device registered, or replaced
 *    at the same address. The repair registers a new device;
 *  - the server does not know this **photo**, which is `not_found` on the history read. It
 *    is the same reinstalled server seen from the other side, or one restored from a
 *    backup taken between the two registrations.
 *
 * Both make a cached identity worthless, and on 2026-10-11 both were live at once: a
 * `tupingd` redeployed onto an empty data directory refused the device (so the app minted
 * a new one) and had no row for any photo the app still held codes for — and the app went
 * on showing those codes, which scanned to the H5 page's 这张照片不在图评里. What is asserted
 * here is that a refusal reports the server replaced (so the caller drops its answers), that
 * nothing else does, and that `not_found` is read as the server not holding the photo.
 *
 * Against a real socket, for the reason `ReviewAuthTest` gives: the question is what this
 * app puts on the wire and how it reads what comes back, and neither survives being stubbed.
 */
class ReviewRecoveryTest {

    // ------------------------------------------------------- the repair's decision

    @Test
    fun `a refused token is repaired and the server is reported replaced`() = runBlocking {
        FakeServer { token, _ ->
            if (token == FRESH_TOKEN) Reply.ok() else Reply.refused()
        }.use { server ->
            val tv = Television(server.base)
            val replaced = AtomicInteger()
            val repair = repairFor(tv, replaced)

            val result = repair.run(
                current = { tv.client() },
                attempt = { client -> client.deviceAccepted() },
            )

            assertTrue("the call has to succeed after the repair", result.isSuccess)
            assertEquals(
                "the second attempt must carry the device the server just issued",
                listOf(STALE_TOKEN, FRESH_TOKEN),
                server.tokens(),
            )
            assertEquals("the replacement has to be reported exactly once", 1, replaced.get())
        }
    }

    /**
     * The state that must never cost this television its identity — or its answers.
     *
     * Nobody answered, so nothing is known about the server, and registering a device would
     * leave a ghost row behind for a packet that was merely dropped.
     */
    @Test
    fun `a dropped packet repairs nothing`() = runBlocking {
        val gone = FakeServer { _, _ -> Reply.ok() }
        val base = gone.base
        gone.close()

        val tv = Television(base)
        val replaced = AtomicInteger()
        val registered = AtomicInteger()
        val repair = DeviceRepair(
            fingerprint = { tv.fingerprint() },
            forgetCredentials = { registered.incrementAndGet() },
            registerDevice = { registered.incrementAndGet(); true },
            onServerReplaced = { replaced.incrementAndGet() },
            log = { },
        )

        val result = repair.run(
            current = { tv.client() },
            attempt = { client -> client.deviceAccepted() },
        )

        assertTrue(result.isFailure)
        assertEquals("no answer means no new device", 0, registered.get())
        assertEquals("and no reason to forget anything", 0, replaced.get())
    }

    /**
     * A refusal that cannot be repaired is still a refusal.
     *
     * Registration can be closed on a server (`TUPING_OPEN_REGISTRATION=false`), and then
     * there is no new device to be had. The old answers are all this television has, so they
     * stay — and the call is not run a second time against a token that is still refused.
     */
    @Test
    fun `a server that will not issue a device is not a replaced server`() = runBlocking {
        FakeServer { _, _ -> Reply.refused() }.use { server ->
            val tv = Television(server.base)
            val replaced = AtomicInteger()
            val repair = DeviceRepair(
                fingerprint = { tv.fingerprint() },
                forgetCredentials = { },
                registerDevice = { false },
                onServerReplaced = { replaced.incrementAndGet() },
                log = { },
            )

            val result = repair.run(
                current = { tv.client() },
                attempt = { client -> client.deviceAccepted() },
            )

            assertTrue(result.isFailure)
            assertEquals("nothing was repaired, so nothing was replaced", 0, replaced.get())
            assertEquals("and the call is not repeated", 1, server.tokens().size)
        }
    }

    /**
     * One burst of refused calls mints one device.
     *
     * This is not hypothetical: the first run against a real server produced two `device
     * issued` lines, and the television was left holding whichever token lost the race —
     * which the server then refused, for good. The lock and the fingerprint check are the
     * two halves of the fix, and both are exercised by two calls starting together.
     */
    @Test
    fun `two calls refused at once mint one device`() = runBlocking {
        FakeServer { token, _ ->
            if (token == FRESH_TOKEN) Reply.ok() else Reply.refused()
        }.use { server ->
            val tv = Television(server.base)
            val replaced = AtomicInteger()
            val devices = AtomicInteger()
            val repair = DeviceRepair(
                fingerprint = { tv.fingerprint() },
                forgetCredentials = { tv.token = "" },
                registerDevice = { devices.incrementAndGet(); tv.token = FRESH_TOKEN; true },
                onServerReplaced = { replaced.incrementAndGet() },
                log = { },
            )

            val results = listOf(
                async { repair.run({ tv.client() }) { it.deviceAccepted() } },
                async { repair.run({ tv.client() }) { it.deviceAccepted() } },
            ).awaitAll()

            assertTrue("both calls have to come back good", results.all { it.isSuccess })
            assertEquals("one new device, not two", 1, devices.get())
            assertEquals("and the replacement is reported once", 1, replaced.get())
        }
    }

    // --------------------------------------------------- the photo that is not there

    /**
     * `not_found` is the server saying it has no row for that picture.
     *
     * Read through the real client and a real 404, because this is a statement about the
     * protocol's error envelope and not about a string.
     */
    @Test
    fun `a history read for a photo the server does not hold is an unknown photo`() = runBlocking {
        FakeServer { _, _ ->
            Reply(404, """{"error":{"code":"not_found","message":"这张照片不在图评里"}}""")
        }.use { server ->
            val result = ReviewClient(server.base, DEVICE_ID, FRESH_TOKEN).comments("01NOPE")

            assertTrue(result.isFailure)
            assertTrue(isUnknownPhoto(result.exceptionOrNull()))
            assertEquals("/v1/comments?imageId=01NOPE&since=0&limit=50", server.targets().single())
        }
    }

    /**
     * Every other answer is about the *request*, not about the photo.
     *
     * A throttled or malformed call says nothing about whether the server holds the picture,
     * and forgetting a working code over one would be the expensive direction of the same
     * mistake — the photo would be registered again and mint a second code for a picture
     * that already had one.
     */
    @Test
    fun `no other answer is an unknown photo`() {
        assertFalse(isUnknownPhoto(ReviewException(ReviewError.Api("unauthorized", "设备令牌不对"))))
        assertFalse(isUnknownPhoto(ReviewException(ReviewError.Api("rate_limited", "发得太快了"))))
        assertFalse(isUnknownPhoto(ReviewException(ReviewError.Api("bad_request", "缺少 imageId"))))
        assertFalse(isUnknownPhoto(ReviewException(ReviewError.Unreachable("timed out"))))
        assertFalse(isUnknownPhoto(IOException("connection reset")))
        assertFalse(isUnknownPhoto(null))
    }

    // ------------------------------------------------------------------- helpers

    /**
     * A television's credentials and client, as `ReviewRepository` keeps them.
     *
     * The token is the mutable part: forgetting it and issuing a new one is the whole of
     * what the repair does to this side.
     */
    private class Television(private val base: String) {
        @Volatile
        var token: String = STALE_TOKEN

        fun client(): ReviewClient = ReviewClient(base, DEVICE_ID, token)

        fun fingerprint(): String = "$base\u0000$DEVICE_ID\u0000$token"
    }

    private fun repairFor(tv: Television, replaced: AtomicInteger) = DeviceRepair(
        fingerprint = { tv.fingerprint() },
        forgetCredentials = { tv.token = "" },
        registerDevice = { tv.token = FRESH_TOKEN; true },
        onServerReplaced = { replaced.incrementAndGet() },
        log = { },
    )

    private data class Reply(val status: Int, val body: String) {
        companion object {
            /** A device this server knows, asking about a photo it does not care about. */
            fun ok() = Reply(200, """{"serverTime":1,"items":[]}""")

            fun refused() = Reply(
                401,
                """{"error":{"code":"unauthorized","message":"设备令牌不对"}}""",
            )
        }
    }

    /**
     * A server that answers from a lambda, one connection per request.
     *
     * `Connection: close` on every reply keeps it that way, which is what lets the loop stay
     * a loop: no keep-alive state, no pipelining, one request in and one reply out.
     */
    private class FakeServer(private val answer: (token: String, target: String) -> Reply) :
        AutoCloseable {

        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val open = AtomicBoolean(true)
        private val seen = CopyOnWriteArrayList<Pair<String, String>>()

        val base: String get() = "http://127.0.0.1:${socket.localPort}"

        init {
            Thread({ accept() }, "fake-server").apply { isDaemon = true; start() }
        }

        /** The device token of every request, in order. */
        fun tokens(): List<String> = seen.map { it.first }

        /** The request target of every request, in order. */
        fun targets(): List<String> = seen.map { it.second }

        private fun accept() {
            while (open.get()) {
                val connection = runCatching { socket.accept() }.getOrNull() ?: return
                Thread({ serve(connection) }, "fake-connection").apply { isDaemon = true; start() }
            }
        }

        private fun serve(client: Socket) {
            runCatching {
                client.use { connection ->
                    val reader = connection.getInputStream().bufferedReader()
                    val target = reader.readLine().orEmpty().split(' ').getOrNull(1).orEmpty()

                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val line = reader.readLine().orEmpty()
                        if (line.isEmpty()) break
                        val colon = line.indexOf(':')
                        if (colon > 0) {
                            headers[line.take(colon).trim().lowercase()] = line.substring(colon + 1).trim()
                        }
                    }

                    val token = headers["x-device-token"].orEmpty()
                    seen.add(token to target)

                    val reply = answer(token, target)
                    val payload = reply.body.toByteArray()
                    connection.getOutputStream().apply {
                        write(
                            (
                                "HTTP/1.1 ${reply.status} X\r\n" +
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
                // `close()` unblocks accept() with a SocketException, and a connection that
                // goes away mid-request is not this test's business.
                if (error !is IOException) throw error
            }
        }

        override fun close() {
            open.set(false)
            runCatching { socket.close() }
        }
    }

    private companion object {
        const val DEVICE_ID = "01M4NCXV5Q89WK4FAGJEDHFW1R"

        /** The token this television held while the server it came from was still up. */
        const val STALE_TOKEN = "5xyKxmeRJsy5FmkG91ecD0UV4fdBRLMh"

        /** The one the replaced server issues. */
        const val FRESH_TOKEN = "uVvK0w8ccR9ffAwJD51RBtIPKhrzbG2v"
    }
}
