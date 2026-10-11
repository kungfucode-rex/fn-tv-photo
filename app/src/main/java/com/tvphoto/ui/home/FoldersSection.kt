package com.tvphoto.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.R
import com.tvphoto.data.FolderLayout
import com.tvphoto.domain.FolderItem
import com.tvphoto.ui.ContentState
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.PositionKeys
import com.tvphoto.ui.PositionMemory
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.RetryButton
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.localizedError
import com.tvphoto.ui.rememberPositionMemory

/**
 * 文件夹, in either of two shapes.
 *
 * The grid is the default and the list is kept, because they answer different questions: a
 * grid tells you *how many folders you have and which is which* in one look, and a list
 * tells you *exactly what a folder is called and where it lives* without truncating either.
 *
 * Both share one position memory key. That is deliberate rather than lazy: switching shape
 * and landing somewhere else in the same library would be a second, invisible jump on top
 * of the one the user asked for.
 */
@Composable
fun FoldersSection(viewModel: MainViewModel) {
    val state by viewModel.folders.collectAsStateWithLifecycle()
    val layout by viewModel.folderLayout.collectAsStateWithLifecycle()
    val memory = rememberPositionMemory(PositionKeys.FOLDERS, viewModel)

    // The same trap 设置 fell into, and the same fix: the section's content is composed
    // after the navigation rail, so Compose's focus order hands the D-pad to the rail and
    // the user can stand on a section without ever reaching anything in it. Without this,
    // the layout toggle is unreachable by remote — pressing up from the first card walks
    // back into the rail instead of up to the header.
    //
    // Focus is claimed by the *toggle* rather than by the first folder, which is a choice
    // about discoverability: a control nobody can find is the same as one that is not
    // there, and this one is new. The cost is one press of DOWN to reach the folders, paid
    // once per visit; the alternative is a button that only the person who wrote it knows
    // how to reach.
    val toggleFocus = remember { FocusRequester() }
    val claimFocus = remember { viewModel.savedIndex(PositionKeys.FOLDERS) < 0 }
    LaunchedEffect(Unit) {
        if (claimFocus) runCatching { toggleFocus.requestFocus() }
    }
    LaunchedEffect(claimFocus) {
        if (claimFocus) viewModel.saveIndex(PositionKeys.FOLDERS, 0)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        SectionHeader(
            title = stringResource(R.string.nav_folders),
            caption = (state as? ContentState.Ready)?.let {
                stringResource(R.string.album_count, it.value.size)
            },
            trailing = {
                LayoutToggle(
                    layout = layout,
                    onClick = viewModel::toggleFolderLayout,
                    modifier = Modifier.focusRequester(toggleFocus),
                )
            },
        )
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            ContentState.Loading -> StatusMessage(stringResource(R.string.loading))

            is ContentState.Failed -> StatusMessage(localizedError(current.message)) {
                RetryButton { viewModel.loadFolders(force = true) }
            }

            is ContentState.Ready -> if (current.value.isEmpty()) {
                StatusMessage(stringResource(R.string.empty))
            } else {
                when (layout) {
                    FolderLayout.GRID -> FolderGrid(current.value, memory) { folder ->
                        viewModel.openFolder(folder.path, folder.name)
                    }

                    FolderLayout.LIST -> FolderList(current.value, memory) { folder ->
                        viewModel.openFolder(folder.path, folder.name)
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderGrid(
    folders: List<FolderItem>,
    memory: PositionMemory,
    onOpen: (FolderItem) -> Unit,
) {
    val gridState = rememberLazyGridState()

    LaunchedEffect(memory) {
        if (memory.hasRestorePoint) {
            gridState.scrollToItem(memory.restoreIndex)
            memory.requestFocusOnRestoredItem()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(4),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        // Room above the first row: the focused card scales up by 5%, and without this the
        // grid's viewport cuts its top border off.
        contentPadding = PaddingValues(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        itemsIndexed(
            items = folders,
            key = { _, folder -> folder.path },
        ) { index, folder ->
            FolderCard(
                folder = folder,
                onClick = { onOpen(folder) },
                modifier = memory.modifierFor(index),
            )
        }
    }
}

@Composable
private fun FolderList(
    folders: List<FolderItem>,
    memory: PositionMemory,
    onOpen: (FolderItem) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(memory) {
        if (memory.hasRestorePoint) {
            listState.scrollToItem(memory.restoreIndex)
            memory.requestFocusOnRestoredItem()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(
            items = folders,
            key = { _, folder -> folder.path },
        ) { index, folder ->
            FolderRow(
                folder = folder,
                onClick = { onOpen(folder) },
                modifier = memory.modifierFor(index),
            )
        }
    }
}

/**
 * The 网格 / 列表 switch, in the section header's trailing slot.
 *
 * One button that names what it will switch *to*, not two radio buttons: there are two
 * states and one of them is always current, so a pair would spend twice the width saying
 * the same thing. The label is what the press will do.
 */
@Composable
private fun LayoutToggle(
    layout: FolderLayout,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val target = layout.toggled()
    val label = when (target) {
        FolderLayout.GRID -> stringResource(R.string.folders_layout_grid)
        FolderLayout.LIST -> stringResource(R.string.folders_layout_list)
    }

    FocusableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/**
 * One folder as a card.
 *
 * No cover image, because a managed folder has none to offer — the API returns a path, a
 * name and two counts — and inventing one would mean fetching a photo per folder on every
 * visit to this screen. The counts are what is actually known, so they are what the middle
 * of the card shows.
 */
@Composable
private fun FolderCard(
    folder: FolderItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.folders_count, folder.totalCount),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        R.string.photos_videos_count,
                        folder.photoCount,
                        folder.videoCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun FolderRow(
    folder: FolderItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                // The count leads, matching the grid card's centre: the two layouts are the
                // same list twice, and a number that moves from the middle to the far right
                // would read as a different piece of information.
                text = stringResource(R.string.folders_count, folder.totalCount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                modifier = Modifier.width(96.dp),
            )
            Spacer(Modifier.width(20.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = folder.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = stringResource(
                    R.string.photos_videos_count,
                    folder.photoCount,
                    folder.videoCount,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
