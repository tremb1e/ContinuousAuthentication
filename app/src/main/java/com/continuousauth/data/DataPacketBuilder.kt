package com.continuousauth.data

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.continuousauth.model.SensorSample
import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.Metadata
import com.continuousauth.proto.SerializedSensorBatch
import com.continuousauth.time.EnhancedTimeSync
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DataPacket构建器
 * 负责创建包含批次时间戳的数据包
 */
@Singleton
class DataPacketBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val enhancedTimeSync: EnhancedTimeSync,
    private val envelopeCryptoBox: com.continuousauth.crypto.EnvelopeCryptoBox
) {
    
    companion object {
        private const val SCHEMA_VERSION = "1.0"  // proto中是string类型
        private const val TAG = "DataPacketBuilder"
    }
    
    private val deviceId by lazy { generateDeviceId() }
    private var packetSeqNo = 0L
    
    /**
     * 构建DataPacket
     * 记录批次生成时的base_wall_ms和device_uptime_ns
     */
    fun buildDataPacket(
        sensorSamples: List<SensorSample>,
        encryptedPayload: ByteArray,
        packetSeqNo: Long? = null,
        userId: String,
        sessionId: String,
        encryptedDek: ByteArray? = null,
        dekKeyId: String = "",
        sha256: ByteArray? = null,
        compressionType: String = "LZ4"
    ): DataPacket {
        
        // 记录批次创建时的关键时间戳
        val baseElapsedNs = SystemClock.elapsedRealtimeNanos()
        val baseWallMs = enhancedTimeSync.getCorrectedWallTime()
        val ntpOffsetMs = enhancedTimeSync.getNtpOffset()
        
        // 生成唯一的包ID
        val packetId = UUID.randomUUID().toString()
        
        // 构建元数据
        val metadata = buildMetadata(compressionType)
        
        // 获取设备ID的HMAC哈希
        val deviceIdHash = envelopeCryptoBox.getDeviceIdHash()
        
        // 获取下一个包序列号
        val seqNo = packetSeqNo ?: envelopeCryptoBox.getNextPacketSeqNo()
        this.packetSeqNo = seqNo
        
        // 构建DataPacket
        val builder = DataPacket.newBuilder()
            .setPacketId(packetId)
            .setDeviceIdHash(deviceIdHash)  // 使用HMAC哈希
            .setBaseWallMs(baseWallMs)
            .setDeviceUptimeNs(baseElapsedNs)
            .setEncryptedSensorPayload(com.google.protobuf.ByteString.copyFrom(encryptedPayload))
            .setPacketSeqNo(seqNo)  // 添加序列号
            .setMetadata(metadata)
        
        // 加密相关字段（共享对称密钥方案）
        if (encryptedDek != null) {
            builder.setEncryptedDek(com.google.protobuf.ByteString.copyFrom(encryptedDek))
        }
        if (dekKeyId.isNotEmpty()) {
            builder.setDekKeyId(dekKeyId)
        }
        if (sha256 != null) {
            builder.setSha256(com.google.protobuf.ByteString.copyFrom(sha256))
        }
        
        // 如果NTP同步有效，添加NTP偏移量
        if (enhancedTimeSync.isNtpSyncValid()) {
            builder.setNtpOffsetMs(ntpOffsetMs)
        }
        
        android.util.Log.d(TAG, "构建DataPacket - ID: $packetId, 样本数: ${sensorSamples.size}, " +
                "base_wall_ms: $baseWallMs, device_uptime_ns: $baseElapsedNs, " +
                "ntp_offset_ms: $ntpOffsetMs")
        
        return builder.build()
    }
    
    /**
     * 构建传感器批次数据（用于加密前）
     */
    fun buildSensorBatch(
        sensorSamples: List<SensorSample>,
        userId: String,
        sessionId: String
    ): SerializedSensorBatch {
        
        // 转换模型数据为proto格式
        val protoSamples = sensorSamples.map { sample ->
            val builder = com.continuousauth.proto.SensorSample.newBuilder()
                .setType(convertSensorType(sample.type))
                .setEventTimestampNs(sample.eventTimestampNs)
                .setX(sample.x)
                .setY(sample.y)
                .setZ(sample.z)
                .setAccuracy(sample.accuracy)
                .setSeqNo(sample.seqNo)
            
            // 前台应用使用明文字段，确保日志能看到真实包名
            if (sample.foregroundApp.isNotEmpty()) {
                builder.setForegroundApp(sample.foregroundApp)
            }
            
            builder.build()
        }
        
        // 使用HMAC哈希用户ID
        val userIdHash = envelopeCryptoBox.getUserIdHash(userId)
        
        return SerializedSensorBatch.newBuilder()
            .addAllSamples(protoSamples)
            .setUserIdHash(userIdHash)  // 使用HMAC哈希
            .setSessionId(sessionId)
            .build()
    }
    
    /**
     * 构建元数据
     */
    private fun buildMetadata(compressionType: String = "LZ4"): Metadata {
        return Metadata.newBuilder()
            .setAppVersion(getAppVersion())
            .setAndroidApiLevel(Build.VERSION.SDK_INT)  // 这是正确的，proto定义中是int32
            .setSchemaVersion(SCHEMA_VERSION)
            .setCompression(compressionType)  // 使用传入的压缩类型
            .setEncryptionScheme("AES-256-GCM")  // 与服务端共享密钥的对称加密
            .setKeyVersion(envelopeCryptoBox.getDekKeyId())  // 密钥版本
            .build()
    }
    
    /**
     * 转换传感器类型
     */
    private fun convertSensorType(sensorType: com.continuousauth.model.SensorType): com.continuousauth.proto.SensorType {
        return when (sensorType) {
            com.continuousauth.model.SensorType.ACCELEROMETER -> com.continuousauth.proto.SensorType.ACCELEROMETER
            com.continuousauth.model.SensorType.GYROSCOPE -> com.continuousauth.proto.SensorType.GYROSCOPE
            com.continuousauth.model.SensorType.MAGNETOMETER -> com.continuousauth.proto.SensorType.MAGNETOMETER
        }
    }
    
    /**
     * 获取设备ID
     */
    private fun generateDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            Log.w(TAG, "无法获取ANDROID_ID，使用随机UUID", e)
            UUID.randomUUID().toString()
        }
    }
    
    /**
     * 获取应用版本
     */
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.longVersionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }
}
