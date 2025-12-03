package com.continuousauth.crypto

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 增强的AAD(Associated Data)构建器
 * 将packet_id、device_id、设备型号、App版本、电池电量、网络类型等
 * 关键非机密元数据序列化后作为AAD传入，提供更强的防篡改保护
 */
@Singleton  
class AADBuilder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val envelopeCryptoBox: EnvelopeCryptoBox
) {
    
    /**
     * 构建关联数据(AAD)
     * 使用HMAC摘要替代明文敏感信息
     * 严格按照 claude.md Task 1.3.4 的顺序构建
     */
    fun buildAAD(
        packetId: String,
        packetSeqNo: Long,
        dekKeyId: String,
        appVersion: String,
        sampleCount: Int,
        keyVersion: String = "v1"
    ): ByteArray {
        
        return try {
            // 使用HMAC摘要替代明文设备ID
            val deviceIdHash = envelopeCryptoBox.getDeviceIdHash()
            
            // 严格按照文档要求的顺序构建AAD
            val aadData = AADData.newBuilder()
                // 必须的核心字段（严格按照顺序）
                .setPacketId(packetId)              // 1. packet_id
                .setDeviceIdHash(deviceIdHash)      // 2. device_id_hash (HMAC)
                .setPacketSeqNo(packetSeqNo)       // 3. packet_seq_no
                .setDekKeyId(dekKeyId)              // 4. dek_key_id
                .setKeyVersion(keyVersion)          // 5. key_version
                // 额外的元数据字段
                .setDeviceModel(Build.MODEL)
                .setDeviceManufacturer(Build.MANUFACTURER)
                .setAppVersion(appVersion)
                .setAndroidApiLevel(Build.VERSION.SDK_INT)
                .setSampleCount(sampleCount)
                .setBatteryLevel(getBatteryLevel())
                .setNetworkType(getNetworkType())
                .setTimestamp(System.currentTimeMillis())
                .build()
                
            aadData.toByteArray()
            
        } catch (e: Exception) {
            android.util.Log.e("AADBuilder", "构建AAD失败，使用基础AAD", e)
            // 回退到基础AAD（仍然保持核心字段顺序）
            buildBasicAAD(packetId, deviceIdHash = envelopeCryptoBox.getDeviceIdHash(), 
                         packetSeqNo = packetSeqNo, dekKeyId = dekKeyId, keyVersion = keyVersion)
        }
    }
    
    /**
     * 构建基础AAD（当详细AAD构建失败时使用）
     * 仍然保持核心字段顺序
     */
    private fun buildBasicAAD(
        packetId: String,
        deviceIdHash: String,
        packetSeqNo: Long,
        dekKeyId: String,
        keyVersion: String
    ): ByteArray {
        // 按照文档要求的顺序拼接核心字段
        val basicData = StringBuilder()
            .append(packetId).append("|")           // 1. packet_id
            .append(deviceIdHash).append("|")       // 2. device_id_hash
            .append(packetSeqNo).append("|")        // 3. packet_seq_no
            .append(dekKeyId).append("|")           // 4. dek_key_id
            .append(keyVersion)                     // 5. key_version
            .toString()
        
        return basicData.toByteArray(StandardCharsets.UTF_8)
    }
    
    /**
     * 获取电池电量
     */
    private fun getBatteryLevel(): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        } catch (e: Exception) {
            android.util.Log.w("AADBuilder", "无法获取电池电量", e)
            -1
        }
    }
    
    /**
     * 获取网络类型
     */
    private fun getNetworkType(): String {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) 
                as? android.net.ConnectivityManager
            
            val activeNetwork = connectivityManager?.activeNetwork
            val capabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            
            when {
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true -> "WIFI"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "CELLULAR"
                capabilities?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "ETHERNET"
                else -> "UNKNOWN"
            }
        } catch (e: Exception) {
            android.util.Log.w("AADBuilder", "无法获取网络类型", e)
            "UNKNOWN"
        }
    }
}

/**
 * AAD数据的简单序列化结构
 * 使用简单的键值对格式而非Protobuf以避免循环依赖
 */
private class AADData private constructor(
    private val data: MutableMap<String, Any> = mutableMapOf()
) {
    
    companion object {
        fun newBuilder() = Builder()
    }
    
    class Builder {
        private val data = mutableMapOf<String, Any>()
        
        // 核心字段（按文档顺序）
        fun setPacketId(value: String) = apply { data["01_packet_id"] = value }
        fun setDeviceIdHash(value: String) = apply { data["02_device_id_hash"] = value }
        fun setPacketSeqNo(value: Long) = apply { data["03_packet_seq_no"] = value }
        fun setDekKeyId(value: String) = apply { data["04_dek_key_id"] = value }
        fun setKeyVersion(value: String) = apply { data["05_key_version"] = value }
        
        // 额外元数据字段
        fun setDeviceModel(value: String) = apply { data["device_model"] = value }
        fun setDeviceManufacturer(value: String) = apply { data["device_manufacturer"] = value }
        fun setAppVersion(value: String) = apply { data["app_version"] = value }
        fun setAndroidApiLevel(value: Int) = apply { data["android_api_level"] = value }
        fun setSampleCount(value: Int) = apply { data["sample_count"] = value }
        fun setBatteryLevel(value: Int) = apply { data["battery_level"] = value }
        fun setNetworkType(value: String) = apply { data["network_type"] = value }
        fun setTimestamp(value: Long) = apply { data["timestamp"] = value }
        
        fun build() = AADData(data)
    }
    
    fun toByteArray(): ByteArray {
        // 简单的键值对序列化
        val sortedEntries = data.toSortedMap() // 确保顺序一致
        val serialized = sortedEntries.entries.joinToString("|") { "${it.key}=${it.value}" }
        return serialized.toByteArray(StandardCharsets.UTF_8)
    }
}
