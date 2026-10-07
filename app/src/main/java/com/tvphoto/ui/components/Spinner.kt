package com.tvphoto.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * A rotating arc, drawn rather than pulled in from a widget library.
 *
 * The whole app is built on `androidx.tv.material3`, which does not ship a spinner;
 * taking one from `androidx.compose.material3` would mean a second theme in the tree for
 * a single shape, so the shape is drawn here instead.
 *
 * Used both for a whole screen that is waiting (start-up sign-in) and for the viewer,
 * which waits on a link rather than on a person.
 */
@Composable
fun Spinner(
    modifier: Modifier = Modifier,
    diameter: Dp = 44.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    val transition = rememberInfiniteTransition(label = "spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "spinner-angle",
    )

    Canvas(modifier = modifier.size(diameter)) {
        val stroke = 4.dp.toPx()
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = stroke, cap = StrokeCap.Round),
            size = Size(size.width - stroke, size.height - stroke),
            topLeft = Offset(stroke / 2f, stroke / 2f),
        )
    }
}
