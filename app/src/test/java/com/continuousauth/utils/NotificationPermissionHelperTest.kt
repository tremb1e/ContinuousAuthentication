package com.continuousauth.utils

import android.Manifest
import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class NotificationPermissionHelperTest {
    @Test
    fun android13RequestsOnlyMissingNotificationPermission() {
        val application = RuntimeEnvironment.getApplication()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(NotificationPermissionHelper.shouldRequestNotificationPermission(application))
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(NotificationPermissionHelper.shouldRequestNotificationPermission(application))
    }

    @Test
    @Config(sdk = [30])
    fun olderAndroidDoesNotRequestUnsupportedRuntimePermission() {
        val application = RuntimeEnvironment.getApplication()
        assertFalse(NotificationPermissionHelper.shouldRequestNotificationPermission(application))
    }
}
