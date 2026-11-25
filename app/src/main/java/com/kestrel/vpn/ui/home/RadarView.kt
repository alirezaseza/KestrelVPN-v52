package com.kestrel.vpn.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.ui.components.rememberSpinState
import com.kestrel.vpn.ui.components.statusAccent
import com.kestrel.vpn.ui.theme.KestrelAmber
import com.kestrel.vpn.ui.theme.KestrelEmerald
import com.kestrel.vpn.ui.theme.KestrelRose
import kotlin.math.cos
import kotlin.math.sin

/**
 * Falcon radar: every blip is a REAL tested server. Distance from the centre = latency
 * (closer = faster), colour = quality tier. The sweep runs while idle/connecting; once
 * connected it turns into calm outgoing ripples around the shield.
 */
@Composable
fun RadarView(
    status: ConnectionStatus,
    servers: List<ServerWithResult>,
    activeKey: String?,
    bestKey: String?,
    modifier: Modifier = Modifier,
    onHubClick: (() -> Unit)? = null
) {
    val cs = MaterialTheme.colorScheme
    val accent by animateColorAsState(statusAccent(status), tween(900), label = "radar_accent")
    val connected = status == ConnectionStatus.CONNECTED
    val busy = status == ConnectionStatus.CONNECTING ||
        status == ConnectionStatus.FAILING_OVER ||
        status == ConnectionStatus.DISCONNECTING

    val infinite = rememberInfiniteTransition(label = "radar_fx")
    // One continuously accumulated sweep angle whose speed eases between idle and busy: no jump on state change.
    val sweepAngle = rememberSpinState(if (busy) 200f else 51f)
    val connectedF by animateFloatAsState(if (connected) 1f else 0f, tween(800, easing = FastOutSlowInEasing), label = "radar_connected")
    val ripple by infinite.animateFloat(
        0f, 1f, infiniteRepeatable(tween(3000, easing = LinearEasing)), label = "ripple"
    )
    val breathe by infinite.animateFloat(
        0.85f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Reverse), label = "breathe"
    )

    val good = cs.primary
    val grid = cs.onSurface

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f

            // soft core glow
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(accent.copy(alpha = 0.22f * breathe), Color.Transparent),
                    center = c,
                    radius = r
                ),
                radius = r,
                center = c
            )
            // range rings
            listOf(0.25f, 0.5f, 0.75f, 1f).forEachIndexed { i, f ->
                drawCircle(
                    color = grid.copy(alpha = if (i == 3) 0.22f else 0.09f),
                    radius = r * f * 0.98f,
                    center = c,
                    style = Stroke(1.dp.toPx())
                )
            }
            // crosshair
            drawLine(grid.copy(alpha = 0.07f), Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.dp.toPx())
            drawLine(grid.copy(alpha = 0.07f), Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.dp.toPx())
            // bezel ticks
            for (i in 0 until 72) {
                val a = Math.toRadians(i * 5.0)
                val outer = r * 0.98f
                val len = if (i % 6 == 0) 10.dp.toPx() else 5.dp.toPx()
                val inner = outer - len
                drawLine(
                    color = grid.copy(alpha = if (i % 6 == 0) 0.32f else 0.14f),
                    start = Offset(c.x + inner * cos(a).toFloat(), c.y + inner * sin(a).toFloat()),
                    end = Offset(c.x + outer * cos(a).toFloat(), c.y + outer * sin(a).toFloat()),
                    strokeWidth = 1.dp.toPx()
                )
            }

            if (connectedF > 0.01f) {
                for (k in 0..2) {
                    val p = (ripple + k / 3f) % 1f
                    drawCircle(
                        color = accent.copy(alpha = (1f - p) * 0.38f * connectedF),
                        radius = r * (0.2f + 0.78f * p),
                        center = c,
                        style = Stroke(2.dp.toPx())
                    )
                }
            }
            if (connectedF < 0.99f) {
                val sweepA = 1f - connectedF
                rotate(sweepAngle.value, pivot = c) {
                    drawCircle(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.80f to Color.Transparent,
                            1f to accent.copy(alpha = 0.5f * sweepA),
                            center = c
                        ),
                        radius = r * 0.98f,
                        center = c
                    )
                    drawLine(accent.copy(alpha = 0.85f * sweepA), c, Offset(c.x + r * 0.98f, c.y), 1.6.dp.toPx())
                }
            }

            // hub halo
            drawCircle(accent.copy(alpha = 0.18f * breathe), radius = 52.dp.toPx(), center = c)

            // real server blips
            servers.forEach { s ->
                val ping = s.result?.pingMs ?: return@forEach
                val key = s.config.canonicalKey
                val a = Math.toRadians(((key.hashCode() and 0x7fffffff) % 360).toDouble())
                val frac = 0.36f + 0.58f * ((ping.coerceIn(20, 400) - 20) / 380f)
                val pos = Offset(
                    c.x + (r * frac * cos(a)).toFloat(),
                    c.y + (r * frac * sin(a)).toFloat()
                )
                val col = when (s.tier) {
                    ServerTier.EXCELLENT -> KestrelEmerald
                    ServerTier.GOOD -> good
                    ServerTier.SLOW -> KestrelAmber
                    ServerTier.DEAD -> KestrelRose
                    ServerTier.UNTESTED -> grid.copy(alpha = 0.4f)
                }
                drawCircle(col.copy(alpha = 0.22f), radius = 9.dp.toPx(), center = pos)
                val isActive = key == activeKey
                drawCircle(col, radius = (if (isActive) 5.5f else 3.5f).dp.toPx(), center = pos)
                if (isActive) {
                    drawCircle(Color.White, radius = 9.dp.toPx(), center = pos, style = Stroke(1.5.dp.toPx()))
                }
                if (key == bestKey && !connected) {
                    drawCircle(
                        color = col.copy(alpha = 1f - ripple),
                        radius = (5f + 14f * ripple).dp.toPx(),
                        center = pos,
                        style = Stroke(1.5.dp.toPx())
                    )
                }
            }
        }
        // Hub: a soft glowing orb (no black disc, no hard ring) with a clean white glyph.
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .then(if (onHubClick != null) Modifier.clickable(role = Role.Button, onClick = onHubClick) else Modifier)
                .background(
                    Brush.radialGradient(
                        listOf(accent.copy(alpha = 0.95f), accent.copy(alpha = 0.55f))
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Crossfade(targetState = connected, animationSpec = tween(450), label = "hub_icon") { isOn ->
                Icon(
                    imageVector = if (isOn) Icons.Rounded.VerifiedUser else Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }
        }
    }
}
