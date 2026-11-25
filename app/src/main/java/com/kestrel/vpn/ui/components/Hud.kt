package com.kestrel.vpn.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val HudMono: FontFamily = FontFamily.Default

/**
 * Continuously accumulating angle (degrees) whose speed eases to [degPerSec]. Changing speed never makes the
 * angle jump, so spinners can speed up / slow down smoothly. Read the returned state only inside draw blocks.
 */
@Composable
fun rememberSpinState(degPerSec: Float): State<Float> {
    val speed = animateFloatAsState(degPerSec, tween(900, easing = FastOutSlowInEasing), label = "spin_speed")
    val angle = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                    angle.floatValue = (angle.floatValue + speed.value * dt) % 360f
                }
                last = now
            }
        }
    }
    return angle
}

/** Liquid ring: glowing progress arc whose length follows live throughput; a comet while busy. States crossfade, nothing snaps. */
@Composable
fun TelemetryRing(level: Float, accent: Color, busy: Boolean, modifier: Modifier = Modifier) {
    val lvl by animateFloatAsState(level.coerceIn(0f, 1f), tween(900, easing = FastOutSlowInEasing), label = "ring_level")
    val busyF by animateFloatAsState(if (busy) 1f else 0f, tween(700, easing = FastOutSlowInEasing), label = "ring_busy")
    val spin = rememberSpinState(165f)
    Canvas(modifier = modifier) {
        val w = 10.dp.toPx()
        val inset = w / 2f + 2.dp.toPx()
        val sz = Size(size.width - inset * 2, size.height - inset * 2)
        val tl = Offset(inset, inset)
        // glass track
        drawArc(Color.White.copy(alpha = 0.09f), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(Color.White.copy(alpha = 0.18f), 0f, 360f, false, tl, sz, style = Stroke(1.dp.toPx()))
        // throughput arc (fades out while busy)
        val levelAlpha = 1f - busyF
        val sweep = 360f * lvl
        if (sweep > 0.5f && levelAlpha > 0.01f) {
            drawArc(accent.copy(alpha = 0.22f * levelAlpha), -90f, sweep, false, tl, sz, style = Stroke(w * 2.4f, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(accent.copy(alpha = 0.4f * levelAlpha), accent.copy(alpha = levelAlpha), Color.White.copy(alpha = levelAlpha))),
                -90f, sweep, false, tl, sz, style = Stroke(w * 0.8f, cap = StrokeCap.Round)
            )
        }
        // comet (fades in while busy): gradient rotates together with the arc so brightness stays constant
        if (busyF > 0.01f) {
            rotate(spin.value - 90f, pivot = center) {
                drawArc(accent.copy(alpha = 0.22f * busyF), 0f, 110f, false, tl, sz, style = Stroke(w * 2.4f, cap = StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(
                        0f to accent.copy(alpha = 0.10f * busyF),
                        0.28f to accent.copy(alpha = busyF),
                        0.31f to Color.White.copy(alpha = busyF),
                        center = center
                    ),
                    0f, 110f, false, tl, sz, style = Stroke(w * 0.8f, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/** Liquid-glass tile with a small caption. */
@Composable
fun HudCard(
    label: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .liquidGlass(RoundedCornerShape(28.dp), tint = accent, strength = 0.9f)
            .padding(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.3.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
        )
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
fun HudValue(text: String, modifier: Modifier = Modifier, size: Int = 22) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = size.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.6).sp,
        maxLines = 1,
        color = MaterialTheme.colorScheme.onSurface
    )
}
