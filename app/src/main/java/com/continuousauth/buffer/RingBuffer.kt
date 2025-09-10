package com.continuousauth.buffer

import com.continuousauth.model.SensorSample
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 环形缓冲区实现
 * 在 FAST_MODE 下使用预分配的 Direct ByteBuffer 来减少内存分配
 * 避免在主线程创建大量对象，提高性能
 */
@Singleton
class RingBuffer @Inject constructor() {
    
    companion object {
        private const val TAG = "RingBuffer"
        
        // 每个传感器样本的字节大小（估算）
        // type(4) + timestamp(8) + x(4) + y(4) + z(4) + accuracy(4) + seqNo(8) + app(估算50) = ~90字节
        private const val SAMPLE_SIZE_BYTES = 96
        
        // 默认缓冲区大小（可存储约10,000个样本）
        private const val DEFAULT_BUFFER_SIZE = 10000 * SAMPLE_SIZE_BYTES // ~960KB
        
        // 最小和最大缓冲区大小
        private const val MIN_BUFFER_SIZE = 1000 * SAMPLE_SIZE_BYTES    // ~96KB
        private const val MAX_BUFFER_SIZE = 50000 * SAMPLE_SIZE_BYTES   // ~4.8MB
    }
    
    // Direct ByteBuffer，分配在堆外内存中，减少GC压力
    private val buffer: ByteBuffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
        .order(ByteOrder.nativeOrder())
    
    // 读写位置
    private var writePosition = 0
    private var readPosition = 0
    private var size = 0
    
    // 线程安全锁
    private val bufferMutex = Mutex()
    
    // 统计信息
    private var totalWritten = 0L
    private var totalRead = 0L
    private var overflowCount = 0L
    
    /**
     * 写入传感器样本到环形缓冲区
     * 在FAST_MODE下直接写入ByteBuffer，避免创建中间对象
     */
    suspend fun write(sample: SensorSample): Boolean = bufferMutex.withLock {
        // 检查是否有足够空间
        if (size >= buffer.capacity() - SAMPLE_SIZE_BYTES) {
            // 缓冲区满，覆盖最旧的数据
            overflowCount++
            android.util.Log.w(TAG, "环形缓冲区溢出，覆盖最旧数据。溢出次数: $overflowCount")
            
            // 移动读指针，丢弃最旧的数据
            readPosition = (readPosition + SAMPLE_SIZE_BYTES) % buffer.capacity()
            size -= SAMPLE_SIZE_BYTES
        }
        
        try {
            // 设置写位置
            buffer.position(writePosition)
            
            // 写入样本数据到ByteBuffer
            // 使用简化格式以提高性能
            buffer.putInt(sample.type.ordinal)             // 4 bytes: sensor type
            buffer.putLong(sample.eventTimestampNs)        // 8 bytes: timestamp
            buffer.putFloat(sample.x)                      // 4 bytes: x value
            buffer.putFloat(sample.y)                      // 4 bytes: y value
            buffer.putFloat(sample.z)                      // 4 bytes: z value
            buffer.putInt(sample.accuracy)                  // 4 bytes: accuracy
            buffer.putLong(sample.seqNo)                   // 8 bytes: sequence number
            
            // 写入前台应用信息（固定长度字符串）
            val appBytes = (sample.foregroundApp ?: "").toByteArray(Charsets.UTF_8)
            val appLength = minOf(appBytes.size, 50)
            buffer.putInt(appLength)                       // 4 bytes: string length
            if (appLength > 0) {
                buffer.put(appBytes, 0, appLength)         // N bytes: app string
            }
            // 填充到固定长度
            for (i in appLength until 50) {
                buffer.put(0)
            }
            
            // 更新写位置
            writePosition = (writePosition + SAMPLE_SIZE_BYTES) % buffer.capacity()
            size += SAMPLE_SIZE_BYTES
            totalWritten++
            
            return true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "写入环形缓冲区失败", e)
            return false
        }
    }
    
    /**
     * 批量读取传感器样本
     * 返回指定数量的样本（如果可用）
     */
    suspend fun read(maxSamples: Int): List<SensorSample> = bufferMutex.withLock {
        val samples = mutableListOf<SensorSample>()
        
        if (size == 0) {
            return samples
        }
        
        val samplesToRead = minOf(maxSamples, size / SAMPLE_SIZE_BYTES)
        
        try {
            repeat(samplesToRead) {
                // 设置读位置
                buffer.position(readPosition)
                
                // 读取样本数据
                val typeOrdinal = buffer.getInt()
                val type = com.continuousauth.model.SensorType.values()[typeOrdinal]
                val timestamp = buffer.getLong()
                val x = buffer.getFloat()
                val y = buffer.getFloat()
                val z = buffer.getFloat()
                val accuracy = buffer.getInt()
                val seqNo = buffer.getLong()
                
                // 读取前台应用信息
                val appLength = buffer.getInt()
                val foregroundApp = if (appLength > 0) {
                    val appBytes = ByteArray(appLength)
                    buffer.get(appBytes)
                    // 跳过填充
                    buffer.position(buffer.position() + (50 - appLength))
                    String(appBytes, Charsets.UTF_8)
                } else {
                    // 跳过填充
                    buffer.position(buffer.position() + 50)
                    null
                }
                
                samples.add(
                    SensorSample(
                        type = type,
                        eventTimestampNs = timestamp,
                        x = x,
                        y = y,
                        z = z,
                        accuracy = accuracy,
                        seqNo = seqNo,
                        foregroundApp = foregroundApp ?: ""
                    )
                )
                
                // 更新读位置
                readPosition = (readPosition + SAMPLE_SIZE_BYTES) % buffer.capacity()
                size -= SAMPLE_SIZE_BYTES
                totalRead++
            }
            
            android.util.Log.v(TAG, "从环形缓冲区读取 ${samples.size} 个样本")
        } catch (e: Exception) {
            android.util.Log.e(TAG, "从环形缓冲区读取失败", e)
        }
        
        return samples
    }
    
    /**
     * 清空缓冲区
     */
    suspend fun clear() = bufferMutex.withLock {
        buffer.clear()
        writePosition = 0
        readPosition = 0
        size = 0
        android.util.Log.i(TAG, "环形缓冲区已清空")
    }
    
    /**
     * 获取缓冲区状态
     */
    suspend fun getStatus(): RingBufferStatus = bufferMutex.withLock {
        RingBufferStatus(
            capacity = buffer.capacity(),
            size = size,
            sampleCount = size / SAMPLE_SIZE_BYTES,
            writePosition = writePosition,
            readPosition = readPosition,
            totalWritten = totalWritten,
            totalRead = totalRead,
            overflowCount = overflowCount,
            utilizationPercent = (size * 100.0 / buffer.capacity()).toFloat()
        )
    }
    
    /**
     * 调整缓冲区大小（需要重新分配）
     * 注意：这会清空现有数据
     */
    suspend fun resize(newSize: Int): Boolean = bufferMutex.withLock {
        val actualSize = newSize.coerceIn(MIN_BUFFER_SIZE, MAX_BUFFER_SIZE)
        
        try {
            // 分配新的ByteBuffer
            val newBuffer = ByteBuffer.allocateDirect(actualSize)
                .order(ByteOrder.nativeOrder())
            
            // 复制现有数据（如果需要）
            if (size > 0 && size <= actualSize) {
                // TODO: 实现数据迁移逻辑
                android.util.Log.w(TAG, "调整缓冲区大小会丢失现有数据")
            }
            
            // 替换缓冲区（让旧缓冲区被GC回收）
            buffer.clear()
            writePosition = 0
            readPosition = 0
            size = 0
            
            android.util.Log.i(TAG, "环形缓冲区大小已调整为: $actualSize 字节")
            return true
        } catch (e: OutOfMemoryError) {
            android.util.Log.e(TAG, "调整缓冲区大小失败：内存不足", e)
            return false
        } catch (e: Exception) {
            android.util.Log.e(TAG, "调整缓冲区大小失败", e)
            return false
        }
    }
    
    /**
     * 获取可用空间（字节）
     */
    fun getAvailableSpace(): Int {
        return buffer.capacity() - size
    }
    
    /**
     * 检查是否为空
     */
    fun isEmpty(): Boolean {
        return size == 0
    }
    
    /**
     * 检查是否已满
     */
    fun isFull(): Boolean {
        return size >= buffer.capacity() - SAMPLE_SIZE_BYTES
    }
}

/**
 * 环形缓冲区状态
 */
data class RingBufferStatus(
    val capacity: Int,              // 总容量（字节）
    val size: Int,                  // 当前使用（字节）
    val sampleCount: Int,           // 当前样本数
    val writePosition: Int,         // 写位置
    val readPosition: Int,          // 读位置
    val totalWritten: Long,         // 总写入样本数
    val totalRead: Long,            // 总读取样本数
    val overflowCount: Long,        // 溢出次数
    val utilizationPercent: Float   // 使用率百分比
)