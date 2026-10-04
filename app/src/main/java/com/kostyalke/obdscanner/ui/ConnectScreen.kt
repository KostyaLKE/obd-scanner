package com.kostyalke.obdscanner.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

enum class BtAccess { OK, NO_PERMISSION, OFF }

/** Обёртка: разрешения и системные экраны Bluetooth. Вся отрисовка — в [ConnectContent]. */
@Composable
fun ConnectScreen(vm: AppViewModel, state: ConnState) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
    var granted by remember {
        mutableStateOf(perm == null || ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    var refresh by remember { mutableIntStateOf(0) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    LaunchedEffect(Unit) { if (!granted && perm != null) permLauncher.launch(perm) }

    val access = when {
        !granted -> BtAccess.NO_PERMISSION
        !vm.isBluetoothOn() -> BtAccess.OFF
        else -> BtAccess.OK
    }
    val devices = remember(refresh, access) { if (access == BtAccess.OK) vm.pairedDevices() else emptyList() }
    LaunchedEffect(devices) { if (access == BtAccess.OK) vm.autoConnectIfNeeded(devices) }
    var auto by remember { mutableStateOf(vm.autoConnect) }

    ConnectContent(
        state = state,
        access = access,
        devices = devices,
        lastDevice = vm.lastDevice,
        autoConnect = auto,
        onAutoConnect = { auto = it; vm.autoConnect = it },
        onConnect = vm::connect,
        onCancel = vm::cancelConnect,
        onDemo = vm::connectDemo,
        onFixAccess = {
            when (access) {
                BtAccess.NO_PERMISSION -> perm?.let { permLauncher.launch(it) }
                BtAccess.OFF -> enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                BtAccess.OK -> {}
            }
        },
        onBtSettings = {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            refresh++
        },
    )
}

@Composable
fun ConnectContent(
    state: ConnState,
    access: BtAccess,
    devices: List<PairedDevice>,
    lastDevice: String?,
    autoConnect: Boolean,
    onAutoConnect: (Boolean) -> Unit,
    onConnect: (String) -> Unit,
    onCancel: () -> Unit,
    onDemo: () -> Unit,
    onFixAccess: () -> Unit,
    onBtSettings: () -> Unit,
) {
    val connecting = state is ConnState.Connecting
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                is ConnState.Connecting -> item {
                    Panel {
                        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(state.step, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = onCancel) { Text("Отмена") }
                        }
                    }
                }
                is ConnState.Failed -> item { FailurePanel(state) }
                else -> {}
            }
            when (access) {
                BtAccess.NO_PERMISSION -> item {
                    StatusNote("Нужен доступ к Bluetooth", Status.colors.warning, Icons.Filled.Warning,
                        "Без него приложение не увидит адаптер. Разрешение даёт только подключение к уже сопряжённым устройствам.")
                }
                BtAccess.OFF -> item {
                    StatusNote("Bluetooth выключен", Status.colors.warning, AppIcons.Bluetooth)
                }
                BtAccess.OK -> {
                    item { SectionTitle("Адаптеры", trailing = if (devices.isEmpty()) null else "${devices.size}") }
                    item {
                        Panel {
                            if (devices.isEmpty()) {
                                Text(
                                    "Сопряжённых устройств нет. Вставьте адаптер в разъём OBD, включите зажигание и " +
                                        "выполните сопряжение в настройках Bluetooth телефона (PIN обычно 1234 или 0000).",
                                    Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            devices.forEachIndexed { i, d ->
                                if (i > 0) PanelDivider()
                                DeviceRow(d, d.address == lastDevice, enabled = !connecting) { onConnect(d.address) }
                            }
                        }
                    }
                    item {
                        Panel {
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("Подключаться автоматически", style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text("к последнему адаптеру при запуске", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Spacer(Modifier.width(12.dp))
                                Switch(checked = autoConnect, onCheckedChange = onAutoConnect)
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Разъём OBD обычно под панелью слева, под рулём. Зажигание должно быть включено, " +
                        "а адаптер не подключён к другому приложению.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
                )
            }
        }
        ActionBar {
            if (access != BtAccess.OK) {
                Button(onClick = onFixAccess, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Text(if (access == BtAccess.OFF) "Включить Bluetooth" else "Разрешить доступ")
                }
            } else {
                OutlinedButton(onClick = onBtSettings, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                    Icon(Icons.Filled.Settings, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сопряжение")
                }
            }
            OutlinedButton(onClick = onDemo, enabled = !connecting, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text("Демо-режим")
            }
        }
    }
}

@Composable
private fun DeviceRow(d: PairedDevice, last: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.size(40.dp)) {
            Icon(AppIcons.Bluetooth, null, Modifier.padding(9.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                if (last) {
                    Spacer(Modifier.width(8.dp))
                    StatusPill("последний", Status.colors.neutral)
                }
            }
            Text(d.address, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text("Подключить", style = MaterialTheme.typography.labelLarge,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun FailurePanel(state: ConnState.Failed) {
    var showTried by remember { mutableStateOf(false) }
    var showAllTips by remember { mutableStateOf(false) }
    val (title, tips) = failureAdvice(state)
    val tone = Status.colors.error
    Surface(Modifier.fillMaxWidth(), shape = PanelShape, color = tone.container) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Error, null, tint = tone.accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = tone.onContainer)
            }
            // Для «машина молчит» заголовок уже всё сказал — не повторяем.
            if (state.stage != FailStage.CAR) {
                Spacer(Modifier.size(4.dp))
                Text(state.message, style = MaterialTheme.typography.bodySmall, color = tone.onContainer)
            }
            if (tips.isNotEmpty()) {
                Spacer(Modifier.size(12.dp))
                Text("Что проверить", style = MaterialTheme.typography.labelLarge, color = tone.onContainer)
                (if (showAllTips) tips else tips.take(2)).forEachIndexed { i, t ->
                    Row(Modifier.padding(top = 6.dp)) {
                        Text("${i + 1}.", style = MaterialTheme.typography.bodyMedium.merge(Tabular), color = tone.accent,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.width(22.dp))
                        Text(t, style = MaterialTheme.typography.bodyMedium, color = tone.onContainer)
                    }
                }
            }
            if (tips.size > 2) {
                TextButton(onClick = { showAllTips = !showAllTips }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(if (showAllTips) "Свернуть советы" else "Ещё ${tips.size - 2} ${plural(tips.size - 2, "совет", "совета", "советов")}",
                        color = tone.onContainer)
                }
            }
            if (state.tried.isNotEmpty() && (showAllTips || tips.size <= 2)) {
                TextButton(onClick = { showTried = !showTried }) {
                    Text(if (showTried) "Скрыть протоколы" else "Какие протоколы пробовали · ${state.tried.size}",
                        color = tone.onContainer)
                }
                if (showTried) state.tried.forEach {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = tone.onContainer,
                        modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                }
            }
        }
    }
}

private fun failureAdvice(state: ConnState.Failed): Pair<String, List<String>> = when (state.stage) {
    FailStage.BLUETOOTH -> "Телефон не связался с адаптером" to listOf(
        "Адаптер вставлен в разъём OBD и светится?",
        "Закройте другие OBD-приложения: адаптер работает только с одним.",
        "Удалите сопряжение и выполните его заново (PIN 1234, 0000 или 6789).",
        "Выключите и включите Bluetooth на телефоне.",
        "Адаптеры Bluetooth LE (без PIN, не видны в списке) пока не поддерживаются.",
    )
    FailStage.ADAPTER -> "Адаптер отвечает неправильно" to listOf(
        "Выньте адаптер на 5 секунд и вставьте обратно.",
        "Похоже на неисправный или урезанный клон ELM327 — попробуйте другой адаптер.",
        "Отправьте журнал (меню → Журнал обмена → Поделиться).",
    )
    FailStage.CAR -> "Адаптер работает, машина молчит" to buildList {
        add("Зажигание включено (ключ в положении II, горит приборная панель)?")
        state.voltage?.let { v ->
            add("Напряжение на разъёме %.1f В — ".format(Ru, v) +
                if (v < 11.0) "мало, проверьте аккумулятор." else "в норме, адаптер и разъём живы.")
        }
        add("Старые VAG (Fabia до ~2007) работают по K-line, которую многие клоны ELM327 не тянут. Надёжнее кабель VAG-COM KKL 409.1.")
        add("Дизели до ~2004 года могут вообще не поддерживать стандартный OBD-II.")
        add("Попробуйте ещё раз: первая инициализация K-line иногда не проходит.")
    }
    FailStage.LOST -> "Связь оборвалась" to listOf(
        "Адаптер выпал из разъёма или выключилось зажигание?",
        "Отключите для приложения режим энергосбережения.",
        "Подключитесь заново — протокол запомнен, будет быстрее.",
    )
    null -> "Не удалось подключиться" to emptyList()
}
