package com.continuousauth.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.continuousauth.R
import com.continuousauth.auth.AuthenticationManager
import com.continuousauth.core.SmartTransmissionManager
import com.continuousauth.network.UploadManager
import com.continuousauth.ui.compose.MainComposeActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Owns collection for its entire lifetime, including while another app is visible. */
@AndroidEntryPoint
class DataCollectionService : Service() {
    companion object {
        private const val TAG = "DataCollectionService"
        private const val NOTIFICATION_CHANNEL_ID = "continuous_auth_service"
        private const val NOTIFICATION_ID = 1001
        private const val MANUALLY_PAUSED_KEY = "collection_manually_paused"
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        private const val SCREEN_STATE_CHECK_INTERVAL_MS = 1_000L

        const val ACTION_START_COLLECTION = "com.continuousauth.START_COLLECTION"
        const val ACTION_STOP_COLLECTION = "com.continuousauth.STOP_COLLECTION"
        const val ACTION_PAUSE_COLLECTION = "com.continuousauth.PAUSE_COLLECTION"
        const val ACTION_RESUME_COLLECTION = "com.continuousauth.RESUME_COLLECTION"
        const val ACTION_REFRESH_STATE = "com.continuousauth.REFRESH_COLLECTION_STATE"
        const val ACTION_RESET_AUTHENTICATION = "com.continuousauth.RESET_AUTHENTICATION"
        const val PREFS_NAME = "app_prefs"
        const val COLLECTION_REQUESTED_KEY = "collection_requested"
        internal const val EXTRA_STOP_REQUEST_ID = "stop_request_id"

        internal data class StopState(val requestedId: Long = 0, val completedId: Long = 0) {
            val isPending: Boolean get() = requestedId > completedId
        }

        private val stopLock = Any()
        private val mutableStopState = MutableStateFlow(StopState())
        internal val stopState: StateFlow<StopState> = mutableStopState.asStateFlow()
        private var stopIntentDispatched = false
        private var serviceInstances = 0
        internal val hasServiceInstance: Boolean get() = synchronized(stopLock) { serviceInstances > 0 }

        /** Concurrent stop and deletion callers share the same actual service completion. */
        internal fun requestStop(context: Context): Long = synchronized(stopLock) {
            val requestId = pendingOrNewStopRequest()
            if (!stopIntentDispatched) {
                context.startService(Intent(context, DataCollectionService::class.java)
                    .setAction(ACTION_STOP_COLLECTION)
                    .putExtra(EXTRA_STOP_REQUEST_ID, requestId))
                stopIntentDispatched = true
            }
            requestId
        }

        private fun pendingOrNewStopRequest(): Long {
            if (!mutableStopState.value.isPending) {
                val previous = mutableStopState.value
                mutableStopState.value = previous.copy(requestedId = previous.requestedId + 1)
                stopIntentDispatched = false
            }
            return mutableStopState.value.requestedId
        }

        private fun acceptStopRequest(intent: Intent): Long = synchronized(stopLock) {
            val suppliedId = intent.getLongExtra(EXTRA_STOP_REQUEST_ID, 0)
            if (suppliedId > 0 && suppliedId <= mutableStopState.value.completedId) return@synchronized 0L
            val requestId = if (suppliedId in 1L..mutableStopState.value.requestedId) suppliedId else pendingOrNewStopRequest()
            stopIntentDispatched = true
            requestId
        }

        internal fun completeStopRequest(requestId: Long) = synchronized(stopLock) {
            val state = mutableStopState.value
            if (requestId > state.completedId && requestId <= state.requestedId) {
                mutableStopState.value = state.copy(completedId = requestId)
            }
        }
    }

    @Inject lateinit var smartTransmissionManager: SmartTransmissionManager
    @Inject lateinit var authenticationManager: AuthenticationManager
    @Inject lateinit var uploadManager: UploadManager

    private val serviceScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val commandMutex = Mutex()
    private val pauseState = CollectionPauseState()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewalJob: Job? = null
    private var receiverRegistered = false
    private var isCollecting = false
    private var isPaused = false
    private var acceptedStopRequestId = 0L
    private var explicitStopFinished = false

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }
    private val powerManager by lazy { getSystemService(Context.POWER_SERVICE) as PowerManager }
    private val keyguardManager by lazy { getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }

    private val systemEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> pauseState.set(CollectionPauseState.Reason.SCREEN, true)
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_USER_PRESENT -> refreshScreenState()
                else -> return
            }
            reconcileCollectionState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        synchronized(stopLock) { serviceInstances++ }
        createNotificationChannel()
        if (!checkPrivacyConsent()) {
            setCollectionRequested(false)
            stopSelf()
            return
        }

        pauseState.set(
            CollectionPauseState.Reason.MANUAL,
            prefs.getBoolean(MANUALLY_PAUSED_KEY, false)
        )
        refreshScreenState()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        registerReceiver(systemEventReceiver, filter)
        receiverRegistered = true
        serviceScope.launch {
            smartTransmissionManager.smartUploadWindowOpen.collect { updateWakeLock() }
        }
        serviceScope.launch {
            while (isActive) {
                delay(SCREEN_STATE_CHECK_INTERVAL_MS)
                // Keyguard state can settle after USER_PRESENT, and some devices miss broadcasts.
                if (refreshScreenState()) reconcileCollectionState()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A stop must be accepted even after consent was withdrawn, before any
        // foreground-start rejection or suspended startup can bypass its receipt.
        if (intent?.action == ACTION_STOP_COLLECTION) {
            val requestId = acceptStopRequest(intent)
            if (requestId > 0) stopCollection(requestId)
            if (requestId == 0L && !isCollectionRequested()) stopSelf()
            return if (isCollectionRequested()) START_STICKY else START_NOT_STICKY
        }
        if (!checkPrivacyConsent()) {
            setCollectionRequested(false)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START_COLLECTION && !isCollectionRequested()) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
            setCollectionRequested(false)
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_START_COLLECTION -> {
                setManuallyPaused(false)
                refreshScreenState()
                reconcileCollectionState()
            }
            ACTION_RESET_AUTHENTICATION -> {
                authenticationManager.stopAuthentication()
                serviceScope.launch {
                    commandMutex.withLock {
                        if (isCollectionRequested()) {
                            try {
                                smartTransmissionManager.rotateSession()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.e(TAG, "结束认证会话失败", e)
                                setCollectionRequested(false)
                                releaseWakeLock()
                                stopForeground(STOP_FOREGROUND_REMOVE)
                                stopSelf()
                            }
                        }
                    }
                }
            }
            ACTION_PAUSE_COLLECTION -> {
                setManuallyPaused(true)
                reconcileCollectionState()
            }
            ACTION_RESUME_COLLECTION -> {
                setManuallyPaused(false)
                refreshScreenState()
                reconcileCollectionState()
            }
            else -> {
                refreshScreenState()
                reconcileCollectionState()
            }
        }

        if (!isCollectionRequested()) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(systemEventReceiver)
            receiverRegistered = false
        }
        releaseWakeLock()
        serviceScope.cancel()
        if (!isCollectionRequested() || !checkPrivacyConsent()) authenticationManager.stopAuthentication()
        // Preserve the session only for an unexpected restart. Explicit stop or consent
        // revocation still requires clearing volatile data if its command was cancelled.
        serviceScope.launch(NonCancellable, start = CoroutineStart.UNDISPATCHED) {
            try {
                if (!explicitStopFinished) {
                    smartTransmissionManager.stop(endSession = !isCollectionRequested() || !checkPrivacyConsent())
                }
                completeStopRequest(acceptedStopRequestId)
            } finally {
                synchronized(stopLock) { serviceInstances-- }
            }
        }
        super.onDestroy()
    }

    private fun reconcileCollectionState() {
        serviceScope.launch {
            commandMutex.withLock {
                if (!isCollectionRequested()) return@withLock
                try {
                    explicitStopFinished = false
                    uploadManager.setCollectionUploadAllowed(true)
                    smartTransmissionManager.start()
                    // Stop can arrive while start() is suspended on its IO dispatcher.
                    if (!isCollectionRequested() || !checkPrivacyConsent()) return@withLock
                    isCollecting = true
                    if (pauseState.isPaused) {
                        smartTransmissionManager.pause(allowBufferedUpload = pauseState.allowBufferedUpload)
                    } else {
                        smartTransmissionManager.resume()
                    }
                    isPaused = pauseState.isPaused
                    updateWakeLock()
                    updateNotification()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "更新数据采集状态失败", e)
                    isCollecting = false
                    setCollectionRequested(false)
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun stopCollection(requestId: Long) {
        if (requestId <= acceptedStopRequestId) return
        acceptedStopRequestId = requestId
        explicitStopFinished = false
        uploadManager.setCollectionUploadAllowed(false)
        uploadManager.pauseUpload("collection_stopping")
        authenticationManager.stopAuthentication()
        setCollectionRequested(false)
        setManuallyPaused(false)
        serviceScope.launch {
            commandMutex.withLock {
                smartTransmissionManager.stop()
                isCollecting = false
                isPaused = false
                releaseWakeLock()
                explicitStopFinished = true
                if (!isCollectionRequested()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
                completeStopRequest(requestId)
            }
        }
    }

    private fun refreshScreenState(): Boolean =
        pauseState.set(
            CollectionPauseState.Reason.SCREEN,
            !powerManager.isInteractive || keyguardManager.isKeyguardLocked || keyguardManager.isDeviceLocked
        )

    private fun setManuallyPaused(paused: Boolean) {
        pauseState.set(CollectionPauseState.Reason.MANUAL, paused)
        prefs.edit().putBoolean(MANUALLY_PAUSED_KEY, paused).apply()
    }

    private fun checkPrivacyConsent(): Boolean = prefs.getBoolean("privacy_consent_granted", false)

    private fun setCollectionRequested(requested: Boolean) {
        prefs.edit().putBoolean(COLLECTION_REQUESTED_KEY, requested).apply()
    }

    private fun isCollectionRequested(): Boolean = prefs.getBoolean(COLLECTION_REQUESTED_KEY, false)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "持续认证数据采集服务"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainComposeActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this, 1, Intent(this, DataCollectionService::class.java).setAction(ACTION_STOP_COLLECTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumePendingIntent = PendingIntent.getService(
            this, 2, Intent(this, DataCollectionService::class.java).setAction(
                if (isPaused) ACTION_RESUME_COLLECTION else ACTION_PAUSE_COLLECTION
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(when {
                isPaused -> "数据采集已暂停"
                isCollecting -> "数据采集运行中"
                else -> "数据采集服务就绪"
            })
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "恢复" else "暂停", pauseResumePendingIntent
            )
            .addAction(android.R.drawable.ic_delete, "停止", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, createNotification())
    }

    private fun updateWakeLock() {
        val needsWakeLock = isCollecting && (!isPaused ||
            (pauseState.allowBufferedUpload && smartTransmissionManager.smartUploadWindowOpen.value))
        if (!needsWakeLock) {
            releaseWakeLock()
            return
        }
        if (wakeLockRenewalJob?.isActive == true) return
        val lock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:DataCollectionWakeLock"
        ).apply { setReferenceCounted(false) }
        wakeLock = lock
        wakeLockRenewalJob = serviceScope.launch {
            while (isCollecting) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                delay(WAKE_LOCK_TIMEOUT_MS / 2)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRenewalJob?.cancel()
        wakeLockRenewalJob = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
