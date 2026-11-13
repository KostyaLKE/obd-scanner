package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ObdTransport
import com.kostyalke.obdscanner.obd.DtcKind
import com.kostyalke.obdscanner.obd.DtcReport
import com.kostyalke.obdscanner.obd.ObdService
import com.kostyalke.obdscanner.obd.ReadStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Ошибок нет» допустимо только после корректного положительного ответа блока.
 * Ответы задаются вручную, а не демо-эмулятором: проверяем разбор, а не свои же ожидания.
 */
class DtcReadTest {

    /** Адаптер с заранее заданными ответами; AT-команды — «OK». CAN 11 бит. */
    private class Scripted(val replies: Map<String, String>) : ObdTransport {
        private var last = ""
        override val name = "test"
        override suspend fun open() {}
        override suspend fun write(text: String) { last = text.trim() }
        override suspend fun readUntilPrompt(timeoutMs: Long): String = when {
            last in replies -> replies.getValue(last)
            last == "ATZ" -> "ELM327 v1.5"
            last == "ATDPN" -> "A6"
            last == "0100" -> "7E8 06 41 00 98 3B 80 11"
            last.startsWith("AT") -> "OK"
            else -> "NO DATA"
        } + "\r"
        override suspend fun clearInput() {}
        override fun close() {}
    }

    /** Check Engine не горит, 0 ошибок по данным блока. */
    private val readinessOk = "7E8 06 41 01 00 07 E5 00"

    private fun read(vararg r: Pair<String, String>): DtcReport = runBlocking {
        val elm = Elm327(Scripted(mapOf("0101" to readinessOk) + r)) {}
        elm.initialize('6') {}
        ObdService(elm).readDtcs()
    }

    private val zero = arrayOf("03" to "7E8 02 43 00", "07" to "7E8 02 47 00", "0A" to "7E8 02 4A 00")

    @Test
    fun correctZeroAnswerIsTrustedNoErrors() {
        val r = read(*zero)
        assertTrue(r.isComplete)
        assertTrue(r.all.isEmpty())
        assertNull(r.mismatch)
        assertEquals(ReadStatus.OK, r.statusOf(DtcKind.STORED))
    }

    @Test
    fun noDataIsNotNoErrors() {
        val r = read("03" to "NO DATA", "07" to "7E8 02 47 00", "0A" to "7E8 02 4A 00")
        assertFalse(r.isComplete)
        assertEquals(ReadStatus.NO_RESPONSE, r.statusOf(DtcKind.STORED))
    }

    @Test
    fun garbageIsCorrupt() {
        val r = read("03" to "7E8 GARBAGE ZZ", "07" to "7E8 02 47 00", "0A" to "7E8 02 4A 00")
        assertFalse(r.isComplete)
        assertEquals(ReadStatus.CORRUPT, r.statusOf(DtcKind.STORED))
    }

    @Test
    fun negativeAnswerIsRejectedOrUnsupported() {
        val r = read("03" to "7E8 02 43 00", "07" to "7E8 03 7F 07 22", "0A" to "7E8 03 7F 0A 11")
        assertFalse(r.isComplete)
        assertEquals(ReadStatus.REJECTED, r.statusOf(DtcKind.PENDING))
        assertEquals(ReadStatus.NOT_SUPPORTED, r.statusOf(DtcKind.PERMANENT))
    }

    @Test
    fun countDoesNotMatchPairsIsCorrupt() {
        // Блок сообщил о двух ошибках, а пришла одна пара — усечённый ответ.
        val r = read("03" to "7E8 04 43 02 01 01", "07" to "7E8 02 47 00", "0A" to "7E8 02 4A 00")
        assertEquals(ReadStatus.CORRUPT, r.statusOf(DtcKind.STORED))
        assertFalse(r.isComplete)
    }

    @Test
    fun incompleteMultiFrameIsCorrupt() {
        // Первый кадр обещает 10 байт, последующего кадра нет.
        val r = read("03" to "7E8 10 0A 43 04 01 01 15 57", "07" to "7E8 02 47 00", "0A" to "7E8 02 4A 00")
        assertEquals(ReadStatus.CORRUPT, r.statusOf(DtcKind.STORED))
        assertTrue(r.stored.isEmpty())
    }

    @Test
    fun milOnWithEmptyListIsMismatch() {
        // Check Engine горит, блок насчитал 1 ошибку, а список пуст.
        val r = read("0101" to "7E8 06 41 01 81 07 E5 00", *zero)
        assertTrue(r.isComplete)
        assertNotNull(r.mismatch)
    }

    @Test
    fun partialFailureKeepsReadData() {
        val r = read("03" to "7E8 04 43 01 01 01", "07" to "NO DATA", "0A" to "7E8 02 4A 00")
        assertEquals(listOf("P0101"), r.stored.map { it.code })
        assertEquals(ReadStatus.NO_RESPONSE, r.statusOf(DtcKind.PENDING))
        assertFalse(r.isComplete)
    }
}
