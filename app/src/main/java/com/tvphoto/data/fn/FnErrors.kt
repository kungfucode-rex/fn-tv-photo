package com.tvphoto.data.fn

/**
 * Protocol failures.
 *
 * Messages are deliberately locale-neutral and technical: the data layer has no
 * `Resources`, so translating them here would either hardcode one language or
 * require threading a `Context` through the whole network stack. The UI adds its
 * own localised prefix (see `R.string.error_prefix`) and shows this text as the
 * diagnostic detail.
 */
sealed class FnException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Diagnostic detail used when the gateway demands an access code. */
const val ERROR_ACCESS_CODE_REQUIRED = "this device requires an access code"

/** Diagnostic detail used when the entered access code was refused. */
const val ERROR_ACCESS_CODE_REJECTED = "access code rejected"

/** Diagnostic detail used when the NAS presented a certificate other than the pinned one. */
const val ERROR_CERTIFICATE_CHANGED = "server certificate changed"

/** The WebSocket login handshake failed. `errno` is the server's code. */
class FnAuthException(val errno: Int, detail: String?) : FnException(
    buildString {
        append("errno ").append(errno)
        if (!detail.isNullOrBlank()) append(": ").append(detail)
    },
)

/** DNS, TCP or TLS failure. */
class FnNetworkException(message: String, cause: Throwable? = null) : FnException(message, cause)

/** The server rejected our `authx` signature (business code 5000). */
class FnSignatureException : FnException("authx signature rejected (code 5000)")

/** The AccessToken is no longer valid (business code 401/5001); re-login and retry. */
class FnSessionExpiredException : FnException("access token expired (code 401/5001)")

/**
 * The gateway refused the login because this device has 访问码 enabled and no valid
 * access-code cookie is present. The UI turns this into an actionable prompt rather
 * than a bare 401.
 */
class FnAccessCodeRequiredException : FnException(ERROR_ACCESS_CODE_REQUIRED)

/** The supplied access code was rejected by `/access_code_verify`. */
class FnAccessCodeRejectedException : FnException(ERROR_ACCESS_CODE_REJECTED)

/**
 * The server's certificate no longer matches the fingerprint pinned on first use.
 *
 * Not a transport failure: this client is refusing a changed certificate, and the pin has
 * already been dropped so the next attempt can accept it deliberately.
 */
class FnCertificateChangedException : FnException(ERROR_CERTIFICATE_CHANGED)

/** Any other non-zero business code. */
class FnApiException(val code: Int, val serverMessage: String) : FnException(
    if (serverMessage.isBlank()) "code $code" else "code $code: $serverMessage",
)
