package com.kostyalke.obdscanner.obd

enum class DtcKind(val title: String, val service: Int) {
    STORED("Сохранённые (подтверждённые)", 0x03),
    PENDING("Ожидающие (текущий цикл)", 0x07),
    PERMANENT("Постоянные (не стираются сканером)", 0x0A),
}

data class Dtc(val code: String, val ecu: String, val kind: DtcKind)

object DtcDecoder {

    fun decode(a: Int, b: Int): String {
        val letter = "PCBU"[(a shr 6) and 3]
        return "%c%d%X%02X".format(letter, (a shr 4) and 3, a and 0x0F, b)
    }

    /**
     * Ответы 43/47/4A. CAN: [43, N, a1, b1, …]; K-line: [43, a1, b1, a2, b2, a3, b3] в каждой строке,
     * пустые слоты = 00 00.
     */
    fun parse(messages: List<EcuMessage>, kind: DtcKind): List<Dtc> {
        val result = LinkedHashSet<Dtc>()
        for (m in messages.forService(kind.service)) {
            val d = m.data
            // [сервис, пары…] — нечётная длина; [сервис, счётчик, пары…] (CAN) — чётная.
            var i = if (d.size % 2 == 0) 2 else 1
            while (i + 1 < d.size) {
                val a = d[i]
                val b = d[i + 1]
                if (a != 0 || b != 0) result += Dtc(decode(a, b), m.ecu, kind)
                i += 2
            }
        }
        return result.toList()
    }

    /**
     * Номер в формате VAG (5 цифр), как его показывают VCDS/VAG-COM и форумы.
     * P0xxx → 16384 + xxx, P1xxx → 16408 + 1xxx (только если в коде одни цифры).
     */
    fun vagCode(code: String): Int? {
        if (code.length != 5 || code[0] != 'P') return null
        val digits = code.substring(1)
        if (!digits.all { it.isDigit() }) return null
        val n = digits.toInt()
        return when (code[1]) {
            '0' -> 16384 + n
            '1' -> 16408 + n
            else -> null
        }
    }

    fun systemOf(code: String): String {
        val type = when (code[0]) {
            'P' -> "Двигатель/трансмиссия"
            'C' -> "Шасси"
            'B' -> "Кузов"
            'U' -> "Сеть/обмен данными"
            else -> ""
        }
        val owner = when (code[1]) {
            '0', '2' -> "стандартный код"
            '1' -> "код производителя"
            '3' -> if (code[0] == 'P' && code[2] in '4'..'9') "стандартный код" else "код производителя"
            else -> ""
        }
        val group = if (code[0] == 'P' && (code[1] == '0' || code[1] == '1' || code[1] == '2')) {
            when (code[2]) {
                '0', '1', '2' -> "топливо и воздух"
                '3' -> "зажигание / пропуски воспламенения"
                '4' -> "система снижения токсичности"
                '5' -> "скорость, холостой ход, входные сигналы"
                '6' -> "блок управления и его выходы"
                '7', '8', '9' -> "коробка передач"
                'A', 'B', 'C' -> "гибридная система"
                else -> null
            }
        } else null
        return listOfNotNull(type, owner, group).joinToString(" · ")
    }
}
