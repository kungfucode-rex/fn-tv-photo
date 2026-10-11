package com.tvphoto.data.review

import com.tvphoto.domain.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local half of a photo's identity.
 *
 * `image_id` is the server's and cannot be computed here, so what is checked is the two
 * things the album contributes: a name that does not change when the library is
 * reorganised, and the sha256 of bytes that were already on disk.
 */
class PhotoIdentityTest {

    private fun item(id: Long) = MediaItem(
        id = id,
        name = "IMG_$id.jpg",
        isVideo = false,
        isLivePhoto = false,
        thumbnailUrl = "https://nas/m/$id.jpg",
        fullUrl = "https://nas/o/$id.jpg",
        videoUrl = null,
        takenAt = "2026:10:09 12:00:00",
        width = 4032,
        height = 3024,
        fileSize = 4_000_000,
    )

    @Test
    fun `the local id is the source and the media id`() {
        assertEquals("fnos:1015", PhotoIdentity.localId("fnos", item(1015)))
    }

    /**
     * The trap this exists to avoid: the same photo reached through two different screens
     * has to produce one identity, or the TV uploads it twice and the server makes two
     * image ids for it.
     */
    @Test
    fun `the path is not part of the identity`() {
        val fromGrid = item(1015)
        val fromFolder = item(1015).copy(name = "holiday/IMG_1015.jpg")
        assertEquals(
            PhotoIdentity.localId("fnos", fromGrid),
            PhotoIdentity.localId("fnos", fromFolder),
        )
    }

    @Test
    fun `the source is part of the identity`() {
        assertTrue(PhotoIdentity.localId("fnos", item(1)) != PhotoIdentity.localId("smb", item(1)))
    }

    @Test
    fun `sha256 matches the known digest of an empty input`() {
        // The published digest of zero bytes: this is what proves the helper is sha256 and
        // not a hash-shaped thing that happens to be 64 characters long.
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            PhotoIdentity.sha256(ByteArray(0)),
        )
    }

    @Test
    fun `sha256 is lowercase hex of the right length`() {
        val digest = PhotoIdentity.sha256("图评".toByteArray())
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in "0123456789abcdef" })
    }

    @Test
    fun `the sample size never upscales`() {
        // Already inside the target: read every pixel.
        assertEquals(1, PhotoIdentity.sampleSize(320, PhotoIdentity.CANONICAL_WIDTH))
        assertEquals(1, PhotoIdentity.sampleSize(240, PhotoIdentity.CANONICAL_WIDTH))
    }

    @Test
    fun `the sample size lands at or just above the target`() {
        // 1280 -> /2 = 640 >= 320 -> /4 = 320 >= 320 -> /8 = 160 < 320, so 4.
        assertEquals(4, PhotoIdentity.sampleSize(1280, PhotoIdentity.CANONICAL_WIDTH))
        // 4032 -> 2016 -> 1008 -> 504 -> 252 < 320, so 8.
        assertEquals(8, PhotoIdentity.sampleSize(4032, PhotoIdentity.CANONICAL_WIDTH))
        // 640 -> /2 = 320, which is exactly the target, so the decoder is told to halve it
        // and the final scale to 320 is a no-op rather than an upscale.
        assertEquals(2, PhotoIdentity.sampleSize(640, PhotoIdentity.CANONICAL_WIDTH))
    }

    @Test
    fun `a zero or negative target does not divide by zero`() {
        assertEquals(1, PhotoIdentity.sampleSize(4032, 0))
        assertEquals(1, PhotoIdentity.sampleSize(4032, -5))
    }
}
