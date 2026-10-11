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
 * Five details matter:
 *
 *  - the window is anchored at [anchor] — the photo being looked at — so paging
 *    forward is what gets accelerated, rather than the top of a list the user has
 *    already scrolled past;
 *  - it only re-anchors once the anchor *leaves* the window. Restarting on every
 *    focus change would cancel downloads already in flight, so scrolling would
 *    starve the cache instead of filling it;
 *  - while [hold] says the screen is waiting for a picture of its own, the window
 *    stands down — see [narrowWhenHeld] for the one case where standing down is
 *    the wrong answer and the window is narrowed instead;
 *  - [restartKey] re-anchors the window from scratch when it changes, for the rare
 *    facts — which tier is being warmed, whether a show is running — that make the
 *    window's own contents wrong rather than merely stale;
 *  - the anchor is handed over **as state** and read inside this composable's own
 *    coroutine. Taking it as a plain `Int` made every D-pad move recompose the screen
 *    that owns it — grid, item lambdas and all — for a value only this window cares
 *    about, which is a large part of why moving through a grid felt heavy.
 *
 * [hold] is a lambda rather than a `Boolean` for the same reason as the anchor: it is
 * read inside the coroutine, so the screen that owns it does not recompose every time
 * the photo it is waiting for arrives. Under [narrowWhenHeld] it is handed on to
 * [prefetch] as well, which reads it between batches and never stops.
 *
 * The prefetch runs in the composition's scope, so leaving the screen cancels it.
 */
@Composable
fun PrefetchAhead(
    itemCount: Int,
    anchor: State<Int>,
    window: Int = DEFAULT_WINDOW,
    hold: () -> Boolean = { false },
    /**
     * Whether a held window is stopped outright or merely narrowed.
     *
     * Stopping is what a grid wants: the photo the user has just opened shares the wire
     * with the window, and nothing the window was warming is on screen anyway, so the
     * window can wait and start again from where it was let go.
     *
     * A running slideshow is the other case. There the window is the only thing that can
     * have the *next* original ready — the show draws its next photo from the whole list,
     * so nothing else predicts it — and a download that is stopped has to start again from
     * nothing, because only a complete body becomes a disk cache entry. Narrowed to one
     * download while a step is in flight, the window loses the wire to the visible photo
     * and still advances; stopped, it never gets anywhere, and the show runs on
     * thumbnails no matter how long it is left playing.
     */
    narrowWhenHeld: Boolean = false,
    /** Re-anchors the window from scratch when this changes. */
    restartKey: Any? = Unit,
    prefetch: suspend (fromIndex: Int, held: () -> Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val currentPrefetch = rememberUpdatedState(prefetch)
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(itemCount, restartKey) {
        if (itemCount <= 0) return@LaunchedEffect
        var windowStart = NOT_STARTED

        try {
            snapshotFlow { anchor.value to (if (narrowWhenHeld) false else hold()) }
                .distinctUntilChanged()
                .collect { (requested, stopped) ->
                    if (stopped) {
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
                    job = scope.launch { currentPrefetch.value(start, hold) }
                }
        } finally {
            // The window belongs to this key: a restart (a new tier, a show starting) or
            // leaving the screen ends the downloads it owns rather than leaving them to
            // run under a window that no longer exists.
            job?.cancel()
            job = null
        }
    }
}

/** How many images to keep warm ahead of the current position. */
const val DEFAULT_WINDOW = 50

private const val NOT_STARTED = Int.MIN_VALUE
