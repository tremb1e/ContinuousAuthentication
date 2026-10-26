package com.continuousauth.utils

import android.app.ActivityManager
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class BackgroundExecutionHelperTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun previouslyOfferedGuidanceDoesNotClaimBatteryExemption() {
        context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit()
            .putBoolean("battery_optimization_requested", true).commit()
        val powerManager = shadowOf(context.getSystemService(PowerManager::class.java))
        powerManager.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BackgroundExecutionHelper.isBatteryOptimizationExempt(context))

        powerManager.setIgnoringBatteryOptimizations(context.packageName, true)
        assertTrue(BackgroundExecutionHelper.isBatteryOptimizationExempt(context))
        powerManager.setIgnoringBatteryOptimizations(context.packageName, false)
        assertFalse(BackgroundExecutionHelper.isBatteryOptimizationExempt(context))
    }

    @Test
    fun batteryRequestTargetsThisPackageAndCanBeOpenedAgainAfterDenial() {
        val launched = mutableListOf<Intent>()
        repeat(2) {
            assertTrue(BackgroundExecutionHelper.openBatterySettings(context) { launched += it })
        }
        assertEquals(2, launched.size)
        launched.forEach {
            assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, it.action)
            assertEquals("package:${context.packageName}", it.data.toString())
        }
    }

    @Test
    fun unavailableRequestFallsBackToSettingsAndThenAppDetails() {
        val attempted = mutableListOf<Intent>()
        assertTrue(BackgroundExecutionHelper.openBatterySettings(context) {
            attempted += it
            when (it.action) {
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS -> throw SecurityException()
                Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS -> throw ActivityNotFoundException()
            }
        })
        assertEquals(3, attempted.size)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempted.last().action)
        assertEquals("package:${context.packageName}", attempted.last().data.toString())
    }

    @Test
    fun noAvailableSettingsReturnsFailure() {
        assertFalse(BackgroundExecutionHelper.openBatterySettings(context) { throw ActivityNotFoundException() })
        assertFalse(BackgroundExecutionHelper.openAppSettings(context) { throw SecurityException() })
    }

    @Test
    fun alreadyExemptAppOpensSettingsWithoutRequestingAgain() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        val launched = mutableListOf<Intent>()
        assertTrue(BackgroundExecutionHelper.openBatterySettings(context) { launched += it })
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, launched.single().action)
    }

    @Test
    fun backgroundRestrictionIsIndependentOfBatteryExemption() {
        shadowOf(context.getSystemService(PowerManager::class.java))
            .setIgnoringBatteryOptimizations(context.packageName, true)
        val activityManager = shadowOf(context.getSystemService(ActivityManager::class.java))
        activityManager.setBackgroundRestricted(true)
        assertTrue(BackgroundExecutionHelper.isBackgroundRestricted(context))
        activityManager.setBackgroundRestricted(false)
        assertFalse(BackgroundExecutionHelper.isBackgroundRestricted(context))
    }
}
