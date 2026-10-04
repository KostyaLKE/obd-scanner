package com.kostyalke.obdscanner.obd

/** Параметр текущих данных (сервис 01). Формулы — SAE J1979. */
class PidDef(
    val pid: Int,
    val name: String,
    val unit: String,
    val bytes: Int,
    val decimals: Int = 0,
    val decode: (IntArray) -> Double,
)

object Pids {
    private fun a(d: IntArray) = d[0].toDouble()
    private fun ab(d: IntArray) = (d[0] * 256 + d[1]).toDouble()
    private fun pct(d: IntArray) = d[0] * 100.0 / 255.0
    private fun trim(d: IntArray) = (d[0] - 128) * 100.0 / 128.0

    val all: List<PidDef> = listOf(
        PidDef(0x0C, "Обороты двигателя", "об/мин", 2) { ab(it) / 4 },
        PidDef(0x0D, "Скорость", "км/ч", 1) { a(it) },
        PidDef(0x05, "Температура охлаждающей жидкости", "°C", 1) { a(it) - 40 },
        PidDef(0x04, "Расчётная нагрузка двигателя", "%", 1) { pct(it) },
        PidDef(0x0B, "Давление во впускном коллекторе (наддув, абс.)", "кПа", 1) { a(it) },
        PidDef(0x33, "Атмосферное давление", "кПа", 1) { a(it) },
        PidDef(0x10, "Массовый расход воздуха (ДМРВ)", "г/с", 2, 1) { ab(it) / 100 },
        PidDef(0x0F, "Температура воздуха на впуске", "°C", 1) { a(it) - 40 },
        PidDef(0x46, "Температура наружного воздуха", "°C", 1) { a(it) - 40 },
        PidDef(0x5C, "Температура масла", "°C", 1) { a(it) - 40 },
        PidDef(0x11, "Положение дросселя / педали", "%", 1, 1) { pct(it) },
        PidDef(0x49, "Положение педали газа D", "%", 1, 1) { pct(it) },
        PidDef(0x4A, "Положение педали газа E", "%", 1, 1) { pct(it) },
        PidDef(0x45, "Относительное положение дросселя", "%", 1, 1) { pct(it) },
        PidDef(0x43, "Абсолютная нагрузка", "%", 2) { ab(it) * 100 / 255 },
        PidDef(0x0E, "Угол опережения", "°", 1, 1) { a(it) / 2 - 64 },
        PidDef(0x23, "Давление топлива в рампе", "бар", 2, 1) { ab(it) * 10 / 100 },
        PidDef(0x22, "Давление топлива в рампе (отн. разрежения)", "кПа", 2, 1) { ab(it) * 0.079 },
        PidDef(0x0A, "Давление топлива", "кПа", 1) { a(it) * 3 },
        PidDef(0x5E, "Расход топлива", "л/ч", 2, 1) { ab(it) / 20 },
        PidDef(0x2F, "Уровень топлива", "%", 1) { pct(it) },
        PidDef(0x2C, "Заданное открытие EGR", "%", 1) { pct(it) },
        PidDef(0x2D, "Отклонение EGR", "%", 1, 1) { trim(it) },
        PidDef(0x06, "Краткосрочная коррекция топлива, банк 1", "%", 1, 1) { trim(it) },
        PidDef(0x07, "Долгосрочная коррекция топлива, банк 1", "%", 1, 1) { trim(it) },
        PidDef(0x42, "Напряжение бортсети (по блоку)", "В", 2, 2) { ab(it) / 1000 },
        PidDef(0x1F, "Время с момента запуска", "с", 2) { ab(it) },
        PidDef(0x21, "Пробег с горящим Check Engine", "км", 2) { ab(it) },
        PidDef(0x4D, "Время работы с горящим Check Engine", "мин", 2) { ab(it) },
        PidDef(0x31, "Пробег после стирания ошибок", "км", 2) { ab(it) },
        PidDef(0x4E, "Время после стирания ошибок", "мин", 2) { ab(it) },
        PidDef(0x30, "Прогревов после стирания ошибок", "", 1) { a(it) },
    )

    val byId: Map<Int, PidDef> = all.associateBy { it.pid }

    /** PID, которые быстро меняются и имеют смысл на экране «Датчики». */
    val liveIds: Set<Int> = setOf(
        0x0C, 0x0D, 0x05, 0x04, 0x0B, 0x33, 0x10, 0x0F, 0x46, 0x5C, 0x11, 0x49, 0x4A, 0x45, 0x43,
        0x0E, 0x23, 0x22, 0x0A, 0x5E, 0x2F, 0x2C, 0x2D, 0x06, 0x07, 0x42, 0x1F,
    )

    fun format(def: PidDef, value: Double): String =
        if (def.decimals == 0) Math.round(value).toString()
        else "%.${def.decimals}f".format(value).replace('.', ',')

    fun obdStandard(code: Int): String = when (code) {
        1 -> "OBD-II (CARB, США)"
        2 -> "OBD (EPA, США)"
        3 -> "OBD и OBD-II"
        4 -> "OBD-I"
        5 -> "Не соответствует OBD"
        6 -> "EOBD (Европа)"
        7 -> "EOBD и OBD-II"
        8 -> "EOBD и OBD"
        9 -> "EOBD, OBD и OBD-II"
        10 -> "JOBD (Япония)"
        11 -> "JOBD и OBD-II"
        12 -> "JOBD и EOBD"
        13 -> "JOBD, EOBD и OBD-II"
        else -> "код $code"
    }
}
