package com.kostyalke.obdscanner.elm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Чтение ответа ELM327 до приглашения '>'.
 * Время — монотонное (не зависит от перевода часов), дедлайн и отмена проверяются
 * на каждом шаге, даже если данные идут без перерыва. Байты после '>' не теряются.
 */
class PromptReader(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Ответ ELM длиннее этого — поток испорчен (ни одна команда столько не возвращает). */
    private val maxChars: Int = 64 * 1024,
) {
    /** Прочитано после '>' — начало следующего ответа или мусор. */
    private val rest = StringBuilder()

    suspend fun read(inp: InputStream, timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = nowMs() + timeoutMs
        if (takePrompt(sb, rest)) return sb.toString()
        val buf = ByteArray(256)
        while (true) {
            currentCoroutineContext().ensureActive()
            if (nowMs() > deadline) throw ElmTimeoutException(sb.toString())
            val available = inp.available()
            if (available > 0) {
                val n = inp.read(buf, 0, minOf(available, buf.size))
                if (n < 0) throw IOException("Адаптер закрыл соединение")
                val chunk = StringBuilder()
                for (i in 0 until n) {
                    val c = (buf[i].toInt() and 0xFF).toChar()
                    if (c != '\u0000') chunk.append(c)
                }
                if (takePrompt(sb, chunk)) return sb.toString()
                if (sb.length > maxChars) throw IOException("Адаптер прислал слишком длинный ответ без '>'")
            } else {
                delay(5)
            }
        }
    }

    /** Переносит [src] в [sb] до '>'; остаток — в [rest]. true — приглашение найдено. */
    private fun takePrompt(sb: StringBuilder, src: StringBuilder): Boolean {
        val i = src.indexOf(">")
        if (i < 0) {
            sb.append(src)
            rest.setLength(0)
            return false
        }
        sb.append(src, 0, i)
        val tail = src.substring(i + 1)
        rest.setLength(0)
        rest.append(tail)
        return true
    }

    /** Выбрасывает непрочитанное перед новой командой; возвращает выброшенное (для журнала). */
    fun clear(inp: InputStream): String {
        val out = StringBuilder(rest)
        rest.setLength(0)
        val buf = ByteArray(256)
        while (inp.available() > 0) {
            val n = inp.read(buf, 0, minOf(inp.available(), buf.size))
            if (n <= 0) break
            for (i in 0 until n) out.append((buf[i].toInt() and 0xFF).toChar())
            if (out.length > maxChars) break
        }
        return out.toString()
    }
}

/**
 * Блокирующая операция (connect сокета), которую можно прервать только закрытием ресурса.
 * При отмене корутины или по таймауту вызывает [abort] — для КОНКРЕТНОЙ попытки,
 * так что закрытие старой попытки не задевает новую.
 */
suspend fun <T> abortable(timeoutMs: Long, abort: () -> Unit, block: () -> T): T = coroutineScope {
    val finished = AtomicBoolean(false)
    val timedOut = AtomicBoolean(false)
    val watcher = launch {
        try {
            delay(timeoutMs)
            if (!finished.get()) { timedOut.set(true); abort() }
        } finally {
            // Отмена всей операции — прерываем блокирующий вызов.
            if (!finished.get() && !timedOut.get()) abort()
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: IOException) {
        // Сокет закрыли из-за отмены — это отмена, а не ошибка связи.
        currentCoroutineContext().ensureActive()
        if (timedOut.get()) throw IOException("Нет ответа за ${timeoutMs / 1000} с", e)
        throw e
    } finally {
        finished.set(true)
        watcher.cancel()
    }
}

