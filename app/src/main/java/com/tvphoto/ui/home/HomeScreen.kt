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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tvphoto.R
import com.tvphoto.data.SessionState
import com.tvphoto.data.displayHost
import com.tvphoto.domain.PhotoStats
import com.tvphoto.ui.HomeSection
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.PositionKeys
import com.tvphoto.ui.components.FocusableSurface

@Composable
fun HomeScreen(viewModel: MainViewModel) {
    val section by viewModel.section.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val server = (sessionState as? SessionState.SignedIn)?.session?.baseUrl.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        NavRail(
            current = section,
            onSelect = viewModel::selectSection,
            server = server,
            stats = stats,
            onSignOut = viewModel::signOut,
            // Claim focus only when this section's list has no remembered position.
            // Otherwise returning from a photo grid would yank focus back to the
            // rail instead of landing where the user left off.
            shouldClaimFocus = viewModel.savedIndex(PositionKeys.forSection(section)) < 0,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            when (section) {
                HomeSection.Timeline -> TimelineSection(viewModel)
                HomeSection.Albums -> AlbumsSection(viewModel)
                HomeSection.Shared -> SharedAlbumsSection(viewModel)
                HomeSection.People -> PeopleSection(viewModel)
                HomeSection.Folders -> FoldersSection(viewModel)
                HomeSection.Settings -> SettingsSection(viewModel)
            }
        }
    }
}

@Composable
private fun NavRail(
    current: HomeSection,
    onSelect: (HomeSection) -> Unit,
    server: String,
    stats: PhotoStats?,
    onSignOut: () -> Unit,
    shouldClaimFocus: Boolean,
) {
    val selectedFocus = remember { FocusRequester() }

    LaunchedEffect(current, shouldClaimFocus) {
        if (shouldClaimFocus) runCatching { selectedFocus.requestFocus() }
    }

    Column(
        modifier = Modifier
            .width(250.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )

        Spacer(Modifier.height(16.dp))

        val items = listOf(
            HomeSection.Timeline to stringResource(R.string.nav_timeline),
            HomeSection.Albums to stringResource(R.string.nav_albums),
            HomeSection.Shared to stringResource(R.string.nav_shared),
            HomeSection.People to stringResource(R.string.nav_people),
            HomeSection.Folders to stringResource(R.string.nav_folders),
            HomeSection.Settings to stringResource(R.string.nav_settings),
        )

        items.forEach { (target, label) ->
            NavItem(
                label = label,
                selected = target == current,
                onClick = { onSelect(target) },
                modifier = if (target == current) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }

        Spacer(Modifier.weight(1f))

        if (stats != null) {
            Text(
                text = stringResource(R.string.stat_summary, stats.photoCount, stats.videoCount),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        if (server.isNotBlank()) {
            Text(
                text = displayHost(server),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        FocusableSurface(
            onClick = onSignOut,
            shape = RoundedCornerShape(10.dp),
            containerColor = Color.Transparent,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.logout),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
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
