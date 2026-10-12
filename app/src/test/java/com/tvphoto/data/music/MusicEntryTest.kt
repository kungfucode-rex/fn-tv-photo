package com.tvphoto.data.music

import com.tvphoto.data.customMusicId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The library's own list logic: what a row is called, what order the rows come in, and what
 * a selection has to be pruned against when a file disappears.
 *
 * These are the decisions the screens do not make themselves — the upload page and the
 * viewer's track list both read the same list in the same order, and both read the rank of
 * a row from it rather than deciding one of their own.
 */
class MusicEntryTest {

    @Test
    fun `a title falls back to the file's own name`() {
        assertEquals("月亮照山川", musicTitleFromFileName("月亮照山川.mp3"))
        assertEquals("Easy Breeze", musicTitleFromFileName("Easy Breeze.flac"))
        assertEquals("no extension", musicTitleFromFileName("no extension"))
        assertEquals("song", musicTitleFromFileName("song.MP3"))
    }

    @Test
    fun `a name with nothing before the dot is left alone rather than blanked`() {
        assertEquals(".mp3", musicTitleFromFileName(".mp3"))
    }

    @Test
    fun `the library's order is by title, case-insensitively, and stable between ties`() {
        val sorted = sortedByTitle(
            listOf(
                entry("file:/b.mp3", "banana"),
                entry("file:/A.mp3", "Apple"),
                entry("file:/c.mp3", "cherry"),
            ),
        )

        assertEquals(listOf("Apple", "banana", "cherry"), sorted.map { it.title })
    }

    @Test
    fun `two files of the same name keep one order`() {
        val sorted = sortedByTitle(
            listOf(
                entry("file:/vol2/song.mp3", "song"),
                entry("file:/vol1/song.mp3", "song"),
            ),
        )

        assertEquals(listOf("file:/vol1/song.mp3", "file:/vol2/song.mp3"), sorted.map { it.id })
    }

    @Test
    fun `only the uploaded tracks are the ones a selection has to be pruned against`() {
        val state = MusicLibraryState(
            entries = listOf(
                entry(customMusicId("/uploads/a.mp3"), "a", source = MusicSource.UPLOAD),
                entry("music/bundled.mp3", "bundled", source = MusicSource.BUILTIN),
            ),
        )

        // The bundled track is deliberately absent: it cannot go missing, so a stored
        // selection naming it is always valid and never needs pruning.
        assertEquals(listOf(customMusicId("/uploads/a.mp3")), state.availableUploadIds)
    }

    // -------------------------------------------------------- the user's own order

    @Test
    fun `the stored order wins, and tracks it does not name come after it by title`() {
        val a = entry(customMusicId("/usb/a.mp3"), "A")
        val b = entry(customMusicId("/usb/b.mp3"), "B")
        val c = entry(customMusicId("/usb/c.mp3"), "C")
        val dropped = entry(customMusicId("/usb/gone.mp3"), "Z")

        val ordered = orderedByStoredOrder(
            entries = listOf(a, b, c),
            // The stored order may name a file that is gone, and may not know about one that
            // is there — an upload from an older build, or a file copied in with a cable.
            order = listOf(customMusicId("/usb/gone.mp3"), c.id, a.id),
        )

        assertEquals(listOf("C", "A", "B"), ordered.map { it.title })
        assertEquals(0, ordered.count { it.title == dropped.title })
    }

    @Test
    fun `an arrangement moves one place at a time and stops at both ends`() {
        val order = listOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), movedWithinOrder(order, "a", +1))
        assertEquals(listOf("a", "c", "b"), movedWithinOrder(order, "c", -1))
        // Clamped, not wrapped: the top of a list is where "move up" has to stop being able
        // to do anything, or a held key would send the track to the bottom.
        assertEquals(order, movedWithinOrder(order, "a", -1))
        assertEquals(order, movedWithinOrder(order, "c", +1))
    }

    @Test
    fun `moving a track the order has not named yet appends it and then moves it`() {
        // The library always hands this an order that names everything it can see, so this
        // is the belt-and-braces path: an unknown id is placed at the end first, and one
        // step up from there is the second-to-last place, not the top.
        assertEquals(listOf("a", "new", "b"), movedWithinOrder(listOf("a", "b"), "new", -1))
        assertEquals(listOf("a", "b", "new"), movedWithinOrder(listOf("a", "b"), "new", +1))
    }

    private fun entry(
        id: String,
        title: String,
        source: MusicSource = MusicSource.UPLOAD,
    ) = MusicEntry(id = id, title = title, source = source)
}
