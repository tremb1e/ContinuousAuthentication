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

    @Test fun choicesPersistIndependentlyAndAllSensorsCanBeDisabled() {
        val settings = AppSettingsRepository(context)
        settings.setProtectionMode(ProtectionMode.OFF)
        settings.setSensorEnabled("gyroscope", false)
        val recreated = AppSettingsRepository(context)
        assertEquals(ProtectionMode.OFF, recreated.protectionMode.value)
        assertTrue(recreated.sensorSettings.value.accelerometerEnabled)
        assertFalse(recreated.sensorSettings.value.gyroscopeEnabled)
        assertTrue(recreated.sensorSettings.value.magnetometerEnabled)
        recreated.setSensorEnabled("accelerometer", false)
        recreated.setSensorEnabled("magnetometer", false)
        assertFalse(recreated.sensorSettings.value.anyEnabled)
    }

    @Test fun unknownStoredModeFallsBackToMonitoringWithoutEnablingAlerts() {
        context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString("protection_mode", "unknown-version-mode").commit()
        assertEquals(ProtectionMode.MONITOR_ONLY, AppSettingsRepository(context).protectionMode.value)
    }
}
