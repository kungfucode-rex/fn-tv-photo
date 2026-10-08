package com.tvphoto.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.mutableStateMapOf
import androidx.media3.datasource.DataSource
import com.tvphoto.TvPhotoApp
import com.tvphoto.data.AppContainer
import com.tvphoto.data.PreviewOriginal
import com.tvphoto.data.SavedAccount
import com.tvphoto.data.SessionState
import com.tvphoto.data.ThemeMode
import com.tvphoto.data.accountFor
import com.tvphoto.data.fn.SignMode
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

    private val _signMode = MutableStateFlow(container.signMode)
    val signMode: StateFlow<SignMode> = _signMode.asStateFlow()

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
        loadSection(_albums, force, fetch = { container.photos.albums() })

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

    private suspend fun loadPage(screen: Screen, offset: Int, limit: Int): MediaPage = when (screen) {
        is Screen.MonthPhotos -> container.photos.photosInMonth(screen.month, limit, offset)
        is Screen.AlbumPhotos -> container.photos.albumPhotos(screen.album.id, limit, offset)
        is Screen.PersonPhotos -> container.photos.personPhotos(screen.person.id, limit, offset)
        is Screen.FolderBrowse -> container.photos.folderFiles(screen.path, limit, offset)
        else -> MediaPage(emptyList(), hasMore = false)
    }

    // --------------------------------------------------------------- settings

    // ------------------------------------------------------- position memory

    private val positions = mutableMapOf<String, Int>()

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

    fun setSignMode(mode: SignMode) {
        container.forceSignMode(mode)
        _signMode.value = mode
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
     * Suspending rather than launching internally on purpose: the caller owns the
     * job, so navigating away cancels the outstanding downloads. Videos are filtered
     * out — only stills belong in the image cache.
     */
    suspend fun warmImageCache(items: List<MediaItem>, fromIndex: Int, original: Boolean) {
        val stills = items.asSequence()
            .drop(fromIndex.coerceAtLeast(0))
            .filterNot { it.isVideo }
            .mapNotNull { it.stillUrl(original) }
            .toList()
        container.prefetcher.prefetch(stills)
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

        const val TAG = "MainViewModel"
    }
}

/** Adds the underlying cause's message, which is usually the actionable part. */
internal fun Throwable.friendlyMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: (cause?.message ?: "unknown error")
