package com.continuousauth.compression

import android.util.Log
import net.jpountz.lz4.LZ4Factory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据压缩管理器
 * 符合 Epic 1.4.3 要求：先序列化 -> 压缩 -> 再加密
 * 支持 LZ4 和 GZIP 压缩
 */
@Singleton
class CompressionManager @Inject constructor() {
    
    companion object {
        private const val TAG = "CompressionManager"
        
        // 默认压缩缓冲区大小
        private const val BUFFER_SIZE = 8192
        
        // LZ4 工厂实例（单例）
        private val lz4Factory = LZ4Factory.fastestInstance()
    }
    
    /**
     * 压缩算法枚举
     */
    enum class CompressionType {
        NONE,
        GZIP,
        LZ4,    // 默认算法
        SNAPPY  // 预留，暂未实现
    }
    
    /**
     * 压缩数据
     * @param data 原始数据
     * @param type 压缩类型
     * @return 压缩后的数据，如果压缩失败返回null
     */
    fun compress(data: ByteArray, type: CompressionType = CompressionType.LZ4): ByteArray? {
        try {
            return when (type) {
                CompressionType.NONE -> data
                CompressionType.GZIP -> compressGzip(data)
                CompressionType.LZ4 -> compressLz4(data)
                CompressionType.SNAPPY -> {
                    Log.w(TAG, "Snappy 压缩暂未实现，使用 LZ4")
                    compressLz4(data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "压缩数据失败", e)
            return null
        }
    }
    
    /**
     * 解压数据
     * @param data 压缩数据
     * @param type 压缩类型
     * @return 解压后的数据，如果解压失败返回null
     */
    fun decompress(data: ByteArray, type: CompressionType = CompressionType.LZ4): ByteArray? {
        try {
            return when (type) {
                CompressionType.NONE -> data
                CompressionType.GZIP -> decompressGzip(data)
                CompressionType.LZ4 -> decompressLz4(data)
                CompressionType.SNAPPY -> {
                    Log.w(TAG, "Snappy 解压暂未实现，使用 LZ4")
                    decompressLz4(data)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解压数据失败", e)
            return null
        }
    }
    
    /**
     * GZIP 压缩实现
     */
    private fun compressGzip(data: ByteArray): ByteArray {
        val startTime = System.currentTimeMillis()
        val originalSize = data.size
        
        val outputStream = ByteArrayOutputStream()
        GZIPOutputStream(outputStream).use { gzipStream ->
            gzipStream.write(data)
            gzipStream.finish()
        }
        
        val compressedData = outputStream.toByteArray()
        val compressedSize = compressedData.size
        val compressionRatio = (1 - compressedSize.toDouble() / originalSize) * 100
        val duration = System.currentTimeMillis() - startTime
        
        Log.d(TAG, "GZIP 压缩完成: $originalSize -> $compressedSize 字节 " +
                "(压缩率: %.1f%%, 耗时: ${duration}ms)".format(compressionRatio))
        
        return compressedData
    }
    
    /**
     * GZIP 解压实现
     */
    private fun decompressGzip(data: ByteArray): ByteArray {
        val startTime = System.currentTimeMillis()
        val compressedSize = data.size
        
        val inputStream = ByteArrayInputStream(data)
        val outputStream = ByteArrayOutputStream()
        
        GZIPInputStream(inputStream).use { gzipStream ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (gzipStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
        }
        
        val decompressedData = outputStream.toByteArray()
        val decompressedSize = decompressedData.size
        val duration = System.currentTimeMillis() - startTime
        
        Log.d(TAG, "GZIP 解压完成: $compressedSize -> $decompressedSize 字节 " +
                "(耗时: ${duration}ms)")
        
        return decompressedData
    }
    
    /**
     * 获取压缩类型的字符串表示
     */
    fun getCompressionTypeString(type: CompressionType): String {
        return when (type) {
            CompressionType.NONE -> "NONE"
            CompressionType.GZIP -> "GZIP"
            CompressionType.LZ4 -> "LZ4"
            CompressionType.SNAPPY -> "SNAPPY"
        }
    }
    
    /**
     * 从字符串解析压缩类型
     */
    fun parseCompressionType(typeString: String): CompressionType {
        return when (typeString.lowercase()) {
            "none" -> CompressionType.NONE
            "gzip" -> CompressionType.GZIP
            "lz4" -> CompressionType.LZ4
            "snappy" -> CompressionType.SNAPPY
            else -> CompressionType.LZ4 // 默认使用 LZ4
        }
    }
    
    /**
     * 计算压缩比率
     */
    fun calculateCompressionRatio(originalSize: Int, compressedSize: Int): Double {
        if (originalSize == 0) return 0.0
        return (1 - compressedSize.toDouble() / originalSize) * 100
    }
    
    /**
     * LZ4 压缩实现
     * 使用高性能的LZ4算法，适合实时数据压缩
     */
    private fun compressLz4(data: ByteArray): ByteArray {
        val startTime = System.currentTimeMillis()
        val originalSize = data.size
        
        // 使用高压缩率的压缩器
        val compressor = lz4Factory.highCompressor()
        val maxCompressedLength = compressor.maxCompressedLength(originalSize)
        
        // 创建输出缓冲区（包含4字节的原始大小信息）
        val compressed = ByteArray(maxCompressedLength + 4)
        
        // 写入原始大小（用于解压）
        ByteBuffer.wrap(compressed, 0, 4).putInt(originalSize)
        
        // 执行压缩
        val compressedLength = compressor.compress(
            data, 0, originalSize,
            compressed, 4, maxCompressedLength
        )
        
        // 创建实际大小的结果数组
        val result = ByteArray(compressedLength + 4)
        System.arraycopy(compressed, 0, result, 0, compressedLength + 4)
        
        val compressionRatio = (1 - result.size.toDouble() / originalSize) * 100
        val duration = System.currentTimeMillis() - startTime
        
        Log.d(TAG, "LZ4 压缩完成: $originalSize -> ${result.size} 字节 " +
                "(压缩率: %.1f%%, 耗时: ${duration}ms)".format(compressionRatio))
        
        return result
    }
    
    /**
     * LZ4 解压实现
     */
    private fun decompressLz4(data: ByteArray): ByteArray {
        val startTime = System.currentTimeMillis()
        val compressedSize = data.size
        
        // 读取原始大小
        val originalSize = ByteBuffer.wrap(data, 0, 4).int
        
        // 创建解压器
        val decompressor = lz4Factory.safeDecompressor()
        
        // 执行解压
        val decompressed = ByteArray(originalSize)
        decompressor.decompress(
            data, 4, compressedSize - 4,
            decompressed, 0, originalSize
        )
        
        val duration = System.currentTimeMillis() - startTime
        
        Log.d(TAG, "LZ4 解压完成: $compressedSize -> $originalSize 字节 " +
                "(耗时: ${duration}ms)")
        
        return decompressed
    }
}
