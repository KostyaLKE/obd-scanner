package com.kostyalke.obdscanner.elm

/** Канал до адаптера: Bluetooth или демо-эмулятор. */
interface ObdTransport {
    val name: String
    suspend fun open()
    suspend fun write(text: String)
    /** Читает ответ до приглашения '>' (без него). Бросает [ElmTimeoutException] по таймауту. */
    suspend fun readUntilPrompt(timeoutMs: Long): String
    /** Выбрасывает непрочитанные данные из входного буфера. */
    suspend fun clearInput()
    fun close()
}

class ElmTimeoutException(val partial: String) : Exception("Адаптер не ответил вовремя")
