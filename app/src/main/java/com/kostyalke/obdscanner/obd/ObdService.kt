package com.kostyalke.obdscanner.obd

import com.kostyalke.obdscanner.elm.Elm327
import com.kostyalke.obdscanner.elm.ElmReply

class ObdException(message: String) : Exception(message)

data class DtcReport(
    val time: Long,
    val stored: List<Dtc>,
    val pending: List<Dtc>,
    val permanent: List<Dtc>,
    val readiness: Readiness?,
    val freezeFrame: FreezeFrame?,
    /** Сервисы, которые машина не поддерживает (например, 0A на старых авто). */
    val notes: List<String>,
) {
    val all: List<Dtc> get() = stored + pending + permanent
}

data class FreezeFrame(val dtc: String?, val values: List<Pair<PidDef, Double>>)

data class VehicleInfo(
    val vin: String?,
    val calibrationIds: List<String>,
    val ecuName: String?,
    val obdStandard: String?,
    val protocol: String,
    val elmVersion: String,
    val voltage: String?,
)

/** Стандартная диагностика OBD-II/EOBD поверх ELM327. */
class ObdService(val elm: Elm327) {

    private val protocol get() = elm.protocol

    private suspend fun query(cmd: String, timeoutMs: Long = 5000): Pair<ElmReply, List<EcuMessage>> {
        val reply = elm.send(cmd, timeoutMs)
        return reply to ResponseParser.parse(reply.lines, protocol)
    }

    /** Битовые маски поддерживаемых PID (01 00, 01 20, …). Для сервиса 02 — с номером кадра. */
    suspend fun supportedPids(service: Int = 1): Set<Int> {
        val result = HashSet<Int>()
        var base = 0x00
        while (base <= 0xC0) {
            val cmd = if (service == 2) "02%02X00".format(base) else "%02X%02X".format(service, base)
            val (reply, msgs) = query(cmd, if (base == 0) 8000 else 5000)
            if (reply.isNoData || reply.error != null) break
            val offset = if (service == 2) 3 else 2
            val masks = msgs.forService(service).filter { it.data.size >= offset + 4 && it.data[1] == base }
            if (masks.isEmpty()) break
            var nextRange = false
            for (m in masks) {
                val bits = (m.data[offset].toLong() shl 24) or (m.data[offset + 1].toLong() shl 16) or
                    (m.data[offset + 2].toLong() shl 8) or m.data[offset + 3].toLong()
                for (n in 1..32) {
                    if ((bits shr (32 - n)) and 1L == 1L) result += base + n
                }
                if (bits and 1L == 1L) nextRange = true
            }
            if (!nextRange) break
            base += 0x20
        }
        return result
    }

    /** Значение PID сервиса 01; ответ двигателя приоритетнее остальных блоков. */
    suspend fun readPidRaw(pid: Int, bytes: Int): IntArray? {
        val (_, msgs) = query("01%02X".format(pid))
        val candidates = msgs.forService(1).filter { it.data.size >= 2 + bytes && it.data[1] == pid }
        val m = candidates.firstOrNull { it.ecu.startsWith("Двигатель") } ?: candidates.firstOrNull() ?: return null
        return m.data.copyOfRange(2, 2 + bytes)
    }

    suspend fun readPid(def: PidDef): Double? =
        readPidRaw(def.pid, def.bytes)?.let(def.decode)?.takeIf { !it.isNaN() }

    suspend fun readiness(): Readiness? = readPidRaw(0x01, 4)?.let { Readiness.decode(it) }

    private suspend fun readDtcKind(kind: DtcKind, notes: MutableList<String>): List<Dtc> {
        val (reply, msgs) = query("%02X".format(kind.service), 10000)
        if (reply.isNoData) return emptyList()
        reply.error?.let {
            if (kind == DtcKind.STORED) throw ObdException(it)
            notes += "${kind.title}: блок не поддерживает запрос"
            return emptyList()
        }
        if (msgs.any { it.data.size >= 2 && it.data[0] == 0x7F }) {
            notes += "${kind.title}: блок не поддерживает запрос"
            return emptyList()
        }
        return DtcDecoder.parse(msgs, kind)
    }

    suspend fun readDtcs(): DtcReport {
        val notes = ArrayList<String>()
        val readiness = runCatching { readiness() }.getOrNull()
        val stored = readDtcKind(DtcKind.STORED, notes)
        val pending = readDtcKind(DtcKind.PENDING, notes)
        val permanent = if (protocol.isCan) readDtcKind(DtcKind.PERMANENT, notes) else {
            notes += "Постоянные ошибки (сервис 0A) есть только на машинах с CAN — пропущено"
            emptyList()
        }
        val ff = if (stored.isNotEmpty() || pending.isNotEmpty()) runCatching { freezeFrame() }.getOrNull() else null
        return DtcReport(System.currentTimeMillis(), stored, pending, permanent, readiness, ff, notes)
    }

    suspend fun clearDtcs() {
        val (reply, msgs) = query("04", 10000)
        reply.error?.let { throw ObdException(it) }
        if (msgs.any { it.data.size >= 3 && it.data[0] == 0x7F && it.data[2] == 0x22 }) {
            throw ObdException("Блок отказал: условия не выполнены. Заглушите двигатель, зажигание оставьте включённым.")
        }
        if (msgs.forService(4).isEmpty()) {
            throw ObdException("Блок не подтвердил стирание ошибок")
        }
    }

    /** Стоп-кадр (сервис 02, кадр 0): снимок параметров в момент появления ошибки. */
    suspend fun freezeFrame(): FreezeFrame? {
        val supported = supportedPids(2)
        if (supported.isEmpty()) return null
        var dtc: String? = null
        if (0x02 in supported) {
            val (_, msgs) = query("020200")
            msgs.forService(2).firstOrNull { it.data.size >= 5 && it.data[1] == 0x02 }?.let {
                if (it.data[3] != 0 || it.data[4] != 0) dtc = DtcDecoder.decode(it.data[3], it.data[4])
            }
        }
        val values = ArrayList<Pair<PidDef, Double>>()
        for (def in Pids.all) {
            if (def.pid !in supported) continue
            val (_, msgs) = query("02%02X00".format(def.pid))
            val m = msgs.forService(2).firstOrNull { it.data.size >= 3 + def.bytes && it.data[1] == def.pid } ?: continue
            val v = def.decode(m.data.copyOfRange(3, 3 + def.bytes))
            if (!v.isNaN()) values += def to v
        }
        if (dtc == null && values.isEmpty()) return null
        return FreezeFrame(dtc, values)
    }

    /** Текст из сервиса 09 (VIN, калибровки, имя блока): отбрасываем [49, PID, №] и нули. */
    private suspend fun readInfoText(pid: Int): String? {
        val (reply, msgs) = query("09%02X".format(pid), 8000)
        if (reply.isNoData || reply.error != null) return null
        val parts = msgs.forService(9).filter { it.data.size > 3 && it.data[1] == pid }
        if (parts.isEmpty()) return null
        val sb = StringBuilder()
        for (p in parts) for (i in 3 until p.data.size) {
            val c = p.data[i]
            if (c in 0x20..0x7E) sb.append(c.toChar())
        }
        return sb.toString().trim().ifEmpty { null }
    }

    suspend fun vehicleInfo(): VehicleInfo {
        val vin = runCatching { readInfoText(0x02) }.getOrNull()
        val cal = runCatching { readInfoText(0x04) }.getOrNull()
        val ecuName = runCatching { readInfoText(0x0A) }.getOrNull()
        val std = runCatching { readPidRaw(0x1C, 1) }.getOrNull()?.let { Pids.obdStandard(it[0]) }
        val volt = runCatching { elm.send("ATRV", 2000).lines.firstOrNull() }.getOrNull()
        return VehicleInfo(
            vin = vin,
            calibrationIds = cal?.chunked(16)?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            ecuName = ecuName,
            obdStandard = std,
            protocol = protocol.title,
            elmVersion = elm.version,
            voltage = volt,
        )
    }

    suspend fun batteryVoltage(): String? = runCatching { elm.send("ATRV", 2000).lines.firstOrNull() }.getOrNull()
}
