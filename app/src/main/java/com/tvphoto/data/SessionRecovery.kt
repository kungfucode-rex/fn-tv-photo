package com.tvphoto.data

import com.tvphoto.data.fn.FnSession
import com.tvphoto.data.fn.FnSessionExpiredException

/**
 * What to do with one failed attempt at a signed API call.
 *
 * The failure this exists for is the one a television shows as a bare **401** after the app
 * has been left sitting for hours: fnOS retires the `AccessToken`, the next request is
 * answered `code 401`, and a row appears with a 重试 button under it. Pressing that button
 * used to be a dead end — the same stale token went out again, the same 401 came back, and
 * the only way out was 退出登录 followed by a fresh login. A retry has to be that login.
 *
 * Three outcomes, and the shape of the login is what decides between them:
 *
 *  - a failure that is not the token — a timeout, a refused certificate, a body that was
 *    not JSON — is retried **as it is**. It says nothing about the session, and a fresh
 *    login cannot fix a NAS that is asleep;
 *  - a retired token is retried **after a fresh login**. Once per call: a second expiry
 *    answers a second 401, which is the server disagreeing with a session it has just
 *    issued, and going round again would only turn that into a loop with a spinner on it;
 *  - no session to log in with — no saved credentials, or a login that failed — is not this
 *    layer's problem. There is nothing it can do, and the caller's own error, or the
 *    login's, is the honest answer for the login form to show.
 *
 * [refresh] answers a session only when a **new** one was actually accepted from a
 * handshake, and how that is decided is the one thing here that had to be got right twice.
 * The first shape compared the two tokens and treated "the same string" as "nothing
 * happened" — which a mock NAS disproved in a minute, because its token is
 * `mock-token-<user>`-shaped and logging in again returns that very string. The guard then
 * read a successful login as a failed one and rethrew the 401 without retrying. A token is
 * opaque: two equal ones can be two different sessions, and a server may deliberately hand
 * the same one back to the same device. What actually distinguishes the two cases is
 * decided where the handshake happens (`SessionRepository.sessionGeneration`), and this
 * object only consumes the answer.
 *
 * Pure on purpose, so the rules above can be pinned by a test that never touches Android, a
 * socket or a NAS. [SessionRepository.withSession] is the only caller.
 */
internal object SessionRecovery {

    /** Whether this failure is the server saying the `AccessToken` is no longer valid. */
    fun isExpiry(error: Throwable): Boolean = error is FnSessionExpiredException

    /**
     * The answer for one [error]: retry with [perform] as it is, or log in again with
     * [refresh] and retry with whatever that produced.
     *
     * [refresh] may throw — a NAS that is off fails the login the same way it failed the
     * request — and the caller lets that failure out rather than the original expiry, so
     * the reason on screen is the one somebody can act on.
     */
    suspend fun <T> retry(
        error: Throwable,
        session: FnSession,
        canRefresh: Boolean,
        refresh: suspend () -> FnSession?,
        perform: suspend (FnSession) -> T,
    ): T {
        if (!isExpiry(error)) return perform(session)
        if (!canRefresh) throw error

        val refreshed = refresh() ?: throw error
        return perform(refreshed)
    }
}
