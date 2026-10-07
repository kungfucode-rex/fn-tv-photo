package com.tvphoto.data.fn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The NAS can only present a self-signed certificate, so the client trusts it on first
 * use and refuses a change once. These pin that rule, including the part that matters
 * most for a TV: a refused certificate must be recoverable, not a dead end.
 */
class CertificateTrustTest {

    private class FakePins(initial: Map<String, String> = emptyMap()) : CertificatePinStore {
        val pins = initial.toMutableMap()
        var remembered = 0
        var forgotten = 0

        override fun pinnedFingerprint(host: String): String? = pins[host]

        override fun rememberFingerprint(host: String, fingerprint: String) {
            remembered++
            pins[host] = fingerprint
        }

        override fun forgetFingerprint(host: String) {
            forgotten++
            pins.remove(host)
        }
    }

    private val certA = "B1:E2:DB:32:82:85:9A:AB"
    private val certB = "AA:BB:CC:DD:EE:FF:00:11"

    @Test
    fun `first contact is accepted and remembered`() {
        val pins = FakePins()
        val trust = FnCertificateTrust(pins)

        assertTrue(trust.accept("192.168.31.14", certA))
        assertEquals(certA, pins.pinnedFingerprint("192.168.31.14"))
        assertEquals(1, pins.remembered)
        assertNull(trust.consumeChangedHost())
    }

    @Test
    fun `the same certificate is accepted without being re-pinned`() {
        val pins = FakePins(mapOf("192.168.31.14" to certA))
        val trust = FnCertificateTrust(pins)

        assertTrue(trust.accept("192.168.31.14", certA))
        assertEquals(0, pins.remembered)
        assertNull(trust.consumeChangedHost())
    }

    @Test
    fun `a changed certificate is refused once and reported`() {
        val pins = FakePins(mapOf("192.168.31.14" to certA))
        val trust = FnCertificateTrust(pins)

        assertFalse(trust.accept("192.168.31.14", certB))

        // The pin is dropped so the user can accept the new certificate deliberately.
        assertEquals(1, pins.forgotten)
        assertNull(pins.pinnedFingerprint("192.168.31.14"))
        assertEquals("192.168.31.14", trust.consumeChangedHost())
        // Reported exactly once: the login reads it a single time when building its error.
        assertNull(trust.consumeChangedHost())
    }

    @Test
    fun `the attempt after a refusal is trusted again`() {
        // This is the recovery path: fnOS certificates last about three months, so a
        // renewal must not lock anyone out of their own NAS with no way back on a remote.
        val pins = FakePins(mapOf("nas.local" to certA))
        val trust = FnCertificateTrust(pins)

        assertFalse(trust.accept("nas.local", certB))
        assertTrue(trust.accept("nas.local", certB))
        assertEquals(certB, pins.pinnedFingerprint("nas.local"))
    }

    @Test
    fun `pins are per host, so one NAS changing does not disturb another`() {
        val pins = FakePins(mapOf("nas.local" to certA))
        val trust = FnCertificateTrust(pins)

        assertTrue(trust.accept("192.168.31.14", certB))
        assertEquals(certA, pins.pinnedFingerprint("nas.local"))
    }

    @Test
    fun `a certificate that was never presented is refused`() {
        val pins = FakePins()
        val trust = FnCertificateTrust(pins)

        assertFalse(trust.accept("nas.local", null))
        assertFalse(trust.accept("nas.local", ""))
        assertEquals(0, pins.remembered)
    }

    @Test
    fun `fingerprints are compared case-insensitively`() {
        assertEquals(PinVerdict.MATCH, judgePin(certA.lowercase(), certA.uppercase()))
        assertEquals(PinVerdict.FIRST_USE, judgePin(null, certA))
        assertEquals(PinVerdict.CHANGED, judgePin(certA, certB))
    }

    @Test
    fun `a fingerprint is colon-separated upper-case sha256`() {
        // SHA-256 of the empty string, the canonical vector.
        assertEquals(
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:" +
                "A4:95:99:1B:78:52:B8:55",
            fingerprintOf(ByteArray(0)),
        )
    }
}
