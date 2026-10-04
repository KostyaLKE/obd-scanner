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
                    Text(state.step)
                }
            }
            is ConnState.Failed -> Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(state.message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
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
