package com.kostyalke.obdscanner.elm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LogLine(val time: Long, val outgoing: Boolean, val text: String)

/** Ответ адаптера: строки без эха и служебного мусора. */
data class ElmReply(val command: String, val raw: String, val lines: List<String>) {
    val error: String? = lines.firstNotNullOfOrNull { ERRORS[it.uppercase()] }
        ?: lines.firstOrNull { it.uppercase().startsWith("BUS INIT") && it.uppercase().contains("ERROR") }
            ?.let { "Шина не инициализировалась (K-line). Включите зажигание." }

    val isNoData: Boolean get() = lines.any { it.uppercase() == "NO DATA" }

    companion object {
        private val ERRORS = mapOf(
            "?" to "Адаптер не понял команду",
            "UNABLE TO CONNECT" to "Блок управления не отвечает. Включите зажигание (или заведите двигатель).",
            "CAN ERROR" to "Ошибка шины CAN",
            "BUS ERROR" to "Ошибка шины",
            "BUS BUSY" to "Шина занята",
            "FB ERROR" to "Ошибка обратной связи шины",
            "DATA ERROR" to "Ошибка данных (повреждённый ответ)",
            "BUFFER FULL" to "Переполнен буфер адаптера",
            "STOPPED" to "Запрос прерван",
            "ERROR" to "Ошибка адаптера",
            "ACT ALERT" to "Адаптер ушёл в спящий режим",
            "LV RESET" to "Адаптер перезагрузился из-за низкого напряжения",
        )
    }
}

/** Протоколы ELM327 (ответ на ATDPN). */
enum class ObdProtocol(val code: Char, val title: String, val isCan: Boolean, val is29bit: Boolean = false) {
    AUTO('0', "Автоопределение", false),
    J1850_PWM('1', "SAE J1850 PWM", false),
    J1850_VPW('2', "SAE J1850 VPW", false),
    ISO_9141('3', "ISO 9141-2 (K-line)", false),
    KWP_5BAUD('4', "ISO 14230-4 KWP2000, медленная инициализация (K-line)", false),
    KWP_FAST('5', "ISO 14230-4 KWP2000, быстрая инициализация (K-line)", false),
    CAN_11_500('6', "ISO 15765-4 CAN 11 бит, 500 кбит/с", true),
    CAN_29_500('7', "ISO 15765-4 CAN 29 бит, 500 кбит/с", true, true),
    CAN_11_250('8', "ISO 15765-4 CAN 11 бит, 250 кбит/с", true),
    CAN_29_250('9', "ISO 15765-4 CAN 29 бит, 250 кбит/с", true, true),
    J1939('A', "SAE J1939 CAN", true, true),
    USER1('B', "Пользовательский CAN 1", true),
    USER2('C', "Пользовательский CAN 2", true);

    companion object {
        fun fromDpn(reply: String): ObdProtocol {
            val s = reply.trim().uppercase()
            val c = when {
                s.length >= 2 && s[0] == 'A' -> s[1]
                s.isNotEmpty() -> s[0]
                else -> '0'
            }
            return entries.firstOrNull { it.code == c } ?: AUTO
        }
    }
}

/**
 * Низкоуровневая работа с ELM327: отправка AT/OBD-команд строго по одной,
 * чистка ответа, журнал обмена.
 */
class Elm327(
    private val transport: ObdTransport,
    private val onLog: (LogLine) -> Unit,
) {
    private val mutex = Mutex()

    var version: String = ""
        private set
    var protocol: ObdProtocol = ObdProtocol.AUTO
        private set

    suspend fun send(command: String, timeoutMs: Long = 5000): ElmReply = mutex.withLock {
        transport.clearInput()
        onLog(LogLine(System.currentTimeMillis(), true, command))
        transport.write(command + "\r")
        val raw = try {
            transport.readUntilPrompt(timeoutMs)
        } catch (e: ElmTimeoutException) {
            onLog(LogLine(System.currentTimeMillis(), false, e.partial.trim() + " ⏱ таймаут"))
            throw e
        }
        onLog(LogLine(System.currentTimeMillis(), false, raw.trim()))
        ElmReply(command, raw, cleanLines(command, raw))
    }

    /** Инициализация адаптера и поиск протокола машины. */
    suspend fun initialize(onStep: (String) -> Unit) {
        onStep("Сброс адаптера…")
        runCatching { send("ATD", 2000) }
        val z = runCatching { send("ATZ", 4000) }.getOrNull()
        delay(500)
        version = z?.lines?.firstOrNull { it.contains("ELM", true) } ?: ""
        onStep("Настройка…")
        for (cmd in listOf("ATE0", "ATL0", "ATS1", "ATH1", "ATAT1", "ATSP0")) {
            val r = send(cmd, 3000)
            if (r.lines.none { it.equals("OK", true) } && cmd != "ATE0") {
                // Некоторые клоны не знают отдельных команд — это не фатально.
                if (cmd == "ATH1") throw IllegalStateException("Адаптер не поддерживает ATH1 (заголовки)")
            }
        }
        if (version.isEmpty()) version = runCatching { send("ATI", 2000).lines.firstOrNull() }.getOrNull() ?: "?"
        onStep("Поиск протокола машины (до 20 с)…")
        val probe = send("0100", 20000)
        probe.error?.let { throw IllegalStateException(it) }
        if (probe.isNoData) throw IllegalStateException("Машина не ответила на запрос OBD. Включите зажигание.")
        protocol = ObdProtocol.fromDpn(send("ATDPN", 2000).lines.firstOrNull() ?: "")
        onStep("Протокол: ${protocol.title}")
    }

    companion object {
        fun cleanLines(command: String, raw: String): List<String> =
            raw.split('\r', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.equals(command, true) || it.replace(" ", "").equals(command.replace(" ", ""), true) }
                .filterNot { it.uppercase().startsWith("SEARCHING") }
                .filterNot { it.uppercase().startsWith("BUS INIT") && it.uppercase().endsWith("OK") }
    }
}
