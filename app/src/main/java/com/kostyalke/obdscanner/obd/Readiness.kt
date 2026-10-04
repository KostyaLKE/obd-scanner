package com.kostyalke.obdscanner.obd

enum class MonitorState { COMPLETE, INCOMPLETE }

data class Monitor(val name: String, val state: MonitorState)

data class Readiness(
    val milOn: Boolean,
    val dtcCount: Int,
    val diesel: Boolean,
    val monitors: List<Monitor>,
) {
    companion object {
        private val SPARK = listOf(
            "Катализатор", "Подогрев катализатора", "Система улавливания паров топлива (EVAP)",
            "Вторичный воздух", "Кондиционер (A/C)", "Лямбда-зонды", "Подогрев лямбда-зондов",
            "Рециркуляция ОГ (EGR)",
        )
        private val DIESEL = listOf(
            "Катализатор NMHC", "Нейтрализатор NOx / SCR", null, "Давление наддува", null,
            "Датчики отработавших газов", "Сажевый фильтр (DPF)", "Рециркуляция ОГ (EGR) / фазы",
        )

        /** Данные PID 01 01: байты A, B, C, D. */
        fun decode(d: IntArray): Readiness? {
            if (d.size < 4) return null
            val (a, b, c, dd) = d
            val diesel = b and 0x08 != 0
            val list = ArrayList<Monitor>()
            val common = listOf("Пропуски воспламенения", "Топливная система", "Компоненты (общий контроль)")
            for (i in 0..2) {
                if (b and (1 shl i) != 0) {
                    list += Monitor(common[i], if (b and (1 shl (i + 4)) != 0) MonitorState.INCOMPLETE else MonitorState.COMPLETE)
                }
            }
            val names = if (diesel) DIESEL else SPARK
            for (i in 0..7) {
                val name = names[i] ?: continue
                if (c and (1 shl i) != 0) {
                    list += Monitor(name, if (dd and (1 shl i) != 0) MonitorState.INCOMPLETE else MonitorState.COMPLETE)
                }
            }
            return Readiness(a and 0x80 != 0, a and 0x7F, diesel, list)
        }
    }
}
