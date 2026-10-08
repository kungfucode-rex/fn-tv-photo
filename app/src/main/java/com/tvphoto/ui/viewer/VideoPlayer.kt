package com.tvphoto.ui.viewer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays a NAS video or Live Photo clip.
 *
 * Everything about the connection belongs to [streams]: the `AccessToken` header, the
 * 访问码 cookie, the pinned certificate and the timeouts are the app's own client's, and
 * the player is handed it rather than building a connection of its own. See
 * `OkHttpDataSource` for what the opposite — a bare media3 connection — cost here, which
 * was every video on a NAS whose certificate is only pinned.
 *
 * Range requests are supported server side, which is what makes seeking work; the data
 * source asks for the byte range media3 wants and nothing more.
 */
@Composable
fun VideoPlayer(
    url: String,
    streams: DataSource.Factory,
    playWhenReady: Boolean,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current

    val player = remember(url, streams) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(streams))
            .build()
            .apply {
                setMediaItem(MediaItem.fromUri(url))
                repeatMode = Player.REPEAT_MODE_OFF
                prepare()
            }
    }

    LaunchedEffect(player, playWhenReady) {
        player.playWhenReady = playWhenReady
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onFinished()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = false
                setKeepContentOnPlayerReset(true)
            }
        },
    )
}
