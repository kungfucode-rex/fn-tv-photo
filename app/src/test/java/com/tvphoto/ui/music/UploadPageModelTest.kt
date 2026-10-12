package com.tvphoto.ui.music

import com.tvphoto.data.music.MusicEntry
import com.tvphoto.data.music.MusicSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The upload page's three modes, pinned away from the screen.
 *
 * What these protect is that OK never means two things at once: it ticks a track in the
 * normal mode, does the highlighted button while the two buttons are out, and moves the
 * track while the arrows are out. A list where a press could delete what the user meant to
 * tick is the failure this state machine exists to make impossible — so the transitions are
 * tested rather than watched.
 */
class UploadPageModelTest {

    private val mine = MusicEntry(id = "file:/uploads/a.mp3", title = "A", source = MusicSource.UPLOAD)
    private val bundled = MusicEntry(id = "music/b.mp3", title = "B", source = MusicSource.BUILTIN)

    @Test
    fun `rows are numbered across the whole list`() {
        val numbered = numberedTracks(listOf(mine, bundled))

        assertEquals(listOf(1, 2), numbered.map { it.number })
        assertEquals(listOf("A", "B"), numbered.map { it.entry.title })
    }

    @Test
    fun `a long press brings out both buttons with neither of them chosen`() {
        val mode = RowMode.Normal.afterLongPress(mine.id)

        // Nothing chosen, and that is the fix for a real report: the release that ends a long
        // press arrives after the buttons are on screen, so a pre-selected 排序 was run by
        // that very release — and the next repeat event was read as "move down".
        assertEquals(RowMode.Actions(mine.id, focus = null), mode)
        assertEquals(mode, mode.afterActionConfirm())
    }

    @Test
    fun `left and right choose a button and then move between the two`() {
        val shown = RowMode.Normal.afterLongPress(mine.id)

        // Either direction arms the button it points at, so one press is enough.
        assertEquals(ActionFocus.SORT, (shown.stepActionFocus(+1) as RowMode.Actions).focus)
        assertEquals(ActionFocus.DELETE, (shown.stepActionFocus(-1) as RowMode.Actions).focus)

        // And afterwards they wrap as a pair.
        assertEquals(
            ActionFocus.DELETE,
            (shown.stepActionFocus(+1).stepActionFocus(+1) as RowMode.Actions).focus,
        )
        assertEquals(
            ActionFocus.SORT,
            (shown.stepActionFocus(-1).stepActionFocus(-1) as RowMode.Actions).focus,
        )
    }

    @Test
    fun `OK on sort turns the row into the arrows, OK on delete leaves the mode`() {
        val actions = RowMode.Normal.afterLongPress(mine.id)

        assertEquals(
            RowMode.Reordering(mine.id),
            actions.stepActionFocus(+1).afterActionConfirm(),
        )
        assertEquals(RowMode.Normal, actions.stepActionFocus(-1).afterActionConfirm())
    }

    @Test
    fun `back leaves either mode, and leaving the arrows is the confirmation`() {
        assertEquals(RowMode.Normal, RowMode.Normal.afterLongPress(mine.id).afterBack())
        assertEquals(RowMode.Normal, RowMode.Reordering(mine.id).afterBack())
        assertEquals(RowMode.Normal, RowMode.Normal.afterBack())
    }

    @Test
    fun `up and down only mean move while the arrows are out`() {
        assertNull(RowMode.Normal.reorderDelta(keyUp = true))
        assertNull(RowMode.Normal.afterLongPress(mine.id).reorderDelta(keyUp = false))
        assertEquals(-1, RowMode.Reordering(mine.id).reorderDelta(keyUp = true))
        assertEquals(1, RowMode.Reordering(mine.id).reorderDelta(keyUp = false))
    }

    @Test
    fun `stepping focus outside the actions changes nothing`() {
        assertEquals(RowMode.Normal, RowMode.Normal.stepActionFocus(+1))
        assertEquals(RowMode.Normal, RowMode.Normal.afterActionConfirm())
    }
}
