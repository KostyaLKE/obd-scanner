package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ObdTransport
import com.kostyalke.obdscanner.obd.ClearFlow
import com.kostyalke.obdscanner.obd.ClearResult
import com.kostyalke.obdscanner.obd.ClearStatus
import com.kostyalke.obdscanner.obd.ControlRead
import com.kostyalke.obdscanner.obd.ObdService
import com.kostyalke.obdscanner.obd.OutcomeKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Сброс ошибок: результат команды и фактический итог по контрольному чтению. */
class ClearFlowTest {

    private class Scripted(val replies: Map<String, String>) : ObdTransport {
        private var last = ""
        val sent = ArrayList<String>()
        override val name = "test"
        override suspend fun open() {}
        override suspend fun write(text: String) { last = text.trim(); sent += last }
        override suspend fun readUntilPrompt(timeoutMs: Long): String {
            if (replies[last] == "TIMEOUT") throw com.kostyalke.obdscanner.elm.ElmTimeoutException("")
            return when {
                last in replies -> replies.getValue(last)
                last == "ATDPN" -> "A6"
                last == "0100" -> "7E8 06 41 00 98 3B 80 11"
                last.startsWith("AT") -> "OK"
                else -> "NO DATA"
            } + "\r"
        }
        override suspend fun clearInput() = ""
        override fun close() {}
    }

    private fun clear(reply: String): Pair<ClearResult, Scripted> = runBlocking {
        val t = Scripted(mapOf("04" to reply))
        val elm = Elm327(t) {}
        elm.initialize('6') {}
        ObdService(elm).clearDtcs() to t
    }

    @Test
    fun confirmedOnce() {
        val (r, t) = clear("7E8 01 44")
        assertEquals(ClearStatus.CONFIRMED, r.status)
        assertEquals(1, t.sent.count { it == "04" })
    }

    @Test
    fun refusedConditionsNotMet() {
        val (r, _) = clear("7E8 03 7F 04 22")
        assertEquals(ClearStatus.REFUSED, r.status)
        assertTrue(r.detail!!.contains("Заглушите"))
    }

    @Test
    fun noConfirmation() {
        assertEquals(ClearStatus.NOT_CONFIRMED, clear("NO DATA").first.status)
    }

    @Test
    fun partialWhenOneEcuRefuses() {
        assertEquals(ClearStatus.PARTIAL, clear("7E8 01 44\r7E9 03 7F 04 22").first.status)
    }

    @Test
    fun timeoutAfterSendIsNotRetried() {
        val t = Scripted(mapOf("04" to "TIMEOUT"))
        try {
            runBlocking {
                val elm = Elm327(t) {}
                elm.initialize('6') {}
                ObdService(elm).clearDtcs()
            }
            fail("таймаут после отправки не замечен")
        } catch (e: com.kostyalke.obdscanner.elm.ElmTimeoutException) { /* ожидаемо */ }
        assertEquals(1, t.sent.count { it == "04" })
    }

    // ---- Итог ----

    private val ok = ClearResult(ClearStatus.CONFIRMED)

    @Test
    fun clearedWhenConfirmedAndZero() {
        val o = ClearFlow.evaluate(ok, ControlRead(verified = true, remaining = 0))
        assertEquals(OutcomeKind.CLEARED, o.kind)
    }

    @Test
    fun remainingWhenCodesBack() {
        val o = ClearFlow.evaluate(ok, ControlRead(verified = true, remaining = 2))
        assertEquals(OutcomeKind.REMAINING, o.kind)
    }

    @Test
    fun refusedAndCodesStayIsNotCleared() {
        val o = ClearFlow.evaluate(ClearResult(ClearStatus.REFUSED, "x"), ControlRead(verified = true, remaining = 2))
        assertEquals(OutcomeKind.NOT_CLEARED, o.kind)
    }

    @Test
    fun unconfirmedButZeroIsNotPlainSuccess() {
        val o = ClearFlow.evaluate(ClearResult(ClearStatus.NOT_CONFIRMED), ControlRead(verified = true, remaining = 0))
        assertEquals(OutcomeKind.CLEARED_UNCONFIRMED, o.kind)
    }

    @Test
    fun failedControlReadIsUnverified() {
        val o = ClearFlow.evaluate(ok, ControlRead(verified = false, remaining = 0, problem = "нет связи"))
        assertEquals(OutcomeKind.UNVERIFIED, o.kind)
    }

    @Test
    fun linkLostAfterSendIsUnverified() {
        val o = ClearFlow.evaluate(ClearResult(ClearStatus.UNKNOWN, "обрыв"), ControlRead(verified = false, remaining = 0))
        assertEquals(OutcomeKind.UNVERIFIED, o.kind)
    }

    @Test
    fun permanentCodesAreExplained() {
        val o = ClearFlow.evaluate(ok, ControlRead(verified = true, remaining = 0, permanentLeft = 1))
        assertEquals(OutcomeKind.CLEARED, o.kind)
        assertTrue(o.details.any { it.contains("Постоянн") })
    }

    // ---- Подтверждение ----

    @Test
    fun confirmTextShowsIncompletenessAndSaveError() {
        val lines = ClearFlow.confirmLines(incomplete = listOf("ожидающие: блок не ответил"), savedAs = null,
            saveError = "нет места", permanent = 1)
        val text = lines.joinToString("\n")
        assertTrue(text.contains("неполн"))
        assertTrue(text.contains("НЕ сохранён") && text.contains("нет места"))
        assertTrue(text.contains("Постоянн"))
        assertTrue(!text.contains("уже сохранён"))
    }
}
