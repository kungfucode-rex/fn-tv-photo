package com.tvphoto.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Does the code on screen open the server this television is configured for?
 *
 * The fact this pins is the one that was missing for a year: a QR picture is a picture of
 * an address, the address is frozen on the short code when it is minted, and the app had
 * no way to tell a code that opens its own server from one that opens a deployment it left
 * behind (docs/tuping-design.md §17.31). `server/internal/api/photos.go` puts the answer on
 * the response as `X-Page-Url`; this is what the app does with it.
 *
 * The cases are the ones this deployment has actually produced: the same host behind a
 * different port (53347 → 53348 was one of the moves), the same server reached on the LAN
 * while the codes hold a public address (which is *not* a fault and must not be reported as
 * one), and a server that sends no header at all.
 */
class ReviewQrTargetTest {

    private val configured = "192.168.31.14:8080"

    @Test
    fun `the same host and port is not reported`() {
        assertNull(qrPointsElsewhere("http://192.168.31.14:8080/c/WxQJsA", configured))
        // A typed address without a scheme defaults to http, which is port 80 for both
        // sides, so nothing is reported here either.
        assertNull(qrPointsElsewhere("http://photos.example.com/c/WxQJsA", "photos.example.com"))
    }

    @Test
    fun `a scheme alone does not make it somewhere else`() {
        // 443 against 443: the address is the same door, so this is not the app's business.
        assertNull(qrPointsElsewhere("https://photos.example.com/c/WxQJsA", "https://photos.example.com"))
        // And the default port of the scheme is what a missing port means.
        assertNull(qrPointsElsewhere("https://photos.example.com/c/WxQJsA", "photos.example.com:443"))
    }

    @Test
    fun `a different port is somewhere else`() {
        // The move that started this: same host, another door.
        assertEquals(
            "photos.example.com:53347",
            qrPointsElsewhere("http://photos.example.com:53347/c/WxQJsA", "http://photos.example.com:53348"),
        )
    }

    @Test
    fun `a different host is somewhere else`() {
        assertEquals(
            "a.example.com:8080",
            qrPointsElsewhere("http://a.example.com:8080/c/WxQJsA", "http://b.example.com:8080"),
        )
    }

    /**
     * The LAN-with-a-pinned-public-address deployment. The row says what the code opens,
     * and that is the whole point of saying it — this is not a fault to be hidden.
     */
    @Test
    fun `the television on the LAN while the codes hold the public address is still reported`() {
        assertEquals(
            "106.52.237.179:53347",
            qrPointsElsewhere("http://106.52.237.179:53347/c/WxQJsA", "192.168.31.14:8080"),
        )
    }

    @Test
    fun `case in the host is not a difference`() {
        assertNull(qrPointsElsewhere("http://Photos.Example.COM/c/WxQJsA", "http://photos.example.com"))
    }

    /**
     * Nothing to compare, so nothing to report. An older server, or a mock, that sends no
     * `X-Page-Url` must not put a sentence on screen about a code nobody has looked at.
     */
    @Test
    fun `a missing or unusable address is not a difference`() {
        assertNull(qrPointsElsewhere(null, configured))
        assertNull(qrPointsElsewhere("", configured))
        assertNull(qrPointsElsewhere("   ", configured))
        // A bare path is not an address, and OkHttp would happily read `http:///c/x` as
        // host `c` — which became the sentence "this code opens c:80" the first time this
        // was run. Only an absolute page URL is compared.
        assertNull(qrPointsElsewhere("/c/WxQJsA", configured))
        assertNull(qrPointsElsewhere("WxQJsA", configured))
        assertNull(qrPointsElsewhere("192.168.31.14:8080/c/WxQJsA", configured))
        // And a television with no address of its own has nothing to compare against.
        assertNull(qrPointsElsewhere("http://a.example.com/c/WxQJsA", ""))
    }

    /**
     * The picture's file name carries its own bytes.
     *
     * Two bugs ride on this and neither is visible by reading the drawing code: `StateFlow`
     * conflates a republished `Ready` whose `qrFile` is the same value, and Coil keys a
     * `File` by its path, so a picture rewritten in place can keep the old bitmap on
     * screen. The name has to change when the bytes do.
     */
    @Test
    fun `the file name follows the picture's own bytes`() {
        val one = qrFileName("WxQJsA", byteArrayOf(1, 2, 3))
        val two = qrFileName("WxQJsA", byteArrayOf(1, 2, 4))
        val same = qrFileName("WxQJsA", byteArrayOf(1, 2, 3))

        assertEquals(one, same)
        org.junit.Assert.assertNotEquals("same bytes must give the same name", two, one)
        org.junit.Assert.assertTrue("the name is the code plus a hash: $one", one.startsWith("WxQJsA-"))
        org.junit.Assert.assertTrue("and it is still a PNG name: $one", one.endsWith(".png"))
        assertEquals("WxQJsA-".length + 8 + ".png".length, one.length)
    }

    /**
     * The sentence a person actually reads, on `设置 → 图评设置 → 图评连接状态`.
     *
     * That row draws `reviewStatus.label()` and nothing else, so this is the whole of what
     * the feature shows. It is pinned here rather than on the television because walking
     * the D-pad to that row is where the reading was lost: the rail took one press too many
     * and landed on 退出登录, which clears the session and with it the in-memory note
     * (docs/verification.md §33 records that honestly).
     */
    @Test
    fun `the status row says where the code points`() {
        assertEquals(
            "已连接 10.0.2.2:18080 · 二维码指向 10.0.2.2:9999",
            ReviewStatus.Online("10.0.2.2:18080", 0L, "10.0.2.2:9999").label(),
        )
        // And the ordinary case stays exactly the sentence it always was.
        assertEquals("已连接 10.0.2.2:18080", ReviewStatus.Online("10.0.2.2:18080", 0L).label())
    }

    /**
     * The newest picture for a code wins, and another code's pictures are not it.
     *
     * A code keeps more than one file for the moment between writing the new picture and
     * deleting the old ones, which is why the lookup is by prefix and mtime rather than by
     * an exact name.
     */
    @Test
    fun `the newest picture for a code is the one that is drawn`() {
        val dir = Files.createTempDirectory("qr-target-test").toFile()
        try {
            val old = File(dir, qrFileName("WxQJsA", byteArrayOf(1))).apply { writeBytes(byteArrayOf(1)) }
            val other = File(dir, qrFileName("Zz9Zz9", byteArrayOf(1))).apply { writeBytes(byteArrayOf(1)) }
            val fresh = File(dir, qrFileName("WxQJsA", byteArrayOf(2))).apply { writeBytes(byteArrayOf(2)) }
            old.setLastModified(1_000_000L)
            fresh.setLastModified(2_000_000L)
            // A prefix that is not this code: `WxQJsA` must not match `WxQJsAB-…`.
            File(dir, "WxQJsAB-00000000.png").writeBytes(byteArrayOf(9))

            assertEquals(fresh, qrFileFor(dir, "WxQJsA"))
            assertEquals(other, qrFileFor(dir, "Zz9Zz9"))
            assertNull(qrFileFor(dir, "nope12"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
