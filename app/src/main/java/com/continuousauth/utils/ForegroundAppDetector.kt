package com.continuousauth.utils

import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Tracks usage events independently of the sensor screen's lifecycle. */
@Singleton
class ForegroundAppDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class RecentApp(val packageName: String, val timestamp: Long)

    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val tracker = ForegroundAppTracker()
    private val mutex = Mutex()
    private val _recentApps = MutableStateFlow<List<RecentApp>>(emptyList())
    val recentApps: StateFlow<List<RecentApp>> = _recentApps.asStateFlow()

    private var lastQueryWallTimeMs: Long? = null
    private var lastQueryElapsedTimeMs: Long? = null
    private var attributionAvailable = false
    private var screenUnavailableSinceMs: Long? = null
    private var hasLoggedPermissionWarning = false

    suspend fun getCurrentForegroundApp(): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            refreshIfNeeded()
            if (attributionAvailable && screenUnavailableSinceMs == null) tracker.currentPackage else ""
        }
    }

    /** Sensor timestamps use elapsed realtime, whereas UsageEvents use wall-clock time. */
    suspend fun getForegroundAppAt(eventTimestampNs: Long): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            refreshIfNeeded()
            if (!attributionAvailable) return@withLock ""
            val sampleTimeMs = System.currentTimeMillis() -
                (SystemClock.elapsedRealtimeNanos() - eventTimestampNs) / 1_000_000L
            if (screenUnavailableSinceMs?.let { sampleTimeMs >= it } == true) {
                ""
            } else {
                tracker.packageAt(sampleTimeMs)
            }
        }
    }

    private fun refreshIfNeeded() {
        val elapsedTimeMs = SystemClock.elapsedRealtime()
        val previousElapsedTimeMs = lastQueryElapsedTimeMs
        if (previousElapsedTimeMs != null &&
            elapsedTimeMs - previousElapsedTimeMs in 0 until QUERY_INTERVAL_MS) return

        val nowMs = System.currentTimeMillis()
        val previousWallTimeMs = lastQueryWallTimeMs
        if (previousWallTimeMs != null && previousElapsedTimeMs != null &&
            abs((nowMs - previousWallTimeMs) - (elapsedTimeMs - previousElapsedTimeMs)) > CLOCK_CHANGE_TOLERANCE_MS
        ) {
            resetTracking()
        }
        lastQueryElapsedTimeMs = elapsedTimeMs

        if (!hasUsageStatsPermission()) {
            if (!hasLoggedPermissionWarning) {
                Log.w(TAG, "Usage access is unavailable; sensor app attribution is empty")
                hasLoggedPermissionWarning = true
            }
            resetTracking()
            return
        }
        hasLoggedPermissionWarning = false

        try {
            val manager = usageStatsManager ?: run {
                resetTracking()
                return
            }
            val isInitialQuery = lastQueryWallTimeMs == null
            // The overlap catches events published just after the previous query completed.
            val startMs = (lastQueryWallTimeMs?.minus(QUERY_OVERLAP_MS)
                ?: (nowMs - INITIAL_LOOKBACK_MS)).coerceAtLeast(0L)
            val events = manager.queryEvents(startMs, nowMs) ?: run {
                resetTracking()
                return
            }
            val updates = mutableListOf<ForegroundAppEvent>()
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val type = when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED -> ForegroundEventType.RESUMED
                    // STOPPED can follow a replacement instance's RESUMED event with the same class name.
                    UsageEvents.Event.ACTIVITY_PAUSED -> ForegroundEventType.PAUSED
                    UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                    UsageEvents.Event.KEYGUARD_SHOWN,
                    UsageEvents.Event.DEVICE_SHUTDOWN,
                    UsageEvents.Event.DEVICE_STARTUP -> ForegroundEventType.CLEARED
                    else -> continue
                }
                updates += ForegroundAppEvent(
                    event.timeStamp, type, event.packageName.orEmpty(),
                    event.className.orEmpty()
                )
            }
            val transitions = tracker.apply(updates, nowMs)
            if (isInitialQuery) {
                tracker.latestTransition?.takeIf { it.packageName.isNotEmpty() }?.let {
                    rememberApp(it.packageName, it.timestamp)
                }
            } else {
                transitions.filter { it.packageName.isNotEmpty() }.forEach {
                    rememberApp(it.packageName, it.timestamp)
                }
            }
            val screenUnavailable = powerManager?.isInteractive == false ||
                keyguardManager?.isKeyguardLocked == true
            screenUnavailableSinceMs = if (screenUnavailable) screenUnavailableSinceMs ?: nowMs else null
            lastQueryWallTimeMs = nowMs
            attributionAvailable = true
        } catch (e: SecurityException) {
            resetTracking()
            Log.w(TAG, "Usage access was revoked", e)
        } catch (e: RuntimeException) {
            resetTracking()
            Log.w(TAG, "Unable to query foreground usage events", e)
        }
    }

    private fun resetTracking() {
        tracker.clear()
        lastQueryWallTimeMs = null
        attributionAvailable = false
        screenUnavailableSinceMs = null
    }

    private fun rememberApp(packageName: String, timestamp: Long) {
        if (_recentApps.value.any { it.packageName == packageName && it.timestamp >= timestamp }) return
        _recentApps.value = (_recentApps.value.filterNot { it.packageName == packageName } +
            RecentApp(packageName, timestamp)).sortedBy { it.timestamp }.takeLast(MAX_RECENT_APPS)
    }

    fun hasUsageStatsPermission(): Boolean = UsageStatsHelper.hasUsageStatsPermission(context)

    fun getAppName(packageName: String): String = try {
        val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
        context.packageManager.getApplicationLabel(appInfo).toString()
    } catch (_: PackageManager.NameNotFoundException) {
        packageName
    }

    private companion object {
        const val TAG = "ForegroundAppDetector"
        const val QUERY_INTERVAL_MS = 250L
        const val QUERY_OVERLAP_MS = 10_000L
        const val INITIAL_LOOKBACK_MS = 24 * 60 * 60 * 1_000L
        const val CLOCK_CHANGE_TOLERANCE_MS = 5_000L
        const val MAX_RECENT_APPS = 10
    }
}
