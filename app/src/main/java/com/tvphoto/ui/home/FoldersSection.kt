package com.tvphoto.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.R
import com.tvphoto.domain.FolderItem
import com.tvphoto.ui.ContentState
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.PositionKeys
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.RetryButton
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.localizedError
import com.tvphoto.ui.rememberPositionMemory

@Composable
fun FoldersSection(viewModel: MainViewModel) {
    val state by viewModel.folders.collectAsStateWithLifecycle()
    val memory = rememberPositionMemory(PositionKeys.FOLDERS, viewModel)
    val listState = rememberLazyListState()

    LaunchedEffect(memory) {
        if (memory.hasRestorePoint) {
            listState.scrollToItem(memory.restoreIndex)
            memory.requestFocusOnRestoredItem()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        SectionHeader(title = stringResource(R.string.nav_folders))
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            ContentState.Loading -> StatusMessage(stringResource(R.string.loading))

            is ContentState.Failed -> StatusMessage(localizedError(current.message)) {
                RetryButton { viewModel.loadFolders(force = true) }
            }

            is ContentState.Ready -> if (current.value.isEmpty()) {
                StatusMessage(stringResource(R.string.empty))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    // Room above the first row: the focused row scales up by 5%, and
                    // without this the viewport cuts its top border off.
                    contentPadding = PaddingValues(top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(
                        items = current.value,
                        key = { _, folder -> folder.path },
                    ) { index, folder ->
                        FolderRow(
                            folder = folder,
                            onClick = { viewModel.openFolder(folder.path, folder.name) },
                            modifier = memory.modifierFor(index),
                        )
                    }
                }
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
            Column(modifier = Modifier.width(320.dp)) {
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
