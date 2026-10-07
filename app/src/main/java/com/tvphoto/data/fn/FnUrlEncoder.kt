package com.tvphoto.data.fn

/**
 * Encodes query strings the way JavaScript's `URLSearchParams` does, because that
 * is the form the fnOS frontend puts on the wire.
 *
 * The difference from OkHttp's own encoder matters: `URLSearchParams` renders a
 * space as `+` and leaves `*` alone, while OkHttp emits `%20` and escapes `*`.
 * Since the signature is derived from the parameter values, reproducing the exact
 * wire form keeps the two consistent.
 */
internal object FnUrlEncoder {

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*-._"

    fun encode(value: String): String {
        val out = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xFF
            when {
                unsigned == 0x20 -> out.append('+')
                unsigned.toChar() in UNRESERVED -> out.append(unsigned.toChar())
                else -> out.append('%').append("%02X".format(unsigned))
            }
        }
        return out.toString()
    }
}
