package com.tvphoto.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.R
import com.tvphoto.domain.AlbumItem

/**
 * The album list, over the settings screen: pick the 屏保相册.
 *
 * It lists **every** album the account can see — its own and the ones other accounts shared
 * with it — and the two together are deliberate rather than two sections. This is a television
 * choosing what to play in a room; which route the album arrived by is not a distinction
 * anybody makes while looking at a list of names, and an album that could only be picked from
 * the second list would be a rule nobody could guess. A shared album says who shared it, which
 * is the part a user does need.
 *
 * A `Dialog` rather than an overlay drawn inside the screen, and that is the load-bearing
 * choice. This is a television: a list floating over a screen of focusable rows would leave
 * the D-pad able to walk out of the dialog and onto the rows behind it, and the user would be
 * pressing OK on things they cannot see. A dialog is its own window, so focus cannot leave it
 * — which is also why nothing here has to remember where the cursor was or restore it later.
 *
 * The **不指定** row is the **first** row, above the albums, and both halves of that are
 * deliberate. It is how the screensaver is switched off again — without it the only way back
 * to "nothing plays by itself" would be to clear the app's data — and a switch that lives at
 * the *bottom* of a list that can hold two hundred albums is a switch nobody finds: the D-pad
 * has to walk every album to reach it, which on this library is eight presses and on a real one
 * is a scroll nobody performs. It also puts the cursor's opening position — the first row —
 * on a row that is always there and always does something, so the dialog's initial focus can
 * never land on a row that a slow album list has not composed yet.
 *
 * Nothing here intercepts a key, and that is a bug this dialog shipped for one build. The
 * root used to swallow every event in the **preview** pass to guarantee that nothing leaked
 * behind the panel — which it did, and which also stopped the D-pad from ever reaching the
 * rows: `onPreviewKeyEvent` on an ancestor runs *before* the focused child, so up, down and OK
 * were eaten one node above the list that was supposed to act on them. On a television that
 * reads as a dialog that opens, shows the right albums, and cannot be used. What actually
 * keeps a key from reaching the screen behind is the dialog being its own window, which is
 * why none of this needs guarding.
 *
 * What *is* drawn is a tick on the stored album, which is the only place the dialog says which
 * one is set — the row under the cursor is not the same fact once the D-pad has moved, and
 * "which album plays at night" has to stay readable while the list is being read.
 */
@Composable
fun AlbumPickerDialog(
    albums: List<AlbumItem>,
    currentId: String?,
    onPick: (AlbumItem?) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        val listState = rememberLazyListState()
        val firstRow = remember { FocusRequester() }

        // Row 0 is 不指定 and the albums start at 1; the cursor opens on the album that is
        // currently set, or on 不指定 when none is. The question this dialog is usually asked is
        // "what is it now", and the answer should be under the cursor.
        val cursorStart = remember(albums, currentId) {
            val at = albums.indexOfFirst { it.id.toString() == currentId }
            if (at < 0) 0 else at + 1
        }

        LaunchedEffect(Unit) {
            // The list is scrolled to its own first item and the *fixed* 不指定 row above it is
            // what takes focus, so the opening position never depends on a row inside a lazy
            // list that has not been composed yet — which is what `requestFocus` failing on a
            // 200-album library would look like: a dialog that opens and ignores the D-pad.
            runCatching { listState.scrollToItem(0) }
            runCatching { firstRow.requestFocus() }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xCC000000)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(PICKER_WIDTH)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.border, RoundedCornerShape(18.dp))
                    .padding(vertical = 22.dp),
            ) {
                Text(
                    text = stringResource(R.string.screen_saver_pick_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    text = stringResource(R.string.screen_saver_pick_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )

                Spacer(Modifier.height(14.dp))

                // First, always: the off switch, and the row the cursor opens on when nothing
                // is set. See the note above for why it is not below the list.
                PickerRow(
                    label = stringResource(R.string.screen_saver_none),
                    detail = stringResource(R.string.screen_saver_none_hint),
                    ticked = currentId.isNullOrBlank(),
                    modifier = Modifier.focusRequester(firstRow),
                    onClick = { onPick(null) },
                )

                Spacer(Modifier.height(10.dp))

                if (albums.isEmpty()) {
                    Text(
                        text = stringResource(R.string.screen_saver_pick_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.heightIn(max = PICKER_MAX_HEIGHT),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        itemsIndexed(
                            items = albums,
                            key = { _, album -> album.id },
                        ) { index, album ->
                            PickerRow(
                                label = album.name.ifBlank {
                                    stringResource(R.string.album_untitled)
                                },
                                // A shared album names whoever shared it, and the counts come
                                // after: on a list of two dozen names, "whose album is this" is
                                // the question that decides, not how many photos are in it.
                                detail = buildString {
                                    if (album.isShared) {
                                        append(stringResource(R.string.shared_by, album.ownerName))
                                        append(" · ")
                                    }
                                    append(
                                        stringResource(
                                            R.string.photos_videos_count,
                                            album.photoCount,
                                            album.videoCount,
                                        ),
                                    )
                                },
                                // The tick is the stored setting, not the cursor: the cursor
                                // moves as the list is read, and "which album plays at night"
                                // has to stay readable while it does.
                                ticked = album.id.toString() == currentId,
                                onClick = { onPick(album) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One row of the picker: a thing to choose, what it is, and whether it is the choice. */
@Composable
private fun PickerRow(
    label: String,
    detail: String,
    ticked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        containerColor = if (ticked) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            Color.Transparent
        },
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        focusedScale = 1.02f,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (ticked) "\u2713" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                // The slot is always there, ticked or not, so the labels line up and the
                // tick reads as a state rather than as part of the name.
                modifier = Modifier.width(20.dp),
            )
        }
    }
}

private val PICKER_WIDTH = 620.dp

/**
 * How tall the list may grow before it scrolls.
 *
 * The panel is centred on a 1080p screen and has a title, a caption and the 不指定 row above
 * the list, so this leaves those room at the top rather than pushing the caption off it on a
 * library with a hundred albums.
 */
private val PICKER_MAX_HEIGHT = 420.dp
