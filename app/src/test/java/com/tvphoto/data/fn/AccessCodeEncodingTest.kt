package com.tvphoto.data.fn

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The access code travels as `x-access-code: base64(code)`, produced by the fnOS
 * challenge page with `btoa(String.fromCharCode(...new TextEncoder().encode(code)))`.
 * These vectors pin our encoder to that behaviour, including padding.
 */
class AccessCodeEncodingTest {

    private fun encode(code: String) = base64Encode(code.toByteArray(Charsets.UTF_8))

    @Test
    fun `matches btoa for ascii`() {
        assertEquals("MTIzNDU2", encode("123456"))
        assertEquals("YWJj", encode("abc"))
        assertEquals("YQ==", encode("a"))
        assertEquals("YWI=", encode("ab"))
    }

    @Test
    fun `empty input produces empty output`() {
        assertEquals("", encode(""))
    }

    @Test
    fun `uses the standard alphabet, not the url safe one`() {
        // 0xFB 0xFF encodes to "+/" in the standard alphabet.
        assertEquals("+/8=", base64Encode(byteArrayOf(0xFB.toByte(), 0xFF.toByte())))
    }

    /** A code containing Chinese must be encoded as UTF-8, not UTF-16. */
    @Test
    fun `multi byte characters are encoded as utf8`() {
        val bytes = "访问码".toByteArray(Charsets.UTF_8)
        assertEquals(9, bytes.size)
        assertEquals("6K6/6Zeu56CB", encode("访问码"))
    }

    @Test
    fun `padding appears only for partial trailing groups`() {
        assertEquals(0, encode("abc").count { it == '=' })    // 3 bytes: whole group
        assertEquals(2, encode("abca").count { it == '=' })   // 4 bytes: 3 + 1 remainder
        assertEquals(1, encode("abcaa").count { it == '=' })  // 5 bytes: 3 + 2 remainder
        assertEquals(0, encode("abcaaa").count { it == '=' }) // 6 bytes: two whole groups
    }
}
