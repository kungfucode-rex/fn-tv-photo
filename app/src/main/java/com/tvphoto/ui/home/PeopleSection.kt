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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.tvphoto.R
import com.tvphoto.domain.Person
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
 * Face clusters from the gallery's AI ("人物").
 *
 * An empty list is a normal state rather than a failure: it means AI indexing is off
 * on the NAS, so the empty state explains how to enable it instead of offering a
 * pointless retry.
 */
@Composable
fun PeopleSection(viewModel: MainViewModel) {
    val state by viewModel.people.collectAsStateWithLifecycle()
    val memory = rememberPositionMemory(PositionKeys.PEOPLE, viewModel)
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
            title = stringResource(R.string.nav_people),
            caption = (state as? ContentState.Ready)?.let {
                stringResource(R.string.people_count, it.value.size)
            },
        )
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            ContentState.Loading -> StatusMessage(stringResource(R.string.loading))

            is ContentState.Failed -> StatusMessage(localizedError(current.message)) {
                RetryButton { viewModel.loadPeople(force = true) }
            }

            is ContentState.Ready -> if (current.value.isEmpty()) {
                StatusMessage(stringResource(R.string.people_empty))
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(5),
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
                        key = { _, person -> person.id },
                    ) { index, person ->
                        PersonCard(
                            person = person,
                            onClick = { viewModel.openPerson(person) },
                            modifier = memory.modifierFor(index),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonCard(
    person: Person,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.72f)
                    .aspectRatio(1f)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (person.avatarUrl != null) {
                    AsyncImage(
                        model = person.avatarUrl,
                        contentDescription = person.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = person.name.ifBlank { stringResource(R.string.person_untitled) },
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.photos_count, person.count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
