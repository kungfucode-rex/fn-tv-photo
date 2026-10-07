package com.tvphoto.data.fn

import java.util.concurrent.atomic.AtomicInteger

/**
 * Request ids are 28 hex characters: `tttttttt` + `bbbbbbbbbbbbbbbb` + `eeee`.
 *
 * The middle 16 characters start as zeros and are replaced by the `backId` the
 * server returns from login.
 */
internal object FnReqId {

    private val counter = AtomicInteger(1)

    @Volatile
    private var backId: String = ZERO_BACK_ID

    private const val ZERO_BACK_ID = "0000000000000000"

    fun setBackId(value: String?) {
        if (value != null && value.length == 16) backId = value
    }

    /**
     * Clears the session id.
     *
     * `backId` belongs to one login, so it must not leak into the next handshake.
     * Without this a sign-out followed by a sign-in sends the previous session's id
     * in the `util.crypto.getRSAPub` request, which a real server may reject — and
     * because a fresh login starts before any new `backId` exists, the only correct
     * value at that point is the zero placeholder.
     */
    fun reset() {
        backId = ZERO_BACK_ID
    }

    /** The id currently embedded in generated request ids; used by tests. */
    val currentBackId: String get() = backId

    fun next(): String {
        val seconds = "%08x".format(System.currentTimeMillis() / 1000)
        val seq = "%04x".format(counter.getAndIncrement() and 0xFFFF)
        return seconds + backId + seq
    }
}
