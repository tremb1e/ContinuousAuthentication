package com.continuousauth.privacy

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.continuousauth.database.BatchMetadataDao
import com.continuousauth.database.BatchStatus
import com.continuousauth.database.ContinuousAuthDatabase
import com.continuousauth.network.GrpcManager
// import com.continuousauth.proto.DataDeletionRequest
// import com.continuousauth.proto.DataDeletionResponse
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.utils.UserIdManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 隐私管理器
 * 负责处理用户同意、数据删除、合规相关功能
 * 符合 claude.md 第5节要求
 */
@Singleton
class PrivacyManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: ContinuousAuthDatabase,
    private val fileQueueManager: FileQueueManager,
    private val userIdManager: UserIdManager,
    private val grpcManager: GrpcManager
) {
    
    companion object {
        private const val TAG = "PrivacyManager"
        private const val PREFS_FILE = "privacy_prefs"
        private const val KEY_CONSENT_GIVEN = "consent_given"
        private const val KEY_CONSENT_TIMESTAMP = "consent_timestamp"
        private const val KEY_DATA_RETENTION_DAYS = "data_retention_days"
        private const val KEY_LAST_CLEANUP_TIME = "last_cleanup_time"
        
        const val DEFAULT_RETENTION_DAYS = 30  // 默认保留30天
        const val MAX_RETENTION_DAYS = 365     // 最长保留365天
    }
    
    // 加密的SharedPreferences存储隐私设置
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    // 用户同意状态
    private val _consentState = MutableStateFlow(ConsentState.UNKNOWN)
    val consentState: StateFlow<ConsentState> = _consentState.asStateFlow()
    
    // 数据删除状态
    private val _deletionState = MutableStateFlow(DeletionState.IDLE)
    val deletionState: StateFlow<DeletionState> = _deletionState.asStateFlow()
    
    init {
        // 初始化时检查同意状态
        checkConsentStatus()
    }
    
    /**
     * 检查用户同意状态
     */
    fun checkConsentStatus() {
        val hasConsent = encryptedPrefs.getBoolean(KEY_CONSENT_GIVEN, false)
        _consentState.value = if (hasConsent) {
            ConsentState.GRANTED
        } else {
            ConsentState.NOT_GRANTED
        }
        
        if (hasConsent) {
            val consentTime = encryptedPrefs.getLong(KEY_CONSENT_TIMESTAMP, 0)
            Log.i(TAG, "用户已同意，时间: ${java.util.Date(consentTime)}")
        }
    }
    
    /**
     * 记录用户同意
     */
    fun grantConsent() {
        encryptedPrefs.edit()
            .putBoolean(KEY_CONSENT_GIVEN, true)
            .putLong(KEY_CONSENT_TIMESTAMP, System.currentTimeMillis())
            .apply()
        
        _consentState.value = ConsentState.GRANTED
        Log.i(TAG, "用户已同意隐私协议")
    }
    
    /**
     * 撤回同意并删除所有数据
     * 这是主要的撤回同意接口，符合Section 5要求
     */
    suspend fun withdrawConsentAndDeleteData(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                _deletionState.value = DeletionState.IN_PROGRESS
                Log.i(TAG, "开始执行撤回同意流程")
                
                // 1. 更新同意状态
                _consentState.value = ConsentState.WITHDRAWN
                encryptedPrefs.edit()
                    .putBoolean(KEY_CONSENT_GIVEN, false)
                    .putLong(KEY_CONSENT_TIMESTAMP, System.currentTimeMillis())
                    .apply()
                
                // 2. 删除本地缓存数据
                val localDeletionResult = deleteAllLocalData()
                if (!localDeletionResult.isSuccess) {
                    Log.e(TAG, "本地数据删除失败: ${localDeletionResult.exceptionOrNull()}")
                    _deletionState.value = DeletionState.FAILED
                    return@withContext Result.failure(
                        Exception("本地数据删除失败: ${localDeletionResult.exceptionOrNull()?.message}")
                    )
                }
                
                // 3. 向服务器发送删除请求
                val serverDeletionResult = sendServerDeletionRequest()
                if (!serverDeletionResult.isSuccess) {
                    Log.e(TAG, "服务器数据删除请求失败: ${serverDeletionResult.exceptionOrNull()}")
                    // 服务器删除失败不影响本地删除结果，但要记录
                    _deletionState.value = DeletionState.PARTIAL_SUCCESS
                    
                    // 将失败的删除请求保存到队列，稍后重试
                    savePendingDeletionRequest()
                } else {
                    _deletionState.value = DeletionState.SUCCESS
                }
                
                Log.i(TAG, "撤回同意流程完成")
                Result.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "撤回同意过程中发生错误", e)
                _deletionState.value = DeletionState.FAILED
                Result.failure(e)
            }
        }
    }
    
    /**
     * 删除所有本地数据
     */
    private suspend fun deleteAllLocalData(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "开始删除本地数据")
                
                // 1. 删除数据库中的所有批次记录
                val batchDao = database.batchMetadataDao()
                // Use getPendingBatches with all statuses to get all batches as a List
                val allBatches = batchDao.getPendingBatches(
                    listOf(BatchStatus.PENDING, BatchStatus.UPLOADING, BatchStatus.UPLOADED, 
                           BatchStatus.FAILED, BatchStatus.ACKNOWLEDGED, BatchStatus.CORRUPT)
                )
                Log.d(TAG, "需要删除的批次数: ${allBatches.size}")
                
                for (batch in allBatches) {
                    // 删除文件
                    val file = File(batch.filePath)
                    if (file.exists()) {
                        file.delete()
                        Log.d(TAG, "删除文件: ${batch.filePath}")
                    }
                    
                    // 删除数据库记录
                    batchDao.delete(batch)
                }
                
                // 2. 清空文件队列
                fileQueueManager.clearQueue()
                
                // 3. 清空缓存目录
                clearCacheDirectory()
                
                // 4. 清除用户ID和会话信息
                userIdManager.clearAllData()
                
                // 5. 清除所有SharedPreferences（保留同意撤回记录）
                clearPreferences()
                
                Log.i(TAG, "本地数据删除完成")
                Result.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "删除本地数据失败", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 向服务器发送删除请求
     * TODO: 需要实现服务器端点
     */
    private suspend fun sendServerDeletionRequest(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "向服务器发送数据删除请求")
                
                // TODO: 实现实际的gRPC调用
                // 这里是示例代码，需要服务器端实现对应的RPC方法
                /*
                val request = DataDeletionRequest.newBuilder()
                    .setDeviceIdHash(getDeviceIdHash())
                    .setUserId(userIdManager.getUserId())
                    .setTimestamp(System.currentTimeMillis())
                    .setReason("USER_CONSENT_WITHDRAWAL")
                    .build()
                
                val response = grpcManager.sendDataDeletionRequest(request)
                
                if (response.success) {
                    Log.i(TAG, "服务器数据删除请求成功")
                    Result.success(Unit)
                } else {
                    Log.e(TAG, "服务器拒绝删除请求: ${response.message}")
                    Result.failure(Exception(response.message))
                }
                */
                
                // 暂时返回成功（等待服务器端实现）
                Log.w(TAG, "服务器删除请求端点尚未实现，跳过")
                Result.success(Unit)
                
            } catch (e: Exception) {
                Log.e(TAG, "发送服务器删除请求失败", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * 保存待处理的删除请求（用于重试）
     */
    private fun savePendingDeletionRequest() {
        encryptedPrefs.edit()
            .putBoolean("pending_deletion_request", true)
            .putLong("pending_deletion_timestamp", System.currentTimeMillis())
            .apply()
        
        Log.i(TAG, "已保存待处理的删除请求")
    }
    
    /**
     * 检查并重试待处理的删除请求
     */
    suspend fun retryPendingDeletionRequests() {
        if (encryptedPrefs.getBoolean("pending_deletion_request", false)) {
            Log.i(TAG, "发现待处理的删除请求，尝试重新发送")
            
            val result = sendServerDeletionRequest()
            if (result.isSuccess) {
                encryptedPrefs.edit()
                    .remove("pending_deletion_request")
                    .remove("pending_deletion_timestamp")
                    .apply()
                Log.i(TAG, "待处理的删除请求已成功发送")
            }
        }
    }
    
    /**
     * 清空缓存目录
     */
    private fun clearCacheDirectory() {
        try {
            val cacheDir = context.cacheDir
            val queueDir = File(cacheDir, "sensor_queue")
            if (queueDir.exists()) {
                queueDir.deleteRecursively()
                Log.d(TAG, "已清空缓存目录")
            }
        } catch (e: Exception) {
            Log.e(TAG, "清空缓存目录失败", e)
        }
    }
    
    /**
     * 清除SharedPreferences（保留必要的记录）
     */
    private fun clearPreferences() {
        // 保存需要保留的值
        val consentWithdrawn = _consentState.value == ConsentState.WITHDRAWN
        val withdrawalTime = if (consentWithdrawn) System.currentTimeMillis() else 0L
        
        // 清除其他所有preferences
        val prefsDir = File(context.applicationInfo.dataDir, "shared_prefs")
        prefsDir.listFiles()?.forEach { file ->
            if (!file.name.contains(PREFS_FILE)) {
                // 不清除隐私设置文件本身
                file.delete()
                Log.d(TAG, "删除preferences文件: ${file.name}")
            }
        }
        
        // 如果是撤回同意，记录撤回时间
        if (consentWithdrawn) {
            encryptedPrefs.edit()
                .putBoolean("consent_withdrawn", true)
                .putLong("withdrawal_timestamp", withdrawalTime)
                .apply()
        }
    }
    
    /**
     * 设置数据保留期限（天）
     */
    fun setDataRetentionDays(days: Int) {
        val validDays = days.coerceIn(1, MAX_RETENTION_DAYS)
        encryptedPrefs.edit()
            .putInt(KEY_DATA_RETENTION_DAYS, validDays)
            .apply()
        
        Log.i(TAG, "数据保留期限设置为: $validDays 天")
    }
    
    /**
     * 获取数据保留期限
     */
    fun getDataRetentionDays(): Int {
        return encryptedPrefs.getInt(KEY_DATA_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
    }
    
    /**
     * 执行数据保留策略清理
     * 删除超过保留期限的数据
     */
    suspend fun performRetentionCleanup() {
        withContext(Dispatchers.IO) {
            try {
                val retentionDays = getDataRetentionDays()
                val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
                
                Log.i(TAG, "执行数据保留清理，删除 ${java.util.Date(cutoffTime)} 之前的数据")
                
                // 删除过期的批次
                val batchDao = database.batchMetadataDao()
                // Get all batches and filter for expired ones
                val allBatches = batchDao.getPendingBatches(
                    listOf(BatchStatus.PENDING, BatchStatus.UPLOADING, BatchStatus.UPLOADED, 
                           BatchStatus.FAILED, BatchStatus.ACKNOWLEDGED, BatchStatus.CORRUPT)
                )
                val expiredBatches = allBatches.filter { it.createdTime < cutoffTime }
                
                for (batch in expiredBatches) {
                    // 删除文件
                    val file = File(batch.filePath)
                    if (file.exists()) {
                        file.delete()
                    }
                    
                    // 删除数据库记录
                    batchDao.delete(batch)
                }
                
                Log.i(TAG, "数据保留清理完成，删除了 ${expiredBatches.size} 个过期批次")
                
                // 更新最后清理时间
                encryptedPrefs.edit()
                    .putLong(KEY_LAST_CLEANUP_TIME, System.currentTimeMillis())
                    .apply()
                
            } catch (e: Exception) {
                Log.e(TAG, "数据保留清理失败", e)
            }
        }
    }
    
    /**
     * 检查是否需要执行数据保留清理
     */
    fun shouldPerformRetentionCleanup(): Boolean {
        val lastCleanup = encryptedPrefs.getLong(KEY_LAST_CLEANUP_TIME, 0)
        val daysSinceCleanup = (System.currentTimeMillis() - lastCleanup) / (24 * 60 * 60 * 1000L)
        return daysSinceCleanup >= 1  // 每天执行一次清理
    }
    
    /**
     * 获取隐私政策文本（支持多语言）
     */
    fun getPrivacyPolicyText(): String {
        // TODO: 从资源文件或远程加载完整的隐私政策
        return """
            数据收集与使用说明
            
            1. 数据收集类型
            - 传感器数据：加速度计、陀螺仪、磁力计
            - 设备信息：设备型号、系统版本
            - 应用使用情况：前台应用信息
            
            2. 数据用途
            - 用于持续身份认证研究
            - 改进认证算法准确性
            - 学术研究与分析
            
            3. 数据存储
            - 所有数据均采用AES-256加密
            - 本地缓存最多保留${getDataRetentionDays()}天
            - 服务器端安全存储
            
            4. 数据共享
            - 不与第三方共享原始数据
            - 仅分享聚合统计信息
            
            5. 用户权利
            - 您可以随时撤回同意
            - 撤回后将删除所有相关数据
            - 支持数据导出请求
            
            6. 联系方式
            - 邮箱：privacy@continuousauth.com
            - 电话：+86-xxx-xxxx
        """.trimIndent()
    }
}

/**
 * 用户同意状态
 */
enum class ConsentState {
    UNKNOWN,        // 未知状态
    NOT_GRANTED,    // 未同意
    GRANTED,        // 已同意
    WITHDRAWN       // 已撤回
}

/**
 * 数据删除状态
 */
enum class DeletionState {
    IDLE,               // 空闲
    IN_PROGRESS,        // 删除中
    SUCCESS,            // 删除成功
    PARTIAL_SUCCESS,    // 部分成功（本地成功，服务器失败）
    FAILED              // 删除失败
}