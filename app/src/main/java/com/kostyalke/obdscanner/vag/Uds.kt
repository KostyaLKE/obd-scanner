package com.kostyalke.obdscanner.vag

import com.kostyalke.obdscanner.elm.ElmReply
import com.kostyalke.obdscanner.elm.ObdProtocol
import com.kostyalke.obdscanner.obd.ResponseParser

/**
 * UDS (ISO 14229) поверх ISO-TP в обычном режиме ELM327 (ATCAF1) — как OBD-II,
 * только со своим CAN-адресом блока. Работает даже на простых клонах.
 * Здесь только чтение и сброс ошибок; записи настроек нет.
 */
class Uds(private val send: suspend (String, Long) -> ElmReply) {

    private suspend fun at(cmd: String): Boolean =
        runCatching { send(cmd, 2000).lines.any { it.equals("OK", true) } }.getOrDefault(false)

    suspend fun begin() {
        for (c in listOf("ATCAF1", "ATR1", "ATAT1", "ATST64")) at(c)
    }

    /** Возврат ELM к обычному OBD-II. */
    suspend fun restore() {
        for (c in listOf("ATCAF1", "ATR1", "ATST32", "ATAT1", "ATCRA", "ATFCSM0", "ATSH7DF")) at(c)
    }

    /** Направляет запросы блоку: заголовок, фильтр ответов и flow control для длинных ответов. */
    suspend fun target(tx: Int, rx: Int) {
        if (!at("ATSH%03X".format(tx))) throw VagException("Адаптер не принял ATSH")
        at("ATCRA%03X".format(rx))
        // Иначе ELM шлёт flow control на (rx − 8), а блоку VAG нужен его адрес запроса.
        if (at("ATFCSH%03X".format(tx))) {
            at("ATFCSD300000")
            at("ATFCSM1")
        }
    }

    /** Запрос UDS; null — блок не ответил. Отказ 7F → VagException. */
    suspend fun request(vararg bytes: Int): IntArray? {
        val reply = send(bytes.joinToString("") { "%02X".format(it) }, 4000)
        if (reply.isNoData || reply.error != null) return null
        val msgs = ResponseParser.parse(reply.lines, ObdProtocol.CAN_11_500)
        val sid = bytes[0]
        // 7F xx 78 — «подождите»: берём окончательный ответ, если он пришёл в том же ответе ELM.
        val final = msgs.lastOrNull { it.data.isNotEmpty() && (it.data[0] == sid + 0x40 ||
            (it.data[0] == 0x7F && it.data.getOrNull(2) != 0x78)) } ?: return null
        if (final.data[0] == 0x7F) throw VagException("Блок ответил отказом (код 0x%02X)".format(final.data.getOrElse(2) { 0 }))
        return final.data
    }

    suspend fun readDtcs(): List<VagDtc>? {
        val r = request(0x19, 0x02, 0xFF) ?: return null
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

    /** Текст из DID (номер детали F187, название системы F197 и т.п.). */
    suspend fun readText(did: Int): String? = runCatching {
        val r = request(0x22, did shr 8, did and 0xFF) ?: return@runCatching null
        r.drop(3).takeWhile { it in 0x20..0x7E }.map { it.toChar() }.joinToString("").trim().ifEmpty { null }
    }.getOrNull()

    suspend fun readRaw(did: Int): IntArray? = runCatching {
        request(0x22, did shr 8, did and 0xFF)?.let { it.copyOfRange(3, it.size) }
    }.getOrNull()

    suspend fun clearDtcs() {
        runCatching { request(0x10, 0x03) } // расширенная сессия; часть блоков сбрасывает и без неё
        val r = request(0x14, 0xFF, 0xFF, 0xFF) ?: throw VagException("Блок не ответил на сброс")
        if (r[0] != 0x54) throw VagException("Блок не подтвердил сброс")
    }

    companion object {
        /** DID для резервной копии: номер детали, ПО, железо, название, кодировка (0x0600). */
        val BACKUP_DIDS = listOf(0xF187, 0xF189, 0xF191, 0xF197, 0x0600)

        suspend fun scanModule(uds: Uds, m: VagModule): VagModuleResult? {
            val (tx, rx) = VagModules.udsIds[m.address] ?: return null
            uds.target(tx, rx)
            return try {
                val dtcs = uds.readDtcs() ?: return null
                val part = listOfNotNull(uds.readText(0xF187), uds.readText(0xF197)).joinToString(" · ").ifEmpty { null }
                VagModuleResult(m, part, dtcs, responded = true, protocol = "UDS")
            } catch (e: VagException) {
                VagModuleResult(m, null, emptyList(), responded = true, error = e.message, protocol = "UDS")
            }
        }

        suspend fun backupModule(uds: Uds, m: VagModule): ModuleBackup? {
            val (tx, rx) = VagModules.udsIds[m.address] ?: return null
            uds.target(tx, rx)
            val recs = LinkedHashMap<Int, IntArray>()
            for (did in BACKUP_DIDS) uds.readRaw(did)?.let { recs[did] = it }
            return if (recs.isEmpty()) null else ModuleBackup(m, recs)
        }
    }
}
