package com.kostyalke.obdscanner.vag

import com.kostyalke.obdscanner.elm.ElmReply
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.kostyalke.obdscanner.elm.catching
import com.kostyalke.obdscanner.elm.ObdProtocol
import com.kostyalke.obdscanner.obd.ResponseParser

/**
 * UDS (ISO 14229) поверх ISO-TP в обычном режиме ELM327 (ATCAF1) — как OBD-II,
 * только со своим CAN-адресом блока. Работает даже на простых клонах.
 * Здесь только чтение и сброс ошибок; записи настроек нет.
 */
class Uds(private val send: suspend (String, Long) -> ElmReply) {

    private suspend fun at(cmd: String): Boolean =
        catching { send(cmd, 2000).lines.any { it.equals("OK", true) } }.getOrDefault(false)

    suspend fun begin() {
        for (c in listOf("ATCAF1", "ATR1", "ATAT1", "ATST64")) at(c)
    }

    /** Возврат ELM к обычному OBD-II, в том числе при отмене (см. [Tp20.restore]). false — не вернулся. */
    suspend fun restore(): Boolean = withContext(NonCancellable) {
        withTimeoutOrNull(Tp20.RESTORE_TIMEOUT_MS) {
            val ok = listOf("ATCAF1", "ATR1", "ATST32", "ATAT1", "ATCRA", "ATFCSM0", "ATSH7DF").associateWith { at(it) }
            val filterOk = ok.getValue("ATCRA") || (at("ATCF000") && at("ATCM000"))
            ok.getValue("ATCAF1") && ok.getValue("ATSH7DF") && filterOk
        } ?: false
    }

    /** CAN-ID ответа текущего блока: ответы с других ID не принимаем. */
    private var rxHeader: String? = null

    /** Направляет запросы блоку: заголовок, фильтр ответов и flow control для длинных ответов. */
    suspend fun target(tx: Int, rx: Int) {
        rxHeader = null
        if (!at("ATSH%03X".format(tx))) throw VagException("Адаптер не принял ATSH", VagErrorKind.ADAPTER)
        if (!at("ATCRA%03X".format(rx)) && !(at("ATCF%03X".format(rx)) && at("ATCM7FF"))) {
            throw VagException("Адаптер не принял фильтр ответов (ATCRA/ATCF) — опрос блока невозможен", VagErrorKind.ADAPTER)
        }
        // Иначе ELM шлёт flow control на (rx − 8), а блоку VAG нужен его адрес запроса.
        // Без ATFCSH (старые клоны) работаем как есть; начатую настройку доводим до конца.
        if (at("ATFCSH%03X".format(tx))) {
            if (!at("ATFCSD300000") || !at("ATFCSM1")) throw VagException("Адаптер не принял настройку flow control", VagErrorKind.ADAPTER)
        }
        rxHeader = "%03X".format(rx)
    }

    /**
     * Запрос UDS. null — блок не ответил (NO DATA). Принимается только полный ответ
     * с CAN-ID блока на этот же сервис; отказ 7F на этот сервис → VagException.
     * 7F xx 78 («ответ готовится»): чтение повторяется, а команды с последствиями
     * ([retryPending] = false, например сброс) — нет: результат считается неподтверждённым.
     */
    suspend fun request(vararg bytes: Int, retryPending: Boolean = true): IntArray? {
        val header = rxHeader ?: throw VagException("Блок не выбран")
        val sid = bytes[0]
        repeat(PENDING_RETRIES + 1) {
            val reply = send(bytes.joinToString("") { "%02X".format(it) }, 4000)
            if (reply.isNoData) return null
            reply.error?.let { throw VagException("Ошибка адаптера: $it", VagErrorKind.ADAPTER) }
            val all = ResponseParser.parse(reply.lines, ObdProtocol.CAN_11_500)
            val msgs = all.filter { it.header == header }
            if (all.isNotEmpty() && msgs.isEmpty()) throw VagException("Ответ пришёл не от запрошенного блока")
            if (msgs.any { it.incomplete }) throw VagException("Ответ блока повреждён или неполный")
            msgs.lastOrNull { it.data.isNotEmpty() && it.data[0] == sid + 0x40 }?.let { return it.data }
            val neg = msgs.filter { it.data.size >= 3 && it.data[0] == 0x7F && it.data[1] == sid }
            neg.lastOrNull { it.data[2] != 0x78 }?.let {
                throw VagException("Блок ответил отказом (код 0x%02X)".format(it.data[2]), VagErrorKind.REFUSED)
            }
            if (neg.isEmpty()) throw VagException("Непонятный ответ блока")
            if (!retryPending) throw VagException("Блок принял команду, но не сообщил итог — результат не подтверждён")
            delay(PENDING_DELAY_MS)
        }
        throw VagException("Блок так и не прислал итог")
    }

    suspend fun readDtcs(): List<VagDtc>? {
        val r = request(0x19, 0x02, 0xFF) ?: return null
        // 59 02 <маска> + по 4 байта на ошибку
        if (r.size < 3 || r[1] != 0x02 || (r.size - 3) % 4 != 0) {
            throw VagException("Ответ со списком ошибок повреждён")
        }
        val out = ArrayList<VagDtc>()
        var i = 3
        while (i + 3 < r.size) {
            val code = (r[i] shl 16) or (r[i + 1] shl 8) or r[i + 2]
            val status = r[i + 3]
            if (code != 0 && status and 0x0D != 0) out += VagDtc(code, status, uds = true)
            i += 4
        }
        return out
    }

    /** Данные DID; ответ с другим DID не принимается. */
    private suspend fun readDid(did: Int): IntArray? {
        val r = request(0x22, did shr 8, did and 0xFF) ?: return null
        if (r.size < 3 || r[1] != did shr 8 || r[2] != did and 0xFF) throw VagException("Блок вернул другой DID")
        return r.copyOfRange(3, r.size)
    }

    /**
     * Необязательный DID: нет ответа, отказ или чужой/повреждённый ответ — null.
     * Обрыв связи и таймаут адаптера не прячем: это не «нет номера детали».
     */
    private suspend fun optionalDid(did: Int): IntArray? = try {
        readDid(did)
    } catch (e: VagException) {
        null
    }

    /** Текст из DID (номер детали F187, название системы F197 и т.п.). */
    suspend fun readText(did: Int): String? =
        optionalDid(did)?.takeWhile { it in 0x20..0x7E }?.map { it.toChar() }?.joinToString("")?.trim()?.ifEmpty { null }

    suspend fun readRaw(did: Int): IntArray? = optionalDid(did)

    suspend fun clearDtcs() {
        // Расширенная сессия; часть блоков сбрасывает и без неё. Обрыв связи — наверх.
        try { request(0x10, 0x03) } catch (e: VagException) { }
        request(0x14, 0xFF, 0xFF, 0xFF, retryPending = false) ?: throw VagException("Блок не ответил на сброс")
    }

    companion object {
        /** DID для резервной копии: номер детали, ПО, железо, название, кодировка (0x0600). */
        val BACKUP_DIDS = listOf(0xF187, 0xF189, 0xF191, 0xF197, 0x0600)
        const val PENDING_RETRIES = 3
        const val PENDING_DELAY_MS = 500L

        suspend fun scanModule(uds: Uds, m: VagModule): VagModuleResult? {
            val (tx, rx) = VagModules.udsIds[m.address] ?: return null
            return try {
                uds.target(tx, rx)
                val dtcs = uds.readDtcs() ?: return null
                val part = listOfNotNull(uds.readText(0xF187), uds.readText(0xF197)).joinToString(" · ").ifEmpty { null }
                VagModuleResult(m, part, dtcs, responded = true, protocol = "UDS")
            } catch (e: VagException) {
                VagModuleResult(m, null, emptyList(), responded = true, error = e.message, errorKind = e.kind, protocol = "UDS")
            }
        }

        suspend fun backupModule(uds: Uds, m: VagModule): ModuleBackup? {
            val (tx, rx) = VagModules.udsIds[m.address] ?: return null
            uds.target(tx, rx)
            val recs = LinkedHashMap<Int, IntArray>()
            for (did in BACKUP_DIDS) uds.readRaw(did)?.let { recs[did] = it }
            val part = recs[0xF187]?.takeWhile { it in 0x20..0x7E }?.map { it.toChar() }?.joinToString("")?.trim()?.ifEmpty { null }
            return ModuleBackup(m, recs, "UDS", canIds = tx to rx, requested = BACKUP_DIDS, partNumber = part)
        }
    }
}
