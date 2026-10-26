package com.continuousauth.ui.dialogs

import android.app.Application
import android.content.DialogInterface
import androidx.fragment.app.DialogFragment
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class PermissionDialogsTest {
    @Test
    fun fragmentsCanBeRestoredAndCancellationAdvancesGuidance() {
        val results = mutableListOf<Boolean>()
        val notification = NotificationPermissionDialog::class.java.getDeclaredConstructor().newInstance()
        val usage = UsageStatsPermissionDialog::class.java.getDeclaredConstructor().newInstance()
        val battery = BatteryOptimizationDialog::class.java.getDeclaredConstructor().newInstance()
        notification.setOnResultListener { results += it }
        usage.setOnResultListener { results += it }
        battery.setOnResultListener { results += it }
        val dialog = mockk<DialogInterface>(relaxed = true)

        listOf<DialogFragment>(notification, usage, battery).forEach { it.onCancel(dialog) }

        assertEquals(listOf(false, false, false), results)
    }
}
