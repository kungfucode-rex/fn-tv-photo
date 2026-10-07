package com.tvphoto.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * The focusable building block for the whole UI.
 *
 * `Modifier.clickable` already makes a node focusable and maps D-pad centre and
 * Enter onto `onClick`, so this only adds the visual response to focus. The node
 * is scaled rather than merely outlined because on a TV, viewed from across a
 * room, size reads far better than a thin border.
 */
@Composable
fun FocusableSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(14.dp),
    focusedScale: Float = 1.05f,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    focusedContainerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    // Animated rather than snapped: on a slow TV the jump from one size to another in
    // a single frame reads as the UI skipping, and a short tween also covers the frame
    // the item spends being laid out for the first time. The value is read inside
    // `graphicsLayer`, so the animation moves a draw-time scale and does not recompose
    // or re-lay-out the card - or the row of cards around it - on every frame.
    val scale = animateFloatAsState(
        targetValue = if (focused) focusedScale else 1f,
        animationSpec = tween(FOCUS_SCALE_MILLIS, easing = FastOutSlowInEasing),
        label = "focus-scale",
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(if (focused) focusedContainerColor else containerColor)
            .border(
                width = if (focused) 3.dp else 1.dp,
                color = if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.border,
                shape = shape,
            )
            .clickable(onClick = onClick),
        content = content,
    )
}

/** How long a card takes to grow into focus. Short enough to still feel immediate. */
private const val FOCUS_SCALE_MILLIS = 140

/** Centred status text used for loading, empty and error states. */
@Composable
fun StatusMessage(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

/** Retry affordance paired with [StatusMessage] on failure. */
@Composable
fun RetryButton(modifier: Modifier = Modifier, onClick: () -> Unit) {    FocusableSurface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        modifier = modifier,
    ) {
        Text(
            text = androidx.compose.ui.res.stringResource(com.tvphoto.R.string.retry),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

/** Small pill used for counts, media kinds and playback hints. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xCC000000))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
        )
    }
}

/**
 * Prefixes a protocol-level detail with a localised heading.
 *
 * The data layer reports failures in technical, locale-neutral terms so it does not
 * have to carry a `Context`; this is where they become user-facing.
 */
@Composable
fun localizedError(detail: String): String =
    stringResource(com.tvphoto.R.string.error_prefix) + " · " + detail

/** Title plus optional trailing caption, used at the top of each content pane. */@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    androidx.compose.foundation.layout.Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (!caption.isNullOrBlank()) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}
