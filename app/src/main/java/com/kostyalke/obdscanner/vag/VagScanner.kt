package com.kostyalke.obdscanner.vag

import com.kostyalke.obdscanner.elm.ElmReply

/**
 * Опрос всех блоков VAG: сначала TP2.0/KWP2000, затем UDS для промолчавших.
 * Вызывается внутри эксклюзивной сессии ELM; после работы адаптер возвращается к OBD-II.
 */
object VagScanner {

    suspend fun scanAll(
        send: suspend (String, Long) -> ElmReply,
        obdProtocol: Char,
        log: (String) -> Unit,
        progress: (String, Float) -> Unit,
    ): List<VagModuleResult> {
        val list = VagModules.scanList
        val found = ArrayList<VagModuleResult>()

        // 1) TP2.0. Логические адреса ≠ номерам VCDS: таблица у шлюза, без неё — известные + перебор.
        val tp = Tp20(send, obdProtocol, log)
        try {
            tp.enterRawMode()
            progress("шлюз — список блоков", 0f)
            val map = Tp20.gatewayMap(tp)
            log("VAG: таблица шлюза — " +
                (map?.entries?.joinToString(", ") { "%02X→TP%02X".format(it.key, it.value) } ?: "нет ответа"))
            val targets: List<Pair<VagModule, Int>> = if (map != null) {
                map.map { (vcds, tpA) -> VagModule(vcds, VagModules.titleFor(vcds)) to tpA }
            } else {
                VagModules.tpFallback.map { (vcds, tpA) -> VagModule(vcds, VagModules.titleFor(vcds)) to tpA } +
                    VagModules.tpProbe.filter { it !in VagModules.tpFallback.values }
                        .map { VagModule(0x100 + it, "Блок TP2.0 %02X".format(it)) to it }
            }
            targets.forEachIndexed { i, (m, tpA) ->
                progress("${m.title} (TP2.0)", 0.6f * i / targets.size)
                found += Tp20.scanModule(tp, m, tpA)
            }
        } catch (e: VagException) {
            log("VAG: TP2.0 недоступен: ${e.message}")
        } finally {
            tp.restore()
        }

        // Все блоки из списка + найденные сверх него (только ответившие).
        val merged = (list.map { m ->
            found.firstOrNull { it.module.address == m.address && it.responded } ?: VagModuleResult(m, null, emptyList(), false)
        } + found.filter { r -> r.responded && list.none { it.address == r.module.address } }).toMutableList()

        // 2) UDS для промолчавших (например, приборка на PQ25 с 2010 года, двигатель EDC17).
        val silent = merged.filter { !it.responded && VagModules.udsIds.containsKey(it.module.address) }
        if (silent.isNotEmpty()) {
            val uds = Uds(send)
            try {
                uds.begin()
                silent.forEachIndexed { i, r ->
                    progress("${r.module.title} (UDS)", 0.6f + 0.4f * i / silent.size)
                    val u = runCatching { Uds.scanModule(uds, r.module) }.getOrNull()
                    if (u != null) {
                        val idx = merged.indexOfFirst { it.module.address == u.module.address }
                        if (idx >= 0) merged[idx] = u
                    }
                }
            } finally {
                uds.restore()
            }
        }
        return merged
    }
}
