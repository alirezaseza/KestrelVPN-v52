package com.kestrel.vpn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * "Liquid Glass" surface (2026): translucent tinted body, soft top specular sheen,
 * bright light-catching rim (strong top-left, faint bottom-left, warm bottom-right) and a deep soft shadow.
 * No backdrop blur is available in plain Compose, so the vivid background orbs provide the depth.
 */
/** Provided by [AuroraBackground]; glass surfaces blur whatever it paints behind them. */
val LocalHazeState = androidx.compose.runtime.staticCompositionLocalOf<HazeState?> { null }

/** True when the user (or a low-RAM device) asked for no blur and no ambient animation. */
val LocalReduceEffects = androidx.compose.runtime.staticCompositionLocalOf { false }

fun Modifier.liquidGlass(
    shape: Shape,
    tint: Color = Color.White,
    strength: Float = 1f,
    elevation: Boolean = true
): Modifier = composed {
    val haze = LocalHazeState.current
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    // On light backgrounds glass reads as frosted white; on dark ones as a faint tinted lens.
    val bodyTint = if (light) Color.White else tint
    val a1 = (if (light) 0.62f else 0.17f) * strength
    val a2 = (if (light) 0.28f else 0.05f) * strength
    val body = Brush.linearGradient(
        colors = listOf(bodyTint.copy(alpha = a1.coerceAtMost(0.9f)), bodyTint.copy(alpha = a2.coerceAtMost(0.9f))),
        start = Offset(0f, 0f),
        end = Offset(600f, 900f)
    )
    val rim = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = if (light) 0.95f else 0.70f),
            Color.White.copy(alpha = if (light) 0.35f else 0.10f),
            (if (light) Color(0xFF8A94B8) else Color.White).copy(alpha = if (light) 0.18f else 0.03f),
            Color.White.copy(alpha = if (light) 0.70f else 0.34f)
        ),
        start = Offset(0f, 0f),
        end = Offset(700f, 700f)
    )
    val shadowA = if (light) 0.14f else 0.35f
    this
        .then(
            if (elevation) Modifier.shadow(
                elevation = 22.dp,
                shape = shape,
                ambientColor = Color(0xFF1B2447).copy(alpha = shadowA),
                spotColor = Color(0xFF1B2447).copy(alpha = shadowA + 0.1f)
            ) else Modifier
        )
        .clip(shape)
        .then(
            if (haze != null) Modifier.hazeEffect(state = haze) {
                blurRadius = 26.dp
                noiseFactor = 0.05f
                tints = listOf(HazeTint(if (light) Color.White.copy(alpha = 0.18f) else tint.copy(alpha = 0.06f)))
            } else Modifier
        )
        .background(body)
        .drawBehind {
            drawRect(
                Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = (if (light) 0.35f else 0.16f) * strength), Color.Transparent),
                    endY = size.height * 0.45f
                )
            )
        }
        .border(1.dp, rim, shape)
}
