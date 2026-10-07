package com.tvphoto.data

import com.tvphoto.data.fn.ERROR_CERTIFICATE_CHANGED
import com.tvphoto.data.fn.FnAccessCodeClient
import com.tvphoto.data.fn.FnAccessCodeRejectedException
import com.tvphoto.data.fn.FnApi
import com.tvphoto.data.fn.FnAuthClient
import com.tvphoto.data.fn.FnCertificateChangedException
import com.tvphoto.data.fn.FnCertificateTrust
import com.tvphoto.data.fn.FnException
import com.tvphoto.data.fn.FnSession
import com.tvphoto.data.fn.FnSessionExpiredException
import com.tvphoto.data.fn.FnAuthException
import com.tvphoto.data.fn.FnNetworkException
import com.tvphoto.data.fn.webSocketUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    val current: FnSession? get() = (_state.value as? SessionState.SignedIn)?.session
    val token: String? get() = current?.token

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

    /** Re-runs the handshake with the stored credentials. */
    suspend fun reSignIn(): Result<FnSession> {
        if (!settings.hasSavedCredentials) {
            return Result.failure(FnAuthException(0, "no saved credentials"))
        }
        return signIn(
            address = settings.host,
            userName = settings.userName,
            password = settings.password,
            accessCode = settings.accessCode,
        )
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
     * expired, signs in again and replays it exactly once.
     */
    suspend fun <T> withSession(block: suspend (FnSession) -> T): T {
        val session = current ?: reSignIn().getOrElse { throw it }
        return try {
            block(session)
        } catch (expired: FnSessionExpiredException) {
            val refreshed = reSignIn().getOrElse { throw expired }
            block(refreshed)
        }
    }

    private fun adopt(session: FnSession, newSocket: WebSocket) {
        keepAlive?.cancel()
        socket?.close(1000, "replaced")
        socket = newSocket
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
        const val PING_INTERVAL_MS = 25_000L
        const val CLOSE_NORMAL = 1000
        const val CLOSE_GRACE_MS = 1_500L
    }
}

/** Convenience for diagnostics and the settings screen. */
internal fun FnSession.describe(): String = "$userName @ ${webSocketUrl(baseUrl)}"
