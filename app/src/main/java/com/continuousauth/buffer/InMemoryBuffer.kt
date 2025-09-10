package com.continuousauth.buffer

import com.continuousauth.proto.DataPacket
import kotlinx.coroutines.flow.Flow

/**
 * 内存缓冲区接口
 * 线程安全地管理已加密的传感器数据队列
 */
interface InMemoryBuffer {
    
    /**
     * 添加加密数据包到缓冲区
     */
    suspend fun enqueue(dataPacket: DataPacket): Boolean
    
    /**
     * 从缓冲区取出一批数据包
     * @param maxCount 最大数量，-1表示取出所有
     */
    suspend fun dequeue(maxCount: Int = -1): List<DataPacket>
    
    /**
     * 获取缓冲区中的数据包数量
     */
    fun getSize(): Int
    
    /**
     * 检查缓冲区是否为空
     */
    fun isEmpty(): Boolean
    
    /**
     * 获取缓冲区数据流（用于持续监听）
     */
    fun getDataFlow(): Flow<DataPacket>
    
    /**
     * 清空缓冲区
     */
    suspend fun clear()
    
    /**
     * 获取缓冲区状态信息
     */
    fun getBufferStatus(): BufferStatus
    
    /**
     * 调整缓冲区大小
     */
    fun adjustBufferSize(multiplier: Float)
}

/**
 * 缓冲区状态信息
 */
data class BufferStatus(
    val currentSize: Int,           // 当前数据包数量
    val maxCapacity: Int,           // 最大容量
    val totalEnqueued: Long,        // 总入队数
    val totalDequeued: Long,        // 总出队数
    val memoryUsageBytes: Long,     // 估算内存使用量（字节）
    val isOverflow: Boolean         // 是否接近溢出
)