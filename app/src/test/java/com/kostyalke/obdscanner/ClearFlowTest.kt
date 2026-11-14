package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ObdTransport
import com.kostyalke.obdscanner.obd.ClearFlow
import com.kostyalke.obdscanner.obd.ClearResult
import com.kostyalke.obdscanner.obd.ClearStatus
import com.kostyalke.obdscanner.obd.ControlRead
import com.kostyalke.obdscanner.obd.ObdService
import com.kostyalke.obdscanner.obd.OutcomeKind
import com.kostyalke.obdscanner.obd.ClearOutcome
import kotlinx.coroutines.launch
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
    fun answersOfOtherEcusAreIgnoredWhenOnlyEngineIsAddressed() {
        // Команда адресована двигателю: отказ постороннего блока к ней не относится.
        assertEquals(ClearStatus.CONFIRMED, clear("7E8 01 44\r7E9 03 7F 04 22").first.status)
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

    // ---- Только двигатель и сравнение ----

    @Test
    fun clearGoesToEngineOnlyOnCan() {
        val (r, t) = clear("7E8 01 44")
        assertEquals(ClearStatus.CONFIRMED, r.status)
        val i = t.sent.indexOf("04")
        assertEquals("ATSH7E0", t.sent[i - 1])
        assertEquals("заголовок не возвращён к общему", "ATSH7DF", t.sent[i + 1])
    }

    @Test
    fun clearNotSentIfEngineAddressRejected() {
        val t = Scripted(mapOf("ATSH7E0" to "?", "04" to "7E8 01 44"))
        val r = runBlocking {
            val elm = Elm327(t) {}
            elm.initialize('6') {}
            ObdService(elm).clearDtcs()
        }
        assertEquals(ClearStatus.NOT_CONFIRMED, r.status)
        assertTrue("сброс ушёл на общий адрес", "04" !in t.sent)
    }

    @Test
    fun compareShowsGoneStayedNew() {
        val c = ClearFlow.compare(listOf("P0401", "P1557"), listOf("P1557", "P0234"))
        assertEquals(listOf("P0401"), c.gone)
        assertEquals(listOf("P1557"), c.stayed)
        assertEquals(listOf("P0234"), c.appeared)
        val o = ClearFlow.evaluate(ok, ControlRead(verified = true, remaining = 2), c)
        assertTrue(o.details.any { it.contains("Исчезли: P0401") })
        assertTrue(o.details.any { it.contains("Появились: P0234") })
    }

    // ---- Замечания ревью 14.11: строгое подтверждение и полный сценарий ----

    @Test
    fun confirmationFromOtherEcuIsNotAccepted() {
        // Команда ушла двигателю (7E0) — «44» от другого блока подтверждением не считается.
        assertEquals(ClearStatus.NOT_CONFIRMED, clear("7E9 01 44").first.status)
    }

    @Test
    fun truncatedConfirmationIsNotAccepted() {
        assertEquals(ClearStatus.NOT_CONFIRMED, clear("7E8 05 44").first.status)
    }

    private class Run(
        val cmd: suspend () -> ClearResult = { ClearResult(ClearStatus.CONFIRMED) },
        val settle: suspend () -> Unit = {},
        val read: suspend () -> com.kostyalke.obdscanner.obd.AfterRead? = {
            com.kostyalke.obdscanner.obd.AfterRead(ControlRead(true, 0), emptyList(), "тело отчёта")
        },
        val saveError: String? = null,
    ) {
        var sent = 0
        val saved = ArrayList<Pair<ClearOutcome, com.kostyalke.obdscanner.obd.AfterRead?>>()
        var published: ClearOutcome? = null
        suspend fun go() = ClearFlow.run(
            beforeCodes = listOf("P0401"),
            send = { sent++; cmd() },
            settle = settle,
            read = read,
            save = { o, a -> saved += o to a; saveError },
            publish = { published = it },
        )
    }

    @Test
    fun fullScenarioSendsOnceSavesAndPublishes() = runBlocking {
        val r = Run()
        r.go()
        assertEquals(1, r.sent)
        assertEquals(1, r.saved.size)
        assertEquals(OutcomeKind.CLEARED, r.published!!.kind)
        assertTrue(r.published!!.details.any { it.contains("Исчезли: P0401") })
    }

    @Test
    fun linkLossAfterSendStillSavesUnknownResult() = runBlocking {
        // Переподключение отменяет операцию во время паузы перед контрольным чтением.
        var job: kotlinx.coroutines.Job? = null
        val r = Run(settle = { job!!.cancel(); kotlinx.coroutines.yield() })
        job = launch { r.go() }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals("отчёт «после» не сохранён", 1, r.saved.size)
        assertEquals(OutcomeKind.UNVERIFIED, r.saved[0].first.kind)
        assertEquals(OutcomeKind.UNVERIFIED, r.published!!.kind)
        assertEquals(1, r.sent)
    }

    @Test
    fun cancelDuringCommandIsSavedAsUnknown() = runBlocking {
        var job: kotlinx.coroutines.Job? = null
        val r = Run(cmd = { job!!.cancel(); kotlinx.coroutines.yield(); ClearResult(ClearStatus.CONFIRMED) })
        job = launch { r.go() }
        job.join()
        assertEquals(1, r.saved.size)
        assertTrue(r.saved[0].first.details.any { it.contains("неизвестно") })
    }

    @Test
    fun errorInControlReadDoesNotCrashAndIsUnverified() = runBlocking {
        val r = Run(read = { throw com.kostyalke.obdscanner.elm.ElmTimeoutException("") })
        r.go()
        assertEquals(OutcomeKind.UNVERIFIED, r.published!!.kind)
        assertEquals(1, r.saved.size)
    }

    @Test
    fun errorInCommandIsUnknownNotCrash() = runBlocking {
        val r = Run(cmd = { throw java.io.IOException("обрыв") }, read = { null })
        r.go()
        assertEquals(OutcomeKind.UNVERIFIED, r.published!!.kind)
        assertEquals(1, r.sent)
    }

    @Test
    fun saveErrorIsShownInOutcome() = runBlocking {
        val r = Run(saveError = "нет места")
        r.go()
        assertTrue(r.published!!.details.any { it.contains("НЕ сохранён") && it.contains("нет места") })
    }
}
