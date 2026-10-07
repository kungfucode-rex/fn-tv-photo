package com.tvphoto.ui.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.tvphoto.R
import com.tvphoto.domain.TimelineMonth
import com.tvphoto.domain.TimelineYear
import com.tvphoto.ui.ContentState
import com.tvphoto.ui.MainViewModel
import com.tvphoto.ui.PositionKeys
import com.tvphoto.ui.components.FocusableSurface
import com.tvphoto.ui.components.RetryButton
import com.tvphoto.ui.components.SectionHeader
import com.tvphoto.ui.components.StatusMessage
import com.tvphoto.ui.components.formatMonthShort
import com.tvphoto.ui.components.localizedError

/**
 * The timeline: one row per year, each row running through that year's months.
 *
 * The year is a row label rather than a screen of its own, because a year-per-card
 * list spends a whole row on four digits and leaves the width empty. Months with no
 * photos are dropped when the rows are built (see `timelineYears`), so every card
 * leads somewhere.
 *
 * The two axes fall out of the nesting: left/right is the row's own LazyRow, up/down
 * walks the LazyColumn of years.
 */
@Composable
fun TimelineSection(viewModel: MainViewModel) {
    val state by viewModel.timeline.collectAsStateWithLifecycle()
    // Deliberately not collected as state: the map object is the same one for the life
    // of the view model, and each card reads its own entry out of it. Collecting it
    // here would recompose the whole section every time a single cover arrived — which
    // is exactly what made moving along a row feel heavy.
    val covers = viewModel.monthCovers
    val listState = rememberLazyListState()

    // The view model remembers one index per section, so the two axes are packed
    // into it as row * COLUMNS + column.
    val saved = remember { viewModel.savedIndex(PositionKeys.TIMELINE_YEARS) }
    val restoreRow = if (saved >= 0) saved / COLUMNS else -1
    val restoreColumn = if (saved >= 0) saved % COLUMNS else -1
    val years = state.valueOrNull.orEmpty()

    // Bring the remembered year on screen first; its own effect then handles the
    // column, once the row has actually been composed.
    LaunchedEffect(restoreRow, years.size) {
        if (restoreRow >= 0 && years.isNotEmpty()) {
            listState.scrollToItem(restoreRow.coerceAtMost(years.lastIndex))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 28.dp),
    ) {
        SectionHeader(
            title = stringResource(R.string.nav_timeline),
            caption = (state as? ContentState.Ready)?.let {
                stringResource(R.string.year_count, it.value.size)
            },
        )
        Spacer(Modifier.height(20.dp))

        when (val current = state) {
            ContentState.Loading -> StatusMessage(stringResource(R.string.loading))

            is ContentState.Failed -> StatusMessage(localizedError(current.message)) {
                RetryButton { viewModel.loadTimeline(force = true) }
            }

            is ContentState.Ready -> if (current.value.isEmpty()) {
                StatusMessage(stringResource(R.string.empty))
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = current.value,
                        key = { _, year -> year.id },
                    ) { rowIndex, year ->
                        YearRow(
                            year = year,
                            covers = covers,
                            restoreColumn = if (rowIndex == restoreRow) restoreColumn else -1,
                            onCoverVisible = viewModel::requestMonthCover,
                            onMonthClick = viewModel::openMonth,
                            onColumnFocused = { column ->
                                viewModel.saveIndex(
                                    PositionKeys.TIMELINE_YEARS,
                                    rowIndex * COLUMNS + column,
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** One year: its label, then the months of that year. */
@Composable
private fun YearRow(
    year: TimelineYear,
    covers: Map<String, String>,
    restoreColumn: Int,
    onCoverVisible: (TimelineMonth) -> Unit,
    onMonthClick: (TimelineMonth) -> Unit,
    onColumnFocused: (Int) -> Unit,
) {
    val rowState = rememberLazyListState()
    val restoreFocus = remember { FocusRequester() }

    // Restoring a column means scrolling it into view before asking for focus: the
    // row is a lazy list, and the remembered month may sit past the right edge.
    LaunchedEffect(restoreColumn, year.months.size) {
        if (restoreColumn >= 0 && year.months.isNotEmpty()) {
            rowState.scrollToItem(restoreColumn.coerceAtMost(year.months.lastIndex))
            runCatching { restoreFocus.requestFocus() }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = year.year.toString(),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .width(YEAR_LABEL_WIDTH)
                .padding(start = 4.dp),
        )

        LazyRow(
            state = rowState,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            // Breathing room for the focus scale, which the row would otherwise clip.
            contentPadding = PaddingValues(4.dp),
        ) {
            itemsIndexed(
                items = year.months,
                key = { _, month -> month.id },
            ) { column, month ->
                MonthCard(
                    month = month,
                    covers = covers,
                    onVisible = { onCoverVisible(month) },
                    onClick = { onMonthClick(month) },
                    modifier = Modifier
                        .then(
                            if (column == restoreColumn) {
                                Modifier.focusRequester(restoreFocus)
                            } else {
                                Modifier
                            },
                        )
                        .onFocusChanged { if (it.isFocused) onColumnFocused(column) },
                )
            }
        }
    }
}

/** One month: its number above, that month's first photo below. */
@Composable
private fun MonthCard(
    month: TimelineMonth,
    covers: Map<String, String>,
    onVisible: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // This card's own cover, read through a derived state: a cover arriving for another
    // month cannot recompose this card, and one arriving for this card recomposes
    // nothing else. The map is the same object throughout — only one entry in it
    // changes — which is what keeps a row of cards smooth to move through.
    val cover by remember(month.id) { derivedStateOf { covers[month.id] } }

    // Lazy rows compose items as they scroll into view, so this doubles as the
    // visibility trigger that keeps cover fetching to what is on screen.
    LaunchedEffect(month.id) { onVisible() }

    FocusableSurface(onClick = onClick, modifier = modifier.width(CARD_WIDTH)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatMonthShort(month.year, month.month),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.photos_count, month.count),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                if (cover != null) {
                    AsyncImage(
                        model = cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** Months in a year, and therefore the width of the packed focus position. */
private const val COLUMNS = 12

private val CARD_WIDTH = 120.dp

private val YEAR_LABEL_WIDTH = 72.dp
