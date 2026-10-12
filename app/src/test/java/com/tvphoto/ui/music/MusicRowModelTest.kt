package com.tvphoto.ui.music

import com.tvphoto.data.music.MusicEntry
import com.tvphoto.data.music.MusicSource
import com.tvphoto.data.customMusicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The viewer's track list: the tracks in the order the library gives them, then the way in
 * to the upload page.
 *
 * The list stopped being grouped in 1.5.1, and that is the rule these pin: the list *is* the
 * playlist, the numbers on the upload page count the same single sequence, and a cursor has
 * exactly one non-track row to skip past at the bottom.
 */
class MusicRowModelTest {

    private val tracks = listOf(
        entry(customMusicId("/uploads/c.mp3"), "C", MusicSource.UPLOAD),
        entry("music/bundled.mp3", "B", MusicSource.BUILTIN),
        entry(customMusicId("/uploads/a.mp3"), "A", MusicSource.UPLOAD),
    )

    @Test
    fun `the list is the tracks in order, then the way in`() {
        val rows = musicRows(tracks)

        assertEquals(4, rows.size)
        assertEquals(listOf("C", "B", "A"), rows.filterIsInstance<MusicRow.Track>().map { it.entry.title })
        assertEquals(MusicRow.Manage, rows[3])
    }

    @Test
    fun `every track row and the way in can be landed on`() {
        val rows = musicRows(tracks)

        assertTrue(rows.all { it.selectable })
    }

    @Test
    fun `moving down walks the tracks and comes back round through the way in`() {
        val rows = musicRows(tracks)

        assertEquals(1, nextSelectableRow(rows, 0, +1))
        assertEquals(3, nextSelectableRow(rows, 2, +1))
        assertEquals(0, nextSelectableRow(rows, 3, +1))
        assertEquals(3, nextSelectableRow(rows, 0, -1))
    }

    @Test
    fun `the list opens on the first ticked track`() {
        val rows = musicRows(tracks)

        assertEquals(1, firstTickedRow(rows, setOf("music/bundled.mp3")))
        assertEquals(2, firstTickedRow(rows, setOf(customMusicId("/uploads/a.mp3"))))
    }

    @Test
    fun `nothing ticked opens on the first track`() {
        val rows = musicRows(tracks)

        assertEquals(0, firstTickedRow(rows, emptySet()))
    }

    @Test
    fun `an empty library still has somewhere to go`() {
        val rows = musicRows(emptyList())

        assertEquals(1, rows.size)
        assertEquals(0, nextSelectableRow(rows, 0, +1))
        assertEquals(0, firstTickedRow(rows, emptySet()))
    }

    @Test
    fun `an out-of-range cursor is pulled back onto the list`() {
        val rows = musicRows(tracks)

        assertEquals(0, nextSelectableRow(rows, 99, +1))
    }

    private fun entry(id: String, title: String, source: MusicSource) =
        MusicEntry(id = id, title = title, source = source)
}
