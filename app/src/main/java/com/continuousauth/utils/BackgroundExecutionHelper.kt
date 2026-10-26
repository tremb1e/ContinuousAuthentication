package com.continuousauth.utils

import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.Log

object BackgroundExecutionHelper {
    fun isBatteryOptimizationExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun isBackgroundRestricted(context: Context): Boolean =
        context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

    fun openAppSettings(context: Context, launch: (Intent) -> Unit): Boolean =
        openFirstAvailable(listOf(appSettingsIntent(context)), launch)

    fun openBatterySettings(context: Context, launch: (Intent) -> Unit): Boolean {
        val intents = buildList {
            if (!isBatteryOptimizationExempt(context)) {
                add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}")))
            }
            add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            add(appSettingsIntent(context))
        }
        return openFirstAvailable(intents, launch)
    }

    private fun openFirstAvailable(intents: List<Intent>, launch: (Intent) -> Unit): Boolean {
        for (intent in intents) {
            try {
                launch(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                Log.w("BackgroundExecution", "Battery settings activity unavailable", e)
            } catch (e: SecurityException) {
                Log.w("BackgroundExecution", "Battery settings activity denied", e)
            }
        }
        return false
    }
}
