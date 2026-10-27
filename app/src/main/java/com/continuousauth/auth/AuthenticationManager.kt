package com.continuousauth.auth

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.continuousauth.network.UploadManager
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.proto.AuthResult
import com.continuousauth.proto.AuthSessionResponse
import com.continuousauth.settings.AppSettingsRepository
import com.continuousauth.service.DataCollectionService
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.utils.ForegroundAppDetector
import com.continuousauth.utils.SessionManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Owns authentication independently of the screen, including history and background alerts. */
@Singleton
class AuthenticationManager internal constructor(
    context: Context,
    private val uploadManager: UploadManager,
    private val settings: AppSettingsRepository,
    private val history: AuthHistoryRepository,
    private val privacy: PrivacyManager,
    private val sessionManager: SessionManager,
    private val foregroundApps: ForegroundAppDetector,
    private val alerts: AuthenticationAlertManager,
    dispatcher: CoroutineDispatcher
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        uploadManager: UploadManager,
        settings: AppSettingsRepository,
        history: AuthHistoryRepository,
        privacy: PrivacyManager,
        sessionManager: SessionManager,
        foregroundApps: ForegroundAppDetector,
        alerts: AuthenticationAlertManager
    ) : this(context, uploadManager, settings, history, privacy, sessionManager, foregroundApps, alerts, Dispatchers.Default)

    private val prefs = context.getSharedPreferences("authentication_state", Context.MODE_PRIVATE)
    private val collectionPrefs = context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
    private val authStateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val resultMutex = Mutex()
    private val generation = AtomicLong()
    @Volatile private var activeSession = prefs.getString("active_session", null)
    @Volatile private var expectedDevice = prefs.getString("device_hash", null)
    private val _active = MutableStateFlow(
        canAuthenticate() && !activeSession.isNullOrBlank() && activeSession == sessionManager.getCurrentSessionId()
    )
    val active = _active.asStateFlow()
    private val _latestResult = MutableStateFlow<AuthResult?>(null)
    val latestResult = _latestResult.asStateFlow()

    init {
        // Subscribe before a service can open its connection. An Activity is never required.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            uploadManager.authResultFlow.collect { result ->
                try {
                    receiveResult(result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("AuthenticationManager", "保存认证结果失败", e)
                }
            }
        }
        scope.launch {
            settings.protectionMode.collect { mode ->
                if (mode == ProtectionMode.OFF) stopAuthentication()
                if (mode != ProtectionMode.ALERT) withContext(Dispatchers.Main) { alerts.dismiss() }
            }
        }
        scope.launch {
            privacy.consentState.collect { state ->
                if (state != ConsentState.GRANTED) stopAuthentication()
            }
        }
        scope.launch {
            while (isActive) {
                runCatching { history.refresh() }
                    .onFailure { if (it is CancellationException) throw it }
                delay(60_000)
            }
        }
    }

    private fun isCollectionRequested(): Boolean =
        collectionPrefs.getBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false)

    private fun canAuthenticate(): Boolean =
        isCollectionRequested() && settings.protectionMode.value != ProtectionMode.OFF &&
            privacy.consentState.value == ConsentState.GRANTED && settings.sensorSettings.value.allEnabled

    suspend fun startAuthentication(deviceIdHash: String, sessionId: String): AuthSessionResponse? {
        val requestGeneration = synchronized(authStateLock) {
            if (!canAuthenticate()) return null
            generation.incrementAndGet()
        }
        val response = uploadManager.startAuthentication(deviceIdHash, sessionId)
        return synchronized(authStateLock) {
            // Stop may arrive before this RPC starts, during it, or before its result is committed.
            if (requestGeneration != generation.get() || !canAuthenticate() ||
                sessionManager.getCurrentSessionId() != sessionId) return@synchronized null
            if (response?.accepted == true) {
                // Upload packets keep the local session. A different response session cannot be used safely.
                if (response.sessionId.isNotBlank() && response.sessionId != sessionId) return@synchronized null
                activeSession = sessionId
                expectedDevice = deviceIdHash
                prefs.edit().putString("active_session", sessionId).putString("device_hash", deviceIdHash).apply()
                _latestResult.value = null
                _active.value = true
            }
            response
        }
    }

    fun stopAuthentication() {
        synchronized(authStateLock) {
            generation.incrementAndGet()
            _active.value = false
            activeSession = null
            expectedDevice = null
            _latestResult.value = null
            prefs.edit().clear().apply()
        }
        scope.launch(Dispatchers.Main) { alerts.dismiss() }
    }

    private fun belongsToActiveSession(result: AuthResult): Boolean =
        _active.value && canAuthenticate() &&
            result.sessionId.isNotBlank() && result.sessionId == activeSession &&
            result.sessionId == sessionManager.getCurrentSessionId() &&
            result.deviceIdHash.isNotBlank() && result.deviceIdHash == expectedDevice

    private suspend fun receiveResult(result: AuthResult) = resultMutex.withLock {
        val resultGeneration = generation.get()
        if (!belongsToActiveSession(result) || result.windowId < 0L || result.serverTimestampMs <= 0L ||
            !result.normalizedScore.isFinite() || !result.threshold.isFinite()) return@withLock
        val now = System.currentTimeMillis()
        val timestamp = result.serverTimestampMs.coerceAtMost(now)
        if (timestamp < now - AuthHistoryRepository.RETENTION_MS) return@withLock
        val eventElapsedNs = SystemClock.elapsedRealtimeNanos() - (now - timestamp) * 1_000_000L
        val appPackage = if (eventElapsedNs >= 0L) foregroundApps.getForegroundAppAt(eventElapsedNs) else ""
        val appName = if (appPackage.isBlank()) "" else runCatching {
            foregroundApps.getAppName(appPackage)
        }.getOrDefault(appPackage)
        if (generation.get() != resultGeneration || !belongsToActiveSession(result)) return@withLock
        val entry = AuthHistoryEntry(
            id = "${result.sessionId}:${result.windowId}",
            timestampMs = timestamp,
            accepted = result.accept && !result.interrupt,
            appPackage = appPackage,
            appName = appName
        )
        if (!history.record(entry, now) {
            generation.get() == resultGeneration && belongsToActiveSession(result)
        }) return@withLock
        synchronized(authStateLock) {
            if (generation.get() != resultGeneration || !belongsToActiveSession(result)) return@withLock
            _latestResult.value = result
        }
        if (!entry.accepted && settings.protectionMode.value == ProtectionMode.ALERT) {
            withContext(Dispatchers.Main) {
                if (generation.get() == resultGeneration && belongsToActiveSession(result) &&
                    settings.protectionMode.value == ProtectionMode.ALERT) {
                    alerts.showFailure(appName)
                }
            }
        }
    }

    internal fun close() = scope.cancel()
}
