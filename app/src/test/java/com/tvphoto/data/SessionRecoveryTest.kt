package com.tvphoto.data

import com.tvphoto.data.fn.FnApiException
import com.tvphoto.data.fn.FnNetworkException
import com.tvphoto.data.fn.FnSession
import com.tvphoto.data.fn.FnSessionExpiredException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The rules behind the 401 a television shows after hours of being left alone.
 *
 * The bug these pin is a button that did nothing: the row said the token had expired, 重试
 * went out with the same stale token, and the only way back to the library was 退出登录 and a
 * fresh login. A retry now performs that login itself, and every case below is one of the
 * ways that can go — including the two that must *not* turn into a login, because a NAS that
 * is merely asleep is not a session problem.
 */
class SessionRecoveryTest {

    private val stale = session(token = "stale")
    private val fresh = session(token = "fresh")

    @Test
    fun `a failure that is not the token is retried with the session it had`() = runBlocking {
        var refreshes = 0
        var sent: FnSession? = null

        val answer = SessionRecovery.retry(
            error = FnNetworkException("connect timed out"),
            session = stale,
            canRefresh = true,
            refresh = { refreshes++; fresh },
            perform = { sent = it; "ok" },
        )

        assertEquals("ok", answer)
        assertEquals("a timeout must not cost a login", 0, refreshes)
        assertSame(stale, sent)
    }

    @Test
    fun `an expired token is replayed with the session the login produced`() = runBlocking {
        var refreshes = 0
        var sent: FnSession? = null

        val answer = SessionRecovery.retry(
            error = FnSessionExpiredException(),
            session = stale,
            canRefresh = true,
            refresh = { refreshes++; fresh },
            perform = { sent = it; "ok" },
        )

        assertEquals("ok", answer)
        assertEquals("the login has to run exactly once", 1, refreshes)
        assertSame(fresh, sent)
    }

    @Test
    fun `no credentials to log in with leaves the expiry as the answer`() = runBlocking {
        var refreshes = 0
        var attempts = 0

        try {
            SessionRecovery.retry<Unit>(
                error = FnSessionExpiredException(),
                session = stale,
                canRefresh = false,
                refresh = { refreshes++; fresh },
                perform = { attempts++ },
            )
            fail("an expiry with nothing to log in with must not be swallowed")
        } catch (expected: FnSessionExpiredException) {
            assertEquals("nothing to replay it with", 0, refreshes)
            assertEquals("the stale token must not be sent again", 0, attempts)
        }
    }

    @Test
    fun `a refresh that produced no session does not replay the stale one`() = runBlocking {
        var attempts = 0

        try {
            SessionRecovery.retry<Unit>(
                error = FnSessionExpiredException(),
                session = stale,
                canRefresh = true,
                // What the repository answers when the handshake did not produce a new
                // session — the case that must be a reported expiry, not a second request
                // carrying the token the server has just refused.
                refresh = { null },
                perform = { attempts++ },
            )
            fail("a refresh that produced nothing must not be sent again")
        } catch (expected: FnSessionExpiredException) {
            assertEquals(0, attempts)
        }
    }

    @Test
    fun `a session that is a different object but the same token is still a refresh`() = runBlocking {
        var sent: FnSession? = null

        // The trap this rule exists for, and it was found on a mock NAS in a minute: its
        // token is `mock-token-<user>`-shaped, so a second login hands back the *identical*
        // string. An earlier version compared the two tokens, read that as "nothing was
        // refreshed", and rethrew the 401 — so the retry button still did nothing. What the
        // repository reports is whether a handshake was accepted, not what it returned.
        val sameToken = session(token = "stale")

        val answer = SessionRecovery.retry(
            error = FnSessionExpiredException(),
            session = stale,
            canRefresh = true,
            refresh = { sameToken },
            perform = { sent = it; "ok" },
        )

        assertEquals("ok", answer)
        assertSame(sameToken, sent)
    }

    @Test
    fun `a second expiry is reported rather than retried forever`() = runBlocking {
        var refreshes = 0

        try {
            SessionRecovery.retry<Unit>(
                error = FnSessionExpiredException(),
                session = stale,
                canRefresh = true,
                refresh = { refreshes++; fresh },
                perform = { throw FnSessionExpiredException() },
            )
            fail("a token the server refuses twice must surface")
        } catch (expected: FnSessionExpiredException) {
            assertEquals("one login, one replay, no loop", 1, refreshes)
        }
    }

    @Test
    fun `a failure during the replay is reported and not retried again`() = runBlocking {
        var refreshes = 0

        try {
            SessionRecovery.retry<Unit>(
                error = FnSessionExpiredException(),
                session = stale,
                canRefresh = true,
                refresh = { refreshes++; fresh },
                // A 500 on the way back is the server's answer, not another expiry.
                perform = { throw FnApiException(500, "internal error") },
            )
            fail("the second failure has to reach the caller")
        } catch (expected: FnApiException) {
            assertEquals(500, expected.code)
            assertEquals(1, refreshes)
        }
    }

    @Test
    fun `a login that itself fails is reported as that failure`() = runBlocking {
        try {
            SessionRecovery.retry<Unit>(
                error = FnSessionExpiredException(),
                session = stale,
                canRefresh = true,
                // The wrong access code, a password changed on the NAS, a device that is
                // off: the only honest answer is the login's own reason, so the login form
                // has something to show for itself.
                refresh = { throw FnNetworkException("cannot connect to the NAS") },
                perform = { },
            )
            fail("a failed login must not be swallowed")
        } catch (expected: FnNetworkException) {
            assertTrue(expected.message!!.contains("cannot connect"))
        }
    }

    @Test
    fun `only the expired-token failure counts as an expiry`() {
        assertTrue(SessionRecovery.isExpiry(FnSessionExpiredException()))
        assertEquals(false, SessionRecovery.isExpiry(FnNetworkException("offline")))
        assertEquals(false, SessionRecovery.isExpiry(FnApiException(500, "internal error")))
    }

    private fun session(token: String) = FnSession(
        baseUrl = "https://192.168.31.14:5667",
        token = token,
        secret = "secret",
        backId = "0000000000000000",
        uid = 1L,
        isAdmin = false,
        userName = "user",
    )
}
