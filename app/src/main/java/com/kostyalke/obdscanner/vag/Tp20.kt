package com.kostyalke.obdscanner.vag

import com.kostyalke.obdscanner.elm.ElmReply

/** Ошибка протокола VAG. Не IOException: это не обрыв Bluetooth, переподключаться не нужно. */
class VagException(message: String) : Exception(message)

/**
 * Ошибка блока. KWP2000: [code] — 5-значный код VAG (00283).
 * UDS: [code] — 3 байта (2 байта кода как в OBD + байт типа неисправности), [uds] = true.
 */
data class VagDtc(val code: Int, val status: Int, val uds: Boolean = false)

/** Сырые идентификационные записи блока (1A xx) — основа резервной копии кодировки. */
data class ModuleBackup(val module: VagModule, val records: Map<Int, IntArray>)

data class VagModuleResult(
    val module: VagModule,
    /** null — блок не ответил (его нет в машине или он спит). */
    val partNumber: String?,
    val dtcs: List<VagDtc>,
    val responded: Boolean,
    val error: String? = null,
    /** Каким протоколом удалось связаться: «KWP2000» (TP2.0) или «UDS». */
    val protocol: String = "KWP2000",
    /** Логический адрес TP2.0, по которому блок ответил (для сброса и копии). */
    val tpAddress: Int? = null,
)

/**
 * Протокол VAG TP2.0 поверх ELM327 (CAN 11 бит, 500 кбит/с) и KWP2000 внутри него.
 *
 * Порядок: канал открывается запросом на ID 0x200 (ответ приходит с 0x200 + адрес блока,
 * в нём CAN-ID для обмена), затем параметры канала (A0/A1), затем пакеты данных:
 * первый байт — тип и номер пакета (0x1N — последний, ждём подтверждение), подтверждение BN.
 * В первом пакете сообщения — 2 байта длины KWP-сообщения.
 *
 * ELM327 переводится в «сырой» режим (ATCAF0): мы сами собираем кадры. В конце — возврат
 * настроек для обычного OBD, даже если что-то пошло не так.
 */
class Tp20(
    private val send: suspend (String, Long) -> ElmReply,
    /** Протокол OBD, к которому вернуть адаптер после сессии (обычно '6'). */
    private val obdProtocol: Char = '6',
    private val log: (String) -> Unit = {},
) {

    private var txId = 0
    private var rxId = 0
    private var seqOut = 0
    private var seqIn = 0

    /** Каким способом адаптер переведён в «сырой» CAN — для журнала. */
    var mode: String = "?"
        private set

    private suspend fun tryAt(cmd: String): Boolean =
        runCatching { send(cmd, 2000).lines.any { it.equals("OK", true) } }.getOrDefault(false)

    private suspend fun at(cmd: String) {
        if (!tryAt(cmd)) throw VagException("Адаптер не принял $cmd")
    }

    /** Фильтр приёма: ATCRA, а если клон его не знает — ATCF + ATCM. */
    private suspend fun filter(id: Int) {
        if (tryAt("ATCRA%03X".format(id))) return
        if (tryAt("ATCF%03X".format(id)) && tryAt("ATCM7FF")) return
        throw VagException("Адаптер не умеет фильтровать CAN-кадры (ATCRA/ATCF)")
    }

    /** Кадры от блока: (CAN-ID, байты данных). */
    private fun frames(reply: ElmReply, fromId: Int): List<IntArray> {
        val id = "%03X".format(fromId)
        return reply.lines.mapNotNull { line ->
            val t = line.trim().uppercase().split(Regex("\\s+"))
            if (t.size < 2 || t[0] != id) return@mapNotNull null
            runCatching { t.drop(1).map { it.toInt(16) }.toIntArray() }.getOrNull()
        }
    }

    private fun hex(vararg b: Int) = b.joinToString("") { "%02X".format(it) }

    /**
     * «Сырой» CAN с ПЕРЕМЕННОЙ длиной кадра. В режиме ATSP6 + ATCAF0 ELM327 дополняет каждый
     * кадр нулями до 8 байт, а блоки VAG ждут кадр открытия канала ровно из 7 байт — поэтому
     * основной способ, как в открытых реализациях TP2.0: пользовательский протокол B
     * (ATPB C0 01 = 11 бит, переменная длина, без форматирования, 500 кбит/с).
     */
    suspend fun enterRawMode() {
        if (tryAt("ATPBC001") && tryAt("ATSPB")) {
            mode = "ATPB C0 01 + ATSP B"
        } else {
            at("ATSP6")
            at("ATCAF0")
            mode = if (tryAt("ATV1")) "ATSP6 + ATCAF0 + ATV1" else "ATSP6 + ATCAF0 (длина кадра 8 — может не работать)"
        }
        tryAt("ATH1")
        tryAt("ATR1")
        tryAt("ATAT0")
        tryAt("ATST19") // 100 мс на ответ при открытии канала
        log("TP2.0: режим адаптера — $mode")
    }

    /** Возврат ELM к обычному OBD-II (ошибки игнорируем — делаем всё, что получится). */
    suspend fun restore() {
        for (c in listOf("ATSP$obdProtocol", "ATCAF1", "ATV0", "ATR1", "ATST32", "ATAT1", "ATCRA", "ATSH7DF")) {
            runCatching { send(c, 2000) }
        }
    }

    /**
     * Открывает канал к блоку по его ЛОГИЧЕСКОМУ адресу TP2.0 (это не номер блока в VCDS!).
     * false — блок не ответил.
     */
    suspend fun open(tpAddr: Int): Boolean {
        at("ATSH200")
        filter(0x200 + tpAddr)
        tryAt("ATST19")
        // Суффикс «1» — ждать один ответ: ELM вернётся сразу, не дожидаясь тишины на шине.
        val r = send(hex(tpAddr, 0xC0, 0x00, 0x10, 0x00, 0x03, 0x01) + "1", 3000)
        val resp = frames(r, 0x200 + tpAddr).firstOrNull { it.size >= 6 } ?: return false
        if (resp[1] != 0xD0) throw VagException("Блок отказал в открытии канала (0x%02X)".format(resp[1]))
        rxId = resp[2] or ((resp[3] and 0x0F) shl 8)
        txId = resp[4] or ((resp[5] and 0x0F) shl 8)
        seqOut = 0
        seqIn = 0
        at("ATSH%03X".format(txId))
        filter(rxId)
        val p = send(hex(0xA0, 0x0F, 0x8A, 0xFF, 0x32, 0xFF) + "1", 3000)
        if (frames(p, rxId).none { it.isNotEmpty() && it[0] == 0xA1 }) throw VagException("Блок не принял параметры канала")
        // Дальше короткое ожидание: блок ждёт наше подтверждение не дольше ~100 мс.
        tryAt("ATST0C")
        return true
    }

    suspend fun close() {
        runCatching { send("A8", 1500) }
    }

    /**
     * Отправляет KWP-запрос (до 5 байт — один пакет) и собирает ответ.
     * Если блок ещё не прислал данные (ELM вернулся только с подтверждением), «слушаем» дальше,
     * отправляя тест канала A3 — блок отвечает A1, а ELM заодно ловит запоздавшие кадры.
     */
    suspend fun request(vararg kwp: Int): IntArray {
        val payload = intArrayOf(0x00, kwp.size) + kwp
        require(payload.size <= 7) { "длинные запросы не нужны" }
        var reply = send(hex(0x10 or seqOut, *payload), 3000)
        seqOut = (seqOut + 1) and 0x0F
        val collected = ArrayList<Int>()
        var expected = -1
        repeat(12) {
            var ackNow = false
            var moreAfterAck = false
            for (f in frames(reply, rxId)) {
                if (f.isEmpty()) continue
                val type = f[0] shr 4
                if (type > 3) continue // ACK (B), «не готов» (9), служебные A1/A3/A8
                seqIn = ((f[0] and 0x0F) + 1) and 0x0F
                val data = f.drop(1)
                if (expected < 0) {
                    if (data.size < 2) continue
                    expected = (data[0] shl 8) or data[1]
                    collected.addAll(data.drop(2))
                } else collected.addAll(data)
                if (type == 0 || type == 1) ackNow = true
                moreAfterAck = type == 0
            }
            if (expected >= 0 && collected.size >= expected) {
                if (ackNow) runCatching { send(hex(0xB0 or seqIn), 1500) }
                val msg = collected.take(expected).toIntArray()
                if (msg.size >= 3 && msg[0] == 0x7F) {
                    if (msg[2] == 0x78) { // «ответ готовится» — ждём настоящий
                        collected.clear(); expected = -1
                        reply = send("A3", 1500)
                        return@repeat
                    }
                    throw VagException("Блок ответил отказом (код 0x%02X)".format(msg[2]))
                }
                return msg
            }
            reply = if (ackNow && moreAfterAck) send(hex(0xB0 or seqIn), 1500) // блок ждёт ACK, чтобы слать дальше
            else {
                if (ackNow) runCatching { send(hex(0xB0 or seqIn), 1500) }
                send("A3", 1500)
            }
        }
        throw VagException("Блок не прислал ответ целиком")
    }

    suspend fun startSession() {
        val r = request(0x10, 0x89)
        if (r.firstOrNull() != 0x50) throw VagException("Блок не открыл диагностическую сессию")
    }

    suspend fun partNumber(): String? = runCatching {
        val r = request(0x1A, 0x9B)
        if (r.size < 3 || r[0] != 0x5A) null
        // «6R0907379AL  ESP MK60EC1» → «6R0907379AL · ESP MK60EC1»
        else r.drop(2).takeWhile { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
            .trim().split(Regex("\\s{2,}")).filter { it.isNotBlank() }.joinToString(" · ").take(48).ifEmpty { null }
    }.getOrNull()

    suspend fun readDtcs(): List<VagDtc> {
        val r = request(0x18, 0x02, 0xFF, 0x00)
        if (r.firstOrNull() != 0x58) throw VagException("Блок не отдал ошибки")
        val n = r.getOrElse(1) { 0 }
        val out = ArrayList<VagDtc>()
        var i = 2
        while (i + 2 < r.size && out.size < n) {
            val code = (r[i] shl 8) or r[i + 1]
            if (code != 0 && code != 0xFFFF) out += VagDtc(code, r[i + 2])
            i += 3
        }
        return out
    }

    suspend fun clearDtcs() {
        val r = request(0x14, 0xFF, 0x00)
        if (r.firstOrNull() != 0x54) throw VagException("Блок не подтвердил сброс")
    }

    /** Читает запись идентификации (только чтение). null — блок её не поддерживает. */
    suspend fun readIdent(id: Int): IntArray? = try {
        val r = request(0x1A, id)
        if (r.size >= 2 && r[0] == 0x5A && r[1] == id) r.copyOfRange(2, r.size) else null
    } catch (e: VagException) {
        null
    }

    /**
     * Таблица шлюза: 1A 9F → записи по 4 байта [адрес VCDS, логический адрес TP2.0, ?, статус].
     * Канал к шлюзу (0x1F) уже должен быть открыт и сессия начата.
     */
    suspend fun readGatewayMap(): Map<Int, Int> {
        val r = request(0x1A, 0x9F)
        if (r.size < 2 || r[0] != 0x5A) return emptyMap()
        val out = LinkedHashMap<Int, Int>()
        var i = 2
        while (i + 1 < r.size) {
            val vcds = r[i]
            val tp = r[i + 1]
            if (vcds != 0 && tp != 0) out[vcds] = tp
            i += 4
        }
        return out
    }

    companion object {
        /**
         * Записи, где VAG хранит номер детали, версию ПО, кодировку и код мастерской.
         * Формат зависит от блока, поэтому сохраняем их как есть и разбираем по копии с машины.
         */
        val BACKUP_IDS = listOf(0x9B, 0x9A, 0x91, 0x86, 0x9C)

        /** Резервная копия одного блока. null — блок не ответил. */
        suspend fun backupModule(tp: Tp20, m: VagModule, tpAddr: Int): ModuleBackup? {
            if (!tp.open(tpAddr)) return null
            return try {
                tp.startSession()
                val recs = LinkedHashMap<Int, IntArray>()
                for (id in BACKUP_IDS) tp.readIdent(id)?.let { recs[id] = it }
                ModuleBackup(m, recs)
            } finally {
                tp.close()
            }
        }

        /** Опрос одного блока: канал → сессия → номер детали → ошибки → закрытие. */
        suspend fun scanModule(tp: Tp20, m: VagModule, tpAddr: Int): VagModuleResult {
            val opened = try {
                tp.open(tpAddr)
            } catch (e: VagException) {
                return VagModuleResult(m, null, emptyList(), responded = true, error = e.message, tpAddress = tpAddr)
            }
            if (!opened) return VagModuleResult(m, null, emptyList(), responded = false)
            return try {
                tp.startSession()
                val part = tp.partNumber()
                val dtcs = tp.readDtcs()
                VagModuleResult(m, part, dtcs, responded = true, tpAddress = tpAddr)
            } catch (e: VagException) {
                VagModuleResult(m, null, emptyList(), responded = true, error = e.message, tpAddress = tpAddr)
            } finally {
                tp.close()
            }
        }

        /** Таблица «адрес VCDS → адрес TP2.0» от шлюза (0x1F). null — шлюз не ответил. */
        suspend fun gatewayMap(tp: Tp20): Map<Int, Int>? {
            if (!runCatching { tp.open(0x1F) }.getOrDefault(false)) return null
            return try {
                tp.startSession()
                tp.readGatewayMap().ifEmpty { null }
            } catch (e: VagException) {
                null
            } finally {
                tp.close()
            }
        }
    }
}
