package com.kostyalke.obdscanner.data

import android.content.Context
import com.kostyalke.obdscanner.vag.ModuleBackup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Резервные копии служебных данных блоков VAG (до любого кодирования).
 * JSON во внутренней памяти; ничего не удаляем автоматически.
 */
class BackupStore(context: Context) {
    private val dir = File(context.filesDir, "backups").apply { mkdirs() }

    fun save(backups: List<ModuleBackup>, vin: String?, time: Long = System.currentTimeMillis()): File {
        val f = File(dir, SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date(time)) + ".json")
        f.writeText(toJson(backups, vin, time).toString(1))
        return f
    }

    fun list(): List<File> = (dir.listFiles { x -> x.name.endsWith(".json") } ?: emptyArray()).sortedByDescending { it.name }

    companion object {
        fun hex(b: IntArray) = b.joinToString(" ") { "%02X".format(it) }

        fun toJson(backups: List<ModuleBackup>, vin: String?, time: Long): JSONObject = JSONObject().apply {
            put("format", "obd-scanner-vag-backup-1")
            put("time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time)))
            vin?.let { put("vin", it) }
            put("modules", JSONArray().apply {
                for (b in backups) put(JSONObject().apply {
                    put("address", b.module.addressText)
                    put("title", b.module.title)
                    put("records", JSONObject().apply {
                        for ((id, data) in b.records) put(if (id > 0xFF) "%04X".format(id) else "%02X".format(id), hex(data))
                    })
                })
            })
        }
    }
}
