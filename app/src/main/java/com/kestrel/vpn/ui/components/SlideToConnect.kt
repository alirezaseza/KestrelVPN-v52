package com.kestrel.vpn.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLayoutDirection
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Slide-to-act control (accidental taps cannot toggle the VPN).
 * [parked] = thumb rests at the far end (connected / connecting); sliding back disconnects/cancels.
 * Works in RTL (thumb starts on the right and travels left).
 */
@Composable
fun SlideToConnect(
    label: String,
    accent: Color,
    parked: Boolean,
    working: Boolean,
    enabled: Boolean,
    onTrigger: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val dir = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    val thumb = 52.dp
    val pad = 6.dp
    val shape = RoundedCornerShape(32.dp)

    var dragging by remember { mutableStateOf(false) }
    var frac by remember(parked) { mutableFloatStateOf(if (parked) 1f else 0f) }
    val shown by animateFloatAsState(
        targetValue = if (dragging) frac else if (parked) 1f else 0f,
        animationSpec = if (dragging) snap() else spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "slide_progress"
    )

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(shape)
            .liquidGlass(shape, elevation = false)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
                onClick(label = label) {
                    if (enabled) onTrigger()
                    true
                }
            }
    ) {
        val travelPx = with(density) { (maxWidth - thumb - pad * 2).toPx() }
        val fillWidth = thumb + pad * 2 + with(density) { (shown * travelPx).toDp() }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(fillWidth)
                .background(accent.copy(alpha = 0.22f))
        )
        Text(
            text = label,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 72.dp)
                .alpha(((if (parked) shown else 1f - shown) * 0.9f + 0.1f).coerceIn(0f, 1f)),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = pad)
                .offset { IntOffset((dir * shown * travelPx).roundToInt(), 0) }
                .size(thumb)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.55f), accent, accent.copy(alpha = 0.75f))))
.border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                .pointerInput(enabled, parked, travelPx) {
                    if (!enabled || travelPx <= 0f) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                            val done = if (parked) frac <= 0.15f else frac >= 0.85f
                            dragging = false
                            if (done) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onTrigger()
                            }
                        },
                        onDragCancel = { dragging = false },
                        onHorizontalDrag = { change, amount ->
                            change.consume()
                            frac = (frac + dir * amount / travelPx).coerceIn(0f, 1f)
                        }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (working) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp,
                    color = Color.White
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.PowerSettingsNew,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

/** Smooth live-throughput chart: download (filled) and upload lines over the last samples. */
@Composable
fun TrafficSparkline(
    down: List<Long>,
    up: List<Long>,
    downColor: Color,
    upColor: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val n = max(down.size, up.size)
        if (n < 2) return@Canvas
        val peak = max(1L, max(down.maxOrNull() ?: 0L, up.maxOrNull() ?: 0L)).toFloat()
        val w = size.width
        val h = size.height

        fun buildPath(values: List<Long>): Path {
            val path = Path()
            val offset = n - values.size
            values.forEachIndexed { i, v ->
                val x = (i + offset) / (n - 1f) * w
                val y = h - (v / peak) * h * 0.88f - 2f
                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    val prevX = (i - 1 + offset) / (n - 1f) * w
                    val prevY = h - (values[i - 1] / peak) * h * 0.88f - 2f
                    val mid = (prevX + x) / 2f
                    path.cubicTo(mid, prevY, mid, y, x, y)
                }
            }
            return path
        }

        if (down.size >= 2) {
            val line = buildPath(down)
            val fill = Path().apply {
                addPath(line)
                lineTo(w, h)
                lineTo((n - down.size) / (n - 1f) * w, h)
                close()
            }
            drawPath(
                fill,
                brush = Brush.verticalGradient(listOf(downColor.copy(alpha = 0.35f), Color.Transparent)),
                style = Fill
            )
            drawPath(line, color = downColor, style = Stroke(width = 2.5.dp.toPx()))
        }
        if (up.size >= 2) {
            drawPath(buildPath(up), color = upColor, style = Stroke(width = 1.8.dp.toPx()))
        }
        drawLine(
            color = downColor.copy(alpha = 0.15f),
            start = Offset(0f, h - 1f),
            end = Offset(w, h - 1f),
            strokeWidth = 1.dp.toPx()
        )
    }
}
