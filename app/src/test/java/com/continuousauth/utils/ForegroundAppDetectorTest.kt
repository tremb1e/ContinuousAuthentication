package com.continuousauth.utils

import android.app.AppOpsManager
import android.app.Application
import android.app.KeyguardManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ForegroundAppDetectorTest {
    private lateinit var context: Context
    private lateinit var appOps: AppOpsManager
    private lateinit var usageStats: ShadowUsageStatsManager
    private lateinit var detector: ForegroundAppDetector

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        usageStats = shadowOf(context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
        shadowOf(context.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(false)
        setUsageAccess(true)
        detector = ForegroundAppDetector(context)
    }

    @Test
    fun permissionIsCheckedEvenWhenThereAreNoUsageEvents() {
        assertTrue(detector.hasUsageStatsPermission())
        setUsageAccess(false)
        assertFalse(detector.hasUsageStatsPermission())
    }

    @Test
    fun initialQueryFindsAppResumedLongerThanOneMinuteAgo() = runBlocking {
        addEvent("third.party", System.currentTimeMillis() - 3_600_000, UsageEvents.Event.ACTIVITY_RESUMED)

        assertEquals("third.party", detector.getCurrentForegroundApp())
        assertEquals(listOf("third.party"), detector.recentApps.value.map { it.packageName })
    }

    @Test
    fun backgroundSwitchIsRecordedWithoutUiSubscriber() = runBlocking {
        val now = System.currentTimeMillis()
        addEvent(context.packageName, now - 2_000, UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals(context.packageName, detector.getCurrentForegroundApp())

        addEvent(context.packageName, now - 1_000, UsageEvents.Event.ACTIVITY_PAUSED)
        addEvent("third.party", now - 900, UsageEvents.Event.ACTIVITY_RESUMED)
        nextQuery()
        assertEquals("third.party", detector.getCurrentForegroundApp())
        assertEquals(listOf(context.packageName, "third.party"), detector.recentApps.value.map { it.packageName })
    }

    @Test
    fun highRateCallsReuseLatestQuery() = runBlocking {
        val now = System.currentTimeMillis()
        addEvent("first.app", now - 2_000, UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("first.app", detector.getCurrentForegroundApp())
        addEvent("second.app", now - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)

        repeat(300) { assertEquals("first.app", detector.getCurrentForegroundApp()) }
        nextQuery()
        assertEquals("second.app", detector.getCurrentForegroundApp())
    }

    @Test
    fun revokedPermissionDoesNotReuseOldAppOrEmitFakePackage() = runBlocking {
        addEvent("third.party", System.currentTimeMillis() - 1_000, UsageEvents.Event.ACTIVITY_RESUMED)
        assertEquals("third.party", detector.getCurrentForegroundApp())
        setUsageAccess(false)
        nextQuery()

        assertEquals("", detector.getCurrentForegroundApp())
        assertEquals("", detector.getForegroundAppAt(SystemClock.elapsedRealtimeNanos()))
        setUsageAccess(true)
        nextQuery()
        assertEquals("third.party", detector.getCurrentForegroundApp())
    }

    @Test
    fun pauseAndScreenLockDoNotKeepLastUsedAppAsCurrent() = runBlocking {
        val now = System.currentTimeMillis()
        addEvent("third.party", now - 2_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("third.party", now - 1_000, UsageEvents.Event.ACTIVITY_PAUSED)
        assertEquals("", detector.getCurrentForegroundApp())
        addEvent("third.party", now - 500, UsageEvents.Event.ACTIVITY_RESUMED)
        nextQuery()
        shadowOf(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).setKeyguardLocked(true)
        assertEquals("", detector.getCurrentForegroundApp())
    }

    @Test
    fun stoppingOldActivityDoesNotClearItsResumedSameClassReplacement() = runBlocking {
        val now = System.currentTimeMillis()
        addEvent("third.party", now - 4_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("third.party", now - 3_000, UsageEvents.Event.ACTIVITY_PAUSED)
        addEvent("third.party", now - 2_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("third.party", now - 1_000, UsageEvents.Event.ACTIVITY_STOPPED)

        assertEquals("third.party", detector.getCurrentForegroundApp())
        assertEquals("third.party", detector.getForegroundAppAt(SystemClock.elapsedRealtimeNanos() - 500_000_000))
    }

    @Test
    fun delayedSensorSamplesUseAppAtMeasurementTime() = runBlocking {
        val now = System.currentTimeMillis()
        addEvent("first.app", now - 3_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("first.app", now - 700, UsageEvents.Event.ACTIVITY_PAUSED)
        addEvent("second.app", now - 600, UsageEvents.Event.ACTIVITY_RESUMED)

        assertEquals("first.app", detector.getForegroundAppAt(SystemClock.elapsedRealtimeNanos() - 1_000_000_000L))
        assertEquals("second.app", detector.getForegroundAppAt(SystemClock.elapsedRealtimeNanos()))
    }

    @Test
    fun bootstrapDoesNotPopulateUiWithAppsUsedBeforeCollection() = runBlocking {
        val now = System.currentTimeMillis()
        addEvent("old.app", now - 4_000, UsageEvents.Event.ACTIVITY_RESUMED)
        addEvent("old.app", now - 3_000, UsageEvents.Event.ACTIVITY_PAUSED)
        addEvent("current.app", now - 2_000, UsageEvents.Event.ACTIVITY_RESUMED)

        detector.getCurrentForegroundApp()
        assertEquals(listOf("current.app"), detector.recentApps.value.map { it.packageName })
    }

    private fun addEvent(packageName: String, timestamp: Long, type: Int) {
        usageStats.addEvent(ShadowUsageStatsManager.EventBuilder.buildEvent()
            .setPackage(packageName)
            .setClass("Activity")
            .setTimeStamp(timestamp)
            .setEventType(type)
            .build())
    }

    private fun setUsageAccess(granted: Boolean) {
        shadowOf(appOps).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            if (granted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_IGNORED)
    }

    private fun nextQuery() {
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
    }
}
