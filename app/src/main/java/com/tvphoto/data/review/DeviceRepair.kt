package com.tvphoto.data.review

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs one authenticated call, and answers a refused device token by registering this
 * device again and running the call once more.
 *
 * This is the repair the app was missing. A server that was reinstalled, restored from an
 * old backup, replaced at the same address, or simply restarted onto a fresh database
 * answers `/healthz` — so the settings row reads 已连接 — and refuses every request that
 * carries the token this television still holds. Without the repair the failure is
 * permanent and invisible: the viewer's 图评 button greys out, nothing on screen says why,
 * and no press anywhere in the app can clear a token the user cannot see.
 *
 * Only a refusal is repaired, never a network failure: minting a new device because a
 * packet was dropped would fill the server's device table with ghosts.
 *
 * **A repair also means the server has been replaced, and [onServerReplaced] is the half
 * of that the app used to skip.** A refused token is a server saying it has never heard of
 * this television — which is not a fact about the token alone. Every short code, image id
 * and comment this television is holding was issued by *that* server, and the only reason
 * the app kept them was an assumption that the server would recognise the photo again when
 * it was registered once more (the identity layer does match the same bytes to the same
 * photo — when the rows are still there). Measured on 2026-10-11, against a `tupingd`
 * redeployed onto an empty data directory: the device row was gone, the photo rows were
 * gone, nothing re-registered a photo whose identity was cached, and the television went on
 * showing a QR code that scanned to the H5 page's **这张照片不在图评里**. The comment socket
 * for the same cached id answered 404. Dropping the answers as well as the credentials is
 * cheap — a photo registered again comes back as the same photo — and the case it costs
 * nothing in is exactly the case the old assumption was written for.
 *
 * [attempt] is handed the client rather than capturing one, because the repair rebuilds
 * it — a client carries the token it was constructed with.
 */
internal class DeviceRepair(
    /** What the settings hold right now: server, device id and token, as one string. */
    private val fingerprint: () -> String,
    /** Drops the device credentials, so the next client is built without them. */
    private val forgetCredentials: () -> Unit,
    /** Mints a new device. False when the server will not issue one at all. */
    private val registerDevice: suspend () -> Boolean,
    /** The server has been replaced: everything it answered before is stale. */
    private val onServerReplaced: () -> Unit,
    /**
     * Where the two lines this file has to say go.
     *
     * Injected for one reason: a local unit test cannot call `android.util.Log` — the
     * framework's stubs throw — and this class is otherwise pure Kotlin over a socket, so
     * being testable is worth one parameter. The repository passes nothing and gets the log
     * a television has no console for.
     */
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {

    /**
     * Serialises registering this device, so a burst of refused calls mints one identity.
     *
     * The calls that can discover a refused token arrive together — a photo being opened
     * registers it and pre-registers the four behind it — and two repairs racing would each
     * issue a device, leaving whichever client lost the race holding a token the server has
     * already replaced.
     */
    private val lock = Mutex()

    suspend fun <T> run(
        current: () -> ReviewClient?,
        attempt: suspend (ReviewClient) -> Result<T>,
    ): Result<T> {
        val built = current() ?: return Result.failure(
            IllegalStateException("图评: no usable server address"),
        )
        // What the call was signed with. If it is no longer what the settings hold, somebody
        // else has already repaired the device and this call only needs running again.
        val signedWith = fingerprint()
        val first = attempt(built)
        if (first.isSuccess || !isRefusedToken(first.exceptionOrNull())) return first

        log("图评: ${built.server} does not accept this device's token; registering again")

        // True only for the caller that actually performed the repair, because that is the
        // one that has to tell the rest of the app the server was replaced. A caller that
        // finds the fingerprint already moved is riding on somebody else's repair, and that
        // somebody has already done it.
        var replaced = false
        val repaired = lock.withLock {
            if (fingerprint() != signedWith) {
                true
            } else {
                forgetCredentials()
                registerDevice().also { replaced = it }
            }
        }
        if (!repaired) return first

        if (replaced) {
            log("图评: ${built.server} issued a new device; forgetting its old answers")
            onServerReplaced()
        }

        val rebuilt = current() ?: return first
        return attempt(rebuilt)
    }

    private companion object {
        const val TAG = "DeviceRepair"
    }
}
