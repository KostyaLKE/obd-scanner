package com.kostyalke.obdscanner.elm

import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Эмулятор ELM327 + дизельная Skoda, чтобы проверить приложение без машины.
 *  - по умолчанию: Fabia II 2012, 1.6 TDI common rail, шина CAN 11 бит / 500 кбит/с;
 *  - [kLine] = true: Fabia I 1.9 TDI на K-line (ISO 14230) — для тестов разбора старого протокола.
 */
class DemoTransport(
    /** Для тестов: блок отвечает только при ATKW0 и явном протоколе 5, как капризные блоки VAG на K-line. */
    private val strictKeyword: Boolean = false,
    private val kLine: Boolean = strictKeyword,
) : ObdTransport {
    override val name = if (kLine) "Демо: Skoda Fabia 1.9 TDI" else "Демо: Skoda Fabia 1.6 TDI CR"

    private var kw0 = false
    private var proto = '0'

    private var pending = ""
    private val start = System.currentTimeMillis()

    // K-line: P0401, P1557, ожидающая P0234. CAN (common rail): P0401, P2463, ожидающая P0299.
    private val stored = if (kLine) mutableListOf(0x04 to 0x01, 0x15 to 0x57) else mutableListOf(0x04 to 0x01, 0x24 to 0x63)
    private val pendingCodes = if (kLine) mutableListOf(0x02 to 0x34) else mutableListOf(0x02 to 0x99)

    override suspend fun open() = delay(600)

    override suspend fun write(text: String) {
        pending = text.trim().uppercase().replace(" ", "")
    }

    override suspend fun clearInput() {}

    override fun close() {}

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val cmd = pending
        delay(if (cmd.startsWith("AT")) 30 else if (kLine) 80 else 25)
        val body = respond(cmd)
        return body.joinToString("\r", postfix = "\r\r")
    }

    /** K-line: «48 6B 10 <данные> CS», каждая строка — отдельное сообщение. */
    private fun kLineLine(vararg data: Int): String {
        val bytes = intArrayOf(0x48, 0x6B, 0x10) + data
        val cs = bytes.sum() and 0xFF
        return (bytes + cs).joinToString(" ") { "%02X".format(it) }
    }

    /** CAN: сообщение блока 7E8 в кадрах ISO-TP (с заголовками и заполнением до 8 байт, как у ELM327). */
    private fun canFrames(payload: IntArray, from: Int = 0x7E8): List<String> {
        fun frame(bytes: List<Int>) =
            "%03X ".format(from) + (bytes + List(8 - bytes.size) { 0x00 }).joinToString(" ") { "%02X".format(it) }
        if (payload.size <= 7) return listOf(frame(listOf(payload.size) + payload.toList()))
        val out = ArrayList<String>()
        out += frame(listOf(0x10 or (payload.size shr 8), payload.size and 0xFF) + payload.take(6))
        var seq = 1
        payload.drop(6).chunked(7).forEach { chunk ->
            out += frame(listOf(0x20 or (seq and 0x0F)) + chunk)
            seq++
        }
        return out
    }

    private fun msg(vararg payload: Int): List<String> = if (kLine) listOf(kLineLine(*payload)) else canFrames(payload)

    private fun mask(base: Int, pids: Set<Int>): IntArray {
        var bits = 0L
        for (p in pids) if (p in base + 1..base + 32) bits = bits or (1L shl (32 - (p - base)))
        return intArrayOf(((bits shr 24) and 0xFF).toInt(), ((bits shr 16) and 0xFF).toInt(),
            ((bits shr 8) and 0xFF).toInt(), (bits and 0xFF).toInt())
    }

    private val supported: Set<Int> = setOf(0x01, 0x03, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1C, 0x1F,
        0x20, 0x21, 0x31, 0x33, 0x40, 0x42, 0x46, 0x49) +
        (if (kLine) emptySet() else setOf(0x23, 0x2C, 0x60, 0x62, 0x78, 0x7A, 0x7C))

    private fun t() = (System.currentTimeMillis() - start) / 1000.0

    private fun u16(v: Int) = intArrayOf((v shr 8) and 0xFF, v and 0xFF)

    // ---------- Эмуляция блоков VAG по TP2.0 (только CAN-демо) ----------

    private var caf = true
    private var header = 0x7DF
    private class DemoEcu(val part: String, val dtcs: MutableList<Pair<Int, Int>>)
    private val ecus = mapOf(
        0x01 to DemoEcu("03P906021AB  EDC17C64", mutableListOf(16785 to 0x23)), // = P0401
        0x03 to DemoEcu("6R0907379AL  ESP MK60EC1", mutableListOf(283 to 0x23)),
        0x09 to DemoEcu("6R0937087K  BCM PQ25", mutableListOf()),
        0x15 to DemoEcu("6R0959655C  Airbag", mutableListOf()),
        0x17 to DemoEcu("5J0920810C   KOMBI", mutableListOf(668 to 0x25)),
        0x19 to DemoEcu("6R0907530   Gateway", mutableListOf()),
    )
    private var ecu: DemoEcu? = null
    private var ecuSeq = 0

    private fun hexLine(id: Int, vararg b: Int) = "%03X ".format(id) + b.joinToString(" ") { "%02X".format(it) }

    private fun tp20(b: List<Int>): List<String> {
        if (header == 0x200) {
            if (b.size >= 7 && b[1] == 0xC0) {
                val e = ecus[b[0]] ?: return listOf("NO DATA")
                ecu = e
                ecuSeq = 0
                return listOf(hexLine(0x200 + b[0], 0x00, 0xD0, 0x00, 0x03, 0x40, 0x07, 0x01))
            }
            return listOf("NO DATA")
        }
        val e = ecu ?: return listOf("NO DATA")
        if (b.isEmpty()) return listOf("?")
        return when (b[0] shr 4) {
            0xA -> when (b[0]) {
                0xA0, 0xA3 -> listOf(hexLine(0x300, 0xA1, 0x0F, 0x8A, 0xFF, 0x4A, 0xFF))
                0xA8 -> { ecu = null; listOf(hexLine(0x300, 0xA8)) }
                else -> listOf("NO DATA")
            }
            0xB -> listOf("NO DATA")
            0x1, 0x3 -> {
                val ack = hexLine(0x300, 0xB0 or ((b[0] + 1) and 0x0F))
                val len = (b[1] shl 8) or b[2]
                val kwp = b.drop(3).take(len)
                val resp: List<Int> = when {
                    kwp == listOf(0x10, 0x89) -> listOf(0x50, 0x89)
                    kwp == listOf(0x1A, 0x9B) -> listOf(0x5A, 0x9B) + e.part.map { it.code }
                    kwp == listOf(0x18, 0x02, 0xFF, 0x00) ->
                        listOf(0x58, e.dtcs.size) + e.dtcs.flatMap { (c, st) -> listOf(c shr 8, c and 0xFF, st) }
                    kwp == listOf(0x14, 0xFF, 0x00) -> { e.dtcs.clear(); listOf(0x54, 0xFF, 0x00) }
                    kwp == listOf(0x1A, 0x9A) -> listOf(0x5A, 0x9A, 0x01, 0x0A, 0x24, 0x03, 0x00, 0x12, 0x34)
                    kwp == listOf(0x1A, 0x91) -> listOf(0x5A, 0x91, 0x0F) + "SW 0102".map { it.code }
                    else -> listOf(0x7F, kwp.firstOrNull() ?: 0, 0x11)
                }
                val data = listOf(resp.size shr 8, resp.size and 0xFF) + resp
                val chunks = data.chunked(7)
                listOf(ack) + chunks.mapIndexed { i, c ->
                    val type = if (i == chunks.lastIndex) 0x10 else 0x20
                    hexLine(0x300, type or (ecuSeq++ and 0x0F), *c.toIntArray())
                }
            }
            else -> listOf("NO DATA")
        }
    }

    // Блок только с UDS (как новые блоки VAG): усилитель руля, запрос 0x712 → ответ 0x77C.
    private val epsDtcs = mutableListOf(0x454500 to 0x09) // C0545, подтверждена

    private fun udsPowerSteering(b: List<Int>): List<String> {
        val resp: List<Int> = when {
            b == listOf(0x19, 0x02, 0xFF) -> listOf(0x59, 0x02, 0xFF) +
                epsDtcs.flatMap { (c, st) -> listOf(c shr 16, (c shr 8) and 0xFF, c and 0xFF, st) }
            b == listOf(0x22, 0xF1, 0x87) -> listOf(0x62, 0xF1, 0x87) + "6R0423156B ".map { it.code }
            b == listOf(0x22, 0xF1, 0x97) -> listOf(0x62, 0xF1, 0x97) + "EPS ZFLS".map { it.code }
            b == listOf(0x22, 0x06, 0x00) -> listOf(0x62, 0x06, 0x00, 0x01, 0x02, 0x03)
            b == listOf(0x10, 0x03) -> listOf(0x50, 0x03, 0x00, 0x32, 0x01, 0xF4)
            b == listOf(0x14, 0xFF, 0xFF, 0xFF) -> { epsDtcs.clear(); listOf(0x54) }
            else -> listOf(0x7F, b.firstOrNull() ?: 0, 0x31)
        }
        return canFrames(resp.toIntArray(), from = 0x77C)
    }

    private fun respond(cmd: String): List<String> {
        if (!kLine) {
            when {
                cmd == "ATCAF0" -> { caf = false; return listOf("OK") }
                cmd == "ATCAF1" -> { caf = true; return listOf("OK") }
                cmd.startsWith("ATSH") -> { header = cmd.drop(4).toIntOrNull(16) ?: header; return listOf("OK") }
                cmd.startsWith("ATCRA") || cmd.startsWith("ATST") || cmd.startsWith("ATR") -> return listOf("OK")
                !caf && !cmd.startsWith("AT") -> return tp20(cmd.chunked(2).mapNotNull { it.toIntOrNull(16) })
                caf && !cmd.startsWith("AT") && header == 0x712 -> return udsPowerSteering(cmd.chunked(2).mapNotNull { it.toIntOrNull(16) })
            }
        }
        if (cmd == "ATKW0") kw0 = true
        if (cmd.startsWith("ATSP") && cmd.length == 5) proto = cmd[4]
        if (strictKeyword && !cmd.startsWith("AT") && !(kw0 && proto == '5')) {
            return listOf("BUS INIT: ...ERROR")
        }
        if (cmd.startsWith("AT")) return when {
            cmd == "ATZ" || cmd == "ATWS" -> listOf("", "ELM327 v1.5 (демо)")
            cmd == "ATI" -> listOf("ELM327 v1.5 (демо)")
            cmd == "ATRV" -> listOf("%.1fV".format(13.9 + 0.1 * sin(t())).replace(',', '.'))
            cmd == "ATDPN" -> listOf(if (kLine) "A5" else "A6")
            else -> listOf("OK")
        }
        val bytes = cmd.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return listOf("?")
        val wave = sin(t() / 3)
        val rpm = 850 + 600 * (1 + wave)
        return when (bytes[0]) {
            0x01 -> {
                val pid = bytes.getOrNull(1) ?: return listOf("?")
                if (pid !in supported && pid !in setOf(0x00, 0x01)) return listOf("NO DATA")
                val data: IntArray? = when (pid) {
                    0x00, 0x20, 0x40, 0x60 -> mask(pid, supported)
                    0x01 -> intArrayOf(0x80 or stored.size, 0x0F, 0xC9, 0xC0) // MIL, дизель; EGR и DPF не готовы
                    0x04 -> intArrayOf((25 + 20 * wave).toInt().coerceIn(0, 100) * 255 / 100)
                    0x05 -> intArrayOf((40 + minOf(t(), 50.0)).toInt() + 40)
                    0x0B -> intArrayOf((100 + 60 * (1 + wave)).toInt())
                    0x0C -> u16((rpm * 4).toInt())
                    0x0D -> intArrayOf((40 + 20 * sin(t() / 5)).toInt())
                    0x0F -> intArrayOf(18 + 40)
                    0x10 -> u16((rpm / 3 * 100 / 10).toInt())
                    0x11, 0x49 -> intArrayOf((20 + 15 * wave).toInt() * 255 / 100)
                    0x1C -> intArrayOf(6)
                    0x1F -> u16(t().toInt())
                    0x21 -> intArrayOf(0, 37)
                    0x23 -> u16(((260 + rpm * 0.35) * 10).toInt()) // давление в рампе, бар × 10
                    0x2C -> intArrayOf(((35 - 15 * wave) * 255 / 100).toInt())
                    0x31 -> intArrayOf(0x03, 0x20)
                    0x33 -> intArrayOf(99)
                    0x42 -> intArrayOf(0x36, 0x4C)
                    0x46 -> intArrayOf(12 + 40)
                    0x62 -> intArrayOf((125 + 20 + 25 * wave).toInt())
                    0x78 -> intArrayOf(0x01) + u16(((330 + 80 * wave + 40) * 10).toInt())
                    0x7A -> intArrayOf(0x01) + u16(((6.5 + 4 * (1 + wave)) * 100).toInt())
                    0x7C -> intArrayOf(0x01) + u16(((290 + 60 * wave + 40) * 10).toInt())
                    else -> null
                }
                if (data == null) listOf("NO DATA") else msg(0x41, pid, *data)
            }
            0x02 -> {
                val pid = bytes.getOrNull(1) ?: return listOf("?")
                if (stored.isEmpty()) return listOf("NO DATA")
                val data = when (pid) {
                    0x00 -> mask(0, setOf(0x02, 0x04, 0x05, 0x0B, 0x0C, 0x0D))
                    0x02 -> intArrayOf(stored[0].first, stored[0].second)
                    0x04 -> intArrayOf(204)
                    0x05 -> intArrayOf(87 + 40)
                    0x0B -> intArrayOf(232)
                    0x0C -> intArrayOf(0x3A, 0x98)
                    0x0D -> intArrayOf(96)
                    else -> null
                } ?: return listOf("NO DATA")
                msg(0x42, pid, 0x00, *data)
            }
            0x03 -> codes(0x43, stored)
            0x07 -> codes(0x47, pendingCodes)
            0x0A -> if (kLine) listOf("NO DATA") else codes(0x4A, emptyList())
            0x04 -> {
                stored.clear(); pendingCodes.clear()
                msg(0x44)
            }
            0x09 -> when (bytes.getOrNull(1)) {
                0x02 -> {
                    val vin = (if (kLine) "TMBPY16Y123456789" else "TMBEG25J5C3057412").map { it.code }
                    if (kLine) {
                        (listOf(0, 0, 0) + vin).chunked(4).mapIndexed { i, c -> kLineLine(0x49, 0x02, i + 1, *c.toIntArray()) }
                    } else {
                        canFrames((listOf(0x49, 0x02, 0x01) + vin).toIntArray())
                    }
                }
                0x0A -> if (kLine) listOf("NO DATA") else
                    canFrames((listOf(0x49, 0x0A, 0x01) + "ECM-EngineControl".padEnd(20, '\u0000').map { it.code }).toIntArray())
                else -> listOf("NO DATA")
            }
            else -> listOf("NO DATA")
        }
    }

    private fun codes(service: Int, list: List<Pair<Int, Int>>): List<String> {
        if (!kLine) {
            // CAN: [сервис, количество, пары…] одним сообщением.
            return canFrames((listOf(service, list.size) + list.flatMap { listOf(it.first, it.second) }).toIntArray())
        }
        if (list.isEmpty()) return listOf(kLineLine(service, 0, 0, 0, 0, 0, 0))
        return list.chunked(3).map { chunk ->
            val d = chunk.flatMap { listOf(it.first, it.second) }.toMutableList()
            while (d.size < 6) d += 0
            kLineLine(service, *d.toIntArray())
        }
    }
}
