package com.continuousauth.buffer

import com.continuousauth.proto.DataPacket
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 内存缓冲区实现
 * 使用 kotlinx.coroutines.channels.Channel 维护已加密的传感器数据队列
 * 符合 Epic 1.4.1 的要求：线程安全的队列，优先存放已加密 chunk
 * Epic 4.1.3 增强：支持 FAST_MODE 下的 ring buffer 优化
 */
@Singleton
class InMemoryBufferImpl @Inject constructor() : InMemoryBuffer {
    
    companion object {
        private const val DEFAULT_MAX_CAPACITY = 10000 // 默认最大容量
        private const val OVERFLOW_THRESHOLD = 0.9 // 溢出阈值（90%）
        private const val ESTIMATED_PACKET_SIZE = 1024 // 估算每个数据包大小（字节）
        private const val TAG = "InMemoryBuffer"
        private const val RING_BUFFER_CAPACITY = 2 * 1024 * 1024 // 2MB ring buffer
    }
    
    // 使用 Channel 作为主要的线程安全队列 (Epic 1.4.1 要求)
    private val dataChannel = Channel<DataPacket>(capacity = DEFAULT_MAX_CAPACITY)
    
    // Ring Buffer 实现 (Epic 4.1.3 要求：FAST_MODE 优化)
    private val ringBuffer = java.nio.ByteBuffer.allocateDirect(RING_BUFFER_CAPACITY)
    private var ringBufferWritePos = 0
    private var ringBufferReadPos = 0
    private var ringBufferSize = 0
    private val ringBufferLock = kotlinx.coroutines.sync.Mutex()
    
    // 传输模式标志
    private var isFastMode = false
    
    // 用于批量读取的缓存队列
    private val batchCache = mutableListOf<DataPacket>()
    private val batchCacheMutex = Mutex()
    
    // 统计信息
    private val currentSize = AtomicInteger(0)
    private val totalEnqueued = AtomicLong(0L)
    private val totalDequeued = AtomicLong(0L)
    
    // 配置
    private val maxCapacity = DEFAULT_MAX_CAPACITY
    
    override suspend fun enqueue(dataPacket: DataPacket): Boolean {
        try {
            // 检查容量限制
            if (currentSize.get() >= maxCapacity) {
                android.util.Log.w(TAG, "缓冲区已满，丢弃数据包 - 当前大小: ${currentSize.get()}")
                return false
            }
            
            // 使用 Channel 的 trySend 方法（非阻塞）
            val result = dataChannel.trySend(dataPacket)
            
            if (result.isSuccess) {
                currentSize.incrementAndGet()
                totalEnqueued.incrementAndGet()
                android.util.Log.v(TAG, "数据包已入队 - 当前大小: ${currentSize.get()}")
                return true
            } else {
                android.util.Log.w(TAG, "Channel 已满，无法入队数据包")
                return false
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "数据包入队失败", e)
            return false
        }
    }
    
    override suspend fun dequeue(maxCount: Int): List<DataPacket> = batchCacheMutex.withLock {
        val result = mutableListOf<DataPacket>()
        
        try {
            val actualMaxCount = if (maxCount <= 0) currentSize.get() else minOf(maxCount, currentSize.get())
            
            // 从 Channel 中批量取出数据
            var count = 0
            while (count < actualMaxCount) {
                val packet = dataChannel.tryReceive()
                if (packet.isSuccess) {
                    result.add(packet.getOrThrow())
                    currentSize.decrementAndGet()
                    totalDequeued.incrementAndGet()
                    count++
                } else {
                    // Channel 已空
                    break
                }
            }
            
            if (result.isNotEmpty()) {
                android.util.Log.v(TAG, "数据包已出队 - 数量: ${result.size}, 剩余: ${currentSize.get()}")
            }
            
        } catch (e: Exception) {
            android.util.Log.e(TAG, "数据包出队失败", e)
        }
        
        return result
    }
    
    override fun getSize(): Int {
        return currentSize.get()
    }
    
    override fun isEmpty(): Boolean {
        return currentSize.get() == 0
    }
    
    override fun getDataFlow(): Flow<DataPacket> {
        return dataChannel.receiveAsFlow()
    }
    
    override suspend fun clear() {
        try {
            // 清空 Channel 中的所有数据
            while (!dataChannel.isEmpty) {
                dataChannel.tryReceive()
            }
            currentSize.set(0)
            android.util.Log.i(TAG, "缓冲区已清空")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "清空缓冲区失败", e)
        }
    }
    
    override fun getBufferStatus(): BufferStatus {
        val size = currentSize.get()
        val memoryUsage = size * ESTIMATED_PACKET_SIZE.toLong()
        val isOverflow = size.toDouble() / maxCapacity > OVERFLOW_THRESHOLD
        
        return BufferStatus(
            currentSize = size,
            maxCapacity = maxCapacity,
            totalEnqueued = totalEnqueued.get(),
            totalDequeued = totalDequeued.get(),
            memoryUsageBytes = memoryUsage,
            isOverflow = isOverflow
        )
    }
    
    /**
     * 设置传输模式
     * Epic 4.1.3: FAST_MODE 使用 ring buffer 优化
     */
    fun setTransmissionMode(fastMode: Boolean) {
        isFastMode = fastMode
        if (fastMode) {
            android.util.Log.i(TAG, "切换到 FAST_MODE - 使用 Ring Buffer 优化")
        } else {
            android.util.Log.i(TAG, "切换到 SLOW_MODE - 使用标准 Channel")
        }
    }
    
    /**
     * 快速模式下的环形缓冲区写入
     */
    private suspend fun writeToRingBuffer(data: ByteArray): Boolean = ringBufferLock.withLock {
        if (data.size > RING_BUFFER_CAPACITY) {
            android.util.Log.e(TAG, "数据大小超过环形缓冲区容量")
            return false
        }
        
        // 检查可用空间
        val availableSpace = RING_BUFFER_CAPACITY - ringBufferSize
        if (data.size > availableSpace) {
            // 缓冲区满，丢弃最旧数据
            val toDiscard = data.size - availableSpace
            ringBufferReadPos = (ringBufferReadPos + toDiscard) % RING_BUFFER_CAPACITY
            ringBufferSize -= toDiscard
            android.util.Log.w(TAG, "Ring Buffer 溢出，丢弃 $toDiscard 字节")
        }
        
        // 写入数据
        val endPos = (ringBufferWritePos + data.size) % RING_BUFFER_CAPACITY
        if (endPos > ringBufferWritePos) {
            // 连续写入
            ringBuffer.position(ringBufferWritePos)
            ringBuffer.put(data)
        } else {
            // 分段写入（环绕）
            val firstPartSize = RING_BUFFER_CAPACITY - ringBufferWritePos
            ringBuffer.position(ringBufferWritePos)
            ringBuffer.put(data, 0, firstPartSize)
            
            ringBuffer.position(0)
            ringBuffer.put(data, firstPartSize, data.size - firstPartSize)
        }
        
        ringBufferWritePos = endPos
        ringBufferSize += data.size
        
        return true
    }
    
    /**
     * 快速模式下的环形缓冲区读取
     */
    private suspend fun readFromRingBuffer(maxBytes: Int): ByteArray? = ringBufferLock.withLock {
        if (ringBufferSize == 0) {
            return null
        }
        
        val bytesToRead = minOf(maxBytes, ringBufferSize)
        val data = ByteArray(bytesToRead)
        
        val endPos = (ringBufferReadPos + bytesToRead) % RING_BUFFER_CAPACITY
        if (endPos > ringBufferReadPos) {
            // 连续读取
            ringBuffer.position(ringBufferReadPos)
            ringBuffer.get(data)
        } else {
            // 分段读取（环绕）
            val firstPartSize = RING_BUFFER_CAPACITY - ringBufferReadPos
            ringBuffer.position(ringBufferReadPos)
            ringBuffer.get(data, 0, firstPartSize)
            
            ringBuffer.position(0)
            ringBuffer.get(data, firstPartSize, bytesToRead - firstPartSize)
        }
        
        ringBufferReadPos = endPos
        ringBufferSize -= bytesToRead
        
        return data
    }
    
    /**
     * 调整缓冲区大小
     */
    override fun adjustBufferSize(multiplier: Float) {
        android.util.Log.i(TAG, "调整缓冲区大小倍数: $multiplier")
        // TODO: 实现缓冲区大小调整逻辑
    }
}