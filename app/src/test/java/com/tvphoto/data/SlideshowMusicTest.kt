package com.tvphoto.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The music setting is a selection *from* a fixed catalogue, and it is stored as one
 * asset path per line. These pin the two rules that makes true — playback order is the
 * catalogue's, and only tracks that still exist can be selected — plus that the files
 * the catalogue names are actually in the APK.
 */
class SlideshowMusicTest {

    private val first = SLIDESHOW_MUSIC.first().asset
    private val second = SLIDESHOW_MUSIC.last().asset

    @Test
    fun `every track names a distinct file that ships with the app`() {
        val assets = SLIDESHOW_MUSIC.map { it.asset }

        assertEquals(assets.size, assets.toSet().size)
        assertTrue(SLIDESHOW_MUSIC.all { it.title.isNotBlank() })
        assertTrue(assets.all { it.endsWith(".mp3") })

        // A path the package does not carry is silent music, and the player would only
        // report it at the moment a show started. Read from the module's own asset tree
        // rather than from a build output, so renaming a file without renaming it here
        // fails the build instead.
        assets.forEach { asset ->
            assertTrue(
                "asset missing from the APK: $asset",
                musicAssetsDirectory().resolve(asset).isFile,
            )
        }
    }

    @Test
    fun `the selection plays in catalogue order, not tick order`() {
        assertEquals(listOf(first, second), normalizeMusicSelection(listOf(second, first)))
    }

    @Test
    fun `tracks the catalogue does not know are dropped`() {
        assertEquals(
            listOf(first),
            normalizeMusicSelection(listOf("music/removed-in-a-later-version.mp3", first)),
        )
        assertEquals(emptyList<String>(), normalizeMusicSelection(listOf("", "not/a/track")))
    }

    @Test
    fun `ticking the same track twice does not play it twice`() {
        assertEquals(listOf(second), normalizeMusicSelection(listOf(second, second)))
    }

    @Test
    fun `a selection survives a round trip through storage`() {
        val selection = listOf(first, second)

        // Storage keeps the *normalised* form, so what is read back is what will play.
        assertEquals(selection, decodeMusicSelection(encodeMusicSelection(selection)))
        assertEquals(selection, decodeMusicSelection(encodeMusicSelection(selection.reversed())))
    }

    @Test
    fun `nothing stored means no music rather than a failure`() {
        assertEquals(emptyList<String>(), decodeMusicSelection(null))
        assertEquals(emptyList<String>(), decodeMusicSelection(""))
        assertEquals(emptyList<String>(), decodeMusicSelection("\n \n"))
        assertEquals(emptyList<String>(), decodeMusicSelection("music/gone.mp3"))
    }

    @Test
    fun `no audio file ships without an entry in the catalogue`() {
        // The other direction of the same rule, and the one that decides the package's
        // size: a file left behind after its entry was dropped is megabytes of every
        // download that nobody can ever select. The catalogue shrank from nine tracks to
        // four by deleting files, and this is what stops the next removal from being a
        // one-line edit that leaves the audio in the APK.
        val onDisk = musicAssetsDirectory().resolve("music").listFiles().orEmpty()
            .filter { it.isFile }
            .map { "music/${it.name}" }
            .toSet()

        assertEquals(
            "audio in assets/music that no catalogue entry names",
            emptySet<String>(),
            onDisk - SLIDESHOW_MUSIC.map { it.asset }.toSet(),
        )
    }

    /**
     * The directory the catalogue's paths are relative to.
     *
     * Gradle runs unit tests from the module directory and from the project root
     * depending on the task, so both are tried before giving up: a test that quietly
     * finds no directory would pass by accident, which is worse than failing.
     */
    private fun musicAssetsDirectory(): File {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("no assets directory found from ${File("").absolutePath}")
    }
}
