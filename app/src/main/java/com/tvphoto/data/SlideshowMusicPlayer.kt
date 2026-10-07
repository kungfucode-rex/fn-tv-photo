package com.tvphoto.data

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * The slideshow's background music.
 *
 * One player for the process, built the first time music is asked for and then reused:
 * a new `ExoPlayer` per slideshow would rebuild the audio stack — and re-request audio
 * focus — every time somebody started a show.
 *
 * The tracks loop as a playlist ([Player.REPEAT_MODE_ALL]) in the order they are given,
 * which is the catalogue order the setting stores. They are read straight out of the
 * APK's assets, so playback starts with the show rather than after a download.
 *
 * Every method must be called from the main thread: `ExoPlayer` is built against the
 * calling thread's `Looper`, and the composer effects that drive this are the main one.
 *
 * Audio focus is handled by the player, so a television that starts speaking — or a
 * soundbar that takes over — pauses the music instead of playing both at once.
 */
class SlideshowMusicPlayer(private val context: Context) {

    private var player: ExoPlayer? = null

    /**
     * The playlist the player currently holds.
     *
     * Kept so that asking for what is already loaded is not a restart: the setting is
     * re-evaluated on every relevant recomposition, and re-preparing the same list each
     * time would drop the tune back to its first bar.
     */
    private var loaded: List<String> = emptyList()

    /** Starts — or resumes — [assets], looping in the order given. */
    fun play(assets: List<String>) {
        if (assets.isEmpty()) {
            stop()
            return
        }
        val exo = player ?: newPlayer().also { player = it }
        if (assets != loaded) {
            exo.setMediaItems(assets.map { MediaItem.fromUri("asset:///$it") })
            exo.prepare()
            loaded = assets
        }
        exo.play()
    }

    /**
     * Holds the music where it is.
     *
     * Used while the show itself is held — the settings row is open, or a video is on
     * screen bringing its own sound. Unlike [stop] this keeps the position, so closing
     * the row picks the tune up where it left off instead of starting it over.
     */
    fun pause() {
        player?.pause()
    }

    /** The show is over: playback ends and a later show starts the list from the top. */
    fun stop() {
        val exo = player ?: return
        exo.stop()
        exo.clearMediaItems()
        loaded = emptyList()
    }

    private fun newPlayer(): ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true,
        )
        repeatMode = Player.REPEAT_MODE_ALL
    }
}
