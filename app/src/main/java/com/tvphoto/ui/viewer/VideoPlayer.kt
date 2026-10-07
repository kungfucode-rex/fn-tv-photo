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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * Plays a NAS video or Live Photo clip.
 *
 * The stream endpoint is authenticated by the `AccessToken` header alone, so the
 * token is injected through a custom HTTP data source rather than a signed URL.
 * Range requests are supported server side, which is what makes seeking work.
 */
@Composable
fun VideoPlayer(
    url: String,
    token: String?,
    playWhenReady: Boolean,
    modifier: Modifier = Modifier,
    onFinished: () -> Unit = {},
) {
    val context = LocalContext.current

    val player = remember(url, token) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
            .apply {
                if (!token.isNullOrBlank()) {
                    setDefaultRequestProperties(mapOf("AccessToken" to token))
                }
            }

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
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
