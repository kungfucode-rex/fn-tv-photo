package com.tvphoto.data.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address that an older build could save and still draw as if it were fine.
 *
 * Not hypothetical: the television this was found on held `106.52.237.179:53347` with a
 * **NUL** on the end. The settings row drew it exactly like a working address, the server
 * was up, the television could reach it (`toybox nc` from inside the emulator connected),
 * and 图评 was silently off — the log said the address was "blank, or not a URL" about an
 * address that is neither. The NAS address row has folded full-width punctuation since
 * 1.3.2; this row folded nothing and trimmed nothing that mattered, because `String.trim()`
 * does not remove a NUL.
 *
 * So these pin two things: the bytes that must never be able to disable the feature again,
 * and the cases that must still be *refused*, because a sanitiser that makes anything parse
 * would only move the failure to the wire.
 */
class ReviewAddressTest {

    // ------------------------------------------------- what must not disable 图评

    @Test
    fun `a trailing NUL is dropped instead of disabling the feature`() {
        assertEquals(
            "http://106.52.237.179:53347/",
            ReviewClient.normalize("106.52.237.179:53347\u0000").toString(),
        )
    }

    @Test
    fun `whitespace and a newline from a paste are trimmed`() {
        assertEquals(
            "http://192.168.1.4:8080/",
            ReviewClient.normalize("  192.168.1.4:8080\n").toString(),
        )
    }

    @Test
    fun `zero-width characters and a byte-order mark are dropped`() {
        assertEquals(
            "http://192.168.1.4:8080/",
            ReviewClient.normalize("\uFEFF192.168.1.4\u200B:8080").toString(),
        )
    }

    /**
     * The same lesson as `BaseUrlTest`'s full-width case, which this row never learned: a
     * Chinese IME produces `：` and `．` for an address typed by hand, and the URL stack
     * folding them back is incidental rather than something to rely on.
     */
    @Test
    fun `full-width punctuation and digits are folded like the NAS address row`() {
        assertEquals(
            "http://192.168.1.4:8080/",
            ReviewClient.normalize("１９２．１６８．１．４：８０８０").toString(),
        )
    }

    @Test
    fun `a bare host and port becomes http, and a typed https is kept`() {
        assertEquals("http://review.local:8080/", ReviewClient.normalize("review.local:8080").toString())
        assertEquals(
            "https://review.example.com:8443/",
            ReviewClient.normalize("https://review.example.com:8443").toString(),
        )
    }

    @Test
    fun `a trailing slash is not part of the address`() {
        assertEquals(
            "http://192.168.1.4:8080/",
            ReviewClient.normalize("192.168.1.4:8080///").toString(),
        )
    }

    // ------------------------------------------------- what must still be refused

    @Test
    fun `nothing at all is nothing at all`() {
        assertNull(ReviewClient.normalize(""))
        assertNull(ReviewClient.normalize("   "))
        assertNull(ReviewClient.normalize("\u0000"))
        assertNull(ReviewClient.normalize("\u200B\n"))
    }

    /**
     * Cleaning is not forgiving. A port that cannot exist is still refused, so the honest
     * answer stays on the settings row instead of becoming a connection error later.
     */
    @Test
    fun `a port that cannot exist is still refused`() {
        assertNull(ReviewClient.normalize("192.168.1.4:99999"))
    }

    // ------------------------------------------------- the store's own cleaning

    @Test
    fun `cleaning is idempotent, so a read cannot keep changing the value`() {
        val once = cleanServerAddress("  106.52.237.179:53347\u0000\u200B ")
        assertEquals("106.52.237.179:53347", once)
        assertEquals(once, cleanServerAddress(once))
    }

    @Test
    fun `the address itself is what is left, punctuation and all`() {
        assertEquals(
            "https://review.example.com:8443/base",
            cleanServerAddress("https://review.example.com:8443/base"),
        )
    }
}
