package com.kostyalke.obdscanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kostyalke.obdscanner.data.SavedReport
import com.kostyalke.obdscanner.elm.LogLine
import com.kostyalke.obdscanner.obd.MonitorState
import com.kostyalke.obdscanner.obd.PidDef
import com.kostyalke.obdscanner.obd.Pids
import com.kostyalke.obdscanner.obd.Readiness
import com.kostyalke.obdscanner.obd.VehicleInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------- Готовность ----------------

@Composable
fun ReadinessContent(rd: Readiness?, since: List<Pair<PidDef, Double>>, busy: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (rd == null) {
                item {
                    StatusNote(if (busy) "Читаю…" else "Нет данных", Status.colors.neutral, Icons.Filled.CheckCircle,
                        "Самопроверки — это тесты, которые блок двигателя проводит сам во время поездок.")
                }
            } else {
                val ready = rd.monitors.count { it.state == MonitorState.COMPLETE }
                item {
                    Panel {
                        Column(Modifier.padding(16.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (rd.milOn) StatusPill("Check Engine горит", Status.colors.warning, Icons.Filled.Warning)
                                else StatusPill("Check Engine не горит", Status.colors.ok, Icons.Filled.CheckCircle)
                                StatusPill(if (rd.diesel) "дизель" else "бензин", Status.colors.neutral)
                            }
                            Spacer(Modifier.size(12.dp))
                            Text("Готово $ready из ${rd.monitors.size}", style = MaterialTheme.typography.headlineSmall.merge(Tabular),
                                fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(Modifier.size(4.dp))
                            Text(
                                "«Не готов» значит, что после стирания ошибок или отключения АКБ проверка ещё не " +
                                    "завершилась. Много неготовых у подержанной машины — признак, что ошибки недавно стирали.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { SectionTitle("Самопроверки") }
                item {
                    Panel {
                        rd.monitors.forEachIndexed { i, m ->
                            if (i > 0) PanelDivider()
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(m.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface)
                                Spacer(Modifier.width(12.dp))
                                if (m.state == MonitorState.COMPLETE) StatusPill("готов", Status.colors.ok, Icons.Filled.CheckCircle)
                                else StatusPill("не готов", Status.colors.warning, Icons.Filled.Warning)
                            }
                        }
                    }
                }
            }
            item { SectionTitle("После стирания ошибок") }
            item {
                Panel {
                    if (since.isEmpty()) {
                        Text("Машина не сообщает эти данные.", Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    since.forEachIndexed { i, (def, v) ->
                        if (i > 0) PanelDivider()
                        InfoRow(def.name.removeSuffix(" после стирания ошибок").replaceFirstChar { it.uppercase() },
                            "${Pids.format(def, v)} ${def.unit}".trim())
                    }
                }
            }
        }
        ActionBar {
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Обновить") }
        }
    }
}

// ---------------- Авто ----------------

@Composable
fun VehicleContent(info: VehicleInfo?, conn: ConnState.Connected?, busy: Boolean, onRefresh: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { SectionTitle("Машина") }
            item {
                SelectionContainer {
                    Panel {
                        InfoRow("VIN", info?.vin ?: if (info != null) "не отдаёт" else null)
                        PanelDivider()
                        InfoRow("Стандарт OBD", info?.obdStandard)
                        PanelDivider()
                        InfoRow("Блок управления", info?.ecuName)
                        PanelDivider()
                        InfoRow("Калибровка ПО", info?.calibrationIds?.joinToString(", ")?.ifEmpty { null })
                    }
                }
            }
            if (info != null && info.vin == null) {
                item {
                    Text("VIN по OBD-II обязателен только с ~2005 года, на более старых машинах его может не быть.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            item { SectionTitle("Подключение") }
            item {
                Panel {
                    InfoRow("Адаптер", conn?.deviceName)
                    PanelDivider()
                    InfoRow("Версия", conn?.elmVersion)
                    PanelDivider()
                    InfoRow("Протокол", conn?.protocol)
                    PanelDivider()
                    InfoRow("Напряжение", (info?.voltage?.let { com.kostyalke.obdscanner.elm.Elm327.parseVoltage(it) } ?: conn?.voltage)
                        ?.let { "%.1f В".format(Ru, it) })
                }
            }
        }
        ActionBar {
            Button(onClick = onRefresh, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Обновить") }
        }
    }
}

// ---------------- Журнал ----------------

@Composable
fun LogContent(log: List<LogLine>, busy: Boolean, onSend: (String) -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val fmt = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.size - 1) }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Если что-то не работает — отправьте журнал разработчику.", Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onShare) { Icon(Icons.Filled.Share, "Поделиться журналом", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onClear) { Icon(Icons.Filled.Delete, "Очистить журнал", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Panel(Modifier.weight(1f).padding(horizontal = 16.dp)) {
            LazyColumn(Modifier.fillMaxSize().padding(12.dp), state = listState) {
                if (log.isEmpty()) item {
                    Text("Пока пусто", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(log) { l ->
                    Row(Modifier.padding(vertical = 1.dp)) {
                        Text(fmt.format(Date(l.time)), fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.outline)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            (if (l.outgoing) "› " else "  ") + l.text,
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp,
                            color = if (l.outgoing) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (l.outgoing) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
        ActionBar {
            OutlinedTextField(
                value = cmd,
                onValueChange = { cmd = it },
                placeholder = { Text("Команда: 0105, ATRV…") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (cmd.isNotBlank() && !busy) { onSend(cmd); cmd = "" } }),
            )
            FilledIconButton(onClick = { onSend(cmd); cmd = "" }, enabled = cmd.isNotBlank() && !busy,
                modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Send, contentDescription = "Отправить")
            }
        }
    }
}

// ---------------- История ----------------

@Composable
fun HistoryContent(reports: List<SavedReport>, onShare: (SavedReport) -> Unit, onDelete: (SavedReport) -> Unit) {
    var open by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ScreenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reports.isEmpty()) item {
            StatusNote("Отчётов пока нет", Status.colors.neutral, AppIcons.History,
                "Каждое чтение ошибок сохраняется сюда автоматически.")
        }
        items(reports, key = { it.file.name }) { r ->
            val codes = remember(r.text) {
                Regex("\\b[PCBU][0-9A-F]{4}\\b").findAll(r.text).map { it.value }.distinct().toList()
            }
            Panel {
                Column(Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(6.dp))
                    if (codes.isEmpty()) StatusPill("ошибок не было", Status.colors.ok, Icons.Filled.CheckCircle)
                    else Text(codes.joinToString("  "), style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace, color = Status.colors.error.accent, fontWeight = FontWeight.SemiBold)
                    if (open == r.file.name) {
                        Spacer(Modifier.size(10.dp))
                        SelectionContainer {
                            Text(r.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.padding(horizontal = 4.dp)) {
                    TextButton(onClick = { open = if (open == r.file.name) null else r.file.name }) {
                        Text(if (open == r.file.name) "Свернуть" else "Открыть")
                    }
                    TextButton(onClick = { onShare(r) }) { Text("Поделиться") }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { onDelete(r) }) {
                        Icon(Icons.Filled.Delete, "Удалить отчёт", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
