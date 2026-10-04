package com.kostyalke.obdscanner.ui

import android.Manifest
import android.content.Intent
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext

@Composable
fun ConnectScreen(vm: AppViewModel, state: ConnState, onOpenHistory: () -> Unit) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_CONNECT else null
    var granted by remember {
        mutableStateOf(perm == null || ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED)
    }
    var refresh by remember { mutableIntStateOf(0) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted && perm != null) launcher.launch(perm) }
    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }

    val connecting = state is ConnState.Connecting

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("OBD Сканер", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Адаптер ELM327 · Bluetooth",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        when (state) {
            is ConnState.Connecting -> Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(state.step, Modifier.weight(1f))
                    TextButton(onClick = { vm.cancelConnect() }) { Text("Отмена") }
                }
            }
            is ConnState.Failed -> FailureCard(state)
            else -> {}
        }
        Spacer(Modifier.height(12.dp))

        if (!granted) {
            Text("Нужно разрешение на доступ к Bluetooth-устройствам, иначе приложение не увидит адаптер.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { perm?.let { launcher.launch(it) } }) { Text("Разрешить") }
        } else if (!vm.isBluetoothOn() && refresh >= 0) {
            Text("Bluetooth выключен.")
            Spacer(Modifier.height(8.dp))
            Button(onClick = { enableBt.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)) }) {
                Text("Включить Bluetooth")
            }
        } else {
            val devices = remember(refresh, granted) { vm.pairedDevices() }
            LaunchedEffect(devices) { vm.autoConnectIfNeeded(devices) }
            Text("Сопряжённые устройства", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            if (devices.isEmpty()) {
                Text(
                    "Нет сопряжённых устройств. Вставьте адаптер в разъём OBD, включите зажигание и выполните " +
                        "сопряжение в настройках Bluetooth телефона (PIN обычно 1234 или 0000).",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(devices, key = { it.address }) { d ->
                    val last = d.address == vm.lastDevice
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clickable(enabled = !connecting) { vm.connect(d.address) },
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Text(d.name + if (last) "  · последний" else "", fontWeight = FontWeight.SemiBold)
                            Text(d.address, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            var auto by remember { mutableStateOf(vm.autoConnect) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = auto, onCheckedChange = { auto = it; vm.autoConnect = it })
                Text("Подключаться к последнему адаптеру при запуске", style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { refresh++ }) { Text("Обновить список") }
                TextButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                }) { Text("Настройки Bluetooth") }
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.connectDemo() }, enabled = !connecting) { Text("Демо-режим") }
            OutlinedButton(onClick = onOpenHistory) { Text("История отчётов") }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Подсказка: разъём OBD обычно под панелью слева, под рулём (бывает за крышкой). Зажигание должно быть включено. " +
                "Адаптер не должен быть подключён к другому приложению.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FailureCard(state: ConnState.Failed) {
    var showTried by remember { mutableStateOf(false) }
    val (title, tips) = when (state.stage) {
        FailStage.BLUETOOTH -> "Телефон не смог подключиться к адаптеру по Bluetooth" to listOf(
            "Адаптер вставлен в разъём OBD и светится индикатор?",
            "Закройте другие OBD-приложения (Car Scanner, Torque): адаптер может работать только с одним.",
            "Удалите сопряжение в настройках Bluetooth и выполните его заново (PIN 1234, 0000 или 6789).",
            "Выключите и снова включите Bluetooth на телефоне.",
            "Адаптеры Bluetooth LE (сопряжение без PIN, нет в списке устройств) пока не поддерживаются.",
        )
        FailStage.ADAPTER -> "Адаптер подключился, но отвечает неправильно" to listOf(
            "Выньте адаптер из разъёма на 5 секунд и вставьте обратно.",
            "Похоже на неисправный или сильно урезанный клон ELM327: попробуйте другой адаптер.",
            "Отправьте журнал (вкладка «Журнал» → «Поделиться»): по нему видно, что ответил адаптер.",
        )
        FailStage.CAR -> "Адаптер работает, но машина не отвечает" to buildList {
            add("Зажигание включено (ключ в положении II, горит приборная панель)?")
            state.voltage?.let { v ->
                add("Напряжение на разъёме: %.1f В. ".format(v) +
                    if (v < 11.0) "Это мало: проверьте аккумулятор." else "Питание в норме, значит адаптер и разъём живы.")
            }
            add("На старых VAG (Fabia до ~2007) диагностика идёт по K-line. Многие клоны ELM327 работают " +
                "с ней плохо. Надёжнее кабель VAG-COM KKL 409.1.")
            add("У дизелей до ~2004 года блок может вообще не поддерживать стандартный OBD-II/EOBD.")
            add("Попробуйте ещё раз: первая инициализация K-line иногда не проходит.")
        }
        FailStage.LOST -> "Связь оборвалась и не восстановилась" to listOf(
            "Адаптер выпал из разъёма или выключилось зажигание?",
            "Отключите для приложения режим энергосбережения в настройках телефона.",
            "Подключитесь заново: приложение помнит протокол, и повторное подключение быстрее.",
        )
        null -> "Не удалось подключиться" to emptyList()
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (tips.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Что проверить:", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                tips.forEachIndexed { i, t ->
                    Text("${i + 1}. $t", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (state.tried.isNotEmpty()) {
                TextButton(onClick = { showTried = !showTried }) {
                    Text(if (showTried) "Скрыть протоколы" else "Какие протоколы пробовали (${state.tried.size})")
                }
                if (showTried) state.tried.forEach {
                    Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}
