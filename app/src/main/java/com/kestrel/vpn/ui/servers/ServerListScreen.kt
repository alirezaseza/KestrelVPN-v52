package com.kestrel.vpn.ui.servers
import androidx.compose.material.icons.rounded.Dns
import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import com.kestrel.vpn.domain.model.ConnectionStatus
import com.kestrel.vpn.domain.model.ServerTier
import com.kestrel.vpn.ui.components.AuroraBackground
import com.kestrel.vpn.ui.components.GlassCard
import com.kestrel.vpn.ui.theme.KestrelAmber
import com.kestrel.vpn.ui.theme.KestrelEmerald
import com.kestrel.vpn.ui.theme.KestrelRose
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kestrel.vpn.R
import com.kestrel.vpn.domain.model.ServerWithResult
import com.kestrel.vpn.ui.components.TierBadge
import com.kestrel.vpn.ui.util.LocaleFormatters
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import com.kestrel.vpn.ui.theme.KestrelOfflineRed
@Composable
fun ServerListScreen(
    onBack: () -> Unit,
    viewModel: ServerListViewModel = hiltViewModel()
) {
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val aliases by viewModel.aliases.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watchConnectivity() }
    }
    var renaming by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<ServerWithResult?>(null) }
    var showAll by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val context = LocalContext.current
    val vpnLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnPermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ServerListEffect.ConnectedNavigateBack -> onBack()
                ServerListEffect.RequestVpnPermission -> {
                    val intent = VpnService.prepare(context)
                    if (intent != null) {
                        vpnLauncher.launch(intent)
                    } else {
                        viewModel.onVpnPermissionResult(true)
                    }
                }
            }
        }
    }
    AuroraBackground(
        status = if (ui.isBusy) ConnectionStatus.CONNECTING else ConnectionStatus.DISCONNECTED,
        particles = false,
        offline = ui.isRed
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(text = stringResource(R.string.nav_servers), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { showAdd = true }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.servers_add_title)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.Dns,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.servers_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                var query by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf("") }
                fun nameOf(c: com.kestrel.vpn.domain.model.ProxyConfig): String =
                    aliases[c.canonicalKey] ?: com.kestrel.vpn.data.local.ServerNaming.defaultName(c.canonicalKey)
                // Default view: only the usable servers (top 15 + favorites). "Show all" or a search reveals the rest.
                val visibleServers = androidx.compose.runtime.remember(servers, favorites, query, aliases, showAll) {
                    val filtered = if (query.isNotBlank()) {
                        servers.filter {
                            nameOf(it.config).contains(query, ignoreCase = true) ||
                                it.config.remark.contains(query, ignoreCase = true) ||
                                it.config.host.contains(query, ignoreCase = true)
                        }
                    } else if (showAll) {
                        servers
                    } else {
                        val usable = servers.filter {
                            it.config.canonicalKey in favorites ||
                                (it.tier != ServerTier.DEAD && it.tier != ServerTier.UNTESTED)
                        }
                        val fav = usable.filter { it.config.canonicalKey in favorites }
                        val rest = usable.filter { it.config.canonicalKey !in favorites }.take(15)
                        (fav + rest).ifEmpty { servers.take(15) }
                    }
                    filtered.sortedByDescending { it.config.canonicalKey in favorites }
                }
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                NoticeBanner(ui = ui, onDismiss = viewModel::dismissNotice)
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item(key = "best") {
                        BestServerCard(
                            enabled = !ui.isBusy,
                            onClick = viewModel::onBestServerClicked
                        )
                    }
                    item(key = "search") {
                        androidx.compose.material3.OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            placeholder = { Text(stringResource(R.string.servers_search_hint)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) }
                        )
                    }
                    if (visibleServers.isEmpty()) {
                        item(key = "no_results") {
                            Text(
                                text = stringResource(R.string.servers_no_results),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                    items(
                        items = visibleServers,
                        key = { it.config.canonicalKey }
                    ) { item ->
                        ServerRow(
                            item = item,
                            name = nameOf(item.config),
                            onRename = { renaming = item },
                            isFavorite = item.config.canonicalKey in favorites,
                            testing = ui.testingKey == item.config.canonicalKey,
                            failed = ui.failedKey == item.config.canonicalKey,
                            onToggleFavorite = { viewModel.toggleFavorite(item.config.canonicalKey) },
                            onClick = { viewModel.onServerClicked(item.config.canonicalKey) }
                        )
                    }
                    if (query.isBlank() && servers.size > visibleServers.size || showAll) {
                        item(key = "toggle_all") {
                            androidx.compose.material3.TextButton(
                                onClick = { showAll = !showAll },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (showAll) {
                                        stringResource(R.string.servers_show_best)
                                    } else {
                                        stringResource(R.string.servers_show_all, LocaleFormatters.formatNumber(servers.size))
                                    }
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
    if (showAdd) {
        AddConfigDialog(
            onDismiss = { showAdd = false },
            onAdd = { raw ->
                val r = viewModel.addManual(raw)
                if (r.added > 0) showAdd = false
                r.added > 0
            },
            scope = scope
        )
    }
    renaming?.let { target ->
        var draft by androidx.compose.runtime.remember(target.config.canonicalKey) {
            androidx.compose.runtime.mutableStateOf(aliases[target.config.canonicalKey].orEmpty())
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.servers_rename_title)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it.take(40) },
                    singleLine = true,
                    placeholder = {
                        Text(com.kestrel.vpn.data.local.ServerNaming.defaultName(target.config.canonicalKey))
                    }
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.rename(target.config.canonicalKey, draft)
                    renaming = null
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.rename(target.config.canonicalKey, "")
                    renaming = null
                }) { Text(stringResource(R.string.servers_rename_reset)) }
            }
        )
    }
}
@Composable
private fun ServerRow(
    item: ServerWithResult,
    name: String,
    onRename: () -> Unit,
    isFavorite: Boolean,
    testing: Boolean,
    failed: Boolean,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit
) {
    val baseTierColor = when (item.tier) {
        ServerTier.EXCELLENT -> KestrelEmerald
        ServerTier.GOOD -> MaterialTheme.colorScheme.primary
        ServerTier.SLOW -> KestrelAmber
        ServerTier.DEAD -> KestrelRose
        ServerTier.UNTESTED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val tierColor by androidx.compose.animation.animateColorAsState(
        when {
            failed -> KestrelOfflineRed
            testing -> KestrelAmber
            else -> baseTierColor
        },
        androidx.compose.animation.core.tween(500),
        label = "row_color"
    )
    com.kestrel.vpn.ui.components.KestrelCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        onClick = onClick,
        onLongClick = onRename
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 56.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tierColor)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                val ping = LocaleFormatters.formatPing(item.result?.pingMs)
                val speed = LocaleFormatters.formatApproxSpeedKbps(item.result?.speedKbps)
                    ?: if (item.result?.pingMs != null) "—" else null
                val meta = listOfNotNull(
                    item.config.protocol.wireName.uppercase(),
                    ping,
                    speed
                ).joinToString(" • ")
                Text(
                    text = when {
                        testing -> stringResource(R.string.servers_testing)
                        failed -> stringResource(R.string.servers_status_down)
                        else -> meta
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) KestrelOfflineRed else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (testing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = KestrelAmber
                )
            } else if (!failed) {
                TierBadge(tier = item.tier)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (isFavorite) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                    contentDescription = stringResource(
                        if (isFavorite) R.string.servers_favorite_remove else R.string.servers_favorite_add
                    ),
                    tint = if (isFavorite) KestrelAmber else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun BestServerCard(enabled: Boolean, onClick: () -> Unit) {
    com.kestrel.vpn.ui.components.KestrelCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        onClick = if (enabled) onClick else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 64.dp)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
Icon(Icons.Rounded.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.servers_best_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.servers_best_sub),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NoticeBanner(ui: ServerListUiState, onDismiss: () -> Unit) {
    val res = when {
        ui.notice == ServerNotice.NO_PING -> R.string.servers_notice_no_ping
        ui.notice == ServerNotice.TOO_SLOW -> R.string.servers_notice_slow
        ui.notice == ServerNotice.NO_BEST -> R.string.servers_notice_no_best
        ui.deviceOffline || ui.notice == ServerNotice.DEVICE_OFFLINE -> R.string.servers_notice_offline
        else -> null
    }
    AnimatedVisibility(
        visible = res != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically()
    ) {
        // Keep the last text while the banner animates out.
        val shown = remember { mutableStateOf(res) }
        if (res != null) shown.value = res
        val text = shown.value ?: return@AnimatedVisibility
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .clickable(onClick = onDismiss),
            color = KestrelOfflineRed.copy(alpha = 0.18f),
            shape = RoundedCornerShape(18.dp)
        ) {
            Text(
                text = stringResource(text),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = KestrelOfflineRed
            )
        }
    }
}

@Composable
private fun AddConfigDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (String) -> Boolean,
    scope: kotlinx.coroutines.CoroutineScope
) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.servers_add_title)) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = false },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    maxLines = 8,
                    isError = error,
                    shape = RoundedCornerShape(16.dp),
                    placeholder = { Text(stringResource(R.string.servers_add_hint)) },
                    supportingText = if (error) {
                        { Text(stringResource(R.string.servers_add_invalid)) }
                    } else null
                )
                androidx.compose.material3.TextButton(onClick = {
                    clipboard.getText()?.text?.let { text = it; error = false }
                }) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.servers_add_paste))
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = text.isNotBlank(),
                onClick = { scope.launch { if (!onAdd(text)) error = true } }
            ) { Text(stringResource(R.string.servers_add_button)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel))
            }
        }
    )
}
