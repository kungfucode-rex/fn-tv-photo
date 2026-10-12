package com.tvphoto.ui.music

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.zxing.common.BitMatrix
import com.tvphoto.R
import com.tvphoto.data.music.MusicEntry
import com.tvphoto.data.music.MusicQr
import com.tvphoto.data.music.MusicSource
import com.tvphoto.data.music.MusicUploadProtocol
import com.tvphoto.data.music.MusicUploadServer
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.components.ActionRow
import com.tvphoto.ui.components.InfoRow
import com.tvphoto.ui.components.formatFileSize
import kotlinx.coroutines.delay

/**
 * 上传背景音乐: a QR code on the left, the playlist on the right.
 *
 * One screen for both the phone route and the list it feeds, because they are one act: the
 * phone sends a file, the television shows it arriving at the top, and the user ticks it to
 * hear it. Reached from the viewer's track list and from 设置 → 幻灯片设置 → 上传背景音乐, and
 * Back returns to whichever of them it was opened from — the navigation stack remembers, so
 * nothing here has to.
 *
 * The list is the playlist: its order is the order the tracks loop in, its numbers are the
 * positions, and 排序 writes the stored order the library then reads back. The three modes a
 * row can be in ([RowMode]) are what the remote's keys mean while a row is being managed —
 * and they exist so that OK never means two things at once: it ticks a track in the normal
 * mode, does the highlighted button in the actions, and moves the track in reordering.
 */
@Composable
fun MusicUploadScreen(viewModel: MainViewModel) {
    val library by viewModel.musicLibrary.collectAsStateWithLifecycle()
    val upload by viewModel.musicUpload.collectAsStateWithLifecycle()
    val selected by viewModel.slideshowMusic.collectAsStateWithLifecycle()

    var mode by remember { mutableStateOf<RowMode>(RowMode.Normal) }
    var notice by remember { mutableStateOf<PageNotice?>(null) }

    // Whether an OK press has been seen *since* the arrows came out. It is what tells a
    // confirmation apart from the tail of the press that chose 排序: that press ends as this
    // mode begins, and treating its release as a confirmation would flash the arrows on and
    // off. A confirmation has to be a press that started here. Declared above the handlers
    // that write it, because a local function may only see locals declared before it.
    var reorderPressSeen by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val firstRow = remember { FocusRequester() }

    // The listener exists exactly as long as this page does: this is the page that is *for*
    // receiving from a phone, so opening it starts the server and leaving it stops the server.
    LaunchedEffect(Unit) { viewModel.startMusicUpload() }
    DisposableEffect(Unit) { onDispose { viewModel.stopMusicUpload() } }

    LaunchedEffect(Unit) {
        viewModel.refreshMusicLibrary()
        runCatching { firstRow.requestFocus() }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MILLIS)
            notice = null
        }
    }

    // --------------------------------------------------- a new upload arriving
    //
    // The library is ordered with the newest upload first, so "what is new" is "what the
    // previous reading did not have". The row's border then flashes twice: the answer to
    // "did my song arrive" should be something the eye catches, not a number in a corner
    // that the user has to compare against one they remember.
    var known by remember { mutableStateOf<Set<String>?>(null) }
    var flashId by remember { mutableStateOf<String?>(null) }
    val flash = remember { Animatable(0f) }

    LaunchedEffect(library.entries) {
        val ids = library.entries.map { it.id }
        val before = known
        known = ids.toSet()
        if (before == null) return@LaunchedEffect
        val arrived = ids.firstOrNull { it !in before } ?: return@LaunchedEffect
        flashId = arrived
        runCatching { listState.animateScrollToItem(0) }
    }

    LaunchedEffect(flashId) {
        if (flashId == null) return@LaunchedEffect
        repeat(FLASH_COUNT) {
            flash.animateTo(1f, tween(FLASH_MILLIS))
            flash.animateTo(0f, tween(FLASH_MILLIS))
        }
        flash.animateTo(0f, tween(1))
        flashId = null
    }

    // ------------------------------------------------------------ keys and modes
    //
    // Preview, on the page's root: while a row is being managed, every key belongs to that
    // management and none of them reaches the list underneath.

    /** OK in the actions: with one chosen, 排序 opens the arrows and 删除 removes the track. */
    fun confirmAction(current: RowMode.Actions) {
        when (current.focus) {
            // Nothing chosen yet, so this press means nothing: the buttons are out and ← / →
            // decide which of them OK would do. This is the branch that keeps the release
            // ending a long press from acting on a button nobody selected.
            null -> Unit

            ActionFocus.SORT -> {
                mode = current.afterActionConfirm()
                // The press that chose 排序 ends here, so nothing about it may count as a
                // confirmation of the mode it just started.
                reorderPressSeen = false
            }

            ActionFocus.DELETE -> {
                library.entries.firstOrNull { it.id == current.id }?.let { entry ->
                    viewModel.deleteMusicTrack(entry)
                    notice = PageNotice.Deleted(entry.title)
                }
                mode = RowMode.Normal
            }
        }
    }

    val pageKeys: (KeyEvent) -> Boolean = { event ->
        // Auto-repeat is ignored in both management modes, and that is deliberate: the repeats
        // that belong to the long press which opened the buttons arrive *after* the buttons
        // are on screen, and one of them landing as "move down" is how a long press used to
        // reorder the list by itself. One press, one step.
        val fresh = !event.isRepeat
        when (val current = mode) {
            is RowMode.Reordering -> {
                when (event.key) {
                    // Only the arrows move the track, and only on a fresh press of them. Every
                    // other key — OK above all — is swallowed rather than read as "down",
                    // which is exactly how the OK that chose 排序 used to shift the row one
                    // place further.
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (event.type == KeyEventType.KeyDown && fresh) {
                            current.reorderDelta(keyUp = event.key == Key.DirectionUp)?.let { delta ->
                                viewModel.moveMusic(current.id, delta)
                            }
                        }
                        true
                    }

                    // OK confirms the arrangement and leaves the mode, which is a change from
                    // 1.5.4: there, Back was the only way out and OK did nothing at all.
                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        when (event.type) {
                            KeyEventType.KeyDown -> reorderPressSeen = true
                            KeyEventType.KeyUp -> if (reorderPressSeen) {
                                reorderPressSeen = false
                                mode = RowMode.Normal
                            }

                            else -> Unit
                        }
                        true
                    }

                    // Everything else is swallowed: an up/down that hit the end of the list must
                    // not fall through and scroll the list out from under the row being moved.
                    else -> true
                }
            }

            is RowMode.Actions -> {
                when (event.key) {
                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (event.type == KeyEventType.KeyDown && fresh) {
                            mode = current.stepActionFocus(if (event.key == Key.DirectionLeft) -1 else 1)
                        }
                        true
                    }

                    Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
                        if (event.type == KeyEventType.KeyUp) confirmAction(current)
                        true
                    }

                    // Up/down do not move the cursor off a row that has its buttons out.
                    Key.DirectionUp, Key.DirectionDown -> true

                    else -> false
                }
            }

            RowMode.Normal -> false
        }
    }

    // Back leaves the management, and for the arrows that is also a confirmation: each press
    // already moved the track and stored the order, so there is nothing left to commit.
    BackHandler(enabled = mode != RowMode.Normal) {
        mode = mode.afterBack()
        reorderPressSeen = false
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent(pageKeys),
    ) {
        UploadSide(
            upload = upload,
            usedBytes = library.usedBytes,
            notice = notice,
        )

        Playlist(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            firstRow = firstRow,
            entries = library.entries,
            selected = selected,
            mode = mode,
            flashId = flashId,
            flash = flash.value,
            listState = listState,
            uploads = library.uploadCount,
            removedBuiltins = library.removedBuiltins,
            directory = library.directory,
            onRowPress = { entry ->
                viewModel.setSlideshowMusic(
                    if (entry.id in selected) selected - entry.id else selected + entry.id,
                )
            },
            onRowLongPress = { entry -> mode = mode.afterLongPress(entry.id) },
            onRestoreBuiltin = {
                viewModel.restoreBuiltinMusic()
                notice = PageNotice.Restored
            },
            onClear = {
                viewModel.clearMusicUploads()
                notice = PageNotice.Cleared
            },
            onAnyFocus = { mode = RowMode.Normal },
        )
    }
}

// ------------------------------------------------------------------- the left side

/**
 * The title, and under it the code the phone scans.
 *
 * Laid out as the specification asks — title at the top left, code and its instructions at
 * the bottom left — and the empty middle is deliberate: it puts the code at the height a
 * phone held in front of a television is naturally pointed at, rather than level with the
 * title, where the user has to hold the phone up against the screen's top edge.
 */
@Composable
private fun UploadSide(
    upload: MusicUploadServer.State,
    usedBytes: Long,
    notice: PageNotice?,
) {
    val url = upload.url
    val code = remember(url) {
        url?.let { runCatching { MusicQr.matrix(it).toImageBitmap() }.getOrNull() }
    }

    Column(
        modifier = Modifier
            .width(LEFT_COLUMN_WIDTH.dp)
            .fillMaxHeight()
            .padding(start = 40.dp, top = 32.dp, end = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = stringResource(R.string.music_upload_title),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        notice?.let {
            Text(
                text = noticeText(it),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        // The block sits above centre rather than against the bottom of the screen: a code
        // held here is at the height a phone in front of the television is pointed at, and
        // the 18dp gap under the card is what keeps the address and the three steps from
        // reading as part of the code itself.
        Spacer(Modifier.weight(0.35f))

        // The white card is the quiet zone: the matrix has none of its own, so the finder
        // patterns sit at the edge of exactly this much white.
        Box(
            modifier = Modifier
                .size(QR_CARD_DP.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            when {
                code != null -> Image(
                    bitmap = code,
                    contentDescription = stringResource(R.string.music_upload_qr_description),
                    modifier = Modifier.size(QR_SIZE_DP.dp),
                )

                url == null && upload.error != null -> Text(
                    text = stringResource(R.string.music_upload_no_address),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black,
                    modifier = Modifier.padding(18.dp),
                )

                else -> Text(
                    text = stringResource(R.string.music_upload_preparing),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black,
                )
            }
        }

        Spacer(Modifier.height(18.dp))

        if (url != null) {
            Text(
                text = url,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = stringResource(R.string.music_upload_step_1),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.music_upload_step_2),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.music_upload_step_3),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(
                R.string.music_upload_received,
                upload.received,
                sizeLabel(upload.bytes),
            ),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        // The last two results only: this is a status line under a code, and a list of
        // twenty uploads belongs nowhere near it.
        upload.lines.takeLast(2).forEach { line ->
            val failure = line.failure
            Text(
                text = if (failure == null) {
                    "\u2713 ${line.name}"
                } else {
                    "\u2717 ${line.name} · ${stringResource(uploadFailureText(failure))}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (failure == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(
                R.string.music_usage_value,
                sizeLabel(usedBytes),
                sizeLabel(MusicUploadProtocol.LIBRARY_LIMIT_BYTES),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.music_upload_stay),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // What is left of the column's height stays under the text, so the block above keeps
        // its place when the status lines come and go as uploads arrive.
        Spacer(Modifier.weight(0.65f))
    }
}

// ------------------------------------------------------------------ the right side

/** The playlist: numbered rows, the bundled four under them, and the housekeeping rows. */
@Composable
private fun Playlist(
    firstRow: FocusRequester,
    entries: List<MusicEntry>,
    selected: List<String>,
    mode: RowMode,
    flashId: String?,
    flash: Float,
    listState: LazyListState,
    uploads: Int,
    removedBuiltins: Int,
    directory: String,
    onRowPress: (MusicEntry) -> Unit,
    onRowLongPress: (MusicEntry) -> Unit,
    onRestoreBuiltin: () -> Unit,
    onClear: () -> Unit,
    onAnyFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val numbered = numberedTracks(entries)

    LazyColumn(
        state = listState,
        modifier = modifier.padding(top = 32.dp, end = 40.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.music_page_hint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (numbered.isEmpty()) {
            item { EmptyHint(stringResource(R.string.music_page_empty)) }
        }

        itemsIndexed(numbered, key = { _, row -> row.entry.id }) { index, row ->
            TrackRow(
                row = row,
                ticked = row.entry.id in selected,
                mode = mode,
                flashing = flashId == row.entry.id,
                flash = flash,
                // The cursor opens on the first row, which is where a press can land.
                modifier = if (index == 0) Modifier.focusRequester(firstRow) else Modifier,
                onPress = { onRowPress(row.entry) },
                onLongPress = { onRowLongPress(row.entry) },
                onFocus = onAnyFocus,
            )
        }

        item { Spacer(Modifier.height(10.dp)) }

        if (removedBuiltins > 0) {
            item {
                ActionRow(
                    label = stringResource(R.string.music_restore_builtin),
                    value = removedBuiltins.toString(),
                    hint = stringResource(R.string.music_restore_builtin_hint),
                    valueColor = MaterialTheme.colorScheme.primary,
                    onClick = onRestoreBuiltin,
                    // With nothing left in the list, this is the first pressable row here.
                    modifier = if (numbered.isEmpty()) Modifier.focusRequester(firstRow) else Modifier,
                )
            }
        }
        if (uploads > 0) {
            item {
                ActionRow(
                    label = stringResource(R.string.music_clear),
                    value = stringResource(R.string.music_clear_value, uploads),
                    hint = stringResource(R.string.music_clear_hint),
                    valueColor = MaterialTheme.colorScheme.primary,
                    onClick = onClear,
                    modifier = if (numbered.isEmpty() && removedBuiltins == 0) {
                        Modifier.focusRequester(firstRow)
                    } else {
                        Modifier
                    },
                )
            }
        }

        // Where the files actually are, last and unfocusable: the answer to "can I put one
        // there with a cable", which is a question the page is asked by anyone who would
        // rather not use a phone for it.
        item {
            InfoRow(
                label = stringResource(R.string.music_directory),
                value = directory,
                detail = stringResource(R.string.music_directory_hint),
            )
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

/**
 * One track: its number, its name, and whatever is currently being done to it.
 *
 * Two press lengths on one key, and the difference matters: a press ticks the track into the
 * show, a held press brings out the row's own controls. That is why this does not use
 * `clickable` — a click and a long click have to be told apart here, and the duration is the
 * only thing that tells them apart.
 */
@Composable
private fun TrackRow(
    row: NumberedTrack,
    ticked: Boolean,
    mode: RowMode,
    flashing: Boolean,
    flash: Float,
    onPress: () -> Unit,
    onLongPress: (() -> Unit)?,
    onFocus: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = row.entry
    val primary = MaterialTheme.colorScheme.primary
    var focused by remember { mutableStateOf(false) }
    var pressedAt by remember { mutableLongStateOf(0L) }
    // Whether the press in progress has already done its one job. A long press fires the
    // moment it becomes long — while the key is still held, the way long press works
    // everywhere else on a remote — and from then on the rest of that press is swallowed.
    var longFired by remember { mutableStateOf(false) }
    val managed = when (mode) {
        is RowMode.Actions -> mode.id == entry.id
        is RowMode.Reordering -> mode.id == entry.id
        RowMode.Normal -> false
    }
    val reordering = mode is RowMode.Reordering && mode.id == entry.id
    val actions = (mode as? RowMode.Actions)?.takeIf { it.id == entry.id }

    val shape = RoundedCornerShape(12.dp)
    // The arrival flash, measured in two places: the border goes to full-strength primary
    // and the fill lifts with it, twice. A border-only pulse at 1dp reads as a change of
    // focus rather than as "this is the file you just sent", which is why the fill moves too.
    val borderColor = when {
        flashing -> lerp(primary.copy(alpha = 0.35f), primary, flash)
        managed -> primary
        focused -> primary
        else -> MaterialTheme.colorScheme.border
    }
    val fill = when {
        flashing -> lerp(baseFill(managed, focused), primary.copy(alpha = 0.30f), flash)
        managed -> baseFill(true, focused)
        focused -> baseFill(false, true)
        else -> baseFill(false, false)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // The arrows sit above and below the row rather than beside it: "move this up" is
        // read from the shape, and a pair of arrows stacked at the right edge reads as a
        // stepper for a value.
        if (reordering) {
            ReorderArrow("\u25B2")
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) onFocus()
                }
                .focusable()
                .onPreviewKeyEvent { event ->
                    val centre = event.key == Key.DirectionCenter || event.key == Key.Enter
                    when {
                        centre && event.type == KeyEventType.KeyDown -> {
                            if (!event.isRepeat) {
                                pressedAt = System.currentTimeMillis()
                                longFired = false
                            } else if (!longFired && onLongPress != null && pressedAt != 0L &&
                                System.currentTimeMillis() - pressedAt >= LONG_PRESS_MILLIS
                            ) {
                                longFired = true
                                onLongPress()
                            }
                            true
                        }

                        centre && event.type == KeyEventType.KeyUp -> {
                            val startedAt = pressedAt
                            val fired = longFired
                            pressedAt = 0L
                            longFired = false
                            val held = if (startedAt == 0L) 0L else System.currentTimeMillis() - startedAt
                            when {
                                // The buttons are already out: this release is the tail of the
                                // long press, not a press of OK.
                                fired -> Unit

                                // A hold the platform reported as one press and one release,
                                // with no repeats in between (`adb shell input` does exactly
                                // that, and so do some remotes). The release is where a long
                                // press is recognised, and it still must not also tick.
                                held >= LONG_PRESS_MILLIS && onLongPress != null -> onLongPress()

                                startedAt != 0L -> onPress()

                                else -> Unit
                            }
                            true
                        }

                        else -> false
                    }
                }
                .clip(shape)
                .background(fill)
                .border(
                    width = if (focused || managed || flashing) 2.dp else 1.dp,
                    color = borderColor,
                    shape = shape,
                )
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = row.number.toString(),
                style = MaterialTheme.typography.titleSmall,
                color = if (focused || managed) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(NUMBER_WIDTH.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = when {
                        focused || managed -> primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = rowDetail(entry),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (actions != null) {
                ActionPill(
                    text = stringResource(R.string.music_row_sort),
                    highlighted = actions.focus == ActionFocus.SORT,
                )
                ActionPill(
                    text = stringResource(R.string.music_row_delete),
                    highlighted = actions.focus == ActionFocus.DELETE,
                )
            } else {
                Chip(
                    when (entry.source) {
                        MusicSource.BUILTIN -> stringResource(R.string.music_source_builtin)
                        MusicSource.UPLOAD -> stringResource(R.string.music_source_upload)
                    },
                )
                Text(
                    text = if (ticked) "\u2713 ${stringResource(R.string.music_ticked)}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = primary,
                )
            }
        }

        if (reordering) {
            ReorderArrow("\u25BC")
        }
    }
}

/** `3:03 · 3.0MB`. */
@Composable
private fun rowDetail(entry: MusicEntry): String {
    val parts = ArrayList<String>(2)
    if (entry.durationMs > 0) parts += formatDuration(entry.durationMs)
    if (entry.sizeBytes > 0) parts += sizeLabel(entry.sizeBytes)
    return parts.joinToString(" · ")
}

/**
 * The row's resting fill, before any flash: managed rows are tinted, the focused row uses
 * the surface variant, and everything else is the plain surface.
 */
@Composable
private fun baseFill(managed: Boolean, focused: Boolean): Color = when {
    managed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    focused -> MaterialTheme.colorScheme.surfaceVariant
    else -> MaterialTheme.colorScheme.surface
}

/** One of the row's two floating buttons. */@Composable
private fun ActionPill(text: String, highlighted: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(9.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (highlighted) primary else primary.copy(alpha = 0.18f))
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = primary,
                shape = shape,
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) MaterialTheme.colorScheme.onPrimary else primary,
        )
    }
}

/** A small label, used for 内置. */
@Composable
private fun Chip(text: String) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The up or down arrow of the reordering mode. */
@Composable
private fun ReorderArrow(glyph: String) {
    Text(
        text = glyph,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    )
}

// ------------------------------------------------------------------------ notices

/** What just happened, in the words of the page that did it. */
private sealed interface PageNotice {
    data class Deleted(val title: String) : PageNotice

    data object Cleared : PageNotice

    data object Restored : PageNotice
}

@Composable
private fun noticeText(notice: PageNotice): String = when (notice) {
    is PageNotice.Deleted -> stringResource(R.string.music_deleted, notice.title)
    PageNotice.Cleared -> stringResource(R.string.music_cleared)
    PageNotice.Restored -> stringResource(R.string.music_restore_builtin_done)
}

private fun uploadFailureText(failure: MusicUploadServer.UploadFailure): Int = when (failure) {
    MusicUploadServer.UploadFailure.TOO_LARGE -> R.string.music_upload_fail_too_large
    MusicUploadServer.UploadFailure.NOT_AUDIO -> R.string.music_upload_fail_not_audio
    MusicUploadServer.UploadFailure.LIBRARY_FULL -> R.string.music_upload_fail_library_full
    MusicUploadServer.UploadFailure.NO_SPACE -> R.string.music_upload_fail_no_space
    MusicUploadServer.UploadFailure.FAILED -> R.string.music_upload_fail_failed
}

/** Turns a zxing matrix into something Compose can draw. One pixel per module. */
private fun BitMatrix.toImageBitmap(): ImageBitmap {
    val pixels = IntArray(width * height)
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            pixels[row + x] = if (get(x, y)) BLACK else WHITE
        }
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    return bitmap.asImageBitmap()
}

/**
 * A size for a row, with zero spelled out.
 *
 * [formatFileSize] answers an empty string below one byte — its job elsewhere is to *omit*
 * a size the server did not send — and "已用  / 1.0GB" is what that reads as on a library
 * that has nothing in it yet, which is exactly the state this page opens in.
 */
private fun sizeLabel(bytes: Long): String = formatFileSize(bytes).ifBlank { "0B" }

/** `3:45`, or `1:02:03` for anything over an hour. */
internal fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private const val BLACK = 0xFF000000.toInt()
private const val WHITE = 0xFFFFFFFF.toInt()
private const val QR_CARD_DP = 200
private const val QR_SIZE_DP = 176
private const val LEFT_COLUMN_WIDTH = 380
private const val NUMBER_WIDTH = 32
private const val FLASH_COUNT = 2
private const val FLASH_MILLIS = 220
private const val NOTICE_MILLIS = 3_000L

/**
 * Whether this event is one of the auto-repeats of a key that is being held.
 *
 * Compose's own `KeyEvent` does not carry it, so it is read from the platform event: the
 * difference between the press and its repeats is what tells a long press apart from a
 * stream of separate ones.
 */
private val KeyEvent.isRepeat: Boolean
    get() = nativeKeyEvent.repeatCount > 0

/**
 * How long the centre key has to be held before it means "manage this row" instead of OK.
 *
 * Below the platform's own long-press timeout (500 ms) on purpose: a hold measured here
 * starts at the first KeyDown, and `adb shell input keyevent --longpress` — the only way to
 * test this on an emulator — holds for exactly that timeout. A quick tap is 100–150 ms, so
 * 400 ms still leaves no doubt which of the two a user meant.
 */
private const val LONG_PRESS_MILLIS = 400L
