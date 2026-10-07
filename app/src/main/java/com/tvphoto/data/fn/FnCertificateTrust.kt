package com.tvphoto.data.fn

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Where a server's certificate fingerprint is remembered, keyed by host. */
interface CertificatePinStore {
    fun pinnedFingerprint(host: String): String?
    fun rememberFingerprint(host: String, fingerprint: String)
    fun forgetFingerprint(host: String)
}

/** What to do with a certificate that has just been presented. */
enum class PinVerdict {
    /** Nothing remembered for this host yet. */
    FIRST_USE,

    /** Same certificate as last time. */
    MATCH,

    /** A different certificate from the one pinned. */
    CHANGED,
}

/**
 * TLS trust for a NAS that can only ever present a self-signed certificate.
 *
 * Measured on a real device: the certificate is self-signed (`CN=fnOS`) and its only
 * `subjectAltName` is `DNS:fnOS` — no IP, no other name. That is **two** independent
 * reasons it can never validate: no chain to a trusted root, and no name matching a
 * host or address a user could actually type. A stock HTTPS client therefore cannot
 * connect at all, which is why the app used to speak plain HTTP.
 *
 * So the certificate is trusted **on first use**: the first fingerprint seen for a host
 * is remembered, and a *different* one afterwards is refused once. On refusal the pin is
 * dropped and [consumeChangedHost] reports it, so the login can say what happened — and a
 * second, deliberate attempt accepts the new certificate. These certificates are issued
 * for about three months, so a renewal is a normal event, not an attack; refusing
 * outright would lock the user out of their own NAS with no way back on a TV remote.
 */
class FnCertificateTrust(private val pins: CertificatePinStore) {

    @Volatile
    private var changedHost: String? = null

    /** The host whose certificate no longer matches its pin, reported once. */
    fun consumeChangedHost(): String? = changedHost.also { changedHost = null }

    /**
     * Accepts any chain, because there is no chain to check.
     *
     * Continuity is enforced in [hostnameVerifier] instead: unlike this callback, it is
     * told which host is being connected to, so pins can be kept per server.
     */
    val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val hostnameVerifier = HostnameVerifier { host, session ->
        accept(host, session.peerCertificates.firstOrNull()?.let { fingerprintOf(it.encoded) })
    }

    /**
     * The pinch decision and its side effects, separated from the TLS session so it can
     * be tested without a socket.
     */
    internal fun accept(host: String, presented: String?): Boolean {
        // Nothing was presented, so there is nothing to pin.
        if (presented.isNullOrBlank()) return false

        return when (judgePin(pins.pinnedFingerprint(host), presented)) {
            PinVerdict.FIRST_USE -> {
                pins.rememberFingerprint(host, presented)
                true
            }

            PinVerdict.MATCH -> true

            PinVerdict.CHANGED -> {
                // Forget it so the user's next attempt — after being told — can trust the
                // new certificate. A silently-accepted change would make the pin useless.
                pins.forgetFingerprint(host)
                changedHost = host
                false
            }
        }
    }

    fun sslContext(): SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(trustManager), SecureRandom())
    }
}

/**
 * The pinning rule on its own, so it can be tested without a socket.
 *
 * A certificate that has not been seen before is [pinned] as `null`.
 */
fun judgePin(pinned: String?, presented: String): PinVerdict = when {
    pinned == null -> PinVerdict.FIRST_USE
    pinned.equals(presented, ignoreCase = true) -> PinVerdict.MATCH
    else -> PinVerdict.CHANGED
}

/** SHA-256 of a DER certificate, written the usual colon-separated way. */
fun fingerprintOf(encodedCertificate: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(encodedCertificate)
        .joinToString(":") { String.format(Locale.US, "%02X", it.toInt() and 0xFF) }
