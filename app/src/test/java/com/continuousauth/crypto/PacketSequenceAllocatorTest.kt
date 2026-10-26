package com.continuousauth.crypto

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PacketSequenceAllocatorTest {
    @Test
    fun reservesDurablyBeforeReturningAndExtendsAtBoundary() {
        var persisted = 0L
        val reservations = mutableListOf<Long>()
        val allocator = PacketSequenceAllocator(
            { persisted },
            { persisted = it; reservations.add(it); true },
            reservationSize = 3L
        )

        repeat(7) { index ->
            val sequence = allocator.next()
            assertEquals(index + 1L, sequence)
            assertTrue(sequence <= persisted)
        }
        assertEquals(listOf(3L, 6L, 9L), reservations)
    }

    @Test
    fun repeatedInitializationDoesNotRewindOrSkipWithinProcess() {
        var persisted = 0L
        val allocator = PacketSequenceAllocator({ persisted }, { persisted = it; true })

        allocator.initialize()
        assertEquals(1L, allocator.next())
        allocator.initialize()
        allocator.initialize()
        assertEquals(2L, allocator.next())
        assertEquals(2L, allocator.current())
    }

    @Test
    fun processRestartSkipsUnusedPartOfDurableReservation() {
        var persisted = 0L
        fun process() = PacketSequenceAllocator({ persisted }, { persisted = it; true })

        val firstProcess = process()
        assertEquals(1L, firstProcess.next())
        assertEquals(2L, firstProcess.next())
        assertEquals(100L, persisted)
        val restarted = process()
        assertEquals(101L, restarted.next())
        assertEquals(200L, persisted)
    }

    @Test
    fun failedPersistenceDoesNotIssueNumberAndCanRetry() {
        var persisted = 0L
        var permitWrite = false
        val allocator = PacketSequenceAllocator(
            { persisted },
            { if (permitWrite) { persisted = it; true } else false }
        )

        try {
            allocator.next()
            fail("A number must not be returned before its reservation is durable")
        } catch (_: IllegalStateException) {
            assertEquals(0L, allocator.current())
            assertEquals(0L, persisted)
        }
        permitWrite = true
        assertEquals(1L, allocator.next())
    }

    @Test
    fun concurrentCallersReceiveUniqueNumbers() {
        var persisted = 0L
        val allocator = PacketSequenceAllocator({ persisted }, { persisted = it; true })
        val executor = Executors.newFixedThreadPool(8)
        try {
            val results = executor.invokeAll(List(8) {
                Callable { List(250) { allocator.next() } }
            }).flatMap { it.get() }
            assertEquals((1L..2000L).toList(), results.sorted())
            assertEquals(2000L, persisted)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun migrationSkipsNumbersThatLegacyVersionMayHaveIssuedWithoutSaving() {
        assertEquals(100L, PacketSequenceAllocator.migrateLegacyCheckpoint(0L))
        val floor = PacketSequenceAllocator.migrateLegacyCheckpoint(4100L)
        val allocator = PacketSequenceAllocator({ floor }, { true })
        assertEquals(4201L, allocator.next())
    }

    @Test(expected = ArithmeticException::class)
    fun sequenceOverflowFailsBeforePersistingOrReusingNumbers() {
        val allocator = PacketSequenceAllocator(
            { Long.MAX_VALUE - 10L },
            { fail("Overflow must not persist a wrapped reservation"); false }
        )
        allocator.next()
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPersistedSequenceFailsClosed() {
        PacketSequenceAllocator({ -1L }, { true }).next()
    }
}
