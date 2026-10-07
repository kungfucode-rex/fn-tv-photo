package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The app speaks **only** HTTPS — the NAS's certificate is self-signed, so a cleartext
 * fallback would be the one path with no protection at all. These pin that, including
 * what happens to a scheme the user types out of habit.
 */
class BaseUrlTest {

    @Test
    fun `a bare address becomes https on the secure port`() {
        assertEquals("https://192.168.1.10:5667", normalizeBaseUrl("192.168.1.10"))
    }

    @Test
    fun `an explicit port is preserved`() {
        assertEquals("https://192.168.31.14:50317", normalizeBaseUrl("192.168.31.14:50317"))
        assertEquals("https://nas.local:8000", normalizeBaseUrl("nas.local:8000"))
    }

    @Test
    fun `a typed scheme is folded away, because there is no cleartext path`() {
        assertEquals("https://nas.example.com:5667", normalizeBaseUrl("https://nas.example.com"))
        assertEquals("https://nas.example.com:50316", normalizeBaseUrl("http://nas.example.com:50316"))
        assertEquals("https://10.0.2.2:5667", normalizeBaseUrl("http://10.0.2.2/"))
        assertEquals("https://10.0.2.2:9000", normalizeBaseUrl("http://10.0.2.2:9000/some/path"))
    }

    /**
     * The regression this guards: with a Chinese IME, typing `10.0.2.2` can produce
     * ideographic full stops, which previously only worked because the URL stack's
     * IDN nameprep happened to fold them back.
     */
    @Test
    fun `full-width address typed by a Chinese IME is folded to ASCII`() {
        assertEquals("https://10.0.2.2:5667", normalizeBaseUrl("10\u30020\u30022\u30022"))
        assertEquals("https://10.0.2.2:5667", normalizeBaseUrl("10\uFF0E0\uFF0E2\uFF0E2"))
        assertEquals(
            "https://192.168.1.10:5667",
            normalizeBaseUrl("\uFF11\uFF19\uFF12\u3002\uFF11\uFF16\uFF18\u3002\uFF11\u3002\uFF11\uFF10"),
        )
    }

    @Test
    fun `full-width colon separates the port`() {
        assertEquals("https://10.0.2.2:50317", normalizeBaseUrl("10.0.2.2\uFF1A50317"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        assertEquals("https://10.0.2.2:5667", normalizeBaseUrl("  10.0.2.2  "))
    }

    @Test
    fun `blank input yields an empty base url`() {
        assertEquals("", normalizeBaseUrl(""))
        assertEquals("", normalizeBaseUrl("   "))
        assertEquals("", normalizeBaseUrl("http://"))
        assertEquals("", normalizeBaseUrl("https://"))
        assertEquals("", normalizeBaseUrl("https:///p/api"))
    }

    @Test
    fun `ipv6 literal keeps its brackets`() {
        assertEquals("https://[fd00::1]:5667", normalizeBaseUrl("[fd00::1]"))
        assertEquals("https://[fd00::1]:8080", normalizeBaseUrl("[fd00::1]:8080"))
    }

    @Test
    fun `displayHost drops the scheme`() {
        assertEquals("10.0.2.2:5667", displayHost("https://10.0.2.2:5667"))
        assertEquals("nas.example.com:50317", displayHost("https://nas.example.com:50317"))
    }
}
