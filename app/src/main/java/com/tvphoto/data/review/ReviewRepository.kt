package com.tvphoto.data.review

import android.content.Context
import android.util.Log
import com.tvphoto.domain.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Everything the viewer needs to know about the photo on screen and its comments.
 *
 * A single immutable snapshot rather than half a dozen flows, because the danmaku layer,
 * the QR code and the 评论 N line are three views of one answer and must never disagree:
 * "there are comments" and "here is the code" arriving separately is exactly the state
 * the design's §9.5 forbids (the 评论 N line is the only way to know a photo is being
 * talked about when the QR code is off).
 */
sealed interface ReviewView {
    /** 图评 is off for this photo — no code, no danmaku, no network. */
    data object Hidden : ReviewView

    /** A code is being obtained. The viewer shows nothing while this is true. */
    data object Preparing : ReviewView

    data class Ready(
        val identity: ReviewIdentity,
        /** The QR PNG on disk, or null while it is still being fetched. */
        val qrFile: File?,
        val commentCount: Int,
        val comments: List<ReviewComment>,
        val status: ReviewStatus,
    ) : ReviewView
}

/**
 * The album end's whole conversation with 图评.
 *
 * Three rules shape this class, and each of them is a way 图评 could make the app worse
 * than not having it:
 *
 *  1. **Failure is silent.** Nothing here throws at the caller, and a missing server
 *     produces `Hidden`, not an error screen. Watching photos must be unaffected
 *     (docs/tuping-design.md §9.3).
 *  2. **Nothing blocks the viewer.** Registration and the QR fetch happen ahead of the
 *     photo being looked at, and an answer that arrives late is discarded rather than
 *     applied to whatever is on screen by then.
 *  3. **The comment count is part of the picture.** It comes from the same response as
 *     the code, so a photo whose QR code failed still reports that people have written
 *     about it.
 */
class ReviewRepository(
    context: Context,
    private val settings: ReviewSettingsStore,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    private val appContext = context.applicationContext

    /** QR PNGs, kept on disk so leaving and re-entering a photo does not re-fetch one. */
    private val qrDir: File = File(appContext.cacheDir, "review-qr")

    /** deviceId + code -> identity, for the life of the process. */
    private val identities = ConcurrentHashMap<String, ReviewIdentity>()

    /** imageId -> comments already fetched, so a return visit shows them at once. */
    private val commentCache = ConcurrentHashMap<String, MutableList<ReviewComment>>()

    /**
     * Photos this session has already found to be unknown to the server, once each.
     *
     * The guard is what keeps the self-heal in [refreshComments] from looping: a server
     * that answers `not_found` even for a photo it has *just* been handed is a server with
     * a bug, and registering the same picture again and again would be this end's.
     */
    private val healedPhotos = ConcurrentHashMap.newKeySet<String>()

    /**
     * What the viewer last asked for, so a repair can ask again.
     *
     * The request is the only place the photo itself is — [openPhoto] is handed an item and
     * throws it away — and a repair has to register the photo on screen again, because the
     * code that screen is showing is a code the new server never issued.
     */
    private var lastOpen: OpenRequest? = null

    private data class OpenRequest(
        val item: MediaItem,
        val source: String,
        val decision: ReviewDecision,
        val danmakuLevel: DanmakuLevel,
        /** Whether this was a screensaver, where the code is never drawn (see [openPhoto]). */
        val screenSaver: Boolean,
        /** Whether a show was running, which the 幻灯片 row's own 图评 switch governs. */
        val slideshow: Boolean,
    )

    private val _view = MutableStateFlow<ReviewView>(ReviewView.Hidden)
    val view: StateFlow<ReviewView> = _view.asStateFlow()

    private val _status = MutableStateFlow<ReviewStatus>(ReviewStatus.Off)
    val status: StateFlow<ReviewStatus> = _status.asStateFlow()

    private var client: ReviewClient? = null
    private var clientFingerprint: String = ""

    /**
     * The device repair, and the reason this repository hands it a callback rather than
     * doing the work itself: a server that refuses this television's token has been
     * replaced, and everything this repository cached came from the server it replaced.
     * [onServerReplaced] is that conclusion's other half (see [DeviceRepair]).
     */
    private val repair = DeviceRepair(
        fingerprint = { settings.fingerprint() },
        forgetCredentials = { forgetCredentials() },
        registerDevice = { ensureDevice() },
        onServerReplaced = { onServerReplaced() },
    )

    /** The photo the current answer belongs to, so a late answer can be discarded. */
    private var currentLocalId: String? = null

    private var socket: WebSocket? = null

    /**
     * Rebuilds the client when the address or the tokens change.
     *
     * Done lazily on every use rather than on a settings callback: a settings screen that
     * forgot to notify would otherwise leave the app talking to the previous server,
     * which is the failure mode with the least visible symptom.
     */
    private fun client(): ReviewClient? {
        val fingerprint = settings.fingerprint()
        if (fingerprint != clientFingerprint) {
            clientFingerprint = fingerprint
            client = null
        }
        client?.let { return it }

        val url = settings.serverUrl
        if (url.isBlank()) {
            // Logged for the same reason the settings row exists: "it is off and I do not
            // know why" is the report this app has to be able to answer, and on a TV the
            // log is the only place the answer can be more detailed than one line.
            Log.i(TAG, "图评: no server address is set, so nothing is attempted")
            _status.value = ReviewStatus.NoServer
            return null
        }
        val built = ReviewClient(url, settings.deviceId, settings.deviceToken)
        if (!built.configured) {
            // Printed only after the store has dropped the invisible characters and folded
            // the full-width ones (`cleanServerAddress`), so an address that still will not
            // parse has something visibly wrong with it. That order is the lesson of this
            // line: it used to be printed for `106.52.237.179:53347` with a NUL on the end —
            // a perfectly good-looking address, reported as "blank, or not a URL", with the
            // server up and the television able to reach it.
            Log.i(TAG, "图评: address $url was rejected as unusable (blank, or not a URL)")
            _status.value = ReviewStatus.NoServer
            return null
        }
        client = built
        return built
    }

    /**
     * Drops this device's credentials, keeping the answers about photos for now.
     *
     * The device id goes with the token on purpose. A server that knows the id but not the
     * token refuses to re-issue one — `handleDeviceRegister` authenticates an existing id
     * against the token it was given and answers 401 设备令牌不对 when that fails — so the
     * only identity a server is guaranteed to accept is a *new* one. The cost is a spare row
     * in its devices table; the alternative is a television that can never be reconnected
     * without clearing the app's data.
     *
     * The identities and comments in memory are dropped by [forgetServerAnswers], which the
     * repair calls as soon as the new device is in hand — not here, because a registration
     * that is *refused* (registration closed on the server) leaves this television with
     * nothing to replace them with, and its old answers are then still the best it has.
     */
    private fun forgetCredentials() {
        settings.forgetDevice()
        clientFingerprint = ""
        client = null
    }

    /**
     * Forgets every answer the previous server gave about photos.
     *
     * These are keyed by *its* image ids, and the QR PNGs on disk encode *its* short codes,
     * so a server that no longer knows this device cannot honour any of them. Called from
     * the repair, where the fresh device is proof of the replacement, and from
     * [forgetDevice], where the user pointed the app somewhere else.
     */
    private fun forgetServerAnswers() {
        identities.clear()
        commentCache.clear()
        healedPhotos.clear()
        // Deleted here rather than on a coroutine: the very next thing to touch this
        // directory is the `qrFileFor` of whatever is registered next, and a few small
        // PNGs are not worth a race with it. A failure is ignored — a stale file that
        // nothing references is not a state anybody can be in.
        runCatching { qrDir.listFiles()?.forEach { it.delete() } }
    }

    /**
     * The other half of a repair: the code on screen was issued by the old server, so the
     * photo is registered again and a code the new server honours takes its place.
     */
    private fun onServerReplaced() {
        forgetServerAnswers()
        reRegisterCurrentPhoto()
    }

    /**
     * Registers the photo on screen again, replacing the code it was showing.
     *
     * Only when a code is actually on screen. Every caller here has just decided that the
     * identity in hand is worthless, and a photo that is *being* registered is being
     * registered against a server that has already said it knows it — asking a second time
     * would mint two codes for one picture and fetch two QR PNGs. That is not hypothetical:
     * a replaced server makes both of this file's conclusions true at once, since the retry
     * of the old identity's history read comes back `not_found` right after the repair.
     *
     * Registration, not a lookup: the whole point is that the server has *lost* this
     * picture, so asking whether it knows it would answer no and change nothing.
     */
    private fun reRegisterCurrentPhoto() {
        if (_view.value !is ReviewView.Ready) return
        val last = lastOpen ?: return
        Log.i(TAG, "图评: registering ${last.item.id} again; its code came from the old server")
        scope.launch {
            register(last.item, last.source, PhotoIdentity.localId(last.source, last.item), !last.screenSaver)
        }
    }

    /**
     * Makes sure this device has a token, once.
     *
     * Registration is the one call that is worth waiting for, because every later call
     * needs the token it returns.
     */
    suspend fun ensureDevice(): Boolean {
        val built = client() ?: return false
        if (settings.deviceToken.isNotBlank() && settings.deviceId.isNotBlank()) return true

        val id = settings.deviceId.ifBlank { randomDeviceId() }
        _status.value = ReviewStatus.Registering(built.server)

        val result = retry(times = 3) {
            built.issueDevice(id = id, name = DEVICE_NAME, existingToken = settings.deviceToken)
        }
        return result.fold(
            onSuccess = { credentials ->
                settings.deviceId = credentials.deviceId
                settings.deviceToken = credentials.deviceToken
                if (credentials.ownerToken.isNotBlank()) settings.ownerToken = credentials.ownerToken
                clientFingerprint = "" // force a rebuild with the new token
                _status.value = ReviewStatus.Online(built.server, System.currentTimeMillis())
                true
            },
            onFailure = { error ->
                report(built.server, error)
                false
            },
        )
    }

    /**
     * Runs one authenticated call through the device repair.
     *
     * The repair and every rule it follows — only a refusal counts, one repair per burst,
     * and what a repair costs — live in [DeviceRepair]; this is the wiring that gives it
     * this repository's credentials and cache.
     */
    private suspend fun <T> withDeviceRetry(
        attempt: suspend (ReviewClient) -> Result<T>,
    ): Result<T> = repair.run(current = { client() }, attempt = attempt)

    /**
     * Makes sure the server accepts the device this row is reporting on, repairing it once
     * if it does not.
     *
     * `/healthz` alone is what made 已连接 worthless: it is the same answer for a server
     * that this television can use and for one that has thrown its registration away. The
     * probe is what turns the row into a diagnosis (docs/tuping-design.md §9.4).
     *
     * It goes through [withDeviceRetry] rather than repairing by itself, and that is not
     * tidiness: a photo being opened probes and registers at the same moment, so two
     * unguarded repairs race and each mints a device — which is exactly what happened the
     * first time this was run against a real server (two `device issued` lines, and the
     * television left holding the loser's token). One lock, one new identity.
     */
    private suspend fun deviceIsAccepted(built: ReviewClient): Boolean {
        val probed = withDeviceRetry { client -> client.deviceAccepted() }
        if (probed.isSuccess) return true

        // A failure always carries one; the elvis is what says so to the compiler, and
        // "could not tell" is the safe reading if that ever stops being true.
        val error = probed.exceptionOrNull() ?: return true
        if (!isRefusedToken(error)) {
            // Not a refusal: the device may simply have been confirmed against a server that
            // answered something unexpected. The health line has just proved it is there, so
            // this is not a state to report as "cannot connect" — and emphatically not one to
            // cost this television its identity over.
            Log.i(
                TAG,
                "图评: ${built.server} answered, but this device could not be confirmed: " +
                    error.message,
            )
            return true
        }

        // Still refused *after* registering again: registration is closed on this server
        // (`TUPING_OPEN_REGISTRATION=false` and a register token this app cannot send), and
        // that reason — rather than a green 已连接 — is what the row has to carry.
        report(built.server, error)
        return false
    }

    /**
     * Shows 图评 for one photo, or hides it — **without uploading anything**.
     *
     * [decision] is the caller's answer to "what should happen about 图评 here", computed
     * from the mode, the address and the display switches (`reviewDecision`). Handing it in
     * rather than reading it here keeps the "is this photo eligible" question where the
     * facts are.
     *
     * What this never does is register a photo nobody asked about (§17.28). A photo the
     * server does not know comes back [ReviewDecision.LOOKUP]-and-miss, which is drawn as
     * nothing at all and logged; the way onto the server is [armPhoto], which a person has
     * to press. [ReviewDecision.REGISTER] is the show whose own 图评 switch is on, where the
     * host asked for the whole rotation.
     *
     * [screenSaver] decides two things left to say here: whether the code's picture is worth
     * fetching at all (a screensaver never draws one) and *why* nothing is being shown.
     * [slideshow] is carried for the log line's sake — a show whose 图评 switch is off is a
     * third way for a photo to be left alone, and a line that blamed the mode or the address
     * for one of those would be the "a line that names the wrong cause is worse than none"
     * mistake this method's logging exists to avoid.
     */
    fun openPhoto(
        item: MediaItem?,
        source: String,
        decision: ReviewDecision,
        danmakuLevel: DanmakuLevel,
        screenSaver: Boolean = false,
        slideshow: Boolean = false,
    ) {
        // Kept before anything can return, so a repair triggered by this very photo can
        // register it again from the same request.
        lastOpen = item?.let { OpenRequest(it, source, decision, danmakuLevel, screenSaver, slideshow) }
        closeSocket()

        if (decision == ReviewDecision.HIDDEN || item == null || item.isVideo) {
            currentLocalId = null
            _view.value = ReviewView.Hidden
            logHidden(item, decision, danmakuLevel, screenSaver, slideshow)
            return
        }

        val localId = PhotoIdentity.localId(source, item)
        currentLocalId = localId

        val cached = identities[identityKey(localId)]
        if (cached != null) {
            // Published here rather than on a coroutine: the answer is already in hand, and
            // leaving the *previous* photo's answer up for a frame is the bug the viewer's
            // own note about a page turn describes — one frame of the last picture's code
            // over this one.
            publishReady(cached, qrFileFor(cached.code))
            return
        }

        // Nothing cached: say "preparing" rather than showing a code that is not there yet.
        // The viewer draws nothing either way, so the state exists for the tests and for the
        // settings page's diagnostics.
        _view.value = ReviewView.Preparing
        scope.launch {
            val known = lookup(source, localId)
            if (known != null) {
                // Refreshed, not taken from the cache: a lookup is the moment the server gets
                // to say which address this code opens, and `POST /v1/admin/codes/rebind`
                // exists precisely because that answer can change under a code that stays the
                // same (§17.27). A few kilobytes per photo per session, and the reason a
                // rebind only needs the television restarted.
                publishReady(known, if (screenSaver) qrFileFor(known.code) else fetchQr(known.code, refresh = true))
                return@launch
            }
            // A miss. Only a show that carries 图评 registers by itself; anything else stays
            // off the server until somebody asks for it.
            if (decision == ReviewDecision.REGISTER) {
                register(item, source, localId, qrNeeded = !screenSaver)
                return@launch
            }
            Log.i(
                TAG,
                "图评: ${item.id} is not on the server — nobody has opened 图评 on it. " +
                    "Press 扫码评论 to put it there; nothing was uploaded.",
            )
            if (currentLocalId == localId) _view.value = ReviewView.Hidden
        }
    }

    /**
     * The explicit act: put *this* photo on the server, so it can be commented on.
     *
     * Bound to the viewer's 图评 row, and the only path in the app that uploads a thumbnail
     * outside a show (§17.28). Everything after it is the existing machinery — identity
     * matching, a short code, the QR picture — so a photo that was registered from another
     * television or another album comes back as the same identity with the same thread.
     *
     * Arming twice is harmless and does not upload twice: the second call answers from the
     * session's cache, or from the server's own `device_local` row, which is what
     * `POST /v1/photos` has always done for a photo it already knows (§6.2).
     */
    fun armPhoto(
        item: MediaItem?,
        source: String,
        danmakuLevel: DanmakuLevel,
        screenSaver: Boolean = false,
        slideshow: Boolean = false,
    ) {
        if (item == null || item.isVideo) return
        lastOpen = OpenRequest(item, source, ReviewDecision.LOOKUP, danmakuLevel, screenSaver, slideshow)

        val localId = PhotoIdentity.localId(source, item)
        currentLocalId = localId

        val cached = identities[identityKey(localId)]
        if (cached != null) {
            publishReady(cached, qrFileFor(cached.code))
            return
        }
        _view.value = ReviewView.Preparing
        scope.launch { register(item, source, localId, qrNeeded = !screenSaver) }
    }

    /**
     * Publishes the answer for a photo the server knows, with the code's picture already
     * resolved by the caller.
     *
     * [qr] is a parameter rather than something this fetches, and that is what keeps the
     * previous photo's answer from staying on screen for a frame: a caller that has the
     * identity in hand publishes at once (with whatever file is on disk), and a caller that
     * had to ask the server fetches the picture first and publishes when it arrives — which
     * is what `ReviewView.Preparing` is for.
     */
    private fun publishReady(identity: ReviewIdentity, qr: File?) {
        if (currentLocalId != identity.localId) return
        _view.value = ReviewView.Ready(
            identity = identity,
            qrFile = qr,
            commentCount = identity.commentCount,
            comments = commentCache[identity.imageId]?.toList().orEmpty(),
            status = _status.value,
        )
        refreshComments(identity)
    }

    /**
     * Asks the server whether it already knows this picture, and caches a yes.
     *
     * A miss returns null rather than failing: it is the ordinary answer for a photo nobody
     * has opened 图评 on, and it has to be as quiet as one (§17.28). A *failure* — nothing
     * answered, or the device token was refused — also returns null here, because the caller
     * has already reported it: [withDeviceRetry] reports a refusal and repairs the device,
     * and the view stays hidden either way.
     */
    private suspend fun lookup(source: String, localId: String): ReviewIdentity? {
        if (!ensureDevice()) return null
        val found = withDeviceRetry { client -> client.lookupPhoto(source, localId) }
        return found.fold(
            onSuccess = { identity ->
                if (identity != null) {
                    identities[identityKey(localId)] = identity
                    _status.value = ReviewStatus.Online(client()?.server.orEmpty(), System.currentTimeMillis())
                }
                identity
            },
            onFailure = { error ->
                report(client()?.server.orEmpty(), error)
                null
            },
        )
    }

    /** The one log line that says why nothing is on screen; see [openPhoto]. */
    private fun logHidden(
        item: MediaItem?,
        decision: ReviewDecision,
        danmakuLevel: DanmakuLevel,
        screenSaver: Boolean,
        slideshow: Boolean,
    ) {
        // Said out loud, because "已连接 in the settings, no code on the photo" has several
        // unrelated causes and this is only a log — there is no console on a television, and
        // a line that names the wrong cause is worse than none.
        //
        // The previous version of this line blamed the mode unconditionally, which sent one
        // diagnosis in the wrong direction: the actual reason was an EMPTY SERVER ADDRESS,
        // and the mode was fine. Each cause is named separately now.
        val danmakuOn = danmakuDrawn(danmakuLevel)
        val reason = when {
            item == null -> "nothing on screen"
            item.isVideo -> "it is a video"
            settings.serverUrl.isBlank() -> "no server address is set"
            settings.mode == ReviewMode.OFF -> "the mode is 关"
            screenSaver && !danmakuOn ->
                "a screensaver draws no code and the danmaku is off, so there is nothing to ask for"
            else -> "the caller said no (decision=$decision)"
        }
        Log.i(
            TAG,
            "图评: nothing for photo ${item?.id ?: "none"} — $reason " +
                "(decision=$decision, mode=${settings.mode.id}, " +
                "level=${danmakuLevel.id}, screensaver=$screenSaver, slideshow=$slideshow)",
        )
    }

    /**
     * Registers a photo ahead of it being looked at, like the image prefetcher does.
     *
     * Best effort and silent: pre-registering must never be the reason the photo the user
     * actually opened is late (docs/tuping-design.md §9.2). Only ever called by a show whose
     * own 图评 switch is on — that is the one mode in which a photo nobody pressed anything
     * on is allowed onto the server (§17.28).
     */
    fun preRegister(candidates: List<ReviewCandidate>, source: String) {
        if (candidates.isEmpty()) return
        scope.launch {
            if (!ensureDevice()) return@launch
            for (candidate in candidates) {
                val key = identityKey(candidate.localId)
                if (identities.containsKey(key)) continue
                runCatching {
                    // Through the same repair as everything else, silently: pre-registration
                    // is the *first* call a freshly opened photo makes, so it is usually the
                    // one that discovers a refused token.
                    withDeviceRetry { client -> client.registerPhoto(candidate, source) }
                        .getOrNull()?.let { identity ->
                            identities[key] = identity
                            // The QR PNG is what the TV actually needs in hand, so it is
                            // fetched here rather than when the photo appears — and
                            // refreshed, because this answer is the server's current word
                            // on what that code encodes (see [fetchQr]).
                            fetchQr(identity.code, refresh = true)
                        }
                }
                // One at a time, and slowly: pre-registration competes with the photos
                // themselves for the same link.
                kotlinx.coroutines.delay(PRE_REGISTER_GAP_MS)
            }
        }
    }

    /**
     * Re-reads the comments on the current photo and subscribes to new ones.
     *
     * Called when the viewer settles on a photo: a WebSocket that follows every flick of
     * the remote would be a connection per keystroke.
     */
    fun startLive() {
        val ready = _view.value as? ReviewView.Ready ?: return
        refreshComments(ready.identity)
        subscribe(ready.identity)
    }

    /** Stops the socket; the comments already fetched stay in the cache. */
    fun stopLive() = closeSocket()

    /**
     * Fetches the comments this television does not have yet, **without** opening a socket.
     *
     * The half of [startLive] that a screensaver needs, and the reason it is separate: the
     * socket is a subscription to *new* comments, which is worth a connection while somebody
     * is looking at a photo — the design keeps it closed through every flick of the remote for
     * exactly that reason. A 屏保相册 has nobody to notify; a socket per photo would be a
     * connection opened and closed every eight seconds, all night, to deliver nothing. What it
     * does need is the *history*, because 弹幕 is the whole point of the layer: without this
     * call the photo on screen renders and every comment its family left on it stays invisible.
     *
     * Fire and forget on purpose — the merge publishes through `_view`, which is where the
     * viewer is already listening.
     */
    fun refreshCommentsForCurrentPhoto() {
        val ready = _view.value as? ReviewView.Ready ?: return
        refreshComments(ready.identity)
    }

    /**
     * The still's bytes, for the caller that has to build a candidate itself.
     *
     * Exposed because pre-registration has to happen where the list is — the viewer knows
     * which photos are coming next — while the cache read belongs here, next to the Coil
     * client it goes through.
     */
    suspend fun thumbnailBytesFor(url: String): ByteArray? = thumbnailBytes(url)

    /**
     * Forgets this device's registration, e.g. because the user pointed the app at a
     * different server.
     *
     * Wider than the repair's [forgetCredentials] on purpose: a token belongs to the server
     * that issued it, so an address change also invalidates every identity, every comment
     * and every QR PNG this repository holds — those are that *other* server's answers.
     */
    fun forgetDevice() {
        forgetCredentials()
        forgetServerAnswers()
        _status.value = ReviewStatus.NoServer
        _view.value = ReviewView.Hidden
    }

    /**
     * Reports the connection state for the settings page.
     *
     * A television has no console. "It is not working" and "it cannot reach
     * 192.168.31.14" are different sentences, and only the second one can be acted on.
     *
     * Two questions, not one: whether the server answers at all ([ReviewClient.health]) and
     * whether it accepts this device ([ReviewClient.deviceAccepted]). The first alone is
     * what made this row say 已连接 over a 图评 layer that could not draw a single thing.
     */
    suspend fun checkStatus() {
        val url = settings.serverUrl
        Log.i(TAG, "图评: checking status (mode=${settings.mode.id}, url=${if (url.isBlank()) "<unset>" else url})")

        // The token is obtained before the client is built, for the reason spelled out on
        // `register`: a client carries the token it was constructed with, and a freshly
        // issued one would otherwise not be in the client that uses it.
        if (!ensureDevice()) {
            Log.i(TAG, "图评: could not obtain a device token from $url")
            return
        }
        val built = client()
        if (built == null) {
            // `client()` has already set the specific reason — NoServer for a blank or
            // unusable address. Overwriting it with `Off` here is what made this row read
            // "关" and say nothing else: a status nobody can act on is worse than no row.
            Log.i(TAG, "图评: nothing to check — the address is unset or unusable")
            return
        }
        built.health().fold(
            onSuccess = {
                if (!deviceIsAccepted(built)) return@fold
                Log.i(TAG, "图评: ${built.server} answered and accepts this device")
                _status.value = ReviewStatus.Online(built.server, System.currentTimeMillis())
            },
            onFailure = { error ->
                Log.i(TAG, "图评: ${built.server} did not answer the health check: ${error.message}")
                report(built.server, error)
            },
        )
    }

    // ------------------------------------------------------------------ work

    /**
     * Uploads the canonical thumbnail and turns the answer into a view.
     *
     * Reached from two places only, and both of them are somebody asking for this picture to
     * be commentable: [armPhoto] (the viewer's 图评 row) and the show whose own 图评 switch is
     * on. Nothing else in the app calls it, which is what "no photo reaches the server until
     * a person asks" means in code (§17.28).
     *
     * [qrNeeded] is whether the code's PNG is worth fetching: a screensaver never draws one.
     */
    private suspend fun register(item: MediaItem, source: String, localId: String, qrNeeded: Boolean) {
        // `ensureDevice` first, and the client built *after* it — not before.
        //
        // The client snapshots the device token when it is constructed, so a client built
        // first carries whatever token existed at that moment. On the very first photo after
        // a fresh install that token is empty, and `ensureDevice` then mints one — into the
        // settings and into the *next* client. The upload went out with the old, empty
        // token and came back `unauthorized / 缺少设备令牌`, while the server log showed a
        // device registered successfully a moment earlier. The failure looked like the
        // server rejecting a device it had just accepted.
        if (!ensureDevice()) {
            _view.value = ReviewView.Hidden
            return
        }
        val built = client() ?: run {
            _view.value = ReviewView.Hidden
            return
        }
        val server = built.server

        val candidate = buildCandidate(item, localId) ?: run {
            Log.i(TAG, "no thumbnail available for ${item.id}; leaving 图评 hidden")
            if (currentLocalId == localId) _view.value = ReviewView.Hidden
            return
        }

        // Through [withDeviceRetry] rather than straight at the client this method built:
        // the first photo after a server is reinstalled is exactly the call that finds out
        // its token is no longer known, and it has to be the one that repairs it.
        withDeviceRetry { client -> client.registerPhoto(candidate, source) }.fold(
            onSuccess = { identity ->
                identities[identityKey(localId)] = identity
                // Refreshed rather than taken from the cache: this is a fresh answer from
                // the server about *this* code, and the picture it hands over is the only
                // thing that knows which address the code currently opens (see [fetchQr]).
                val qr = if (qrNeeded) fetchQr(identity.code, refresh = true) else qrFileFor(identity.code)
                _status.value = ReviewStatus.Online(server, System.currentTimeMillis())
                // The user may have moved on while this was in flight. Publishing then
                // would put this photo's code over a different one — and [publishReady] is
                // what checks that, for this path and the lookup's alike.
                if (currentLocalId == localId) {
                    publishReady(identity, qr)
                    // No socket from here. The viewer opens one when the identity that just
                    // arrived is the photo it is actually showing (see its effect on
                    // `reviewReady`), and doing it here as well is how two subscriptions for
                    // one photo happened: only the last was remembered, so the other one was
                    // never closed.
                }
            },
            onFailure = { error ->
                report(server, error)
                if (currentLocalId == localId) _view.value = ReviewView.Hidden
            },
        )
    }

    /**
     * Builds the candidate to upload: the canonical thumbnail, plus the original's
     * sha256 when the bytes are already on disk.
     *
     * The original is never fetched for this. `item.stillUrl(original = true)` is only
     * read when the caller has already arranged for it to be there — which is why this
     * takes what it is given rather than reaching for the network (docs/tuping-design.md §3.5).
     */
    private suspend fun buildCandidate(item: MediaItem, localId: String): ReviewCandidate? {
        val url = item.thumbnailUrl ?: return null
        val bytes = thumbnailBytes(url) ?: return null
        val canonical = PhotoIdentity.canonicalBytes(bytes) ?: return null
        return ReviewCandidate(
            item = item,
            localId = localId,
            thumb = canonical,
            sha256Original = null,
        )
    }

    /**
     * The still's bytes, read back out of the cache Coil has already filled.
     *
     * The photo on screen has just been drawn from Coil, so its bytes are on disk:
     * asking for them again over the network would be the "download the original to
     * hash it" mistake in miniature. `DiskCache.openSnapshot` is what reads that entry
     * back, and it is the *only* way to do it — `coil3.Image` exposes size, width,
     * height, a shareable flag and draw, and nothing else. The first version of this
     * reached for a `getFile()` on the image by reflection, on the assumption that a
     * decoded image knows where it came from; it does not, the lookup returned null, and
     * 图评 then hid itself for every photo with the log line "no thumbnail available".
     *
     * Fetching through the loader is the fallback for a photo whose bytes are genuinely
     * gone from the cache. It goes through the same `ImageLoader` — and therefore the same
     * `AccessToken`-bearing client — as every other image, which is why the cache is
     * checked first: this path does hit the network.
     *
     * A failure here is answered with null, and the caller hides 图评 for that one photo.
     * Registration is an ornament; it must never be the reason a photo is late.
     */
    private suspend fun thumbnailBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        cachedBytes(url)?.let { return@withContext it }
        fetchedBytes(url)
    }

    /** The cached copy, or null when the entry is not there. */
    private fun cachedBytes(url: String): ByteArray? = runCatching {
        val cache = coil3.SingletonImageLoader.get(appContext).diskCache ?: return null
        val snapshot = cache.openSnapshot(url) ?: return null
        snapshot.use { bytes -> bytes.data.toFile().readBytes().takeIf { it.isNotEmpty() } }
    }.getOrNull()

    /**
     * The fallback: ask Coil for the image, which fetches it if the cache missed.
     *
     * `Size.ORIGINAL` on purpose. Coil keys its disk cache on the URL and the size, so a
     * request for a specific size looks like a *different* image and sends the whole file
     * over the wire again.
     */
    private suspend fun fetchedBytes(url: String): ByteArray? = runCatching {
        val loader = coil3.SingletonImageLoader.get(appContext)
        val request = coil3.request.ImageRequest.Builder(appContext)
            .data(url)
            .size(coil3.size.Size.ORIGINAL)
            .diskCachePolicy(coil3.request.CachePolicy.ENABLED)
            .build()

        // The decoded image is deliberately discarded: all this needs is the side effect
        // of the loader having written the response to the disk cache, which it does
        // before decoding.
        loader.execute(request)
        cachedBytes(url)
    }.getOrNull()

    /**
     * Fetches a QR PNG once and writes it where Coil can load it as a file.
     *
     * [refresh] is the difference between "the server just answered for this photo" and
     * "show the photo again". The file is named after the code, and a code's address can
     * now change — `POST /v1/admin/codes/rebind` rewrites what an existing code opens, for
     * the deployment that has moved to a new tunnel address (docs/tuping-design.md §17.27)
     * — so the cached file is only trusted between registrations. After a registration the
     * picture is fetched again, which is a few kilobytes and the only moment the answer can
     * have changed; the code itself does not have to move for the address to be fixed, and
     * that is exactly why trusting the file across a registration would keep a dead address
     * on screen for the life of the install.
     */
    private suspend fun fetchQr(code: String, refresh: Boolean = false): File? {
        val file = File(qrDir, "$code.png")
        if (!refresh && file.isFile && file.length() > 0) return file
        val built = client() ?: return null
        return built.qrPng(code).fold(
            onSuccess = { bytes ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        qrDir.mkdirs()
                        // Written to a temporary name and renamed, so a reader never sees
                        // half a PNG.
                        val tmp = File(qrDir, "$code.tmp")
                        tmp.writeBytes(bytes)
                        if (!tmp.renameTo(file)) {
                            file.delete()
                            tmp.renameTo(file)
                        }
                        file
                    }.getOrNull()
                }
            },
            onFailure = { error ->
                report(built.server, error)
                null
            },
        )
    }

    private fun refreshComments(identity: ReviewIdentity) {
        val built = client() ?: return
        val server = built.server
        val known = commentCache.getOrPut(identity.imageId) { mutableListOf() }
        val since = known.maxOfOrNull { it.createdAt } ?: 0L

        scope.launch {
            withDeviceRetry { client -> client.comments(identity.imageId, since = since, limit = HISTORY_LIMIT) }
                .fold(
                    onSuccess = { fresh ->
                        mergeComments(identity.imageId, fresh)
                        // Re-read the count from the server's answer rather than incrementing
                        // a local number: another album may have registered the same photo,
                        // and its comments are ours too.
                        publishComments(identity)
                    },
                    onFailure = { error ->
                        report(server, error)
                        if (isUnknownPhoto(error)) healUnknownPhoto(identity)
                    },
                )
        }
    }

    /**
     * A photo this television holds a code for, and the server does not hold at all.
     *
     * The same conclusion as a replaced server, reached the other way round: the device is
     * still accepted, and this one picture is not there. Its identity is dropped so the code
     * cannot be shown again, and if it is the picture on screen it is registered afresh —
     * which is the whole repair, because the next answer is a code that resolves.
     *
     * Once per photo, so a server that answers `not_found` for a photo it has just been
     * handed cannot make this end register in a loop.
     */
    private fun healUnknownPhoto(identity: ReviewIdentity) {
        if (!healedPhotos.add(identity.localId)) return
        Log.i(TAG, "图评: the server does not hold ${identity.imageId}; registering it again")
        identities.remove(identityKey(identity.localId))
        commentCache.remove(identity.imageId)
        if (currentLocalId == identity.localId) reRegisterCurrentPhoto()
    }

    private fun subscribe(identity: ReviewIdentity) {
        val built = client() ?: return
        // Belt and braces around the socket. Nothing 图评 does may take the viewer down
        // with it, and this call site is where that promise was first broken: an
        // IllegalArgumentException from building the URL escaped into the registration
        // coroutine and killed the process. `live` no longer throws, and this makes the
        // guarantee hold even if it starts to again.
        socket = runCatching {
            built.live(
                imageId = identity.imageId,
                onComment = { comment ->
                    mergeComments(identity.imageId, listOf(comment))
                    publishComments(identity)
                },
                onClosed = { reason ->
                    // Reported, not retried: a layer that reconnects by itself keeps talking
                    // to a server that is switched off. The viewer's next photo re-subscribes.
                    if (reason != null) Log.i(TAG, "live socket closed: $reason")
                },
            )
        }.getOrElse { error ->
            Log.w(TAG, "could not open the live socket; 图评 continues without danmaku", error)
            null
        }
    }

    private fun closeSocket() {
        socket?.close(1000, "photo changed")
        socket = null
    }

    /**
     * One answer folded into the cache for a photo.
     *
     * The rule itself is [mergeCommentStates]; this is only the map and the lock. The
     * history call and the live socket both come through here, so a comment is treated the
     * same whichever door it arrived by — which is what lets a delete that only ever
     * happened on the phone page reach a television already showing the photo.
     */
    private fun mergeComments(imageId: String, fresh: List<ReviewComment>) {
        if (fresh.isEmpty()) return
        val known = commentCache.getOrPut(imageId) { mutableListOf() }
        synchronized(known) {
            mergeCommentStates(known, fresh, COMMENT_CACHE_LIMIT)
        }
    }

    private fun publishComments(identity: ReviewIdentity) {
        val ready = _view.value as? ReviewView.Ready ?: return
        if (ready.identity.imageId != identity.imageId) return
        val comments = commentCache[identity.imageId]?.toList().orEmpty()
        val visible = comments.count { it.status == DanmakuFeed.VISIBLE_STATUS }
        _view.value = ready.copy(
            comments = comments,
            // The count the picture reports is the server's, so a merged thread counts
            // comments written before the merge (docs/tuping-design.md §3.4).
            commentCount = maxOf(ready.commentCount, visible),
        )
    }

    private fun report(server: String, error: Throwable) {
        val reason = when (val failure = reviewErrorOf(error)) {
            is ReviewError.Api -> failure.message
            is ReviewError.Unreachable -> failure.reason
            is ReviewError.BadThumbnail -> failure.reason
            null -> error.message ?: error.javaClass.simpleName
        }
        Log.i(TAG, "图评: $reason ($server)")
        _status.value = ReviewStatus.Failed(server, reason, System.currentTimeMillis())
    }

    private fun identityKey(localId: String): String = localId

    private fun qrFileFor(code: String): File? =
        File(qrDir, "$code.png").takeIf { it.isFile && it.length() > 0 }

    /** A ULID-shaped device id: 26 Crockford base32 characters, time first. */
    private fun randomDeviceId(): String {
        val random = java.security.SecureRandom()
        val out = StringBuilder(26)
        // ULIDs sort by time, which is what makes the server's log readable.
        var time = System.currentTimeMillis()
        val timeChars = CharArray(10)
        for (i in 9 downTo 0) {
            timeChars[i] = CROCKFORD[(time and 31L).toInt()]
            time = time ushr 5
        }
        out.append(timeChars)
        repeat(16) { out.append(CROCKFORD[random.nextInt(32)]) }
        return out.toString()
    }

    companion object {
        private const val TAG = "ReviewRepository"
        private const val DEVICE_NAME = "FN Photo TV"

        /** How many comments to hold per photo. */
        private const val COMMENT_CACHE_LIMIT = 200

        /** How many to ask for when entering a photo. */
        private const val HISTORY_LIMIT = 50

        /** The gap between two pre-registrations. */
        private const val PRE_REGISTER_GAP_MS = 250L

        private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    }
}
