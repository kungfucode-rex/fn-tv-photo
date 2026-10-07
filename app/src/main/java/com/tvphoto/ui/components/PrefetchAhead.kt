package com.tvphoto.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps a cache window ahead of wherever the user has moved to.
 *
 * Four details matter:
 *
 *  - the window is anchored at [anchor] — the photo being looked at — so paging
 *    forward is what gets accelerated, rather than the top of a list the user has
 *    already scrolled past;
 *  - it only re-anchors once the anchor *leaves* the window. Restarting on every
 *    focus change would cancel downloads already in flight, so scrolling would
 *    starve the cache instead of filling it;
 *  - while [hold] says the screen is waiting for a picture of its own, the window
 *    stands down entirely — those downloads leave the same client and the same wire
 *    as the one the user is looking at, and warming a photo nobody has opened yet
 *    must never be what makes the visible one late. The held window restarts from
 *    the anchor when it is let go, so nothing is lost but the time it was held;
 *  - the anchor is handed over **as state** and read inside this composable's own
 *    coroutine. Taking it as a plain `Int` made every D-pad move recompose the screen
 *    that owns it — grid, item lambdas and all — for a value only this window cares
 *    about, which is a large part of why moving through a grid felt heavy.
 *
 * [hold] is a lambda rather than a `Boolean` for the same reason as the anchor: it is
 * read inside the coroutine, so the screen that owns it does not recompose every time
 * the photo it is waiting for arrives.
 *
 * The prefetch runs in the composition's scope, so leaving the screen cancels it.
 */
@Composable
fun PrefetchAhead(
    itemCount: Int,
    anchor: State<Int>,
    window: Int = DEFAULT_WINDOW,
    hold: () -> Boolean = { false },
    prefetch: suspend (fromIndex: Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentPrefetch = rememberUpdatedState(prefetch)
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(itemCount) {
        if (itemCount <= 0) return@LaunchedEffect
        var windowStart = NOT_STARTED

        snapshotFlow { anchor.value to hold() }
            .distinctUntilChanged()
            .collect { (requested, held) ->
                if (held) {
                    job?.cancel()
                    job = null
                    return@collect
                }

                val start = requested.coerceIn(0, itemCount - 1)
                // A window that was stood down starts again from the anchor, even when
                // the anchor has not moved out of the window it was stopped on.
                val restarted = job == null
                val stillInsideWindow = !restarted && windowStart != NOT_STARTED &&
                    start >= windowStart && start < windowStart + window
                if (stillInsideWindow) return@collect

                windowStart = start
                job?.cancel()
                job = scope.launch { currentPrefetch.value(start) }
            }
    }
}

/** How many images to keep warm ahead of the current position. */
const val DEFAULT_WINDOW = 50

private const val NOT_STARTED = Int.MIN_VALUE
