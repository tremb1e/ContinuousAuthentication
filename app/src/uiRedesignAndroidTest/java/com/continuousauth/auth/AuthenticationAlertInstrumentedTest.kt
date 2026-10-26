package com.continuousauth.auth

import android.content.Context
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses actual TYPE_APPLICATION_OVERLAY windows and real app-ops on an Android device. */
@RunWith(AndroidJUnit4::class)
class AuthenticationAlertInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var alerts: AuthenticationAlertManager
    private var originallyAllowed = false

    @Before fun setUp() {
        context = instrumentation.targetContext.applicationContext
        originallyAllowed = Settings.canDrawOverlays(context)
        alerts = AuthenticationAlertManager(context)
    }

    @After fun tearDown() {
        instrumentation.runOnMainSync { alerts.dismiss() }
        setOverlayPermission(originallyAllowed)
    }

    @Test fun permissionCreatesARealWindowAndRepeatedFailuresReuseIt() {
        setOverlayPermission(true)
        instrumentation.runOnMainSync { assertTrue(alerts.showFailure("阅读")) }
        instrumentation.waitForIdleSync()
        val first = overlay()
        assertNotNull(first)
        assertTrue(first!!.isAttachedToWindow)
        assertNotNull(first.windowToken)
        assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            (first.layoutParams as WindowManager.LayoutParams).type)

        instrumentation.runOnMainSync { assertTrue(alerts.showFailure("相册")) }
        instrumentation.waitForIdleSync()
        assertSame(first, overlay())
        assertTrue(descendants(first).filterIsInstance<TextView>().any { it.text.contains("相册") })

        instrumentation.runOnMainSync { alerts.dismiss() }
        instrumentation.waitForIdleSync()
        assertFalse(first.isAttachedToWindow)
    }

    @Test fun deniedOrRevokedPermissionCannotLeaveAnOverlayVisible() {
        setOverlayPermission(false)
        instrumentation.runOnMainSync { assertFalse(alerts.showFailure("阅读")) }
        assertEquals(null, overlay())
        setOverlayPermission(true)
        instrumentation.runOnMainSync { assertTrue(alerts.showFailure("阅读")) }
        instrumentation.waitForIdleSync()
        val first = overlay()!!
        setOverlayPermission(false)
        instrumentation.runOnMainSync { assertFalse(alerts.showFailure("阅读")) }
        instrumentation.waitForIdleSync()
        assertEquals(null, overlay())
        assertFalse(first.isAttachedToWindow)
    }

    @Test fun allActionsRemainReachableWhenLargeTextExceedsTheWindowHeight() {
        setOverlayPermission(true)
        instrumentation.runOnMainSync { assertTrue(alerts.showFailure("一个很长的应用名称".repeat(15))) }
        instrumentation.waitForIdleSync()
        val scroll = overlay() as ScrollView
        awaitUi("Overlay should finish its first layout") { scroll.height > 0 && scroll.getChildAt(0).height > 0 }
        val buttons = descendants(scroll).filterIsInstance<Button>()
        val dismiss = buttons.single { it.text.toString() == "知道了" }
        val originalContentHeight = scroll.getChildAt(0).height
        instrumentation.runOnMainSync {
            // Deliberately make the content taller than a landscape phone window.
            descendants(scroll).filterIsInstance<TextView>().forEach { it.textSize = 42f }
            scroll.requestLayout()
        }
        // IdleSync alone can return before the next Choreographer traversal; scrolling
        // before that traversal uses the old content height and does not reach the action.
        awaitUi("Large text should be remeasured before scrolling") {
            scroll.getChildAt(0).height > originalContentHeight
        }
        instrumentation.runOnMainSync {
            scroll.isSmoothScrollingEnabled = false
            scroll.fullScroll(View.FOCUS_DOWN)
        }
        val visible = Rect()
        awaitUi("Dismiss action should be fully reachable after scrolling") {
            dismiss.getGlobalVisibleRect(visible) && visible.height() >= dismiss.height - 1
        }
        assertTrue("Dismiss target must stay at least 48dp tall", dismiss.height >= (48 * context.resources.displayMetrics.density).toInt())
        assertTrue("The entire dismissal action must be reachable by scrolling", visible.height() >= dismiss.height - 1)
        instrumentation.runOnMainSync { dismiss.performClick() }
        instrumentation.waitForIdleSync()
        assertFalse(scroll.isAttachedToWindow)
    }

    private fun overlay(): View? = AuthenticationAlertManager::class.java.getDeclaredField("overlay")
        .apply { isAccessible = true }.get(alerts) as? View

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 3_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync { ready = condition() }
            if (!ready) SystemClock.sleep(16)
        }
        assertTrue(message, ready)
    }

    private fun setOverlayPermission(allowed: Boolean) {
        val command = "appops set ${context.packageName} SYSTEM_ALERT_WINDOW ${if (allowed) "allow" else "deny"}"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use {
            it.readBytes()
        }
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (Settings.canDrawOverlays(context) != allowed && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(25)
        }
        assertEquals(allowed, Settings.canDrawOverlays(context))
    }
}
