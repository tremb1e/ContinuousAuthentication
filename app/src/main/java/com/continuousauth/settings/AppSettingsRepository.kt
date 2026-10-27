package com.continuousauth.settings

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

// OFF is retained only to recognize values written by older app versions.
enum class ProtectionMode { OFF, MONITOR_ONLY, ALERT }

data class SensorCollectionSettings(
    val accelerometerEnabled: Boolean = true,
    val gyroscopeEnabled: Boolean = true,
    val magnetometerEnabled: Boolean = true
) {
    val anyEnabled: Boolean
        get() = accelerometerEnabled || gyroscopeEnabled || magnetometerEnabled

    val allEnabled: Boolean
        get() = accelerometerEnabled && gyroscopeEnabled && magnetometerEnabled

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
        val effectiveMode = if (mode == ProtectionMode.OFF) ProtectionMode.MONITOR_ONLY else mode
        prefs.edit().putString("protection_mode", effectiveMode.name).apply()
        refresh()
    }

    fun setAllSensorsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SENSOR_COLLECTION, enabled).apply()
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

    private fun readProtectionMode(): ProtectionMode {
        val stored = prefs.getString("protection_mode", null)
        val mode = ProtectionMode.entries.firstOrNull { it != ProtectionMode.OFF && it.name == stored }
            ?: ProtectionMode.MONITOR_ONLY
        if (stored != mode.name) prefs.edit().putString("protection_mode", mode.name).apply()
        return mode
    }

    private fun readSensorSettings(): SensorCollectionSettings {
        val enabled = if (prefs.contains(KEY_SENSOR_COLLECTION)) {
            prefs.getBoolean(KEY_SENSOR_COLLECTION, true)
        } else {
            // Never re-enable a sensor the user had disabled before the controls were merged.
            SENSOR_KEYS.all { prefs.getBoolean("sensor_$it", true) }.also { allowed ->
                prefs.edit().putBoolean(KEY_SENSOR_COLLECTION, allowed).apply {
                    SENSOR_KEYS.forEach { remove("sensor_$it") }
                }.apply()
            }
        }
        return SensorCollectionSettings(enabled, enabled, enabled)
    }

    companion object {
        const val PREFS_NAME = "user_settings"
        private const val KEY_SENSOR_COLLECTION = "sensor_collection_enabled"
        private val SENSOR_KEYS = setOf("accelerometer", "gyroscope", "magnetometer")
    }
}
