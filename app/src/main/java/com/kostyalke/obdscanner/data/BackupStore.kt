package com.kostyalke.obdscanner.data

import android.content.Context
import com.kostyalke.obdscanner.vag.ModuleBackup
import com.kostyalke.obdscanner.vag.VagModule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Снимки данных блоков VAG (идентификация и сырые служебные записи). JSON во внутренней памяти;
 * ничего не удаляем автоматически. Старые файлы формата «backup-1» остаются в той же папке.
 * Снимок НЕ является проверенной резервной копией: кодирование по одному его наличию запрещено.
 */
class BackupStore(context: Context) {
    private val dir = File(context.filesDir, "backups").apply { mkdirs() }

    fun save(
        backups: List<ModuleBackup>,
        vin: String?,
        time: Long = System.currentTimeMillis(),
        appVersion: String = "",
        failed: List<Pair<VagModule, String>> = emptyList(),
    ): File {
        val base = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(time))
        var f = File(dir, "$base.json")
        var n = 2
        while (f.exists()) f = File(dir, "${base}_${n++}.json")
        f.writeText(toJson(backups, vin, time, appVersion, failed).toString(1))
        return f
    }

    fun list(): List<File> = (dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.name }

    companion object {
        fun hex(b: IntArray) = b.joinToString(" ") { "%02X".format(it) }

        private fun id(id: Int) = if (id > 0xFF) "%04X".format(id) else "%02X".format(id)

        fun toJson(
            backups: List<ModuleBackup>,
            vin: String?,
            time: Long,
            appVersion: String = "",
            failed: List<Pair<VagModule, String>> = emptyList(),
        ): JSONObject = JSONObject().apply {
            put("format", "obd-scanner-vag-snapshot-2")
            put("kind", "Снимок данных блоков")
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time)))
            put("appVersion", appVersion)
            put("vin", vin ?: JSONObject.NULL)
            put("vinMissing", vin == null)
            put("complete", failed.isEmpty() && backups.isNotEmpty() && backups.all { it.complete })
            // Восстановление по снимку не проверялось — не основание для кодирования.
            put("restoreVerified", false)
            put("modules", JSONArray().apply {
                for (b in backups) put(JSONObject().apply {
                    put("address", b.module.addressText)
                    put("title", b.module.title)
                    put("protocol", b.protocol)
                    b.tpAddress?.let { put("tpAddress", "%02X".format(it)) }
                    b.canIds?.let { (tx, rx) -> put("canIds", "%03X/%03X".format(tx, rx)) }
                    b.partNumber?.let { put("partNumber", it) }
                    put("complete", b.complete)
                    put("records", JSONObject().apply { for ((k, data) in b.records) put(id(k), hex(data)) })
                    put("missing", JSONArray().apply { b.missing.forEach { put(id(it)) } })
                })
            })
            put("notRead", JSONArray().apply {
                for ((m, why) in failed) put(JSONObject().apply {
                    put("address", m.addressText); put("title", m.title); put("reason", why)
                })
            })
        }
    }
}
