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
import androidx.compose.runtime.mutableStateOf
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
import com.tvphoto.data.SLIDESHOW_MUSIC
import com.tvphoto.data.SessionState
import com.tvphoto.data.SettingsStore
import com.tvphoto.domain.MediaItem
import com.tvphoto.domain.nextSlideshowItem
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.Screen
import com.tvphoto.ui.SlideshowDelta
import com.tvphoto.ui.components.PrefetchAhead
import com.tvphoto.ui.components.Spinner
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.formatTakenAt
import com.tvphoto.ui.components.mediaSubtitle
import kotlinx.coroutines.delay
import kotlin.random.Random

@Composable
fun ViewerScreen(viewModel: MainViewModel, screen: Screen.Viewer) {
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val items = gallery.items.valueOrNull.orEmpty()
    val slideshowSeconds by viewModel.slideshowSeconds.collectAsStateWithLifecycle()
    val slideshowShuffled by viewModel.slideshowShuffled.collectAsStateWithLifecycle()
    val selectedMusic by viewModel.slideshowMusic.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val token = (sessionState as? SessionState.SignedIn)?.session?.token
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
    var layerIds by remember { mutableStateOf<List<Long?>>(listOf(items[startIndex].id, null)) }
    var frontLayer by remember { mutableIntStateOf(0) }

    // What each layer is actually showing, as opposed to what it has been asked for. A
    // layer that has finished a photo keeps it until the model changes, so the photo
    // behind the visible one is often the one the queue is waiting for — paging back is
    // the obvious case — and then there is nothing to download and nothing to wait for.
    var loadedIds by remember { mutableStateOf<List<Long?>>(listOf(null, null)) }

    // Files the server could not produce. Only the loading indicator below cares: a
    // photo that failed is not a photo on its way, and a spinner left running on it
    // would be a worse answer than the black window it replaced.
    var failedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }

    // --------------------------------------------------------- the paging queue
    // The photos between the photo on screen and the pointer the user has walked to,
    // in the order they will be shown. Nothing about the list is re-ordered by a press:
    // a queued photo is loaded first and becomes the current one when it is ready, and
    // the corner arrows report how far the pointer still has to travel.
    var pending by remember { mutableStateOf<List<Long>>(emptyList()) }
    var pendingRight by remember { mutableStateOf(true) }

    // Which of those the *show* asked for, as opposed to the user. Only the user's own
    // presses are reported in the corner: a show fetching its next picture in the
    // background is not something to announce, and "one arrow per photo you queued"
    // would be a lie if some of the arrows were the show's.
    var slideshowQueued by remember { mutableStateOf<Set<Long>>(emptySet()) }

    var slideshowRunning by remember { mutableStateOf(false) }
    var videoPlaying by remember { mutableStateOf(true) }
    var showInfo by remember { mutableStateOf(false) }

    // Where the photo on screen last sat. Used when that photo is deleted out from under
    // the viewer: the photo that slid into its place is the least jarring replacement,
    // and falling back to the top of the list would jump the show back to the newest.
    var positionHint by remember { mutableIntStateOf(startIndex) }

    // The 幻灯片 button opens a row of settings above itself. `onMainButton` says which
    // row the D-pad is on; `settingFocus` is the control within the settings row.
    var menuOpen by remember { mutableStateOf(false) }
    var onMainButton by remember { mutableStateOf(true) }
    var settingFocus by remember { mutableIntStateOf(SETTING_PLAY) }

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
     * True while the picture on screen has not been decoded yet, which is the viewer
     * opening on it or the list changing underneath it. A page turn never sets it: the
     * photo being left stays up until the next one is ready.
     */
    fun frontIsLoading(): Boolean {
        val id = layerIds[frontLayer] ?: return false
        return loadedIds[frontLayer] != id && id !in failedIds
    }

    // Immersive viewing: the viewer hides the system bars and restores them on exit.
    ImmersiveMode()

    // Keeps the cache ahead of the photo on screen, so paging right stays instant.
    // The anchor is handed over as state so that moving the window does not recompose
    // the viewer — the whole screen would otherwise be rebuilt on every page.
    val prefetchAnchor = remember { mutableIntStateOf(startIndex) }
    LaunchedEffect(currentIndex) { prefetchAnchor.value = currentIndex }
    PrefetchAhead(
        itemCount = items.size,
        anchor = prefetchAnchor,
        // The window stands down while the viewer is waiting for a picture of its own:
        // the one on screen at open, and the one a page turn has asked for. Both travel
        // over the client and the LAN the prefetch is using, and the photo the user is
        // looking at — or has just asked for — is the one that has to arrive first.
        hold = { pending.isNotEmpty() || frontIsLoading() },
        prefetch = { from -> viewModel.warmImageCache(items, from) },
    )

    /**
     * The photo [delta] places away from [from] in the order the user is walking.
     *
     * Manual paging is always sequential even when the slideshow is shuffling: left and
     * right mean the neighbouring photo, and a random neighbour would make the arrow
     * count a lie.
     */
    fun neighbourOf(from: Long?, delta: Int): Long? {
        if (items.size <= 1) return null
        val at = from?.let { indexById[it] } ?: currentIndex
        val next = ((at + delta) % items.size + items.size) % items.size
        return items[next].id
    }

    /**
     * Walks the paging pointer one step in [delta]'s direction.
     *
     * Left and right move a *pointer* along the list rather than appending to a queue.
     * The pointer is the photo the user has asked for; the arrows in the corner are how
     * far it has been walked from the photo on screen, so pressing right and then left
     * puts it back where it started — the right arrow disappears — instead of queueing a
     * photo in each direction and reading as two leftwards arrows for a pointer that had
     * not moved left at all.
     *
     * Photos are still shown one at a time as they arrive: the queue is the stretch of
     * list between the photo on screen and the pointer, and it drains from the pointer's
     * end as each photo lands.
     */
    fun queueStep(delta: Int) {
        if (pending.isEmpty()) {
            val target = neighbourOf(currentId, delta) ?: return
            pendingRight = delta > 0
            pending = listOf(target)
        } else if ((delta > 0) == pendingRight) {
            val target = neighbourOf(pending.last(), delta) ?: return
            pending = pending + target
        } else {
            // Walking back over a step the pointer had taken: it gives that step up
            // rather than queueing the photo behind it. A step the *show* took is given
            // up the same way — the user is holding the list now.
            val dropped = pending.last()
            pending = pending.dropLast(1)
            slideshowQueued = slideshowQueued - dropped
        }
    }

    /**
     * Queues the picture the show moves to next — see [nextSlideshowItem], which is
     * where the rules about videos and duplicates live.
     */
    fun queueSlideshowStep() {
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
     * Takes [id] out of the queue and makes it the photo on screen.
     *
     * Only ever called for a photo that has finished loading — or has failed, which is
     * handled the same way so that one unreadable file cannot stall the queue behind it.
     */
    fun advanceTo(id: Long) {
        if (pending.firstOrNull() != id) return
        pending = pending.drop(1)
        slideshowQueued = slideshowQueued - id
        frontLayer = 1 - frontLayer
    }

    /**
     * Keeps the hidden layer pointed at the photo the queue is waiting for, and brings
     * it forward the moment it is there.
     *
     * Keyed on the head of the queue rather than the whole queue, so pressing right
     * again while a photo is still downloading does not cancel that download.
     */
    LaunchedEffect(pending.firstOrNull(), frontLayer, items, loadedIds) {
        val head = pending.firstOrNull() ?: return@LaunchedEffect
        val back = 1 - frontLayer
        when {
            // Already loaded and waiting off-screen — the photo just paged away from, or
            // one this layer has held all along. There is nothing to fetch, so the queue
            // must not sit here waiting for a callback that will never come.
            loadedIds[back] == head -> advanceTo(head)

            layerIds[back] != head ->
                layerIds = layerIds.toMutableList().also { it[back] = head }
        }
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
    LaunchedEffect(items) {
        val present = items.mapTo(HashSet()) { it.id }
        if (pending.any { it !in present }) {
            pending = pending.filter { it in present }
            slideshowQueued = slideshowQueued.filterTo(HashSet()) { it in present }
        }

        if (layerIds.any { it != null && it !in present }) {
            val fallback = items[positionHint.coerceIn(0, items.lastIndex)].id
            layerIds = layerIds.map { id -> if (id == null || id in present) id else fallback }
        }
    }

    // Keyed on the id rather than the index on purpose. A frame after a deletion the
    // current id has no index at all, and reading one then would record the placeholder
    // the line above fell back to instead of where the photo really sat.
    LaunchedEffect(currentId) {
        indexById[currentId]?.let { positionHint = it }
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
        menuOpen,
    ) {
        if (slideshowRunning && items.size > 1 && !menuOpen) {
            delay(slideshowSeconds * 1000L)
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
    val musicWanted = slideshowRunning && selectedMusic.isNotEmpty()
    LaunchedEffect(musicWanted, menuOpen, current.isVideo, selectedMusic, onScreen) {
        when {
            !musicWanted -> viewModel.stopSlideshowMusic()
            !onScreen || menuOpen || current.isVideo -> viewModel.pauseSlideshowMusic()
            else -> viewModel.playSlideshowMusic()
        }
    }

    // Videos always start playing when they become the visible item.
    LaunchedEffect(currentIndex) { videoPlaying = true }

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
        menuOpen = false
        musicListOpen = false
        onMainButton = true
        settingFocus = SETTING_PLAY
    }

    /**
     * Opens the track list above the 背景音乐 pill, with the cursor already on something
     * ticked when there is something ticked — the list opens on what the setting is,
     * rather than always at the top.
     */
    fun openMusicList() {
        val firstTicked = SLIDESHOW_MUSIC.indexOfFirst { it.asset in selectedMusic }
        musicCursor = if (firstTicked >= 0) firstTicked else 0
        musicListOpen = true
    }

    fun moveMusicCursor(delta: Int) {
        if (SLIDESHOW_MUSIC.isEmpty()) return
        musicCursor = (musicCursor + delta + SLIDESHOW_MUSIC.size) % SLIDESHOW_MUSIC.size
    }

    /** Ticks or unticks one track. Applied at once, so the tick is the confirmation. */
    fun toggleMusicTrack(index: Int) {
        val track = SLIDESHOW_MUSIC.getOrNull(index) ?: return
        viewModel.setSlideshowMusic(
            if (track.asset in selectedMusic) selectedMusic - track.asset
            else selectedMusic + track.asset,
        )
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

    // Back closes the track list if it is up — focus stays on the 背景音乐 pill it was
    // opened from — and otherwise closes the settings row, leaving the 幻灯片 button up.
    // Pressing it again leaves the viewer, which is the enclosing BackHandler's job.
    // Deliberately not also bound to the info band, so one Back never has two meanings.
    BackHandler(enabled = menuOpen) {
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
                // The track list is a second level inside the settings row: while it is
                // up, up/down walk the tracks rather than the row, and left/right and
                // the photo keys are held so that nothing moves underneath it.
                val inMusicList = showInfo && menuOpen && musicListOpen
                val inSettings = showInfo && menuOpen && !onMainButton && !inMusicList
                val onSlideshowButton = showInfo && onMainButton

                when (event.key) {
                    Key.DirectionLeft -> {
                        when {
                            inMusicList -> Unit
                            inSettings ->
                                settingFocus = (settingFocus - 1 + SETTING_COUNT) % SETTING_COUNT

                            else -> queueStep(-1)
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        when {
                            inMusicList -> Unit
                            inSettings -> settingFocus = (settingFocus + 1) % SETTING_COUNT
                            else -> queueStep(1)
                        }
                        true
                    }

                    Key.DirectionUp -> {
                        when {
                            inMusicList -> moveMusicCursor(-1)
                            inSettings -> stepSetting(-1)
                            onSlideshowButton -> {
                                menuOpen = true
                                settingFocus = SETTING_PLAY
                                onMainButton = false
                            }

                            else -> showInfo = true
                        }
                        true
                    }

                    Key.DirectionDown -> {
                        when {
                            inMusicList -> moveMusicCursor(+1)
                            inSettings -> stepSetting(+1)
                            onSlideshowButton -> {
                                showInfo = false
                                closeSettings()
                            }

                            else -> Unit
                        }
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.Spacebar, Key.MediaPlayPause -> {
                        when {
                            // With the info hidden, OK stays the direct play/pause
                            // shortcut it has always been.
                            !showInfo -> togglePlayback()

                            inMusicList -> toggleMusicTrack(musicCursor)

                            onSlideshowButton -> {
                                menuOpen = true
                                settingFocus = SETTING_PLAY
                                onMainButton = false
                            }

                            else -> activateSetting()
                        }
                        true
                    }

                    else -> false
                }
            },
    ) {
        // The two layers, back one first so that the visible one is on top. Both are
        // always composed: it is the alpha that moves with a page turn, not the model.
        for (layer in 0..1) {
            val item = layerIds[layer]?.let { id -> indexById[id]?.let(items::getOrNull) }
            PhotoLayer(
                item = item,
                visible = layer == frontLayer,
                onReady = { id ->
                    loadedIds = loadedIds.toMutableList().also { it[layer] = id }
                    failedIds = failedIds - id
                    advanceTo(id)
                },
                onFailed = { id ->
                    loadedIds = loadedIds.toMutableList().also { it[layer] = null }
                    failedIds = failedIds + id
                    advanceTo(id)
                },
            )
        }

        if (current.isVideo) {
            val videoUrl = current.bestVideoUrl
            if (videoUrl != null) {
                VideoPlayer(
                    url = videoUrl,
                    token = token,
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

        // What the user's own presses are doing, in the corner the eye already goes to
        // for "next". One arrow per photo still waiting, so three presses right read as
        // three photos rather than as a single vague "loading". The show's own queue is
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
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(32.dp))
                SlideshowControls(
                    menuOpen = menuOpen,
                    onMainButton = onMainButton,
                    settingFocus = settingFocus,
                    slideshowRunning = slideshowRunning,
                    slideshowSeconds = slideshowSeconds,
                    shuffled = slideshowShuffled,
                    selectedMusic = selectedMusic,
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

/** How often a running slideshow re-reads its list looking for changes. */
private const val SLIDESHOW_REFRESH_MILLIS = 30_000L

/** Above this many queued pages the arrows stop being countable and just say "many". */
private const val MAX_ARROWS = 5

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
 */
@Composable
private fun PhotoLayer(
    item: MediaItem?,
    visible: Boolean,
    onReady: (Long) -> Unit,
    onFailed: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = item?.id
    // A video's full-size URL is the video itself, so its still is the thumbnail —
    // never the original.
    val url = item?.let { if (it.isVideo) it.thumbnailUrl else it.bestStillUrl }

    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .fillMaxSize()
            .alpha(if (visible) 1f else 0f),
        onSuccess = { state ->
            // `request.data` rather than the layer's current id: the painter reports
            // the result of the request it was given, and a layer whose model has just
            // changed must not mistake the *previous* photo arriving for this one.
            if (id != null && state.result.request.data == url) onReady(id)
        },
        onError = { state ->
            if (id != null && state.result.request.data == url) onFailed(id)
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
private const val SETTING_COUNT = 4

/**
 * The bottom-right controls: a 幻灯片 button, and the settings row it opens above
 * itself.
 *
 * The controls are not individually focusable — the viewer's root box owns focus and
 * routes the D-pad — so the highlighted one is drawn from [settingFocus] instead.
 */
@Composable
private fun SlideshowControls(
    menuOpen: Boolean,
    onMainButton: Boolean,
    settingFocus: Int,
    slideshowRunning: Boolean,
    slideshowSeconds: Int,
    shuffled: Boolean,
    selectedMusic: List<String>,
    musicCursor: Int,
    musicListOpen: Boolean,
    playLabel: String,
    pauseLabel: String,
    orderSequentialLabel: String,
    orderShuffleLabel: String,
    modifier: Modifier = Modifier,
) {
    val focusable = !onMainButton
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
            visible = menuOpen && musicListOpen,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            MusicTrackList(cursor = musicCursor, selected = selectedMusic)
        }

        AnimatedVisibility(visible = menuOpen, enter = fadeIn(), exit = fadeOut()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ActionButton(
                    label = if (slideshowRunning) pauseLabel else playLabel,
                    selected = focusable && settingFocus == SETTING_PLAY,
                )
                ActionStepper(
                    label = stringResource(R.string.viewer_interval_label),
                    // Formatted from the value itself rather than looked up in the
                    // option list, so it can never show a neighbour's number.
                    value = stringResource(R.string.viewer_interval_option, slideshowSeconds),
                    selected = focusable && settingFocus == SETTING_INTERVAL,
                )
                ActionStepper(
                    label = stringResource(R.string.viewer_order_label),
                    value = if (shuffled) orderShuffleLabel else orderSequentialLabel,
                    selected = focusable && settingFocus == SETTING_ORDER,
                )
                ActionStepper(
                    label = stringResource(R.string.viewer_music_label),
                    value = musicSummary,
                    selected = focusable && settingFocus == SETTING_MUSIC,
                )
            }
        }

        ActionButton(
            label = stringResource(R.string.viewer_action_slideshow),
            selected = onMainButton,
            prominent = true,
        )
    }
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
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val listState = rememberLazyListState()

    LaunchedEffect(cursor) {
        runCatching { listState.animateScrollToItem(cursor.coerceAtLeast(0)) }
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
            itemsIndexed(SLIDESHOW_MUSIC) { index, track ->
                MusicTrackRow(
                    title = track.title,
                    ticked = track.asset in selected,
                    highlighted = index == cursor,
                )
            }
        }
    }
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .clip(shape)
            .background(if (selected) Color(0x33101826) else Color(0xCC101826))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) primary else Color(0x33FFFFFF),
                shape = shape,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (selected) 0.85f else 0.6f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
) {
    val shape = RoundedCornerShape(10.dp)
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) primary else Color(0xCC101826))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) primary else Color(0x33FFFFFF),
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
            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
        )
    }
}

@Composable
private fun InfoText(
    name: String,
    takenAt: String,
    details: String,
    position: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
