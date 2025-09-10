package com.continuousauth.chunking

import android.util.Log
import com.continuousauth.proto.DataPacket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * 数据分片管理器
 * 注意：根据最新规范，不再需要分片功能
 * 保留此类以维持向后兼容，但所有方法返回原始数据
 */
@Singleton
class ChunkingManager @Inject constructor() {
    
    companion object {
        private const val TAG = "ChunkingManager"
        
        // 默认最大数据包大小：10MB (根据规范)
        const val DEFAULT_MAX_PACKET_SIZE = 10 * 1024 * 1024
        
        // 最小数据包大小：256KB
        const val MIN_PACKET_SIZE = 256 * 1024
        
        // 最大数据包大小：10MB
        const val MAX_PACKET_SIZE = 10 * 1024 * 1024
        
        // 分片时的缓冲区大小
        private const val CHUNK_BUFFER_SIZE = 8192
    }
    
    // 当前配置的最大数据包大小
    private var maxPacketSize = DEFAULT_MAX_PACKET_SIZE
    
    /**
     * 设置最大数据包大小
     * @param size 字节数，必须在 MIN_PACKET_SIZE 和 MAX_PACKET_SIZE 之间
     */
    fun setMaxPacketSize(size: Int) {
        if (size in MIN_PACKET_SIZE..MAX_PACKET_SIZE) {
            maxPacketSize = size
            Log.i(TAG, "最大数据包大小已设置为: ${size / 1024}KB")
        } else {
            Log.w(TAG, "无效的数据包大小: ${size}, 保持当前设置: ${maxPacketSize / 1024}KB")
        }
    }
    
    /**
     * 检查数据是否需要分片
     * @param data 数据字节数组
     * @return 始终返回 false (不再需要分片)
     */
    fun needsChunking(data: ByteArray): Boolean {
        return false // 根据规范，不再需要分片
    }
    
    /**
     * 将数据分片
     * @param data 原始数据
     * @return 分片后的数据列表
     */
    fun chunkData(data: ByteArray): List<ByteArray> {
        if (!needsChunking(data)) {
            return listOf(data)
        }
        
        val chunks = mutableListOf<ByteArray>()
        val chunkCount = ceil(data.size.toDouble() / maxPacketSize).toInt()
        
        Log.d(TAG, "数据大小 ${data.size} 字节，将分为 $chunkCount 个分片")
        
        for (i in 0 until chunkCount) {
            val start = i * maxPacketSize
            val end = minOf(start + maxPacketSize, data.size)
            val chunk = data.sliceArray(start until end)
            chunks.add(chunk)
            
            Log.v(TAG, "分片 ${i + 1}/$chunkCount: ${chunk.size} 字节")
        }
        
        return chunks
    }
    
    /**
     * 处理 DataPacket 的分片
     * 如果数据包的 encrypted_sensor_payload 超过大小限制，将创建多个分片包
     * @param originalPacket 原始数据包
     * @return 分片后的数据包列表 (始终返回原始包)
     */
    fun processPacketChunking(originalPacket: DataPacket): List<DataPacket> {
        // 不再需要分片，直接返回原始包
        return listOf(originalPacket)
    }
    
    /**
     * 重组分片数据
     * @param chunks 分片数据包列表（必须按 chunk_index 排序）
     * @return 重组后的完整数据包，如果重组失败返回 null
     */
    fun reassembleChunks(chunks: List<DataPacket>): DataPacket? {
        if (chunks.isEmpty()) {
            Log.e(TAG, "分片列表为空，无法重组")
            return null
        }
        
        // 不再需要分片，直接返回第一个包
        return chunks.first()
    }
    
    /**
     * 获取分片信息描述
     */
    fun getChunkInfo(packet: DataPacket): String {
        return "完整包" // 不再有分片
    }
    
    /**
     * 计算给定大小需要的分片数
     */
    fun calculateChunkCount(dataSize: Int): Int {
        return if (dataSize <= maxPacketSize) {
            1
        } else {
            ceil(dataSize.toDouble() / maxPacketSize).toInt()
        }
    }
}