package com.continuousauth.time

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 增强的时间同步模块
 * 使用NTP协议同步时间，提供精确的UTC时间戳
 */
@Singleton
class EnhancedTimeSync @Inject constructor(
    private val coroutineScope: CoroutineScope
) {
    
    companion object {
        private const val TAG = "EnhancedTimeSync"
//        private const val NTP_SERVER = "pool.ntp.org"
        private const val NTP_SERVER = "cn.pool.ntp.org"
        private const val NTP_PORT = 123
        private const val NTP_PACKET_SIZE = 48
        private const val NTP_TIMESTAMP_OFFSET = 2208988800L // 1900-1970的秒数差
        private const val SYNC_INTERVAL_MS = 3600000L // 每小时同步一次
        private const val RETRY_DELAY_MS = 5000L // 重试延迟
        private const val MAX_RETRIES = 3
        private const val TIMEOUT_MS = 10000 // 10秒超时
    }
    
    // NTP偏移量（毫秒）
    private val _ntpOffset = MutableStateFlow(0L)
    val ntpOffset: StateFlow<Long> = _ntpOffset.asStateFlow()
    
    // 上次同步时间
    private var lastSyncTime = 0L
    
    // 同步状态
    private val _syncStatus = MutableStateFlow(SyncStatus.IDLE)
    val syncStatus: StateFlow<SyncStatus> = _syncStatus.asStateFlow()
    
    // 同步精度（毫秒）
    private val _syncAccuracy = MutableStateFlow(0L)
    val syncAccuracy: StateFlow<Long> = _syncAccuracy.asStateFlow()
    
    private var syncJob: Job? = null
    
    /**
     * 开始周期性时间同步
     */
    fun startPeriodicSync() {
        stopPeriodicSync()
        
        syncJob = coroutineScope.launch {
            while (isActive) {
                syncTime()
                delay(SYNC_INTERVAL_MS)
            }
        }
        
        Log.i(TAG, "开始周期性NTP时间同步")
    }
    
    /**
     * 停止周期性时间同步
     */
    fun stopPeriodicSync() {
        syncJob?.cancel()
        syncJob = null
        Log.i(TAG, "停止周期性NTP时间同步")
    }
    
    /**
     * 手动触发时间同步
     */
    suspend fun syncTime(): Boolean {
        _syncStatus.value = SyncStatus.SYNCING
        
        var retryCount = 0
        while (retryCount < MAX_RETRIES) {
            try {
                val offset = performNtpSync()
                _ntpOffset.value = offset
                lastSyncTime = System.currentTimeMillis()
                _syncStatus.value = SyncStatus.SUCCESS
                
                Log.i(TAG, "NTP同步成功，偏移量: ${offset}ms")
                return true
                
            } catch (e: Exception) {
                retryCount++
                Log.w(TAG, "NTP同步失败 (尝试 $retryCount/$MAX_RETRIES): ${e.message}")
                
                if (retryCount < MAX_RETRIES) {
                    delay(RETRY_DELAY_MS)
                } else {
                    _syncStatus.value = SyncStatus.ERROR
                    Log.e(TAG, "NTP同步失败，已达最大重试次数")
                }
            }
        }
        
        return false
    }
    
    /**
     * 执行NTP同步
     */
    private suspend fun performNtpSync(): Long = withContext(Dispatchers.IO) {
        val socket = DatagramSocket()
        socket.soTimeout = TIMEOUT_MS
        
        try {
            // 创建NTP请求包
            val ntpData = ByteArray(NTP_PACKET_SIZE)
            ntpData[0] = 0x1B // LI = 0, VN = 3, Mode = 3 (Client)
            
            // 记录发送时间
            val requestTime = System.currentTimeMillis()
            val requestTicks = SystemClock.elapsedRealtime()
            
            // 发送请求
            val address = InetAddress.getByName(NTP_SERVER)
            val packet = DatagramPacket(ntpData, ntpData.size, address, NTP_PORT)
            socket.send(packet)
            
            // 接收响应
            val response = DatagramPacket(ntpData, ntpData.size)
            socket.receive(response)
            
            // 记录接收时间
            val responseTime = System.currentTimeMillis()
            val responseTicks = SystemClock.elapsedRealtime()
            
            // 解析NTP时间戳
            val ntpTime = parseNtpTime(ntpData)
            
            // 计算往返时延
            val roundTripTime = responseTicks - requestTicks
            
            // 计算时钟偏移
            // offset = ((T2 - T1) + (T3 - T4)) / 2
            // 简化计算：offset = ntpTime - localTime - roundTripTime/2
            val localTime = requestTime + roundTripTime / 2
            val offset = ntpTime - localTime
            
            // 更新同步精度
            _syncAccuracy.value = roundTripTime / 2
            
            Log.d(TAG, "NTP同步详情 - RTT: ${roundTripTime}ms, 精度: ${roundTripTime/2}ms")
            
            offset
            
        } finally {
            socket.close()
        }
    }
    
    /**
     * 解析NTP时间戳
     */
    private fun parseNtpTime(data: ByteArray): Long {
        // 传输时间戳在字节40-47
        val transmitTimeOffset = 40
        
        // 读取秒数部分（32位）
        var seconds = 0L
        for (i in 0..3) {
            seconds = (seconds shl 8) or (data[transmitTimeOffset + i].toLong() and 0xFF)
        }
        
        // 读取小数部分（32位）
        var fraction = 0L
        for (i in 4..7) {
            fraction = (fraction shl 8) or (data[transmitTimeOffset + i].toLong() and 0xFF)
        }
        
        // 转换为毫秒时间戳
        // 减去1900-1970的秒数差，转换为Unix时间戳
        val unixSeconds = seconds - NTP_TIMESTAMP_OFFSET
        val milliseconds = (fraction * 1000L) / 0x100000000L
        
        return unixSeconds * 1000L + milliseconds
    }
    
    /**
     * 获取经过NTP校正的当前UTC时间
     */
    fun getCorrectedWallTime(): Long {
        return System.currentTimeMillis() + _ntpOffset.value
    }
    
    /**
     * 获取当前NTP偏移量
     */
    fun getNtpOffset(): Long {
        return _ntpOffset.value
    }
    
    /**
     * 检查NTP同步是否有效
     */
    fun isNtpSyncValid(): Boolean {
        val timeSinceLastSync = System.currentTimeMillis() - lastSyncTime
        return _syncStatus.value == SyncStatus.SUCCESS && 
               timeSinceLastSync < SYNC_INTERVAL_MS * 2 // 允许2倍同步间隔内有效
    }
    
    /**
     * 获取同步信息
     */
    fun getSyncInfo(): SyncInfo {
        return SyncInfo(
            offset = _ntpOffset.value,
            lastSyncTime = lastSyncTime,
            accuracy = _syncAccuracy.value,
            status = _syncStatus.value
        )
    }
    
    /**
     * 将相对时间戳转换为绝对UTC时间
     * @param elapsedNanos 设备启动后的纳秒数
     * @param baseElapsedNanos 批次创建时的elapsed时间
     * @param baseWallMs 批次创建时的wall clock时间（已经过NTP校正）
     */
    fun convertToAbsoluteTime(
        elapsedNanos: Long,
        baseElapsedNanos: Long,
        baseWallMs: Long
    ): Long {
        val deltaNanos = elapsedNanos - baseElapsedNanos
        val deltaMs = deltaNanos / 1_000_000
        return baseWallMs + deltaMs
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        stopPeriodicSync()
    }
}

/**
 * 同步状态
 */
enum class SyncStatus {
    IDLE,       // 空闲
    SYNCING,    // 同步中
    SUCCESS,    // 同步成功
    ERROR       // 同步失败
}

/**
 * 同步信息
 */
data class SyncInfo(
    val offset: Long,        // NTP偏移量（毫秒）
    val lastSyncTime: Long,  // 上次同步时间
    val accuracy: Long,      // 同步精度（毫秒）
    val status: SyncStatus   // 同步状态
)
