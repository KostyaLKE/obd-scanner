package com.kostyalke.obdscanner.obd

import com.kostyalke.obdscanner.elm.ObdProtocol

/** Сообщение от одного блока управления (заголовок/адрес + полезные данные). */
class EcuMessage(val ecu: String, val data: IntArray, val incomplete: Boolean = false) {
    override fun toString(): String = "$ecu: " + data.joinToString(" ") { "%02X".format(it) }
}

/**
 * Разбор ответа ELM327 при включённых заголовках (ATH1).
 *  - CAN: «7E8 06 41 00 BE 3F A8 13» (11 бит) или «18 DA F1 10 06 41 00 …» (29 бит),
 *    многокадровые ответы ISO-TP собираются по каждому блоку.
 *  - K-line / J1850: «48 6B 10 41 00 BE 3F A8 13 CS» — 3 байта заголовка, данные, контрольная сумма.
 *    Каждая строка — отдельное сообщение.
 */
object ResponseParser {

    fun parse(lines: List<String>, protocol: ObdProtocol): List<EcuMessage> {
        val frames = lines.mapNotNull { tokenize(it, protocol) }
        return if (protocol.isCan || frames.any { it.first.length == 3 }) {
            reassembleCan(frames)
        } else {
            frames.mapNotNull { (_, bytes) ->
                if (bytes.size < 5) return@mapNotNull null
                val source = bytes[2]
                EcuMessage(kLineEcuName(source), bytes.copyOfRange(3, bytes.size - 1))
            }
        }
    }

    /** Возвращает (заголовок, байты после заголовка для CAN / все байты для K-line). */
    private fun tokenize(line: String, protocol: ObdProtocol): Pair<String, IntArray>? {
        val clean = line.trim().uppercase()
        if (clean.isEmpty()) return null
        var tokens = clean.split(Regex("\\s+"))
        if (tokens.size == 1) {
            // Пробелы выключены (ATS0) — режем вручную.
            val s = tokens[0]
            if (!s.all { it.isHexDigit() }) return null
            tokens = if (protocol.isCan && !protocol.is29bit && s.length % 2 == 1) {
                listOf(s.substring(0, 3)) + s.substring(3).chunked(2)
            } else s.chunked(2)
        }
        if (!tokens.all { t -> t.all { it.isHexDigit() } }) return null
        return when {
            tokens[0].length == 3 -> {
                val bytes = tokens.drop(1).map { it.toInt(16) }.toIntArray()
                tokens[0] to bytes
            }
            protocol.isCan && protocol.is29bit -> {
                if (tokens.size < 5) return null
                val header = tokens.take(4).joinToString("")
                header to tokens.drop(4).map { it.toInt(16) }.toIntArray()
            }
            tokens.all { it.length == 2 } -> "" to tokens.map { it.toInt(16) }.toIntArray()
            else -> null
        }
    }

    /**
     * Сборка ISO-TP по каждому блоку. Сообщение считается полным только при правильном порядке
     * кадров: первый (1x) → последующие 21, 22, … 2F, 20, … без пропусков и повторов.
     * Всё остальное отдаётся с пометкой [EcuMessage.incomplete].
     */
    private fun reassembleCan(frames: List<Pair<String, IntArray>>): List<EcuMessage> {
        class Acc {
            var expected = -1
            var nextSeq = 1
            val data = ArrayList<Int>()
            val done = ArrayList<Pair<IntArray, Boolean>>() // данные, неполное

            fun abort() {
                if (expected >= 0) done += data.toIntArray() to true
                expected = -1
                data.clear()
            }
        }
        val byHeader = LinkedHashMap<String, Acc>()
        for ((header, bytes) in frames) {
            if (bytes.isEmpty()) continue
            val acc = byHeader.getOrPut(header) { Acc() }
            val pci = bytes[0]
            when (pci shr 4) {
                0 -> { // одиночный кадр: длина 1..7 и все обещанные байты на месте
                    acc.abort()
                    val len = pci and 0x0F
                    val ok = len in 1..7 && bytes.size >= 1 + len
                    acc.done += bytes.copyOfRange(1, minOf(bytes.size, 1 + len)) to !ok
                }
                1 -> { // первый кадр; длина меньше 8 в многокадровом сообщении недопустима
                    acc.abort()
                    val len = if (bytes.size >= 2) ((pci and 0x0F) shl 8) or bytes[1] else -1
                    if (len < 8) {
                        acc.done += bytes.drop(1).toIntArray() to true
                        continue
                    }
                    acc.expected = len
                    acc.nextSeq = 1
                    for (i in 2 until bytes.size) acc.data += bytes[i]
                }
                2 -> { // последующий кадр
                    val seq = pci and 0x0F
                    if (acc.expected < 0 || seq != acc.nextSeq) {
                        // Нет начала, пропуск, повтор или перестановка — склеивать нельзя.
                        acc.abort()
                        acc.done += bytes.drop(1).toIntArray() to true
                        continue
                    }
                    acc.nextSeq = (acc.nextSeq + 1) and 0x0F
                    for (i in 1 until bytes.size) acc.data += bytes[i]
                }
                else -> continue // flow control и прочее — не данные
            }
            if (acc.expected >= 0 && acc.data.size >= acc.expected) {
                acc.done += acc.data.take(acc.expected).toIntArray() to false
                acc.expected = -1
                acc.data.clear()
            }
        }
        val result = ArrayList<EcuMessage>()
        for ((header, acc) in byHeader) {
            acc.abort() // недособранное в конце ответа
            val name = canEcuName(header)
            acc.done.forEach { (d, bad) -> result += EcuMessage(name, d, incomplete = bad) }
        }
        return result
    }

    fun canEcuName(header: String): String {
        val source = when (header.length) {
            3 -> header
            8 -> header.substring(6)
            else -> header
        }
        val title = when (source) {
            "7E8", "10" -> "Двигатель"
            "7E9", "18" -> "КПП"
            "7EA" -> "Блок 3"
            "7EB" -> "Блок 4"
            else -> null
        }
        return if (title != null) "$title ($header)" else "Блок $header"
    }

    fun kLineEcuName(source: Int): String {
        val hex = "%02X".format(source)
        val title = when (source) {
            in 0x10..0x17 -> "Двигатель"
            in 0x18..0x1F -> "КПП"
            in 0x28..0x2F -> "Тормоза/ABS"
            else -> null
        }
        return if (title != null) "$title ($hex)" else "Блок $hex"
    }

    private fun Char.isHexDigit() = this in '0'..'9' || this in 'A'..'F'
}

/** Ответы конкретного сервиса (первый байт = сервис + 0x40). */
fun List<EcuMessage>.forService(service: Int): List<EcuMessage> =
    filter { it.data.isNotEmpty() && it.data[0] == service + 0x40 }
