package com.continuousauth.processing

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.continuousauth.model.SensorSample
import com.continuousauth.model.SensorType
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 将 1 秒窗口的传感器样本构造成 server-lite 兼容的加密负载：
 * JSON -> gzip -> AES-256-GCM（key = SHA256("Continuous_Authentication")，输出 iv|tag|ciphertext）。
 */
@Singleton
class LitePacketBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "LitePacketBuilder"
        private const val ENCRYPTION_KEY = "Continuous_Authentication"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BYTES = 16
    }

    private val gson = Gson()
    private val secureRandom = SecureRandom()
    private val keyBytes: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(ENCRYPTION_KEY.toByteArray(Charsets.UTF_8))
    private val deviceIdHash: Long by lazy { deriveStableLong(loadDeviceId()) }

    /**
     * 构建加密后的数据包，返回密文及上传所需的 Header 值。
     */
    fun buildEncryptedPacket(
        samples: List<SensorSample>,
        sessionId: String,
        packetSeqNo: Long
    ): LiteEncryptedPacket? {
        if (samples.isEmpty()) return null
        return try {
            val nowMs = System.currentTimeMillis()
            val elapsedNowMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
            val elapsedToWallOffsetMs = nowMs - elapsedNowMs
            val windowStartMs = samples.minOf { it.eventTimestampNs / 1_000_000 } + elapsedToWallOffsetMs
            val windowEndMs = samples.maxOf { it.eventTimestampNs / 1_000_000 } + elapsedToWallOffsetMs
            val sessionNumeric = deriveStableLong(sessionId)

            val payload = LitePacketPayload(
                deviceIdHash = deviceIdHash,
                sessionId = sessionNumeric,
                packetSeqNo = packetSeqNo,
                timestampMs = windowEndMs,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                type = "sensor",
                foregroundPackageName = samples.lastOrNull { it.foregroundApp.isNotBlank() }?.foregroundApp
                    ?: "",
                sensorData = samples.map { it.toLiteSensorData() }
            )

            val jsonBytes = gson.toJson(payload).toByteArray(Charsets.UTF_8)
            val compressed = gzip(jsonBytes)
            val encrypted = encryptGcm(compressed) ?: return null

            LiteEncryptedPacket(
                payload = encrypted,
                deviceIdHash = payload.deviceIdHash.toString(),
                sessionId = payload.sessionId.toString(),
                packetSeqNo = packetSeqNo
            )
        } catch (e: Exception) {
            Log.e(TAG, "构建加密数据包失败", e)
            null
        }
    }

    private fun SensorSample.toLiteSensorData(): LiteSensorData {
        return LiteSensorData(
            sensorName = when (type) {
                SensorType.ACCELEROMETER -> "accelerometer"
                SensorType.GYROSCOPE -> "gyroscope"
                SensorType.MAGNETOMETER -> "magnetometer"
            },
            sensorType = when (type) {
                SensorType.ACCELEROMETER -> 1  // Sensor.TYPE_ACCELEROMETER
                SensorType.MAGNETOMETER -> 2    // Sensor.TYPE_MAGNETIC_FIELD
                SensorType.GYROSCOPE -> 4       // Sensor.TYPE_GYROSCOPE
            },
            timestampNs = eventTimestampNs,
            values = LiteSensorValue(x, y, z),
            accuracy = accuracy
        )
    }

    private fun gzip(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { gzip ->
            gzip.write(data)
        }
        return output.toByteArray()
    }

    private fun encryptGcm(plaintext: ByteArray): ByteArray? {
        return try {
            val iv = ByteArray(IV_LENGTH_BYTES).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val keySpec = SecretKeySpec(keyBytes, "AES")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)
            val cipherWithTag = cipher.doFinal(plaintext)
            if (cipherWithTag.size < GCM_TAG_LENGTH_BYTES) {
                Log.e(TAG, "加密输出长度异常")
                return null
            }

            val cipherText = cipherWithTag.copyOfRange(
                0,
                cipherWithTag.size - GCM_TAG_LENGTH_BYTES
            )
            val tag = cipherWithTag.copyOfRange(
                cipherWithTag.size - GCM_TAG_LENGTH_BYTES,
                cipherWithTag.size
            )

            iv + tag + cipherText
        } catch (e: Exception) {
            Log.e(TAG, "AES-GCM 加密失败", e)
            null
        }
    }

    private fun deriveStableLong(input: String): Long {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        val buffer = ByteBuffer.wrap(digest, 0, 8).order(ByteOrder.BIG_ENDIAN)
        val value = buffer.long
        val nonNegative = value and Long.MAX_VALUE
        return if (nonNegative == 0L) 1L else nonNegative
    }

    private fun loadDeviceId(): String {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: UUID.randomUUID().toString()
        } catch (e: Exception) {
            UUID.randomUUID().toString()
        }
    }
}

/**
 * HTTP 上传需要的加密包和 Header 值。
 */
data class LiteEncryptedPacket(
    val payload: ByteArray,
    val deviceIdHash: String,
    val sessionId: String,
    val packetSeqNo: Long
)

private data class LitePacketPayload(
    @SerializedName("device_id_hash") val deviceIdHash: Long,
    @SerializedName("session_id") val sessionId: Long,
    @SerializedName("packet_seq_no") val packetSeqNo: Long,
    @SerializedName("timestamp_ms") val timestampMs: Long,
    @SerializedName("window_start_ms") val windowStartMs: Long,
    @SerializedName("window_end_ms") val windowEndMs: Long,
    @SerializedName("type") val type: String,
    @SerializedName("foreground_package_name") val foregroundPackageName: String?,
    @SerializedName("sensor_data") val sensorData: List<LiteSensorData>
)

private data class LiteSensorData(
    @SerializedName("sensor_name") val sensorName: String,
    @SerializedName("sensor_type") val sensorType: Int,
    @SerializedName("timestamp_ns") val timestampNs: Long,
    @SerializedName("values") val values: LiteSensorValue,
    @SerializedName("accuracy") val accuracy: Int
)

private data class LiteSensorValue(
    @SerializedName("x") val x: Float,
    @SerializedName("y") val y: Float,
    @SerializedName("z") val z: Float
)
