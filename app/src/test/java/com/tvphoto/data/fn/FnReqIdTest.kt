package com.tvphoto.data.fn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FnReqIdTest {

    private val zeroBackId = "0000000000000000"

    /** Request ids are `tttttttt` + 16-char backId + `eeee`. */
    private fun backIdOf(reqId: String): String {
        assertEquals(28, reqId.length)
        return reqId.substring(8, 24)
    }

    @Test
    fun `a fresh request id uses the zero placeholder`() {
        FnReqId.reset()
        assertEquals(zeroBackId, backIdOf(FnReqId.next()))
    }

    @Test
    fun `the returned back id is embedded in later request ids`() {
        FnReqId.reset()
        FnReqId.setBackId("00000000000000ab")
        assertEquals("00000000000000ab", backIdOf(FnReqId.next()))
    }

    /**
     * The regression this guards: `backId` belongs to one login, but the object is a
     * process-wide singleton that outlives a sign-out. Without [FnReqId.reset] the
     * second handshake advertises the previous session's id, which a real server may
     * reject — and a mock that ignores `reqid` will never notice.
     */
    @Test
    fun `starting a new handshake forgets the previous session id`() {
        FnReqId.setBackId("00000000000000ab")
        assertNotEquals(zeroBackId, backIdOf(FnReqId.next()))

        FnReqId.reset()

        assertEquals(zeroBackId, FnReqId.currentBackId)
        assertEquals(zeroBackId, backIdOf(FnReqId.next()))
    }

    @Test
    fun `only a well formed back id is accepted`() {
        FnReqId.reset()
        FnReqId.setBackId("too-short")
        assertEquals(zeroBackId, FnReqId.currentBackId)

        FnReqId.setBackId(null)
        assertEquals(zeroBackId, FnReqId.currentBackId)

        FnReqId.setBackId("0123456789abcdef")
        assertEquals("0123456789abcdef", FnReqId.currentBackId)
    }

    @Test
    fun `the sequence counter keeps request ids distinct`() {
        FnReqId.reset()
        val seen = (1..50).map { backIdOf(FnReqId.next()) }.toSet()
        assertEquals(1, seen.size)
        assertTrue(seen.contains(zeroBackId))
    }
}
