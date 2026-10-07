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
     * Full-screen viewer. It intentionally holds only an index: the surrounding
     * item list lives in the view model so that pages loaded while the viewer is
     * open are immediately browsable.
     */
    data class Viewer(val index: Int, val title: String) : Screen
}

/** Heading shown above the content of a screen. */
fun Screen.displayTitle(
    formatMonth: (Int, Int) -> String,
    fallback: String = "",
): String = when (this) {
    Screen.Home -> fallback
    is Screen.MonthPhotos -> formatMonth(month.year, month.month)
    is Screen.AlbumPhotos -> album.name
    is Screen.PersonPhotos -> person.name.ifBlank { fallback }
    is Screen.FolderBrowse -> name
    is Screen.Viewer -> title
}

/** Stable identity for the per-screen item cache and for position memory. */
val Screen.cacheKey: String
    get() = when (this) {
        Screen.Home -> "home"
        is Screen.MonthPhotos -> "month:${month.id}"
        is Screen.AlbumPhotos -> "album:${album.id}"
        is Screen.PersonPhotos -> "person:${person.id}"
        is Screen.FolderBrowse -> "folder:$path"
        is Screen.Viewer -> "viewer:$index"
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
