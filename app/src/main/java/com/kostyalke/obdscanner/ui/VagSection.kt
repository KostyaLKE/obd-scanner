package com.kostyalke.obdscanner.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.kostyalke.obdscanner.vag.VagModule
import com.kostyalke.obdscanner.vag.VagModuleResult
import com.kostyalke.obdscanner.vag.VagModules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Раздел «Все блоки»: опрос ABS, подушек, приборки и т.д. по протоколу VAG.
 * Кнопка опроса живёт внутри раздела — она относится только к нему.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VagSection(
    st: VagState,
    supported: Boolean,
    dtcText: (com.kostyalke.obdscanner.vag.VagDtc) -> String,
    onScan: () -> Unit,
    onClear: (VagModule) -> Unit,
    onBackup: () -> Unit = {},
    onShareBackup: (String) -> Unit = {},
    onShareLog: () -> Unit = {},
) {
    var confirm by remember { mutableStateOf<VagModule?>(null) }
    Column {
        SectionTitle("Все блоки автомобиля", trailing = st.time?.let { SimpleDateFormat("HH:mm", Locale.US).format(Date(it)) })
        if (!supported) {
            StatusNote("Только для VAG с шиной CAN", Status.colors.neutral, Icons.Filled.Info,
                "Опрос ABS, подушек и приборки работает на Skoda/VW/Audi/Seat примерно с 2008 года.", compact = true)
            return
        }
        Panel {
            when {
                st.scanning -> Column(Modifier.padding(16.dp)) {
                    Text("Опрашиваю: ${st.current ?: "…"}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(progress = { st.progress }, Modifier.fillMaxWidth())
                }
                st.results.isEmpty() -> Column(Modifier.padding(16.dp)) {
                    Text(
                        "Проверка ошибок в ABS, подушках безопасности, приборной панели, электронике и других " +
                            "блоках. Занимает около 30 секунд, зажигание должно быть включено.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val responded = st.results.filter { it.responded }
            responded.forEachIndexed { i, r ->
                if (i > 0 || st.scanning) PanelDivider()
                VagRow(r, dtcText, clearing = st.clearing == r.module.address, onClear = { confirm = r.module })
            }
            val silent = st.results.filter { !it.responded }
            if (silent.isNotEmpty() && !st.scanning) {
                PanelDivider()
                Text(
                    "Не ответили (нет в машине или спят): " + silent.joinToString(", ") { it.module.title.lowercase() },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (st.backingUp) {
                Column(Modifier.padding(16.dp)) {
                    Text("Сохраняю копию: ${st.current ?: "…"}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                    Spacer(Modifier.size(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            } else if (!st.scanning) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 14.dp,
                        top = if (st.results.isEmpty()) 0.dp else 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(onClick = onScan, contentPadding = ButtonPadding,
                        modifier = Modifier.heightIn(min = 44.dp)) {
                        Text(if (st.results.isEmpty()) "Проверить все блоки" else "Проверить снова")
                    }
                    if (st.results.any { it.responded }) {
                        OutlinedButton(onClick = onBackup, contentPadding = ButtonPadding,
                            modifier = Modifier.heightIn(min = 44.dp)) {
                            Text("Сохранить копию")
                        }
                    }
                }
            }
        }
        st.error?.let {
            Spacer(Modifier.size(8.dp))
            StatusNote(it, Status.colors.error, AppIcons.Error, compact = true)
            TextButton(onClick = onShareLog) { Text("Отправить журнал") }
        }
        st.backupInfo?.let { info ->
            Spacer(Modifier.size(8.dp))
            StatusNote(info, Status.colors.ok, Icons.Filled.CheckCircle,
                "Отправьте файл разработчику — по нему настроим кодирование под вашу машину.", compact = true)
            st.backupJson?.let { json ->
                TextButton(onClick = { onShareBackup(json) }) { Text("Поделиться копией") }
            }
        }
        if (st.results.any { it.responded } && st.backupInfo == null && !st.scanning) {
            Text(
                "«Сохранить копию» читает служебные данные блоков (кодировку) — только чтение, ничего не меняет. " +
                    "Копия нужна, чтобы позже безопасно включать функции и откатывать изменения.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }

    confirm?.let { m ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Сбросить ошибки блока?") },
            text = {
                Text("${m.title} (${m.addressText}). Если неисправность не устранена, ошибка вернётся. " +
                    "Ошибки подушек безопасности с записью об аварии (crash data) так не сбрасываются.")
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; onClear(m) }) { Text("Сбросить", color = Status.colors.error.accent) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun VagRow(r: VagModuleResult, dtcText: (com.kostyalke.obdscanner.vag.VagDtc) -> String, clearing: Boolean, onClear: () -> Unit) {
    var expanded by rememberSaveable(r.module.address) { mutableStateOf(r.dtcs.isNotEmpty()) }
    val tone = when {
        r.error != null -> Status.colors.warning
        r.dtcs.isNotEmpty() -> Status.colors.error
        else -> Status.colors.ok
    }
    Column(
        Modifier.fillMaxWidth().animateContentSize().clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.module.addressText, style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(28.dp))
            Text(r.module.title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            when {
                r.error != null -> StatusPill("нет связи", tone, AppIcons.Error)
                r.dtcs.isEmpty() -> StatusPill("ок", tone, Icons.Filled.CheckCircle)
                else -> StatusPill("${r.dtcs.size} ${plural(r.dtcs.size, "ошибка", "ошибки", "ошибок")}", tone, AppIcons.Error)
            }
        }
        if (expanded) {
            Column(Modifier.padding(start = 28.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOfNotNull(r.partNumber) + r.protocol).joinToString(" · ").let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                r.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (r.dtcs.isNotEmpty()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            r.dtcs.forEach { d ->
                                val text = dtcText(d)
                                val code = text.substringBefore(" — ")
                                Column {
                                    Text(code, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold, color = Status.colors.error.accent)
                                    Text(text.substringAfter(" — "), style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface)
                                    Text("статус %02X".format(d.status), style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    TextButton(onClick = onClear, enabled = !clearing, modifier = Modifier.padding(start = 0.dp)) {
                        Text(if (clearing) "Сбрасываю…" else "Сбросить ошибки блока", color = Status.colors.error.accent)
                    }
                }
            }
        }
    }
}
