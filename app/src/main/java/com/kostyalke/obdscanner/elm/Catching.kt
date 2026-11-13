package com.kostyalke.obdscanner.elm

import kotlin.coroutines.cancellation.CancellationException

/**
 * Как runCatching, но отмену корутины не глотает: иначе отменённая операция
 * продолжает работать с адаптером и пишет результат на экран.
 */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}
