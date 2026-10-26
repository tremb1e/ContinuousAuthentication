package com.continuousauth.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ProtectionMode { OFF, MONITOR_ONLY, ALERT }

data class SensorCollectionSettings(
    val accelerometerEnabled: Boolean = true,
    val gyroscopeEnabled: Boolean = true,
    val magnetometerEnabled: Boolean = true
) {
    val anyEnabled: Boolean
        get() = accelerometerEnabled || gyroscopeEnabled || magnetometerEnabled

    fun isEnabled(key: String): Boolean = when (key) {
        "accelerometer" -> accelerometerEnabled
        "gyroscope" -> gyroscopeEnabled
        "magnetometer" -> magnetometerEnabled
        else -> false
    }
}

/** User choices also apply while no activity is present. */
@Singleton
class AppSettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val _protectionMode = MutableStateFlow(readProtectionMode())
    val protectionMode = _protectionMode.asStateFlow()
    private val _sensorSettings = MutableStateFlow(readSensorSettings())
    val sensorSettings = _sensorSettings.asStateFlow()

    // Keep a strong reference: SharedPreferences holds listeners weakly.
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init { prefs.registerOnSharedPreferenceChangeListener(listener) }

    fun setProtectionMode(mode: ProtectionMode) {
        prefs.edit().putString("protection_mode", mode.name).apply()
        refresh()
    }

    fun setSensorEnabled(key: String, enabled: Boolean) {
        require(key in SENSOR_KEYS) { "未知传感器：$key" }
        prefs.edit().putBoolean("sensor_$key", enabled).apply()
        refresh()
    }

    fun reset() {
        prefs.edit().clear().apply()
        refresh()
    }

    private fun refresh() {
        _protectionMode.value = readProtectionMode()
        _sensorSettings.value = readSensorSettings()
    }

    private fun readProtectionMode(): ProtectionMode =
        ProtectionMode.entries.firstOrNull { it.name == prefs.getString("protection_mode", null) }
            ?: ProtectionMode.MONITOR_ONLY

    private fun readSensorSettings() = SensorCollectionSettings(
        accelerometerEnabled = prefs.getBoolean("sensor_accelerometer", true),
        gyroscopeEnabled = prefs.getBoolean("sensor_gyroscope", true),
        magnetometerEnabled = prefs.getBoolean("sensor_magnetometer", true)
    )

    companion object {
        const val PREFS_NAME = "user_settings"
        val SENSOR_KEYS = setOf("accelerometer", "gyroscope", "magnetometer")
    }
}
