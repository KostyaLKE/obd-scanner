package com.kostyalke.obdscanner.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kostyalke.obdscanner.obd.Dtc
import com.kostyalke.obdscanner.obd.DtcInfo
import com.kostyalke.obdscanner.obd.DtcKind
import com.kostyalke.obdscanner.obd.DtcReport
import com.kostyalke.obdscanner.obd.FreezeFrame
import com.kostyalke.obdscanner.obd.Pids
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ErrorsContent(
    st: DtcState,
    warnings: List<String>,
    info: (String) -> DtcInfo,
    onRead: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onSearch: (String) -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val report = st.report
    val busy = st.loading || st.clearing

    Column(Modifier.fillMaxSize()) {
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Spacer(Modifier.height(4.dp))
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = ScreenPadding,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { SummaryPanel(report, st.loading) }
            warnings.forEach { w ->
                item { StatusNote(w, Status.colors.warning, Icons.Filled.Warning, compact = true) }
            }
            st.error?.let { item { StatusNote(it, Status.colors.error, AppIcons.Error) } }
            st.message?.let { item { StatusNote(it, Status.colors.ok, Icons.Filled.CheckCircle) } }

            if (report != null) {
                for (kind in DtcKind.entries) {
                    val list = report.all.filter { it.kind == kind }
                    if (list.isEmpty()) continue
                    item(key = "title-$kind") { SectionTitle(kind.title, trailing = list.size.toString()) }
                    item(key = "list-$kind") {
                        Panel {
                            list.forEachIndexed { i, d ->
                                if (i > 0) PanelDivider()
                                DtcRow(d, info(d.code), report.freezeFrame?.takeIf { it.dtc == d.code && d.kind == DtcKind.STORED }, onSearch)
                            }
                        }
                    }
                }
                report.freezeFrame?.let { ff ->
                    if (report.all.none { it.code == ff.dtc }) {
                        item { SectionTitle("Стоп-кадр") }
                        item { Panel { Column(Modifier.padding(16.dp)) { FreezeFrameTable(ff) } } }
                    }
                }
                if (report.notes.isNotEmpty()) {
                    item {
                        Row(Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)) {
                            Icon(Icons.Filled.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(report.notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        ActionBar {
            Button(onClick = onRead, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                Text(if (report == null) "Прочитать ошибки" else "Прочитать снова")
            }
            OutlinedIconButton(onClick = onShare, enabled = report != null && !busy, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.Share, contentDescription = "Поделиться отчётом")
            }
            OutlinedIconButton(
                onClick = { confirmClear = true },
                enabled = report != null && !busy,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = Status.colors.error.accent),
            ) { Icon(Icons.Filled.Delete, contentDescription = "Стереть ошибки") }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            icon = { Icon(Icons.Filled.Delete, null, tint = Status.colors.error.accent) },
            title = { Text("Стереть ошибки?") },
            text = {
                Text(
                    "Заглушите двигатель, зажигание оставьте включённым.\n\n" +
                        "Вместе с ошибками сотрутся стоп-кадры и готовность самопроверок. " +
                        "Если неисправность не устранена, ошибка вернётся.\n\n" +
                        "Текущий отчёт уже сохранён в истории."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) {
                    Text("Стереть", color = Status.colors.error.accent)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun SummaryPanel(report: DtcReport?, loading: Boolean) {
    if (report == null) {
        val tone = Status.colors.neutral
        Surface(Modifier.fillMaxWidth(), shape = PanelShape, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.padding(20.dp)) {
                Text(if (loading) "Читаю ошибки…" else "Ошибки ещё не читались",
                    style = MaterialTheme.typography.titleLarge, color = tone.onContainer)
                Spacer(Modifier.height(4.dp))
                Text(
                    "На старых VAG с K-line чтение занимает 10–20 секунд.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    val confirmed = report.stored.size + report.permanent.size
    val pending = report.pending.size
    val mil = report.readiness?.milOn
    val tone = when {
        confirmed > 0 -> Status.colors.error
        pending > 0 || mil == true -> Status.colors.warning
        else -> Status.colors.ok
    }
    val title = when {
        confirmed > 0 -> "$confirmed ${plural(confirmed, "ошибка", "ошибки", "ошибок")}"
        pending > 0 -> "$pending ${plural(pending, "ожидающая", "ожидающие", "ожидающих")}"
        else -> "Ошибок нет"
    }
    val sub = buildList {
        if (confirmed > 0 && pending > 0) add("ещё $pending ${plural(pending, "ожидающая", "ожидающие", "ожидающих")}")
        when (mil) {
            true -> add("Check Engine горит")
            false -> add("Check Engine не горит")
            null -> {}
        }
        add("прочитано в " + SimpleDateFormat("HH:mm", Locale.US).format(Date(report.time)))
    }.joinToString(" · ")
    Surface(Modifier.fillMaxWidth(), shape = PanelShape, color = tone.container) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (tone == Status.colors.ok) Icons.Filled.CheckCircle else AppIcons.Error,
                contentDescription = null, tint = tone.accent, modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = tone.onContainer)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = tone.onContainer)
            }
        }
    }
}

@Composable
private fun DtcRow(d: Dtc, info: DtcInfo, ff: FreezeFrame?, onSearch: (String) -> Unit) {
    var expanded by rememberSaveable(d.code, d.kind) { mutableStateOf(false) }
    val tone = if (d.kind == DtcKind.PENDING) Status.colors.warning else Status.colors.error
    Column(
        Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.code, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold, color = tone.accent)
            info.vagCode?.let {
                Spacer(Modifier.width(10.dp))
                Text("VAG $it", style = MaterialTheme.typography.labelLarge.merge(Tabular),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) AppIcons.ExpandLess else AppIcons.ExpandMore,
                contentDescription = if (expanded) "Свернуть" else "Подробнее",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
        Text(info.description ?: "Расшифровки нет в базе", style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(end = 8.dp))
        Spacer(Modifier.height(2.dp))
        Text("${shortSystem(d.code, info.system)} · ${d.ecu}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    info.hint?.let {
                        Column {
                            Text("Возможные причины", style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    if (d.kind == DtcKind.PENDING) {
                        Text(
                            "Ожидающая: блок заметил проблему, но ещё не подтвердил. Повторится — станет " +
                                "сохранённой и может зажечь Check Engine.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ff?.let { FreezeFrameTable(it) }
                    if (info.hint == null && ff == null && d.kind != DtcKind.PENDING) {
                        Text("Подробностей по этому коду в базе нет.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TextButton(onClick = {
                onSearch(info.vagCode?.let { v -> "VAG $v ${d.code} Skoda" } ?: "${d.code} Skoda")
            }) {
                Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Искать в интернете")
            }
        }
    }
}

@Composable
private fun FreezeFrameTable(ff: FreezeFrame) {
    Column {
        Text(
            "Стоп-кадр${ff.dtc?.let { " · $it" } ?: ""}",
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Параметры в момент появления ошибки", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        for ((def, v) in ff.values) {
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(def.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Spacer(Modifier.width(12.dp))
                Text("${Pids.format(def, v)} ${def.unit}".trim(), style = MaterialTheme.typography.bodyMedium.merge(Tabular),
                    fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** «Двигатель/трансмиссия · стандартный код · топливо и воздух» → «Топливо и воздух» (+ «код VAG»). */
private fun shortSystem(code: String, system: String): String {
    val parts = system.split(" · ")
    val main = (parts.getOrNull(2) ?: parts.firstOrNull() ?: "").replaceFirstChar { it.uppercase() }
    return if (parts.getOrNull(1) == "код производителя") "$main · код VAG" else main
}

fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100
    val m10 = n % 10
    return when {
        m100 in 11..14 -> many
        m10 == 1 -> one
        m10 in 2..4 -> few
        else -> many
    }
}
