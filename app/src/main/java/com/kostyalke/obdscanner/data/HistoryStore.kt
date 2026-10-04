package com.kostyalke.obdscanner.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SavedReport(val file: File, val title: String, val text: String)

/** Сохранённые отчёты диагностики — обычные текстовые файлы во внутренней памяти. */
class HistoryStore(context: Context) {
    private val dir = File(context.filesDir, "reports").apply { mkdirs() }

    fun save(text: String, time: Long): SavedReport {
        val name = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(time)) + ".txt"
        val f = File(dir, name)
        f.writeText(text)
        // Храним последние 100 отчётов.
        (dir.listFiles { x -> x.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }.drop(100).forEach { it.delete() }
        return SavedReport(f, titleOf(f), text)
    }

    fun list(): List<SavedReport> =
        (dir.listFiles { f -> f.name.endsWith(".txt") } ?: emptyArray())
            .sortedByDescending { it.name }
            .map { SavedReport(it, titleOf(it), it.readText()) }

    fun delete(report: SavedReport) {
        report.file.delete()
    }

    private fun titleOf(f: File): String {
        val first = f.useLines { it.firstOrNull() } ?: f.name
        return first.removePrefix("Отчёт диагностики · ")
    }
}
