package com.tvphoto.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import com.tvphoto.data.ThemeMode

/**
 * The dark scheme: a TV photo browser is used in a dim room and every surface
 * should defer to the photograph on screen.
 */
private val TvPhotoDarkColors = darkColorScheme(
    primary = Color(0xFF35C4A0),
    onPrimary = Color(0xFF04231C),
    primaryContainer = Color(0xFF14453A),
    onPrimaryContainer = Color(0xFFD6FFF3),
    secondary = Color(0xFFFFC24B),
    onSecondary = Color(0xFF2A1C00),
    background = Color(0xFF0B0F17),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF141A24),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF1E2733),
    onSurfaceVariant = Color(0xFFAEBBCB),
    border = Color(0xFF2B3646),
    borderVariant = Color(0xFF232C39),
    error = Color(0xFFFFB4B4),
    onError = Color(0xFF3A0A0A),
    errorContainer = Color(0x33FF5252),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * The light scheme: the same app with the lights on.
 *
 * The teal is darkened rather than reused: the dark theme's `primary` is chosen to glow
 * against near-black, and as ink on a white card it fails contrast — which on a TV,
 * read from across a room, is the difference between a label and a smudge.
 *
 * The two schemes deliberately share every slot they set, so a surface cannot be
 * theme-aware in one mode and hard-coded in the other. Surfaces over a photograph — the
 * viewer, its focus pills, the badges on thumbnails — stay dark in both modes by design:
 * they sit on an image, not on the app's background.
 */
private val TvPhotoLightColors = lightColorScheme(
    primary = Color(0xFF0E7C64),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFB6EBDC),
    onPrimaryContainer = Color(0xFF00251C),
    secondary = Color(0xFF8A5A00),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F7FA),
    onBackground = Color(0xFF101720),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101720),
    surfaceVariant = Color(0xFFE4EAF2),
    onSurfaceVariant = Color(0xFF4B5766),
    border = Color(0xFFC7D1DD),
    borderVariant = Color(0xFFDAE1EA),
    error = Color(0xFF9C2B2B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0x1FFF5252),
    onErrorContainer = Color(0xFF3A0A0A),
)

@Composable
fun TvPhotoTheme(
    mode: ThemeMode = ThemeMode.DEFAULT,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (mode == ThemeMode.LIGHT) TvPhotoLightColors else TvPhotoDarkColors,
        content = content,
    )
}

/**
 * The colour the window is filled with before Compose has drawn anything.
 *
 * The activity's window background is set from this at start-up: the theme in
 * `themes.xml` is a single static colour, and a light-mode launch that flashed a black
 * window first would look like the app had crashed and restarted.
 */
fun windowBackgroundArgb(mode: ThemeMode): Int =
    (if (mode == ThemeMode.LIGHT) TvPhotoLightColors else TvPhotoDarkColors).background.toArgb()
