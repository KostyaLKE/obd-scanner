package com.kostyalke.obdscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kostyalke.obdscanner.obd.MonitorState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ReadinessScreen(vm: AppViewModel) {
    val rd by vm.readiness.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (rd == null) vm.readReadiness() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.readReadiness() }, enabled = !busy) { Text("Обновить") }
                if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp))
            }
        }
        item {
            Text(
                "Мониторы — это самопроверки блока двигателя. «Не готов» значит, что после стирания ошибок или " +
                    "отключения АКБ проверка ещё не завершилась. Много неготовых мониторов у подержанной машины — " +
                    "признак того, что ошибки недавно стирали.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val r = rd
        if (r == null) {
            item { Text(if (busy) "Чтение…" else "Нет данных") }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            if (r.milOn) "Check Engine горит" else "Check Engine не горит",
                            fontWeight = FontWeight.SemiBold,
                            color = if (r.milOn) Warning else Ok,
                        )
                        Text("Подтверждённых ошибок по данным блока: ${r.dtcCount}")
                        Text("Тип двигателя: ${if (r.diesel) "дизель" else "бензин"}")
                    }
                }
            }
            items(r.monitors) { m ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(m.name, Modifier.weight(1f))
                        if (m.state == MonitorState.COMPLETE) Text("готов ✓", color = Ok, fontWeight = FontWeight.SemiBold)
                        else Text("не готов", color = Warning, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
            Text("Пробег и время после стирания ошибок", style = MaterialTheme.typography.titleMedium)
            SinceClearedRows(vm)
        }
    }
}

@Composable
private fun SinceClearedRows(vm: AppViewModel) {
    val values by vm.sinceCleared.collectAsStateWithLifecycle()
    if (values.isEmpty()) {
        Text("Машина не сообщает эти данные (или ещё не прочитаны).", style = MaterialTheme.typography.bodySmall)
    }
    for ((def, v) in values) {
        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text(def.name, Modifier.weight(1f))
            Text("${com.kostyalke.obdscanner.obd.Pids.format(def, v)} ${def.unit}", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun VehicleScreen(vm: AppViewModel) {
    val info by vm.vehicle.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val conn by vm.conn.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { if (info == null) vm.readVehicleInfo() }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { vm.readVehicleInfo() }, enabled = !busy) { Text("Обновить") }
            if (busy) CircularProgressIndicator(Modifier.padding(start = 12.dp))
        }
        Spacer(Modifier.height(12.dp))
        val c = conn as? ConnState.Connected
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                InfoRow("Адаптер", c?.let { "${it.deviceName} · ${it.elmVersion}" })
                InfoRow("Протокол", c?.protocol)
                InfoRow("Напряжение (на разъёме)", info?.voltage)
                InfoRow("Стандарт OBD", info?.obdStandard)
                InfoRow("VIN", info?.vin ?: if (info != null) "не поддерживается (на авто до ~2005 это нормально)" else null)
                InfoRow("Блок управления", info?.ecuName)
                InfoRow("Калибровка ПО", info?.calibrationIds?.joinToString(", ")?.ifEmpty { null })
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value ?: "—", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun LogScreen(vm: AppViewModel) {
    val log by vm.log.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }

    Column(Modifier.fillMaxSize().padding(12.dp).imePadding()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val text = log.joinToString("\n") { "${fmt.format(Date(it.time))} ${if (it.outgoing) ">>" else "<<"} ${it.text}" }
                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(android.content.Intent.EXTRA_TEXT, text)
                }
                ctx.startActivity(android.content.Intent.createChooser(send, "Отправить журнал"))
            }) { Text("Поделиться") }
            TextButton(onClick = { vm.clearLog() }) { Text("Очистить") }
        }
        Text(
            "Обмен с адаптером. Если что-то не работает — отправьте журнал разработчику.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(log) { l ->
                Text(
                    "${fmt.format(Date(l.time))} ${if (l.outgoing) ">>" else "<<"} ${l.text}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = if (l.outgoing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                label = { Text("Команда (напр. 0105, ATRV)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (cmd.isNotBlank()) { vm.sendRaw(cmd); cmd = "" } }),
            )
            Button(
                onClick = { vm.sendRaw(cmd); cmd = "" },
                enabled = cmd.isNotBlank() && !busy,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("Отпр.") }
        }
    }
}

@Composable
fun HistoryScreen(vm: AppViewModel) {
    val reports by vm.reports.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshReports() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reports.isEmpty()) item { Text("Сохранённых отчётов пока нет. На вкладке «Ошибки» нажмите «Сохранить».") }
        items(reports, key = { it.file.name }) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.title, fontWeight = FontWeight.SemiBold)
                    val codes = Regex("\\b[PCBU][0-9A-F]{4}\\b").findAll(r.text).map { it.value }.distinct().toList()
                    Text(
                        if (codes.isEmpty()) "Ошибок не было" else codes.joinToString(", "),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (open == r.file.name) {
                        Spacer(Modifier.height(8.dp))
                        SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodySmall) }
                    }
                    Row {
                        TextButton(onClick = { open = if (open == r.file.name) null else r.file.name }) {
                            Text(if (open == r.file.name) "Свернуть" else "Открыть")
                        }
                        TextButton(onClick = {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, r.text)
                            }
                            ctx.startActivity(android.content.Intent.createChooser(send, "Отправить отчёт"))
                        }) { Text("Поделиться") }
                        TextButton(onClick = { vm.deleteReport(r) }) { Text("Удалить", color = Danger) }
                    }
                }
            }
        }
    }
}
