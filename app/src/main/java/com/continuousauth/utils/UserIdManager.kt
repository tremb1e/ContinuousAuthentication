package com.continuousauth.utils

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 用户ID管理器
 * 负责生成和管理应用内的用户唯一标识UUID
 */
@Singleton
class UserIdManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    companion object {
        private const val TAG = "UserIdManager"
        private const val PREFS_FILE_NAME = "user_secure_prefs"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_START_TIME = "session_start_time"
    }
    
    // 使用加密的SharedPreferences存储敏感信息
    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
            
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
    
    // 缓存的用户ID
    private var cachedUserId: String? = null
    
    // 当前会话信息
    private var currentSessionId: String? = null
    private var sessionStartTime: Long = 0
    
    /**
     * 获取用户ID
     * 如果不存在则自动生成一个新的UUID
     */
    fun getUserId(): String {
        // 从缓存中获取
        cachedUserId?.let { return it }
        
        // 从加密存储中获取
        var userId = encryptedPrefs.getString(KEY_USER_ID, null)
        
        // 如果不存在则生成新的UUID
        if (userId == null) {
            userId = UUID.randomUUID().toString()
            encryptedPrefs.edit().putString(KEY_USER_ID, userId).apply()
            Log.i(TAG, "生成新的用户ID: $userId")
        } else {
            Log.d(TAG, "获取已存在的用户ID: $userId")
        }
        
        // 更新缓存
        cachedUserId = userId
        return userId
    }
    
    /**
     * 开始新的会话
     * 生成新的会话ID并记录开始时间
     */
    fun startNewSession(): String {
        currentSessionId = UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()
        
        // 保存会话信息
        encryptedPrefs.edit()
            .putString(KEY_SESSION_ID, currentSessionId)
            .putLong(KEY_SESSION_START_TIME, sessionStartTime)
            .apply()
            
        Log.i(TAG, "开始新会话: $currentSessionId")
        return currentSessionId!!
    }
    
    /**
     * 获取当前会话ID
     * 如果没有活动会话则返回null
     */
    fun getCurrentSessionId(): String? {
        if (currentSessionId == null) {
            // 尝试从存储中恢复
            currentSessionId = encryptedPrefs.getString(KEY_SESSION_ID, null)
            sessionStartTime = encryptedPrefs.getLong(KEY_SESSION_START_TIME, 0)
        }
        return currentSessionId
    }
    
    /**
     * 获取会话开始时间
     */
    fun getSessionStartTime(): Long {
        if (sessionStartTime == 0L) {
            sessionStartTime = encryptedPrefs.getLong(KEY_SESSION_START_TIME, 0)
        }
        return sessionStartTime
    }
    
    /**
     * 获取会话已运行时长（毫秒）
     */
    fun getSessionDuration(): Long {
        return if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0
        }
    }
    
    /**
     * 格式化会话时长为可读字符串
     */
    fun getFormattedSessionDuration(): String {
        val duration = getSessionDuration()
        val seconds = (duration / 1000) % 60
        val minutes = (duration / (1000 * 60)) % 60
        val hours = duration / (1000 * 60 * 60)
        
        return when {
            hours > 0 -> String.format("%02d:%02d:%02d", hours, minutes, seconds)
            else -> String.format("%02d:%02d", minutes, seconds)
        }
    }
    
    /**
     * 结束当前会话
     */
    fun endSession() {
        currentSessionId = null
        sessionStartTime = 0
        
        // 清除存储的会话信息
        encryptedPrefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_START_TIME)
            .apply()
            
        Log.i(TAG, "会话已结束")
    }
    
    /**
     * 重置用户ID（仅在特殊情况下使用，如用户撤回同意）
     */
    fun resetUserId() {
        cachedUserId = null
        encryptedPrefs.edit().remove(KEY_USER_ID).apply()
        Log.w(TAG, "用户ID已重置")
    }
    
    /**
     * 清除所有用户数据
     */
    fun clearAllData() {
        cachedUserId = null
        currentSessionId = null
        sessionStartTime = 0
        encryptedPrefs.edit().clear().apply()
        Log.w(TAG, "所有用户数据已清除")
    }
}