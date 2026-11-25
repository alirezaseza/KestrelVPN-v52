package com.kestrel.vpn.ui.settings
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import com.kestrel.vpn.ui.components.GlassCard
import com.kestrel.vpn.ui.components.AuroraBackground
import com.kestrel.vpn.domain.model.ConnectionStatus
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kestrel.vpn.BuildConfig
import com.kestrel.vpn.R
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Shield
import com.kestrel.vpn.data.local.AppThemeMode
import androidx.compose.runtime.remember
import com.kestrel.vpn.data.local.PreConnectCheck
import com.kestrel.vpn.data.local.SplitMode
import com.kestrel.vpn.data.local.TrafficHistoryStore
import com.kestrel.vpn.domain.model.TestMode
import com.kestrel.vpn.domain.model.TestSpeedMode
import com.kestrel.vpn.ui.util.LocaleFormatters
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.GppMaybe
import com.kestrel.vpn.ui.components.SettingsBlock
import com.kestrel.vpn.ui.components.SettingsDivider
import com.kestrel.vpn.ui.components.SettingsRow
import com.kestrel.vpn.ui.components.SettingsSection
import com.kestrel.vpn.ui.components.SettingsStatTile
import com.kestrel.vpn.ui.components.SettingsSwitchRow
import com.kestrel.vpn.ui.theme.KestrelAmber
import com.kestrel.vpn.ui.theme.KestrelEmerald
import com.kestrel.vpn.ui.theme.KestrelRose
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenSplitTunnel: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val todayIndex = TrafficHistoryStore.todayIndex()
    val todayBytes = usage.firstOrNull { it.dayIndex == todayIndex }?.totalBytes ?: 0L
    val weekBytes = usage.filter { it.dayIndex > todayIndex - 7 }.sumOf { it.totalBytes }
    val urlInvalid by viewModel.urlValidationError.collectAsStateWithLifecycle()
    var urlDraft by rememberSaveable(prefs.subscriptionUrl) { mutableStateOf(prefs.subscriptionUrl) }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val blue = cs.primary
    val violet = cs.secondary
    AuroraBackground(status = ConnectionStatus.DISCONNECTED, particles = false) {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = { Text(text = stringResource(R.string.nav_settings), fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp)
            ) {
                // ── Appearance ─────────────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.settings_theme),
                    icon = Icons.Rounded.Palette,
                    accent = violet
                ) {
                    SettingsBlock(title = stringResource(R.string.settings_language), icon = Icons.Rounded.Language, accent = violet) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.languageTag.isBlank(),
                                onClick = { viewModel.setLanguageTag("") },
                                label = { Text(stringResource(R.string.settings_system_default)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.languageTag == "en",
                                onClick = { viewModel.setLanguageTag("en") },
                                label = { Text("English") }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.languageTag == "fa",
                                onClick = { viewModel.setLanguageTag("fa") },
                                label = { Text("فارسی") }
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsBlock(title = stringResource(R.string.settings_theme), icon = Icons.Rounded.Palette, accent = violet) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.themeMode == AppThemeMode.SYSTEM,
                                onClick = { viewModel.setThemeMode(AppThemeMode.SYSTEM) },
                                label = { Text(stringResource(R.string.settings_system_default)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.themeMode == AppThemeMode.LIGHT,
                                onClick = { viewModel.setThemeMode(AppThemeMode.LIGHT) },
                                label = { Text(stringResource(R.string.settings_theme_light)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.themeMode == AppThemeMode.DARK,
                                onClick = { viewModel.setThemeMode(AppThemeMode.DARK) },
                                label = { Text(stringResource(R.string.settings_theme_dark)) }
                            )
                        }
                    }
                }

                // ── Data usage ─────────────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.settings_usage),
                    icon = Icons.Rounded.DataUsage,
                    accent = KestrelEmerald
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SettingsStatTile(
                            label = stringResource(R.string.usage_today),
                            value = LocaleFormatters.formatBytes(todayBytes),
                            accent = KestrelEmerald,
                            modifier = Modifier.weight(1f)
                        )
                        SettingsStatTile(
                            label = stringResource(R.string.usage_week),
                            value = LocaleFormatters.formatBytes(weekBytes),
                            accent = blue,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // ── Routing & privacy ──────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.settings_routing),
                    icon = Icons.Rounded.Shield,
                    accent = blue
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_bypass_iran),
                        subtitle = stringResource(R.string.settings_bypass_iran_desc),
                        icon = Icons.Rounded.Public,
                        accent = KestrelEmerald,
                        checked = prefs.bypassIran,
                        onCheckedChange = viewModel::setBypassIran
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_split_tunnel),
                        subtitle = stringResource(
                            when (prefs.splitMode) {
                                SplitMode.OFF -> R.string.split_mode_off
                                SplitMode.EXCLUDE -> R.string.split_mode_exclude
                                SplitMode.INCLUDE -> R.string.split_mode_include
                            }
                        ),
                        icon = Icons.Rounded.CallSplit,
                        accent = violet,
                        showChevron = true,
                        onClick = onOpenSplitTunnel
                    )
                    SettingsDivider()
                    val exitIp by viewModel.exitIp.collectAsState()
                    val checking by viewModel.checkingExitIp.collectAsState()
                    SettingsRow(
                        title = stringResource(R.string.settings_leak_check),
                        subtitle = exitIp?.let { stringResource(R.string.settings_leak_check_result, it) }
                            ?: stringResource(R.string.settings_leak_check_desc),
                        icon = Icons.Rounded.Radar,
                        accent = KestrelAmber,
                        enabled = !checking,
                        onClick = viewModel::checkExitIp
                    )
                    SettingsDivider()
                    val ctxKs = androidx.compose.ui.platform.LocalContext.current
                    SettingsRow(
                        title = stringResource(R.string.settings_killswitch),
                        subtitle = stringResource(R.string.settings_killswitch_desc),
                        icon = Icons.Rounded.Lock,
                        accent = KestrelRose,
                        showChevron = true,
                        onClick = {
                            runCatching {
                                ctxKs.startActivity(
                                    android.content.Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }
                    )
                }

                // ── Hunt mode ──────────────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.hunt_mode_title),
                    icon = Icons.Rounded.AutoAwesome,
                    accent = KestrelAmber
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.settings_hunt_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                viewModel.startHunt()
                                onBack()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = stringResource(R.string.hunt_mode_button), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // ── Server testing ─────────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.settings_testing),
                    icon = Icons.Rounded.Speed,
                    accent = blue
                ) {
                    SettingsBlock(title = stringResource(R.string.settings_test_speed), icon = Icons.Rounded.Speed, accent = blue) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.testSpeedMode == TestSpeedMode.SLOW,
                                onClick = { viewModel.setTestSpeedMode(TestSpeedMode.SLOW) },
                                label = { Text(stringResource(R.string.test_speed_slow)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.testSpeedMode == TestSpeedMode.MEDIUM,
                                onClick = { viewModel.setTestSpeedMode(TestSpeedMode.MEDIUM) },
                                label = { Text(stringResource(R.string.test_speed_medium)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.testSpeedMode == TestSpeedMode.FAST,
                                onClick = { viewModel.setTestSpeedMode(TestSpeedMode.FAST) },
                                label = { Text(stringResource(R.string.test_speed_fast)) }
                            )
                        }
                        Text(
                            text = stringResource(
                                when (prefs.testSpeedMode) {
                                    TestSpeedMode.SLOW -> R.string.test_speed_slow_desc
                                    TestSpeedMode.MEDIUM -> R.string.test_speed_medium_desc
                                    TestSpeedMode.FAST -> R.string.test_speed_fast_desc
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    SettingsDivider()
                    SettingsBlock(title = stringResource(R.string.settings_mobile_mode), icon = Icons.Rounded.SignalCellularAlt, accent = blue) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.mobileTestMode == TestMode.PING_ONLY,
                                onClick = { viewModel.setMobileTestMode(TestMode.PING_ONLY) },
                                label = { Text(stringResource(R.string.test_mode_ping_only)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.mobileTestMode == TestMode.PING_AND_SPEED,
                                onClick = { viewModel.setMobileTestMode(TestMode.PING_AND_SPEED) },
                                label = { Text(stringResource(R.string.test_mode_ping_speed)) }
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsBlock(title = stringResource(R.string.settings_wifi_mode), icon = Icons.Rounded.Wifi, accent = blue) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.wifiTestMode == TestMode.PING_ONLY,
                                onClick = { viewModel.setWifiTestMode(TestMode.PING_ONLY) },
                                label = { Text(stringResource(R.string.test_mode_ping_only)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.wifiTestMode == TestMode.PING_AND_SPEED,
                                onClick = { viewModel.setWifiTestMode(TestMode.PING_AND_SPEED) },
                                label = { Text(stringResource(R.string.test_mode_ping_speed)) }
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_data_saver),
                        subtitle = stringResource(R.string.settings_data_saver_desc),
                        icon = Icons.Rounded.DataUsage,
                        accent = KestrelEmerald,
                        checked = prefs.dataSaver,
                        onCheckedChange = viewModel::setDataSaver
                    )
                    SettingsDivider()
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(
                                R.string.settings_speed_payload,
                                LocaleFormatters.formatPayloadMb(prefs.speedPayloadKb)
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = prefs.speedPayloadKb.toFloat(),
                            onValueChange = { viewModel.setSpeedPayloadKb(it.roundToInt()) },
                            valueRange = 200f..300f,
                            steps = 9
                        )
                    }
                    SettingsDivider()
                    SettingsBlock(title = stringResource(R.string.settings_preconnect), icon = Icons.Rounded.Speed, accent = violet) {
                        Text(
                            text = stringResource(R.string.settings_preconnect_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.preConnectCheck == PreConnectCheck.AUTO,
                                onClick = { viewModel.setPreConnectCheck(PreConnectCheck.AUTO) },
                                label = { Text(stringResource(R.string.preconnect_auto)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.preConnectCheck == PreConnectCheck.PING_ONLY,
                                onClick = { viewModel.setPreConnectCheck(PreConnectCheck.PING_ONLY) },
                                label = { Text(stringResource(R.string.test_mode_ping_only)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.preConnectCheck == PreConnectCheck.PING_AND_SPEED,
                                onClick = { viewModel.setPreConnectCheck(PreConnectCheck.PING_AND_SPEED) },
                                label = { Text(stringResource(R.string.test_mode_ping_speed)) }
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_min_speed),
                        subtitle = stringResource(R.string.settings_min_speed_desc),
                        icon = Icons.Rounded.Speed,
                        accent = KestrelAmber,
                        checked = prefs.minSpeedEnabled,
                        onCheckedChange = viewModel::setMinSpeedEnabled
                    )
                    if (prefs.minSpeedEnabled) {
                        val levels = remember { listOf(50, 100, 250, 500, 1000, 2000, 5000) }
                        val idx = levels.indices.minByOrNull { kotlin.math.abs(levels[it] - prefs.minSpeedKbps) } ?: 0
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text(
                                text = stringResource(
                                    R.string.settings_min_speed_value,
                                    LocaleFormatters.formatMbit(prefs.minSpeedKbps)
                                ),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Slider(
                                value = idx.toFloat(),
                                onValueChange = { viewModel.setMinSpeedKbps(levels[it.roundToInt().coerceIn(0, levels.lastIndex)]) },
                                valueRange = 0f..levels.lastIndex.toFloat(),
                                steps = levels.size - 2
                            )
                        }
                    }
                    SettingsDivider()
                    SettingsBlock(title = stringResource(R.string.settings_fingerprint), icon = Icons.Rounded.Tune, accent = violet) {
                        Text(
                            text = stringResource(R.string.settings_fingerprint_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "",
                                onClick = { viewModel.setFingerprintOverride("") },
                                label = { Text(stringResource(R.string.fingerprint_from_link)) }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "chrome",
                                onClick = { viewModel.setFingerprintOverride("chrome") },
                                label = { Text("chrome") }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "firefox",
                                onClick = { viewModel.setFingerprintOverride("firefox") },
                                label = { Text("firefox") }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "safari",
                                onClick = { viewModel.setFingerprintOverride("safari") },
                                label = { Text("safari") }
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "ios",
                                onClick = { viewModel.setFingerprintOverride("ios") },
                                label = { Text("ios") }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "android",
                                onClick = { viewModel.setFingerprintOverride("android") },
                                label = { Text("android") }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "edge",
                                onClick = { viewModel.setFingerprintOverride("edge") },
                                label = { Text("edge") }
                            )
                            FilterChip(
                                colors = com.kestrel.vpn.ui.components.kestrelChipColors(),
                                selected = prefs.fingerprintOverride == "random",
                                onClick = { viewModel.setFingerprintOverride("random") },
                                label = { Text("random") }
                            )
                        }
                    }
                }

                // ── Advanced ───────────────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.settings_advanced),
                    icon = Icons.Rounded.Tune,
                    accent = violet
                ) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_reduce_effects),
                        subtitle = stringResource(R.string.settings_reduce_effects_desc),
                        icon = Icons.Rounded.Bolt,
                        accent = KestrelAmber,
                        checked = prefs.reduceEffects,
                        onCheckedChange = viewModel::setReduceEffects
                    )
                    SettingsDivider()
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_allow_insecure),
                        subtitle = stringResource(R.string.settings_allow_insecure_desc),
                        icon = Icons.Rounded.GppMaybe,
                        accent = KestrelRose,
                        checked = prefs.allowInsecureTls,
                        onCheckedChange = viewModel::setAllowInsecureTls
                    )
                    SettingsDivider()
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = urlDraft,
                                onValueChange = {
                                    urlDraft = it
                                    if (urlInvalid) viewModel.clearUrlValidationError()
                                },
                                label = { Text(stringResource(R.string.settings_sub_url)) },
                                leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null) },
                                isError = urlInvalid,
                                supportingText = if (urlInvalid) {
                                    { Text(text = stringResource(R.string.settings_sub_url_invalid)) }
                                } else {
                                    null
                                },
                                singleLine = true,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { viewModel.saveSubscriptionUrl(urlDraft) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(text = stringResource(R.string.common_save))
                            }
                            OutlinedButton(
                                onClick = viewModel::resetSubscriptionUrl,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp)
                            ) {
                                Text(text = stringResource(R.string.settings_reset_default))
                            }
                        }
                    }
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_clear_cache),
                        icon = Icons.Rounded.DeleteSweep,
                        accent = KestrelRose,
                        onClick = { showClearConfirm = true }
                    )
                }

                // ── Support ────────────────────────────────────────────────
                SettingsSection(
                    title = stringResource(R.string.settings_privacy_about),
                    icon = Icons.Rounded.Info,
                    accent = blue
                ) {
                    val clipboardDiag = androidx.compose.ui.platform.LocalClipboardManager.current
                    SettingsRow(
                        title = stringResource(R.string.settings_diag),
                        subtitle = stringResource(R.string.settings_diag_desc),
                        icon = Icons.Rounded.BugReport,
                        accent = KestrelAmber,
                        onClick = {
                            clipboardDiag.setText(
                                androidx.compose.ui.text.AnnotatedString(
                                    com.kestrel.vpn.util.DiagnosticsLog.dump().ifBlank { "(empty)" }
                                )
                            )
                        }
                    )
                    SettingsDivider()
                    SettingsRow(
                        title = stringResource(R.string.settings_privacy_about),
                        subtitle = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                        icon = Icons.Rounded.VisibilityOff,
                        accent = blue,
                        showChevron = true,
                        onClick = onOpenPrivacyPolicy
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(text = stringResource(R.string.settings_clear_cache)) },
            text = { Text(text = stringResource(R.string.settings_clear_cache_confirm)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearTestCache()
                        showClearConfirm = false
                    }
                ) {
                    Text(text = stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }
}
