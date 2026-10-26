package com.continuousauth.processing

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** Flushes quiet streams on a timer and drains accepted samples when collection finishes. */
internal suspend fun <T> Flow<T>.collectTimedBatches(
    windowMillis: Long,
    onBatch: suspend (List<T>) -> Unit
) = coroutineScope {
    require(windowMillis > 0)
    val pending = mutableListOf<T>()
    val pendingLock = Any()
    val completed = CompletableDeferred<Throwable?>()
    val collector = launch {
        var failure: Throwable? = null
        try {
            collect { synchronized(pendingLock) { pending.add(it) } }
        } catch (error: Throwable) {
            failure = error
        } finally {
            completed.complete(failure)
        }
    }
    val ticks = Channel<Unit>(Channel.CONFLATED)
    val timer = launch {
        while (isActive) {
            delay(windowMillis)
            ticks.send(Unit)
        }
    }
    suspend fun flush() {
        val batch = synchronized(pendingLock) {
            pending.toList().also { pending.clear() }
        }
        if (batch.isEmpty()) return
        withContext(NonCancellable) { onBatch(batch) }
    }
    try {
        var finished = false
        while (!finished) {
            select<Unit> {
                ticks.onReceive { flush() }
                completed.onAwait { failure ->
                    failure?.let { throw it }
                    finished = true
                }
            }
        }
    } finally {
        withContext(NonCancellable) {
            timer.cancelAndJoin()
            collector.cancelAndJoin()
            // Samples stay in the owned buffer, never in a cancellable select handoff.
            flush()
            ticks.close()
        }
    }
}
