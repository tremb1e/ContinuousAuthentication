package com.continuousauth.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.RootMatchers.isDialog as isViewDialog
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.fragment.app.DialogFragment
import com.continuousauth.auth.AuthHistoryEntry
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.service.DataCollectionService
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.testing.UiRegressionEntryPoint
import com.continuousauth.ui.compose.MainComposeActivity
import com.continuousauth.utils.BackgroundExecutionHelper
import com.continuousauth.utils.NotificationPermissionHelper
import com.continuousauth.utils.UsageStatsHelper
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs on the disposable Android test device: consent withdrawal deliberately clears its app data. */
@RunWith(AndroidJUnit4::class)
class MainComposeNavigationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var entryPoint: UiRegressionEntryPoint
    private var scenario: ActivityScenario<MainComposeActivity>? = null

    @Before
    fun resetDisposableDevice() {
        entryPoint = EntryPointAccessors.fromApplication(context, UiRegressionEntryPoint::class.java)
        resetState()
    }

    @After
    fun cleanup() {
        scenario?.close()
        scenario = null
        resetState()
    }

    private fun resetState() {
        if (!::entryPoint.isInitialized) return
        entryPoint.authenticationManager().stopAuthentication()
        context.stopService(Intent(context, DataCollectionService::class.java))
        runBlocking { entryPoint.privacyManager().withdrawConsentAndDeleteData().getOrThrow() }
        entryPoint.settingsRepository().reset()
        context.getSharedPreferences(MainComposeActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(MainComposeActivity.PRIVACY_AGREEMENT_SHOWN, false)
            .putBoolean("collection_requested", false).commit()
    }

    @Test
    fun decliningFirstNoticeKeepsAllTabsAvailableAndRecreationDoesNotRepeatNotice() {
        launchAndDecline()
        assertFalse(entryPoint.privacyManager().consentState.value == ConsentState.GRANTED)
        val tabs = listOf("偷用防护", "设置", "传感器数据", "详细信息")
        val positions = tabs.map { title ->
            tab(title).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.center.x
        }
        assertTrue(positions.zipWithNext().all { (left, right) -> left < right })
        tabs.drop(1).forEach { title -> tab(title).performClick(); tab(title).assertIsSelected() }
        tab("偷用防护").performClick()
        scenario!!.recreate()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.onNodeWithTag("authentication_screen").assertIsDisplayed()
        compose.onNodeWithTag("authentication_action").assertIsDisplayed()
    }

    @Test
    fun settingsHaveSixCollapsedCategoriesAndEveryHeaderExpandsAndCollapses() {
        launchAndDecline()
        tab("设置").performClick()
        val sections = listOf("consent", "protection", "background", "transmission", "server", "storage")
        sections.forEach { key -> compose.onNodeWithTag("section_content_$key").assertDoesNotExist() }
        sections.forEach { key ->
            expand(key)
            compose.onNodeWithTag("section_content_$key").assertExists()
            scrollToTag("section_$key").performClick()
            compose.onNodeWithTag("section_content_$key").assertDoesNotExist()
        }
    }

    @Test
    fun protectionShortcutExpandsSettingsAndHomeRemainsReachableAfterRepeatedVisits() {
        launchAndDecline()
        repeat(3) {
            scrollToTag("authentication_protection").performClick()
            tab("设置").assertIsSelected()
            compose.onNodeWithTag("settings_screen").assertIsDisplayed()
            compose.onNodeWithTag("section_content_protection").assertExists()
            scrollToTag("protection_monitor_only").assertIsDisplayed()
            // A new shortcut request must reopen a section the user previously collapsed.
            scrollToTag("section_protection").performClick()
            compose.onNodeWithTag("section_content_protection").assertDoesNotExist()
            tab("偷用防护").performClick().assertIsSelected()
            compose.onNodeWithTag("authentication_screen").assertIsDisplayed()
            compose.onNodeWithTag("settings_screen").assertDoesNotExist()
        }
        tab("设置").performClick().assertIsSelected()
        tab("传感器数据").performClick().assertIsSelected()
        tab("偷用防护").performClick().assertIsSelected()
        scenario!!.recreate()
        compose.onNodeWithTag("authentication_screen").assertIsDisplayed()
        scrollToTag("authentication_protection").performClick()
        compose.onNodeWithTag("section_content_protection").assertExists()
        tab("偷用防护").performClick().assertIsSelected()
        compose.onNodeWithTag("authentication_screen").assertIsDisplayed()
    }

    @Test
    fun consentDetailsCanBeReviewedFromSettingsAfterDeclining() {
        launchAndDecline()
        tab("设置").performClick()
        expand("consent")
        compose.onNodeWithText("会采集什么").assertExists()
        compose.onNodeWithText("保存多久").assertExists()
        scrollToTag("review_consent").performClick()
        compose.onNodeWithText("同意并继续").assertIsDisplayed()
        compose.onNodeWithText("暂不启用").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
    }

    @Test
    fun acceptingWithdrawingAndAcceptingAgainClearsPersistedAuthenticationHistory() {
        scenario = ActivityScenario.launch(MainComposeActivity::class.java)
        compose.onNodeWithText("同意并继续").assertIsDisplayed().performClick()
        // Skip the optional guidance through its real buttons, preserving all system grants.
        skipOptionalPermissionGuidance()
        compose.waitUntil(10_000) { entryPoint.privacyManager().consentState.value == ConsentState.GRANTED }

        val history = entryPoint.historyRepository()
        val result = AuthHistoryEntry("consent-lifecycle-test", System.currentTimeMillis(), false, "test.recorded.app", "测试记录应用")
        assertTrue(runBlocking { history.record(result) })
        runBlocking { history.refresh() }
        assertEquals(listOf(result), history.entries.value)

        tab("设置").performClick()
        expand("consent")
        scrollToTag("withdraw_consent").assertIsDisplayed().performClick()
        compose.onNodeWithText("撤回同意并删除数据？").assertIsDisplayed()
        compose.onNodeWithText("确认删除").performClick()
        compose.waitUntil(15_000) {
            entryPoint.privacyManager().consentState.value == ConsentState.WITHDRAWN &&
                entryPoint.privacyManager().deletionState.value != DeletionState.IN_PROGRESS
        }
        assertEquals(ConsentState.WITHDRAWN, entryPoint.privacyManager().consentState.value)
        assertTrue(history.entries.value.isEmpty())
        // A fresh repository reads the SQLite file, independently of the existing StateFlow cache.
        val reloadedHistory = com.continuousauth.auth.AuthHistoryRepository(context)
        try {
            runBlocking { reloadedHistory.refresh() }
            assertTrue(reloadedHistory.entries.value.isEmpty())
        } finally {
            reloadedHistory.close()
        }

        // The withdrawal confirmation closes in a native window. Its exit animation and
        // the deletion snackbar temporarily cover the review button despite Compose being idle.
        // Acknowledge the visible feedback through its real action. Accessibility settings
        // and the Compose test clock can lengthen automatic snackbar dismissal.
        if (entryPoint.privacyManager().deletionState.value == DeletionState.PARTIAL_SUCCESS) {
            val feedback = "本地数据已删除，服务端删除尚未完成"
            compose.waitUntil(10_000) { compose.onAllNodesWithText(feedback).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(feedback).assertIsDisplayed()
            compose.onNodeWithText("知道了").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(feedback).fetchSemanticsNodes().isEmpty() }
        }
        compose.waitUntil(10_000) {
            var focused = false
            scenario!!.onActivity { focused = it.window.decorView.hasWindowFocus() }
            focused
        }
        scrollToTag("review_consent").assertIsDisplayed().performClick()
        compose.onNodeWithText("同意并继续").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) { entryPoint.privacyManager().consentState.value == ConsentState.GRANTED }
        compose.onNodeWithTag("withdraw_consent").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        runBlocking { history.refresh() }
        assertTrue(history.entries.value.isEmpty())
    }

    @Test
    fun detailCategoriesStartCollapsedAndAllCanBeRead() {
        launchAndDecline()
        tab("详细信息").performClick()
        val sections = listOf("auth_details", "connection_details", "buffer_details", "time_details", "sensor_details",
            "device_security", "server_policy", "performance", "identifiers", "manual_analysis")
        sections.forEach { key -> compose.onNodeWithTag("section_content_$key").assertDoesNotExist() }
        sections.forEach { key ->
            expand(key)
            compose.onNodeWithTag("section_content_$key").assertExists()
            scrollToTag("section_$key").performClick()
        }
    }

    @Test
    fun sensorChartsRemainReachableAndExpandWithoutKeepingOffscreenChartsComposed() {
        launchAndDecline()
        tab("传感器数据").performClick()
        scrollToTag("sensor_reading_accelerometer").assertIsDisplayed()
        scrollToTag("sensor_chart_toggle").performClick()
        listOf("accelerometer", "gyroscope", "magnetometer").forEach { sensor ->
            scrollToTag("sensor_chart_$sensor").assertIsDisplayed()
        }
        scrollToTag("sensor_chart_toggle").performClick()
        listOf("accelerometer", "gyroscope", "magnetometer").forEach { sensor ->
            compose.onNodeWithTag("sensor_chart_$sensor").assertDoesNotExist()
            scrollToTag("sensor_reading_$sensor").assertIsDisplayed()
        }
    }

    @Test
    fun sensorCollectionProtectionAndExpandedSectionsSurviveNavigationAndRecreation() {
        launchAndDecline()
        tab("设置").performClick()
        expand("consent")
        scrollToTag("sensor_collection").assertIsOn().performClick().assertIsOff()
        assertFalse(entryPoint.settingsRepository().sensorSettings.value.anyEnabled)
        scrollToTag("sensor_collection").performClick().assertIsOn()
        val enabledSensors = entryPoint.settingsRepository().sensorSettings.value
        assertTrue(enabledSensors.accelerometerEnabled && enabledSensors.gyroscopeEnabled && enabledSensors.magnetometerEnabled)
        scrollToTag("sensor_collection").performClick().assertIsOff()
        expand("protection")
        compose.onNodeWithTag("protection_monitor_only").assertIsSelected()
        compose.onNodeWithTag("protection_off").assertDoesNotExist()
        scrollToTag("protection_alert").performClick().assertIsSelected()
        assertEquals(ProtectionMode.ALERT, entryPoint.settingsRepository().protectionMode.value)
        tab("详细信息").performClick()
        tab("设置").performClick()
        scrollToTag("section_consent")
        compose.onNodeWithTag("section_content_consent").assertExists()
        scrollToTag("section_protection")
        compose.onNodeWithTag("section_content_protection").assertExists()
        scenario!!.recreate()
        scrollToTag("protection_alert").assertIsSelected()
        scrollToTag("sensor_collection").assertIsOff()
        assertFalse(entryPoint.settingsRepository().sensorSettings.value.anyEnabled)
    }

    @Test
    fun wifiOnlyIsOffByDefaultAndChoiceSurvivesRecreation() {
        launchAndDecline()
        tab("设置").performClick()
        expand("transmission")
        scrollToTag("wifi_only").assertIsOff().performClick().assertIsOn()
        scenario!!.recreate()
        scrollToTag("wifi_only").assertIsOn()
    }

    @Test
    fun storageShowsFixedRetentionAndDoesNotOfferAnEditableDuration() {
        launchAndDecline()
        tab("设置").performClick()
        expand("storage")
        compose.onNodeWithText("认证记录保存期限").assertExists()
        compose.onNodeWithText("固定 30 天").assertExists()
        compose.onNodeWithText("传感器缓存保存期限").assertExists()
        compose.onNodeWithText("固定 7 天").assertExists()
        compose.onNodeWithTag("retention_days").assertDoesNotExist()
        compose.onNodeWithText("保存天数").assertDoesNotExist()
        assertEquals(7, entryPoint.privacyManager().getDataRetentionDays())
    }

    @Test
    fun serverInputRejectsPageUrlsAndInvalidPortsBeforeSaving() {
        launchAndDecline()
        tab("设置").performClick()
        expand("server")
        scrollToTag("server_address").performTextReplacement("https://example.com/page")
        scrollToText("保存服务器设置").performClick()
        compose.onNodeWithText("请输入主机地址，例如 https://ca.macrz.com，不要包含页面路径").assertExists()
        scrollToTag("server_address").performTextReplacement("https://example.com")
        scrollToTag("server_port").performTextReplacement("0")
        scrollToText("保存服务器设置").performClick()
        compose.onNodeWithText("端口应为 1 至 65535 之间的整数").assertExists()
        scrollToText("恢复默认地址").performClick()
        scrollToText("保存服务器设置").performClick()
        compose.onNodeWithText("服务器设置已保存").assertExists()
    }

    @Test
    fun clearingCacheRequiresExplicitConfirmationAndCancelLeavesScreenUsable() {
        launchAndDecline()
        tab("设置").performClick()
        expand("storage")
        scrollToTag("clear_queue").performClick()
        compose.onNodeWithText("清理本机传感器缓存？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("清理本机传感器缓存？").assertDoesNotExist()
        compose.onNodeWithTag("clear_queue").assertIsDisplayed()
    }

    @Test
    fun alertModePermissionButtonOpensRealSystemSettingsAndReturns() {
        launchAndDecline()
        tab("设置").performClick()
        expand("protection")
        scrollToTag("protection_alert").performClick()
        val overlayFilter = IntentFilter(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            // The actual button supplies a package: URI; action-only filters reject that data.
            addDataScheme("package")
        }
        val monitor = instrumentation.addMonitor(overlayFilter, null, false)
        try {
            scrollToTag("overlay_permission").performClick()
            compose.waitUntil(10_000) { monitor.hits > 0 }
            assertTrue(monitor.hits > 0)
            val systemPackage = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                .resolveActivity(context.packageManager)?.packageName
            if (systemPackage != null) {
                compose.waitUntil(10_000) {
                    instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == systemPackage
                }
            }
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            compose.waitUntil(10_000) { scenario?.state == Lifecycle.State.RESUMED }
            compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun launchAndDecline() {
        scenario = ActivityScenario.launch(MainComposeActivity::class.java)
        compose.onNodeWithText("暂不启用").assertIsDisplayed().performClick()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    private fun skipOptionalPermissionGuidance() {
        val expectedDialogs = buildList {
            if (NotificationPermissionHelper.shouldRequestNotificationPermission(context)) add("notification_permission")
            if (!UsageStatsHelper.hasUsageStatsPermission(context)) add("usage_stats_permission")
            if (!BackgroundExecutionHelper.isBatteryOptimizationExempt(context)) add("battery_optimization")
        }
        expectedDialogs.forEach { tag ->
            compose.waitUntil(10_000) {
                var showing = false
                scenario!!.onActivity { activity ->
                    showing = (activity.supportFragmentManager.findFragmentByTag(tag) as? DialogFragment)
                        ?.dialog?.isShowing == true
                }
                showing
            }
            onView(withId(android.R.id.button2)).inRoot(isViewDialog()).perform(click())
        }
    }

    /** Lazy pages compose only visible items. Scroll the list to locate an item first,
     * then scroll within a tall expanded item to expose the actual control. */
    private fun scrollScreenTo(matcher: SemanticsMatcher) {
        val screen = listOf("settings_screen", "details_screen", "sensors_screen", "authentication_screen")
            .first { compose.onAllNodesWithTag(it).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(screen).performScrollToNode(matcher)
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        scrollScreenTo(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        scrollScreenTo(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun tab(title: String): SemanticsNodeInteraction = compose.onNode(hasText(title) and hasClickAction())

    private fun expand(key: String) {
        scrollToTag("section_$key").performClick()
    }
}
