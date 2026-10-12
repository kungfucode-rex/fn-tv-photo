package com.tvphoto.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.tvphoto.domain.AlbumItem
import com.tvphoto.domain.Person
import com.tvphoto.domain.TimelineMonth

/** Sections reachable from the home navigation rail. */
enum class HomeSection { Timeline, Albums, Shared, People, Folders, Settings }

/**
 * A screen on the navigation stack.
 *
 * The timeline is not one of these: its years and months are both on the home pane,
 * so only a chosen month pushes a screen.
 */
sealed interface Screen {

    data object Home : Screen

    /** Photos taken in one month, reached from the timeline's month cards. */
    data class MonthPhotos(val month: TimelineMonth) : Screen

    /** Photos inside one album. */
    data class AlbumPhotos(val album: AlbumItem) : Screen

    /** Photos clustered around one recognised face. */
    data class PersonPhotos(val person: Person) : Screen

    /** One directory inside a managed folder. */
    data class FolderBrowse(val path: String, val name: String) : Screen

    /**
     * The music library: what the slideshow can play besides the four bundled tracks.
     *
     * A screen of its own rather than a pane inside 设置, because it is reached from two
     * places that are not settings — the viewer's track list and the settings row — and
     * because it is the one place where a guest's phone is talking to this television while
     * the user is looking at it.
     */
    data object Music : Screen

    /**
     * Full-screen viewer. It intentionally holds only an index: the surrounding
     * item list lives in the view model so that pages loaded while the viewer is
     * open are immediately browsable.
     */
    data class Viewer(val index: Int, val title: String) : Screen

    /**
     * The 屏保相册 playing itself, after nobody has touched the remote for a while.
     *
     * A screen of its own rather than "the viewer, but with a flag", and the reason is the
     * gallery underneath it. `_gallery` holds the list of exactly one screen — the topmost
     * one that is not a viewer — so opening *Screen.AlbumPhotos* would have replaced the
     * grid the user was standing on with the screensaver album's photos, and coming back
     * from the screensaver would have left them on a grid of somebody else's pictures,
     * scrolled to the position memory of a screen they never visited. With its own type
     * this screen owns its own gallery entry, its own cache key and its own position
     * memory (which nothing ever writes — the user never stands on it), and the album grid
     * underneath is left exactly as it was.
     *
     * It draws the viewer rather than a plain image, on purpose: the two layers that keep a
     * step from flashing black, the prefetch window, the show's interval and its music are
     * all in that screen, and a second implementation of them would be a second set of bugs
     * (see `ViewerScreen`).
     */
    data class ScreenSaverAlbum(val album: AlbumItem) : Screen
}

/** Heading shown above the content of a screen. */
fun Screen.displayTitle(
    formatMonth: (Int, Int) -> String,
    fallback: String = "",
): String = when (this) {
    // Home draws its own heading per section, and the music screen draws its own title.
    Screen.Home, Screen.Music -> fallback
    is Screen.MonthPhotos -> formatMonth(month.year, month.month)
    is Screen.AlbumPhotos -> album.name
    is Screen.PersonPhotos -> person.name.ifBlank { fallback }
    is Screen.FolderBrowse -> name
    is Screen.Viewer -> title
    is Screen.ScreenSaverAlbum -> album.name
}

/** Stable identity for the per-screen item cache and for position memory. */
val Screen.cacheKey: String
    get() = when (this) {
        Screen.Home -> "home"
        Screen.Music -> "music"
        is Screen.MonthPhotos -> "month:${month.id}"
        is Screen.AlbumPhotos -> "album:${album.id}"
        is Screen.PersonPhotos -> "person:${person.id}"
        is Screen.FolderBrowse -> "folder:$path"
        is Screen.Viewer -> "viewer:$index"
        // Prefixed like the album key, and never equal to it: the two hold different lists
        // of the same album, and sharing one entry would let whichever loaded last decide
        // what the other screen shows.
        is Screen.ScreenSaverAlbum -> "screensaver:${album.id}"
    }

/** Locale helper kept here so [displayTitle] stays free of Android types. */
fun monthTitleFormatter(locale: java.util.Locale): (Int, Int) -> String = { year, month ->
    com.tvphoto.ui.components.formatMonthLabel(year, month, locale)
}

/**
 * Position-memory keys for the home sections' own lists.
 *
 * They double as a "has the user been here" signal: the navigation rail takes focus
 * only when the active section has no recorded position, so returning to a section
 * lands back on the item the user left instead of being pulled back to the rail.
 */
object PositionKeys {
    const val TIMELINE_YEARS = "timeline:years"
    const val ALBUMS = "albums"
    const val SHARED = "shared-albums"
    const val PEOPLE = "people"
    const val FOLDERS = "folders"

    /**
     * Which settings pane is open, and which of its rows was last pressed.
     *
     * Session state rather than a preference, but kept in the same place as the list
     * positions and for the same reason: the screen that owns these is disposed while
     * another screen is on top of it, and `rememberSaveable` does not survive that. The
     * symptom it fixes is concrete — opening the upload page from 幻灯片设置 and pressing
     * Back landed on 版本升级, because the pane fell back to its default.
     */
    const val SETTINGS_PANE = "settings:pane"
    const val SETTINGS_ROW = "settings:row"

    fun forSection(section: HomeSection): String = when (section) {
        HomeSection.Timeline -> TIMELINE_YEARS
        HomeSection.Albums -> ALBUMS
        HomeSection.Shared -> SHARED
        HomeSection.People -> PEOPLE
        HomeSection.Folders -> FOLDERS
        HomeSection.Settings -> "settings"
    }
}

/**
 * Restores and records where the user was in a list or grid.
 *
 * Compose's own `rememberLazyListState` does not survive leaving composition, and
 * this app swaps whole screens on a hand-rolled stack, so the index is kept in the
 * view model and re-applied when the screen is composed again.
 */
class PositionMemory internal constructor(
    /** Index captured when the screen was entered, or -1 when there is none. */
    val restoreIndex: Int,
    private val requester: FocusRequester,
    private val onFocused: (Int) -> Unit,
) {
    val hasRestorePoint: Boolean get() = restoreIndex >= 0

    /**
     * Attach to every item. The restore target also carries the focus requester so
     * focus lands on the item the user left, not at the top of the list.
     */
    fun modifierFor(itemIndex: Int): Modifier = Modifier
        .then(
            if (itemIndex == restoreIndex) Modifier.focusRequester(requester) else Modifier,
        )
        .onFocusChanged { if (it.isFocused) onFocused(itemIndex) }

    fun requestFocusOnRestoredItem() {
        runCatching { requester.requestFocus() }
    }
}

@Composable
fun rememberPositionMemory(key: String, viewModel: MainViewModel): PositionMemory {
    val saved = remember(key) { viewModel.savedIndex(key) }
    val requester = remember(key) { FocusRequester() }
    return remember(key) {
        PositionMemory(saved, requester) { index -> viewModel.saveIndex(key, index) }
    }
}
