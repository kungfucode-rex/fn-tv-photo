package com.tvphoto.data.fn

import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Performs the fnOS encrypted WebSocket login.
 *
 * Sequence: open `ws://host:5666/websocket?type=main`, ask for the RSA public key,
 * then send the credentials inside an AES-256-CBC envelope whose AES key is itself
 * RSA encrypted. The reply carries the `AccessToken` used by every later HTTP call.
 *
 * The returned [LoggedIn.socket] is intentionally handed back to the caller rather
 * than closed: the session is kept warm by pinging it (see `SessionRepository`).
 */
class FnAuthClient(private val httpClient: OkHttpClient) {

    class LoggedIn(val session: FnSession, val socket: WebSocket)

    suspend fun login(baseUrl: String, userName: String, password: String): LoggedIn {
        val socketUrl = webSocketUrl(baseUrl)
        val deviceId = FnCrypto.deviceId()

        // A new handshake must not inherit the previous session's request-id state.
        FnReqId.reset()

        return suspendCancellableCoroutine { cont ->
            val listener = object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(
                        JSONObject()
                            .put("req", REQ_RSA_PUB)
                            .put("reqid", FnReqId.next())
                            .toString(),
                    )
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (cont.isCompleted) return
                    val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                    when {
                        // Keepalive acknowledgement; not part of the handshake.
                        json.optString("res") == "pong" -> Unit

                        json.has("pub") -> try {
                            webSocket.send(buildEncryptedLogin(json, userName, password, deviceId))
                        } catch (t: Throwable) {
                            cont.resumeWithException(
                                FnNetworkException("crypto failed: ${t.message}", t),
                            )
                        }

                        json.optString("result") == "succ" && json.has("token") -> {
                            FnReqId.setBackId(json.optString("backId"))
                            cont.resume(LoggedIn(json.toSession(baseUrl, userName), webSocket))
                        }

                        json.has("errno") -> {
                            // Surface everything the server said. A bare "errno 401" is
                            // not actionable, and the server's own wording is what
                            // distinguishes a rejected session from bad credentials.
                            val errno = json.optInt("errno")
                            val detail = listOf(json.optString("result"), json.optString("msg"))
                                .filter { it.isNotBlank() && it != "null" }
                                .joinToString(" / ")
                            Log.w(TAG, "login rejected by $socketUrl: errno=$errno detail=$detail")
                            cont.resumeWithException(
                                FnAuthException(errno, detail.ifBlank { null }),
                            )
                        }
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (cont.isCompleted) return
                    val status = response?.code
                    val challenged = response?.header(FnAccessCodeClient.CHALLENGE_HEADER) != null

                    // A refused upgrade is the gateway's access-code gate, not a
                    // transport problem — say so, because "401" alone tells the user
                    // nothing they can act on.
                    val failure = when {
                        challenged || status == 401 || status == 403 -> {
                            Log.w(
                                TAG,
                                "handshake refused (HTTP $status, challenge=$challenged) at $socketUrl",
                            )
                            FnAccessCodeRequiredException()
                        }

                        else -> FnNetworkException("cannot connect to $socketUrl: ${t.message}", t)
                    }
                    cont.resumeWithException(failure)
                }
            }

            val socket = httpClient.newWebSocket(Request.Builder().url(socketUrl).build(), listener)
            cont.invokeOnCancellation { socket.cancel() }
        }
    }

    private fun buildEncryptedLogin(
        challenge: JSONObject,
        userName: String,
        password: String,
        deviceId: String,
    ): String {
        // `si` is an 18-digit number delivered as a string. Parsing it into a numeric
        // type would round the low digits and change the JSON field type, which the
        // server rejects with errno 8192 while still decrypting fine — so it is read
        // as a string and echoed back untouched.
        val si = challenge.optString("si")
        val publicKey = challenge.getString("pub")

        val payload = JSONObject()
            .put("req", REQ_LOGIN)
            .put("reqid", FnReqId.next())
            .put("si", si)
            .put("user", userName)
            .put("password", password)
            .put("stay", true)
            .put("deviceType", DEVICE_TYPE)
            .put("deviceName", DEVICE_NAME)
            .put("did", deviceId)
            .toString()

        val aesKey = FnCrypto.randomString(32)
        val iv = FnCrypto.randomBytes(16)

        return JSONObject()
            .put("req", "encrypted")
            .put("iv", FnCrypto.base64(iv))
            .put("rsa", FnCrypto.base64(FnCrypto.rsaEncrypt(publicKey, aesKey)))
            .put("aes", FnCrypto.base64(FnCrypto.aesEncrypt(payload, aesKey, iv)))
            .toString()
    }

    private fun JSONObject.toSession(baseUrl: String, userName: String) = FnSession(
        baseUrl = baseUrl.trimEnd('/'),
        token = optString("token"),
        secret = optString("secret"),
        backId = optString("backId"),
        uid = optLong("uid", 0L),
        isAdmin = optBoolean("admin", false),
        userName = optString("user").ifBlank { userName },
    )

    private companion object {
        const val TAG = "FnAuthClient"
        const val REQ_RSA_PUB = "util.crypto.getRSAPub"
        const val REQ_LOGIN = "user.login"
        const val DEVICE_TYPE = "AndroidTV"
        const val DEVICE_NAME = "FN Photo"
    }
}

/** Turns a base URL such as `http://nas:5666` into the login WebSocket URL. */
internal fun webSocketUrl(baseUrl: String): String {
    val trimmed = baseUrl.trimEnd('/')
    val schemeSwapped = when {
        trimmed.startsWith("https://") -> "wss://" + trimmed.removePrefix("https://")
        trimmed.startsWith("http://") -> "ws://" + trimmed.removePrefix("http://")
        else -> "ws://$trimmed"
    }
    return "$schemeSwapped/websocket?type=main"
}
