package com.continuousauth.buffer

import com.continuousauth.proto.DataPacket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryBufferImplTest {
    @Test
    fun streamingAndBatchConsumersShareAccuratePacketAndByteCounts() = runTest {
        val buffer = InMemoryBufferImpl()
        val packets = (1..3).map { packet(it) }
        packets.forEach { assertTrue(buffer.enqueue(it)) }
        assertEquals(packets.sumOf { it.serializedSize }.toLong(), buffer.getBufferStatus().memoryUsageBytes)

        assertEquals(packets.take(2), buffer.getDataFlow().take(2).toList())
        assertEquals(1, buffer.getSize())
        assertEquals(listOf(packets.last()), buffer.dequeue())
        assertTrue(buffer.isEmpty())
        assertEquals(3L, buffer.getBufferStatus().totalDequeued)
        assertEquals(0L, buffer.getBufferStatus().memoryUsageBytes)
    }

    @Test
    fun consumerWaitingOnAnEmptyQueueReceivesNewPacketsAfterClear() = runTest {
        val buffer = InMemoryBufferImpl()
        val received = async { buffer.getDataFlow().take(2).toList() }
        testScheduler.runCurrent()
        buffer.clear()
        buffer.enqueue(packet(1))
        testScheduler.runCurrent()
        buffer.enqueue(packet(2))
        assertEquals(listOf(packet(1), packet(2)), received.await())
        assertEquals(0, buffer.getSize())
    }

    @Test
    fun concurrentClearEnqueueAndDequeueCannotLeavePhantomOrNegativeCounts() = runTest {
        val buffer = InMemoryBufferImpl()
        (1..6).map { worker ->
            async(Dispatchers.Default) {
                repeat(500) { index ->
                    buffer.enqueue(packet(worker * 1_000 + index))
                    if (worker % 2 == 0) buffer.dequeue(3)
                    if (index % 17 == 0) buffer.clear()
                    assertTrue(buffer.getSize() >= 0)
                }
            }
        }.awaitAll()
        buffer.dequeue()
        assertEquals(0, buffer.getSize())
        assertEquals(0L, buffer.getBufferStatus().memoryUsageBytes)
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun loweringCapacityPreservesAcceptedPacketsAndRejectsNewOnesUntilDrained() = runTest {
        val buffer = InMemoryBufferImpl()
        buffer.enqueue(packet(1))
        buffer.enqueue(packet(2))
        buffer.adjustBufferSize(0.0001f)
        assertFalse(buffer.enqueue(packet(3)))
        assertEquals(listOf(packet(1), packet(2)), buffer.dequeue())
        assertTrue(buffer.enqueue(packet(3)))
        assertEquals(1, buffer.getBufferStatus().maxCapacity)
    }

    private fun packet(id: Int): DataPacket = DataPacket.newBuilder().setPacketId("packet-$id").build()
}
