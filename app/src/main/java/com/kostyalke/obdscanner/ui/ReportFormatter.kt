package com.kostyalke.obdscanner.ui

import com.kostyalke.obdscanner.obd.Dtc
import com.kostyalke.obdscanner.obd.DtcDatabase
import com.kostyalke.obdscanner.obd.DtcReport
import com.kostyalke.obdscanner.obd.MonitorState
import com.kostyalke.obdscanner.obd.Pids
import com.kostyalke.obdscanner.obd.VehicleInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Текстовый отчёт — для «Поделиться» и для истории. */
object ReportFormatter {
    fun format(
        r: DtcReport,
        v: VehicleInfo?,
        conn: ConnState.Connected?,
        db: DtcDatabase,
        vag: List<com.kostyalke.obdscanner.vag.VagModuleResult> = emptyList(),
    ): String = buildString {
        val date = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale("ru")).format(Date(r.time))
        appendLine("Отчёт диагностики · $date")
        conn?.let { appendLine("Адаптер: ${it.deviceName} (${it.elmVersion}), протокол: ${it.protocol}") }
        v?.vin?.let { appendLine("VIN: $it") }
        r.readiness?.let {
            appendLine("Check Engine: ${if (it.milOn) "ГОРИТ" else "не горит"}; ошибок по данным блока: ${it.dtcCount}")
        }
        appendLine()
        fun section(title: String, list: List<Dtc>) {
            appendLine("$title: ${if (list.isEmpty()) "нет" else list.size.toString()}")
            for (d in list) {
                val info = db.info(d.code)
                val vag = info.vagCode?.let { " (VAG $it)" } ?: ""
                appendLine("  ${d.code}$vag — ${info.description ?: "нет расшифровки"} [${d.ecu}]")
                info.hint?.let { appendLine("    Возможные причины: $it") }
            }
        }
        section("Сохранённые ошибки", r.stored)
        section("Ожидающие ошибки", r.pending)
        if (r.permanent.isNotEmpty()) section("Постоянные ошибки", r.permanent)
        r.freezeFrame?.let { ff ->
            appendLine()
            appendLine("Стоп-кадр${ff.dtc?.let { " (ошибка $it)" } ?: ""}:")
            for ((def, value) in ff.values) appendLine("  ${def.name}: ${Pids.format(def, value)} ${def.unit}")
        }
        r.readiness?.let { rd ->
            appendLine()
            appendLine("Готовность систем самодиагностики (${if (rd.diesel) "дизель" else "бензин"}):")
            for (m in rd.monitors) {
                appendLine("  ${m.name}: ${if (m.state == MonitorState.COMPLETE) "готов" else "НЕ готов"}")
            }
        }
        if (vag.isNotEmpty()) {
            appendLine()
            appendLine("Все блоки (VAG):")
            for (m in vag.filter { it.responded }) {
                val head = "  ${m.module.addressText} ${m.module.title}" + (m.partNumber?.let { " ($it)" } ?: "")
                appendLine(head + ": " + when {
                    m.error != null -> "ошибка связи — ${m.error}"
                    m.dtcs.isEmpty() -> "ошибок нет"
                    else -> "ошибок ${m.dtcs.size}"
                })
                for (d in m.dtcs) appendLine("    " + vagDtcText(d.code, db))
            }
            val silent = vag.filter { !it.responded }.joinToString(", ") { it.module.addressText }
            if (silent.isNotEmpty()) appendLine("  Не ответили: $silent")
        }
        if (r.notes.isNotEmpty()) {
            appendLine()
            r.notes.forEach { appendLine("Примечание: $it") }
        }
    }
}

/** «00283 — Датчик скорости…» или для двигателя «16785 (P0401) — Рециркуляция ОГ…». */
fun vagDtcText(code: Int, db: DtcDatabase): String {
    val vag = com.kostyalke.obdscanner.vag.VagModules
    val obd = vag.toObdCode(code)
    val desc = vag.describe(code) ?: obd?.let { db.info(it).description }
    return vag.format(code) + (obd?.let { " ($it)" } ?: "") + " — " + (desc ?: "расшифровки нет, ищите по номеру")
}
