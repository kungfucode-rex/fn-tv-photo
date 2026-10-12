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

    private val bundled = SLIDESHOW_MUSIC.first().asset

    // Two ids that are not in the APK, for the tests that need something to order around
    // the bundled track. The catalogue itself is a single track since 1.5.2.
    private val one = customMusicId("/usb/one.mp3")
    private val two = customMusicId("/usb/two.mp3")

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
    fun `the bundled track plays after the user's own music, whatever the tick order was`() {
        assertEquals(listOf(one, two, bundled), normalizeMusicSelection(listOf(bundled, two, one), listOf(one, two)))
        assertEquals(listOf(one, two, bundled), normalizeMusicSelection(listOf(one, bundled, two), listOf(one, two)))
    }

    @Test
    fun `tracks the catalogue does not know are dropped`() {
        assertEquals(
            listOf(bundled),
            normalizeMusicSelection(listOf("music/removed-in-a-later-version.mp3", bundled)),
        )
        assertEquals(emptyList<String>(), normalizeMusicSelection(listOf("", "not/a/track")))
    }

    @Test
    fun `ticking the same track twice does not play it twice`() {
        assertEquals(listOf(bundled), normalizeMusicSelection(listOf(bundled, bundled)))
        assertEquals(listOf(one), normalizeMusicSelection(listOf(one, one), listOf(one)))
    }

    @Test
    fun `a selection survives a round trip through storage`() {
        // Already in the normalised order — the user's own music, then the bundled track —
        // so this is the round trip itself being tested, not the reordering.
        val selection = listOf(one, two, bundled)

        assertEquals(selection, decodeMusicSelection(encodeMusicSelection(selection)))

        // Writing it twice changes nothing: this is the form the player reads back.
        val once = encodeMusicSelection(selection)
        assertEquals(once, encodeMusicSelection(decodeMusicSelection(once)))

        // The bundled track ends up last wherever it was ticked, while the order the user
        // arranged their own music in is the one that is kept.
        assertEquals(
            listOf(one, two, bundled),
            decodeMusicSelection(encodeMusicSelection(listOf(bundled, one, two))),
        )
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
        //
        // Music the user adds never lands here: uploads live in the app's own storage
        // directory and references point at files the user already had, so this test stays
        // about what the APK carries.
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

    // ------------------------------------------------------------ custom tracks

    @Test
    fun `uploaded and referenced tracks are stored beside the bundled ones`() {
        val upload = customMusicId("/storage/emulated/0/Android/data/app/files/music/song.mp3")
        val usb = customMusicId("/storage/1A2B-3C4D/Music/月亮.mp3")

        val stored = encodeMusicSelection(listOf(usb, bundled, upload))

        // The user's own music first, in the order it was given (which is the library's
        // arrangement), then the bundled track.
        assertEquals(listOf(usb, upload, bundled), decodeMusicSelection(stored))
        assertEquals(stored, encodeMusicSelection(decodeMusicSelection(stored)))
    }

    @Test
    fun `a custom track is dropped once its file is gone, and kept while it is there`() {
        val here = customMusicId("/usb/here.mp3")
        val gone = customMusicId("/usb/gone.mp3")
        val ticked = listOf(bundled, here, gone)

        assertEquals(
            listOf(here, bundled),
            normalizeMusicSelection(ticked, customIds = listOf(here)),
        )
    }

    @Test
    fun `the user's own order comes first and the bundled track comes last`() {
        assertEquals(
            listOf(two, one, bundled),
            normalizeMusicSelection(
                listOf(one, bundled, two),
                customIds = listOf(two, one),
            ),
        )
    }

    @Test
    fun `storage keeps a custom track even before anything has looked at the disk`() {
        // Reading preferences must not depend on a mounted volume: what the store drops is
        // only what could never play at all, and whether a file is there is the library's
        // question to answer later.
        val path = customMusicId("/usb/not-mounted-right-now.mp3")

        assertEquals(listOf(path), decodeMusicSelection("$path\n"))
        assertEquals(emptyList<String>(), decodeMusicSelection("file:\nnot-a-scheme\n"))
    }

    @Test
    fun `a custom id round trips through the prefix helpers`() {
        val path = "/storage/1A2B/Music/Easy Breeze.mp3"

        assertEquals(path, customMusicPath(customMusicId(path)))
        assertTrue(isCustomMusic(customMusicId(path)))
        assertEquals(false, isCustomMusic(bundled))
        assertEquals(null, customMusicPath(bundled))
        assertEquals(null, customMusicPath("file:"))
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
