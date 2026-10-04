package com.kostyalke.obdscanner.obd

import android.content.Context

data class DtcInfo(
    val code: String,
    val description: String?,
    val system: String,
    val vagCode: Int?,
    val hint: String?,
)

/** База расшифровок из assets/dtc_ru.tsv и подсказок из assets/dtc_hints_ru.tsv. */
class DtcDatabase(private val descriptions: Map<String, String>, private val hints: Map<String, String>) {

    fun info(code: String): DtcInfo {
        val desc = descriptions[code] ?: fallback(code)
        return DtcInfo(code, desc, DtcDecoder.systemOf(code), DtcDecoder.vagCode(code), hints[code])
    }

    private fun fallback(code: String): String? = when {
        code.startsWith("P1") || code.startsWith("P3") && code[2] in '0'..'3' ->
            "Код производителя (VAG). Точная расшифровка зависит от блока — ищите по номеру VAG."
        code.startsWith("C") -> "Ошибка шасси (ABS/ESP и т.п.). Расшифровки этого кода в базе нет."
        code.startsWith("B") -> "Ошибка кузовной электроники. Расшифровки этого кода в базе нет."
        code.startsWith("U") -> "Ошибка обмена данными между блоками. Расшифровки этого кода в базе нет."
        else -> null
    }

    companion object {
        fun parseTsv(text: String): Map<String, String> =
            text.lineSequence()
                .mapNotNull { line ->
                    val i = line.indexOf('\t')
                    if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
                }
                .toMap()

        fun load(context: Context): DtcDatabase {
            fun read(name: String) = context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return DtcDatabase(parseTsv(read("dtc_ru.tsv")), parseTsv(read("dtc_hints_ru.tsv")))
        }
    }
}
