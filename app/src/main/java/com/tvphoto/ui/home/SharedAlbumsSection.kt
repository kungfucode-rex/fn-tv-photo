package com.tvphoto.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.tvphoto.R
import com.tvphoto.domain.AlbumItem
import com.tvphoto.ui.ContentState
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.PositionKeys
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.RetryButton
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.localizedError
import com.tvphoto.ui.rememberPositionMemory

/**
 * Albums other accounts have shared with this one ("他人分享相册").
 *
 * These arrive from `album_grant/list_to_me`, a route of their own rather than a
 * filter on the account's own albums, and they open through the ordinary album grid.
 *
 * An empty list is a normal state rather than a failure — it means nobody has shared
 * an album with this account — so the empty state says so instead of offering a
 * pointless retry, the same way the person list handles AI indexing being off.
 */
@Composable
fun SharedAlbumsSection(viewModel: MainViewModel) {
    val state by viewModel.sharedAlbums.collectAsStateWithLifecycle()
    val memory = rememberPositionMemory(PositionKeys.SHARED, viewModel)
    val gridState = rememberLazyGridState()

    LaunchedEffect(memory) {
        if (memory.hasRestorePoint) {
            gridState.scrollToItem(memory.restoreIndex)
            memory.requestFocusOnRestoredItem()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        SectionHeader(
            title = stringResource(R.string.nav_shared),
            caption = (state as? ContentState.Ready)?.let {
                stringResource(R.string.album_count, it.value.size)
            },
        )
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            ContentState.Loading -> StatusMessage(stringResource(R.string.loading))

            is ContentState.Failed -> StatusMessage(localizedError(current.message)) {
                RetryButton { viewModel.loadSharedAlbums(force = true) }
            }

            is ContentState.Ready -> if (current.value.isEmpty()) {
                StatusMessage(stringResource(R.string.shared_albums_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    // Room above the first row: the focused card scales up by 5%, and
                    // without this the grid's viewport cuts its top border off.
                    contentPadding = PaddingValues(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(
                        items = current.value,
                        key = { _, album -> album.id },
                    ) { index, album ->
                        SharedAlbumCard(
                            album = album,
                            onClick = { viewModel.openAlbum(album) },
                            modifier = memory.modifierFor(index),
                        )
                    }
                }
            }
        }
    }
}

/** Like the album card, but it also names whoever shared the album. */
@Composable
private fun SharedAlbumCard(
    album: AlbumItem,
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
            ) {
                if (album.coverUrl != null) {
                    AsyncImage(
                        model = album.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            // Three lines rather than the album card's two — the name, who shared it and
            // the counts — so they need the gaps and the margin the extra line costs.
            // Without them the block reads as one grey lump hanging off the cover.
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = album.name.ifBlank { stringResource(R.string.album_untitled) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (album.isShared) {
                    Text(
                        text = stringResource(R.string.shared_by, album.ownerName),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(
                        R.string.photos_videos_count,
                        album.photoCount,
                        album.videoCount,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
