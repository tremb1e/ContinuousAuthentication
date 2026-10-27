package com.continuousauth.buffer

import com.continuousauth.proto.DataPacket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/** A bounded upload queue shared by batch and streaming consumers. */
@Singleton
class InMemoryBufferImpl @Inject constructor() : InMemoryBuffer {
    private companion object {
        const val DEFAULT_MAX_CAPACITY = 10_000
        const val MAX_CAPACITY = 50_000
        const val OVERFLOW_THRESHOLD = 0.9
    }

    private val lock = Any()
    private val packets = ArrayDeque<DataPacket>()
    private val changes = MutableStateFlow(0L)
    private var maxCapacity = DEFAULT_MAX_CAPACITY
    private var totalEnqueued = 0L
    private var totalDequeued = 0L
    private var memoryUsageBytes = 0L

    override suspend fun enqueue(dataPacket: DataPacket): Boolean = synchronized(lock) {
        if (packets.size >= maxCapacity) return@synchronized false
        packets.addLast(dataPacket)
        memoryUsageBytes += dataPacket.serializedSize
        totalEnqueued++
        changes.value++
        true
    }

    override suspend fun dequeue(maxCount: Int): List<DataPacket> = synchronized(lock) {
        val count = if (maxCount <= 0) packets.size else minOf(maxCount, packets.size)
        List(count) { removeFirst() }
    }

    override fun getSize(): Int = synchronized(lock) { packets.size }

    override fun isEmpty(): Boolean = synchronized(lock) { packets.isEmpty() }

    override fun getDataFlow(): Flow<DataPacket> = flow {
        while (currentCoroutineContext().isActive) {
            // Read the generation before checking the queue so an enqueue between the
            // check and suspension cannot be missed. All consumers use the same accounting.
            val observedChange = changes.value
            val packet = synchronized(lock) {
                if (packets.isEmpty()) null else removeFirst()
            }
            if (packet == null) {
                changes.first { it != observedChange }
            } else {
                emit(packet)
            }
        }
    }

    override suspend fun clear() = synchronized(lock) {
        packets.clear()
        memoryUsageBytes = 0L
    }

    override fun getBufferStatus(): BufferStatus = synchronized(lock) {
        BufferStatus(
            currentSize = packets.size,
            maxCapacity = maxCapacity,
            totalEnqueued = totalEnqueued,
            totalDequeued = totalDequeued,
            memoryUsageBytes = memoryUsageBytes,
            isOverflow = packets.size.toDouble() / maxCapacity > OVERFLOW_THRESHOLD
        )
    }

    /** Kept for legacy callers; both modes use the same encrypted packet queue. */
    fun setTransmissionMode(fastMode: Boolean) = Unit

    override fun adjustBufferSize(multiplier: Float) {
        require(multiplier.isFinite() && multiplier > 0f) { "缓冲区大小倍数必须为有限正数" }
        synchronized(lock) {
            maxCapacity = (DEFAULT_MAX_CAPACITY * multiplier).toInt().coerceIn(1, MAX_CAPACITY)
            // Shrinking never discards accepted packets; producers wait for the queue to drain.
        }
    }

    private fun removeFirst(): DataPacket = packets.removeFirst().also {
        memoryUsageBytes -= it.serializedSize
        totalDequeued++
    }
}
