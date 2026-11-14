package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ElmReply
import com.kostyalke.obdscanner.elm.catching
import com.kostyalke.obdscanner.vag.VagScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Отмена операций: не глотается, а адаптер после VAG возвращается к OBD даже при отмене. */
class CancelTest {

    @Test
    fun catchingRethrowsCancellation() {
        try {
            catching { throw CancellationException("отмена") }
            fail("отмена проглочена")
        } catch (e: CancellationException) { /* ожидаемо */ }
        assertTrue(catching { error("сбой") }.isFailure)
    }

    private fun reply(cmd: String, lines: List<String>) =
        ElmReply(cmd, lines.joinToString("\r"), Elm327.cleanLines(cmd, lines.joinToString("\r")))

    /** Адаптер «всё принимает», блоки молчат. В [done] — только команды, отправка которых завершилась. */
    private class Fake(val badAt: Set<String> = emptySet()) {
        val done = ArrayList<String>()
        /** Все попытки отправки, включая прерванные отменой. */
        val attempted = ArrayList<String>()
        var attemptedAfter = -1
        var cancelAt: Int = -1
        var job: Job? = null
        var cancelledAfter = -1
    }

    private fun send(f: Fake): suspend (String, Long) -> ElmReply = { cmd, _ ->
        if (f.cancelAt > 0 && f.cancelledAfter < 0 && cmd.startsWith("ATSH200") && f.done.count { it.startsWith("ATSH200") } + 1 == f.cancelAt) {
            f.job!!.cancel()
            f.cancelledAfter = f.done.size
            f.attemptedAfter = f.attempted.size + 1
        }
        f.attempted += cmd
        yield() // точка приостановки: здесь отменённая корутина прерывается
        f.done += cmd
        reply(cmd, if (cmd.startsWith("AT")) listOf(if (cmd in f.badAt) "?" else "OK") else emptyList())
    }

    @Test
    fun cancelDuringVagScanStopsAndRestoresAdapter() = runBlocking {
        val f = Fake()
        f.cancelAt = 3 // отмена при открытии третьего блока
        val job = launch { VagScanner.scanAll(send(f), '6', {}) { _, _ -> } }
        f.job = job
        job.join()
        assertTrue(job.isCancelled)
        val after = f.done.drop(f.cancelledAfter)
        val tried = f.attempted.drop(f.attemptedAfter)
        // После отмены — ни одной попытки открыть новый блок, только возврат настроек ELM.
        assertTrue("после отмены опрос продолжился: $tried", tried.none { it.startsWith("ATSH200") || it.endsWith("C000100003011") })
        assertTrue("настройки не возвращены: $after", "ATCAF1" in after && "ATSP6" in after)
    }

    @Test
    fun failedRestoreIsReported() = runBlocking {
        val r = VagScanner.scanAll(send(Fake(badAt = setOf("ATCAF1"))), '6', {}) { _, _ -> }
        assertFalse(r.adapterRestored)
        val ok = VagScanner.scanAll(send(Fake()), '6', {}) { _, _ -> }
        assertTrue(ok.adapterRestored)
        assertEquals(ok.results.size, r.results.size)
    }

    @Test
    fun failedRestoreIsReportedEvenWhenScanThrows() = runBlocking {
        // Адаптер «завис» при открытии блока, а затем не принял ATCAF1 при возврате к OBD.
        var restoreFailed = false
        val send: suspend (String, Long) -> ElmReply = { cmd, _ ->
            if (cmd == "01C000100003011") throw com.kostyalke.obdscanner.elm.ElmTimeoutException("")
            reply(cmd, if (cmd.startsWith("AT")) listOf(if (cmd == "ATCAF1") "?" else "OK") else emptyList())
        }
        try {
            VagScanner.scanAll(send, '6', {}, onRestoreFailed = { restoreFailed = true }) { _, _ -> }
            fail("таймаут проглочен")
        } catch (e: com.kostyalke.obdscanner.elm.ElmTimeoutException) { /* ожидаемо */ }
        assertTrue("неудачный возврат настроек потерян", restoreFailed)
    }

    @Test
    fun resultsObtainedBeforeCancelAreDelivered() = runBlocking {
        val f = Fake()
        f.cancelAt = 4
        val got = ArrayList<String>()
        val job = launch { VagScanner.scanAll(send(f), '6', {}, onResult = { got += it.module.title }) { _, _ -> } }
        f.job = job
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("уже опрошенные блоки потеряны при отмене", got.isNotEmpty())
    }
}
