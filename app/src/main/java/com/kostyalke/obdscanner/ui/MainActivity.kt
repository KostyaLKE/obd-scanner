package com.kostyalke.obdscanner.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
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
}

private enum class Tab(val title: String, val icon: ImageVector) {
    ERRORS("Ошибки", Icons.Filled.Warning),
    LIVE("Датчики", Icons.Filled.PlayArrow),
    READY("Готовность", Icons.Filled.CheckCircle),
    CAR("Авто", Icons.Filled.Info),
    LOG("Журнал", Icons.AutoMirrored.Filled.List),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun App(vm: AppViewModel) {
    val conn by vm.conn.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.ERRORS) }
    var history by rememberSaveable { mutableStateOf(false) }
    val connected = conn as? ConnState.Connected

    BackHandler(enabled = history) { history = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            history -> "История отчётов"
                            connected != null -> connected.deviceName
                            else -> "OBD Сканер"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (history) IconButton(onClick = { history = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (connected != null && !history) TextButton(onClick = { vm.disconnect() }) { Text("Отключить") }
                },
            )
        },
        bottomBar = {
            if (connected != null && !history) NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.title, maxLines = 1) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when {
                history -> HistoryScreen(vm)
                connected == null -> ConnectScreen(vm, conn) { history = true }
                else -> when (tab) {
                    Tab.ERRORS -> ErrorsScreen(vm) { history = true }
                    Tab.LIVE -> LiveScreen(vm)
                    Tab.READY -> ReadinessScreen(vm)
                    Tab.CAR -> VehicleScreen(vm)
                    Tab.LOG -> LogScreen(vm)
                }
            }
        }
    }
}
