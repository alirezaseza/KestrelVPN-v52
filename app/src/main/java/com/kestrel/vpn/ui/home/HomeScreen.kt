package com.kestrel.vpn.ui.home

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import com.kestrel.vpn.ui.components.SlideToConnect
import com.kestrel.vpn.ui.components.TrafficSparkline
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.rounded.PowerSettingsNew
import com.kestrel.vpn.ui.theme.KestrelRose
import com.kestrel.vpn.ui.theme.KestrelOfflineRed
import com.kestrel.vpn.ui.theme.KestrelTeal
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kestrel.vpn.R
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.domain.model.HuntPhase
import com.kestrel.vpn.engine.hunt.HuntUiProgress
import com.kestrel.vpn.engine.scheduler.ScanUiState
import com.kestrel.vpn.ui.components.AuroraBackground
import com.kestrel.vpn.ui.components.ExpandableErrorCard
import com.kestrel.vpn.ui.components.GlassCard
import com.kestrel.vpn.ui.components.HudValue
import com.kestrel.vpn.ui.components.HudCard
import com.kestrel.vpn.ui.components.liquidGlass
import com.kestrel.vpn.ui.components.GlassIconButton
import com.kestrel.vpn.ui.components.GradientButton
import com.kestrel.vpn.ui.components.OfflineBanner
import com.kestrel.vpn.ui.components.PowerButton
import com.kestrel.vpn.ui.components.StatTile
import com.kestrel.vpn.ui.components.TierBadge
import com.kestrel.vpn.ui.components.statusAccent
import com.kestrel.vpn.ui.util.LocaleFormatters
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.kestrel.vpn.ui.components.IconTile

@Composable
fun HomeScreen(
    onOpenServers: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    // Re-check real internet every few seconds while this screen is visible (drives the red offline theme).
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
            viewModel.watchConnectivity()
        }
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                HomeEffect.RequestVpnPermission -> {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        vpnPermissionLauncher.launch(intent)
                    } else {
                        viewModel.onVpnPermissionResult(true)
                    }
                }
            }
        }
    }

    // While the Connect search runs the VPN itself is still DISCONNECTED; show it as 'connecting' (yellow) anyway.
    val status = if (state.isSearching && state.connection.status == ConnectionStatus.DISCONNECTED) {
        ConnectionStatus.CONNECTING
    } else {
        state.connection.status
    }
    val rxHistory = remember { mutableStateListOf<Long>() }
    val txHistory = remember { mutableStateListOf<Long>() }
    LaunchedEffect(state.connection.durationSeconds, status) {
        if (status == ConnectionStatus.CONNECTED) {
            rxHistory.add(state.connection.rxBytesPerSec)
            txHistory.add(state.connection.txBytesPerSec)
            while (rxHistory.size > 40) rxHistory.removeAt(0)
            while (txHistory.size > 40) txHistory.removeAt(0)
        } else {
            rxHistory.clear()
            txHistory.clear()
        }
    }
    val connected = status == ConnectionStatus.CONNECTED
    val busy = status == ConnectionStatus.CONNECTING ||
        status == ConnectionStatus.FAILING_OVER ||
        status == ConnectionStatus.DISCONNECTING
    val accent by animateColorAsState(
        if (state.isRed) KestrelOfflineRed else statusAccent(status),
        tween(900),
        label = "home_accent"
    )
    val bannerMessage = state.friendlyErrorRes?.let { stringResource(it) } ?: state.serviceNoticeText
    // Keep the last message so the banner can fade out without its text vanishing first.
    val lastBanner = remember { mutableStateOf("") }
    if (!bannerMessage.isNullOrBlank()) lastBanner.value = bannerMessage
    AuroraBackground(status = status, offline = state.isRed) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            bottomBar = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp)
                ) {
                    ScanStatusStrip(state = state)
                    ConnectButton(
                        status = status,
                        searching = state.isSearching,
                        enabled = state.isSearching || (status != ConnectionStatus.DISCONNECTING &&
                            (!state.isDeviceOffline || state.connection.isActiveOrTransitioning)),
                        onClick = viewModel::onPowerButtonClicked
                    )
                }
            }
        ) { padding ->
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                HomeHeader(onOpenSettings = onOpenSettings)
            }
            PullToRefreshBox(
                isRefreshing = state.isRefreshingSubscription,
                onRefresh = { viewModel.refreshSubscription(force = true) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Content is centred in the free space: no big empty gap under the server card when standing by.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    AnimatedVisibility(
                        visible = state.isDeviceOffline,
                        enter = fadeIn(tween(300)) + expandVertically(tween(350, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            OfflineBanner(visible = true)
                        }
                    }
                    AnimatedVisibility(
                        visible = !bannerMessage.isNullOrBlank(),
                        enter = fadeIn(tween(300)) + expandVertically(tween(350, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(200)) + shrinkVertically(tween(300, easing = FastOutSlowInEasing))
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(12.dp))
                            ExpandableErrorCard(
                                friendlyMessage = lastBanner.value,
                                technicalDetail = state.errorDetail,
                                onDismiss = viewModel::clearError
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    val peakRx = (rxHistory.maxOrNull() ?: 0L).coerceAtLeast(1L).toFloat()
                    val ringLevel = if (connected) {
                        (state.connection.rxBytesPerSec / peakRx).coerceIn(0.04f, 1f)
                    } else 0f
                    val ringSize by animateDpAsState(
                        if (connected) 250.dp else 300.dp,
                        tween(600, easing = FastOutSlowInEasing),
                        label = "ring_size"
                    )
                    Box(modifier = Modifier.size(ringSize), contentAlignment = Alignment.Center) {
                        com.kestrel.vpn.ui.components.TelemetryRing(
                            level = ringLevel,
                            accent = accent,
                            busy = busy,
                            modifier = Modifier.fillMaxSize()
                        )
                        RadarView(
                            status = status,
                            servers = state.radarServers,
                            activeKey = state.connection.activeServer?.config?.canonicalKey,
                            bestKey = state.bestServer?.config?.canonicalKey,
                            modifier = Modifier.size(ringSize * 0.815f),
                            onHubClick = if (state.isSearching || status != ConnectionStatus.DISCONNECTING) viewModel::onPowerButtonClicked else null
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .liquidGlass(CircleShape, tint = accent, strength = 0.8f, elevation = false)
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        AnimatedContent(
                            targetState = status,
                            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) },
                            label = "status_chip"
                        ) { st ->
                            Text(
                                text = when (st) {
                                    ConnectionStatus.CONNECTED -> "● SECURE LINK"
                                    ConnectionStatus.CONNECTING -> "◌ HANDSHAKE"
                                    ConnectionStatus.FAILING_OVER -> "⟳ REROUTING"
                                    ConnectionStatus.DISCONNECTING -> "◌ CLOSING"
                                    ConnectionStatus.ERROR -> "✕ LINK FAULT"
                                    ConnectionStatus.DISCONNECTED -> "○ STANDBY"
                                },
                                fontSize = 11.sp,
                                letterSpacing = 2.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accent
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    AnimatedContent(
                        targetState = status,
                        transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) },
                        label = "status_title"
                    ) { st ->
                        Text(
                            text = when (st) {
                                ConnectionStatus.CONNECTED -> stringResource(R.string.home_status_connected)
                                ConnectionStatus.CONNECTING -> stringResource(R.string.home_status_connecting)
                                ConnectionStatus.FAILING_OVER -> stringResource(R.string.home_status_switching)
                                ConnectionStatus.DISCONNECTING -> stringResource(R.string.home_status_disconnecting)
                                ConnectionStatus.DISCONNECTED,
                                ConnectionStatus.ERROR -> stringResource(R.string.home_status_not_protected)
                            },
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    ServerPickerCard(state = state, accent = accent, onClick = onOpenServers)
                    AnimatedVisibility(
                        visible = connected,
                        enter = fadeIn(tween(500, delayMillis = 100)) + expandVertically(tween(500, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(250)) + shrinkVertically(tween(400, easing = FastOutSlowInEasing))
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Spacer(modifier = Modifier.height(14.dp))
                            ProtectionChips()
                            Spacer(modifier = Modifier.height(14.dp))
                            ConnectedStats(state, rxHistory, txHistory)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
            }
        }
    }

    if (state.pendingDisconnectForTest != null) {
        AlertDialog(
            onDismissRequest = viewModel::dismissDisconnectPrompt,
            title = { Text(text = stringResource(R.string.dialog_disconnect_test_title)) },
            text = { Text(text = stringResource(R.string.dialog_disconnect_test_body)) },
            confirmButton = {
                Button(onClick = viewModel::confirmDisconnectAndRunTest) {
                    Text(text = stringResource(R.string.dialog_disconnect_and_test))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissDisconnectPrompt) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }
    if (state.showHuntSheet) {
        ModalBottomSheet(
            onDismissRequest = viewModel::dismissHuntSheet,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            HuntProgressSheetContent(
                progress = state.huntProgress,
                nameOf = state::nameOf,
                onConnectWinner = viewModel::connectToHuntWinner,
                onRestart = viewModel::restartHunt,
                onCancel = viewModel::cancelHunt
            )
        }
    }
}

@Composable
private fun HomeHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .liquidGlass(RoundedCornerShape(14.dp), tint = MaterialTheme.colorScheme.primary, strength = 1.4f),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.app_name).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Black,
                letterSpacing = androidx.compose.ui.unit.TextUnit(3f, androidx.compose.ui.unit.TextUnitType.Sp),
                color = MaterialTheme.colorScheme.onBackground
            )
        }
        GlassIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = stringResource(R.string.nav_settings),
            onClick = onOpenSettings
        )
    }
}

/**
 * One-tap entry to the server list. Connected: the active server. Otherwise: the server that worked last time
 * (the Connect button re-checks it first), falling back to the best known one.
 */
@Composable
private fun ServerPickerCard(state: HomeUiState, accent: Color, onClick: () -> Unit) {
    val active = state.connection.activeServer
    val lastGood = state.lastGoodKey
    val server = active ?: state.bestServer
    val title: String? = when {
        active != null -> state.nameOf(active.config)
        lastGood != null -> state.nameOfKey(lastGood)
        server != null -> state.nameOf(server.config)
        else -> null
    }
    val caption: String? = when {
        active != null -> active.config.protocol.name + " / " + active.config.transport.name
        lastGood != null -> stringResource(R.string.home_last_good)
        server != null -> stringResource(R.string.home_ready_best)
        else -> null
    }
    GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 68.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon = Icons.Rounded.Dns, accent = accent, size = 42)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (title != null) {
                    Text(
                        text = caption.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = stringResource(R.string.nav_servers),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (server != null && lastGood == null || active != null) {
                Spacer(modifier = Modifier.width(8.dp))
                (active ?: server)?.let { TierBadge(tier = it.tier) }
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConnectedStats(state: HomeUiState, rxHistory: List<Long>, txHistory: List<Long>) {
    val conn = state.connection
    val accent = statusAccent(conn.status)
    val violet = MaterialTheme.colorScheme.secondary
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HudCard(label = stringResource(R.string.stat_speed), accent = accent, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(4.dp))
                HudValue(LocaleFormatters.formatLiveRate(conn.rxBytesPerSec), size = 28)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowUpward, null, tint = violet, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(2.dp))
                HudValue(LocaleFormatters.formatLiveRate(conn.txBytesPerSec), size = 16)
            }
            Spacer(Modifier.height(10.dp))
            TrafficSparkline(
                down = rxHistory.toList(),
                up = txHistory.toList(),
                downColor = accent,
                upColor = violet,
                modifier = Modifier.fillMaxWidth().height(56.dp)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HudCard(label = stringResource(R.string.stat_uptime), accent = accent, modifier = Modifier.weight(1f)) {
                HudValue(LocaleFormatters.formatDuration(conn.durationSeconds), size = 20)
            }
            HudCard(label = stringResource(R.string.stat_latency), accent = accent, modifier = Modifier.weight(1f)) {
                HudValue(LocaleFormatters.formatPing(conn.activeServer?.result?.pingMs), size = 20)
            }
        }
        HudCard(label = stringResource(R.string.stat_data), accent = violet, modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowDownward, null, tint = accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                HudValue(LocaleFormatters.formatBytes(conn.totalRxBytes), size = 18)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Rounded.ArrowUpward, null, tint = violet, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                HudValue(LocaleFormatters.formatBytes(conn.totalTxBytes), size = 18)
            }
        }
    }
}

@Composable
private fun ScanStatusStrip(state: HomeUiState) {
    val scan = state.scanState
    val hunt = state.huntProgress
    val offline = scan is ScanUiState.PausedOffline || hunt.isPausedOffline
    val searching = state.isSearching
    val running = scan is ScanUiState.Running || offline || searching
    val total = state.totalConfigs
    val tested = when (scan) {
        is ScanUiState.Running -> scan.totalTestedOverall
        is ScanUiState.PausedOffline -> scan.totalTestedOverall
        else -> 0
    }
    val accent = if (offline) KestrelRose else MaterialTheme.colorScheme.primary
    val progress by animateFloatAsState(
        if (total > 0) (tested.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f,
        tween(500),
        label = "scan_progress"
    )
    AnimatedVisibility(
        visible = running,
        enter = fadeIn(tween(300)) + expandVertically(tween(350, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(200)) + shrinkVertically(tween(300, easing = FastOutSlowInEasing))
    ) {
        Column {
            GlassCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = if (offline) {
                            stringResource(R.string.scan_offline_paused)
                        } else if (searching) {
                            stringResource(if (state.isTestingServer) R.string.home_testing_server else R.string.home_searching_best)
                        } else {
                            stringResource(
                                R.string.scan_testing_progress,
                                LocaleFormatters.formatNumber(tested),
                                LocaleFormatters.formatNumber(total)
                            )
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = accent
                    )
                    if (searching && !offline) {
                        // Quick search stops as soon as a fast server is found, so no "x / total" counter here.
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                    } else if (!offline && total > 0) {
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(CircleShape),
                            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun HuntProgressSheetContent(
    progress: HuntUiProgress,
    nameOf: (com.kestrel.vpn.domain.model.ProxyConfig) -> String,
    onConnectWinner: () -> Unit,
    onRestart: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.hunt_mode_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (progress.phase == HuntPhase.COMPLETED && progress.winner != null) {
            val winner = progress.winner
            val winnerName = nameOf(winner.config)
            Text(
                text = stringResource(R.string.hunt_winner_found),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = winnerName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        val pingText = LocaleFormatters.formatPing(winner.result?.pingMs)
                        val speedText = LocaleFormatters.formatApproxSpeedKbps(winner.result?.speedKbps)
                        Text(
                            text = listOfNotNull(pingText, speedText).joinToString(" • "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TierBadge(tier = winner.tier)
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onConnectWinner,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = stringResource(R.string.hunt_connect_winner))
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onRestart) {
                Text(text = stringResource(R.string.hunt_run_again))
            }
        } else {
            val roundTitle = when (progress.phase) {
                HuntPhase.ROUND_1_PING -> stringResource(R.string.hunt_round_1_label)
                HuntPhase.ROUND_2_SPEED -> stringResource(R.string.hunt_round_2_label)
                HuntPhase.ROUND_3_FINALS -> stringResource(R.string.hunt_round_3_label)
                else -> stringResource(R.string.hunt_preparing)
            }
            Text(
                text = stringResource(
                    R.string.hunt_round_counter,
                    LocaleFormatters.formatNumber(progress.roundNumber.coerceAtLeast(1)),
                    LocaleFormatters.formatNumber(progress.totalRounds),
                    roundTitle
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            if (progress.isPausedOffline) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.scan_offline_paused),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = KestrelRose
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(
                    R.string.hunt_contenders_left,
                    LocaleFormatters.formatNumber(progress.contendersLeft)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            val fraction = if (progress.roundTotal > 0) {
                (progress.testedInRound.toFloat() / progress.roundTotal.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = stringResource(R.string.common_cancel))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun ProtectionChips() {
    val labels = listOf(
        stringResource(R.string.chip_dns_encrypted),
        stringResource(R.string.chip_ipv6_tunneled),
        stringResource(R.string.chip_no_direct_probe)
    )
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEach { label ->
            Row(
                modifier = Modifier
                    .liquidGlass(CircleShape, tint = com.kestrel.vpn.ui.theme.KestrelEmerald, strength = 0.9f, elevation = false)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Check, null, tint = com.kestrel.vpn.ui.theme.KestrelEmerald, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = label, maxLines = 1, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}


@Composable
private fun ConnectButton(status: ConnectionStatus, searching: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val connected = status == ConnectionStatus.CONNECTED
    val working = searching || status == ConnectionStatus.CONNECTING ||
        status == ConnectionStatus.FAILING_OVER ||
        status == ConnectionStatus.DISCONNECTING
    val spec = tween<Color>(500, easing = FastOutSlowInEasing)
    val colorA by animateColorAsState(
        when {
            working -> com.kestrel.vpn.ui.theme.KestrelAmber
            connected -> KestrelRose
            else -> KestrelTeal
        },
        spec, label = "connect_btn_a"
    )
    val colorB by animateColorAsState(
        when {
            working -> com.kestrel.vpn.ui.theme.KestrelAmber.copy(alpha = 0.8f)
            connected -> KestrelRose.copy(alpha = 0.8f)
            else -> Color(0xFF5B8CFF)
        },
        spec, label = "connect_btn_b"
    )
    val content by animateColorAsState(if (working) Color(0xFF2A1A00) else Color.White, tween(400), label = "connect_btn_content")
    val btnAlpha by animateFloatAsState(if (enabled) 1f else 0.55f, tween(300), label = "connect_btn_alpha")
    val label = if (searching) stringResource(R.string.home_searching_best) else when (status) {
        ConnectionStatus.CONNECTED -> stringResource(R.string.vpn_action_disconnect)
        ConnectionStatus.CONNECTING -> stringResource(R.string.home_status_connecting)
        ConnectionStatus.FAILING_OVER -> stringResource(R.string.home_status_switching)
        ConnectionStatus.DISCONNECTING -> stringResource(R.string.home_status_disconnecting)
        else -> stringResource(R.string.home_action_connect)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .graphicsLayer { alpha = btnAlpha }
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.horizontalGradient(listOf(colorA, colorB)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            Crossfade(targetState = working, animationSpec = tween(300), label = "connect_btn_icon") { isWorking ->
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.5.dp, color = content)
                } else {
                    Icon(Icons.Rounded.PowerSettingsNew, contentDescription = null, tint = content, modifier = Modifier.size(26.dp))
                }
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(180)) },
            label = "connect_btn_label"
        ) { text ->
            Text(text = text, color = content, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}
