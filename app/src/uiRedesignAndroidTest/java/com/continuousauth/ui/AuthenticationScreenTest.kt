package com.continuousauth.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.continuousauth.auth.AuthHistoryEntry
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.ui.compose.screens.AuthenticationContent
import com.continuousauth.ui.compose.screens.HistoryDateRangeContent
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthenticationScreenTest {
    @get:Rule val compose = createComposeRule()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 26)
    private val now = today.atTime(14, 30).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun protectionShortcutOpensSettingsWithoutStartingAuthentication() {
        var settingsOpened = 0
        var started = 0
        show(onSettings = { settingsOpened++ }, onStart = { started++ })
        scrollToTag("authentication_protection").performClick()
        assertEquals(1, settingsOpened)
        assertEquals(0, started)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun monitorModeShowsPassiveFailureWithoutModal() {
        show(state = failedState(), mode = ProtectionMode.MONITOR_ONLY)
        scrollToTag("authentication_warning").assertIsDisplayed()
        compose.onNodeWithText("当前为仅监测模式，不会弹窗或限制使用。").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun alertModeShowsRecentFailureAndPermissionExplanation() {
        show(state = failedState(), mode = ProtectionMode.ALERT)
        scrollToTag("authentication_warning").assertIsDisplayed()
        compose.onNodeWithText("当前为即时警示模式。系统弹窗需要在设置中允许显示在其他应用上层。").assertExists()
    }

    @Test
    fun historyStartsCollapsedAndEmptyResultsDoNotDuplicateStartAction() {
        var started = 0
        show(onStart = { started++ })
        compose.onNodeWithText("偷用防护").assertExists()
        compose.onNodeWithText("持续身份认证技术，使用三种传感器数据持续建模使用中的用户身份并进行认证").assertExists()
        compose.onNodeWithText("偷用防护启用开关").assertExists()
        compose.onNodeWithTag("section_content_authentication_history").assertDoesNotExist()
        compose.onNodeWithTag("history_chart").assertDoesNotExist()
        compose.onNodeWithTag("authentication_action").performClick()
        assertEquals(1, started)
        scrollToTag("history_total", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("暂无认证记录").assertExists()
        compose.onNodeWithText("这个时段没有收到认证结果，不代表全部通过。").assertDoesNotExist()
        compose.onNodeWithText("开始认证后，结果会自动出现在这里。也可以切换日期，查看已有记录。").assertDoesNotExist()
        compose.onAllNodes(hasText("启用认证") and hasAnyAncestor(hasTestTag("section_content_authentication_history")))
            .assertCountEquals(0)
        scrollToTag("section_authentication_history").performClick()
        compose.onNodeWithTag("section_content_authentication_history").assertDoesNotExist()
        compose.onNodeWithTag("history_chart").assertDoesNotExist()
    }

    @Test
    fun allPassedIsDifferentFromNoData() {
        show(history = listOf(AuthHistoryEntry("pass", now, true, "sample.app", "示例应用")))
        scrollToTag("history_total", useUnmergedTree = true)
        compose.onNodeWithText("0 次不通过").assertExists()
        compose.onNodeWithText("共收到 1 次结果 · 1 次通过").assertExists()
        compose.onNodeWithText("暂无认证记录").assertDoesNotExist()
    }

    @Test
    fun preparingCanBeCancelledAndRunningCanBeStopped() {
        var stopped = 0
        show(state = ContinuousAuthUiState(isStarting = true), onStop = { stopped++ })
        compose.onNodeWithText("关闭认证").assertExists()
        compose.onNodeWithTag("authentication_preparing").assertExists()
        compose.onNodeWithTag("authentication_action").assertIsNotEnabled()
        scrollToTag("authentication_stop").assertIsEnabled().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun runningShowsWaitingForResultAndStopAction() {
        var stopped = 0
        show(state = ContinuousAuthUiState(authActive = true), onStop = { stopped++ })
        compose.onNodeWithText("尚未收到认证结果，请继续正常使用手机。").assertExists()
        compose.onNodeWithText("关闭认证").assertExists()
        compose.onNodeWithTag("authentication_action").assertIsNotEnabled()
        scrollToTag("authentication_stop").assertIsEnabled().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun collectionCanBeStoppedWhileAuthenticationIsInactive() {
        var stopped = 0
        show(collectionActive = true, onStop = { stopped++ })
        compose.onNodeWithTag("authentication_action").assertIsEnabled()
        scrollToTag("authentication_stop").assertIsEnabled().performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun stoppingPreventsRepeatedStartAndStopRequests() {
        show(collectionActive = true, isStopping = true)
        compose.onNodeWithTag("authentication_action").assertIsNotEnabled()
        scrollToTag("authentication_stop").assertIsNotEnabled()
        compose.onNodeWithText("正在关闭…").assertExists()
    }

    @Test
    fun consentIsActionableWithoutStartingCollection() {
        var reviewed = 0
        var started = 0
        show(consent = false, onStart = { started++ }, onConsent = { reviewed++ })
        compose.onNodeWithTag("authentication_action").performClick()
        assertEquals(1, reviewed)
        assertEquals(0, started)
    }

    @Test
    fun weekDaySelectionSurvivesCollapseFiltersApplicationsAndCanBeCleared() {
        val yesterdayTime = today.minusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        show(history = listOf(AuthHistoryEntry("today", now, false, "app.today", "今日应用"),
            AuthHistoryEntry("yesterday", yesterdayTime, false, "app.yesterday", "昨日应用")))
        scrollToTag("history_period_WEEK").performClick()
        awaitHistory()
        assertChartFits(today.minusDays(6), 7, hourly = false)
        compose.onNodeWithText("2 次不通过").assertExists()
        val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        scrollToTag("history_bar_$yesterdayStart").performClick()
        awaitHistory()
        scrollToTag("history_selected_bucket").assertIsDisplayed()
        compose.onNodeWithText("昨日应用").assertExists()
        compose.onNodeWithText("今日应用").assertDoesNotExist()
        scrollToTag("section_authentication_history").performClick()
        compose.onNodeWithTag("section_content_authentication_history").assertDoesNotExist()
        scrollToTag("history_period_WEEK").assertIsSelected()
        compose.onNodeWithTag("history_bar_$yesterdayStart").assertIsSelected()
        compose.onNodeWithText("昨日应用").assertExists()
        compose.onNodeWithText("今日应用").assertDoesNotExist()
        scrollToText("查看整个时段").assertIsDisplayed().performClick()
        awaitHistory()
        compose.onNodeWithTag("history_selected_bucket").assertDoesNotExist()
        compose.onNodeWithText("今日应用").assertExists()
    }

    @Test
    fun allTwentyFourHoursFitWithoutHorizontalScrollingAndEdgeBarsAreClickable() {
        show()
        assertChartFits(today, 24, hourly = true)
        listOf(0, 23).forEach { hour ->
            val start = today.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
            scrollToTag("history_chart")
            compose.onNodeWithTag("history_bar_$start").performTouchInput { click(center) }
            awaitHistory()
            compose.onNodeWithTag("history_bar_$start").assertIsSelected()
            scrollToTag("history_selected_bucket").assertIsDisplayed()
        }
    }

    @Test
    fun customRangeCanBeOpenedCancelledAndConfirmed() {
        show()
        scrollToTag("history_period_CUSTOM").performClick()
        compose.onNodeWithText("选择最近 7 天内的日期").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        scrollToTag("history_period_CUSTOM").performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("选择最近 7 天内的日期").assertDoesNotExist()
        compose.onNodeWithTag("history_date_range").assertExists()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test
    fun shortLargeFontDatePickerKeepsDaySelectionAndConfirmationReachable() {
        var confirmed: AuthHistoryDateRange? = null
        var startDescription = ""
        var endDescription = ""
        compose.setContent {
            val density = LocalDensity.current.density
            val locale = LocalConfiguration.current.locales[0]
            val formatter = DatePickerDefaults.dateFormatter()
            startDescription = formatter.formatDate(today.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                locale, forContentDescription = true).orEmpty()
            endDescription = formatter.formatDate(today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
                locale, forContentDescription = true).orEmpty()
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                MaterialTheme {
                    HistoryDateRangeContent(today, AuthHistoryDateRange(today, today), {}, { confirmed = it },
                        Modifier.width(640.dp).height(300.dp))
                }
            }
        }
        compose.runOnIdle { assertTrue(startDescription.isNotBlank() && endDescription.isNotBlank()) }
        val bounds = compose.onNodeWithTag("history_date_picker").getUnclippedBoundsInRoot()
        listOf(startDescription, endDescription).forEach { description ->
            // Material exposes its full date as semantic Text; the Android accessibility
            // bridge presents the same value as a content description in UIAutomator.
            compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(description, substring = true))
            val day = compose.onNodeWithText(description, substring = true, useUnmergedTree = true)
                .performScrollTo().assertIsDisplayed()
            val dayBounds = day.getUnclippedBoundsInRoot()
            assertTrue("A complete day target must fit inside the short picker", dayBounds.top >= bounds.top && dayBounds.bottom <= bounds.bottom)
            day.performTouchInput { click(center) }
        }
        compose.onNodeWithTag("history_date_cancel").assertIsDisplayed()
        compose.onNodeWithTag("history_date_confirm").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(AuthHistoryDateRange(today.minusDays(1), today), confirmed) }
    }

    @Test
    fun yesterdayAndHourlySelectionKeepApplicationAssociationsSeparate() {
        val yesterday = today.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val earlierToday = today.atTime(9, 15).atZone(zone).toInstant().toEpochMilli()
        show(history = listOf(AuthHistoryEntry("yesterday", yesterday, false, "app.yesterday", "昨日应用"),
            AuthHistoryEntry("morning", earlierToday, false, "app.morning", "早间应用"),
            AuthHistoryEntry("afternoon", now, false, "app.afternoon", "午后应用")))
        scrollToTag("history_period_YESTERDAY").performClick()
        awaitHistory()
        compose.onNodeWithText("1 次不通过").assertExists()
        compose.onNodeWithText("昨日应用").assertExists()
        compose.onNodeWithText("早间应用").assertDoesNotExist()
        scrollToTag("history_period_TODAY").performClick()
        awaitHistory()
        val hourStart = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        scrollToTag("history_bar_$hourStart").performClick()
        awaitHistory()
        compose.onNodeWithText("午后应用").assertExists()
        compose.onNodeWithText("早间应用").assertDoesNotExist()
    }

    @Test
    fun occurrenceTimesAreCollapsedInitiallyAndOlderRecordsRemainReachable() {
        val entries = (0..20).map { index ->
            AuthHistoryEntry("event-$index", now - index * 60_000L, false, "sample.app", "示例应用")
        }
        show(history = entries)
        compose.onNodeWithTag("section_content_failure_events").assertDoesNotExist()
        scrollToTag("section_failure_events").performClick()
        compose.onAllNodesWithText("示例应用").assertCountEquals(21)
        scrollToText("下一页").performClick()
        compose.onAllNodesWithText("示例应用").assertCountEquals(2)
        compose.onNodeWithText(formatAuthHistoryTime(entries.last().timestampMs)).assertExists()
        scrollToText("上一页").performClick()
        compose.onAllNodesWithText("示例应用").assertCountEquals(21)
    }

    @Test
    fun largeFontKeepsPrimaryActionAndPermissionRecoveryReachable() {
        var permissionOpened = 0
        show(fontScale = 2f, usageAccess = false, onUsage = { permissionOpened++ },
            history = listOf(AuthHistoryEntry("unknown", now, false, "", "")))
        scrollToTag("authentication_action").assertIsDisplayed()
        scrollToTag("authentication_stop").assertIsDisplayed()
        scrollToTag("history_period_WEEK").assertIsDisplayed().performClick()
        awaitHistory()
        assertChartFits(today.minusDays(6), 7, hourly = false)
        scrollToText("未识别的应用").assertIsDisplayed()
        scrollToText("了解并开启应用识别").performClick()
        assertEquals(1, permissionOpened)
    }

    private fun awaitHistory() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("history_loading").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithText("正在整理记录…").fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun scrollToTag(tag: String, useUnmergedTree: Boolean = false): SemanticsNodeInteraction {
        if (tag.startsWith("history_") || tag.contains("failure_events")) {
            expandHistory()
            compose.onNodeWithTag("authentication_screen", useUnmergedTree = true).performScrollToNode(hasTestTag("history_total"))
            awaitHistory()
        }
        compose.onNodeWithTag("authentication_screen", useUnmergedTree = true).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag, useUnmergedTree).performScrollTo()
    }

    private fun expandHistory() {
        compose.onNodeWithTag("authentication_screen").performScrollToNode(hasTestTag("section_authentication_history"))
        if (compose.onAllNodesWithTag("section_content_authentication_history").fetchSemanticsNodes().isEmpty()) {
            compose.onNodeWithTag("section_authentication_history").performClick()
        }
    }

    private fun assertChartFits(firstDay: LocalDate, count: Int, hourly: Boolean) {
        val chart = scrollToTag("history_chart").assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.HorizontalScrollAxisRange))
            .fetchSemanticsNode().boundsInRoot
        repeat(count) { index ->
            val start = if (hourly) firstDay.atStartOfDay(zone).plusHours(index.toLong())
                else firstDay.plusDays(index.toLong()).atStartOfDay(zone)
            val bar = compose.onNodeWithTag("history_bar_${start.toInstant().toEpochMilli()}")
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("Bucket $index falls outside the chart", bar.left >= chart.left - 1 && bar.right <= chart.right + 1)
            assertTrue("Bucket $index has no visible width", bar.width > 0f)
        }
    }

    private fun scrollToText(text: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("authentication_screen", useUnmergedTree = true).performScrollToNode(hasText(text))
        return compose.onNodeWithText(text).performScrollTo()
    }

    private fun failedState() = ContinuousAuthUiState(authActive = true, lastDecision = AuthDecision.ABNORMAL,
        lastUpdateTime = now, authFailure = true)

    private fun show(
        state: ContinuousAuthUiState = ContinuousAuthUiState(),
        mode: ProtectionMode = ProtectionMode.MONITOR_ONLY,
        history: List<AuthHistoryEntry> = emptyList(),
        consent: Boolean = true,
        usageAccess: Boolean = true,
        fontScale: Float = 1f,
        onStart: () -> Unit = {},
        onStop: () -> Unit = {},
        onSettings: () -> Unit = {},
        onConsent: () -> Unit = {},
        onUsage: () -> Unit = {},
        collectionActive: Boolean = false,
        isStopping: Boolean = false
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    AuthenticationContent(state, mode, history, consent, usageAccess,
                        onStart, onStop, onSettings, onConsent, onUsage, now, collectionActive, isStopping)
                }
            }
        }
    }
}
