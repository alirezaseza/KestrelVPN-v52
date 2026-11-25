package com.kestrel.vpn.ui.components
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kestrel.vpn.R
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.ui.theme.KestrelAmber
import com.kestrel.vpn.ui.theme.KestrelEmerald
import com.kestrel.vpn.ui.theme.KestrelRose
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlin.math.sqrt
import kotlin.random.Random
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.wrapContentSize

@Composable
fun statusAccent(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> KestrelEmerald
    ConnectionStatus.CONNECTING,
    ConnectionStatus.FAILING_OVER -> KestrelAmber
    ConnectionStatus.ERROR -> KestrelRose
    ConnectionStatus.DISCONNECTED,
    ConnectionStatus.DISCONNECTING -> MaterialTheme.colorScheme.primary
}

@Composable
fun AuroraBackground(
    status: ConnectionStatus,
    modifier: Modifier = Modifier,
    particles: Boolean = false,
    offline: Boolean = false,
    content: @Composable BoxScope.() -> Unit
) {
    val baseAccent = statusAccent(status)
    val accent by animateColorAsState(
        if (offline) com.kestrel.vpn.ui.theme.KestrelOfflineRed else baseAccent,
        tween(900),
        label = "aurora_accent"
    )
    // 0 -> 1 when the internet is gone: the whole background fades to deep red, and back.
    val redMix by animateFloatAsState(if (offline) 1f else 0f, tween(900), label = "aurora_offline")
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    val reduce = LocalReduceEffects.current
    // Connecting / switching: wash the whole screen in the (yellow) status colour.
    val wash by animateFloatAsState(
        if (status == ConnectionStatus.CONNECTING || status == ConnectionStatus.FAILING_OVER || offline) 1f else 0f,
        tween(900),
        label = "aurora_wash"
    )
    // Read lazily inside the draw block so the animation only redraws this layer, never recomposes.
    val tState = rememberInfiniteTransition(label = "orbs").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(18000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "orbs_t"
    )
    // Film grain: fixed random specks, drawn once per frame at very low alpha (kills banding, adds richness).
    val grain = remember {
        val r = Random(7)
        List(900) { Offset(r.nextFloat(), r.nextFloat()) }
    }
    val hazeState = remember { HazeState() }
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .background(
                Brush.verticalGradient(
                    if (light) listOf(
                        androidx.compose.ui.graphics.lerp(Color(0xFFEAF0FF), Color(0xFFFFE3E6), redMix),
                        androidx.compose.ui.graphics.lerp(Color(0xFFF6F2FF), Color(0xFFFFEDEE), redMix),
                        androidx.compose.ui.graphics.lerp(Color(0xFFEAF6FF), Color(0xFFFFE9EB), redMix)
                    )
                    else listOf(
                        androidx.compose.ui.graphics.lerp(Color(0xFF0D1330), Color(0xFF2B0710), redMix),
                        androidx.compose.ui.graphics.lerp(Color(0xFF0A0F26), Color(0xFF1D050B), redMix),
                        androidx.compose.ui.graphics.lerp(Color(0xFF070B18), Color(0xFF110306), redMix)
                    )
                )
            )
                .drawBehind {
                val w = size.width
                val h = size.height
                val t = if (reduce) 0.5f else tState.value
                fun orb(c: Color, cx: Float, cy: Float, r: Float, a: Float) = drawRect(
                    Brush.radialGradient(
                        listOf(c.copy(alpha = a), c.copy(alpha = a * 0.35f), Color.Transparent),
                        Offset(cx, cy),
                        r
                    )
                )
                val k = if (light) 0.5f else 1f
                // One calm hue family around the status accent: no clashing colours, no hard bands.
                orb(accent, w * (0.30f + 0.20f * t), h * (0.22f + 0.03f * t), w * 1.05f, (0.22f + 0.22f * wash) * k)
                if (wash > 0.01f) orb(accent, w * 0.5f, h * 0.62f, w * 1.3f, 0.26f * wash * k)
                orb(Color(0xFF3B5BDB), w * (0.95f - 0.15f * t), h * (0.55f + 0.05f * t), w * 0.95f, 0.16f * k * (1f - 0.85f * redMix))
                orb(Color(0xFF5B4BC4), w * (0.05f + 0.10f * t), h * (0.85f - 0.04f * t), w * 0.9f, 0.12f * k * (1f - 0.85f * redMix))
                // faint concentric halos behind the radar
                val c = Offset(w / 2f, h * 0.30f)
                for (i in 1..4) {
                    drawCircle(
                        Color.White.copy(alpha = 0.0f),
                        radius = w * 0.28f * i,
                        center = c,
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
                    )
                }
            }
        ) {}
        if (!reduce) {
            // Own layer: the 900 grain specks are rasterised once instead of on every orb frame.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer()
                    .drawBehind {
                        val gc = if (light) Color.Black else Color.White
                        grain.forEach {
                            drawCircle(gc.copy(alpha = 0.05f), 0.8f, Offset(it.x * size.width, it.y * size.height))
                        }
                    }
            ) {}
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalHazeState provides (if (reduce) null else hazeState)) {
            content()
        }
    }
}

/** Drifting constellation: dots that link up when they come close. Purely decorative, no touch handling. */
@Composable
fun ParticleField(
    color: Color,
    modifier: Modifier = Modifier,
    count: Int = 36,
    speed: Float = 1f
) {
    // x, y in 0..1 and velocity in screen-heights per second, 4 floats per particle.
    val state = remember {
        FloatArray(count * 4).also { a ->
            val rnd = Random(7)
            for (i in 0 until count) {
                a[i * 4] = rnd.nextFloat()
                a[i * 4 + 1] = rnd.nextFloat()
                a[i * 4 + 2] = (rnd.nextFloat() - 0.5f) * 0.035f
                a[i * 4 + 3] = (rnd.nextFloat() - 0.5f) * 0.035f
            }
        }
    }
    val speedNow by rememberUpdatedState(speed)
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f) * speedNow
                    for (i in 0 until count) {
                        var x = state[i * 4] + state[i * 4 + 2] * dt
                        var y = state[i * 4 + 1] + state[i * 4 + 3] * dt
                        if (x < -0.05f) x = 1.05f else if (x > 1.05f) x = -0.05f
                        if (y < -0.05f) y = 1.05f else if (y > 1.05f) y = -0.05f
                        state[i * 4] = x
                        state[i * 4 + 1] = y
                    }
                }
                last = now
                tick = now
            }
        }
    }
    Canvas(modifier = modifier) {
        @Suppress("UNUSED_VARIABLE")
        val redraw = tick
        val w = size.width
        val h = size.height
        val link = 120.dp.toPx()
        val dot = 1.7.dp.toPx()
        for (i in 0 until count) {
            val xi = state[i * 4] * w
            val yi = state[i * 4 + 1] * h
            for (j in i + 1 until count) {
                val dx = xi - state[j * 4] * w
                val dy = yi - state[j * 4 + 1] * h
                val d = sqrt(dx * dx + dy * dy)
                if (d < link) {
                    drawLine(
                        color = color.copy(alpha = (1f - d / link) * 0.28f),
                        start = Offset(xi, yi),
                        end = Offset(state[j * 4] * w, state[j * 4 + 1] * h),
                        strokeWidth = 0.9.dp.toPx()
                    )
                }
            }
            drawCircle(color.copy(alpha = 0.5f), radius = dot, center = Offset(xi, yi))
        }
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(30.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val base = modifier.liquidGlass(shape)
    Column(
        modifier = if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base,
        content = content
    )
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .liquidGlass(CircleShape, strength = 1.1f)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(22.dp)
        )
    }
}

@Composable
fun StatTile(
    icon: ImageVector,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier
) {
    GlassCard(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).background(accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) { Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp)) }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun GradientButton(
    text: String,
    icon: ImageVector?,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(58.dp)
            .clip(shape)
            .background(Brush.horizontalGradient(listOf(cs.primary, cs.secondary)))
            .then(if (enabled) Modifier else Modifier.background(cs.background.copy(alpha = 0.62f)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(10.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = cs.onPrimary
            )
        }
    }
}

@Composable
fun PowerButton(
    status: ConnectionStatus,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptics = LocalHapticFeedback.current
    val cs = MaterialTheme.colorScheme
    val isTransitioning = status == ConnectionStatus.CONNECTING ||
    status == ConnectionStatus.FAILING_OVER ||
    status == ConnectionStatus.DISCONNECTING
    val isConnected = status == ConnectionStatus.CONNECTED
    val accent by animateColorAsState(statusAccent(status), tween(450), label = "power_color")

    val infinite = rememberInfiniteTransition(label = "power_fx")
    val breathe by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe"
    )
    val spin by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "spin"
    )
    val glowAlpha by animateFloatAsState(
        targetValue = if (isConnected || isTransitioning) 0.55f else 0.22f,
        animationSpec = tween(500),
        label = "glow_alpha"
    )
    val iconTint by animateColorAsState(
        if (isConnected || isTransitioning || status == ConnectionStatus.ERROR) Color.White else accent,
        tween(350),
        label = "icon_tint"
    )
    val description = stringResource(
        if (isConnected) R.string.vpn_action_disconnect else R.string.home_action_connect
    )

    Box(
        modifier = modifier.size(264.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            // ambient glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(accent.copy(alpha = glowAlpha), Color.Transparent),
                    center = c,
                    radius = r * breathe
                ),
                radius = r * breathe,
                center = c
            )
            // concentric hairline rings
            drawCircle(accent.copy(alpha = 0.16f), radius = r * 0.88f, center = c, style = Stroke(1.5.dp.toPx()))
            drawCircle(accent.copy(alpha = 0.09f), radius = r * 0.98f, center = c, style = Stroke(1.dp.toPx()))
            // progress / status arc
            val arcR = r * 0.74f
            val stroke = 5.dp.toPx()
            val topLeft = Offset(c.x - arcR, c.y - arcR)
            val arcSize = Size(arcR * 2, arcR * 2)
            if (isTransitioning) {
                rotate(spin, pivot = c) {
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent,
                            0.8f to accent,
                            1f to accent,
                            center = c
                        ),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
            } else {
                drawCircle(
                    color = accent.copy(alpha = if (isConnected) 0.85f else 0.35f),
                    radius = arcR,
                    center = c,
                    style = Stroke(stroke / 2f)
                )
            }
        }
        // core
        val coreBrush = if (isConnected || isTransitioning || status == ConnectionStatus.ERROR) {
            Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.62f)))
        } else {
            Brush.linearGradient(listOf(cs.surfaceVariant, cs.surface))
        }
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(CircleShape)
                .background(coreBrush)
                .border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
                .semantics { contentDescription = description }
                .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onClick()
                }
            ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.PowerSettingsNew,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(62.dp)
            )
        }
    }
}

@Composable
fun TierBadge(tier: ServerTier, modifier: Modifier = Modifier) {
    val (labelRes, bgColor, textColor) = when (tier) {
        ServerTier.EXCELLENT -> Triple(R.string.tier_excellent, KestrelEmerald.copy(alpha = 0.16f), KestrelEmerald)
        ServerTier.GOOD -> Triple(R.string.tier_good, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.colorScheme.primary)
        ServerTier.SLOW -> Triple(R.string.tier_slow, KestrelAmber.copy(alpha = 0.18f), KestrelAmber)
        ServerTier.DEAD -> Triple(R.string.tier_dead, KestrelRose.copy(alpha = 0.16f), KestrelRose)
        ServerTier.UNTESTED -> Triple(R.string.tier_untested, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Surface(
        modifier = modifier.border(1.dp, textColor.copy(alpha = 0.35f), RoundedCornerShape(999.dp)),
        shape = RoundedCornerShape(999.dp),
        color = bgColor
    ) {
        Text(
            text = stringResource(labelRes),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}
@Composable
fun OfflineBanner(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visible, modifier = modifier.fillMaxWidth()) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = com.kestrel.vpn.ui.theme.KestrelOfflineRed.copy(alpha = 0.18f)
            ),
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Rounded.WifiOff,
                    contentDescription = null,
                    tint = com.kestrel.vpn.ui.theme.KestrelOfflineRed,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.banner_offline_friendly),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}
@Composable
fun ExpandableErrorCard(
    friendlyMessage: String,
    technicalDetail: String?,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.65f)
        ),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = friendlyMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!technicalDetail.isNullOrBlank()) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(text = stringResource(R.string.common_details))
                        Icon(
                            imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(R.string.common_dismiss))
                    }
                }
            }
            AnimatedVisibility(visible = expanded && !technicalDetail.isNullOrBlank()) {
                Column {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = technicalDetail.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}


@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
/** Readable card for Settings/Servers: solid, clearly lifted surface with a visible edge so sections never blend into the background. */
@Composable
fun KestrelCard(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(22.dp),
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val light = cs.background.luminance() > 0.5f
    val fill = if (light) {
        Brush.verticalGradient(listOf(Color.White, Color(0xFFF6F8FE)))
    } else {
        Brush.verticalGradient(listOf(Color(0xFF1D2552), Color(0xFF151B3F)))
    }
    val edge = if (light) Color(0xFFBBC6E0) else Color.White.copy(alpha = 0.16f)
    val base = modifier
        .then(
            if (light) Modifier.shadow(3.dp, shape, ambientColor = Color(0xFF1B2447).copy(alpha = 0.18f), spotColor = Color(0xFF1B2447).copy(alpha = 0.18f))
            else Modifier
        )
        .clip(shape)
        .background(fill)
        .border(1.dp, edge, shape)
    Column(
        modifier = if (onClick != null) {
            base.combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
        } else base,
        content = content
    )
}

@Composable
fun kestrelSwitchColors() = androidx.compose.material3.SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = com.kestrel.vpn.ui.theme.KestrelTeal,
    checkedBorderColor = com.kestrel.vpn.ui.theme.KestrelTeal,
    uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
    uncheckedBorderColor = MaterialTheme.colorScheme.outline,
    disabledCheckedTrackColor = com.kestrel.vpn.ui.theme.KestrelTeal.copy(alpha = 0.4f)
)

@Composable
fun kestrelChipColors() = androidx.compose.material3.FilterChipDefaults.filterChipColors(
    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    labelColor = MaterialTheme.colorScheme.onSurface,
    selectedContainerColor = com.kestrel.vpn.ui.theme.KestrelTeal,
    selectedLabelColor = Color.White
)
