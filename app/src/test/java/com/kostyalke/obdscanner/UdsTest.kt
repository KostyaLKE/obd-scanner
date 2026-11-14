package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ElmReply
import com.kostyalke.obdscanner.vag.Uds
import com.kostyalke.obdscanner.vag.VagException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** UDS: проверка источника, сервиса, подфункции, DID, длины; ожидание 0x78; ошибки адаптера. */
class UdsTest {

    /** Приборка: запрос 714, ответ 77E. Ответы на запросы — из очередей по команде. */
    private class Fake(val replies: Map<String, List<String>>, val badAt: Set<String> = emptySet()) {
        val sent = ArrayList<String>()
        private val queues = replies.mapValues { ArrayDeque(it.value) }
        val send: suspend (String, Long) -> ElmReply = { cmd, _ ->
            sent += cmd
            val text = when {
                cmd.startsWith("AT") -> if (cmd in badAt) "?" else "OK"
                else -> queues[cmd]?.let { q -> if (q.size > 1) q.removeFirst() else q.firstOrNull() } ?: "NO DATA"
            }
            ElmReply(cmd, text, Elm327.cleanLines(cmd, text))
        }
    }

    private fun uds(f: Fake) = Uds(f.send).also { runBlocking { it.target(0x714, 0x77E) } }

    private fun <T> fails(block: suspend () -> T) {
        try {
            runBlocking { block() }
            fail("неполный/чужой ответ принят")
        } catch (e: VagException) { /* ожидаемо */ }
    }

    @Test
    fun correctDtcAnswer() {
        val r = runBlocking { uds(Fake(mapOf("1902FF" to listOf("77E 07 59 02 FF 01 23 45 09")))).readDtcs() }
        assertEquals(listOf(0x012345), r!!.map { it.code })
    }

    @Test
    fun incompleteDtcAnswerIsNotEmptyList() {
        // Первый кадр обещает 11 байт, продолжения нет.
        val f = Fake(mapOf("1902FF" to listOf("77E 10 0B 59 02 FF 01 23 45")))
        fails { uds(f).readDtcs() }
    }

    @Test
    fun truncatedDtcRecordIsRejected() {
        // Запись ошибки — 4 байта, пришло 2.
        fails { uds(Fake(mapOf("1902FF" to listOf("77E 05 59 02 FF 01 23")))).readDtcs() }
    }

    @Test
    fun answerFromOtherEcuIsNotAccepted() {
        fails { uds(Fake(mapOf("1902FF" to listOf("7E8 03 59 02 FF")))).readDtcs() }
    }

    @Test
    fun wrongSubfunctionIsRejected() {
        fails { uds(Fake(mapOf("1902FF" to listOf("77E 03 59 0A FF")))).readDtcs() }
    }

    @Test
    fun wrongDidIsNotAccepted() {
        val u = uds(Fake(mapOf("22F187" to listOf("77E 06 62 F1 89 41 42 43"))))
        assertNull(runBlocking { u.readText(0xF187) })
    }

    @Test
    fun negativeForOtherServiceIsNotRefusal() {
        // 7F другого сервиса — не ответ на наш запрос.
        fails { uds(Fake(mapOf("1902FF" to listOf("77E 03 7F 22 31")))).readDtcs() }
    }

    @Test
    fun pendingThenFinalInNextReplyIsAccepted() {
        val f = Fake(mapOf("1902FF" to listOf("77E 03 7F 19 78", "77E 07 59 02 FF 01 23 45 09")))
        val r = runBlocking { uds(f).readDtcs() }
        assertEquals(1, r!!.size)
    }

    @Test
    fun clearWithOnlyPendingIsNotConfirmedAndNotRepeated() {
        val f = Fake(mapOf("14FFFFFF" to listOf("77E 03 7F 14 78")))
        fails { uds(f).clearDtcs() }
        assertEquals("сброс отправлен повторно", 1, f.sent.count { it == "14FFFFFF" })
    }

    @Test
    fun adapterErrorIsNotNoResponse() {
        fails { uds(Fake(mapOf("1902FF" to listOf("CAN ERROR")))).readDtcs() }
    }

    @Test
    fun noDataIsNoResponse() {
        assertNull(runBlocking { uds(Fake(emptyMap())).readDtcs() })
    }

    @Test
    fun failedFilterIsAdapterError() {
        val f = Fake(emptyMap(), badAt = setOf("ATCRA77E", "ATCF77E"))
        try {
            runBlocking { Uds(f.send).target(0x714, 0x77E) }
            fail("фильтр не установлен, а ошибки нет")
        } catch (e: VagException) {
            assertTrue(e.message!!.contains("фильтр"))
        }
    }

    @Test
    fun refusalAndAdapterErrorAreDifferentKinds() {
        fun kind(reply: String) = try {
            runBlocking { uds(Fake(mapOf("1902FF" to listOf(reply)))).readDtcs() }
            null
        } catch (e: VagException) { e.kind }
        assertEquals(com.kostyalke.obdscanner.vag.VagErrorKind.REFUSED, kind("77E 03 7F 19 22"))
        assertEquals(com.kostyalke.obdscanner.vag.VagErrorKind.ADAPTER, kind("CAN ERROR"))
        assertEquals(com.kostyalke.obdscanner.vag.VagErrorKind.LINK, kind("77E 10 0B 59 02 FF 01 23 45"))
    }
}
