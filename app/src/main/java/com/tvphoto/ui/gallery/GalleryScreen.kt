package com.tvphoto.ui.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.tvphoto.R
import com.tvphoto.domain.MediaItem
import com.tvphoto.domain.SubFolder
import com.tvphoto.ui.ContentState
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.Screen
import com.tvphoto.ui.cacheKey
import com.tvphoto.ui.components.Badge
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.PrefetchAhead
import com.tvphoto.ui.components.RetryButton
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.localizedError
import com.tvphoto.ui.displayTitle
import com.tvphoto.ui.rememberPositionMemory
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun GalleryScreen(viewModel: MainViewModel, screen: Screen) {
    val gallery by viewModel.gallery.collectAsStateWithLifecycle()
    val media = gallery.items.valueOrNull.orEmpty()
    val gridState = rememberLazyGridState()
    val memory = rememberPositionMemory(screen.cacheKey, viewModel)
    val fallbackFocus = remember { FocusRequester() }

    // The photo the user is on. Prefetching is anchored here, not at the top of the
    // list, so what gets warmed is what they are about to page into.
    //
    // Held as state rather than read as a value in this scope on purpose: every D-pad
    // move updates it, and a plain read here would rebuild this whole screen — grid,
    // item lambdas and all — for a number only the prefetch window uses.
    val anchor = remember { mutableIntStateOf(memory.restoreIndex.coerceAtLeast(0)) }

    // Folders occupy a full-width header row ahead of the photos, so grid indices are
    // offset by one on folder screens.
    val headerRows = if (gallery.subFolders.isNotEmpty()) 1 else 0

    // Request the next page slightly before the end so scrolling never stalls.
    LaunchedEffect(gridState, media.size, gallery.canLoadMore) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastVisible ->
                if (gallery.canLoadMore && lastVisible >= media.size - PREFETCH_DISTANCE) {
                    viewModel.loadMore()
                }
            }
    }

    // Warm the disk cache ahead of the current position, so opening or paging through
    // a photo does not wait on the network.
    PrefetchAhead(
        itemCount = media.size,
        anchor = anchor,
        prefetch = { from -> viewModel.warmImageCache(media, from) },
    )

    // Restore where the user was, or focus the first thing on a first visit. This is
    // what makes backing out of the viewer land on the same photo.
    LaunchedEffect(screen.cacheKey, media.isNotEmpty(), headerRows) {
        if (media.isEmpty() && headerRows == 0) return@LaunchedEffect
        if (memory.hasRestorePoint) {
            gridState.scrollToItem((memory.restoreIndex + headerRows).coerceAtLeast(0))
            memory.requestFocusOnRestoredItem()
        } else {
            runCatching { fallbackFocus.requestFocus() }
        }
    }

    val heading = gallery.title.ifBlank { screen.displayTitle(::fallbackMonthTitle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        SectionHeader(
            title = heading,
            caption = if (media.isNotEmpty()) {
                stringResource(R.string.photos_count, media.size) +
                    if (gallery.loadingMore) " · ${stringResource(R.string.loading)}" else ""
            } else {
                null
            },
        )
        Spacer(Modifier.height(18.dp))

        when (val state = gallery.items) {
            ContentState.Loading -> StatusMessage(stringResource(R.string.loading))

            is ContentState.Failed -> StatusMessage(localizedError(state.message)) {
                RetryButton { viewModel.retryGallery() }
            }

            is ContentState.Ready -> if (state.value.isEmpty() && gallery.subFolders.isEmpty()) {
                StatusMessage(stringResource(R.string.empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    // Room above the first row: the focused tile scales up by 5%, and
                    // without this the viewport cuts its top border off.
                    contentPadding = PaddingValues(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    // Folders come first, spanning the full width, because they are
                    // navigation rather than content.
                    if (gallery.subFolders.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SubFolderRow(
                                folders = gallery.subFolders,
                                onOpen = { viewModel.openFolder(it.path, it.name) },
                                focusRequester = fallbackFocus.takeIf { !memory.hasRestorePoint },
                            )
                        }
                    }

                    items(
                        count = state.value.size,
                        key = { index -> state.value[index].id },
                    ) { index ->
                        val entry = state.value[index]
                        val focus = if (index == 0 && headerRows == 0 && !memory.hasRestorePoint) {
                            Modifier.focusRequester(fallbackFocus)
                        } else {
                            Modifier
                        }
                        PhotoCell(
                            item = entry,
                            modifier = memory.modifierFor(index)
                                .onFocusChanged { if (it.isFocused) anchor.intValue = index }
                                .then(focus),
                            onClick = { viewModel.openViewer(index = index, title = heading) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubFolderRow(
    folders: List<SubFolder>,
    onOpen: (SubFolder) -> Unit,
    focusRequester: FocusRequester?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp),
    ) {
        Text(
            text = stringResource(R.string.nav_folders),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(count = folders.size, key = { folders[it].path }) { index ->
                val folder = folders[index]
                FocusableSurface(
                    onClick = { onOpen(folder) },
                    shape = RoundedCornerShape(10.dp),
                    modifier = if (index == 0 && focusRequester != null) {
                        Modifier.focusRequester(focusRequester)
                    } else {
                        Modifier
                    },
                ) {
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .width(200.dp)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun PhotoCell(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AsyncImage(
                model = item.thumbnailUrl,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (item.isVideo) Badge(stringResource(R.string.badge_video))
                if (item.hasMotionClip) Badge(stringResource(R.string.badge_live))
            }
        }
    }
}

private const val PREFETCH_DISTANCE = 8

/** Fallback used only when the view model has not produced a title yet. */
private fun fallbackMonthTitle(year: Int, month: Int): String = "%04d-%02d".format(year, month)
