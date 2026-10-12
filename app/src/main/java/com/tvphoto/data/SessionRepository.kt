package com.tvphoto.data

import com.tvphoto.data.fn.ERROR_CERTIFICATE_CHANGED
import com.tvphoto.data.fn.FnAccessCodeClient
import com.tvphoto.data.fn.FnAccessCodeRejectedException
import com.tvphoto.data.fn.FnAuthException
import com.tvphoto.data.fn.FnAuthClient
import com.tvphoto.data.fn.FnCertificateChangedException
import com.tvphoto.data.fn.FnCertificateTrust
import com.tvphoto.data.fn.FnNetworkException
import com.tvphoto.data.fn.FnSession
import com.tvphoto.data.fn.webSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.WebSocket

sealed interface SessionState {
    data object SignedOut : SessionState
    data object SigningIn : SessionState
    data class SignedIn(val session: FnSession) : SessionState
    data class Failed(val message: String) : SessionState
}

/** Diagnostic detail used when the user left the device address empty. */
const val ERROR_HOST_REQUIRED = "device address is required"

/**
 * Owns the login lifecycle.
 *
 * Holds the login WebSocket open and pings it, because that socket carries the
 * session the token belongs to, and re-runs the whole encrypted handshake when the
 * server reports the token is stale.
 */
class SessionRepository(
    private val settings: SettingsStore,
    private val authClient: FnAuthClient,
    private val accessCodeClient: FnAccessCodeClient,
    private val certificateTrust: FnCertificateTrust,
    private val httpClient: OkHttpClient,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.SignedOut)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /**
     * True while a session that went stale is being re-established behind the user's back.
     *
     * Distinct from [state] being [SessionState.SigningIn], which is what the login form
     * shows while the user is typing credentials: this one means "something the app was
     * doing lost its session and is logging back in", and the UI answers it with the same
     * loading screen a start-up sign-in gets rather than with a login form nobody asked
     * for. See `SessionRecovery` for why the re-login has to happen at all.
     */
    private val _recovering = MutableStateFlow(false)
    val recovering: StateFlow<Boolean> = _recovering.asStateFlow()

    val current: FnSession? get() = (_state.value as? SessionState.SignedIn)?.session
    val token: String? get() = current?.token

    /**
     * Serialises logins.
     *
     * A screen that loads several lists at once fails all of them together when the token
     * has expired, and every one of those failures asks for a fresh login. Without this
     * they would run the encrypted handshake concurrently, each adopting a session and
     * closing the socket the other had just installed — which on fnOS is how a device ends
     * up holding a token the server has already retired, the very state being repaired.
     * One login at a time; the rest wait and are answered from the session it produced.
     */
    private val signInLock = Mutex()

    /**
     * How many sessions have been accepted from a handshake, ever.
     *
     * The only trustworthy answer to "did that refresh actually produce a new session?", and
     * therefore the thing that stops `SessionRecovery` from replaying a token the server has
     * already refused. Comparing the tokens themselves looks equivalent and is not: a token
     * is opaque, and a server that hands the same one back for the same device (the mock NAS
     * in `tools/mock-nas` does exactly this) would read as a refresh that never happened —
     * which is a retry thrown away as if the login had failed.
     *
     * Volatile because it is read on whichever coroutine is retrying and written by the one
     * that just logged in; the value is only ever compared for equality with an earlier
     * read, so no stronger ordering is needed.
     */
    @Volatile
    private var sessionGeneration: Long = 0

    private var socket: WebSocket? = null
    private var keepAlive: Job? = null

    /**
     * Signs in to a server address such as `192.168.31.192:50316`.
     *
     * An address with no port takes the fnOS default (5666, or 5667 for `https://`).
     */
    suspend fun signIn(
        address: String,
        userName: String,
        password: String,
        accessCode: String = "",
    ): Result<FnSession> = signInLock.withLock {
        signInLocked(address, userName, password, accessCode)
    }

    private suspend fun signInLocked(
        address: String,
        userName: String,
        password: String,
        accessCode: String = "",
    ): Result<FnSession> {
        val typed = address.trim()

        // Persist what the user typed *before* attempting the login, so a failed
        // attempt — a wrong access code, a NAS that is asleep — does not make them
        // retype everything with a remote.
        settings.userName = userName
        settings.password = password
        settings.accessCode = accessCode.trim()

        if (typed.isBlank()) {
            _state.value = SessionState.Failed(ERROR_HOST_REQUIRED)
            return Result.failure(FnNetworkException(ERROR_HOST_REQUIRED))
        }

        val baseUrl = normalizeBaseUrl(typed)
        if (baseUrl.isBlank()) {
            _state.value = SessionState.Failed(ERROR_HOST_REQUIRED)
            return Result.failure(FnNetworkException(ERROR_HOST_REQUIRED))
        }

        // Remembered in the same form the login screen looks it up by, so the shortcut
        // list and this share one definition of "the same server".
        val remembered = rememberedHost(typed)
        settings.host = remembered
        settings.saveAccount(
            SavedAccount(
                host = remembered,
                user = userName,
                password = password,
                accessCode = accessCode.trim(),
            ),
        )

        _state.value = SessionState.SigningIn

        return try {
            // Pass the gateway's access-code gate first when one is configured. The
            // authorisation lands in the cookie jar, which the login WebSocket then
            // reuses — without it the upgrade is refused with 401.
            if (accessCode.isNotBlank()) {
                when (accessCodeClient.authorize(baseUrl, accessCode.trim())) {
                    FnAccessCodeClient.Result.AUTHORIZED,
                    FnAccessCodeClient.Result.NOT_REQUIRED,
                    -> Unit

                    FnAccessCodeClient.Result.REJECTED ->
                        throw FnAccessCodeRejectedException()

                    FnAccessCodeClient.Result.UNREACHABLE -> Unit // let login report it
                }
            }

            val loggedIn = authClient.login(baseUrl, userName, password)
            adopt(loggedIn.session, loggedIn.socket)
            Result.success(loggedIn.session)
        } catch (e: Exception) {
            // A refused certificate is this client's own decision, not a transport
            // fault, so it must not surface as OkHttp's "hostname not verified" — the
            // user needs to be told the certificate changed and that they can accept it.
            if (certificateTrust.consumeChangedHost() != null) {
                _state.value = SessionState.Failed(ERROR_CERTIFICATE_CHANGED)
                return Result.failure(FnCertificateChangedException())
            }
            _state.value = SessionState.Failed(e.message ?: "login failed")
            Result.failure(e)
        }
    }

    /**
     * Re-runs the whole login — access-code gate, encrypted handshake and all — with the
     * credentials already on the device.
     *
     * The one entry point for "get me a usable session again", so that whatever a user does
     * to recover by hand (退出登录, then 登录) is exactly what the app does by itself. The
     * session it retires first is what makes the two the same: fnOS keeps its own view of
     * which device holds a session, and a handshake started while the previous socket is
     * still open can be answered with an authorisation error rather than a token.
     */
    suspend fun reSignIn(): Result<FnSession> {
        if (!settings.hasSavedCredentials) {
            return Result.failure(FnAuthException(0, "no saved credentials"))
        }
        // Outside the lock on purpose: this is closing the *old* socket, and a login
        // running behind the lock has already replaced it.
        retireSocket()
        return signInLock.withLock {
            signInLocked(
                address = settings.host,
                userName = settings.userName,
                password = settings.password,
                accessCode = settings.accessCode,
            )
        }
    }

    fun signOut() {
        keepAlive?.cancel()
        keepAlive = null

        val previous = socket
        socket = null
        _state.value = SessionState.SignedOut

        if (previous != null) {
            // Ask for a clean close so the server can retire the session, then make
            // sure the connection is actually gone even if it never acknowledges.
            // A session the server still believes is open can make the next login
            // fail with an authorisation error.
            previous.close(CLOSE_NORMAL, "sign out")
            scope.launch {
                delay(CLOSE_GRACE_MS)
                runCatching { previous.cancel() }
            }
        }
    }

    /**
     * Runs [block] with the active session and, if the server says the token has
     * expired, logs in again and replays it exactly once.
     *
     * This is the whole of the app's answer to the 401 a television shows after hours of
     * being left alone: the retry the user can press has to land on a working session, not
     * on the same stale token. The rules — what counts as an expiry, when a login is worth
     * running, and when going round again would only be a loop — are in [SessionRecovery],
     * where a unit test can pin them without a NAS.
     */
    suspend fun <T> withSession(block: suspend (FnSession) -> T): T {
        val session = current ?: reSignIn().getOrElse { throw it }
        return try {
            block(session)
        } catch (failed: Throwable) {
            SessionRecovery.retry(
                error = failed,
                session = session,
                canRefresh = settings.hasSavedCredentials,
                refresh = { refreshSession() },
                perform = block,
            )
        }
    }

    /**
     * Logs in again for a caller that has just been told its token is stale, and answers
     * the session that login produced — or null when it produced none.
     *
     * Null is the one case [SessionRecovery] must not replay through, and it is decided
     * here rather than by looking at the token: a login that succeeded has bumped
     * [sessionGeneration], whatever the token it came back with happens to say. A login
     * that failed does not reach this at all — [reSignIn] publishes
     * [SessionState.Failed] and the exception travels out, which is what puts the form,
     * with the server's own reason on it and the credentials already filled in, in front of
     * the only person who can fix it.
     *
     * [recovering] is raised around the whole thing so the UI can keep the user on a loading
     * screen instead of flashing a login form at somebody who never signed out.
     */
    private suspend fun refreshSession(): FnSession? {
        val before = sessionGeneration
        _recovering.value = true
        return try {
            val refreshed = reSignIn().getOrElse { throw it }
            refreshed.takeIf { sessionGeneration != before }
        } finally {
            _recovering.value = false
        }
    }

    /**
     * Closes the login socket and lets the server retire the session it carried.
     *
     * The pause before returning is the point rather than politeness: the close frame and
     * the handshake that follows it travel the same connection pool, and a login that
     * overtakes the close is a login the server can still see as the old session's.
     */
    private suspend fun retireSocket() {
        keepAlive?.cancel()
        keepAlive = null

        val previous = socket ?: return
        socket = null
        runCatching { previous.close(CLOSE_NORMAL, "re-login") }
        delay(CLOSE_GRACE_MS)
        runCatching { previous.cancel() }
    }

    private fun adopt(session: FnSession, newSocket: WebSocket) {
        keepAlive?.cancel()
        socket?.close(CLOSE_NORMAL, "replaced")
        socket = newSocket
        // Bumped before the state is published, so any retry woken by it sees the new
        // generation rather than the one it was waiting to be replaced.
        sessionGeneration++
        _state.value = SessionState.SignedIn(session)
        keepAlive = scope.launch {
            // Keeps the server-side session alive; the token's own lifetime is
            // undocumented, so the socket is never left to idle out.
            while (true) {
                delay(PING_INTERVAL_MS)
                val sent = runCatching { newSocket.send("""{"req":"ping"}""") }.getOrDefault(false)
                if (!sent) break
            }
        }
    }

    private companion object {
        const val TAG = "SessionRepository"
        const val PING_INTERVAL_MS = 25_000L
        const val CLOSE_NORMAL = 1000
        const val CLOSE_GRACE_MS = 1_500L
    }
}

/** Convenience for diagnostics and the settings screen. */
internal fun FnSession.describe(): String = "$userName @ ${webSocketUrl(baseUrl)}"
