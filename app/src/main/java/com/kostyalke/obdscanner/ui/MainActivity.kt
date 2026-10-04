package com.kostyalke.obdscanner.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Экран не гаснет во время диагностики.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { AppTheme { App(vm) } }
    }

    override fun onResume() {
        super.onResume()
        vm.onResume()
    }
}

/** [label] — короткая подпись вкладки (влезает и при крупном шрифте), [title] — заголовок экрана. */
enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    ERRORS("Ошибки", "Ошибки", Icons.Filled.Warning),
    LIVE("Датчики", "Датчики", AppIcons.Speed),
    READY("Тесты", "Готовность систем", Icons.Filled.CheckCircle),
    CAR("Авто", "Авто", AppIcons.Car),
}

private enum class Overlay { NONE, LOG, HISTORY }

private fun Context.shareText(title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { startActivity(Intent.createChooser(send, title)) }
}

@Composable
private fun App(vm: AppViewModel) {
    val ctx = LocalContext.current
    val conn by vm.conn.collectAsStateWithLifecycle()
    val dtc by vm.dtc.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.ERRORS) }
    var overlay by rememberSaveable { mutableStateOf(Overlay.NONE) }
    val connected = conn as? ConnState.Connected
    val update by vm.update.collectAsStateWithLifecycle()
    var autoUpdate by remember { mutableStateOf(vm.autoUpdate) }

    BackHandler(enabled = overlay != Overlay.NONE) { overlay = Overlay.NONE }

    AppScaffold(
        title = when {
            overlay == Overlay.LOG -> "Журнал обмена"
            overlay == Overlay.HISTORY -> "История отчётов"
            connected != null -> tab.title
            else -> "OBD Сканер"
        },
        connected = connected,
        tab = tab,
        errorCount = dtc.report?.let { it.stored.size + it.permanent.size } ?: 0,
        showTabs = connected != null && overlay == Overlay.NONE,
        showBack = overlay != Overlay.NONE,
        onBack = { overlay = Overlay.NONE },
        onTab = { tab = it },
        onLog = { overlay = Overlay.LOG },
        onHistory = { overlay = Overlay.HISTORY },
        onDisconnect = { vm.disconnect() },
        versionName = vm.versionName,
        autoUpdate = autoUpdate,
        onAutoUpdate = { autoUpdate = it; vm.autoUpdate = it },
        onCheckUpdate = { vm.checkForUpdate(manual = true) },
        banner = {
            UpdateBanner(
                update,
                onUpdate = vm::startUpdate,
                onInstall = vm::installNow,
                onGrant = {
                    runCatching {
                        ctx.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + ctx.packageName)))
                    }
                },
                onRetry = { vm.checkForUpdate(manual = true) },
                onDismiss = vm::dismissUpdate,
            )
        },
    ) {
        when {
            overlay == Overlay.HISTORY -> {
                val reports by vm.reports.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) { vm.refreshReports() }
                HistoryContent(reports, onShare = { ctx.shareText("Отправить отчёт", it.text) }, onDelete = vm::deleteReport)
            }
            overlay == Overlay.LOG -> {
                val log by vm.log.collectAsStateWithLifecycle()
                val busy by vm.busy.collectAsStateWithLifecycle()
                LogContent(log, busy, onSend = vm::sendRaw, onShare = { ctx.shareText("Отправить журнал", vm.shareLog()) },
                    onClear = vm::clearLog)
            }
            connected == null -> ConnectScreen(vm, conn)
            else -> when (tab) {
                Tab.ERRORS -> ErrorsContent(
                    st = dtc,
                    warnings = connected.warnings,
                    info = vm.db::info,
                    onRead = vm::readDtcs,
                    onClear = vm::clearDtcs,
                    onShare = { vm.buildReportText()?.let { ctx.shareText("Отправить отчёт", it) } },
                    onSearch = { q ->
                        val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                )
                Tab.LIVE -> {
                    val live by vm.live.collectAsStateWithLifecycle()
                    DisposableEffect(Unit) {
                        vm.startLive()
                        onDispose { vm.stopLive() }
                    }
                    LiveContent(live, vm::toggleFavorite, vm::setOnlyFavorites, vm::resetMinMax)
                }
                Tab.READY -> {
                    val rd by vm.readiness.collectAsStateWithLifecycle()
                    val since by vm.sinceCleared.collectAsStateWithLifecycle()
                    val busy by vm.busy.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { if (rd == null || since.isEmpty()) vm.readReadiness() }
                    ReadinessContent(rd, since, busy, vm::readReadiness)
                }
                Tab.CAR -> {
                    val info by vm.vehicle.collectAsStateWithLifecycle()
                    val busy by vm.busy.collectAsStateWithLifecycle()
                    LaunchedEffect(Unit) { if (info == null) vm.readVehicleInfo() }
                    VehicleContent(info, connected, busy, vm::readVehicleInfo)
                }
            }
        }
    }
}

/** Каркас без ViewModel — его же используют скриншот-тесты. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    title: String,
    connected: ConnState.Connected?,
    tab: Tab,
    errorCount: Int,
    showTabs: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    onTab: (Tab) -> Unit,
    onLog: () -> Unit,
    onHistory: () -> Unit,
    onDisconnect: () -> Unit,
    versionName: String = "",
    autoUpdate: Boolean = true,
    onAutoUpdate: (Boolean) -> Unit = {},
    onCheckUpdate: () -> Unit = {},
    banner: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (connected != null && !showBack) ConnectionLine(connected)
                    }
                },
                navigationIcon = {
                    if (showBack) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (!showBack) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "Меню") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text("История отчётов") },
                                    leadingIcon = { Icon(AppIcons.History, null) },
                                    onClick = { menu = false; onHistory() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Журнал обмена") },
                                    leadingIcon = { Icon(AppIcons.Terminal, null) },
                                    onClick = { menu = false; onLog() },
                                )
                                if (connected != null) DropdownMenuItem(
                                    text = { Text("Отключиться") },
                                    leadingIcon = { Icon(Icons.Filled.Close, null) },
                                    onClick = { menu = false; onDisconnect() },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Проверить обновления") },
                                    leadingIcon = { Icon(Icons.Filled.Refresh, null) },
                                    onClick = { menu = false; onCheckUpdate() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Обновлять автоматически") },
                                    trailingIcon = { Checkbox(checked = autoUpdate, onCheckedChange = null) },
                                    onClick = { onAutoUpdate(!autoUpdate) },
                                )
                                Text(
                                    "Версия $versionName",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            if (showTabs) NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { onTab(t) },
                        icon = {
                            if (t == Tab.ERRORS && errorCount > 0) {
                                BadgedBox(badge = { Badge(containerColor = Status.colors.error.accent) { Text("$errorCount") } }) {
                                    Icon(t.icon, contentDescription = null)
                                }
                            } else Icon(t.icon, contentDescription = null)
                        },
                        label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding)) {
            banner()
            Box(Modifier.weight(1f)) { content() }
        }
    }
}

/** Строка состояния связи под заголовком: зелёная точка + адаптер · напряжение. */
@Composable
private fun ConnectionLine(c: ConnState.Connected) {
    val okTone = Status.colors.ok
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = okTone.accent, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
        Spacer(Modifier.width(6.dp))
        val volt = c.voltage?.let { " · " + "%.1f В".format(Ru, it) } ?: ""
        Text(
            c.deviceName + volt,
            style = MaterialTheme.typography.bodySmall.merge(Tabular),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
