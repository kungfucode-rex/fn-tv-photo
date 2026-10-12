package com.tvphoto.ui.music

import com.tvphoto.data.music.MusicEntry
import com.tvphoto.data.music.MusicSource

/**
 * A track with the number the upload page prints beside it.
 *
 * The number is the row's place in the *whole* list — the user's own music first, then the
 * bundled four — because that list is also the playlist's order. A number that restarted at
 * the 内置 heading would say "1" twice for two different playback positions.
 */
data class NumberedTrack(val number: Int, val entry: MusicEntry)

/** The page's rows in order, numbered from 1. */
fun numberedTracks(entries: List<MusicEntry>): List<NumberedTrack> =
    entries.mapIndexed { index, entry -> NumberedTrack(index + 1, entry) }

/**
 * Which per-row controls are showing, and where the user is inside them.
 *
 * A screen-local state machine, kept out of the composable so its rules can be pinned by a
 * test: the three modes are what the remote's keys mean, and the failure they exist to
 * prevent is a list where OK sometimes ticks a song and sometimes deletes it.
 */
sealed interface RowMode {

    /** Nothing but ticking: OK selects or unselects the row under the cursor. */
    data object Normal : RowMode

    /**
     * The two floating buttons are out. Left/right move between them, OK does the one that
     * is highlighted, Back puts them away.
     *
     * [focus] is **null while nothing is chosen**, and that is how a long press leaves them:
     * the buttons appear, and no part of that same press — its auto-repeats, or the release
     * that ends it — can act on either of them. It is not cosmetic. A long press is one press
     * among many: on a real remote the release that ends it arrives *after* the buttons are on
     * screen, so with a button pre-selected that release ran 排序, the row became the arrows,
     * and the next repeat event was read as "move down" — a long press that reordered the
     * list on its own.
     */
    data class Actions(val id: String, val focus: ActionFocus? = null) : RowMode

    /** The arrows are out and up/down move the track. Back confirms, which is to say exits. */
    data class Reordering(val id: String) : RowMode
}

/** Which of the two floating buttons is highlighted. */
enum class ActionFocus { SORT, DELETE }

/** A long press on row [id] offers its two actions, with neither of them chosen yet. */
fun RowMode.afterLongPress(id: String): RowMode = RowMode.Actions(id, focus = null)

/**
 * Left/right inside the actions.
 *
 * From nothing chosen, either direction picks the button it points at (right → 排序,
 * left → 删除), so one press arms one; after that they move between the two, wrapping as a
 * pair of buttons should.
 */
fun RowMode.stepActionFocus(delta: Int): RowMode = when (this) {
    is RowMode.Actions -> {
        if (delta == 0) {
            this
        } else {
            copy(
                focus = when (focus) {
                    null -> if (delta > 0) ActionFocus.SORT else ActionFocus.DELETE
                    ActionFocus.SORT -> ActionFocus.DELETE
                    ActionFocus.DELETE -> ActionFocus.SORT
                },
            )
        }
    }

    else -> this
}

/**
 * OK inside the actions.
 *
 * With nothing chosen this does nothing at all, which is the point of a long press leaving
 * [RowMode.Actions.focus] null. Otherwise 排序 turns the same row into the reordering mode,
 * and 删除 is answered by the caller (it has to touch storage), which also leaves the row in
 * [RowMode.Normal] — a row that has just been deleted must not keep two buttons over it.
 */
fun RowMode.afterActionConfirm(): RowMode = when (this) {
    is RowMode.Actions -> when (focus) {
        null -> this
        ActionFocus.SORT -> RowMode.Reordering(id)
        ActionFocus.DELETE -> RowMode.Normal
    }

    else -> this
}

/**
 * Back. Both modes end here, and for [RowMode.Reordering] that *is* the confirmation: every
 * up/down press has already moved the track and stored the order, so there is nothing left
 * to commit — only a mode to leave.
 */
fun RowMode.afterBack(): RowMode = RowMode.Normal

/**
 * How much up/down should move the row, or null when they mean "scroll the list".
 *
 * The one place the two meanings of up/down are told apart, so the key handler does not
 * have to know which mode is on.
 */
fun RowMode.reorderDelta(keyUp: Boolean): Int? = when (this) {
    is RowMode.Reordering -> if (keyUp) -1 else 1
    else -> null
}
