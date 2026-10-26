package com.continuousauth.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.continuousauth.auth.AuthHistoryEntry
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.ui.compose.screens.AuthenticationContent
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
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
    fun offModeHidesWarningAndOpensProtectionSettings() {
        var settingsOpened = 0
        show(state = failedState(), mode = ProtectionMode.OFF, onSettings = { settingsOpened++ })
        compose.onNodeWithTag("authentication_warning").assertDoesNotExist()
        compose.onNodeWithTag("authentication_action").performClick()
        assertEquals(1, settingsOpened)
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun monitorModeShowsPassiveFailureWithoutModal() {
        show(state = failedState(), mode = ProtectionMode.MONITOR_ONLY)
        compose.onNodeWithTag("authentication_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前为仅监测模式，不会弹窗或限制使用。").assertExists()
        compose.onAllNodes(isDialog()).assertCountEquals(0)
    }

    @Test
    fun alertModeShowsRecentFailureAndPermissionExplanation() {
        show(state = failedState(), mode = ProtectionMode.ALERT)
        compose.onNodeWithTag("authentication_warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前为即时警示模式。系统弹窗需要在设置中允许显示在其他应用上层。").assertExists()
    }

    @Test
    fun noDataIsExplicitAndStartUsesSingleAction() {
        var started = 0
        show(onStart = { started++ })
        compose.onNodeWithTag("authentication_action").performClick()
        assertEquals(1, started)
        compose.onNodeWithTag("history_total", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂无认证记录").assertExists()
        compose.onNodeWithText("这个时段没有收到认证结果，不代表全部通过。").assertExists()
    }

    @Test
    fun allPassedIsDifferentFromNoData() {
        show(history = listOf(AuthHistoryEntry("pass", now, true, "sample.app", "示例应用")))
        compose.onNodeWithTag("history_total", useUnmergedTree = true).performScrollTo()
        compose.onNodeWithText("0 次不通过").assertExists()
        compose.onNodeWithText("共收到 1 次结果 · 1 次通过").assertExists()
        compose.onNodeWithText("暂无认证记录").assertDoesNotExist()
    }

    @Test
    fun preparingCanBeCancelledAndRunningCanBeStopped() {
        var stopped = 0
        show(state = ContinuousAuthUiState(isStarting = true), onStop = { stopped++ })
        compose.onNodeWithText("取消准备").assertExists()
        compose.onNodeWithTag("authentication_preparing").assertExists()
        compose.onNodeWithTag("authentication_action").performClick()
        assertEquals(1, stopped)
    }

    @Test
    fun runningShowsWaitingForResultAndStopAction() {
        var stopped = 0
        show(state = ContinuousAuthUiState(authActive = true), onStop = { stopped++ })
        compose.onNodeWithText("尚未收到认证结果，请继续正常使用手机。").assertExists()
        compose.onNodeWithText("停止认证").assertExists()
        compose.onNodeWithTag("authentication_action").performClick()
        assertEquals(1, stopped)
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
    fun weekDaySelectionFiltersAssociatedApplicationsAndCanClearFilter() {
        val yesterdayTime = today.minusDays(1).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        show(history = listOf(AuthHistoryEntry("today", now, false, "app.today", "今日应用"),
            AuthHistoryEntry("yesterday", yesterdayTime, false, "app.yesterday", "昨日应用")))
        compose.onNodeWithTag("history_period_WEEK").performScrollTo().performClick()
        compose.onNodeWithText("2 次不通过").assertExists()
        val yesterdayStart = today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        compose.onNodeWithTag("history_bar_$yesterdayStart").performScrollTo().performClick()
        compose.onNodeWithTag("history_selected_bucket").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("昨日应用").assertExists()
        compose.onNodeWithText("今日应用").assertDoesNotExist()
        compose.onNodeWithText("查看整个时段").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("history_selected_bucket").assertDoesNotExist()
        compose.onNodeWithText("今日应用").assertExists()
    }

    @Test
    fun customRangeCanBeOpenedCancelledAndConfirmed() {
        show()
        compose.onNodeWithTag("history_period_CUSTOM").performScrollTo().performClick()
        compose.onNodeWithText("选择最近 7 天内的日期").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithTag("history_period_CUSTOM").performScrollTo().performClick()
        compose.onNodeWithText("完成").performClick()
        compose.onNodeWithText("选择最近 7 天内的日期").assertDoesNotExist()
        compose.onNodeWithTag("history_date_range").assertExists()
    }

    @Test
    fun yesterdayAndHourlySelectionKeepApplicationAssociationsSeparate() {
        val yesterday = today.minusDays(1).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()
        val earlierToday = today.atTime(9, 15).atZone(zone).toInstant().toEpochMilli()
        show(history = listOf(AuthHistoryEntry("yesterday", yesterday, false, "app.yesterday", "昨日应用"),
            AuthHistoryEntry("morning", earlierToday, false, "app.morning", "早间应用"),
            AuthHistoryEntry("afternoon", now, false, "app.afternoon", "午后应用")))
        compose.onNodeWithTag("history_period_YESTERDAY").performScrollTo().performClick()
        compose.onNodeWithText("1 次不通过").assertExists()
        compose.onNodeWithText("昨日应用").assertExists()
        compose.onNodeWithText("早间应用").assertDoesNotExist()
        compose.onNodeWithTag("history_period_TODAY").performScrollTo().performClick()
        val hourStart = today.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()
        compose.onNodeWithTag("history_bar_$hourStart").performScrollTo().performClick()
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
        compose.onNodeWithTag("section_failure_events").performScrollTo().performClick()
        compose.onAllNodesWithText("示例应用").assertCountEquals(21)
        compose.onNodeWithText("再显示 1 条记录").performScrollTo().performClick()
        compose.onAllNodesWithText("示例应用").assertCountEquals(22)
        compose.onNodeWithText(formatAuthHistoryTime(entries.last().timestampMs)).assertExists()
    }

    @Test
    fun largeFontKeepsPrimaryActionAndPermissionRecoveryReachable() {
        var permissionOpened = 0
        show(fontScale = 2f, usageAccess = false, onUsage = { permissionOpened++ },
            history = listOf(AuthHistoryEntry("unknown", now, false, "", "")))
        compose.onNodeWithTag("authentication_action").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("history_period_WEEK").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("未识别的应用").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("了解并开启应用识别").performScrollTo().performClick()
        assertEquals(1, permissionOpened)
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
        onUsage: () -> Unit = {}
    ) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    AuthenticationContent(state, mode, history, consent, usageAccess,
                        onStart, onStop, onSettings, onConsent, onUsage, now)
                }
            }
        }
    }
}
