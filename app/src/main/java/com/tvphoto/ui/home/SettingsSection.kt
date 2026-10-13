package com.tvphoto.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.BuildConfig
import com.tvphoto.R
import com.tvphoto.data.IMAGE_CACHE_CEILING_BYTES
import com.tvphoto.data.PreviewOriginal
import com.tvphoto.data.SLIDESHOW_MUSIC
import com.tvphoto.data.SessionState
import com.tvphoto.data.SettingsStore
import com.tvphoto.data.ThemeMode
import com.tvphoto.data.fn.SignMode
import com.tvphoto.data.review.DanmakuLevel
import com.tvphoto.data.review.ReviewMode
import com.tvphoto.data.review.ReviewStatus
import com.tvphoto.ui.ContentState
import com.tvphoto.ui.HomeSection
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.PositionKeys
import com.tvphoto.ui.UpdateState
import com.tvphoto.ui.components.ActionRow
import com.tvphoto.ui.components.AlbumPickerDialog
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.InfoRow
import com.tvphoto.ui.components.LocalSettingRowFocus
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.TvTextField
import com.tvphoto.ui.components.formatFileSize

/**
 * The settings screen: a sub-menu on the left, the chosen pane on the right.
 *
 * It used to be one scrolling column, and the column had grown taller than a 1080p screen:
 * twenty-odd rows in an order nobody chose, with — 检测升级— and the 图评 server address
 * scattered down it. Focus brought each row into view, so nothing was unreachable, but
 * "reachable" is not "findable": the row a person came for could be two screens down, and
 * what was above it was whatever had been added most recently. One pane per subject is a
 * fraction of the height each, and the entries on the left are the map.
 *
 * Focus stays on the sub-menu when the pane beneath it changes, and the pane's own rows are
 * reached with the right arrow. Handing the D-pad over on every selection was tried and is
 * wrong twice over: `requestFocus` scrolls the item it lands on into view, and the pane is
 * composed fresh each time, so the heading and its caption were dragged off the top and the
 * user arrived at a list of rows with no title — with the scroll position kept for the *next*
 * pane as well, since it is the same column.
 *
 * The interval and the order — the one pair of settings the viewer also owns — are
 * deliberately in two places. They write one preference, so the two doors cannot disagree;
 * the viewer's copy is where they are usually changed, and this one is for setting a show up
 * before anybody is looking at a photo, which otherwise means opening a picture and
 * pressing 幻灯片 first.
 */
@Composable
fun SettingsSection(viewModel: MainViewModel) {
    // Which pane is open outlives this composable, and that is deliberate: opening a screen
    // from a row here — the upload page, say — disposes the home screen, and
    // `rememberSaveable` does not survive that. It lived in a `rememberSaveable` until
    // 1.5.1, which meant Back from the upload page landed on 版本升级 instead of the pane
    // the user had been in; the position memory now holds it for the session, next to the
    // list positions that exist for the same reason.
    var pane by remember {
        mutableStateOf(SettingsPane.entries.getOrElse(viewModel.savedIndex(PositionKeys.SETTINGS_PANE)) { SettingsPane.VERSION })
    }
    LaunchedEffect(pane) { viewModel.saveIndex(PositionKeys.SETTINGS_PANE, pane.ordinal) }

    // A `LaunchedEffect` declared inside a scrolling column only runs once its position is
    // composed — which is exactly how the review status row used to sit at 关 with no
    // explanation on a 1080p screen — so this one is out here.
    LaunchedEffect(pane) {
        if (pane == SettingsPane.REVIEW) viewModel.refreshReviewStatus()
    }

    // Whether the cursor is in the pane on the right rather than on the list on the left.
    var inPane by remember { mutableStateOf(false) }

    // The entry the cursor came from: where the cursor is put on entering this section, and
    // where left goes from the pane — the entry it left, rather than whichever one happens to
    // be nearest to the left.
    val currentEntry = remember(pane) { FocusRequester() }

    /**
     * Right, from the menu, always lands on the pane's first row.
     *
     * Asking for it rather than leaving it to the focus system, which answers "the nearest
     * rect to the right" — and the nearest rect to the right of the third entry is the third
     * row, so the cursor appeared in the middle of a list whose top it had never shown.
     *
     * `openPane > 0` is load-bearing, and it is a bug this section carried until 1.5.1: the
     * effect also runs on the *first* composition, where the counter is still 0, so entering
     * the section pulled the cursor straight into the pane. That contradicts what this screen
     * is for (the cursor stays on the category; right enters the pane) and it broke the key
     * that enters the pane, because `inPane` was already true by the time it was pressed.
     */
    val paneTop = remember { FocusRequester() }
    var openPane by remember { mutableStateOf(0) }
    LaunchedEffect(openPane) { if (openPane > 0) runCatching { paneTop.requestFocus() } }

    // Claim focus on entering the section, every time.
    //
    // Without this the section is unreachable by remote: the navigation rail holds focus, and
    // D-pad never leaves it for the content column. The other sections claim focus the same
    // way, through `shouldClaimFocus` in HomeScreen.
    //
    // Every time, and on the entry that is showing, for two reasons that were one bug. The
    // claim used to be spent after the first visit, so on the second the section opened with
    // the cursor still on the rail — and D-pad from there walks the rail rather than the
    // settings, which from the sofa reads as a screen that will not open. And the entry the
    // cursor is put on has to be the selected one: the list and the pane are one choice, so
    // letting the cursor back in on the first entry would leave it saying 版本升级 while the
    // pane beside it showed something else.
    LaunchedEffect(Unit) { runCatching { currentEntry.requestFocus() } }

    // Recorded once focus lands, so the rail does not grab it back on the next visit and
    // the user is returned to this column instead of the menu.
    LaunchedEffect(Unit) {
        viewModel.saveIndex(PositionKeys.forSection(HomeSection.Settings), 0)
    }

    // Left, from the pane, goes back to the sub-menu — the direction it came in by. Left again
    // from there is left alone, and the focus system answers it with the rail, which is one
    // press rather than two.
    //
    // A preview hook on the section's root, and the consuming is the point: left is not the
    // Back key, so `BackHandler` never sees it, and the geometric search that *does* answer it
    // treats the pane's rows and the sub-menu's entries as one flat map. The rect nearest to
    // the left of a row is the sub-menu — unless the row is short, or the cursor is on the far
    // side of the pane, and then it is the rail: one press skipped the list the user had just
    // come through.
    val paneKeys: (KeyEvent) -> Boolean = { event ->
        when {
            event.type != KeyEventType.KeyDown -> false

            event.key == Key.DirectionRight && !inPane -> {
                openPane++
                true
            }

            event.key == Key.DirectionLeft && inPane -> {
                inPane = false
                runCatching { currentEntry.requestFocus() }
                true
            }

            // Back is deliberately *not* handled here. `AppRoot` owns that key and it does not
            // ask where the focus is: this section lives on the home screen, so one press from
            // a setting row puts 再按一次返回键退出 on screen instead of stepping up to the
            // list. Handling it here did move the cursor — and the hint appeared anyway,
            // because a consumed key event does not stop the root's `BackHandler`. Two
            // behaviours on one key is worse than the one that is already there.

            else -> false
        }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            // No `focusRestorer` here, deliberately. It remembers the last focused descendant
            // and hands focus back to it, which fights the explicit requests above: the cursor
            // was put on the sub-menu entry and the restorer pulled it straight back into the
            // row it had been on, so left out of a pane looked like nothing at all. This
            // screen drives its own cursor; nothing else should.
            .onPreviewKeyEvent(paneKeys),
    ) {
        SettingsMenu(
            current = pane,
            onSelect = { pane = it },
            onEntryFocus = { inPane = false },
            currentEntry = currentEntry,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            // Every row in this pane reports focus, and none of them claims it: the point is
            // only to know which side of the screen the cursor is on.
            CompositionLocalProvider(LocalSettingRowFocus::provides { inPane = true }) {
                when (pane) {
                    SettingsPane.VERSION -> VersionPane(viewModel, paneTop)
                    SettingsPane.GENERAL -> GeneralPane(viewModel, paneTop)
                    SettingsPane.SLIDESHOW -> SlideshowPane(viewModel, paneTop)
                    SettingsPane.REVIEW -> ReviewPane(viewModel, paneTop)
                }
            }
        }
    }
}

/**
 * The subjects the settings are divided into, in the order the sub-menu lists them.
 */
private enum class SettingsPane(val label: Int) {
    VERSION(R.string.settings_pane_version),
    GENERAL(R.string.settings_pane_general),
    SLIDESHOW(R.string.settings_pane_slideshow),
    REVIEW(R.string.settings_pane_review),
}

/**
 * The left-hand sub-menu.
 *
 * A narrow column rather than the rail's own list: this one changes what the pane beside it
 * shows, in place, so it has to stay visible while that pane is being read. Entering an
 * entry needs no press of its own — moving onto one opens it, because a television remote
 * has one obvious key and making it do double duty here would only add a step between the
 * user and the thing they just asked for.
 */
@Composable
private fun SettingsMenu(
    current: SettingsPane,
    onSelect: (SettingsPane) -> Unit,
    onEntryFocus: () -> Unit,
    currentEntry: FocusRequester,
) {
    Column(
        modifier = Modifier
            .width(200.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(12.dp))

        SettingsPane.entries.forEach { entry ->
            MenuEntry(
                label = stringResource(entry.label),
                selected = entry == current,
                onFocus = {
                    onSelect(entry)
                    onEntryFocus()
                },
                modifier = if (entry == current) Modifier.focusRequester(currentEntry) else Modifier,
            )
        }
    }
}

@Composable
private fun MenuEntry(
    label: String,
    selected: Boolean,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onFocus,
        shape = RoundedCornerShape(10.dp),
        containerColor = if (selected) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            Color.Transparent
        },
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        // The cursor landing on an entry *is* the choice: the pane beside it swaps as it
        // moves. A television remote has one obvious key, and making it do double duty here
        // would put a step between the user and the pane they just asked for.
        onFocus = onFocus,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(20.dp)
                    .background(
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(2.dp),
                    ),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

/**
 * One pane: a heading, a line saying what the rows below are about, and the rows.
 *
 * The heading and its caption are drawn here rather than handed to `SectionHeader`, and the
 * gaps between them are not the column's uniform one: the caption belongs to the heading, and
 * at the spacing the rows use it read as the first row of the list — a line of explanation
 * sitting as far from its title as the settings are from each other.
 *
 * The column scrolls and focus brings the row it lands on into view, exactly as the old
 * single column did — a pane can still be taller than the screen, and a row cut off at the
 * bottom edge reads as a button that does nothing.
 *
 * [firstRowFocus] is attached to the pane's first row here; whether it is ever *requested* is
 * decided by the section, because the request is what scrolls the column and it happens when
 * right is pressed rather than on every recomposition.
 */
@Composable
private fun Pane(
    title: Int,
    caption: String,
    firstRowFocus: FocusRequester,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(6.dp))
        if (caption.isNotBlank()) {
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // The gap that separates the heading from the rows, which is wider than the one the
        // rows keep between themselves.
        Spacer(Modifier.height(14.dp))
        content()
    }
}

// ------------------------------------------------------------------ 版本升级

/**
 * The update check, and nothing else.
 *
 * It is the first entry because it is the one thing here that is about the app rather than
 * about the library, and the one a user is most likely to have been sent to: a red dot on
 * the 设置 entry of the rail lands them here.
 */
@Composable
private fun VersionPane(viewModel: MainViewModel, paneTop: FocusRequester) {
    // Asks the release page by itself the moment this pane appears, throttled in the view
    // model - and already asked once at start-up, which is what the red dot on the rail is.
    LaunchedEffect(Unit) { viewModel.autoCheckForUpdate() }

    val update by viewModel.update.collectAsStateWithLifecycle()
    val row = updateRowText(update, viewModel.updateReleasePageUrl)

    Pane(
        title = R.string.settings_pane_version,
        caption = stringResource(R.string.settings_update_caption),
        firstRowFocus = paneTop,
    ) {
        ActionRow(
            label = stringResource(R.string.settings_update),
            value = row.value,
            hint = row.hint,
            badge = update is UpdateState.Available,
            valueColor = if (update.isAwaitingPress) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            onClick = viewModel::onUpdateRowClick,
            modifier = Modifier.focusRequester(paneTop),
        )

        InfoRow(
            label = stringResource(R.string.settings_version),
            value = "FN Photo ${BuildConfig.VERSION_NAME}",
            detail = stringResource(R.string.settings_version_hint),
        )
    }
}

/**
 * Whether the row is showing an answer the user can act on, and so should be drawn in the
 * accent colour: a release is waiting for a press.
 */
private val UpdateState.isAwaitingPress: Boolean
    get() = this is UpdateState.Available ||
        this is UpdateState.NeedsPermission ||
        this is UpdateState.ReadyToInstall

// ---------------------------------------------------------------------- 通用

/** Theme, the way photos are drawn, the device's own details, and the cache. */
@Composable
private fun GeneralPane(viewModel: MainViewModel, paneTop: FocusRequester) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val previewOriginal by viewModel.previewOriginal.collectAsStateWithLifecycle()
    val signMode by viewModel.signMode.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val session = (sessionState as? SessionState.SignedIn)?.session

    // The screensaver album reads through the same flow the album cards do, and the picker
    // needs *both* album lists — the account's own and the ones shared with it — because
    // choosing what plays in a room is not a question about who owns it. Asked for here rather
    // than in the picker so the lists are already on screen when the row is pressed, and
    // because this pane can be the first screen after a cold start.
    val screenSaverId by viewModel.screenSaverAlbumId.collectAsStateWithLifecycle()
    val albumState by viewModel.albums.collectAsStateWithLifecycle()
    val sharedState by viewModel.sharedAlbums.collectAsStateWithLifecycle()
    var pickingAlbum by remember { mutableStateOf(false) }

    // No `force`: entering 设置 is not a reason to re-read two lists the 相册 sections keep
    // fresh, and a fetch that fails leaves the picker with whatever was already loaded.
    LaunchedEffect(Unit) {
        viewModel.loadAlbums()
        viewModel.loadSharedAlbums()
    }

    var cacheCleared by remember { mutableStateOf(false) }
    var usageTick by remember { mutableIntStateOf(0) }
    var cachedPair by remember { mutableStateOf<Pair<Long, Long>?>(null) }

    // Re-read whenever the pane appears or the cache is cleared, so the figure is never a
    // stale impression of a self-trimming LRU.
    LaunchedEffect(usageTick) { cachedPair = viewModel.imageCacheUsage() }
    LaunchedEffect(cacheCleared) {
        if (cacheCleared) {
            kotlinx.coroutines.delay(2000)
            cacheCleared = false
        }
    }

    /** "1.2GB / 5.0GB", or the cleared confirmation for a moment after clearing. */
    @Composable
    fun cacheUsage(): String {
        if (cacheCleared) return stringResource(R.string.settings_cache_cleared)
        val pair = cachedPair ?: return stringResource(R.string.settings_cache_measuring)
        return stringResource(
            R.string.settings_cache_usage,
            formatFileSize(pair.first),
            formatFileSize(pair.second),
        )
    }

    /**
     * Why the ceiling in that line may be smaller than the 5 GB the app promises.
     *
     * The cache is built at a quarter of the space the box could spare, so a television
     * whose storage is nearly full gets a smaller cache — deliberately, because a 5 GB
     * cache on 1 GB of free space is a cache Android deletes (§ ImageCacheSize). Without
     * this sentence the smaller number reads as a fault, and the person's next move is to
     * clear the cache, which is the one action that guarantees the re-downloads the
     * smaller number exists to prevent.
     */
    @Composable
    fun cacheHint(): String? {
        val pair = cachedPair ?: return null
        if (pair.second >= IMAGE_CACHE_CEILING_BYTES) return null
        return stringResource(
            R.string.settings_cache_capped,
            formatFileSize(IMAGE_CACHE_CEILING_BYTES),
        )
    }

    Pane(
        title = R.string.settings_pane_general,
        caption = stringResource(R.string.settings_general_caption),
        firstRowFocus = paneTop,
    ) {
        InfoRow(
            label = stringResource(R.string.settings_server),
            value = session?.baseUrl.orEmpty(),
        )
        InfoRow(
            label = stringResource(R.string.settings_account),
            value = session?.userName.orEmpty(),
        )

        ActionRow(
            label = stringResource(R.string.settings_theme),
            value = when (themeMode) {
                ThemeMode.DARK -> stringResource(R.string.settings_theme_dark)
                ThemeMode.LIGHT -> stringResource(R.string.settings_theme_light)
            },
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setThemeMode(themeMode.next()) },
            modifier = Modifier.focusRequester(paneTop),
        )

        ActionRow(
            label = stringResource(R.string.settings_preview_original),
            value = when (previewOriginal) {
                PreviewOriginal.ALWAYS -> stringResource(R.string.settings_preview_original_yes)
                PreviewOriginal.NEVER -> stringResource(R.string.settings_preview_original_no)
                PreviewOriginal.SLIDESHOW_ONLY ->
                    stringResource(R.string.settings_preview_original_slideshow)
            },
            hint = stringResource(R.string.settings_preview_original_global_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setPreviewOriginal(previewOriginal.next()) },
        )

        // 屏保相册. The value is the name stored with the id rather than a lookup in the album
        // list, so the row says which album it is before that list has loaded — and on a
        // television, which is often the moment somebody is standing in front of it asking
        // what is set.
        ActionRow(
            label = stringResource(R.string.settings_screen_saver),
            value = viewModel.screenSaverAlbumName.ifBlank {
                stringResource(R.string.screen_saver_unset)
            },
            hint = stringResource(R.string.settings_screen_saver_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { pickingAlbum = true },
        )

        ActionRow(
            label = stringResource(R.string.settings_cache),
            value = cacheUsage(),
            hint = cacheHint(),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = {
                viewModel.clearImageCache()
                cacheCleared = true
                usageTick++
            },
        )

        // Read once per visit: it cannot change while the process lives, and the one time
        // it matters — a launch that died on a TV with no console — it is the only report
        // the user can actually read out.
        val lastCrash = remember { viewModel.lastCrash() }
        InfoRow(
            label = stringResource(R.string.settings_last_crash),
            value = lastCrash ?: stringResource(R.string.settings_crash_none),
        )

        // The escape hatch, last and with its own explanation, because it is not a setting
        // anybody should be choosing on purpose.
        ActionRow(
            label = stringResource(R.string.settings_sign_mode),
            value = when (signMode) {
                SignMode.RAW_VALUES -> "RAW"
                SignMode.ENCODED_QUERY -> "ENCODED"
            },
            hint = stringResource(R.string.settings_sign_mode_hint),
            onClick = {
                viewModel.setSignMode(
                    when (signMode) {
                        SignMode.RAW_VALUES -> SignMode.ENCODED_QUERY
                        SignMode.ENCODED_QUERY -> SignMode.RAW_VALUES
                    },
                )
            },
        )
    }

    // Outside the pane, and that is not cosmetic. A `Dialog` declared inside the scrolling
    // column is composed wherever the cursor happens to be: press OK on 屏保相册 with the row
    // near the bottom edge, the picker opens, the cursor moves into it — and the effect that
    // scrolls the pane to whatever is focused drags the column out from under the panel. It
    // also has to survive the pane being disposed, since choosing an album reloads nothing but
    // does close this composable's own subtree.
    if (pickingAlbum) {
        AlbumPickerDialog(
            // Own albums first, then the albums other accounts shared: the order the two
            // sections are in on the rail, so the list reads the way the rest of the app does.
            albums = (albumState as? ContentState.Ready)?.value.orEmpty() +
                (sharedState as? ContentState.Ready)?.value.orEmpty(),
            currentId = screenSaverId,
            onPick = { album ->
                if (album == null) {
                    viewModel.clearScreenSaverAlbum()
                } else {
                    viewModel.setScreenSaverAlbum(album)
                }
                pickingAlbum = false
            },
            onDismiss = { pickingAlbum = false },
        )
    }
}

/**
 * The slideshow's interval and order, and a line naming the music catalogue.
 *
 * Both rows write the preferences the viewer's own 幻灯片 row writes, through the same view
 * model setters, so the two doors cannot disagree: the viewer recomposes off the same
 * `StateFlow` this page does.
 */
@Composable
private fun SlideshowPane(viewModel: MainViewModel, paneTop: FocusRequester) {
    val interval by viewModel.slideshowSeconds.collectAsStateWithLifecycle()
    val shuffled by viewModel.slideshowShuffled.collectAsStateWithLifecycle()
    val selectedMusic by viewModel.slideshowMusic.collectAsStateWithLifecycle()

    val options = SettingsStore.INTERVAL_OPTIONS
    val library by viewModel.musicLibrary.collectAsStateWithLifecycle()

    /**
     * Which row the cursor lands on when this pane is entered: the one that was last pressed
     * in it, or the first row when nothing has been.
     *
     * It exists for the two rows that open the upload page. Coming back from it — Back pops
     * the page, the home screen is rebuilt, and this pane reopens — the cursor should be on
     * the row the page was opened from rather than three presses further up.
     */
    val rememberedRow = viewModel.savedIndex(PositionKeys.SETTINGS_ROW)
    // -1 when nothing has been pressed here yet, and then the first row is the one to land
    // on: every pane must have exactly one row carrying the requester, or right from the
    // menu has nothing to focus and the key appears dead.
    val landingRow = if (rememberedRow >= 0) rememberedRow else 0
    fun rowFocus(index: Int): Modifier =
        if (index == landingRow) Modifier.focusRequester(paneTop) else Modifier

    Pane(
        title = R.string.settings_pane_slideshow,
        caption = stringResource(R.string.settings_slideshow_same_hint),
        firstRowFocus = paneTop,
    ) {
        ActionRow(
            label = stringResource(R.string.viewer_interval_label),
            value = stringResource(R.string.viewer_interval_option, interval),
            hint = stringResource(R.string.settings_slideshow_interval_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = {
                // The same wrap-around the viewer's stepper does, over the same list, so
                // pressing OK here and pressing down there visit the values in one order.
                val at = options.indexOf(interval)
                viewModel.setSlideshowSeconds(options[(at + 1).mod(options.size)])
            },
            modifier = rowFocus(0),
        )

        ActionRow(
            label = stringResource(R.string.viewer_order_label),
            value = if (shuffled) {
                stringResource(R.string.viewer_order_shuffle)
            } else {
                stringResource(R.string.viewer_order_sequential)
            },
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setSlideshowShuffled(!shuffled) },
            modifier = rowFocus(1),
        )

        // Two rows, one page. 背景音乐 is the setting — what plays, and how much of it is
        // ticked — and 上传背景音乐 is the button the page is reached by. Both open the same
        // screen and Back returns to this pane, because the phone upload, the list and the
        // ticking are one act (see docs/custom-music-design.md §3).
        ActionRow(
            label = stringResource(R.string.settings_slideshow_music),
            value = stringResource(R.string.music_counts, SLIDESHOW_MUSIC.size, library.uploadCount),
            hint = if (selectedMusic.isEmpty()) {
                stringResource(R.string.viewer_music_none)
            } else {
                stringResource(R.string.viewer_music_count, selectedMusic.size)
            },
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = {
                viewModel.saveIndex(PositionKeys.SETTINGS_ROW, 2)
                viewModel.openMusicUpload()
            },
            modifier = rowFocus(2),
        )

        ActionRow(
            label = stringResource(R.string.settings_music_upload),
            value = stringResource(R.string.settings_music_upload_value),
            hint = stringResource(R.string.settings_music_upload_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = {
                viewModel.saveIndex(PositionKeys.SETTINGS_ROW, 3)
                viewModel.openMusicUpload()
            },
            modifier = rowFocus(3),
        )
    }
}

// ------------------------------------------------------------------ 图评设置

/**
 * 图评: on or off, which server, what gets uploaded, and whether it is reachable.
 *
 * Four rows and one status line, in the order somebody sets them up. The status row is the
 * reason this pane exists at all: a television has no console, so "连不上
 * 192.168.31.14" has to be readable from the sofa — the same reasoning as the 上次崩溃 row.
 */
@Composable
private fun ReviewPane(viewModel: MainViewModel, paneTop: FocusRequester) {
    val reviewMode by viewModel.reviewMode.collectAsStateWithLifecycle()
    val danmakuLevel by viewModel.danmakuLevel.collectAsStateWithLifecycle()
    val extendStayOnComment by viewModel.extendStayOnComment.collectAsStateWithLifecycle()
    val reviewStatus by viewModel.reviewStatus.collectAsStateWithLifecycle()

    // The address the user is editing, seeded from what is stored. A draft rather than a
    // direct write on every keystroke: this is a television remote, and every character
    // typed should not be a saved setting.
    var serverDraft by remember { mutableStateOf(viewModel.reviewServerUrl) }
    val serverUrl = viewModel.reviewServerUrl
    var editingServer by remember { mutableStateOf(false) }

    Pane(
        title = R.string.settings_pane_review,
        caption = stringResource(R.string.settings_review_hint),
        firstRowFocus = paneTop,
    ) {
        ActionRow(
            label = stringResource(R.string.settings_review),
            value = when (reviewMode) {
                ReviewMode.OFF -> stringResource(R.string.settings_review_off)
                ReviewMode.SLIDESHOW -> stringResource(R.string.settings_review_slideshow)
                ReviewMode.ALL -> stringResource(R.string.settings_review_all)
            },
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setReviewMode(reviewMode.next()) },
            modifier = Modifier.focusRequester(paneTop),
        )

        // A row rather than a keyboard-first form: the address is typed once per install
        // and everything else on this screen is a single OK press, so this one keeps the
        // same shape and opens the address field beneath itself.
        ActionRow(
            label = stringResource(R.string.settings_review_server),
            value = serverUrl.ifBlank { stringResource(R.string.settings_review_server_unset) },
            hint = stringResource(R.string.settings_review_server_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { editingServer = !editingServer },
        )
        if (editingServer) {
            TvTextField(
                value = serverDraft,
                onValueChange = { serverDraft = it },
                label = stringResource(R.string.settings_review_server),
                placeholder = "192.168.31.14:8080",
                keyboardType = KeyboardType.Uri,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),
            )
            ActionRow(
                label = stringResource(R.string.settings_review_server),
                value = stringResource(R.string.settings_review_server_save),
                valueColor = MaterialTheme.colorScheme.primary,
                onClick = {
                    viewModel.setReviewServerUrl(serverDraft)
                    editingServer = false
                },
            )
        }

        // 弹幕: the amount *is* the switch (关 / 少 / 多), which is why there is no second row
        // for it. It moved here from the viewer's 图评 menu in §17.29, when that menu became
        // the single 扫码评论 button: what the corner does is a decision about one photo,
        // while how much danmaku to draw is a preference.
        ActionRow(
            label = stringResource(R.string.settings_review_danmaku_amount),
            value = when (danmakuLevel) {
                DanmakuLevel.OFF -> stringResource(R.string.settings_review_danmaku_off)
                DanmakuLevel.FEW -> stringResource(R.string.settings_review_danmaku_few)
                DanmakuLevel.MANY -> stringResource(R.string.settings_review_danmaku_many)
            },
            hint = stringResource(R.string.settings_review_danmaku_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setDanmakuLevel(danmakuLevel.next()) },
        )

        ActionRow(
            label = stringResource(R.string.settings_review_extend),
            value = if (extendStayOnComment) {
                stringResource(R.string.settings_yes)
            } else {
                stringResource(R.string.settings_no)
            },
            hint = stringResource(R.string.settings_review_extend_hint),
            valueColor = MaterialTheme.colorScheme.primary,
            onClick = { viewModel.setExtendStayOnComment(!extendStayOnComment) },
        )

        // The check itself is started at the top of the settings screen, not here: a
        // LaunchedEffect inside the scroll only runs when it is scrolled into view.
        ActionRow(
            label = stringResource(R.string.settings_review_status),
            value = reviewStatus.label(),
            valueColor = when (reviewStatus) {
                is ReviewStatus.Online -> MaterialTheme.colorScheme.primary
                is ReviewStatus.Failed -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
            onClick = { viewModel.refreshReviewStatus() },
        )

        // What used to be here and is not any more, on purpose: the danmaku amount (关 / 少 /
        // 多) and the two display switches for the code and the layer. All three moved to
        // the viewer's 图评 button, which is where they are actually decided — while looking
        // at a photo. What stays here is what is genuinely set up once per install.
    }
}

// ----------------------------------------------------------------------- rows

/**
 * The value and the hint the 检测升级 row shows for [state].
 *
 * [releasePageUrl] is carried into the hint whenever the answer is "there is something to
 * install and this row cannot finish the job" - an unverifiable release, a permission the
 * user has not granted, an installer that would not open. That is the workaround the row
 * owes the user: the same APK, fetched by hand on a device that can do it.
 */
private data class UpdateRowText(val value: String, val hint: String?)

@Composable
private fun updateRowText(state: UpdateState, releasePageUrl: String): UpdateRowText = when (state) {
    is UpdateState.Idle -> UpdateRowText(stringResource(R.string.settings_update_idle), null)

    is UpdateState.Checking -> UpdateRowText(stringResource(R.string.settings_update_checking), null)

    is UpdateState.UpToDate ->
        UpdateRowText(stringResource(R.string.settings_update_up_to_date), null)

    is UpdateState.Available -> UpdateRowText(
        value = stringResource(R.string.settings_update_available, state.release.versionName),
        // The red dot on the rail is the point of this hint: it is what the dot means, and
        // the one place a user can read what it was telling them.
        hint = stringResource(
            if (state.release.isInstallable) {
                R.string.settings_update_hint_available
            } else {
                R.string.settings_update_hint_tag_only
            },
            releasePageUrl,
        ) + " · " + stringResource(R.string.settings_update_badge_hint),
    )

    is UpdateState.Downloading -> UpdateRowText(
        value = stringResource(R.string.settings_update_downloading, state.percent),
        hint = stringResource(R.string.settings_update_hint_downloading),
    )

    is UpdateState.NeedsPermission -> UpdateRowText(
        value = stringResource(R.string.settings_update_needs_permission),
        hint = stringResource(R.string.settings_update_hint_permission),
    )

    is UpdateState.ReadyToInstall -> UpdateRowText(
        value = stringResource(R.string.settings_update_ready),
        hint = stringResource(R.string.settings_update_hint_ready),
    )

    is UpdateState.Failed -> UpdateRowText(
        value = stringResource(
            when (state.kind) {
                UpdateState.Failed.Kind.CHECK -> R.string.settings_update_failed_check
                UpdateState.Failed.Kind.DOWNLOAD -> R.string.settings_update_failed_download
                UpdateState.Failed.Kind.VERIFY -> R.string.settings_update_failed_verify
                UpdateState.Failed.Kind.SIGNATURE -> R.string.settings_update_failed_signature
                UpdateState.Failed.Kind.INSTALL -> R.string.settings_update_failed_install
            },
        ),
        // A refused installer is not something pressing OK again is likely to fix, so that
        // one points at the manual route instead of at itself. Everything else repeats the
        // reason the failure gave, when it gave one: on a television this row is the only
        // place that reason can be read, and it is the difference between "检查失败" and
        // "无法解析主机名" - one is a mystery, the other is a diagnosis.
        hint = when {
            state.kind == UpdateState.Failed.Kind.INSTALL ->
                stringResource(R.string.settings_update_hint_manual, releasePageUrl)

            !state.detail.isNullOrBlank() ->
                stringResource(R.string.settings_update_hint_reason, state.detail)

            else -> stringResource(R.string.settings_update_hint_retry)
        },
    )
}

