package com.continuousauth.policy

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.continuousauth.proto.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 策略管理器
 * 负责管理和应用服务器下发的策略配置
 */
@Singleton
class PolicyManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "PolicyManager"
        private const val PREFERENCES_NAME = "policy_preferences"
        
        // DataStore Keys
        private val POLICY_ID = stringPreferencesKey("policy_id")
        private val POLICY_TIMESTAMP = longPreferencesKey("policy_timestamp")
        private val POLICY_VERSION = stringPreferencesKey("policy_version")
        
        // 传输策略Keys
        private val BATCH_INTERVAL_MS = intPreferencesKey("batch_interval_ms")
        private val MAX_PAYLOAD_SIZE = intPreferencesKey("max_payload_size_bytes")
        private val UPLOAD_RATE_LIMIT = floatPreferencesKey("upload_rate_limit")
        private val COMPRESSION_ALGORITHM = stringPreferencesKey("compression_algorithm")
        private val BATCH_SIZE_THRESHOLD = intPreferencesKey("batch_size_threshold")
        
        // 异常检测策略Keys
        private val ANOMALY_ENABLED = booleanPreferencesKey("anomaly_enabled")
        private val ANOMALY_THRESHOLD_MULTIPLIER = floatPreferencesKey("anomaly_threshold_multiplier")
        private val ANOMALY_WINDOW_SIZE = intPreferencesKey("anomaly_window_size_sec")
        private val ANOMALY_COOLDOWN = intPreferencesKey("anomaly_cooldown_period_sec")
        
        // 传感器配置 Keys
        private val ENABLED_SENSORS = stringSetPreferencesKey("enabled_sensors")
        private val SENSOR_SAMPLING_RATES = stringPreferencesKey("sensor_sampling_rates_json") // JSON存储map
        
        // 默认策略值
        private const val DEFAULT_BATCH_INTERVAL = 1000
        private const val DEFAULT_MAX_PAYLOAD_SIZE = 10 * 1024 * 1024 // 10MB
        private const val DEFAULT_ANOMALY_THRESHOLD_MULTIPLIER = 2.0f
    }
    
    private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
        name = PREFERENCES_NAME
    )
    
    // 策略更新回调列表
    private val policyUpdateCallbacks = mutableListOf<(PolicyConfiguration) -> Unit>()
    
    /**
     * 应用策略更新
     */
    suspend fun updatePolicy(policyUpdate: PolicyUpdate) {
        applyPolicyUpdate(policyUpdate)
    }
    
    /**
     * 应用策略更新
     */
    suspend fun applyPolicyUpdate(policyUpdate: PolicyUpdate) {
        Log.i(TAG, "应用策略更新 - 策略ID: ${policyUpdate.policyId}")

        try {
            // 保存策略到DataStore
            context.dataStore.edit { preferences ->
                preferences[POLICY_ID] = policyUpdate.policyId
                preferences[POLICY_VERSION] = policyUpdate.policyVersion
                
                // 传输策略
                if (policyUpdate.batchIntervalMs > 0) {
                    preferences[BATCH_INTERVAL_MS] = policyUpdate.batchIntervalMs
                }
                if (policyUpdate.maxPayloadSizeBytes > 0) {
                    preferences[MAX_PAYLOAD_SIZE] = policyUpdate.maxPayloadSizeBytes
                }
                if (policyUpdate.uploadRateLimit > 0) {
                    preferences[UPLOAD_RATE_LIMIT] = policyUpdate.uploadRateLimit
                }
                if (policyUpdate.compressionAlgorithm.isNotEmpty()) {
                    preferences[COMPRESSION_ALGORITHM] = policyUpdate.compressionAlgorithm
                }
                if (policyUpdate.batchSizeThreshold > 0) {
                    preferences[BATCH_SIZE_THRESHOLD] = policyUpdate.batchSizeThreshold
                }
                
                // 异常检测配置
                if (policyUpdate.hasAnomalyConfig()) {
                    val ac = policyUpdate.anomalyConfig
                    preferences[ANOMALY_ENABLED] = ac.enabled
                    preferences[ANOMALY_THRESHOLD_MULTIPLIER] = ac.thresholdMultiplier
                    preferences[ANOMALY_WINDOW_SIZE] = ac.windowSizeSec
                    preferences[ANOMALY_COOLDOWN] = ac.cooldownPeriodSec
                }
                
                // 传感器配置
                if (policyUpdate.enabledSensorsList.isNotEmpty()) {
                    preferences[ENABLED_SENSORS] = policyUpdate.enabledSensorsList.toSet()
                }
                if (policyUpdate.sensorSamplingRatesCount > 0) {
                    // 将map转换为JSON存储
                    val ratesJson = org.json.JSONObject(policyUpdate.sensorSamplingRatesMap).toString()
                    preferences[SENSOR_SAMPLING_RATES] = ratesJson
                }
            }

            // 获取当前策略配置并通知回调
            val currentConfig = getCurrentPolicyConfiguration()
            notifyPolicyUpdate(currentConfig)

            Log.i(TAG, "策略更新应用成功")

        } catch (e: Exception) {
            Log.e(TAG, "应用策略更新失败", e)
        }
    }
    
    /**
     * 获取当前策略配置
     */
    fun getCurrentPolicyConfiguration(): PolicyConfiguration {
        return runBlocking {
            context.dataStore.data
                .map { preferences ->
                    PolicyConfiguration(
                        policyId = preferences[POLICY_ID] ?: "",
                        transmissionConfig = TransmissionConfiguration(
                            batchIntervalMs = preferences[BATCH_INTERVAL_MS] ?: DEFAULT_BATCH_INTERVAL,
                            maxPayloadSizeBytes = preferences[MAX_PAYLOAD_SIZE] ?: DEFAULT_MAX_PAYLOAD_SIZE,
                            uploadRateLimit = preferences[UPLOAD_RATE_LIMIT] ?: 10.0f,
                            compressionAlgorithm = preferences[COMPRESSION_ALGORITHM] ?: "LZ4",
                            batchSizeThreshold = preferences[BATCH_SIZE_THRESHOLD] ?: 100
                        ),
                        collectionConfig = CollectionConfiguration(
                            anomalyEnabled = preferences[ANOMALY_ENABLED] ?: false,
                            anomalyThresholdMultiplier = preferences[ANOMALY_THRESHOLD_MULTIPLIER] ?: DEFAULT_ANOMALY_THRESHOLD_MULTIPLIER,
                            anomalyWindowSizeSec = preferences[ANOMALY_WINDOW_SIZE] ?: 60,
                            anomalyCooldownSec = preferences[ANOMALY_COOLDOWN] ?: 60,
                            enabledSensors = preferences[ENABLED_SENSORS] ?: setOf("ACCELEROMETER", "GYROSCOPE", "MAGNETOMETER"),
                            sensorSamplingRates = parseSamplingRates(preferences[SENSOR_SAMPLING_RATES])
                        ),
                        securityConfig = SecurityConfiguration(
                            serverEndpoint = "",
                            pinnedCertificates = emptySet()
                        )
                    )
                }
                .first()
        }
    }
    
    /**
     * 注册策略更新回调
     */
    fun registerPolicyUpdateCallback(callback: (PolicyConfiguration) -> Unit) {
        policyUpdateCallbacks.add(callback)
    }
    
    /**
     * 注销策略更新回调
     */
    fun unregisterPolicyUpdateCallback(callback: (PolicyConfiguration) -> Unit) {
        policyUpdateCallbacks.remove(callback)
    }
    
    /**
     * 通知策略更新
     */
    private fun notifyPolicyUpdate(config: PolicyConfiguration) {
        policyUpdateCallbacks.forEach { callback ->
            try {
                callback(config)
            } catch (e: Exception) {
                Log.e(TAG, "策略更新回调执行失败", e)
            }
        }
    }
    
    /**
     * 获取当前策略（简化版本）
     */
    suspend fun getCurrentPolicy(): Policy {
        val config = withContext(Dispatchers.IO) {
            getCurrentPolicyConfiguration()
        }
        return Policy(
            batchIntervalMs = config.transmissionConfig.batchIntervalMs,
            anomalyThresholdMultiplier = config.collectionConfig.anomalyThresholdMultiplier,
            batchSizeThreshold = config.transmissionConfig.batchSizeThreshold
        )
    }
    
    /**
     * 解析传感器采样率JSON
     */
    private fun parseSamplingRates(json: String?): Map<String, Int> {
        if (json.isNullOrEmpty()) {
            return mapOf(
                "ACCELEROMETER" to 200,
                "GYROSCOPE" to 200,
                "MAGNETOMETER" to 100
            )
        }
        return try {
            val jsonObject = org.json.JSONObject(json)
            val map = mutableMapOf<String, Int>()
            jsonObject.keys().forEach { key ->
                map[key] = jsonObject.getInt(key)
            }
            map
        } catch (e: Exception) {
            mapOf(
                "ACCELEROMETER" to 200,
                "GYROSCOPE" to 200,
                "MAGNETOMETER" to 100
            )
        }
    }
}

/**
 * 简化的策略数据类
 */
data class Policy(
    val batchIntervalMs: Int,              // 批处理间隔（毫秒）
    val anomalyThresholdMultiplier: Float, // 异常阈值倍数
    val batchSizeThreshold: Int            // 批处理大小阈值
)

/**
 * 策略配置数据类
 */
data class PolicyConfiguration(
    val policyId: String,
    val transmissionConfig: TransmissionConfiguration,
    val collectionConfig: CollectionConfiguration,
    val securityConfig: SecurityConfiguration
)

/**
 * 传输配置
 */
data class TransmissionConfiguration(
    val batchIntervalMs: Int,
    val maxPayloadSizeBytes: Int,
    val uploadRateLimit: Float,
    val compressionAlgorithm: String,
    val batchSizeThreshold: Int
)

/**
 * 采集配置
 */
data class CollectionConfiguration(
    val anomalyEnabled: Boolean,
    val anomalyThresholdMultiplier: Float,
    val anomalyWindowSizeSec: Int,
    val anomalyCooldownSec: Int,
    val enabledSensors: Set<String>,
    val sensorSamplingRates: Map<String, Int>
)

/**
 * 安全配置
 */
data class SecurityConfiguration(
    val serverEndpoint: String,
    val pinnedCertificates: Set<String>
)
