package com.kostyalke.obdscanner.ui

import android.content.Intent
import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kostyalke.obdscanner.obd.Dtc
import com.kostyalke.obdscanner.obd.DtcKind
import com.kostyalke.obdscanner.obd.FreezeFrame
import com.kostyalke.obdscanner.obd.Pids
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ErrorsScreen(vm: AppViewModel, onOpenHistory: () -> Unit) {
    val st by vm.dtc.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val report = st.report

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { vm.readDtcs() }, enabled = !st.loading && !st.clearing) {
                    Text(if (report == null) "Прочитать ошибки" else "Прочитать снова")
                }
                OutlinedButton(
                    onClick = { confirmClear = true },
                    enabled = !st.loading && !st.clearing && report != null,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) { Text("Стереть") }
                if (st.loading || st.clearing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            }
        }
        st.error?.let { item { Banner(it, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) } }
        st.message?.let { item { Banner(it, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) } }

        if (report != null) {
            item { SummaryCard(report.readiness?.milOn, report.all.size, report.time) }
            if (report.all.isEmpty()) {
                item {
                    Banner("Ошибок нет 👍", Ok.copy(alpha = 0.15f), MaterialTheme.colorScheme.onSurface)
                }
            }
            for (kind in DtcKind.entries) {
                val list = report.all.filter { it.kind == kind }
                if (list.isEmpty()) continue
                item {
                    Text(
                        "${kind.title} · ${list.size}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                items(list, key = { "${it.kind}-${it.ecu}-${it.code}" }) { d ->
                    DtcCard(vm, d, report.freezeFrame?.takeIf { ff -> ff.dtc == d.code && d.kind == DtcKind.STORED })
                }
            }
            report.freezeFrame?.let { ff ->
                if (report.all.none { it.code == ff.dtc }) item { FreezeFrameCard(ff) }
            }
            if (report.notes.isNotEmpty()) {
                item {
                    Text(
                        report.notes.joinToString("\n") { "• $it" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val text = vm.buildReportText() ?: return@OutlinedButton
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Отправить отчёт"))
                    }) { Text("Поделиться") }
                    OutlinedButton(onClick = { vm.saveReport() }) { Text("Сохранить") }
                    TextButton(onClick = onOpenHistory) { Text("История") }
                }
            }
        } else if (!st.loading) {
            item {
                Text(
                    "Нажмите «Прочитать ошибки». Чтение на K-line (старые Skoda/VW) может занять 10–20 секунд.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Стереть ошибки?") },
            text = {
                Text(
                    "• Заглушите двигатель, зажигание оставьте включённым.\n" +
                        "• Вместе с ошибками сотрутся стоп-кадры и статусы готовности систем — " +
                        "машина «забудет», что проверки пройдены.\n" +
                        "• Если неисправность не устранена, ошибка вернётся.\n\n" +
                        "Совет: сначала нажмите «Сохранить», чтобы отчёт остался в истории."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; vm.clearDtcs() }) { Text("Стереть", color = Danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
fun Banner(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(14.dp), color = fg)
    }
}

@Composable
private fun SummaryCard(milOn: Boolean?, count: Int, time: Long) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = when {
                milOn == true -> Warning
                count > 0 -> Warning.copy(alpha = 0.6f)
                else -> Ok
            }
            Surface(color = color, shape = RoundedCornerShape(50), modifier = Modifier.size(14.dp)) {}
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    when (milOn) {
                        true -> "Check Engine горит"
                        false -> "Check Engine не горит"
                        null -> "Состояние Check Engine неизвестно"
                    },
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Найдено кодов: $count · " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(time)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DtcCard(vm: AppViewModel, d: Dtc, ff: FreezeFrame?) {
    val info = remember(d.code) { vm.db.info(d.code) }
    val ctx = LocalContext.current
    var expanded by rememberSaveable(d.code, d.kind) { mutableStateOf(false) }
    val accent = when (d.kind) {
        DtcKind.STORED -> Danger
        DtcKind.PENDING -> Warning
        DtcKind.PERMANENT -> Danger
    }
    Card(
        Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    d.code,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                )
                info.vagCode?.let {
                    Spacer(Modifier.width(10.dp))
                    Text("VAG $it", fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                Text(d.ecu, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(info.description ?: "Расшифровки нет в базе", style = MaterialTheme.typography.bodyLarge)
            Text(info.system, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (expanded) {
                info.hint?.let {
                    Spacer(Modifier.height(8.dp))
                    Text("Возможные причины", fontWeight = FontWeight.SemiBold)
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                if (d.kind == DtcKind.PENDING) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ожидающая ошибка: блок заметил проблему в текущем или прошлом цикле поездки, но ещё не " +
                            "подтвердил её. Если повторится — станет сохранённой и может зажечь Check Engine.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                ff?.let {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    FreezeFrameBody(it)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    val q = info.vagCode?.let { v -> "VAG $v ${d.code} Skoda Fabia" } ?: "${d.code} Skoda Fabia"
                    val url = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                }) { Text("Искать в интернете") }
            } else if (info.hint != null || ff != null) {
                Text(
                    "Нажмите, чтобы увидеть возможные причины" + if (ff != null) " и стоп-кадр" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun FreezeFrameCard(ff: FreezeFrame) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) { FreezeFrameBody(ff) }
    }
}

@Composable
private fun FreezeFrameBody(ff: FreezeFrame) {
    Text("Стоп-кадр${ff.dtc?.let { " ($it)" } ?: ""}", fontWeight = FontWeight.SemiBold)
    Text(
        "Параметры двигателя в момент, когда ошибка была зафиксирована",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    for ((def, v) in ff.values) {
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(def.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${Pids.format(def, v)} ${def.unit}", fontWeight = FontWeight.SemiBold)
        }
    }
}
