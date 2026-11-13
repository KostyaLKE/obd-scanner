package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ElmReply
import com.kostyalke.obdscanner.elm.ObdProtocol
import com.kostyalke.obdscanner.obd.ResponseParser
import com.kostyalke.obdscanner.vag.Tp20
import com.kostyalke.obdscanner.vag.VagException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Целостность многокадровых ответов: потерянные, повторные, переставленные и усечённые кадры. */
class IntegrityTest {

    private fun can(vararg lines: String) = ResponseParser.parse(lines.toList(), ObdProtocol.CAN_11_500)

    // ---- ISO-TP (OBD по CAN) ----

    @Test
    fun completeMultiFrameIsOk() {
        val m = can("7E8 10 0A 43 04 01 01 15 57", "7E8 21 02 34 03 80 00 00 00")
        assertEquals(1, m.size)
        assertFalse(m[0].incomplete)
        assertEquals(10, m[0].data.size)
    }

    @Test
    fun lostConsecutiveFrameIsIncomplete() {
        // Кадр 21 потерян, пришёл 22: склеивать нельзя.
        val m = can("7E8 10 10 49 02 01 57 56 57", "7E8 22 35 30 37 38 37 39 31")
        assertTrue(m.all { it.incomplete })
    }

    @Test
    fun reorderedFramesAreIncomplete() {
        val m = can("7E8 10 10 49 02 01 57 56 57", "7E8 22 35 30 37 38 37 39 31", "7E8 21 5A 5A 5A 36 52 5A 37")
        assertTrue(m.all { it.incomplete })
    }

    @Test
    fun duplicatedFrameIsIncomplete() {
        val m = can("7E8 10 10 49 02 01 57 56 57", "7E8 21 5A 5A 5A 36 52 5A 37", "7E8 21 5A 5A 5A 36 52 5A 37")
        assertTrue(m.any { it.incomplete })
    }

    @Test
    fun consecutiveWithoutFirstIsIncomplete() {
        val m = can("7E8 21 5A 5A 5A 36 52 5A 37")
        assertTrue(m.isNotEmpty() && m.all { it.incomplete })
    }

    @Test
    fun sequenceWrapsAfterF() {
        // 6 + 15×7 + 7 = 118 байт: кадры 21…2F, затем 20.
        val total = 6 + 15 * 7 + 7
        val lines = ArrayList<String>()
        lines += "7E8 10 %02X 49 02 01 00 00 00".format(total)
        for (i in 1..16) lines += "7E8 2%X 00 00 00 00 00 00 00".format(i and 0x0F)
        val m = can(*lines.toTypedArray())
        assertEquals(1, m.size)
        assertFalse(m[0].incomplete)
        assertEquals(total, m[0].data.size)
    }

    @Test
    fun twoEcusInterleavedAreAssembledSeparately() {
        val m = can(
            "7E8 10 0A 43 04 01 01 15 57", "7E9 10 08 43 03 07 00 07 01",
            "7E9 21 07 02 00 00 00 00 00", "7E8 21 02 34 03 80 00 00 00",
        )
        assertEquals(2, m.size)
        assertTrue(m.none { it.incomplete })
    }

    // ---- TP2.0 ----

    /** Блок на 0x300, мы — 0x740. Ответы на запросы данных — из очереди. */
    private fun tp(vararg replies: List<String>): Tp20 {
        val queue = ArrayDeque(replies.toList())
        val send: suspend (String, Long) -> ElmReply = { cmd, _ ->
            val lines = when {
                cmd.startsWith("AT") -> listOf("OK")
                cmd == "01C000100003011" -> listOf("201 00 D0 00 03 40 07 01")
                cmd == "A00F8AFF32FF1" -> listOf("300 A1 0F 8A FF 4A FF")
                cmd.startsWith("1") && cmd.length > 4 -> queue.removeFirstOrNull() ?: emptyList()
                cmd == "A3" -> queue.removeFirstOrNull() ?: listOf("300 A1 0F 8A FF 4A FF")
                else -> emptyList() // подтверждения B*
            }
            ElmReply(cmd, lines.joinToString("\r"), Elm327.cleanLines(cmd, lines.joinToString("\r")))
        }
        return Tp20(send)
    }

    private fun dtcs(t: Tp20) = runBlocking { assertTrue(t.open(0x01)); t.readDtcs() }

    @Test
    fun tpSingleLastPacket() {
        val r = dtcs(tp(listOf("300 10 00 05 58 01 12 34 08")))
        assertEquals(listOf(0x1234), r.map { it.code })
    }

    @Test
    fun tpMultiPacketWithWrap() {
        // Номер пакета F → 0: переход через границу.
        val r = dtcs(tp(listOf("300 2F 00 08 58 02 12 34", "300 10 08 23 45 09")))
        assertEquals(listOf(0x1234, 0x2345), r.map { it.code })
    }

    @Test
    fun tpDuplicatePacketIgnored() {
        val r = dtcs(tp(listOf("300 20 00 08 58 02 12 34", "300 20 00 08 58 02 12 34", "300 11 08 23 45 09")))
        assertEquals(listOf(0x1234, 0x2345), r.map { it.code })
    }

    @Test
    fun tpSkippedPacketIsError() {
        try {
            dtcs(tp(listOf("300 20 00 08 58 02 12 34", "300 12 08 23 45 09")))
            fail("пропуск пакета принят")
        } catch (e: VagException) { /* ожидаемо */ }
    }

    @Test
    fun tpDtcCountMismatchIsError() {
        // Блок сообщил 2 ошибки, а прислал одну.
        try {
            dtcs(tp(listOf("300 10 00 05 58 02 12 34 08")))
            fail("усечённый список ошибок принят")
        } catch (e: VagException) { /* ожидаемо */ }
    }
}
