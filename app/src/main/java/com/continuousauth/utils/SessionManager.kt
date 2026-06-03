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
 * 会话管理器
 * 只负责当前采集/认证会话，不生成或保存用户标识。
 */
@Singleton
class SessionManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "SessionManager"
        private const val PREFS_FILE_NAME = "session_secure_prefs"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SESSION_START_TIME = "session_start_time"
    }

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

    private var currentSessionId: String? = null
    private var sessionStartTime: Long = 0

    fun startNewSession(): String {
        currentSessionId = UUID.randomUUID().toString()
        sessionStartTime = System.currentTimeMillis()

        encryptedPrefs.edit()
            .putString(KEY_SESSION_ID, currentSessionId)
            .putLong(KEY_SESSION_START_TIME, sessionStartTime)
            .apply()

        Log.i(TAG, "开始新会话: $currentSessionId")
        return currentSessionId!!
    }

    fun getCurrentSessionId(): String? {
        if (currentSessionId == null) {
            currentSessionId = encryptedPrefs.getString(KEY_SESSION_ID, null)
            sessionStartTime = encryptedPrefs.getLong(KEY_SESSION_START_TIME, 0)
        }
        return currentSessionId
    }

    fun getSessionStartTime(): Long {
        if (sessionStartTime == 0L) {
            sessionStartTime = encryptedPrefs.getLong(KEY_SESSION_START_TIME, 0)
        }
        return sessionStartTime
    }

    fun getSessionDuration(): Long {
        return if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0
        }
    }

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

    fun endSession() {
        currentSessionId = null
        sessionStartTime = 0

        encryptedPrefs.edit()
            .remove(KEY_SESSION_ID)
            .remove(KEY_SESSION_START_TIME)
            .apply()

        Log.i(TAG, "会话已结束")
    }

    fun clearAllData() {
        currentSessionId = null
        sessionStartTime = 0
        encryptedPrefs.edit().clear().apply()
        Log.w(TAG, "所有会话数据已清除")
    }
}
