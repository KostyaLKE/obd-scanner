package com.kostyalke.obdscanner

import com.kostyalke.obdscanner.elm.ElmTimeoutException
import com.kostyalke.obdscanner.elm.PromptReader
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.io.InputStream

/** Чтение ответа адаптера: таймаут, остаток буфера, предел размера, конец потока, отмена. */
class StreamIoTest {

    /** Поток из готовых кусков; [endless] — бесконечный мусор без '>'. */
    private class Chunks(vararg parts: String, val endless: Boolean = false, val eofAfter: Boolean = false) : InputStream() {
        private val q = ArrayDeque(parts.map { it.toByteArray(Charsets.US_ASCII) })
        private var cur: ByteArray? = null
        private var pos = 0
        private fun head(): ByteArray? {
            if (cur == null || pos >= cur!!.size) { cur = q.removeFirstOrNull(); pos = 0 }
            if (cur == null && endless) { cur = "4141 ".toByteArray(); pos = 0 }
            return cur
        }
        // Закрытый сокет: «готов к чтению», а read() возвращает -1.
        override fun available(): Int = head()?.let { it.size - pos } ?: if (eofAfter) 1 else 0
        override fun read(): Int = head()?.let { it[pos++].toInt() and 0xFF } ?: -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val h = head() ?: return if (eofAfter) -1 else 0
            val n = minOf(len, h.size - pos)
            System.arraycopy(h, pos, b, off, n); pos += n
            return n
        }
    }

    /** Часы, которые идут на 10 мс при каждом обращении — без реального ожидания. */
    private fun fakeClock(): () -> Long { var t = 0L; return { t += 10; t } }

    @Test(timeout = 5000)
    fun continuousGarbageTimesOut() = runBlocking {
        try {
            PromptReader(fakeClock(), maxChars = Int.MAX_VALUE).read(Chunks(endless = true), 1000)
            fail("поток без '>' не завершился таймаутом")
        } catch (e: ElmTimeoutException) { /* ожидаемо */ }
    }

    @Test(timeout = 5000)
    fun oversizedAnswerIsRejected() = runBlocking {
        try {
            PromptReader(maxChars = 4096).read(Chunks(endless = true), 60_000)
            fail("бесконечный ответ принят")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("длинн"))
        }
    }

    @Test
    fun remainderAfterPromptIsKept() = runBlocking {
        val r = PromptReader()
        val inp = Chunks("41 00 BE>OK\r>")
        assertEquals("41 00 BE", r.read(inp, 1000))
        assertEquals("OK\r", r.read(inp, 1000))
    }

    @Test
    fun clearReturnsDiscardedData() {
        val r = PromptReader()
        val inp = Chunks("STALE\r")
        assertEquals("STALE\r", r.clear(inp))
    }

    @Test(timeout = 5000)
    fun endOfStreamIsConnectionLoss() = runBlocking {
        try {
            PromptReader().read(Chunks("41 0", eofAfter = true), 60_000)
            fail("конец потока не замечен")
        } catch (e: IOException) { /* ожидаемо */ }
    }

    @Test(timeout = 5000)
    fun abortableClosesOnCancelAndTimeout() = runBlocking {
        // Таймаут: блокирующий вызов «висит», пока его не прервут закрытием.
        val latch = java.util.concurrent.CountDownLatch(1)
        var aborted = 0
        try {
            com.kostyalke.obdscanner.elm.abortable(100, { aborted++; latch.countDown() }) {
                latch.await(); throw IOException("socket closed")
            }
            fail("зависшее подключение не прервано")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Нет ответа"))
        }
        assertEquals(1, aborted)
        // Отмена: тот же механизм, закрывает только свою попытку.
        val latch2 = java.util.concurrent.CountDownLatch(1)
        var aborted2 = 0
        val job = async {
            com.kostyalke.obdscanner.elm.abortable(60_000, { aborted2++; latch2.countDown() }) {
                latch2.await(); throw IOException("socket closed")
            }
        }
        delay(100)
        job.cancel()
        runCatching { job.await() }
        assertEquals(1, aborted2)
        assertEquals("чужая попытка не должна закрываться", 1, aborted)
        // Успешный вызов не закрывается.
        var aborted3 = 0
        assertEquals(5, com.kostyalke.obdscanner.elm.abortable(1000, { aborted3++ }) { 5 })
        delay(50)
        assertEquals(0, aborted3)
    }

    @Test(timeout = 5000)
    fun readIsCancellable() = runBlocking {
        val job = async { PromptReader().read(Chunks(), 60_000) }
        delay(50)
        job.cancel()
        withTimeout(1000) { runCatching { job.await() } }
        assertTrue(job.isCancelled)
    }
}
