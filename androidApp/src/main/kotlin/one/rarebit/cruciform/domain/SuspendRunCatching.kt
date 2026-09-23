package one.rarebit.cruciform.domain

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for coroutine code: captures every failure of [block] as a
 * [Result.failure] EXCEPT [CancellationException], which is rethrown. A plain
 * `runCatching` around a suspend call swallows cancellation — the scope is torn down
 * (the screen left, the effect re-keyed) but the coroutine carries on as if the call
 * had merely failed, and runs its failure branch (an error dialog, a navigation) on a
 * dead composition. Rethrowing lets structured concurrency finish the cancel.
 */
suspend fun <T> suspendRunCatching(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }
