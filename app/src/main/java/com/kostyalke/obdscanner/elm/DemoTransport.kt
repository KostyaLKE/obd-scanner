package com.kostyalke.obdscanner.elm

import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Эмулятор ELM327 + дизельная Skoda на K-line (ISO 14230, как Fabia I 1.9 SDI/TDI).
 * Нужен, чтобы проверить приложение без машины.
 */
class DemoTransport : ObdTransport {
    override val name = "Демо: Skoda Fabia 1.9 TDI"

    private var pending = ""
    private val start = System.currentTimeMillis()
    private var stored = mutableListOf(0x04 to 0x01, 0x15 to 0x57) // P0401, P1557
    private var pendingCodes = mutableListOf(0x02 to 0x34) // P0234

    override suspend fun open() = delay(600)

    override suspend fun write(text: String) {
        pending = text.trim().uppercase().replace(" ", "")
    }

    override suspend fun clearInput() {}

    override fun close() {}

    override suspend fun readUntilPrompt(timeoutMs: Long): String {
        val cmd = pending
        delay(if (cmd.startsWith("AT")) 30 else 80)
        val body = respond(cmd)
        return body.joinToString("\r", postfix = "\r\r")
    }

    private fun line(vararg data: Int): String {
        val bytes = intArrayOf(0x48, 0x6B, 0x10) + data
        val cs = bytes.sum() and 0xFF
        return (bytes + cs).joinToString(" ") { "%02X".format(it) }
    }

    private fun mask(base: Int, pids: Set<Int>): IntArray {
        var bits = 0L
        for (p in pids) if (p in base + 1..base + 32) bits = bits or (1L shl (32 - (p - base)))
        return intArrayOf(((bits shr 24) and 0xFF).toInt(), ((bits shr 16) and 0xFF).toInt(),
            ((bits shr 8) and 0xFF).toInt(), (bits and 0xFF).toInt())
    }

    private val supported = setOf(0x01, 0x03, 0x04, 0x05, 0x0B, 0x0C, 0x0D, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x20,
        0x21, 0x31, 0x33, 0x40, 0x42, 0x46, 0x49)

    private fun t() = (System.currentTimeMillis() - start) / 1000.0

    private fun respond(cmd: String): List<String> {
        if (cmd.startsWith("AT")) return when {
            cmd == "ATZ" || cmd == "ATWS" -> listOf("", "ELM327 v1.5 (демо)")
            cmd == "ATI" -> listOf("ELM327 v1.5 (демо)")
            cmd == "ATRV" -> listOf("%.1fV".format(13.9 + 0.1 * sin(t())).replace(',', '.'))
            cmd == "ATDPN" -> listOf("A5")
            else -> listOf("OK")
        }
        val bytes = cmd.chunked(2).mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return listOf("?")
        val rpm = 850 + 600 * (1 + sin(t() / 3))
        return when (bytes[0]) {
            0x01 -> {
                val pid = bytes.getOrNull(1) ?: return listOf("?")
                val data: IntArray? = when (pid) {
                    0x00, 0x20, 0x40 -> mask(pid, supported)
                    0x01 -> intArrayOf(0x80 or stored.size, 0x0F, 0x89, 0x80) // MIL, дизель, EGR не готов
                    0x04 -> intArrayOf((25 + 20 * sin(t() / 3)).toInt().coerceIn(0, 100) * 255 / 100)
                    0x05 -> intArrayOf((40 + minOf(t(), 50.0)).toInt() + 40)
                    0x0B -> intArrayOf((100 + 60 * (1 + sin(t() / 3))).toInt())
                    0x0C -> (rpm * 4).toInt().let { intArrayOf(it shr 8, it and 0xFF) }
                    0x0D -> intArrayOf((40 + 20 * sin(t() / 5)).toInt())
                    0x0F -> intArrayOf(18 + 40)
                    0x10 -> (rpm / 3 * 100 / 10).toInt().let { intArrayOf(it shr 8, it and 0xFF) }
                    0x11 -> intArrayOf((20 + 15 * sin(t() / 3)).toInt() * 255 / 100)
                    0x1C -> intArrayOf(6)
                    0x1F -> t().toInt().let { intArrayOf(it shr 8, it and 0xFF) }
                    0x21 -> intArrayOf(0, 37)
                    0x31 -> intArrayOf(0x03, 0x20)
                    0x33 -> intArrayOf(99)
                    0x42 -> intArrayOf(0x36, 0x4C)
                    0x46 -> intArrayOf(12 + 40)
                    0x49 -> intArrayOf((20 + 15 * sin(t() / 3)).toInt() * 255 / 100)
                    else -> null
                }
                if (data == null) listOf("NO DATA") else listOf(line(0x41, pid, *data))
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
                listOf(line(0x42, pid, 0x00, *data))
            }
            0x03 -> codes(0x43, stored)
            0x07 -> codes(0x47, pendingCodes)
            0x04 -> {
                stored.clear(); pendingCodes.clear()
                listOf(line(0x44))
            }
            0x09 -> when (bytes.getOrNull(1)) {
                0x02 -> {
                    val vin = "TMBPY16Y123456789".map { it.code }
                    val padded = listOf(0, 0, 0) + vin
                    padded.chunked(4).mapIndexed { i, c -> line(0x49, 0x02, i + 1, *c.toIntArray()) }
                }
                else -> listOf("NO DATA")
            }
            else -> listOf("NO DATA")
        }
    }

    private fun codes(service: Int, list: List<Pair<Int, Int>>): List<String> {
        if (list.isEmpty()) return listOf(line(service, 0, 0, 0, 0, 0, 0))
        return list.chunked(3).map { chunk ->
            val d = chunk.flatMap { listOf(it.first, it.second) }.toMutableList()
            while (d.size < 6) d += 0
            line(service, *d.toIntArray())
        }
    }
}
