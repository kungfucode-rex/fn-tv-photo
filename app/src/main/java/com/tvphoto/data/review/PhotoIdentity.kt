package com.tvphoto.data.review

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.tvphoto.domain.MediaItem
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * What the album side knows about a photo before the server has said anything.
 *
 * The identity that matters — `image_id` — is the server's, and it is a ULID that
 * *cannot* be computed here. This object only produces the two things the server needs
 * to make that decision, and the two are not equivalent
 * (docs/tuping-design.md §3.1):
 *
 *  - [sha256Original] is exact and is only computed when the original file is already
 *    on disk. Deciding to fetch a multi-megabyte file just to hash it would trade a
 *    real cost for a hypothetical match, so it is never fetched for this.
 *  - [canonicalBytes] is the 320px, aspect-preserving, EXIF-free JPEG the server
 *    fingerprints. It is a *guess* at what the picture looks like, which is exactly
 *    what a perception hash is for.
 */
object PhotoIdentity {

    /**
     * The album's own stable name for a photo.
     *
     * `source + media id` rather than the file path: a path changes when the album is
     * reorganised, and re-registering a re-organised library would be a second identity
     * for every photo in it. The media id is what the NAS itself uses and does not
     * change.
     */
    fun localId(source: String, item: MediaItem): String =
        "${source.lowercase(Locale.US)}:${item.id}"

    /** The `source` field a registration carries, from the URL the item came from. */
    fun sourceFor(item: MediaItem): String = when {
        item.isVideo -> "fnos-video"
        else -> "fnos"
    }

    /**
     * Re-encodes [jpeg] as the canonical thumbnail: at most [CANONICAL_WIDTH] wide,
     * aspect ratio untouched, no EXIF.
     *
     * The aspect ratio is the part that matters. Cropping to a fixed frame would move
     * this photo 54 bits away from itself — as far as a different photo — and the two
     * would never share a thread (docs/tuping-design.md §15.1).
     *
     * Returns null when the bytes are not an image this device can decode, which is a
     * normal answer for a format Android does not carry and means "skip this one".
     */
    fun canonicalBytes(jpeg: ByteArray, width: Int = CANONICAL_WIDTH): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, width)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null

        val scaled = if (decoded.width > width) {
            val height = (decoded.height.toLong() * width / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, width, height, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } else {
            decoded
        }

        return try {
            val out = ByteArrayOutputStream(scaled.byteCount.coerceAtMost(1 shl 20))
            // Quality 80 is the design's choice: it is what the TV would have uploaded
            // anyway, and the server re-encodes regardless, so this only has to be good
            // enough for a perception hash and clear enough for a phone to look at.
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            out.toByteArray()
        } finally {
            scaled.recycle()
        }
    }

    /**
     * The power-of-two sample size that lands [sourceWidth] at or just above [target].
     *
     * A power of two because that is what the decoder can do without filtering, and
     * "at or just above" so the final scale is a downscale rather than an upscale.
     */
    fun sampleSize(sourceWidth: Int, target: Int): Int {
        if (target <= 0) return 1
        var sample = 1
        while (sourceWidth / (sample * 2) >= target) sample *= 2
        return sample
    }

    /**
     * sha256 of an original file's bytes, lowercase hex.
     *
     * Only ever called with bytes that are already on disk — the slideshow fetched the
     * original, or the user pressed the 原图 button. Nothing anywhere fetches a file in
     * order to hash it (docs/tuping-design.md §3.5).
     */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /** The canonical width the server expects. */
    const val CANONICAL_WIDTH = 320

    private const val HEX = "0123456789abcdef"
}
