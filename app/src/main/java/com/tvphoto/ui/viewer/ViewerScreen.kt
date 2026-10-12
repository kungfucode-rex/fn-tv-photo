package com.tvphoto.ui.viewer

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.tvphoto.R
import com.tvphoto.data.ImagePrefetcher
import com.tvphoto.data.SettingsStore
import com.tvphoto.data.review.DanmakuLevel
import com.tvphoto.data.review.PhotoIdentity
import com.tvphoto.data.review.ReviewCandidate
import com.tvphoto.data.review.ReviewComment
import com.tvphoto.data.review.ReviewStatus
import com.tvphoto.data.review.ReviewView
import com.tvphoto.data.review.StayExtension
import com.tvphoto.ui.music.MusicRow
import com.tvphoto.ui.music.firstTickedRow
import com.tvphoto.ui.music.musicRows
import com.tvphoto.ui.music.nextSelectableRow
import com.tvphoto.domain.needsNextPage
import com.tvphoto.domain.nextSlideshowItem
import com.tvphoto.domain.walkPagingPointer
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.Screen
import com.tvphoto.ui.SlideshowDelta
import com.tvphoto.ui.components.PrefetchAhead
import com.tvphoto.ui.components.Spinner
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.formatTakenAt
import com.tvphoto.ui.components.mediaSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

@Composable
fun ViewerScreen(
    viewModel: MainViewModel,
    screen: Screen.Viewer,
    /**
     * True when this is the 屏保相册 playing itself rather than a viewer somebody opened.
     *
     * Two differences, and no more — the two image layers, the paging queue, the prefetch
     * window, the interval, the order and the **弹幕** are the same screen they always were,
     * which is the point of drawing a viewer rather than writing a second slideshow:
     *
     *  - the show starts by itself, because nobody is there to press 幻灯片;
     *  - the information band, the corner controls and the QR code are never drawn, because a
     *    screensaver with a filename or a code across it is a menu, not a picture. The code in
     *    particular is addressed to somebody standing in front of the television with a phone,
     *    and a screensaver is on because nobody is in the room.
     *
     * It is still the *viewer*'s Back that leaves it, and the root's: any key at all ends the
     * screensaver, which is why nothing here handles that.
     */
    screenSaver: Boolean = false,
) {
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val items = gallery.items.valueOrNull.orEmpty()
    val slideshowSeconds by viewModel.slideshowSeconds.collectAsStateWithLifecycle()
    val slideshowShuffled by viewModel.slideshowShuffled.collectAsStateWithLifecycle()
    val previewOriginal by viewModel.previewOriginal.collectAsStateWithLifecycle()
    val selectedMusic by viewModel.slideshowMusic.collectAsStateWithLifecycle()

    // The rows the track list draws: the bundled four, then whatever a phone uploaded or was
    // found on this device, then the way in to the music screen. Built here rather than
    // remembered against the library, because the library is already the remembered thing —
    // it changes when an upload lands, and that is exactly when this list has to change too.
    val musicLibrary by viewModel.musicLibrary.collectAsStateWithLifecycle()
    val trackRows = musicRows(musicLibrary.entries)
    // For the party-mode notice, which is formatted with counts that only the polling
    // loop knows at the time.
    val context = LocalContext.current

    if (items.isEmpty()) {
        StatusMessage(stringResource(R.string.empty))
        return
    }

    val startIndex = screen.index.coerceIn(0, items.lastIndex)

    /** Where each photo id sits in the list as it stands right now. */
    val indexById = remember(items) { items.withIndex().associate { (i, item) -> item.id to i } }

    // ---------------------------------------------------------- the two layers
    // Two image layers are kept composed at all times and advancing a photo is a
    // change of which one is opaque, never a change of what either one holds.
    //
    // That is the whole point. Coil drops the image it is showing the moment its
    // request changes, so switching the model to the next photo blanked the screen
    // until that photo had been downloaded and decoded — the black flash between two
    // pictures. Here the photo being paged to is loaded by the layer *behind* the
    // visible one and only comes forward once its pixels are actually there, so what
    // the user sees changes from one finished photo to another finished photo.
    //
    // A layer's state is its URL, not its id. The URL is what names the pixels: two
    // entries can share one, an entry can have none at all, and the preview setting can
    // change which one an entry asks for without the id moving.
    var layerIds by remember { mutableStateOf<List<Long?>>(listOf(items[startIndex].id, null)) }
    var frontLayer by remember { mutableIntStateOf(0) }

    var slideshowRunning by remember { mutableStateOf(screenSaver) }

    /**
     * Whether the still the viewer asks for *now* is the original file.
     *
     * One answer for the queue, the prefetch window and the layer the viewer opens on:
     * see `PreviewOriginal` for what the setting means and why the slideshow is the
     * exception by default.
     */
    val wantsOriginal = previewOriginal.wantsOriginal(slideshowRunning)

    /**
     * How long a step waits for its original before the thumbnail goes up on it.
     *
     * Follows the show's own interval, because that is the wait the step is spending
     * anyway: a show set to 30 seconds can afford to be patient, one set to 3 cannot. The
     * floor keeps a brisk show from flashing a thumbnail that was a moment away; the
     * ceiling keeps a slow original from parking the show on the previous photo, which is
     * the freeze this mechanism exists to prevent. Past the ceiling nothing is thrown away:
     * the download carries on behind the thumbnail and takes the screen when it lands.
     */
    val stepPatienceMs = (slideshowSeconds * 1000L)
        .coerceIn(STEP_PATIENCE_MIN_MS, STEP_PATIENCE_MAX_MS)

    /**
     * The entry whose original was given up on, if any.
     *
     * A step that asked for the original and did not get it inside its patience window is
     * shown at the thumbnail tier instead — see the effect that decides that below — while
     * the original it already started stays on its way. Until that lands this is what
     * [stillUrlOf] answers for the entry; it is cleared when the original arrives, or when
     * the entry is stepped onto again.
     */
    var downgraded by remember { mutableStateOf<Long?>(null) }

    /**
     * Which entries' originals are already in the image cache, by id.
     *
     * The answer decides whether a preview draws the original even while the setting would
     * have drawn the thumbnail: bytes already on disk cost no wait, and the wait is the only
     * reason the setting prefers the thumbnail while paging by hand
     * (`PreviewOriginal.wantsOriginal`).
     *
     * Filled off the main thread, and only for the entries that can matter right now — the
     * photo on screen plus the few after it — so a page turn usually finds the answer
     * already waiting rather than swapping tiers a moment later.
     */
    val cachedOriginals = remember { mutableStateMapOf<Long, Boolean>() }

    /** The still this viewer would ask for the entry with [id] under the current setting. */
    fun stillUrlOf(id: Long?): String? {
        val item = id?.let { indexById[it] }?.let(items::getOrNull) ?: return null
        val cached = id != null && cachedOriginals[id] == true
        // The cached original is *offered* to the setting rather than simply used: 否 means 否,
        // and the setting is the only thing that can turn the offer down
        // (`PreviewOriginal.wantsOriginal`).
        val original = previewOriginal.wantsOriginal(slideshowRunning, cached) && id != downgraded
        return item.stillUrl(original = original)
    }

    // What each layer has been asked to draw, and what it has finished decoding. A layer
    // whose URL is null has nothing to draw at all.
    var layerUrls by remember {
        mutableStateOf<List<String?>>(listOf(stillUrlOf(items[startIndex].id), null))
    }
    var loadedUrls by remember { mutableStateOf<List<String?>>(listOf(null, null)) }

    // Files the server could not produce, by URL. Only the loading indicator below
    // cares: a photo that failed is not a photo on its way, and a spinner left running
    // on it would be a worse answer than the black window it replaced.
    var failedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }

    // --------------------------------------------------------- the paging queue
    // How far the pointer has been walked, as the stretch of list between the photo on
    // screen and where it stopped. Only the end of it is fetched and only the end of it is
    // gone to — see [walkPagingPointer] — so the list is neither re-ordered by a press nor
    // walked through one photo at a time, and the corner arrows report the distance.
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    var pendingRight by remember { mutableStateOf(true) }

    // Which of those the *show* asked for, as opposed to the user. Only the user's own
    // presses are reported in the corner: a show fetching its next picture in the
    // background is not something to announce, and "one arrow per step you walked"
    // would be a lie if some of the arrows were the show's.
    var slideshowQueued by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var videoPlaying by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    // Bumped when a queued photo is given up rather than shown.
    //
    // The show's interval is keyed on the photo on screen, so an arrival is what starts
    // the next one. A step that is taken back never arrives, so without this the timer
    // would have nothing left to run and a show somebody pressed left in the middle of
    // would sit there for good.
    var slideshowResume by remember { mutableIntStateOf(0) }

    // Where the photo on screen last sat. Used when that photo is deleted out from under
    // the viewer: the photo that slid into its place is the least jarring replacement,
    // and falling back to the top of the list would jump the show back to the newest.
    var positionHint by remember { mutableIntStateOf(startIndex) }

    // The corner opens a row above itself: 幻灯片 opens the slideshow's settings, 图评 opens
    // its display switches. `mainFocus` says which button the D-pad is on; `settingFocus`
    // is the control within whichever row is open.
    //
    // One nullable `cornerMenu` rather than a flag per row: the two rows are mutually
    // exclusive, and two booleans would allow a state — both open — that the drawing has no
    // answer for.
    var cornerMenu by remember { mutableStateOf<CornerMenu?>(null) }
    var mainFocus by remember { mutableIntStateOf(MAIN_SLIDESHOW) }
    var settingFocus by remember { mutableIntStateOf(SETTING_PLAY) }
    var reviewFocus by remember { mutableIntStateOf(REVIEW_ROW_QR) }

    /**
     * The photo the user asked to see at full size, if any.
     *
     * Held as the *id* rather than a flag: the request belongs to the photo that was on
     * screen when it was made, so paging on makes it stale and it is dropped. It is not the
     * setting — the setting says what every preview should be, and this says "this one, now".
     */
    var originalRequest by remember { mutableStateOf<Long?>(null) }

    // The 背景音乐 setting opens a second level: the list of tracks, above the row. Only
    // one of the two levels takes the D-pad at a time, so the cursor into the list is
    // kept here beside the control it belongs to.
    var musicListOpen by remember { mutableStateOf(false) }
    var musicCursor by remember { mutableIntStateOf(0) }

    // The persistent shortcut bar was removed by request, so toggling the slideshow
    // would otherwise give no feedback at all. This notice shows for a moment and
    // then gets out of the way.
    var noticeText by remember { mutableStateOf<String?>(null) }
    var noticeSeq by remember { mutableIntStateOf(0) }

    LaunchedEffect(noticeSeq) {
        if (noticeSeq == 0) return@LaunchedEffect
        delay(NOTICE_MILLIS)
        noticeText = null
    }

    fun notify(text: String) {
        noticeText = text
        noticeSeq++
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    val currentId = layerIds[frontLayer] ?: items.first().id
    val currentIndex = indexById[currentId] ?: positionHint.coerceIn(0, items.lastIndex)
    val current = items.getOrNull(currentIndex) ?: items.first()

    /**
     * The list the viewer holds is only what the grid had loaded — one page of sixty, and
     * until this effect existed nothing asked for the rest. A show walked those sixty and
     * began again at the first, with the 157 remaining photos of that album never even
     * requested: "播放五六十张之后，又从第一张开始播放。后边其实还有很多照片。播放的图片后边
     * 的那些的缩略图还没有加载出来".
     *
     * The grid asks for its next page when its last rows come into view; a viewer has no
     * scroll position to hang that on, so this is the same rule with the two things a viewer
     * does know: where the photo on screen sits in the list, and whether a show is running
     * (see [needsNextPage]). While a show runs the effect is re-keyed by every page that
     * lands, so the pages arrive one after another until the album ends — the same requests,
     * in the same order, that scrolling the grid that far would have produced.
     */
    LaunchedEffect(currentId, currentIndex, items.size, slideshowRunning) {
        if (needsNextPage(currentIndex, items.size, slideshowRunning)) viewModel.loadMore()
    }

    /**
     * Whether what is on screen is the photo's own file rather than a thumbnail of it.
     *
     * Asked of the layer's URL, not of the setting: a photo loaded before the slideshow
     * started — or before the setting was changed — is still the thumbnail, and the details
     * band is the one place that has to tell the truth about it.
     */
    val showingOriginal = current.isOriginalStill(layerUrls[frontLayer])

    // The 原图 button exists for a thumbnail that has an original behind it, and goes away
    // the moment the original is what is on screen — which is also the moment there is
    // nothing left for it to do.
    val canLoadOriginal = current.hasDistinctOriginal && !showingOriginal
    val originalLoading = canLoadOriginal && originalRequest == currentId
    val originalLabel = stringResource(R.string.viewer_quality_original)
    val thumbnailLabel = stringResource(R.string.viewer_quality_thumbnail)

    // 图评 has nowhere to go when no address is usable, and the button says so rather than
    // opening a menu for a layer that cannot talk to anything. Two states are deliberately
    // *not* disabled:
    //
    //  - 关, which is a deliberate setting somebody made, and the way back out of it is this
    //    button;
    //  - a failed call, which used to disable it as well — and that is the bug this comment
    //    exists to keep from coming back. A server that refuses this television's device
    //    token (reinstalled, restored from an old backup, or reached at a new address) makes
    //    every upload answer `unauthorized` while `/healthz` keeps answering, so the settings
    //    row reads 已连接 and this button greys out — and with it the only way to reach
    //    图评's own switches. Reported as "the switches are all grey and I cannot open them".
    //
    // The switches decide what is drawn; they are worth reaching even while the server is
    // quiet, and the status row is what explains why nothing is drawn. Opening the menu says
    // that reason out loud, since a television has no console.
    //
    // The status is refreshed on entering the viewer, because nothing else would have asked
    // since the settings screen was last opened: a server switched off in between would
    // otherwise leave the button lit and its menu openable on a stale 已连接.
    val reviewStatus by viewModel.reviewStatus.collectAsStateWithLifecycle()
    val reviewDisabled = reviewStatus is ReviewStatus.NoServer
    // Not asked for while the screensaver is playing: the status is only ever drawn on the
    // 图评 row, and a screensaver cannot open that row — so the request would be a call to
    // somebody's server, in the middle of the night, for a screen that draws nothing of it.
    LaunchedEffect(Unit) { if (!screenSaver) viewModel.refreshReviewStatus() }

    // Where the D-pad is in the corner. None of them while the band is closed or a row has
    // the D-pad.
    //
    // `available` rather than the original behaviour of only 原图: with three buttons, a
    // narrow "canLoadOriginal" test left 图评 unreachable whenever an original was on
    // screen, which is most of the time in the mode that shows the code.
    val available = cornerButtons(canLoadOriginal, reviewDisabled)
    val onReviewButton = showInfo && cornerMenu == null && mainFocus == MAIN_REVIEW
    val onSlideshowButton = showInfo && cornerMenu == null && mainFocus == MAIN_SLIDESHOW
    val onOriginalButton = showInfo && cornerMenu == null && canLoadOriginal &&
        mainFocus == MAIN_ORIGINAL

    /**
     * True while the corner holds more than one button.
     *
     * Left and right then belong to that row rather than to the page turn, exactly as they
     * belong to a menu while one is open: buttons side by side are read as a row, and a row
     * is what left/right move in. Paging with the band open is a press of down first, which
     * closes it.
     */
    val cornerRow = showInfo && cornerMenu == null && available.size > 1

    /**
     * True while the picture on screen has not been decoded yet, which is the viewer
     * opening on it or the list changing underneath it. A page turn never sets it: the
     * photo being left stays up until the next one is ready.
     *
     * Asked of the URL the layer was given rather than of its id, because the URL is
     * what the layer is actually fetching: an entry whose still moves from the thumbnail
     * to the original under a preview setting is still working, and an entry the server
     * sent no still for is not.
     */
    fun frontIsLoading(): Boolean {
        val url = layerUrls[frontLayer] ?: return false
        return loadedUrls[frontLayer] != url && url !in failedUrls
    }

    // Immersive viewing: the viewer hides the system bars and restores them on exit.
    ImmersiveMode()

    // Keeps the cache ahead of the photo on screen, so paging right stays instant.
    // The anchor is handed over as state so that moving the window does not recompose
    // the viewer — the whole screen would otherwise be rebuilt on every page.
    val prefetchAnchor = remember { mutableIntStateOf(startIndex) }
    LaunchedEffect(currentIndex) { prefetchAnchor.value = currentIndex }

    /**
     * True while the viewer is waiting for a picture of its own: the one on screen at
     * open, the one a page turn — or a slideshow step — has asked for, and the original a
     * press of 原图 put on its way. All of them travel over the client the prefetch is
     * using, and the photo the user is looking at — or has just asked for — has to arrive
     * first.
     */
    val waitingForItsOwn: () -> Boolean = {
        pending.isNotEmpty() || frontIsLoading() || originalRequest != null
    }

    PrefetchAhead(
        itemCount = items.size,
        anchor = prefetchAnchor,
        // Narrowed, not stopped, while the viewer waits for a picture of its own.
        //
        // Standing the window down entirely was the old behaviour, and on a shared album
        // over a tunnel it is what left a show on thumbnails for good: an original takes
        // longer to arrive than any interval worth watching, and a window cancelled at the
        // start of every step never completes one — only a complete body becomes a disk
        // cache entry, so each step began the same bytes again from nothing.
        //
        // One download at a time loses the race politely and still fills the cache between
        // steps; `ImagePrefetcher.prefetch` re-reads this between batches.
        narrowWhenHeld = true,
        hold = waitingForItsOwn,
        // Which tier is being warmed, and whether a show is running, are the two facts
        // that make the window's contents wrong rather than merely stale — a show draws
        // its next photo from the whole list instead of from the position the window is
        // anchored at — so either one re-anchors it.
        restartKey = wantsOriginal to slideshowRunning,
        prefetch = { from, held ->
            viewModel.warmImageCache(
                items = items,
                fromIndex = from,
                original = wantsOriginal,
                limit = if (slideshowRunning) SHOW_WARM_LIMIT else ImagePrefetcher.DEFAULT_LIMIT,
                held = held,
            )
        },
    )

    /**
     * Starts the show's interval again when a step left the queue without a photo
     * arriving to start it — see [slideshowResume].
     */
    fun resumeSlideshowIfIdle() {
        if (slideshowRunning && pending.isEmpty()) slideshowResume++
    }

    /**
     * Walks the paging pointer one step in [delta]'s direction — see [walkPagingPointer],
     * which is where the rule about what the queue holds lives.
     *
     * What it leaves behind is the bookkeeping a pure function cannot do: which way the
     * pointer is walking, which queued step was the show's rather than the user's, and
     * the show's interval when a step is given up rather than taken.
     */
    fun queueStep(delta: Int) {
        val next = walkPagingPointer(items, currentId, pending, pendingRight, delta)
        if (next === pending) return
        if (pending.isEmpty()) {
            pendingRight = delta > 0
        } else if (next.size < pending.size) {
            // A step the *show* took is given up the same way the user's own is: the
            // user is holding the list now.
            slideshowQueued = slideshowQueued - pending.last()
        }
        pending = next
        resumeSlideshowIfIdle()
    }

    /**
     * Queues the picture the show moves to next — see [nextSlideshowItem], which is
     * where the rules about videos and duplicates live.
     */
    fun queueSlideshowStep() {
        // One picture on its way at a time. The viewer goes to the end of the queue, so a
        // second step queued behind one that is still downloading would drop the first:
        // on a slow link the show would quietly show every other photo.
        if (pending.isNotEmpty()) return
        val target = nextSlideshowItem(
            items = items,
            fromIndex = indexById[pending.lastOrNull() ?: currentId] ?: currentIndex,
            taken = pending.toSet() + currentId,
            shuffled = slideshowShuffled,
        ) ?: return
        pendingRight = true
        pending = pending + target.id
        slideshowQueued = slideshowQueued + target.id
    }

    /**
     * Gives up the whole queue and makes [id] — the photo the pointer was walked to — the
     * one on screen.
     *
     * The steps behind it are dropped rather than shown: the queue only records where the
     * pointer ended up, and three presses right mean "the third photo", not "these three
     * photos, one after another". Nothing is left waiting, so the corner arrows go with
     * it.
     *
     * Only ever called for a photo that has finished loading — or has failed, or has no
     * still at all, which are handled the same way so that one unreadable file cannot
     * stall the queue behind it.
     */
    fun advanceTo(id: Long) {
        if (pending.lastOrNull() != id) return
        pending = emptyList()
        slideshowQueued = emptySet()
        // The fallback was about *this* step. Whatever asks for this entry next starts from
        // the top tier again.
        downgraded = null
        frontLayer = 1 - frontLayer
    }

    /**
     * Advances to [id] the moment its *thumbnail* is worth showing, leaving the original it
     * is already downloading exactly where it is.
     *
     * The pointer moves and the queue empties as in [advanceTo], but no layer changes
     * places: the visible one has just been pointed at the thumbnail, and the hidden one
     * still holds the original request the step began. When that lands, the ordinary "the
     * original of the photo on screen" path brings it forward — which is why the caller
     * sets [originalRequest]. A slow original then costs a moment of softness rather than
     * the whole picture, and none of what has already been read is thrown away.
     */
    fun advanceOnThumbnail(id: Long) {
        if (pending.lastOrNull() != id) return
        pending = emptyList()
        slideshowQueued = emptySet()
        // Deliberately *not* cleared here, unlike [advanceTo]: until the original lands
        // this entry is on its thumbnail, and stillUrlOf has to agree with what the visible
        // layer is drawing — otherwise the next thing to ask for that entry would start a
        // second download of a file that is already on its way.
        downgraded = id
    }

    /**
     * Points one layer at [id]'s still, dropping whatever it was holding.
     *
     * [loadedUrls] is cleared with it. A layer that has been asked for something else no
     * longer holds the pixels it had, and leaving that claim behind is what used to let
     * the viewer bring a layer forward believing it was showing a photo it had already
     * been moved off — a spinner over the wrong picture, with nothing left to resolve it.
     */
    fun pointLayerAt(layer: Int, id: Long?, url: String?) {
        layerIds = layerIds.toMutableList().also { it[layer] = id }
        layerUrls = layerUrls.toMutableList().also { it[layer] = url }
        loadedUrls = loadedUrls.toMutableList().also { it[layer] = null }
        // A fresh request for a file deserves a fresh spinner.
        if (url != null) failedUrls = failedUrls - url
    }

    /**
     * Keeps the hidden layer pointed at the photo the queue ends on — or at the original of
     * the photo already on screen — and brings it forward the moment it is there.
     *
     * Keyed on the end of the queue rather than the whole queue, so a step taken back at
     * the other end — left after right — does not restart the download of a photo that is
     * still being waited for.
     */
    LaunchedEffect(
        pending.lastOrNull(),
        // The cached-original answer is part of what decides this layer's URL, and a state
        // map does not change identity when it is written to — so the *value* for the entry
        // being paged to is a key of its own. Without it, a step that began before the probe
        // answered would stay on the thumbnail it was pointed at.
        pending.lastOrNull()?.let { cachedOriginals[it] },
        originalRequest,
        frontLayer,
        items,
        layerIds,
        layerUrls,
        loadedUrls,
        failedUrls,
        wantsOriginal,
        downgraded,
    ) {
        val back = 1 - frontLayer
        val target = pending.lastOrNull()
        if (target != null) {
            val url = stillUrlOf(target)

            // No still at all: the server sent nothing for this entry. Waiting for pixels
            // that cannot arrive is how the viewer used to stop dead on one file, so the
            // step is taken at once — the layer is pointed at it so that the band and the
            // position still agree with what is on screen.
            if (url == null) {
                pointLayerAt(back, target, null)
                advanceTo(target)
                return@LaunchedEffect
            }

            val pointed = layerIds[back] == target && layerUrls[back] == url
            // Already decoded off-screen — the photo just paged away from, or one this
            // layer has held all along — or already refused. There is nothing to wait for
            // either way, and the queue must not sit here for a callback that will never
            // come.
            if (pointed && (loadedUrls[back] == url || url in failedUrls)) {
                advanceTo(target)
                return@LaunchedEffect
            }
            if (!pointed) pointLayerAt(back, target, url)
            return@LaunchedEffect
        }

        // Nothing queued: the back layer's remaining job is the original of the photo that
        // is already on screen, when the user asked for it at full size.
        val wanted = originalRequest ?: return@LaunchedEffect
        // A request is about the photo that was on screen when it was made; the effect
        // below drops a stale one rather than acting on it.
        if (layerIds[frontLayer] != wanted) return@LaunchedEffect
        val url = indexById[wanted]?.let(items::getOrNull)?.fullUrl ?: return@LaunchedEffect

        // Nothing to do: the original is already the thing being looked at (the button is
        // gone by then, but a request can outlive the moment it was made).
        if (layerUrls[frontLayer] == url) {
            originalRequest = null
            return@LaunchedEffect
        }

        val pointed = layerIds[back] == wanted && layerUrls[back] == url
        if (pointed && url in failedUrls) {
            originalRequest = null
            // The show's own fallback has already told the room that this original did not
            // arrive — "这张原图还没到，先看缩略图" — and saying it twice about the same
            // photo is noise rather than news. A request the *user* made still reports its
            // failure, because nothing else has.
            if (downgraded != wanted) notify(context.getString(R.string.viewer_original_failed))
            return@LaunchedEffect
        }
        if (pointed && loadedUrls[back] == url) {
            // Decoded and out of sight: bring that layer forward. It is the *same* photo, so
            // the position, the band and the queue stay exactly where they are — only the
            // layer that is opaque moves, which is what the two of them are for.
            originalRequest = null
            // The entry is at full size now: the fallback that put its thumbnail up is
            // spent, and the next thing to look at it should ask for the original again.
            downgraded = null
            frontLayer = back
            return@LaunchedEffect
        }
        if (!pointed) pointLayerAt(back, wanted, url)
    }

    // A step that asks for the original does not wait for it for ever — and does not throw
    // it away either.
    //
    // The queue is what the show's next photo rides on, and the prefetch window exists so
    // that this download is already done. When it is not — a cold multi-megabyte original
    // on a slow link, or a NAS that has stopped answering — the show used to sit on the
    // previous picture until the media client's own 60-second read timeout gave up. Because
    // the prefetcher also stood down while a step was in flight, the photos *after* it were
    // not warmed either, so one slow file read as a slideshow that had frozen and stopped
    // loading anything. Reported exactly that way: "前3张可以播放，第四张就卡住了… 感觉是
    // 后面的图片没加载完成，而且播放时后面的图片也不会自动加载".
    //
    // So the step gets [stepPatienceMs] — which follows the show's own interval — and is
    // then shown at the thumbnail tier, which the grid has usually already put on disk. The
    // photo appears, the show keeps its rhythm, and the screen says what happened rather
    // than leaving the room to guess — the same bargain the 图评 layer makes when the review
    // server does not answer.
    //
    // What it does *not* do any more is give up the download. This used to repoint the layer
    // that was loading — which cancels it, and throws away every byte read so far, since
    // only a complete body becomes a disk cache entry. On a shared album over a tunnel,
    // where an original takes longer than the interval, that turned into a show that ran on
    // thumbnails for as long as it was left playing: every step began the same file again
    // and none of them ever finished. The thumbnail goes up on the *visible* layer instead,
    // the loading layer keeps its request, and the original takes the screen when it lands
    // (see the effect above, which is the same path the 原图 button uses).
    LaunchedEffect(pending.lastOrNull(), wantsOriginal, stepPatienceMs) {
        val target = pending.lastOrNull() ?: return@LaunchedEffect
        if (!wantsOriginal) return@LaunchedEffect
        delay(stepPatienceMs)
        if (pending.lastOrNull() != target) return@LaunchedEffect

        val loading = 1 - frontLayer
        val asked = layerUrls[loading]
        val item = indexById[target]?.let(items::getOrNull) ?: return@LaunchedEffect
        val thumb = item.thumbnailUrl
        // Arrived, refused, or a NAS that answers with one URL for both tiers: there is
        // nothing smaller to fall back to.
        if (asked == null || loadedUrls[loading] == asked || asked in failedUrls) {
            return@LaunchedEffect
        }
        if (thumb == null || thumb == asked || thumb in failedUrls) return@LaunchedEffect

        pointLayerAt(frontLayer, target, thumb)
        advanceOnThumbnail(target)
        // The original is still on its way on the other layer; this is what tells the
        // viewer to bring it forward the moment it arrives.
        originalRequest = target
        notify(context.getString(R.string.viewer_step_slow))
    }

    // A request for the original belongs to the photo that was on screen when it was made:
    // paging on makes it somebody else's.
    LaunchedEffect(currentId) {
        if (originalRequest != null && originalRequest != currentId) originalRequest = null
    }

    // Which originals are already on disk, asked off the main thread for the photo on screen
    // and the few after it.
    //
    // This is what makes "preview the original when it is already cached" more than a hope: a
    // page turn is decided by [stillUrlOf], and a decision made before the disk has been
    // asked would be the thumbnail every time. Probing a few ahead is also why the common
    // case shows no tier swap at all.
    LaunchedEffect(currentId, pending.lastOrNull(), items) {
        val from = indexById[currentId] ?: return@LaunchedEffect
        // Indexed rather than `drop(..).take(..)`, which copies the rest of the list — an
        // album the show has walked to the end of is a long list, and this runs on every
        // step.
        val end = minOf(from + PROBE_AHEAD, items.size)
        val ids = (from until end).map { items[it].id }
        val answers = withContext(Dispatchers.IO) {
            ids.associateWith { id ->
                val item = indexById[id]?.let(items::getOrNull) ?: return@associateWith false
                // A clip has no still of its own, and a NAS that answers with one URL for
                // both tiers has nothing to prefer.
                if (item.isVideo) return@associateWith false
                val original = item.fullUrl ?: return@associateWith false
                original != item.thumbnailUrl && viewModel.isImageCached(original)
            }
        }
        cachedOriginals.putAll(answers)
    }

    // The photo on screen was pointed at before the disk was asked, so a cached original is
    // brought in through the same lever the 原图 button pulls: loaded on the hidden layer and
    // swapped in when it is there, which is why this costs no black frame.
    //
    // Only when the cache changes the answer — the setting itself asking for the original
    // means the layer is already loading it, and pressing on its behalf would restart that
    // request — and only where the preview tier would have agreed to the offer at all.
    LaunchedEffect(currentId, wantsOriginal, cachedOriginals[currentId]) {
        val cached = cachedOriginals[currentId] == true
        val upgrade = previewOriginal.wantsOriginal(slideshowRunning, cached) && !wantsOriginal
        if (!upgrade || !canLoadOriginal) return@LaunchedEffect
        originalRequest = currentId
    }

    // The 原图 button comes and goes with the photo on screen, and the D-pad follows it:
    // while there is an original to load, that is the one thing about this photo that
    // cannot be seen without pressing something.
    LaunchedEffect(canLoadOriginal) {
        mainFocus = cornerFocus(canLoadOriginal)
    }

    // Party mode. While the slideshow runs the list is re-read every so often, so
    // photos uploaded to the album meanwhile join the rotation without a restart —
    // the point being a big screen at a party where everyone is uploading to it. A
    // photo that has since been deleted leaves the rotation at the same time.
    // Keyed on the flag, so stopping the slideshow cancels the polling outright.
    LaunchedEffect(slideshowRunning) {
        if (!slideshowRunning) return@LaunchedEffect
        while (true) {
            delay(SLIDESHOW_REFRESH_MILLIS)
            val delta = runCatching { viewModel.refreshSlideshowItems() }
                .getOrDefault(SlideshowDelta.NONE)
            when {
                delta.added > 0 && delta.removed > 0 -> notify(
                    context.getString(R.string.viewer_list_changed, delta.added, delta.removed),
                )

                delta.added > 0 -> notify(context.getString(R.string.viewer_added, delta.added))
                delta.removed > 0 -> notify(context.getString(R.string.viewer_removed, delta.removed))
            }
        }
    }

    // A list that changed underneath the viewer has to be reconciled with what it is
    // showing: queued photos that are gone are dropped, and a photo that is gone from
    // the screen is replaced by whatever slid into its place. Without this the queue
    // could wait forever on a photo that no longer exists.
    LaunchedEffect(items, wantsOriginal) {
        val present = items.mapTo(HashSet()) { it.id }
        if (pending.any { it !in present }) {
            pending = pending.filter { it in present }
            slideshowQueued = slideshowQueued.filterTo(HashSet()) { it in present }
            // A queued photo the library no longer lists is a step that will never
            // arrive either, so the show needs its interval back here too.
            resumeSlideshowIfIdle()
        }

        val replaced = layerIds.map { it != null && it !in present }
        if (replaced.any { it }) {
            val fallback = items[positionHint.coerceIn(0, items.lastIndex)].id
            val fallbackUrl = stillUrlOf(fallback)
            layerIds = layerIds.mapIndexed { layer, id -> if (replaced[layer]) fallback else id }
            layerUrls =
                layerUrls.mapIndexed { layer, url -> if (replaced[layer]) fallbackUrl else url }
            loadedUrls = loadedUrls.mapIndexed { layer, url -> if (replaced[layer]) null else url }
        }
    }

    // Keyed on the id rather than the index on purpose. A frame after a deletion the
    // current id has no index at all, and reading one then would record the placeholder
    // the line above fell back to instead of where the photo really sat.
    LaunchedEffect(currentId) {
        indexById[currentId]?.let { positionHint = it }
    }

    // ------------------------------------------------------------------ 图评
    //
    // The QR code and the danmaku layer. Everything about it is arranged so that three
    // rules hold by construction rather than by discipline:
    //
    //  - it reads `current` and never writes anything the viewer's own state depends on,
    //    so a review server that is switched off cannot change what a photo looks like;
    //  - a failure leaves [ReviewView.Hidden] and the screen draws exactly what it drew
    //    before 图评 existed;
    //  - nothing here takes focus, so the remote belongs to the photo as it always did.
    //
    // These declarations sit above the slideshow timer because the timer is keyed on the
    // stay extension a danmaku arrival raises. Putting them further down would read as a
    // forward reference and, worse, would separate the reason the timer restarts from the
    // timer itself.
    val reviewView by viewModel.reviewView.collectAsStateWithLifecycle()
    val danmakuLevel by viewModel.danmakuLevel.collectAsStateWithLifecycle()
    val extendStayOnComment by viewModel.extendStayOnComment.collectAsStateWithLifecycle()

    // The two switches the 图评 button owns. Read here rather than passed in from the menu,
    // because they decide whether the two drawers below draw anything at all.
    val reviewShowQr by viewModel.reviewShowQr.collectAsStateWithLifecycle()
    val reviewShowDanmaku by viewModel.reviewShowDanmaku.collectAsStateWithLifecycle()
    val reviewSlideshowEnabled by viewModel.reviewSlideshowEnabled.collectAsStateWithLifecycle()

    // Which album the photo on screen came from, for the `source` field a registration
    // carries. It is the topmost non-viewer screen — the grid that was open when the viewer
    // was pushed — because that is the only thing that knows.
    //
    // Declared here, above the gate that is built from it, rather than next to the
    // registration that also reads it: the drawing below is built out of the gate, and the
    // gate cannot be written before the source it is compared against.
    val reviewSource = remember(screen) {
        when (viewModel.stack.value.lastOrNull { it !is Screen.Viewer }) {
            is Screen.AlbumPhotos -> "fnos-album"
            is Screen.PersonPhotos -> "fnos-person"
            is Screen.FolderBrowse -> "fnos-folder"
            else -> "fnos"
        }
    }

    /**
     * The review answer for the photo on screen — and only for that photo.
     *
     * A page turn moves `currentId` on the frame the button is pressed, but the repository
     * publishes the new photo's answer from a `LaunchedEffect`, one frame later. For that
     * frame the screen holds the *previous* photo's identity under the new photo's key, and
     * handing it straight to the layer is what put a line about the last picture across the
     * next one: the replay offered it inside that window, and a line the layer has started
     * crosses the screen for the whole of its seven seconds. Reported exactly that way — it
     * leaked on 翻页, and never on 打开, which is the difference between a state that is one
     * frame stale and one that was never set.
     *
     * The QR code is the same window one frame wide, and the same fix covers it: a code
     * drawn for a photo the guest is no longer looking at is a code that comments on the
     * wrong picture.
     *
     * So a mismatched identity counts as no answer at all. The layer is not composed, which
     * disposes its feed and everything crossing in it; the new photo's own answer arrives a
     * frame later and starts from an empty screen, which is the rule the layer already had
     * for the picture changing — it just could not see that it had.
     */
    val reviewLocalId = PhotoIdentity.localId(reviewSource, current)
    val reviewReady = (reviewView as? ReviewView.Ready)
        ?.takeIf { it.identity.localId == reviewLocalId }

    // A comment that has just arrived buys the photo a little more time — the design's
    // answer to "3-second interval, 10.7-second danmaku". Slowing the danmaku down instead
    // would make a 3-second line unreadable; holding the photo is small, visible and
    // explicable. The ceiling is what stops a busy photo from never advancing.
    var stayExtensionMs by remember(currentId) { mutableLongStateOf(0L) }
    var photoShownAt by remember(currentId) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(currentId) {
        stayExtensionMs = 0L
        photoShownAt = System.currentTimeMillis()
    }
    val onDanmaku: (ReviewComment) -> Unit = { comment ->
        if (extendStayOnComment && slideshowRunning) {
            val extra = StayExtension.extraMs(
                comment = comment,
                level = danmakuLevel,
                // The design's numbers are written for 1080p, and this is the one place the
                // layer needs a screen width before it has one.
                screenWidthPx = REVIEW_SCREEN_WIDTH_PX,
                shownMs = System.currentTimeMillis() - photoShownAt,
                plannedMs = slideshowSeconds * 1000L + stayExtensionMs,
            )
            if (extra > 0) {
                stayExtensionMs = (stayExtensionMs + extra).coerceAtMost(StayExtension.MAX_EXTRA_MS)
            }
        }
    }

    // The slideshow timer restarts whenever the thing on screen changes, so the interval
    // is always measured from when it appeared rather than from when playback started.
    // It is held while the settings row is open, so pictures do not advance under
    // someone who is busy choosing an interval.
    //
    // A clip does not hold it. The rotation is photos and the show walks past videos, so
    // the only way one is on screen mid-show is that somebody paged onto it by hand —
    // and then it gets one interval like anything else rather than stopping the show
    // until they page away. That also means a clip that never plays (a stream the NAS
    // refuses, say) cannot strand a party slideshow on a black frame.
    LaunchedEffect(
        slideshowRunning,
        currentIndex,
        slideshowSeconds,
        items.size,
        current.isVideo,
        slideshowShuffled,
        cornerMenu,
        slideshowResume,
        // A comment that arrived during this photo's stay restarts the wait, so "hold this
        // one until it has crossed" is measured from when the comment appeared.
        stayExtensionMs,
    ) {
        if (slideshowRunning && items.size > 1 && cornerMenu == null) {
            delay(slideshowSeconds * 1000L + stayExtensionMs)
            queueSlideshowStep()
        }
    }

    // Music does not follow the app into the background: a TV sent back to its home
    // screen should not keep playing to an empty room. Held as state rather than read
    // from the lifecycle inside the effect, which has to re-run when it changes.
    var onScreen by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { onScreen = false }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { onScreen = true }

    // Background music belongs to the show and to nothing else. It starts when the
    // slideshow does and stops when it does; in between it is *held* — not discarded —
    // while something else has the room's attention. Those are the settings row, which
    // holds the photo timer for exactly the same reason, and a video, which brings its
    // own sound. Leaving the viewer ends it outright, at the bottom of this screen.
    //
    // A screensaver never has it, which is the one place this screen's rules differ from the
    // viewer's: the 背景音乐 setting is about a show somebody is watching, and the screensaver
    // runs unattended in an empty room, usually at night. Starting a household's music library
    // on a loop at 2am, because a photo album happens to be set as the screensaver, is a
    // failure mode that would get the whole feature switched off.
    val musicWanted = slideshowRunning && selectedMusic.isNotEmpty() && !screenSaver
    LaunchedEffect(musicWanted, cornerMenu, current.isVideo, selectedMusic, onScreen) {
        when {
            !musicWanted -> viewModel.stopSlideshowMusic()
            !onScreen || cornerMenu != null || current.isVideo -> viewModel.pauseSlideshowMusic()
            else -> viewModel.playSlideshowMusic()
        }
    }

    // Videos always start playing when they become the visible item.
    LaunchedEffect(currentIndex) { videoPlaying = true }

    // Handing the photo over is what makes the layer follow the picture. Keyed on the id so
    // a late registration does not restart it, and on the source and the video flag because
    // those are the two things that decide what the layer is asked to do with it.
    //
    // `slideshowRunning` used to be in this key list, back when the mode made eligibility
    // depend on it. It is out now: the 图评 menu's switches govern the display, and a menu
    // row that only takes effect once a slideshow is started is what "二维码 开 but nothing
    // on screen" turned out to be.
    //
    // The screensaver registers exactly like the viewer does, and that was a deliberate
    // reversal: 弹幕 is what a screensaver album is *for* on a shared wall. A photo nobody
    // chose, playing to an empty room, with the comments their family left on it drifting
    // across — the first build suppressed all of this ("its photos were never chosen by
    // anybody") and the result was a slideshow with the one thing this app adds removed.
    // What stays off in a screensaver is the QR code (nobody is there to scan it) and the
    // live socket (see below), not the comments.
    LaunchedEffect(currentId, reviewSource, current.isVideo) {
        viewModel.openReviewedPhoto(item = current, source = reviewSource)
    }

    // Pre-registering: the codes and comments for the next few photos are fetched before
    // anybody asks for them, so the danmaku of the photo after this one is already in hand
    // when the show reaches it — the difference between a screensaver that annotates itself
    // and one that shows every comment three seconds late.
    LaunchedEffect(currentId, slideshowRunning) {
        if (!slideshowRunning && !viewModel.isReviewOnForEveryPhoto) return@LaunchedEffect
        val ahead = items.asSequence()
            .drop(currentIndex)
            .take(PRE_REGISTER_AHEAD)
            .filterNot { it.isVideo }
            .toList()
        val candidates = ahead.mapNotNull { item ->
            val url = item.thumbnailUrl ?: return@mapNotNull null
            val bytes = runCatching { viewModel.thumbnailBytesFor(url) }.getOrNull()
                ?: return@mapNotNull null
            val canonical = PhotoIdentity.canonicalBytes(bytes) ?: return@mapNotNull null
            ReviewCandidate(
                item = item,
                localId = PhotoIdentity.localId(reviewSource, item),
                thumb = canonical,
                sha256Original = null,
            )
        }
        viewModel.preRegisterReviewedPhotos(candidates, reviewSource)
    }

    // The socket opens when the photo settles and closes when it changes. Following every
    // flick of the remote with a connection would be a connection per keystroke.
    //
    // A screensaver asks for the other half of the same call: the comments already on the
    // photo, and no subscription. That is what makes 弹幕 appear at all on a screensaver —
    // the history is fetched by the same request `startLive` makes before it subscribes, so
    // a screensaver that skipped it drew a photo with none of its comments. Nobody is in the
    // room to be told about a *new* one, and a socket opened and closed on every step would
    // be a connection every eight seconds, all night, to deliver nothing.
    LaunchedEffect(currentId, reviewReady?.identity?.imageId) {
        when {
            reviewReady == null -> viewModel.stopReviewedPhotoLive()
            screenSaver -> {
                viewModel.stopReviewedPhotoLive()
                viewModel.refreshReviewedPhotoComments()
            }
            else -> viewModel.startReviewedPhotoLive()
        }
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.stopReviewedPhotoLive() }
    }

    // Resolved here because the key handler below is not a composable scope.
    val playingLabel = stringResource(R.string.viewer_playing)
    val pausedLabel = stringResource(R.string.viewer_paused)
    val playLabel = stringResource(R.string.viewer_action_play)
    val pauseLabel = stringResource(R.string.viewer_action_pause)
    val intervalLabels = SettingsStore.INTERVAL_OPTIONS.map {
        stringResource(R.string.viewer_interval_option, it)
    }
    val orderSequentialLabel = stringResource(R.string.viewer_order_sequential)
    val orderShuffleLabel = stringResource(R.string.viewer_order_shuffle)

    // The 图评 row's labels. The two switches reuse the settings screen's 是/否 because it
    // is the same question a person is answering, and the amount reuses the three words it
    // already has — one vocabulary for one setting, wherever it is read from.
    val reviewQrLabel = stringResource(R.string.viewer_review_qr)
    val reviewDanmakuLabel = stringResource(R.string.viewer_review_danmaku)
    val reviewAmountLabel = stringResource(R.string.viewer_review_danmaku_amount)
    val reviewSlideshowLabel = stringResource(R.string.settings_review_slideshow_on)

    // 开/关 rather than 是/否: all three of these turn a layer on or off, and the value is
    // the only thing visible on the row.
    val switchOn = stringResource(R.string.switch_on)
    val switchOff = stringResource(R.string.switch_off)

    val reviewSlideshowOnLabel = "$reviewSlideshowLabel $switchOn"
    val reviewSlideshowOffLabel = "$reviewSlideshowLabel $switchOff"
    val reviewQrOnLabel = "$reviewQrLabel $switchOn"
    val reviewQrOffLabel = "$reviewQrLabel $switchOff"
    val reviewDanmakuOnLabel = "$reviewDanmakuLabel $switchOn"
    val reviewDanmakuOffLabel = "$reviewDanmakuLabel $switchOff"

    // The three words the amount row cycles through, resolved here: `stepReviewRow` is a
    // plain function and cannot call `stringResource` itself.
    val danmakuOffLabel = stringResource(R.string.settings_review_danmaku_off)
    val danmakuFewLabel = stringResource(R.string.settings_review_danmaku_few)
    val danmakuManyLabel = stringResource(R.string.settings_review_danmaku_many)
    fun danmakuLevelLabel(level: DanmakuLevel): String = when (level) {
        DanmakuLevel.OFF -> danmakuOffLabel
        DanmakuLevel.FEW -> danmakuFewLabel
        DanmakuLevel.MANY -> danmakuManyLabel
    }

    /** OK with the info hidden, and the settings row's play control, share this. */
    fun togglePlayback() {
        if (current.isVideo) {
            videoPlaying = !videoPlaying
            notify(if (videoPlaying) playingLabel else pausedLabel)
        } else {
            slideshowRunning = !slideshowRunning
            notify(if (slideshowRunning) playingLabel else pausedLabel)
        }
    }

    fun closeSettings() {
        cornerMenu = null
        musicListOpen = false
        // Back to the corner button the band hands the D-pad to; see [cornerFocus].
        mainFocus = cornerFocus(canLoadOriginal)
        settingFocus = SETTING_PLAY
        reviewFocus = REVIEW_ROW_QR
    }

    /** Opens 图评's row of display switches above its button. */
    fun openReviewMenu() {
        cornerMenu = CornerMenu.REVIEW
        reviewFocus = REVIEW_ROW_QR
        // The switches can only decide what is drawn; they cannot make a server answer. The
        // press that opens them is therefore the one place this screen can say why the layer
        // is empty — and on a television there is no console to say it anywhere else
        // (docs/tuping-design.md §9.4).
        if (reviewStatus is ReviewStatus.Failed) notify(reviewStatus.label())
    }

    /**
     * Asks for the photo on screen at full size, whatever the preview setting says.
     *
     * The file is fetched by the layer behind the visible one, so the thumbnail stays up
     * until the original is decoded and then hands over — no black frame, and the button
     * breathing is the only progress there is to show.
     */
    fun requestOriginal() {
        if (canLoadOriginal) originalRequest = currentId
    }

    /**
     * Opens the track list above the 背景音乐 pill, with the cursor already on something
     * ticked when there is something ticked — the list opens on what the setting is,
     * rather than always at the top.
     */
    fun openMusicList() {
        musicCursor = firstTickedRow(trackRows, selectedMusic.toSet())
        musicListOpen = true
    }

    fun moveMusicCursor(delta: Int) {
        musicCursor = nextSelectableRow(trackRows, musicCursor, delta)
    }

    /**
     * OK on a row: a track is ticked or unticked, and the way in opens the upload page.
     *
     * Ticking is applied at once, so the tick is the confirmation.
     */
    fun activateMusicRow(index: Int) {
        when (val row = trackRows.getOrNull(index)) {
            is MusicRow.Track -> viewModel.setSlideshowMusic(
                if (row.entry.id in selectedMusic) {
                    selectedMusic - row.entry.id
                } else {
                    selectedMusic + row.entry.id
                },
            )

            MusicRow.Manage -> {
                musicListOpen = false
                viewModel.openMusicUpload()
            }

            else -> Unit
        }
    }

    /**
     * Steps one setting, applying it as it moves.
     *
     * Live rather than on confirmation, because the arrows advertise "up/down changes
     * this" and a stepper that only takes effect on OK would contradict them.
     */
    fun stepSetting(delta: Int) {
        when (settingFocus) {
            SETTING_INTERVAL -> {
                val options = SettingsStore.INTERVAL_OPTIONS
                val current = options.indexOf(slideshowSeconds)
                val from = if (current >= 0) current else 0
                val next = (from + delta + options.size) % options.size
                viewModel.setSlideshowSeconds(options[next])
                notify(intervalLabels[next])
            }

            // Two options, so either direction is a toggle.
            SETTING_ORDER -> {
                val shuffled = !slideshowShuffled
                viewModel.setSlideshowShuffled(shuffled)
                notify(if (shuffled) orderShuffleLabel else orderSequentialLabel)
            }

            // The music pill has no value to step. Both the arrows and OK open the list
            // of tracks, which is where this setting is actually changed: a pair of
            // arrows that did nothing at all would be worse than one that opens the
            // editor they point at.
            SETTING_MUSIC -> openMusicList()

            // A switch rather than a stepper's value: either arrow is a toggle, and the
            // notice names which way it went.
            SETTING_REVIEW -> {
                val enabled = !reviewSlideshowEnabled
                viewModel.setReviewSlideshowEnabled(enabled)
                notify(if (enabled) reviewSlideshowOnLabel else reviewSlideshowOffLabel)
            }

            // The play control has no value to step.
            else -> Unit
        }
    }

    /** OK on a setting behaves like a downwards step, matching the settings screen. */
    fun activateSetting() {
        when (settingFocus) {
            SETTING_PLAY -> {
                togglePlayback()
                closeSettings()
            }

            SETTING_MUSIC -> openMusicList()

            else -> stepSetting(+1)
        }
    }

    /**
     * The 图评 row: down the rows, sideways to change a value.
     *
     * The two switches are two-option steppers rather than toggles-on-OK, so that left and
     * right — which the arrows advertise as "change this" — do something on every row, and
     * OK does the same thing as a right press. It is the same shape as the slideshow row
     * beside it, which is what makes one remote do both without a second set of habits.
     */
    fun stepReviewRow(delta: Int) {
        when (reviewFocus) {
            REVIEW_ROW_QR -> {
                val show = !reviewShowQr
                viewModel.setReviewShowQr(show)
                notify(if (show) reviewQrOnLabel else reviewQrOffLabel)
            }

            REVIEW_ROW_DANMAKU -> {
                val show = !reviewShowDanmaku
                viewModel.setReviewShowDanmaku(show)
                notify(if (show) reviewDanmakuOnLabel else reviewDanmakuOffLabel)
            }

            // Three options, so this one really does step.
            else -> {
                val level = danmakuLevel.next()
                viewModel.setDanmakuLevel(level)
                notify(danmakuLevelLabel(level))
            }
        }
    }

    /** OK on a 图评 row behaves like a right press. */
    fun activateReviewRow() = stepReviewRow(+1)

    // Back closes the track list if it is up — focus stays on the 背景音乐 pill it was
    // opened from — and otherwise closes the open row, leaving its button up. Pressing it
    // again leaves the viewer, which is the enclosing BackHandler's job. Deliberately not
    // also bound to the info band, so one Back never has two meanings.
    BackHandler(enabled = cornerMenu != null) {
        if (musicListOpen) musicListOpen = false else closeSettings()
    }

    // Whatever ends the viewer ends the music with it: the show itself may still be
    // "running" as far as this screen's state is concerned, and nothing would ever come
    // back to stop it.
    DisposableEffect(Unit) {
        onDispose { viewModel.stopSlideshowMusic() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // Three places the D-pad can be, and they nest:
                //
                //   - on a corner button (the band is open, no row is);
                //   - inside the slideshow's settings row, or the track list that is a second
                //     level inside it;
                //   - inside 图评's row of display switches.
                //
                // While a row or the list is up, up/down walk it and left/right change the
                // value under the cursor, so nothing moves underneath.
                val inMusicList = showInfo && cornerMenu == CornerMenu.SLIDESHOW && musicListOpen
                val inSlideSettings = showInfo && cornerMenu == CornerMenu.SLIDESHOW && !inMusicList
                val inReviewMenu = showInfo && cornerMenu == CornerMenu.REVIEW

                // The cursor into either row is read and written in exactly two places —
                // here, for vertical movement, and `changeValue`, for the sideways one — so
                // neither row can drift out of step with what is drawn.
                fun stepRow(delta: Int) {
                    when (cornerMenu) {
                        CornerMenu.SLIDESHOW ->
                            settingFocus = (settingFocus + delta + SETTING_COUNT) % SETTING_COUNT

                        CornerMenu.REVIEW ->
                            reviewFocus = (reviewFocus + delta + REVIEW_ROW_COUNT) % REVIEW_ROW_COUNT

                        null -> Unit
                    }
                }

                fun changeValue(delta: Int) {
                    when (cornerMenu) {
                        CornerMenu.SLIDESHOW -> stepSetting(delta)
                        CornerMenu.REVIEW -> stepReviewRow(delta)
                        null -> Unit
                    }
                }

                fun activateRow() {
                    when (cornerMenu) {
                        CornerMenu.SLIDESHOW -> activateSetting()
                        CornerMenu.REVIEW -> activateReviewRow()
                        null -> Unit
                    }
                }

                when (event.key) {
                    // Left/right walk the open row's controls; up/down change the value
                    // under the cursor. That is the shape 1.3.7 has always had — the row is
                    // laid out sideways, so sideways moves along it — and it is the one the
                    // slideshow's own row keeps.
                    //
                    // This was briefly the other way round, on the theory that a television
                    // stepper should navigate vertically. That was wrong twice over: it made
                    // left/right mean one thing here and another in the row next door, and
                    // it broke the muscle memory of the only person who uses this.
                    Key.DirectionLeft -> {
                        when {
                            inMusicList -> Unit
                            inSlideSettings || inReviewMenu -> stepRow(-1)
                            cornerRow -> mainFocus = stepCorner(mainFocus, -1, canLoadOriginal, reviewDisabled)
                            else -> queueStep(-1)
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        when {
                            inMusicList -> Unit
                            inSlideSettings || inReviewMenu -> stepRow(+1)
                            cornerRow -> mainFocus = stepCorner(mainFocus, +1, canLoadOriginal, reviewDisabled)
                            else -> queueStep(1)
                        }
                        true
                    }

                    // Up walks back through the corner buttons and never opens a row: only
                    // OK opens a row. Arriving at 幻灯片 used to open the slideshow's menu,
                    // which meant a person feeling their way along the row fell into a menu
                    // they had not asked for.
                    //
                    // From 幻灯片 — the first button — up has nowhere left to go, so it
                    // closes the band, the same way down does from anywhere.
                    Key.DirectionUp -> {
                        when {
                            inMusicList -> moveMusicCursor(-1)
                            inSlideSettings || inReviewMenu -> changeValue(-1)
                            onSlideshowButton -> {
                                showInfo = false
                                closeSettings()
                            }

                            onReviewButton || onOriginalButton ->
                                mainFocus = stepCorner(mainFocus, -1, canLoadOriginal, reviewDisabled)

                            else -> {
                                showInfo = true
                                mainFocus = cornerFocus(canLoadOriginal)
                            }
                        }
                        true
                    }

                    // Down closes the band from any first-level button. It does not walk
                    // the row: the request is explicit that down is the way *out*, and a
                    // button row that wraps — so that down never reaches an end — would
                    // mean down could never leave. Left/right walk the row instead.
                    Key.DirectionDown -> {
                        when {
                            inMusicList -> moveMusicCursor(+1)
                            inSlideSettings || inReviewMenu -> changeValue(+1)
                            else -> {
                                showInfo = false
                                closeSettings()
                            }
                        }
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.MediaPlayPause -> {
                        when {
                            // With the info hidden, OK stays the direct play/pause
                            // shortcut it has always been.
                            !showInfo -> togglePlayback()

                            inMusicList -> activateMusicRow(musicCursor)

                            onOriginalButton -> requestOriginal()

                            onReviewButton -> openReviewMenu()

                            onSlideshowButton -> {
                                cornerMenu = CornerMenu.SLIDESHOW
                                settingFocus = SETTING_PLAY
                            }

                            else -> activateRow()
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        // The two layers, back one first so that the visible one is on top. Both are
        // always composed: it is the alpha that moves with a page turn, not the model.
        //
        // A layer only records what it decoded. Bringing it forward is the queue's job —
        // see the effect above — so that one place decides what is on screen, and a
        // result that arrives for a request the layer has since been moved off cannot
        // move anything by itself.
        for (layer in 0..1) {
            PhotoLayer(
                url = layerUrls[layer],
                visible = layer == frontLayer,
                onReady = { url ->
                    loadedUrls = loadedUrls.toMutableList().also { it[layer] = url }
                    failedUrls = failedUrls - url
                },
                onFailed = { url ->
                    loadedUrls = loadedUrls.toMutableList().also { it[layer] = null }
                    failedUrls = failedUrls + url
                },
            )
        }

        if (current.isVideo) {
            val videoUrl = current.bestVideoUrl
            if (videoUrl != null) {
                VideoPlayer(
                    url = videoUrl,
                    streams = viewModel.videoStreams,
                    playWhenReady = videoPlaying,
                    modifier = Modifier.fillMaxSize(),
                    onFinished = { videoPlaying = false },
                )
            }
        }

        // A photo that has not arrived leaves a black window — the viewer opens on one,
        // and on a slow link it stays black for as long as the download takes, which
        // says nothing about whether anything is happening. A spinner says it.
        //
        // Only ever shown when there is genuinely nothing to look at: a page turn keeps
        // the photo being left on screen until the next one is decoded, so the front
        // layer is ready throughout, and a file that failed is not on its way.
        if (frontIsLoading()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Spinner()
                Spacer(Modifier.height(20.dp))
                Text(
                    text = stringResource(R.string.loading),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }

        // The danmaku layer, over the photo and under the two corner affordances: it may
        // never cover the paging arrows or the information band, and it must never be
        // focusable. When 图评 is off, the server is unreachable, or the 图评 button has
        // switched the layer off, this draws nothing at all — a missing layer is the
        // intended failure mode (§9.3).
        if (reviewReady != null && !current.isVideo && reviewShowDanmaku) {
            DanmakuOverlay(
                comments = reviewReady.comments,
                level = danmakuLevel,
                photoKey = currentId,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // The QR code: bottom-RIGHT, above the information bar. Right rather than left
        // because that is where a camera's own watermark goes, and a mark in the opposite
        // corner to the one every phone puts there reads as the app's own overlay rather
        // than as a second watermark fighting with the photo's.
        //
        // Left alone while the band is open, because that is the band's space — and while
        // the 图评 menu is open, because that is the moment somebody is deciding whether
        // they want it at all. Never in a screensaver: the code is addressed to somebody
        // standing in front of the television with a phone in their hand, and a screensaver
        // is on precisely because nobody is in the room.
        if (reviewReady != null && !current.isVideo && !showInfo && reviewShowQr && !screenSaver) {
            ReviewQrCode(
                pngFile = reviewReady.qrFile,
                label = stringResource(R.string.viewer_qr_hint),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 28.dp),
            )
        }

        // What the user's own presses are doing, in the corner the eye already goes to
        // for "next". One arrow per step the pointer has been walked, so three presses
        // right read as three photos rather than as a single vague "loading" — even
        // though the viewer is only fetching the last of them. The show's own queue is
        // deliberately not counted: seeing it work is the point of a slideshow, and the
        // pictures arriving on their own are not presses anybody made.
        val manualPending = pending.count { it !in slideshowQueued }
        AnimatedVisibility(
            visible = manualPending > 0,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 48.dp, bottom = if (showInfo) 132.dp else 48.dp),
        ) {
            PagingArrows(count = manualPending, toRight = pendingRight)
        }

        // One bottom band: the photo's details on the left, the slideshow controls in
        // the bottom-right corner. The settings row opens upward from the button.
        AnimatedVisibility(
            visible = showInfo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color(0xE6000000)),
                        ),
                    )
                    .padding(horizontal = 48.dp, vertical = 36.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                InfoText(
                    name = current.name,
                    takenAt = formatTakenAt(current.takenAt),
                    details = mediaSubtitle(current.width, current.height, current.fileSize),
                    position = stringResource(R.string.viewer_position, currentIndex + 1, items.size),
                    // What the eye cannot tell from the picture: the same photograph at
                    // 1920px and at its own size look alike until you stand up.
                    quality = when {
                        current.isVideo -> null
                        showingOriginal -> originalLabel
                        else -> thumbnailLabel
                    },
                    qualityIsOriginal = showingOriginal,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(32.dp))
                SlideshowControls(
                    menu = cornerMenu,
                    onReviewButton = onReviewButton,
                    reviewDisabled = reviewDisabled,
                    onMainButton = onSlideshowButton,
                    onOriginalButton = onOriginalButton,
                    originalAvailable = canLoadOriginal,
                    originalLoading = originalLoading,
                    settingFocus = settingFocus,
                    reviewFocus = reviewFocus,
                    reviewQrLabel = reviewQrLabel,
                    reviewDanmakuLabel = reviewDanmakuLabel,
                    reviewAmountLabel = reviewAmountLabel,
                    reviewQrValue = if (reviewShowQr) switchOn else switchOff,
                    reviewDanmakuValue = if (reviewShowDanmaku) switchOn else switchOff,
                    reviewAmountValue = danmakuLevelLabel(danmakuLevel),
                    reviewSlideshowLabel = reviewSlideshowLabel,
                    reviewSlideshowEnabled = reviewSlideshowEnabled,
                    switchOn = switchOn,
                    switchOff = switchOff,
                    slideshowRunning = slideshowRunning,
                    slideshowSeconds = slideshowSeconds,
                    shuffled = slideshowShuffled,
                    selectedMusic = selectedMusic,
                    musicRows = trackRows,
                    musicCursor = musicCursor,
                    musicListOpen = musicListOpen,
                    playLabel = playLabel,
                    pauseLabel = pauseLabel,
                    orderSequentialLabel = orderSequentialLabel,
                    orderShuffleLabel = orderShuffleLabel,
                )
            }
        }

        // Self-dismissing feedback, always along the bottom of the screen: over the
        // middle of the picture it reads as an intrusion, and the photo is the thing
        // being looked at. A fixed offset rather than one that clears the band — the
        // band grows when the settings row opens, and lifting the notice clear of it
        // would put it back in the middle of the screen, which is the whole problem.
        AnimatedVisibility(
            visible = noticeText != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp),
        ) {
            Text(
                text = noticeText.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

private const val NOTICE_MILLIS = 1800L

/**
 * The bounds a step's patience is held between, whatever the show's interval is.
 *
 * Between them a step waits as long as the show's own interval — the wait it is spending
 * anyway. The floor keeps a 3-second show from flashing a thumbnail that was a moment
 * away; the ceiling keeps a slow original from parking the show on the previous photo,
 * which is the freeze this mechanism exists to prevent. See `stepPatienceMs`.
 */
private const val STEP_PATIENCE_MIN_MS = 4_000L
private const val STEP_PATIENCE_MAX_MS = 12_000L

/**
 * How many stills a running slideshow keeps warm.
 *
 * A show is not a reader. Shuffled — which shows are by default — the next photo is drawn
 * from the whole list, so a window anchored at the current position can only be in the
 * right place by luck; the window therefore covers the list the show is drawing from. It is
 * bounded because a long list of originals is gigabytes and the five-gigabyte disk cache is
 * not endless.
 */
private const val SHOW_WARM_LIMIT = 120

/** How often a running slideshow re-reads its list looking for changes. */
private const val SLIDESHOW_REFRESH_MILLIS = 30_000L

/** Above this many queued pages the arrows stop being countable and just say "many". */
private const val MAX_ARROWS = 5

/**
 * How many photos ahead 图评 pre-registers.
 *
 * Four rather than fifty, which is what the image prefetcher warms: a thumbnail upload is
 * a *write* to someone else's server, and a television that uploaded every photo in the
 * album ahead of itself would be a worse guest than one that uploads the four about to
 * appear on screen (docs/tuping-design.md §9.2).
 */
private const val PRE_REGISTER_AHEAD = 4

/**
 * How many entries ahead the image cache is asked about originals.
 *
 * Eight rather than the prefetcher's fifty because this is not a download: it is eight
 * snapshot opens, and the point is only that a page turn usually finds the answer waiting
 * rather than swapping tiers a moment later. A miss costs the swap, not a worse picture —
 * the layer is pointed at the thumbnail and the cached original takes over through the same
 * path the 原图 button uses.
 */
private const val PROBE_AHEAD = 8

/**
 * The width the stay-extension arithmetic assumes, in pixels.
 *
 * The design's numbers are written for 1080p, and this is the one place the layer needs a
 * screen width before it has one: the extension is decided the instant a comment arrives,
 * which is before any layout has run.
 */
private const val REVIEW_SCREEN_WIDTH_PX = 1920f

/**
 * How tall the track list may grow before it scrolls.
 *
 * Nine tracks at roughly 42dp each is most of the screen, and the list sits above the
 * settings row and the 幻灯片 button, so it is capped to the room those leave.
 */
private val MUSIC_LIST_MAX_HEIGHT = 280.dp

/**
 * One of the viewer's two image layers.
 *
 * The visible one is fully opaque; the other is loaded and decoded out of sight at
 * exactly the same size, so that when it is brought forward there is nothing left to
 * wait for. `alpha` is used rather than leaving the hidden layer out of the tree, since
 * a layer that is not composed has no image loaded into it at all.
 *
 * It draws [url] and nothing else — which still to ask for is the viewer's decision, not
 * the layer's — and reports the URL it finished with rather than an id, since that is
 * what the pixels are. A null URL draws nothing and reports nothing: there is no request
 * to finish.
 */
@Composable
private fun PhotoLayer(
    url: String?,
    visible: Boolean,
    onReady: (String) -> Unit,
    onFailed: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f),
        onSuccess = { state ->
            // `request.data` rather than the layer's URL: the painter reports the result
            // of the request it was given, and a layer whose model has just changed must
            // not mistake the *previous* photo arriving for this one.
            if (url != null && state.result.request.data == url) onReady(url)
        },
        onError = { state ->
            if (url != null && state.result.request.data == url) onFailed(url)
        },
    )
}

/**
 * The paging queue, drawn in the bottom corner: one arrow per photo still on its way,
 * breathing so that it reads as "working" rather than as a decoration.
 */
@Composable
private fun PagingArrows(
    count: Int,
    toRight: Boolean,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "paging")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )

    Row(
        modifier = modifier
            .alpha(pulse)
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0x8C000000))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count.coerceAtMost(MAX_ARROWS)) {
            Text(
                text = "\u25B6",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
                // The same glyph turned around, rather than a second character: the
                // two then cannot drift apart in weight or size.
                modifier = if (toRight) Modifier else Modifier.rotate(180f),
            )
        }
        if (count > MAX_ARROWS) {
            Text(
                text = "+${count - MAX_ARROWS}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * The controls in the settings row, left to right: the play toggle, the interval, the
 * order and the background music. The interval and the order are steppers, moved with
 * up/down — see [ActionStepper]; the music pill opens [MusicTrackList] instead.
 */
private const val SETTING_PLAY = 0
private const val SETTING_INTERVAL = 1
private const val SETTING_ORDER = 2
private const val SETTING_MUSIC = 3

/**
 * 图评's switch, in the slideshow's own row.
 *
 * It is here and not in the 图评 row because it is a question about the *show*, not about
 * the photo on screen: a host decides once whether the corner of every picture in a party
 * slideshow carries a code. The per-photo switches live in the 图评 row, where they belong.
 */
private const val SETTING_REVIEW = 4
private const val SETTING_COUNT = 5

/** The buttons in the corner, left to right: 原图, 图评, 幻灯片. */
private const val MAIN_ORIGINAL = 0
private const val MAIN_REVIEW = 1
private const val MAIN_SLIDESHOW = 2

/**
 * Every corner button, always, in the order they are drawn.
 *
 * The D-pad walks this list and the drawing maps it to what is actually visible, so a
 * button that is absent (原图 with no original behind the thumbnail) is stepped over
 * rather than left as a hole — which is what a fixed index would have produced.
 */
private val MAIN_BUTTONS = listOf(MAIN_ORIGINAL, MAIN_REVIEW, MAIN_SLIDESHOW)

/** Which corner buttons exist for the photo on screen, in drawing order. */
private fun cornerButtons(canLoadOriginal: Boolean, reviewDisabled: Boolean): List<Int> =
    MAIN_BUTTONS.filterNot { button ->
        (button == MAIN_ORIGINAL && !canLoadOriginal) ||
            (button == MAIN_REVIEW && reviewDisabled)
    }

/**
 * Which corner button the D-pad is handed on opening the band.
 *
 * 原图 when there is an original to load, because that is the one thing about the photo on
 * screen that cannot be seen without pressing something; otherwise 幻灯片, whose OK opens
 * the row of settings.
 */
private fun cornerFocus(canLoadOriginal: Boolean): Int =
    if (canLoadOriginal) MAIN_ORIGINAL else MAIN_SLIDESHOW

/**
 * The next button along, wrapping.
 *
 * Left and right walk the corner rather than paging the gallery while the band is open,
 * exactly as they walk the settings row: a row of buttons is read as a row, and a row is
 * what left/right move in. Paging with the band open is a press of down first, which
 * closes it. Wrapping rather than clamping, because a press that does nothing at all reads
 * as a broken key rather than as a row that has run out.
 */
private fun stepCorner(focus: Int, delta: Int, canLoadOriginal: Boolean, reviewDisabled: Boolean): Int {
    val order = cornerButtons(canLoadOriginal, reviewDisabled)
    val at = order.indexOf(focus).coerceAtLeast(0)
    return order[(at + delta + order.size) % order.size]
}

/**
 * The bottom-right corner: the 图评 / 原图 / 幻灯片 buttons, and the row of controls that
 * the one under the cursor opens above itself.
 *
 * The controls are not individually focusable — the viewer's root box owns focus and
 * routes the D-pad — so the highlighted one is drawn from `mainFocus` and the two row
 * cursors instead.
 */
@Composable
private fun SlideshowControls(
    menu: CornerMenu?,
    onReviewButton: Boolean,
    reviewDisabled: Boolean,
    onMainButton: Boolean,
    onOriginalButton: Boolean,
    originalAvailable: Boolean,
    originalLoading: Boolean,
    settingFocus: Int,
    reviewFocus: Int,
    reviewQrLabel: String,
    reviewDanmakuLabel: String,
    reviewAmountLabel: String,
    reviewQrValue: String,
    reviewDanmakuValue: String,
    reviewAmountValue: String,
    reviewSlideshowLabel: String,
    reviewSlideshowEnabled: Boolean,
    switchOn: String,
    switchOff: String,
    slideshowRunning: Boolean,
    slideshowSeconds: Int,
    shuffled: Boolean,
    selectedMusic: List<String>,
    musicRows: List<MusicRow>,
    musicCursor: Int,
    musicListOpen: Boolean,
    playLabel: String,
    pauseLabel: String,
    orderSequentialLabel: String,
    orderShuffleLabel: String,
    modifier: Modifier = Modifier,
) {
    val inSlideshowRow = menu == CornerMenu.SLIDESHOW
    val inReviewRow = menu == CornerMenu.REVIEW
    val musicSummary = if (selectedMusic.isEmpty()) {
        stringResource(R.string.viewer_music_none)
    } else {
        stringResource(R.string.viewer_music_count, selectedMusic.size)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Above the row, and above the pill it belongs to: the list is what OK on
        // 背景音乐 opens, so it appears where the eye already is.
        AnimatedVisibility(
            visible = inSlideshowRow && musicListOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            MusicTrackList(cursor = musicCursor, selected = selectedMusic, rows = musicRows)
        }

        AnimatedVisibility(visible = inSlideshowRow, enter = fadeIn(), exit = fadeOut()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    label = if (slideshowRunning) pauseLabel else playLabel,
                    selected = settingFocus == SETTING_PLAY,
                )
                ActionStepper(
                    label = stringResource(R.string.viewer_interval_label),
                    // Formatted from the value itself rather than looked up in the
                    // option list, so it can never show a neighbour's number.
                    value = stringResource(R.string.viewer_interval_option, slideshowSeconds),
                    selected = settingFocus == SETTING_INTERVAL,
                )
                ActionStepper(
                    label = stringResource(R.string.viewer_order_label),
                    value = if (shuffled) orderShuffleLabel else orderSequentialLabel,
                    selected = settingFocus == SETTING_ORDER,
                )
                ActionStepper(
                    label = stringResource(R.string.viewer_music_label),
                    value = musicSummary,
                    selected = settingFocus == SETTING_MUSIC,
                )
                // 图评, which is a question about the show rather than about the photo: a
                // host decides once whether the corner of every picture carries a code.
                ActionStepper(
                    label = reviewSlideshowLabel,
                    value = if (reviewSlideshowEnabled) switchOn else switchOff,
                    selected = settingFocus == SETTING_REVIEW,
                )
            }
        }

        // 图评's row. Three steppers rather than a mixed bag of switches, so the arrows
        // mean the same thing on every one of them.
        AnimatedVisibility(visible = inReviewRow, enter = fadeIn(), exit = fadeOut()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionStepper(
                    label = reviewQrLabel,
                    value = reviewQrValue,
                    selected = reviewFocus == REVIEW_ROW_QR,
                )
                ActionStepper(
                    label = reviewDanmakuLabel,
                    value = reviewDanmakuValue,
                    selected = reviewFocus == REVIEW_ROW_DANMAKU,
                )
                ActionStepper(
                    label = reviewAmountLabel,
                    value = reviewAmountValue,
                    selected = reviewFocus == REVIEW_ROW_AMOUNT,
                )
            }
        }

        // The corner itself, left to right: 原图 — while there is an original behind the
        // thumbnail on screen — then 图评, and then 幻灯片, which is where the slideshow's
        // settings row above opens from.
        Row(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (originalAvailable) {
                OriginalButton(selected = onOriginalButton, loading = originalLoading)
            }
            ActionButton(
                label = stringResource(R.string.viewer_action_review),
                selected = onReviewButton,
                disabled = reviewDisabled,
            )
            ActionButton(
                label = stringResource(R.string.viewer_action_slideshow),
                selected = onMainButton,
                prominent = true,
            )
        }
    }
}

/**
 * 原图: fetch the photo on screen at full size even though the preview setting says
 * thumbnail, or the slideshow has not reached it yet.
 *
 * It breathes while the file is on its way, which is the only progress there is to show:
 * the thumbnail underneath deliberately stays where it is until the original has been
 * decoded, so there is no half-drawn picture to look at instead.
 */
@Composable
private fun OriginalButton(selected: Boolean, loading: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .alpha(pulseAlpha(loading))
            .clip(shape)
            .background(if (selected) primary else Color(0xCC101826))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) primary else Color(0x33FFFFFF),
                shape = shape,
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(
            text = stringResource(R.string.viewer_action_original),
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        )
    }
}

/**
 * A control's own pulse, 1f when there is nothing to announce.
 *
 * Always composed, and asked for the value it should take: a transition started inside an
 * `if` would break the rule that a composable is called the same way every time, and the
 * breathing is the whole of the "still loading" signal.
 */
@Composable
private fun pulseAlpha(active: Boolean): Float {
    val transition = rememberInfiniteTransition(label = "busy")
    val pulse by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    return if (active) pulse else 1f
}

/**
 * The tracks that can play under the show, listed above the settings row.
 *
 * A list rather than a third stepper: the setting is a multi-select, and cycling a pill
 * through "none, this one, that one, both" hides the thing the user is deciding — which
 * tracks are ticked — behind one press at a time.
 *
 * The catalogue is longer than the room above the settings row, so the list is capped
 * and scrolls, and it scrolls itself: the D-pad never touches these rows — the viewer's
 * root box routes the keys and this list is drawn from [cursor] — so nothing else would
 * bring the cursor's row into view.
 *
 * The cursor is drawn from [cursor] rather than focused, like every other control in the
 * viewer: the root box owns focus and routes the D-pad.
 */
@Composable
private fun MusicTrackList(
    cursor: Int,
    selected: List<String>,
    rows: List<MusicRow>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val listState = rememberLazyListState()

    // Only when the cursor's row is not already on screen: scrolling on every move would
    // slide the whole list under a cursor that had not left the visible part of it. The
    // viewport is empty on the first pass, which is what puts the opening cursor — the
    // first ticked track — in view.
    LaunchedEffect(cursor) {
        val onScreen = listState.layoutInfo.visibleItemsInfo.any { it.index == cursor }
        if (!onScreen) runCatching { listState.animateScrollToItem(cursor.coerceAtLeast(0)) }
    }

    Column(
        modifier = modifier
            .width(380.dp)
            .clip(shape)
            .background(Color(0xE6101826))
            .border(1.dp, Color(0x33FFFFFF), shape)
            .padding(vertical = 10.dp),
    ) {
        Text(
            text = stringResource(R.string.viewer_music_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.heightIn(max = MUSIC_LIST_MAX_HEIGHT),
        ) {
            itemsIndexed(rows) { index, row ->
                when (row) {
                    is MusicRow.Track -> MusicTrackRow(
                        title = row.entry.title,
                        ticked = row.entry.id in selected,
                        highlighted = index == cursor,
                    )

                    MusicRow.Manage -> MusicActionRow(
                        title = stringResource(R.string.music_viewer_manage),
                        highlighted = index == cursor,
                    )
                }
            }
        }
    }
}

/** `＋ 上传 / 管理背景音乐…`: the one row in this list that is not a track. */
@Composable
private fun MusicActionRow(title: String, highlighted: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = if (highlighted) primary else Color.White.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) primary.copy(alpha = 0.25f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

/** One track: a tick when it is in the show's playlist, and the cursor's highlight. */
@Composable
private fun MusicTrackRow(
    title: String,
    ticked: Boolean,
    highlighted: Boolean,
) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (highlighted) primary.copy(alpha = 0.25f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The slot is always there, ticked or not, so the titles line up and the tick
        // reads as a state rather than as part of the name.
        Text(
            text = if (ticked) "\u2713" else "",
            style = MaterialTheme.typography.titleSmall,
            color = primary,
            modifier = Modifier.width(18.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = if (highlighted) primary else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A setting shown as a label, its value, and a pair of arrows.
 *
 * The arrows are the affordance: they are what tells the viewer that up/down change the
 * value rather than moving around the row, which left/right does. Keeping them beside
 * the value rather than stacked over and under it leaves the control one line tall.
 */
@Composable
private fun ActionStepper(
    label: String,
    value: String,
    selected: Boolean,
) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        // Tightened when the slideshow's row grew to five controls: the row is centred in
        // the same band as the photo's name, date and size on the left, and at the old
        // spacing five of these pushed that text into wrapping — a photo whose filename
        // broke across two lines and whose size lost a digit, which reads as a bug in the
        // info rather than as a crowd.
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Color(0x33101826) else Color(0xCC101826))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) primary else Color(0x33FFFFFF),
                shape = shape,
            )
            .padding(horizontal = 11.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (selected) 0.85f else 0.6f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) primary else Color.White,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Literal triangles rather than an icon: they are punctuation-sized and
                // carry no text to translate.
                Text(
                    text = "\u25B2",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) primary else Color.White.copy(alpha = 0.45f),
                )
                Text(
                    text = "\u25BC",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (selected) primary else Color.White.copy(alpha = 0.45f),
                )
            }
        }
    }
}

/** A plain control: no value, so the D-pad only has to be able to land on it. */
@Composable
private fun ActionButton(
    label: String,
    selected: Boolean,
    prominent: Boolean = false,
    disabled: Boolean = false,
) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(shape)
            .background(
                when {
                    disabled -> Color(0x59101826)
                    selected -> primary
                    else -> Color(0xCC101826)
                },
            )
            .border(
                width = if (selected && !disabled) 2.dp else 1.dp,
                color = when {
                    disabled -> Color(0x1AFFFFFF)
                    selected -> primary
                    else -> Color(0x33FFFFFF)
                },
                shape = shape,
            )
            .padding(
                horizontal = if (prominent) 26.dp else 18.dp,
                vertical = if (prominent) 12.dp else 14.dp,
            ),
    ) {
        Text(
            text = label,
            style = if (prominent) {
                MaterialTheme.typography.titleSmall
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = when {
                // Dimmed, not hidden: the button keeps its place in the row so the row does
                // not change shape when the server goes away, and so the person who set the
                // address can see that the thing they configured is what went quiet.
                disabled -> Color.White.copy(alpha = 0.35f)
                selected -> MaterialTheme.colorScheme.onPrimary
                else -> Color.White
            },
        )
    }
}

@Composable
private fun InfoText(
    name: String,
    takenAt: String,
    details: String,
    position: String,
    quality: String?,
    qualityIsOriginal: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Only as wide as it needs, so the pill sits against the name rather than
                // against a stretched-out text box.
                modifier = Modifier.weight(1f, fill = false),
            )
            if (quality != null) QualityPill(quality, original = qualityIsOriginal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (takenAt.isNotBlank()) {
                Text(
                    text = takenAt,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            if (details.isNotBlank()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = position,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = 0.65f),
        )
    }
}

/**
 * 原图 or 缩略图, beside the file name.
 *
 * A pill rather than another line of text: it is a property of the picture, not of the
 * file, and it has to be readable at the distance a sofa is from a television.
 */
@Composable
private fun QualityPill(label: String, original: Boolean) {
    val shape = RoundedCornerShape(6.dp)
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (original) accent.copy(alpha = 0.22f) else Color(0x33FFFFFF))
            .border(1.dp, if (original) accent else Color(0x33FFFFFF), shape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (original) accent else Color.White.copy(alpha = 0.85f),
        )
    }
}

/** Hides the system bars while the viewer is on screen. */
@Composable
private fun ImmersiveMode() {
    val view = LocalView.current
    val activity = LocalContext.current as? Activity

    DisposableEffect(view) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}
