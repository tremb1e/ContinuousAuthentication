package com.continuousauth.settings

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class AppSettingsRepositoryTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test fun newInstallOnlyMonitorsAndAllowsEachSensor() {
        val settings = AppSettingsRepository(context)
        assertEquals(ProtectionMode.MONITOR_ONLY, settings.protectionMode.value)
        assertEquals(SensorCollectionSettings(), settings.sensorSettings.value)
    }

    @Test fun unifiedSensorChoicePersistsAndUpdatesEverySensorTogether() {
        val settings = AppSettingsRepository(context)
        settings.setProtectionMode(ProtectionMode.ALERT)
        settings.setAllSensorsEnabled(false)
        val recreated = AppSettingsRepository(context)
        assertEquals(ProtectionMode.ALERT, recreated.protectionMode.value)
        assertFalse(recreated.sensorSettings.value.accelerometerEnabled)
        assertFalse(recreated.sensorSettings.value.gyroscopeEnabled)
        assertFalse(recreated.sensorSettings.value.magnetometerEnabled)
        assertFalse(recreated.sensorSettings.value.anyEnabled)
        recreated.setAllSensorsEnabled(true)
        assertTrue(recreated.sensorSettings.value.allEnabled)
        assertTrue(AppSettingsRepository(context).sensorSettings.value.allEnabled)
    }

    @Test fun partiallyDisabledLegacySensorsMigrateToAllDisabledWithoutExpandingConsent() {
        val prefs = context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("sensor_gyroscope", false).commit()

        val settings = AppSettingsRepository(context)

        assertEquals(SensorCollectionSettings(false, false, false), settings.sensorSettings.value)
        assertFalse(prefs.contains("sensor_gyroscope"))
        assertFalse(AppSettingsRepository(context).sensorSettings.value.anyEnabled)
        settings.setAllSensorsEnabled(true)
        assertTrue(AppSettingsRepository(context).sensorSettings.value.allEnabled)
    }

    @Test fun fullyEnabledAndFullyDisabledLegacyChoicesKeepTheirMeaning() {
        val prefs = context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean("sensor_accelerometer", true)
            .putBoolean("sensor_gyroscope", true).putBoolean("sensor_magnetometer", true).commit()
        assertTrue(AppSettingsRepository(context).sensorSettings.value.allEnabled)

        prefs.edit().clear().putBoolean("sensor_accelerometer", false)
            .putBoolean("sensor_gyroscope", false).putBoolean("sensor_magnetometer", false).commit()
        assertFalse(AppSettingsRepository(context).sensorSettings.value.anyEnabled)
    }

    @Test fun obsoleteOffModeMigratesToMonitoringAndCannotBeReintroduced() {
        val prefs = context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("protection_mode", "OFF").commit()
        val settings = AppSettingsRepository(context)
        assertEquals(ProtectionMode.MONITOR_ONLY, settings.protectionMode.value)
        assertEquals("MONITOR_ONLY", prefs.getString("protection_mode", null))
        settings.setProtectionMode(ProtectionMode.ALERT)
        settings.setProtectionMode(ProtectionMode.OFF)
        assertEquals(ProtectionMode.MONITOR_ONLY, settings.protectionMode.value)
        assertEquals(ProtectionMode.MONITOR_ONLY, AppSettingsRepository(context).protectionMode.value)
    }

    @Test fun unknownStoredModeFallsBackToMonitoringWithoutEnablingAlerts() {
        context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("protection_mode", "unknown-version-mode").commit()
        assertEquals(ProtectionMode.MONITOR_ONLY, AppSettingsRepository(context).protectionMode.value)
    }
}
