package com.tvphoto.data.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * The upload protocol's decisions, without a socket anywhere.
 *
 * Everything here is what a phone can get wrong or aim at: a request line that is not one,
 * a token that is not this session's, a body longer than the television agreed to accept,
 * a filename that would climb out of the library directory. The server around these
 * functions is plumbing; these are the rules.
 */
class MusicUploadProtocolTest {

    // ------------------------------------------------------------- request lines

    @Test
    fun `a real upload line parses into method, path and decoded query`() {
        val line = MusicUploadProtocol.parseRequestLine(
            "POST /u/AB3X9Q7M/up?name=%E6%9C%88%E4%BA%AE.mp3 HTTP/1.1",
        )

        assertEquals("POST", line?.method)
        assertEquals("/u/AB3X9Q7M/up", line?.path)
        assertEquals("月亮.mp3", line?.query?.get("name"))
    }

    @Test
    fun `the page request has no query and the method is uppercased`() {
        val line = MusicUploadProtocol.parseRequestLine("get /u/AB3X9Q7M HTTP/1.1")

        assertEquals("GET", line?.method)
        assertEquals("/u/AB3X9Q7M", line?.path)
        assertEquals(emptyMap<String, String>(), line?.query)
    }

    @Test
    fun `anything that is not one of the three shapes is refused`() {
        assertNull(MusicUploadProtocol.parseRequestLine("POST /u/ABC"))
        assertNull(MusicUploadProtocol.parseRequestLine("POST /u/ABC HTTP/1.1 extra"))
        assertNull(MusicUploadProtocol.parseRequestLine("PUT /u/ABC HTTP/1.1"))
        assertNull(MusicUploadProtocol.parseRequestLine("POST u/ABC HTTP/1.1"))
        assertNull(MusicUploadProtocol.parseRequestLine(""))
        assertNull(MusicUploadProtocol.parseRequestLine("POST /u/ABC FTP/1.1"))
    }

    @Test
    fun `the token is read out of the path and nothing else is a token`() {
        assertEquals("AB3X9Q7M", MusicUploadProtocol.tokenOf("/u/AB3X9Q7M"))
        assertEquals("AB3X9Q7M", MusicUploadProtocol.tokenOf("/u/AB3X9Q7M/up"))
        assertNull(MusicUploadProtocol.tokenOf("/u/"))
        assertNull(MusicUploadProtocol.tokenOf("/other/AB3X9Q7M"))
    }

    @Test
    fun `only three routes exist under the token`() {
        assertEquals("page", MusicUploadProtocol.routeOf("/u/AB3X9Q7M"))
        assertEquals("up", MusicUploadProtocol.routeOf("/u/AB3X9Q7M/up"))
        assertEquals("state", MusicUploadProtocol.routeOf("/u/AB3X9Q7M/state"))
        assertNull(MusicUploadProtocol.routeOf("/u/AB3X9Q7M/files"))
        assertNull(MusicUploadProtocol.routeOf("/u/AB3X9Q7M/up/../.."))
        assertNull(MusicUploadProtocol.routeOf("/AB3X9Q7M/up"))
    }

    @Test
    fun `headers are found whatever case the client used`() {
        val headers = listOf("Host: 192.168.1.5:8123", "content-length: 12", "Connection: close")

        assertEquals("12", MusicUploadProtocol.header(headers, "Content-Length"))
        assertEquals("close", MusicUploadProtocol.header(headers, "connection"))
        assertNull(MusicUploadProtocol.header(headers, "Transfer-Encoding"))
    }

    // ------------------------------------------------------------------- bodies

    @Test
    fun `a declared body is read exactly`() {
        val body = MusicUploadProtocol.readBody(
            stream("hello world!"),
            listOf("Content-Length: 12"),
        )

        assertEquals("hello world!", String((body as MusicUploadProtocol.Body.Ok).bytes))
    }

    @Test
    fun `a declared body over the limit is refused before it is read`() {
        val body = MusicUploadProtocol.readBody(
            stream("0123456789"),
            listOf("Content-Length: 10"),
            limit = 4,
        )

        assertEquals(MusicUploadProtocol.Body.TooLarge, body)
    }

    @Test
    fun `a body that stops early is malformed, not silently short`() {
        val body = MusicUploadProtocol.readBody(
            stream("abc"),
            listOf("Content-Length: 10"),
        )

        assertEquals(MusicUploadProtocol.Body.Malformed, body)
    }

    @Test
    fun `a chunked body is decoded`() {
        val body = MusicUploadProtocol.readBody(
            stream("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"),
            listOf("Transfer-Encoding: chunked"),
        )

        assertEquals("hello world", String((body as MusicUploadProtocol.Body.Ok).bytes))
    }

    @Test
    fun `a chunked body over the limit is refused`() {
        val body = MusicUploadProtocol.readBody(
            stream("5\r\nhello\r\n0\r\n\r\n"),
            listOf("Transfer-Encoding: chunked"),
            limit = 4,
        )

        assertEquals(MusicUploadProtocol.Body.TooLarge, body)
    }

    @Test
    fun `a chunk that lies about its size is malformed`() {
        val body = MusicUploadProtocol.readBody(
            stream("20\r\nshort\r\n0\r\n\r\n"),
            listOf("Transfer-Encoding: chunked"),
        )

        assertEquals(MusicUploadProtocol.Body.Malformed, body)
    }

    @Test
    fun `a body with neither length nor chunking is read to the end`() {
        val body = MusicUploadProtocol.readBody(stream("till eof"), emptyList())

        assertEquals("till eof", String((body as MusicUploadProtocol.Body.Ok).bytes))
    }

    @Test
    fun `a body read to the end still stops at the limit`() {
        val body = MusicUploadProtocol.readBody(stream("0123456789"), emptyList(), limit = 4)

        assertEquals(MusicUploadProtocol.Body.TooLarge, body)
    }

    @Test
    fun `header lines end at the blank line`() {
        val lines = MusicUploadProtocol.readHeaderLines(
            stream("Host: tv\r\nContent-Length: 3\r\n\r\nBODY"),
        )

        assertEquals(listOf("Host: tv", "Content-Length: 3"), lines)
    }

    // -------------------------------------------------------------- file names

    @Test
    fun `an ordinary name is kept as it is`() {
        assertEquals("月亮照山川.mp3", MusicUploadProtocol.sanitizeFileName("月亮照山川.mp3", emptySet()))
        assertEquals("Easy Breeze.mp3", MusicUploadProtocol.sanitizeFileName("Easy Breeze.mp3", emptySet()))
    }

    @Test
    fun `a path is reduced to its last segment`() {
        assertEquals("passwd.mp3", MusicUploadProtocol.sanitizeFileName("../../etc/passwd.mp3", emptySet()))
        assertEquals("song.mp3", MusicUploadProtocol.sanitizeFileName("C:\\Users\\me\\song.mp3", emptySet()))
    }

    @Test
    fun `characters a television's storage cannot hold are replaced`() {
        assertEquals("a_b_c_.mp3", MusicUploadProtocol.sanitizeFileName("a:b*c?.mp3", emptySet()))
        assertEquals("no-ctrl.mp3", MusicUploadProtocol.sanitizeFileName("no\u0001-ctrl.mp3", emptySet()))
    }

    @Test
    fun `anything that is not audio is refused outright`() {
        assertNull(MusicUploadProtocol.sanitizeFileName("notes.txt", emptySet()))
        assertNull(MusicUploadProtocol.sanitizeFileName("archive.zip", emptySet()))
        assertNull(MusicUploadProtocol.sanitizeFileName("no-extension", emptySet()))
        assertNull(MusicUploadProtocol.sanitizeFileName("", emptySet()))
        assertNull(MusicUploadProtocol.sanitizeFileName("...", emptySet()))
        assertNull(MusicUploadProtocol.sanitizeFileName("///.mp3", emptySet()))
    }

    @Test
    fun `a name already in the library gets a number rather than replacing it`() {
        assertEquals("song-2.mp3", MusicUploadProtocol.sanitizeFileName("song.mp3", setOf("song.mp3")))
        assertEquals(
            "song-3.mp3",
            MusicUploadProtocol.sanitizeFileName("song.mp3", setOf("Song.mp3", "song-2.mp3")),
        )
    }

    @Test
    fun `a very long name is cut, and keeps its extension`() {
        val name = "x".repeat(500) + ".mp3"

        val cleaned = MusicUploadProtocol.sanitizeFileName(name, emptySet())

        assertTrue("was ${cleaned?.length}", cleaned != null && cleaned.length <= MusicUploadProtocol.MAX_FILE_NAME_CHARS)
        assertTrue(cleaned!!.endsWith(".mp3"))
    }

    @Test
    fun `the audio whitelist is the one media3 can play`() {
        assertTrue(MusicUploadProtocol.isAudioFileName("a.MP3"))
        assertTrue(MusicUploadProtocol.isAudioFileName("a.flac"))
        assertTrue(MusicUploadProtocol.isAudioFileName("a.opus"))
        assertEquals(false, MusicUploadProtocol.isAudioFileName("a.txt"))
        assertEquals(false, MusicUploadProtocol.isAudioFileName("a.mp3.exe"))
    }

    // ----------------------------------------------------------------- addresses

    @Test
    fun `a private address is preferred over anything else`() {
        assertEquals(
            "192.168.1.5",
            MusicUploadProtocol.preferredLanAddress(listOf("127.0.0.1", "8.8.8.8", "192.168.1.5")),
        )
        assertEquals(
            "10.0.0.4",
            MusicUploadProtocol.preferredLanAddress(listOf("8.8.8.8", "10.0.0.4", "172.16.3.9")),
        )
        assertEquals(
            "172.20.1.1",
            MusicUploadProtocol.preferredLanAddress(listOf("8.8.8.8", "172.20.1.1")),
        )
    }

    @Test
    fun `loopback and link-local are never put in a QR code`() {
        assertEquals(
            "192.168.0.2",
            MusicUploadProtocol.preferredLanAddress(listOf("127.0.0.1", "169.254.10.1", "192.168.0.2")),
        )
        assertNull(MusicUploadProtocol.preferredLanAddress(listOf("127.0.0.1", "169.254.10.1", "fe80::1")))
        assertNull(MusicUploadProtocol.preferredLanAddress(emptyList()))
    }

    private fun stream(body: String): InputStream = ByteArrayInputStream(body.toByteArray(Charsets.ISO_8859_1))
}
