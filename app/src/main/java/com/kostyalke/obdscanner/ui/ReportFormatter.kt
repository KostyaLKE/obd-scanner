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
    fun format(r: DtcReport, v: VehicleInfo?, conn: ConnState.Connected?, db: DtcDatabase): String = buildString {
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
        if (r.notes.isNotEmpty()) {
            appendLine()
            r.notes.forEach { appendLine("Примечание: $it") }
        }
    }
}
