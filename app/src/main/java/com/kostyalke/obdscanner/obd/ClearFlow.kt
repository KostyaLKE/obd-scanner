package com.kostyalke.obdscanner.obd

/** Что ответили блоки на команду сброса. */
enum class ClearStatus(val text: String) {
    CONFIRMED("блок подтвердил сброс"),
    PARTIAL("часть блоков отказала"),
    REFUSED("блок отказал"),
    NOT_CONFIRMED("блок не подтвердил сброс"),
    /** Команда ушла, а связь прервалась или адаптер не ответил: выполнена ли она — неизвестно. */
    UNKNOWN("неизвестно, выполнен ли сброс"),
}

data class ClearResult(val status: ClearStatus, val detail: String? = null) {
    val text: String get() = status.text + (detail?.let { " ($it)" } ?: "")
}

/** Контрольное чтение после сброса. [verified] = false — прочитать достоверно не удалось. */
data class ControlRead(
    val verified: Boolean,
    /** Сохранённые + ожидающие (то, что сброс должен стереть). */
    val remaining: Int,
    val permanentLeft: Int = 0,
    val problem: String? = null,
)

/** Коды до и после сброса. */
data class CodeDiff(val gone: List<String>, val stayed: List<String>, val appeared: List<String>)

enum class OutcomeKind { CLEARED, CLEARED_UNCONFIRMED, REMAINING, NOT_CLEARED, UNVERIFIED }

data class ClearOutcome(val kind: OutcomeKind, val title: String, val details: List<String>)

/** Итог сброса по фактам: ответ на команду + контрольное чтение. Не по факту отправки. */
object ClearFlow {

    private const val PERMANENT_NOTE =
        "Постоянные ошибки сканер не стирает: блок удалит их сам после нескольких поездок без неисправности."

    fun compare(before: List<String>, after: List<String>): CodeDiff {
        val b = before.distinct()
        val a = after.distinct()
        return CodeDiff(b.filter { it !in a }, b.filter { it in a }, a.filter { it !in b })
    }

    fun evaluate(cmd: ClearResult, check: ControlRead, diff: CodeDiff? = null): ClearOutcome {
        val details = buildList {
            add("Команда: ${cmd.text}")
            add("Контрольное чтение: " + when {
                !check.verified -> "не удалось" + (check.problem?.let { " — $it" } ?: "")
                check.remaining == 0 -> "ошибок нет"
                else -> "ошибок ${check.remaining}"
            })
            // Сравнение — только по достоверному контрольному чтению.
            if (diff != null && check.verified) {
                if (diff.gone.isNotEmpty()) add("Исчезли: " + diff.gone.joinToString(", "))
                if (diff.stayed.isNotEmpty()) add("Остались: " + diff.stayed.joinToString(", "))
                if (diff.appeared.isNotEmpty()) add("Появились: " + diff.appeared.joinToString(", "))
            }
            if (check.permanentLeft > 0) add(PERMANENT_NOTE)
        }
        val confirmed = cmd.status == ClearStatus.CONFIRMED || cmd.status == ClearStatus.PARTIAL
        return when {
            check.remaining > 0 && !confirmed -> ClearOutcome(OutcomeKind.NOT_CLEARED, "Ошибки не стёрты", details)
            check.remaining > 0 -> ClearOutcome(OutcomeKind.REMAINING,
                "Ошибки остались или сразу вернулись: ${check.remaining}", details)
            !check.verified -> ClearOutcome(OutcomeKind.UNVERIFIED, "Результат сброса не проверен", details)
            confirmed -> ClearOutcome(OutcomeKind.CLEARED,
                if (cmd.status == ClearStatus.PARTIAL) "Ошибок нет, но часть блоков отказала" else "Сброс подтверждён, ошибок нет",
                details)
            else -> ClearOutcome(OutcomeKind.CLEARED_UNCONFIRMED, "Ошибок не видно, но блок не подтвердил сброс", details)
        }
    }

    /** Текст подтверждения: честно о неполноте данных и о том, сохранён ли отчёт. */
    fun confirmLines(incomplete: List<String>, savedAs: String?, saveError: String?, permanent: Int): List<String> = buildList {
        // Сначала то, что важно увидеть до нажатия: неполнота и судьба отчёта.
        if (incomplete.isNotEmpty()) add("Внимание: диагностика неполная — " + incomplete.joinToString("; ") + ".")
        when {
            saveError != null -> add("Отчёт до сброса НЕ сохранён: $saveError. Сбросив сейчас, вы потеряете эти данные.")
            savedAs != null -> add("Отчёт до сброса сохранён в истории: $savedAs.")
        }
        if (permanent > 0) add(PERMANENT_NOTE)
        add("Заглушите двигатель, зажигание оставьте включённым.")
        add("Вместе с ошибками сотрутся стоп-кадры и готовность самопроверок. " +
            "Если неисправность не устранена, ошибка вернётся.")
    }
}
