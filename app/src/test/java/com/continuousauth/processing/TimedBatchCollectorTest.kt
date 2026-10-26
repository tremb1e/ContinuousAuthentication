package com.continuousauth.processing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimedBatchCollectorTest {
    @Test
    fun quietInputFlushesWithoutWaitingForAnotherSample() = runTest {
        val source = Channel<Int>(Channel.UNLIMITED)
        val batches = mutableListOf<List<Int>>()
        val collector = launch {
            source.receiveAsFlow().collectTimedBatches(1_000) { batches += it }
        }
        source.send(1)
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertTrue(batches.isEmpty())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(listOf(1)), batches)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(listOf(1)), batches)
        source.send(2)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(listOf(1), listOf(2)), batches)
        source.close()
        collector.join()
    }

    @Test
    fun completionFlushesTailBeforeTheFirstTimerDeadline() = runTest {
        val batches = mutableListOf<List<Int>>()

        flowOf(1, 2, 3).collectTimedBatches(1_000) { batches += it }

        assertEquals(listOf(listOf(1, 2, 3)), batches)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun gracefulCompletionRetainsAllAcceptedSamples() = runTest {
        val expected = (1..2_000).toList()
        val received = mutableListOf<Int>()

        expected.asFlow().collectTimedBatches(1_000) {
            received.addAll(it)
            delay(10)
        }

        assertEquals(expected, received)
    }

    @Test
    fun cancellationFlushesPendingSamplesOnce() = runTest {
        val source = Channel<Int>(Channel.UNLIMITED)
        val batches = mutableListOf<List<Int>>()
        val collector = launch {
            source.receiveAsFlow().collectTimedBatches(1_000) { batches += it }
        }
        source.send(1)
        source.send(2)
        runCurrent()

        collector.cancelAndJoin()

        assertEquals(listOf(listOf(1, 2)), batches)
        source.close()
    }

    @Test
    fun cancellationDuringBatchDeliveryDoesNotDeliverItTwice() = runTest {
        val source = Channel<Int>(Channel.UNLIMITED)
        val received = mutableListOf<Int>()
        val collector = launch {
            source.receiveAsFlow().collectTimedBatches(1_000) {
                received.addAll(it)
                delay(100)
            }
        }
        source.send(1)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(1), received)

        collector.cancelAndJoin()

        assertEquals(listOf(1), received)
        source.close()
    }

    @Test
    fun cancellationDrainsSamplesAcceptedWhilePreviousBatchWasPersisting() = runTest {
        val source = Channel<Int>(Channel.UNLIMITED)
        val accepted = mutableListOf<Int>()
        val received = mutableListOf<Int>()
        val collector = launch {
            source.receiveAsFlow().onEach { accepted += it }.collectTimedBatches(1_000) {
                received.addAll(it)
                withContext(NonCancellable) { delay(100) }
            }
        }
        source.send(1)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        source.send(2)
        source.send(3)
        runCurrent()
        assertEquals(listOf(1, 2, 3), accepted)

        collector.cancelAndJoin()

        assertEquals(accepted, received)
        source.close()
    }

    @Test
    fun emptySourceDoesNotEmitEmptyBatch() = runTest {
        val batches = mutableListOf<List<Int>>()

        emptyList<Int>().asFlow().collectTimedBatches(1_000) { batches += it }

        assertTrue(batches.isEmpty())
    }

    @Test
    fun sourceFailurePropagatesAfterAcceptedSamplesAreFlushed() = runTest {
        val failure = IllegalStateException("Sensor source failed")
        val received = mutableListOf<Int>()

        val result = runCatching {
            flow {
                emit(1)
                emit(2)
                throw failure
            }.collectTimedBatches(1_000) { received.addAll(it) }
        }

        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals(listOf(1, 2), received)
    }

    @Test
    fun batchCallbackFailureIsNotRetriedAsAnotherTailBatch() = runTest {
        val failure = IllegalStateException("Batch persistence failed")
        val received = mutableListOf<Int>()

        val result = runCatching {
            flow {
                emit(1)
                awaitCancellation()
            }.collectTimedBatches(1_000) {
                received.addAll(it)
                throw failure
            }
        }

        assertEquals(failure.message, result.exceptionOrNull()?.message)
        assertEquals(listOf(1), received)
    }

    @Test
    fun cancellationBeforeSelectedSampleResumesRetainsItsValue() = runTest {
        val received = mutableListOf<Int>()
        lateinit var collector: Job
        collector = launch {
            flow {
                emit(1)
                emit(2)
                collector.cancel()
                awaitCancellation()
            }.collectTimedBatches(1_000) { received.addAll(it) }
        }

        collector.join()

        assertEquals(listOf(1, 2), received)
    }
}
