package com.kostyalke.obdscanner.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сведения об отчёте для списка и связей; хранятся рядом с текстом в `<id>.json`. */
data class ReportMeta(
    /** «до сброса», «после сброса», «блоки VAG» и т.п.; null — обычное чтение. */
    val kind: String? = null,
    val vin: String? = null,
    val errors: Int? = null,
    /** false — диагностика неполная; null — неизвестно (старые отчёты). */
    val complete: Boolean? = null,
    /** id связанного отчёта (для «после сброса» — отчёт «до»). */
    val linkedTo: String? = null,
    val note: String? = null,
    val demo: Boolean = false,
)

data class SavedReport(
    val file: File,
    val title: String,
    val text: String,
    /** Устойчивый идентификатор = имя файла без расширения. */
    val id: String = file.nameWithoutExtension,
    val meta: ReportMeta = ReportMeta(),
    /** Файл не читается — показываем отдельной строкой, остальные отчёты доступны. */
    val broken: Boolean = false,
)

/**
 * Сохранённые отчёты диагностики: текст в `<id>.txt`, сведения — в `<id>.json`.
 * Ничего не удаляет само: лишнее убирается только явным [prune] по настройке владельца.
 * Работает с обычной папкой — вызывать не из главного потока.
 */
class HistoryStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "reports"))

    init { dir.mkdirs() }

    /** Сохраняет отчёт. Бросает [IOException], если записать не удалось. */
    fun save(text: String, time: Long, meta: ReportMeta = ReportMeta()): SavedReport {
        val base = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(time))
        // Два сохранения за одну секунду (до/после сброса) не должны перезаписать друг друга.
        var id = base
        var n = 2
        while (File(dir, "$id.txt").exists()) id = "${base}_${n++}"
        val f = File(dir, "$id.txt")
        writeAtomic(f, text)
        writeAtomic(File(dir, "$id.json"), toJson(meta).toString(1))
        return SavedReport(f, titleOf(text, f.name), text, id, meta)
    }

    /** Сначала во временный файл, потом переименование: обрыв записи не оставит половину отчёта. */
    private fun writeAtomic(target: File, text: String) {
        val tmp = File(dir, target.name + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(target)) throw IOException("не удалось записать ${target.name}")
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /** Все отчёты, новые сверху. Повреждённый файл не мешает показать остальные. */
    fun list(): List<SavedReport> =
        (dir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { f ->
                try {
                    val text = f.readText()
                    SavedReport(f, titleOf(text, f.name), text, meta = readMeta(f.nameWithoutExtension))
                } catch (e: Exception) {
                    SavedReport(f, "Повреждённый отчёт ${f.nameWithoutExtension}",
                        "Этот отчёт не удалось прочитать: ${e.message ?: e.javaClass.simpleName}", broken = true)
                }
            }

    fun delete(report: SavedReport) {
        if (report.file.isDirectory) report.file.deleteRecursively() else report.file.delete()
        File(dir, report.id + ".json").delete()
    }

    fun setNote(report: SavedReport, note: String?): SavedReport {
        val meta = report.meta.copy(note = note?.trim()?.ifEmpty { null })
        writeAtomic(File(dir, report.id + ".json"), toJson(meta).toString(1))
        return report.copy(meta = meta)
    }

    /**
     * Явное ограничение истории по настройке владельца: оставить [keep] самых новых.
     * 0 — хранить всё. Возвращает число удалённых.
     */
    fun prune(keep: Int): Int {
        if (keep <= 0) return 0
        val extra = list().drop(keep)
        extra.forEach { delete(it) }
        return extra.size
    }

    private fun readMeta(id: String): ReportMeta = try {
        val f = File(dir, "$id.json")
        if (!f.exists()) ReportMeta() else JSONObject(f.readText()).let { o ->
            ReportMeta(
                kind = o.optString("kind").ifEmpty { null },
                vin = o.optString("vin").ifEmpty { null },
                errors = if (o.has("errors")) o.getInt("errors") else null,
                complete = if (o.has("complete")) o.getBoolean("complete") else null,
                linkedTo = o.optString("linkedTo").ifEmpty { null },
                note = o.optString("note").ifEmpty { null },
                demo = o.optBoolean("demo", false),
            )
        }
    } catch (e: Exception) {
        ReportMeta() // битые сведения не должны прятать сам отчёт
    }

    private fun toJson(m: ReportMeta) = JSONObject().apply {
        m.kind?.let { put("kind", it) }
        m.vin?.let { put("vin", it) }
        m.errors?.let { put("errors", it) }
        m.complete?.let { put("complete", it) }
        m.linkedTo?.let { put("linkedTo", it) }
        m.note?.let { put("note", it) }
        if (m.demo) put("demo", true)
    }

    private fun titleOf(text: String, fallback: String): String =
        (text.lineSequence().firstOrNull()?.takeIf { it.isNotBlank() } ?: fallback).removePrefix("Отчёт диагностики · ")
}
