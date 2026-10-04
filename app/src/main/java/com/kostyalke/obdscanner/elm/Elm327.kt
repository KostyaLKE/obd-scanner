package com.kostyalke.obdscanner.elm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Понятная причина, почему не удалось подключиться: виноват адаптер или связь с машиной. */
class ConnectProblem(val stage: Stage, message: String, val tried: List<String> = emptyList()) : Exception(message) {
    enum class Stage { ADAPTER, CAR }
}

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

    /** Напряжение на разъёме OBD по данным адаптера (ATRV), если удалось прочитать. */
    var voltage: Double? = null
        private set

    /**
     * Инициализация: сначала проверяем сам адаптер, потом ищем протокол машины.
     * Если автопоиск не сработал — перебираем протоколы вручную. Для K-line включаем ATKW0:
     * блоки VAG часто присылают «нестандартные» ключевые слова, и ELM327 из-за этого
     * отвечает BUS INIT: ERROR.
     *
     * @param preferred протокол, который сработал в прошлый раз (ускоряет подключение)
     */
    suspend fun initialize(preferred: Char?, onStep: (String) -> Unit) {
        onStep("Проверка адаптера…")
        runCatching { send("ATD", 2000) }
        val z = runCatching { send("ATZ", 4000) }.getOrNull()
        delay(500)
        version = z?.lines?.firstOrNull { it.contains("ELM", true) } ?: ""
        val e0 = runCatching { send("ATE0", 3000) }.getOrNull()
        if (e0 == null || e0.lines.none { it.equals("OK", true) }) {
            val i = runCatching { send("ATI", 3000) }.getOrNull()
            if (i == null || i.lines.isEmpty()) {
                throw ConnectProblem(
                    ConnectProblem.Stage.ADAPTER,
                    "Bluetooth подключился, но адаптер не отвечает на команды.",
                )
            }
        }
        onStep("Настройка адаптера…")
        for (cmd in listOf("ATL0", "ATS1", "ATH1", "ATAT1")) {
            val r = runCatching { send(cmd, 3000) }.getOrNull()
            if (cmd == "ATH1" && (r == null || r.lines.none { it.equals("OK", true) })) {
                throw ConnectProblem(
                    ConnectProblem.Stage.ADAPTER,
                    "Адаптер не выполняет базовые команды ELM327 (ATH1). Похоже на неисправный или очень урезанный клон.",
                )
            }
        }
        if (version.isEmpty()) version = runCatching { send("ATI", 2000).lines.firstOrNull() }.getOrNull() ?: "?"
        voltage = runCatching { send("ATRV", 2000).lines.firstOrNull() }.getOrNull()?.let(::parseVoltage)

        val order = buildList {
            preferred?.takeIf { it != '0' }?.let { add(it) }
            add('0')
            addAll(listOf('5', '4', '3', '6', '8', '7', '9'))
        }.distinct()
        val tried = ArrayList<String>()
        for (code in order) {
            val p = ObdProtocol.entries.first { it.code == code }
            onStep(if (code == '0') "Автопоиск протокола машины (до 20 с)…" else "Пробую протокол: ${p.title}…")
            val kLine = code in '3'..'5'
            if (kLine) runCatching { send("ATKW0", 2000) }
            runCatching { send("ATSP$code", 2000) }
            val probe = try {
                send("0100", if (code == '0') 20000 else if (kLine) 12000 else 5000)
            } catch (e: ElmTimeoutException) {
                tried += "${p.title}: нет ответа"
                continue
            }
            val ok = probe.error == null && !probe.isNoData &&
                probe.lines.any { it.replace(" ", "").uppercase().contains("4100") }
            if (ok) {
                val dpn = runCatching { send("ATDPN", 2000).lines.firstOrNull() }.getOrNull() ?: ""
                protocol = ObdProtocol.fromDpn(dpn).takeIf { it != ObdProtocol.AUTO } ?: p
                // Закрепляем найденный протокол, чтобы адаптер не искал его заново после паузы.
                if (code == '0') runCatching { send("ATSP${protocol.code}", 2000) }
                onStep("Протокол: ${protocol.title}")
                return
            }
            tried += "${p.title}: ${probe.error ?: if (probe.isNoData) "нет данных" else "непонятный ответ"}"
        }
        throw ConnectProblem(ConnectProblem.Stage.CAR, "Адаптер работает, но машина не ответила.", tried)
    }

    companion object {
        fun parseVoltage(s: String): Double? =
            Regex("(\\d{1,2}[.,]\\d)").find(s)?.value?.replace(',', '.')?.toDoubleOrNull()

        fun cleanLines(command: String, raw: String): List<String> =
            raw.split('\r', '\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .filterNot { it.equals(command, true) || it.replace(" ", "").equals(command.replace(" ", ""), true) }
                .filterNot { it.uppercase().startsWith("SEARCHING") }
                .filterNot { it.uppercase().startsWith("BUS INIT") && it.uppercase().endsWith("OK") }
    }
}
