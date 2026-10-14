package com.tvphoto.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.datasource.DataSource
import com.tvphoto.TvPhotoApp
import com.tvphoto.BuildConfig
import com.tvphoto.R
import com.tvphoto.data.ApkInstaller
import com.tvphoto.data.AppContainer
import com.tvphoto.data.FolderLayout
import com.tvphoto.data.ImagePrefetcher
import com.tvphoto.data.PreviewOriginal
import com.tvphoto.data.SavedAccount
import com.tvphoto.data.SessionState
import com.tvphoto.data.ThemeMode
import com.tvphoto.data.UpdateCheckResult
import com.tvphoto.data.UpdateRelease
import com.tvphoto.data.accountFor
import com.tvphoto.data.fn.SignMode
import com.tvphoto.data.music.MusicEntry
import com.tvphoto.data.music.MusicLibraryState
import com.tvphoto.data.music.MusicUploadServer
import com.tvphoto.data.normalizeMusicSelection
import com.tvphoto.data.review.DanmakuLevel
import com.tvphoto.data.review.ReviewCandidate
import com.tvphoto.data.review.ReviewDecision
import com.tvphoto.data.review.ReviewMode
import com.tvphoto.data.review.ReviewStatus
import com.tvphoto.data.review.ReviewView
import com.tvphoto.data.review.reviewDecision
import com.tvphoto.domain.AlbumItem
import com.tvphoto.domain.FolderItem
import com.tvphoto.domain.MediaItem
import com.tvphoto.domain.MediaPage
import com.tvphoto.domain.Person
import com.tvphoto.domain.PhotoStats
import com.tvphoto.domain.SubFolder
import com.tvphoto.domain.TimelineMonth
import com.tvphoto.domain.TimelineYear
import com.tvphoto.domain.appendNewItems
import com.tvphoto.domain.changedMonthIds
import com.tvphoto.domain.pruneRemovedItems
import com.tvphoto.domain.timelineYears
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/** Everything the gallery screen needs for the screen currently on top. */
data class GalleryState(
    val title: String = "",
    val items: ContentState<List<MediaItem>> = ContentState.Loading,
    val subFolders: List<SubFolder> = emptyList(),
    val loadingMore: Boolean = false,
    val canLoadMore: Boolean = true,
    /**
     * Ids this screen appended from a re-check rather than loading with the page.
     *
     * They are the newest photos in the album by construction, which is what lets a
     * re-read judge them even when the page is too short a window to reach the tail of
     * the list — see `pruneRemovedItems`.
     */
    val arrivals: Set<Long> = emptySet(),
)

/**
 * What one re-read of a running slideshow's list changed.
 *
 * Both counts, rather than a net figure, because they say different things to the
 * people watching: arrivals are what a party album is for, departures are the host
 * tidying up behind them.
 */
data class SlideshowDelta(val added: Int, val removed: Int) {
    companion object {
        val NONE = SlideshowDelta(0, 0)
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val container: AppContainer = (application as TvPhotoApp).container
    private val monthFormatter = monthTitleFormatter(Locale.getDefault())

    val sessionState: StateFlow<SessionState> = container.sessions.state

    /**
     * True while the start-up sign-in with the credentials already on the device is
     * still running.
     *
     * The UI shows a loading screen for as long as it is, because the alternative is
     * what the login form used to do: compose for a moment, grab focus, ask the system
     * for the on-screen keyboard, and then be thrown away the instant the sign-in
     * lands. On a TV that flash of a form nobody asked for is the whole complaint —
     * and it is also a second, pointless screen for the app to build at the exact
     * moment it is busiest.
     */
    private val _restoringSession = MutableStateFlow(container.settings.hasSavedCredentials)
    val restoringSession: StateFlow<Boolean> = _restoringSession.asStateFlow()

    /**
     * True while a token that went stale is being replaced behind the user's back.
     *
     * The second half of "the 401 a television shows after being left alone, and the retry
     * that has to work": this is what the app covers with a loading screen while it logs
     * in again, instead of flashing a login form at somebody who never signed out.
     */
    val recoveringSession: StateFlow<Boolean> = container.sessions.recovering

    // ------------------------------------------------------------ navigation
    private val _stack = MutableStateFlow<List<Screen>>(listOf(Screen.Home))
    val stack: StateFlow<List<Screen>> = _stack.asStateFlow()

    private val _section = MutableStateFlow(HomeSection.Timeline)
    val section: StateFlow<HomeSection> = _section.asStateFlow()

    // ---------------------------------------------------------- home content
    private val _timeline = MutableStateFlow<ContentState<List<TimelineYear>>>(ContentState.Loading)
    val timeline: StateFlow<ContentState<List<TimelineYear>>> = _timeline.asStateFlow()

    private val _monthCovers = mutableStateMapOf<String, String>()

    /**
     * The cover image of each month that has been seen, keyed by [TimelineMonth.id].
     *
     * A snapshot state map rather than a `StateFlow<Map>` on purpose: covers arrive one
     * at a time while the user is moving through the timeline, and replacing an
     * immutable map on every arrival recomposed the entire section — every row, every
     * card and every thumbnail request in it — for a single URL. Each card reads its
     * own entry out of this map through a `derivedStateOf` instead, so an arriving
     * cover recomposes the one card it belongs to.
     */
    val monthCovers: Map<String, String> = _monthCovers

    private val _albums = MutableStateFlow<ContentState<List<AlbumItem>>>(ContentState.Loading)
    val albums: StateFlow<ContentState<List<AlbumItem>>> = _albums.asStateFlow()

    /**
     * Albums other accounts shared with this one. An empty list is a normal state —
     * nobody has shared anything yet — so it is not treated as a failure.
     */
    private val _sharedAlbums = MutableStateFlow<ContentState<List<AlbumItem>>>(ContentState.Loading)
    val sharedAlbums: StateFlow<ContentState<List<AlbumItem>>> = _sharedAlbums.asStateFlow()

    private val _people = MutableStateFlow<ContentState<List<Person>>>(ContentState.Loading)
    val people: StateFlow<ContentState<List<Person>>> = _people.asStateFlow()

    private val _folders = MutableStateFlow<ContentState<List<FolderItem>>>(ContentState.Loading)
    val folders: StateFlow<ContentState<List<FolderItem>>> = _folders.asStateFlow()

    private val _stats = MutableStateFlow<PhotoStats?>(null)
    val stats: StateFlow<PhotoStats?> = _stats.asStateFlow()

    // -------------------------------------------------------- gallery screen
    private val _gallery = MutableStateFlow(GalleryState())
    val gallery: StateFlow<GalleryState> = _gallery.asStateFlow()

    // --------------------------------------------------------------- settings
    private val _slideshowSeconds = MutableStateFlow(container.settings.slideshowSeconds)
    val slideshowSeconds: StateFlow<Int> = _slideshowSeconds.asStateFlow()

    private val _previewOriginal = MutableStateFlow(container.settings.previewOriginal)
    val previewOriginal: StateFlow<PreviewOriginal> = _previewOriginal.asStateFlow()

    private val _slideshowShuffled = MutableStateFlow(container.settings.slideshowShuffled)
    val slideshowShuffled: StateFlow<Boolean> = _slideshowShuffled.asStateFlow()

    /** How the 文件夹 section lays its folders out; toggled from that section's header. */
    private val _folderLayout = MutableStateFlow(container.settings.folderLayout)
    val folderLayout: StateFlow<FolderLayout> = _folderLayout.asStateFlow()

    fun toggleFolderLayout() {
        container.settings.folderLayout = container.settings.folderLayout.toggled()
        _folderLayout.value = container.settings.folderLayout
    }

    /**
     * The tracks that play under the slideshow, in playback order; empty means none.
     *
     * The whole app's colour scheme, likewise. Both are read at start-up rather than
     * defaulted so that a preference set on one launch is still in force on the next.
     */
    private val _slideshowMusic = MutableStateFlow(container.settings.slideshowMusic)
    val slideshowMusic: StateFlow<List<String>> = _slideshowMusic.asStateFlow()

    private val _themeMode = MutableStateFlow(container.settings.themeMode)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    // ------------------------------------------------------------- 屏保相册
    //
    // The album that plays itself when the television has been left alone, and the clock that
    // decides when "left alone" has passed. See `Screen.ScreenSaverAlbum` for the screen and
    // `ScreenSaver` for the two rules, and `AppRoot` for the timer: the timer is a composable
    // effect because that is what makes it stop when the app is not on screen.

    /**
     * The screensaver album's id, mirrored from the store so the album cards can mark
     * themselves and the settings row can name it.
     *
     * A flow rather than a live read like [savedHost], because this one changes *while the
     * screen is up*: the long-press menu writes it, and the card that was long-pressed has to
     * gain its badge without the grid being rebuilt.
     */
    private val _screenSaverAlbumId = MutableStateFlow(container.settings.screenSaverAlbumId)
    val screenSaverAlbumId: StateFlow<String?> = _screenSaverAlbumId.asStateFlow()

    /**
     * The name stored beside that id. Read live: nothing on screen changes when it is written
     * except the settings row, and that row is recomposed by the id's flow anyway.
     */
    val screenSaverAlbumName: String get() = container.settings.screenSaverAlbumName

    /** The album the timer should open, or null when none is set. */
    private val _screenSaverTarget = MutableStateFlow<AlbumItem?>(null)
    val screenSaverTarget: StateFlow<AlbumItem?> = _screenSaverTarget.asStateFlow()

    /**
     * True from the moment the screensaver is triggered until the user comes back.
     *
     * Not the same thing as "the screensaver screen is on the stack": the screen is pushed
     * only once its album has been read from the NAS, and until then this is what stops the
     * timer from firing again on every recomposition of the root.
     */
    private var screenSaverOpening = false

    /**
     * What `_gallery` held before the screensaver replaced it.
     *
     * Kept for the one window in which it matters: the screensaver is triggered, and the user
     * presses a key before the pushed screen has drawn anything. Then nothing of the
     * screensaver was ever on screen and what should be shown is what was there before.
     */
    private var screenSaverGalleryBackup: GalleryState? = null

    /** The album currently playing itself, for the cache entry it has to clean up after. */
    private var screenSaverPlaying: AlbumItem? = null

    /** Whether a screensaver album is set, which is what the root's timer waits on. */
    val screenSaverSet: Boolean get() = !container.settings.screenSaverAlbumId.isNullOrBlank()

    /**
     * Whether the screensaver is the thing on screen right now.
     *
     * Asked by [openScreenSaver]'s caller — the root's timer — and by anything that has to
     * know that the viewer in front of it is playing somebody's album rather than showing a
     * photo they asked for. The marker is the album screen, which is pushed first and stays
     * under the viewer for as long as the screensaver lasts.
     */
    val screenSaverActive: Boolean
        get() = _stack.value.any { it is Screen.ScreenSaverAlbum }

    /**
     * 设为屏保相册: this album plays itself when nobody has touched the remote for a while.
     *
     * The name is stored beside the id rather than looked up later, so that the settings row
     * can say which album it is before any album list has been fetched — see the note on the
     * preference. A blank name falls back to the word the grid uses for it, or the row would
     * read as unset while an album was in fact set.
     */
    fun setScreenSaverAlbum(album: AlbumItem) {
        container.settings.screenSaverAlbumId = album.id.toString()
        container.settings.screenSaverAlbumName =
            album.name.ifBlank { getApplication<Application>().getString(R.string.album_untitled) }
        _screenSaverAlbumId.value = container.settings.screenSaverAlbumId
        // The object is set here rather than left for [resolveScreenSaverAlbum], which has
        // already run: the album in hand *is* the one to play, and waiting for a resolution
        // that has been done would leave the timer holding nothing while the settings row said
        // an album was set.
        screenSaverResolved = true
        _screenSaverTarget.value = album
    }

    /**
     * 取消屏保相册: nothing plays itself any more.
     *
     * The name goes with the id — a row that kept the last album's name beside no album would
     * read as still being set, and the next screensaver would have to be picked from a list
     * whose tick was nowhere.
     */
    fun clearScreenSaverAlbum() {
        container.settings.screenSaverAlbumId = null
        container.settings.screenSaverAlbumName = ""
        _screenSaverAlbumId.value = container.settings.screenSaverAlbumId
        // Nothing to resolve any more, and nothing for the timer to open. The flag is cleared
        // with the value so that re-setting an album later goes through the lookup again rather
        // than trusting an object that is no longer stored.
        screenSaverResolved = false
        _screenSaverTarget.value = null
    }

    /**
     * Starts the screensaver: reads the album's first page, then puts it on screen.
     *
     * The load happens *before* the screen is pushed, and that is the whole reason this is not
     * simply `push`. A viewer with an empty list draws an empty screen; pushing first would put
     * one on a television in a dark room for as long as the NAS took to answer, and if the
     * answer never came it would stay there until somebody picked up the remote to investigate
     * something they never asked for. Loading first means the failure mode of an unreachable
     * NAS is "nothing happened", which is the right one for a feature nobody is watching.
     *
     * Two screens, not one: the album grid is pushed first and then the viewer, because the
     * viewer takes an *index* and a show should not open on a video — the rotation walks past
     * clips, and opening on one would put a playing film on screen with nobody watching it
     * until the first interval had passed. The grid under it is also what `galleryScreen()`
     * answers while the viewer is up, so the party-mode re-read and the next-page request both
     * go on asking for the right album.
     *
     * Deliberately not cached for reuse, unlike the grids: every trigger re-reads the album.
     * What a screensaver is *for* is a slideshow of what is in the album now, and the cache
     * would otherwise hold the list from the evening the album was chosen — on a television
     * that has been on for days.
     */
    fun openScreenSaver(target: AlbumItem) {
        if (screenSaverOpening || screenSaverActive) return
        screenSaverOpening = true
        val screen = Screen.ScreenSaverAlbum(target)
        viewModelScope.launch {
            try {
                val page = runCatching { loadPage(screen, offset = 0, limit = PAGE_SIZE) }.getOrNull()
                val first = page?.items?.indexOfFirst { !it.isVideo } ?: -1
                if (page == null || first < 0) {
                    // Nothing to play: the album is empty, or it holds nothing but clips, or the
                    // NAS is off. Either way there is no slideshow, and clearing the flag is what
                    // lets the next idle period try again rather than leaving the feature
                    // switched off for the session.
                    screenSaverOpening = false
                    return@launch
                }
                screenSaverGalleryBackup = _gallery.value
                screenSaverPlaying = target
                val state = GalleryState(
                    title = target.name,
                    items = ContentState.Ready(page.items),
                    canLoadMore = page.hasMore,
                )
                galleryCache[screen.cacheKey] = state
                _gallery.value = state
                // The title is the album's rather than the viewer's own, so the information
                // band — which the screensaver never draws — cannot disagree with the screen.
                _stack.value = _stack.value + screen + Screen.Viewer(first, target.name)
            } catch (t: Throwable) {
                screenSaverOpening = false
                Log.w(TAG, "屏保相册 could not be opened", t)
            }
        }
    }

    /**
     * Leaves the screensaver, which any key press or touch does.
     *
     * Pops the viewer and the album screen together, so one press anywhere lands back exactly
     * where the user was — and restores the gallery the screensaver took over, but only while
     * nothing of it was ever shown (the user pressed a key inside the load's own moment). Once
     * a photo has been on screen the screensaver did what it was for, and putting the previous
     * list back would replace what is being looked at with a grid nobody asked for.
     */
    fun stopScreenSaver() {
        screenSaverOpening = false
        val playing = screenSaverPlaying
        val backup = screenSaverGalleryBackup
        screenSaverPlaying = null
        screenSaverGalleryBackup = null
        // The screensaver pushes the album screen and then the viewer over it, so everything
        // from the marker onwards is the screensaver — and `indexOfLast` rather than `first`,
        // because a screensaver that was somehow opened twice must still leave the stack where
        // it found it in one press.
        val marker = _stack.value.indexOfLast { it is Screen.ScreenSaverAlbum }
            .takeIf { it >= 0 } ?: return
        val viewer = _stack.value.getOrNull(marker + 1)
        if (viewer != null) {
            // The viewer the screensaver pushed is dropped with its marker, and so is the
            // position it remembered — index 0 of somebody's album is not where the user left
            // the grid they are about to be shown again.
            galleryCache.remove(viewer.cacheKey)
            positions.remove(viewer.cacheKey)
        }
        if (playing != null) galleryCache.remove(Screen.ScreenSaverAlbum(playing).cacheKey)
        // Only while nothing of the screensaver was ever drawn: `_gallery` still being
        // item-less means the load's own moment is where the user pressed the key, and what
        // should be on screen is what was there before. Once a photo has been shown the
        // screensaver did what it was for, and putting the previous list back would replace
        // what is being looked at with a grid nobody asked for.
        if (backup != null && _gallery.value.items.valueOrNull == null) _gallery.value = backup
        _stack.value = _stack.value.take(marker)
    }

    private val _lastActivityAt = MutableStateFlow(System.currentTimeMillis())

    /**
     * The clock the screensaver's timer measures from: when the user last touched anything.
     *
     * A `StateFlow` rather than a bare long because `AppRoot` has to *restart* its delay when a
     * key arrives. Reading it there with `collectAsStateWithLifecycle` is what confines the
     * recomposition that every key press causes to the root composable — the app originally
     * kept this in `remember` state at the top of `AppRoot`, where each D-pad press recomposed
     * the whole signed-in tree: the rail, the section, and every card in it.
     */
    val lastActivityAt: StateFlow<Long> = _lastActivityAt.asStateFlow()

    /**
     * Records that the user did something, which restarts the idle clock.
     *
     * Called from the root for every key and every touch — not from the screens' own handlers,
     * which do not all see a key (a focus change caused by the D-pad is not an event anybody
     * here handles) and would each have to remember to call it.
     */
    fun recordActivity() {
        _lastActivityAt.value = System.currentTimeMillis()
    }

    private val _signMode = MutableStateFlow(container.signMode)
    val signMode: StateFlow<SignMode> = _signMode.asStateFlow()

    // ----------------------------------------------------------------- 图评

    /**
     * The review layer's settings, mirrored here so the settings screen and the viewer read
     * one source.
     *
     * The server address is a plain getter rather than a flow: it is typed on a phone
     * keyboard once per install, and there is nothing that can change it while the app is
     * on screen.
     */
    val reviewServerUrl: String get() = container.reviewSettings.serverUrl

    private val _reviewMode = MutableStateFlow(container.reviewSettings.mode)
    val reviewMode: StateFlow<ReviewMode> = _reviewMode.asStateFlow()

    private val _danmakuLevel = MutableStateFlow(container.reviewSettings.danmakuLevel)
    val danmakuLevel: StateFlow<DanmakuLevel> = _danmakuLevel.asStateFlow()

    private val _extendStayOnComment = MutableStateFlow(container.reviewSettings.extendStayOnComment)
    val extendStayOnComment: StateFlow<Boolean> = _extendStayOnComment.asStateFlow()

    private val _reviewSlideshowEnabled = MutableStateFlow(container.reviewSettings.slideshowEnabled)
    val reviewSlideshowEnabled: StateFlow<Boolean> = _reviewSlideshowEnabled.asStateFlow()

    /**
     * What the review layer is doing, straight from the repository.
     *
     * Not mirrored into a local `MutableStateFlow`: a late registration publishes its
     * answer minutes after the call that started it, and a mirror that only updated when
     * *this* object was poked would leave the QR code off the screen it belongs on.
     */
    val reviewStatus: StateFlow<ReviewStatus> get() = container.review.status

    val reviewView: StateFlow<ReviewView> get() = container.review.view

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /**
     * When the last check was started, so that walking past the settings screen cannot
     * turn into a request every time. In memory only: what it guards against is a user
     * moving around one session, and a restart is reason enough to ask again.
     */
    private var lastUpdateCheckAt = 0L

    // Read live rather than snapshotted at construction: after a successful sign-in
    // the credentials have just been persisted, and the login screen must prefill
    // them when the user signs out again in the same session.
    val savedHost: String get() = container.settings.host
    val savedUser: String get() = container.settings.userName
    val savedPassword: String get() = container.settings.password
    val savedAccessCode: String get() = container.settings.accessCode

    /**
     * Remembered logins for the login screen's shortcuts, most recent first.
     *
     * Read live rather than snapshotted, like the single set above: after a successful
     * sign-in the list has just changed, and signing out again in the same session has
     * to show the account that was just used.
     */
    val savedAccounts: List<SavedAccount> get() = container.settings.savedAccounts

    /**
     * The saved login matching what is currently typed, so switching to another account
     * on the same NAS brings that account's password with it instead of leaving the
     * previous one's in the field.
     */
    fun savedAccountFor(host: String, user: String): SavedAccount? =
        accountFor(container.settings.savedAccounts, host, user)

    private val galleryCache = mutableMapOf<String, GalleryState>()
    private val previewRequests = mutableSetOf<String>()

    /**
     * Whether the stored screensaver album has been looked up in an album list yet.
     *
     * A stored id is a number; a screensaver needs the album's *name* for the slideshow's
     * title, so the id has to be resolved against `albums()` once. It is done here rather than
     * in the root's timer because the list it needs is the one the 相册 section already loads,
     * and asking for it twice would be two requests for the same list.
     */
    private var screenSaverResolved = false

    /**
     * Where the user last left each list or grid, keyed by [PositionKeys] or [Screen.cacheKey].
     *
     * Declared above the `init` block on purpose, and the placement is load-bearing:
     * `resetContent` clears this map, and `resetContent` can run *during construction*.
     * `viewModelScope` is `Dispatchers.Main.immediate`, so on a cold start with a stored
     * login the session collector below runs its first emission eagerly — `SignedIn` is
     * already the current value — and everything it touches has to be initialised by then.
     * With this declared beside the functions that use it (further down the class body) a
     * cold start died on launch with `NullPointerException: Map.clear() on a null object
     * reference`, which is the one crash every user would hit.
     */
    private val positions = mutableMapOf<String, Int>()

    /**
     * Section lists with a load in flight. Only ever touched on the main thread, which
     * is where `selectSection` and `viewModelScope` both run.
     */
    private val loadingSections = mutableSetOf<Any>()

    /** Grids with a re-check in flight, keyed by [Screen.cacheKey]. Main thread only. */
    private val galleryRefreshing = mutableSetOf<String>()
    private var loadedToken: String? = null

    init {
        viewModelScope.launch {
            container.sessions.state.collect { state ->
                when (state) {
                    is SessionState.SignedIn -> if (state.session.token != loadedToken) {
                        loadedToken = state.session.token
                        resetContent()
                        loadTimeline()
                        loadStats()
                    }

                    SessionState.SignedOut -> loadedToken = null
                    else -> Unit
                }
            }
        }

        // The music library, and the one rule that needs both halves to keep: the stored
        // selection is pruned against the files that are actually there. A preference can
        // name an upload somebody deleted with a file manager, or a track on a USB stick
        // that is currently unplugged; the first must stop playing, and only something that
        // can look at the storage is allowed to decide which is which.
        container.musicLibrary.refresh()
        viewModelScope.launch {
            container.musicLibrary.state.collect { library ->
                val pruned = normalizeMusicSelection(
                    container.settings.slideshowMusic,
                    library.availableUploadIds,
                )
                if (pruned != container.settings.slideshowMusic) {
                    container.settings.slideshowMusic = pruned
                    _slideshowMusic.value = container.settings.slideshowMusic
                }
            }
        }

        // Sign straight in when credentials are already stored, so a TV user with a
        // remote never has to type unless the NAS is unreachable or the password
        // changed. The login screen still appears if this fails.
        if (container.settings.hasSavedCredentials) {
            viewModelScope.launch {
                try {
                    container.sessions.signIn(
                        address = container.settings.host,
                        userName = container.settings.userName,
                        password = container.settings.password,
                        accessCode = container.settings.accessCode,
                    )
                } catch (t: Throwable) {
                    // `signIn` already reports ordinary failures through `sessionState`;
                    // this only exists so that anything it does *not* catch — an `Error`
                    // from the crypto or TLS stack, say — cannot take the process down
                    // on a launch the user never asked for anything unusual from.
                    Log.w(TAG, "start-up sign-in failed", t)
                } finally {
                    // Whatever happened, stop covering the UI: a failure leaves the
                    // login screen with the server's own message on it.
                    _restoringSession.value = false
                }
            }
        }

        // The screensaver album's name, resolved against the album list the moment the app can
        // ask for one. It has to happen without anybody opening 相册, because the timer that
        // opens the screensaver is armed whether or not that section has ever been visited.
        if (container.settings.hasSavedCredentials) {
            loadAlbums()
        }

        // Ask the release page what the newest build is, once, as soon as the app is up.
        //
        // It has to happen without anybody opening 设置, because the answer is drawn *on*
        // the 设置 entry of the navigation rail — a red dot saying there is something to
        // install — and a dot that only appears after the page it points at has been
        // visited tells nobody anything. The request goes to Gitee and GitHub, never the
        // NAS: a 401 from a stale session is not a reason for it to fail, and its failure
        // is not a reason to hold up anything else.
        autoCheckForUpdate()
    }

    // ------------------------------------------------------------------ auth

    /**
     * Gives up on the start-up sign-in and shows the login form.
     *
     * The attempt itself is left to finish — it may still succeed, and a second one
     * would only race it — but the user is no longer held on a loading screen they
     * cannot get out of with a remote.
     */
    fun cancelAutoSignIn() {
        _restoringSession.value = false
    }

    fun signIn(
        address: String,
        userName: String,
        password: String,
        accessCode: String = "",
    ) {
        viewModelScope.launch {
            container.sessions.signIn(address, userName, password, accessCode)
        }
    }

    fun signOut() {
        container.sessions.signOut()
        _stack.value = listOf(Screen.Home)
        _section.value = HomeSection.Timeline
        resetContent()
    }

    // ------------------------------------------------------------ navigation

    fun selectSection(section: HomeSection) {
        _section.value = section
        // Entering a section re-checks it. A list that was cached as Ready would
        // otherwise never change again for the rest of the session, so an album someone
        // shares with this account while the app sits on another section — or a photo
        // uploaded to the NAS — would not appear until the app restarted.
        when (section) {
            HomeSection.Timeline -> loadTimeline(force = true)
            HomeSection.Albums -> loadAlbums(force = true)
            HomeSection.Shared -> loadSharedAlbums(force = true)
            HomeSection.People -> loadPeople(force = true)
            HomeSection.Folders -> loadFolders(force = true)
            HomeSection.Settings -> Unit
        }
    }

    fun openMonth(month: TimelineMonth) = push(Screen.MonthPhotos(month))

    fun openAlbum(album: AlbumItem) = push(Screen.AlbumPhotos(album))

    fun openPerson(person: Person) = push(Screen.PersonPhotos(person))

    fun openFolder(path: String, name: String) = push(Screen.FolderBrowse(path, name))

    fun openViewer(index: Int, title: String) {
        _stack.value = _stack.value + Screen.Viewer(index, title)
    }

    /** Returns false when the stack is already at the root, so the UI can exit. */
    fun back(): Boolean {
        val current = _stack.value
        if (current.size <= 1) return false
        _stack.value = current.dropLast(1)
        return true
    }

    private fun push(screen: Screen) {
        _stack.value = _stack.value + screen
        when (screen) {
            is Screen.MonthPhotos, is Screen.AlbumPhotos, is Screen.PersonPhotos,
            is Screen.FolderBrowse,
            -> loadGallery(screen)
            else -> Unit
        }
    }

    // ----------------------------------------------------------- home loaders

    /**
     * Loads one of the home sections' lists.
     *
     * [force] fetches even when a list is already on screen, which is what entering a
     * section does — see [selectSection].
     *
     * Whatever is already loaded deliberately stays put while a fetch runs, and stays
     * put if that fetch fails. Re-entering a section must not blank a list that is
     * probably still correct, nor replace it with an error because the NAS was briefly
     * busy; loading and failure are only shown when there is nothing else to display.
     */
    private fun <T> loadSection(
        target: MutableStateFlow<ContentState<List<T>>>,
        force: Boolean,
        fetch: suspend () -> List<T>,
        onLoaded: (List<T>) -> Unit = {},
    ) {
        if (!force && target.value is ContentState.Ready) return
        // One load per section at a time, so moving along the rail cannot stack
        // duplicate requests for the same list.
        if (!loadingSections.add(target)) return
        if (target.value !is ContentState.Ready) target.value = ContentState.Loading

        viewModelScope.launch {
            try {
                runCatching { fetch() }
                    .onSuccess {
                        // Before the swap, so the hook can still compare against what
                        // was on screen a moment ago.
                        onLoaded(it)
                        target.value = ContentState.Ready(it)
                    }
                    .onFailure {
                        if (target.value !is ContentState.Ready) {
                            target.value = ContentState.Failed(it.friendlyMessage())
                        }
                    }
            } finally {
                loadingSections.remove(target)
            }
        }
    }

    fun loadTimeline(force: Boolean = false) = loadSection(
        target = _timeline,
        force = force,
        fetch = { timelineYears(container.photos.months()) },
        onLoaded = ::forgetChangedMonthCovers,
    )

    fun loadAlbums(force: Boolean = false) =
        loadSection(
            target = _albums,
            force = force,
            fetch = { container.photos.albums() },
            onLoaded = ::resolveScreenSaverAlbum,
        )

    /**
     * Looks the stored screensaver album up in a freshly-read album list, once.
     *
     * `onLoaded` of [loadAlbums], which means the list is already in hand and resolving costs
     * nothing — the alternative was a request of its own at start-up for a feature that may
     * never be used. A stored id that no longer appears is left unresolved and retried on the
     * next album list, rather than cleared: an album that is momentarily missing from a list —
     * a share that is being re-granted, a NAS that answered the request badly — must not empty
     * a setting the user made.
     */
    private fun resolveScreenSaverAlbum(albums: List<AlbumItem>) {
        if (screenSaverResolved) return
        val id = container.settings.screenSaverAlbumId ?: return
        val album = albums.firstOrNull { it.id.toString() == id } ?: return
        screenSaverResolved = true
        _screenSaverTarget.value = album
        // The stored name is what the settings row shows, and it is what the user typed their
        // way to when they set it — so it is only refreshed when there is nothing there at all.
        if (container.settings.screenSaverAlbumName.isBlank()) {
            container.settings.screenSaverAlbumName = album.name
        }
    }

    /** Albums other accounts have shared with this one; empty is a normal state. */
    fun loadSharedAlbums(force: Boolean = false) =
        loadSection(_sharedAlbums, force, fetch = { container.photos.sharedAlbumsToMe() })

    fun loadFolders(force: Boolean = false) =
        loadSection(_folders, force, fetch = { container.photos.managedFolders() })

    /** Face clusters. An empty list is normal: it means AI indexing is off on the NAS. */
    fun loadPeople(force: Boolean = false) =
        loadSection(_people, force, fetch = { container.photos.people() })

    private fun loadStats() {
        viewModelScope.launch {
            runCatching { container.photos.stats() }.onSuccess { _stats.value = it }
        }
    }

    /**
     * Drops the cached cover of any month whose item count moved, so a month that has
     * gained photos fetches a new cover instead of keeping the old first photo.
     *
     * Only the months that actually changed are dropped: re-fetching every cover on
     * every entry would be a dozen requests for thumbnails nobody asked about.
     */
    private fun forgetChangedMonthCovers(fresh: List<TimelineYear>) {
        val previous = _timeline.value.valueOrNull ?: return
        val changed = changedMonthIds(previous, fresh)
        if (changed.isEmpty()) return
        _monthCovers.keys.removeAll(changed)
        synchronized(previewRequests) { previewRequests.removeAll(changed) }
    }

    /**
     * Fetches the cover image for one month card, so the timeline is not a wall of
     * blank cards. Only the first item is asked for: the card shows a single photo,
     * and the order matches what the month's grid opens on.
     *
     * Requests are de-duplicated, and failures are forgotten so that scrolling back
     * can retry.
     */
    fun requestMonthCover(month: TimelineMonth) {
        if (monthCovers.containsKey(month.id)) return
        synchronized(previewRequests) { if (!previewRequests.add(month.id)) return }
        viewModelScope.launch {
            runCatching { container.photos.photosInMonth(month, limit = 1, offset = 0) }
                .onSuccess { page ->
                    val cover = page.items.firstOrNull()?.thumbnailUrl
                    if (cover != null) {
                        _monthCovers[month.id] = cover
                    } else {
                        // Nothing to show, but do not ask again on every recomposition.
                        synchronized(previewRequests) { previewRequests.remove(month.id) }
                    }
                }
                .onFailure { synchronized(previewRequests) { previewRequests.remove(month.id) } }
        }
    }

    // -------------------------------------------------------- gallery loaders

    private fun loadGallery(screen: Screen, force: Boolean = false) {
        val key = screen.cacheKey
        val cached = galleryCache[key]
        if (!force && cached != null) {
            // Show what was loaded last time straight away, then re-check behind it.
            // Entering a grid is also "show me what is in there now", so photos
            // uploaded since the last visit have to be picked up here too.
            _gallery.value = cached
            revalidateGallery(screen)
            return
        }
        val title = screen.displayTitle(monthFormatter)
        _gallery.value = GalleryState(title = title)

        viewModelScope.launch {
            runCatching {
                val page = loadPage(screen, 0, PAGE_SIZE)
                val subFolders = if (screen is Screen.FolderBrowse) {
                    runCatching { container.photos.subFolders(screen.path) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                GalleryState(
                    title = title,
                    items = ContentState.Ready(page.items),
                    subFolders = subFolders,
                    canLoadMore = page.hasMore,
                )
            }
                .onSuccess {
                    galleryCache[key] = it
                    _gallery.value = it
                }
                .onFailure {
                    // Deliberately not cached: a failed screen should retry on re-entry.
                    _gallery.value = GalleryState(
                        title = title,
                        items = ContentState.Failed(it.friendlyMessage()),
                    )
                }
        }
    }

    fun retryGallery() {
        _stack.value.lastOrNull()?.let { loadGallery(it, force = true) }
    }

    /**
     * The screen whose photos the gallery holds: the topmost one that is not the
     * viewer. While the viewer is open the gallery below it is still the list being
     * displayed, and it is the one that has to be refreshed.
     */
    private fun galleryScreen(): Screen? = _stack.value.lastOrNull { it !is Screen.Viewer }

    /**
     * Re-reads a grid in the background without disturbing what it is showing.
     *
     * Entering a grid is treated like entering a section: show the cached list at once,
     * then fold in whatever has appeared since. Guarded per screen so going back and
     * forth cannot stack requests.
     */
    private fun revalidateGallery(screen: Screen) {
        if (!galleryRefreshing.add(screen.cacheKey)) return
        viewModelScope.launch {
            try {
                refreshGalleryItems(screen)
            } finally {
                galleryRefreshing.remove(screen.cacheKey)
            }
        }
    }

    /**
     * Re-reads the first page of [screen] and folds it into what is already loaded.
     *
     * Used by [revalidateGallery] on entering a grid, and by the slideshow's party mode
     * so photos uploaded to the album while it plays join the rotation — and photos
     * deleted from it leave the rotation. See [pruneRemovedItems] for what a single
     * page can and cannot tell about a deletion.
     *
     * Suspending rather than launching internally, like [warmImageCache], so the
     * caller's job owns the work and leaving the screen cancels it.
     */
    private suspend fun refreshGalleryItems(screen: Screen): SlideshowDelta {
        val current = _gallery.value
        val loaded = current.items.valueOrNull ?: return SlideshowDelta.NONE
        val fresh = runCatching { loadPage(screen, offset = 0, limit = PAGE_SIZE) }.getOrNull()
            ?: return SlideshowDelta.NONE

        val kept = pruneRemovedItems(loaded, fresh, current.arrivals)
        val mergedItems = appendNewItems(kept, fresh.items)
        // Both helpers hand back the very same list when nothing moved, which is what
        // keeps an idle poll from recomposing the slideshow.
        if (mergedItems === loaded) return SlideshowDelta.NONE

        // The user may have navigated while this was in flight, and `_gallery` is shared
        // by every grid — writing now would replace another screen's list.
        if (galleryScreen() != screen) return SlideshowDelta.NONE

        val keptIds = kept.mapTo(HashSet(kept.size)) { it.id }
        val addedIds = mergedItems.filterNot { it.id in keptIds }.mapTo(HashSet()) { it.id }

        publishGallery(
            screen,
            current.copy(
                items = ContentState.Ready(mergedItems),
                // Forget arrivals that have left the list, so a long-running show does
                // not accumulate ids nothing can match any more.
                arrivals = current.arrivals.filterNot { it !in keptIds }.toSet() + addedIds,
            ),
        )
        return SlideshowDelta(
            added = addedIds.size,
            removed = loaded.size - kept.size,
        )
    }

    /** Re-reads the list behind the viewer; see [refreshGalleryItems]. */
    suspend fun refreshSlideshowItems(): SlideshowDelta =
        galleryScreen()?.let { refreshGalleryItems(it) } ?: SlideshowDelta.NONE

    fun loadMore() {
        val screen = galleryScreen() ?: return
        val current = _gallery.value
        val loaded = current.items.valueOrNull ?: return
        if (current.loadingMore || !current.canLoadMore) return

        // The cache entry is this screen's state while it is on top, and unlike
        // `_gallery` it stays with the screen if the user navigates away mid-request.
        val pending = current.copy(loadingMore = true)
        galleryCache[screen.cacheKey] = pending
        _gallery.value = pending

        viewModelScope.launch {
            runCatching { loadPage(screen, loaded.size, PAGE_SIZE) }
                .onSuccess { page ->
                    val base = galleryCache[screen.cacheKey] ?: pending
                    val baseItems = base.items.valueOrNull ?: loaded
                    // Merged into the cached copy rather than the snapshot taken above:
                    // a slideshow refresh or a re-check may have appended while this
                    // page was in flight.
                    val mergedItems = appendNewItems(baseItems, page.items)
                    publishGallery(
                        screen,
                        base.copy(
                            items = ContentState.Ready(mergedItems),
                            loadingMore = false,
                            // A page that added nothing means the server's offset has
                            // shifted past what we have; asking again would loop on the
                            // same items forever.
                            canLoadMore = page.hasMore && page.items.isNotEmpty() &&
                                mergedItems.size > baseItems.size,
                        ),
                    )
                }
                .onFailure {
                    val base = galleryCache[screen.cacheKey] ?: pending
                    publishGallery(screen, base.copy(loadingMore = false))
                }
        }
    }

    /**
     * Stores a grid's state and, if that grid is still the one on screen, shows it.
     *
     * `_gallery` is shared by every grid, so publishing a late result unconditionally
     * would replace whatever the user moved on to.
     */
    private fun publishGallery(screen: Screen, state: GalleryState) {
        galleryCache[screen.cacheKey] = state
        if (galleryScreen() == screen) _gallery.value = state
    }

    /**
     * One page of the list a grid holds.
     *
     * The `else` is not dead code to be tidied away: [Screen.Viewer] and [Screen.Music] reach it,
     * and neither has a list of its own. [Screen.ScreenSaverAlbum] has one, and forgetting it
     * here is a silent, total failure of the 屏保相册 — the page comes back empty, the screensaver
     * decides there is nothing to play, and the only symptom is a television that never starts
     * playing. It was found by logging `page=0 firstPhoto=-1` on a device, not by reading this.
     */
    private suspend fun loadPage(screen: Screen, offset: Int, limit: Int): MediaPage = when (screen) {
        is Screen.MonthPhotos -> container.photos.photosInMonth(screen.month, limit, offset)
        is Screen.AlbumPhotos -> container.photos.albumPhotos(screen.album.id, limit, offset)
        is Screen.ScreenSaverAlbum -> container.photos.albumPhotos(screen.album.id, limit, offset)
        is Screen.PersonPhotos -> container.photos.personPhotos(screen.person.id, limit, offset)
        is Screen.FolderBrowse -> container.photos.folderFiles(screen.path, limit, offset)
        else -> MediaPage(emptyList(), hasMore = false)
    }

    // --------------------------------------------------------------- settings

    // ------------------------------------------------------- position memory

    /**
     * Where the user last left the list or grid identified by [key], or
     * [NO_POSITION] when they have never been there — which is what tells a screen
     * apart from a revisit and stops it stealing focus from the navigation rail.
     */
    fun savedIndex(key: String): Int = positions[key] ?: NO_POSITION

    fun saveIndex(key: String, index: Int) {
        positions[key] = index
    }

    fun setSlideshowSeconds(seconds: Int) {
        container.settings.slideshowSeconds = seconds
        _slideshowSeconds.value = container.settings.slideshowSeconds
    }

    /**
     * Which still full-screen previews draw, original or large thumbnail.
     *
     * Read back from the store rather than echoing what was asked for, like the music
     * selection: the setting persisted is what the UI then claims.
     */
    fun setPreviewOriginal(mode: PreviewOriginal) {
        container.settings.previewOriginal = mode
        _previewOriginal.value = container.settings.previewOriginal
    }

    /** Order the slideshow advances in. Persisted, so 随机 survives a restart. */
    fun setSlideshowShuffled(shuffled: Boolean) {
        container.settings.slideshowShuffled = shuffled
        _slideshowShuffled.value = container.settings.slideshowShuffled
    }

    /**
     * Ticks or unticks tracks for the slideshow's background music.
     *
     * Stored through [SettingsStore], which reduces the list to known tracks in
     * playback order, and read back from it so the state in the UI is what was actually
     * persisted rather than what was asked for.
     */
    fun setSlideshowMusic(assets: List<String>) {
        container.settings.slideshowMusic = assets
        _slideshowMusic.value = container.settings.slideshowMusic
    }

    fun setThemeMode(mode: ThemeMode) {
        container.settings.themeMode = mode
        _themeMode.value = container.settings.themeMode
    }

    // ------------------------------------------------------------------ music

    /** Starts the selected tracks, looping in playback order. */
    fun playSlideshowMusic() = container.slideshowMusic.play(_slideshowMusic.value)

    /** Holds the music where it is; a later [playSlideshowMusic] resumes it. */
    fun pauseSlideshowMusic() = container.slideshowMusic.pause()

    /** Ends the music outright, so a later show starts the list from the top. */
    fun stopSlideshowMusic() = container.slideshowMusic.stop()

    // ------------------------------------------------------- music library (1.5.0)

    /**
     * The tracks the slideshow can play: the four in the APK, plus what a phone uploaded
     * and what was found on this device.
     *
     * Read straight from the library rather than mirrored: an upload lands on the server's
     * own thread, and a mirror that only moved when this object was poked would leave the
     * track off the list until something else happened.
     */
    val musicLibrary: StateFlow<MusicLibraryState> get() = container.musicLibrary.state

    /** The phone-upload server, alive only while its page is on screen. */
    val musicUpload: StateFlow<MusicUploadServer.State> get() = container.musicUploads.state

    /** Opens the upload page, from the settings rows and from the viewer's track list. */
    fun openMusicUpload() {
        push(Screen.Music)
        refreshMusicLibrary()
    }

    /**
     * Moves one track by [delta] places, from the page's 排序 mode.
     *
     * The order is stored as it moves rather than on a confirmation press: the arrows are
     * the edit, and Back afterwards only leaves the mode. The page says so, and
     * `docs/custom-music-design.md` §3 records why.
     */
    fun moveMusic(id: String, delta: Int) = container.musicLibrary.moveTrack(id, delta)

    /** Puts the bundled tracks the user deleted back into the list. */
    fun restoreBuiltinMusic() = container.musicLibrary.restoreBuiltins()

    fun refreshMusicLibrary() = container.musicLibrary.refresh()

    /**
     * Removes one track from the list.
     *
     * What that means is the library's decision, not the screen's: an upload's file is
     * deleted, and the bundled track — whose bytes are inside the APK — can only be taken out
     * of the list, reversibly.
     */
    fun deleteMusicTrack(entry: MusicEntry) {
        container.musicLibrary.delete(entry)
        // The stored selection is pruned by the collector in `init`, so a deleted track
        // stops playing without this call having to guess at the new list.
    }

    fun clearMusicUploads() = container.musicLibrary.clearUploads()

    /** Opens the listener. Called when the upload page appears. */
    fun startMusicUpload() = container.musicUploads.start()

    /** Closes it. Called when the page is left. */
    fun stopMusicUpload() = container.musicUploads.stop()

    fun setSignMode(mode: SignMode) {
        container.forceSignMode(mode)
        _signMode.value = mode
    }

    // ------------------------------------------------------------------ 图评

    fun setReviewMode(mode: ReviewMode) {
        container.reviewSettings.mode = mode
        _reviewMode.value = container.reviewSettings.mode
    }

    fun setReviewServerUrl(url: String) {
        val changed = url.trim() != container.reviewSettings.serverUrl
        container.reviewSettings.serverUrl = url
        // The answers belong to the address that gave them; the *identity* may still be good.
        //
        // Every photo, comment and QR picture this repository cached came from the old
        // address and is dropped. The device id and its tokens are kept, and that is a
        // reversal worth spelling out. Dropping them was the remedy for "the new server has
        // never seen this token": `/healthz` answers, the row says 已连接, and every upload is
        // refused 设备令牌不对 — which is still exactly what a *different* server does, and the
        // repair path answers that 401 by forging a new identity ([DeviceRepair]). But a
        // deployment that moved is usually the **same** server at a new address, where the
        // token is valid and the television is already known; throwing the identity away
        // there is what made the server unable to tell "this television, new address" from a
        // stranger, and that distinction is the only thing by which a short code's address
        // can follow its owner (`store.followAddress`, docs/tuping-design.md §17.31).
        //
        // Only when the address really changed: re-saving the same address is what somebody
        // does when 图评 is not working, and that must not throw away the codes on screen.
        if (changed) container.review.onServerChanged()
        refreshReviewStatus()
    }

    fun setDanmakuLevel(level: DanmakuLevel) {
        container.reviewSettings.danmakuLevel = level
        _danmakuLevel.value = container.reviewSettings.danmakuLevel
    }

    fun setExtendStayOnComment(extend: Boolean) {
        container.reviewSettings.extendStayOnComment = extend
        _extendStayOnComment.value = container.reviewSettings.extendStayOnComment
    }

    fun setReviewSlideshowEnabled(enabled: Boolean) {
        container.reviewSettings.slideshowEnabled = enabled
        _reviewSlideshowEnabled.value = container.reviewSettings.slideshowEnabled
    }

    /**
     * Asks the review server how it is doing, for the one row that can report it.
     *
     * A television has no console, so "连不上 192.168.31.14" is the difference between a
     * mystery and a diagnosis — the same reasoning as the 上次崩溃 row.
     */
    fun refreshReviewStatus() {
        viewModelScope.launch { container.review.checkStatus() }
    }

    /**
     * What should happen about 图评 for the photo on screen — see `reviewDecision`.
     *
     * The facts this needs — is this a video, is a show running, is this the screensaver —
     * come from the viewer rather than being guessed at here. The rule itself lives in
     * `reviewDecision`, where a unit test can pin it; this is the wiring that gives it the
     * settings.
     *
     * Three answers, and the ordinary one is [ReviewDecision.LOOKUP]: ask the server whether
     * it already knows this picture, and draw whatever comes back. **Only a show whose own
     * 图评 switch is on registers a photo by itself** (docs/tuping-design.md §17.28), so on a
     * fresh install nothing is uploaded until somebody presses 图评 on a photo they want
     * comments on.
     */
    fun reviewDecisionFor(
        isVideo: Boolean,
        screenSaver: Boolean = false,
        slideshow: Boolean = false,
    ): ReviewDecision {
        val review = container.reviewSettings
        return reviewDecision(
            isVideo = isVideo,
            mode = review.mode,
            serverUrl = review.serverUrl,
            danmakuLevel = review.danmakuLevel,
            screenSaver = screenSaver,
            slideshow = slideshow,
            slideshowEnabled = review.slideshowEnabled,
        )
    }

    /**
     * Hands the viewer's current photo to the review layer, or hides it.
     *
     * [decision] is passed in rather than recomputed, and that is not tidiness: the viewer
     * keys its effect on it, so a value that changes when a switch does is what makes a
     * switch take effect on the photo *on screen* rather than only on the next one.
     * Recomputing it here from the settings would answer the same but leave the effect
     * blind to the change.
     */
    fun openReviewedPhoto(
        item: MediaItem?,
        source: String,
        decision: ReviewDecision,
        screenSaver: Boolean = false,
        slideshow: Boolean = false,
    ) {
        container.review.openPhoto(
            item = item,
            source = source,
            decision = decision,
            danmakuLevel = container.reviewSettings.danmakuLevel,
            screenSaver = screenSaver,
            slideshow = slideshow,
        )
    }

    /**
     * The explicit act: put *this* photo on the 图评 server so guests can comment on it.
     *
     * Bound to the viewer's 图评 row (docs/tuping-design.md §17.28). It is the only path in
     * the app that uploads a thumbnail outside a show that carries 图评, which is what makes
     * "no photo reaches the server until somebody asks" true rather than aspirational.
     */
    fun armReviewedPhoto(
        item: MediaItem?,
        source: String,
        screenSaver: Boolean = false,
        slideshow: Boolean = false,
    ) {
        container.review.armPhoto(
            item = item,
            source = source,
            danmakuLevel = container.reviewSettings.danmakuLevel,
            screenSaver = screenSaver,
            slideshow = slideshow,
        )
    }

    /**
     * Starts the live subscription for whatever photo was just handed over.
     *
     * A separate call from [openReviewedPhoto] on purpose: subscribing is what makes the
     * server keep a socket open, and a socket per keystroke is not what a remote-control
     * app should do.
     */
    fun startReviewedPhotoLive() {
        container.review.startLive()
    }

    fun stopReviewedPhotoLive() {
        container.review.stopLive()
    }

    /**
     * Fetches the current photo's comments without subscribing to new ones.
     *
     * The screensaver's half of [startReviewedPhotoLive]: it draws 弹幕 from whatever the
     * server already holds, and deliberately does not hold a socket open to be told about
     * comments nobody is there to read. See `ReviewRepository.refreshCommentsForCurrentPhoto`.
     */
    fun refreshReviewedPhotoComments() {
        container.review.refreshCommentsForCurrentPhoto()
    }

    /**
     * Registers the photos the viewer is about to show, like the image prefetcher does.
     *
     * **Only a show that carries 图评 may do this** (docs/tuping-design.md §17.28). It is the
     * one path by which a photo nobody pressed anything on reaches the server, and it exists
     * for the party rotation: the host decided once, in 幻灯片 settings, that the whole show
     * is commentable, and nobody is holding the remote. With that switch at its default 关,
     * this method is a no-op for every caller.
     */
    fun preRegisterReviewedPhotos(
        candidates: List<ReviewCandidate>,
        source: String,
        slideshow: Boolean = false,
    ) {
        if (!slideshow || !container.reviewSettings.slideshowEnabled) return
        if (reviewDecisionFor(isVideo = false, screenSaver = false, slideshow = true) !=
            ReviewDecision.REGISTER
        ) {
            return
        }
        container.review.preRegister(candidates, source)
    }

    /** Reads a still's bytes from the cache Coil already filled; null when it is not there. */
    suspend fun thumbnailBytesFor(url: String): ByteArray? = container.review.thumbnailBytesFor(url)

    // ------------------------------------------------------------ update check

    /**
     * The release page, for the row's manual fallback.
     *
     * Shown as text rather than opened: a television is the one device in the house that
     * usually has no browser, and this app will not pretend otherwise. It is meant to be
     * read out and typed somewhere else, like the crash summary above it.
     */
    val updateReleasePageUrl: String get() = container.updates.releasePageUrl

    /**
     * The check the settings screen runs by itself the moment it appears.
     *
     * The states a fresh check must never overwrite are the ones the user is in the
     * middle of - a download in progress, or an answer they have not acted on yet.
     *
     * Of the rest, only "already up to date" is throttled, because it is the one answer
     * that cannot change because someone looked again. Coming back to this screen after a
     * failure asks immediately: the last answer did not do, the user walked back here
     * anyway, and a download or a verification can fail precisely because the answer was
     * stale - a manifest attached after the tag went out, a truncated upload fixed an hour
     * later. Nothing is risked by asking: a file that already verified is still on disk
     * and is reused rather than fetched again (see [beginInstall]).
     */
    fun autoCheckForUpdate() {
        val state = _update.value
        val reAskable = state is UpdateState.Idle || state is UpdateState.Failed
        if (!reAskable && state !is UpdateState.UpToDate) return
        if (!reAskable && System.currentTimeMillis() - lastUpdateCheckAt < UPDATE_CHECK_INTERVAL_MS) return
        checkForUpdate()
    }

    /**
     * Asks the release page what the newest published version is.
     *
     * Refuses to start while one is in flight: on a television OK can be pressed as fast
     * as the remote allows, and two requests racing each other would let the slower,
     * older answer be the one left on screen.
     */
    fun checkForUpdate() {
        if (_update.value.isBusy) return
        lastUpdateCheckAt = System.currentTimeMillis()
        _update.value = UpdateState.Checking
        viewModelScope.launch {
            when (val result = container.updates.check()) {
                is UpdateCheckResult.Newer -> _update.value = UpdateState.Available(result.release)
                UpdateCheckResult.UpToDate ->
                    _update.value = UpdateState.UpToDate(BuildConfig.VERSION_NAME)
                is UpdateCheckResult.Failed -> {
                    // On the log *and* on the row. A television's user cannot act on a DNS
                    // failure by name, but they can read it out to somebody who can - which
                    // is exactly what the crash summary above it is for.
                    Log.w(TAG, "update check failed: ${result.reason}")
                    _update.value = UpdateState.Failed(UpdateState.Failed.Kind.CHECK, detail = result.reason)
                }
            }
        }
    }

    /**
     * What OK on the 检测升级 row means, which is wherever the last attempt left off.
     *
     * A single entry point on purpose. The row is the whole interface - there is no
     * dialog and no second button - so "press OK again" has to be the retry for a failed
     * check, a failed download and a refused installer alike, and the state is what
     * remembers which of those it is.
     */
    fun onUpdateRowClick() {
        when (val state = _update.value) {
            is UpdateState.Idle, is UpdateState.UpToDate, is UpdateState.Checking -> checkForUpdate()

            is UpdateState.Downloading -> Unit

            is UpdateState.Available -> beginInstall(state.release)

            is UpdateState.NeedsPermission ->
                if (container.apkInstaller.canInstall()) {
                    beginInstall(state.release)
                } else {
                    openInstallPermission()
                }

            is UpdateState.ReadyToInstall -> launchInstaller(state.release)

            is UpdateState.Failed -> when (state.kind) {
                UpdateState.Failed.Kind.CHECK -> checkForUpdate()
                // Everything else had a release in hand, so OK means "try that again"
                // rather than "ask GitHub from the top".
                else -> state.retry?.let { launchInstaller(it) } ?: checkForUpdate()
            }
        }
    }

    /**
     * Opens the system screen where a per-app "install unknown apps" grant is given.
     *
     * Android does not hand that permission out through a dialog the app can draw, and it
     * is not a runtime permission, so this is the only route. The user comes back to this
     * row and presses OK again; [UpdateState.NeedsPermission] is holding the release so
     * that press continues the install instead of repeating the check.
     */
    fun openInstallPermission() {
        runCatching { getApplication<Application>().startActivity(container.apkInstaller.installPermissionIntent()) }
            .onFailure { Log.w(TAG, "no system screen for install permissions", it) }
    }

    /** Downloads [release], proves it, and hands it to the installer. */
    private fun beginInstall(release: UpdateRelease) {
        if (!release.isInstallable) {
            // Known only by its tag - the row stays on 有新版本 and carries the manual route.
            Log.w(TAG, "release ${release.versionName} has no downloadable manifest")
            _update.value = UpdateState.Available(release)
            return
        }
        if (!container.apkInstaller.canInstall()) {
            _update.value = UpdateState.NeedsPermission(release)
            return
        }

        val alreadyDownloaded = container.apkInstaller.downloadedFile(release)
        if (alreadyDownloaded != null) {
            launchInstaller(release)
            return
        }

        _update.value = UpdateState.Downloading(release, 0)
        viewModelScope.launch {
            val result = container.apkInstaller.download(release) { percent ->
                _update.value = UpdateState.Downloading(release, percent)
            }
            _update.value = when (result) {
                is ApkInstaller.DownloadResult.Ready -> UpdateState.ReadyToInstall(release)
                ApkInstaller.DownloadResult.NotTheFile -> {
                    Log.w(TAG, "update download did not match the manifest hash")
                    UpdateState.Failed(UpdateState.Failed.Kind.VERIFY, release)
                }
                ApkInstaller.DownloadResult.NotSignedByUs -> {
                    // The bytes matched the manifest, so it is the *manifest* that was not
                    // ours - the case a mirror or a plain-HTTP address makes possible. Said
                    // out loud rather than logged, because it is the one failure here that
                    // means somebody interfered rather than something went wrong.
                    Log.w(TAG, "update download is not signed by this app's key")
                    UpdateState.Failed(UpdateState.Failed.Kind.SIGNATURE, release)
                }
                is ApkInstaller.DownloadResult.Failed -> {
                    Log.w(TAG, "update download failed: ${result.reason}")
                    UpdateState.Failed(
                        UpdateState.Failed.Kind.DOWNLOAD,
                        release,
                        detail = result.reason,
                    )
                }
            }
            // A verified file still has to find something willing to install it, and a
            // handoff that finds nothing is its own answer rather than a success.
            (_update.value as? UpdateState.ReadyToInstall)?.let { launchInstaller(it.release) }
        }
    }

    /** Hands an already-verified APK to the system installer. */
    private fun launchInstaller(release: UpdateRelease) {
        val file = container.apkInstaller.downloadedFile(release)
        if (file == null) {
            // Nothing on disk after all - an earlier failure, or a cache the system
            // cleared - so this is a download to redo rather than a launch to retry.
            beginInstall(release)
            return
        }
        val launched = container.apkInstaller.launchInstaller(file)
        _update.value = if (launched) {
            // The installer owns the screen from here. If the user cancels it and comes
            // back, OK on this row opens it again on the same file without re-downloading.
            UpdateState.ReadyToInstall(release)
        } else {
            Log.w(TAG, "no installer accepted ${file.name}")
            UpdateState.Failed(UpdateState.Failed.Kind.INSTALL, release)
        }
    }

    /**
     * The last uncaught exception this app recorded, or null if it has not crashed.
     *
     * Read on demand rather than held as state: it is only ever shown on the Settings
     * screen, and it cannot change while the app is running — the next crash is the end
     * of this process.
     */
    fun lastCrash(): String? = container.crashLog.summary()

    fun clearImageCache() {
        viewModelScope.launch {
            runCatching {
                val loader = coil3.SingletonImageLoader.get(getApplication())
                loader.memoryCache?.clear()
                loader.diskCache?.clear()
                container.prefetcher.forgetAll()
            }
        }
    }

    /**
     * Whether these bytes are already in the image disk cache.
     *
     * A snapshot open and nothing else: no network, no decode, no memory cache. That is what
     * makes the answer cheap enough to let it change what the viewer draws — a preview whose
     * original is already on disk has nothing to wait for, so it can be drawn at full size
     * even when the setting would have drawn the thumbnail (`PreviewOriginal.wantsOriginal`).
     *
     * Any failure answers "not cached", which is the safe direction: the caller then does
     * exactly what it did before this existed.
     */
    fun isImageCached(url: String): Boolean {
        if (url.isBlank()) return false
        return runCatching {
            val cache = coil3.SingletonImageLoader.get(getApplication()).diskCache
                ?: return@runCatching false
            val snapshot = cache.openSnapshot(url) ?: return@runCatching false
            snapshot.close()
            true
        }.getOrDefault(false)
    }

    /**
     * Warms the disk cache with the stills the viewer will ask for next, starting at
     * [fromIndex] — the photo the user is looking at, not the top of the list, so paging
     * forward is what gets accelerated.
     *
     * [original] is handed in rather than read from the setting here: whether a preview
     * is the original depends on whether the slideshow is running, and that is a fact
     * about the screen, not about the preference. Warming the tier the viewer will not
     * ask for would fill the cache with files nothing reads and leave the ones it needs
     * on the wire.
     *
     * [limit] is how many stills the caller wants warm. A grid leaves it at the default,
     * because a reader's next move is the neighbouring photo; a running slideshow asks for
     * the whole list it is drawing from, because a shuffled show's next photo is anywhere
     * in it and a window anchored at the current position can only be right by luck.
     *
     * [held], when given, is handed to the prefetcher: while it answers true the window
     * narrows to a single download instead of stopping — see `ImagePrefetcher.prefetch`.
     * The slideshow passes it, because a download cancelled on every step is a download
     * that never completes on a link slower than the show's own interval.
     *
     * Suspending rather than launching internally on purpose: the caller owns the
     * job, so navigating away cancels the outstanding downloads. Videos are filtered
     * out — only stills belong in the image cache.
     */
    suspend fun warmImageCache(
        items: List<MediaItem>,
        fromIndex: Int,
        original: Boolean,
        limit: Int = ImagePrefetcher.DEFAULT_LIMIT,
        held: (() -> Boolean)? = null,
    ) {
        val stills = items.asSequence()
            .drop(fromIndex.coerceAtLeast(0))
            .filterNot { it.isVideo }
            .mapNotNull { it.stillUrl(original) }
            .toList()
        container.prefetcher.prefetch(
            urls = stills,
            limit = limit,
            // An original is several megabytes; the viewer is likely to be asking for one
            // of its own at the same time, so the window leaves it room rather than taking
            // the whole client.
            concurrency = if (original) {
                ImagePrefetcher.ORIGINAL_CONCURRENCY
            } else {
                ImagePrefetcher.DEFAULT_CONCURRENCY
            },
            held = held ?: { false },
        )
    }

    /**
     * Where the video player reads a clip from: the app's own client, so the stream
     * carries the token, the cookie and the pinned certificate like every image does.
     *
     * A plain value rather than state — the player is rebuilt from its URL, and this must
     * not be a reason to rebuild it.
     */
    val videoStreams: DataSource.Factory get() = container.videoStreams

    /**
     * Current image-cache usage and ceiling, in bytes.
     *
     * Surfaced because a 5 GB budget is otherwise invisible: the image cache is an
     * LRU that trims itself, and the only way to know it is working is to look.
     */
    fun imageCacheUsage(): Pair<Long, Long>? {
        val cache = runCatching {
            coil3.SingletonImageLoader.get(getApplication()).diskCache
        }.getOrNull() ?: return null
        return runCatching { cache.size to cache.maxSize }.getOrNull()
    }

    private fun resetContent() {
        galleryCache.clear()
        previewRequests.clear()
        positions.clear()
        // The screensaver album is a setting rather than content, so it is deliberately *not*
        // forgotten here — but the resolved object is: it belongs to the album list that has
        // just been thrown away, and the next list is what should decide whether that album is
        // still there. Without this, the timer would go on opening an album that the account
        // that just signed out was the only one able to see.
        screenSaverResolved = false
        _screenSaverTarget.value = null
        screenSaverOpening = false
        screenSaverPlaying = null
        screenSaverGalleryBackup = null
        container.photos.clearCaches()
        _monthCovers.clear()
        _gallery.value = GalleryState()
        _timeline.value = ContentState.Loading
        _albums.value = ContentState.Loading
        _sharedAlbums.value = ContentState.Loading
        _people.value = ContentState.Loading
        _folders.value = ContentState.Loading
        _stats.value = null
    }

    private companion object {
        const val PAGE_SIZE = 60

        /** Sentinel for "this screen has no recorded position yet". */
        const val NO_POSITION = -1

        /**
         * How long an automatic update check stands before the settings screen may start
         * another. Ten minutes is short enough that a build published while the TV was on
         * is noticed, and long enough that the row is not asking GitHub on every visit.
         */
        const val UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L

        const val TAG = "MainViewModel"
    }
}

/** Adds the underlying cause's message, which is usually the actionable part. */
internal fun Throwable.friendlyMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: (cause?.message ?: "unknown error")
