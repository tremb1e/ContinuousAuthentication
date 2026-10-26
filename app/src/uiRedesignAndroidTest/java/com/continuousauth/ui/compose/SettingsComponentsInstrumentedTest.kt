package com.continuousauth.ui.compose

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.ui.compose.components.ExpandableSection
import com.continuousauth.ui.compose.dialogs.PrivacyAgreementContent
import com.continuousauth.ui.compose.screens.PreferenceSwitch
import com.continuousauth.ui.compose.screens.ProtectionChoice
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsComponentsInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sectionsStartCollapsedAndExposeAccessibleExpandState() {
        compose.setContent {
            MaterialTheme {
                ExpandableSection("privacy", "知情同意", "尚未同意", Icons.Default.Settings) { Text("数据使用说明内容") }
            }
        }
        compose.onNodeWithTag("section_privacy").assertIsDisplayed().assert(state("已收起"))
        compose.onNodeWithText("数据使用说明内容").assertDoesNotExist()
        compose.onNodeWithTag("section_privacy").performClick()
        compose.onNodeWithText("数据使用说明内容").assertIsDisplayed()
        compose.onNodeWithTag("section_privacy").assert(state("已展开")).performClick()
        compose.onNodeWithText("数据使用说明内容").assertDoesNotExist()
    }

    @Test fun expandedStateSurvivesActivitySavedStateRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { ExpandableSection("restore", "恢复测试", "点击展开", Icons.Default.Settings) { Text("仍然可见") } }
        }
        compose.onNodeWithTag("section_restore").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("section_restore").assert(state("已展开"))
        compose.onNodeWithText("仍然可见").assertIsDisplayed()
    }

    @Test fun changingASettingDoesNotCollapseItsSection() {
        compose.setContent {
            var allowed by remember { mutableStateOf(true) }
            MaterialTheme {
                ExpandableSection("sensor_test", "数据采集设置", "选择采集内容", Icons.Default.Settings) {
                    PreferenceSwitch("加速度计", "感知手机的移动", allowed, "sensor_toggle") { allowed = it }
                }
            }
        }
        compose.onNodeWithTag("section_sensor_test").performClick()
        compose.onNodeWithTag("sensor_toggle").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("section_sensor_test").assert(state("已展开"))
        compose.onNodeWithTag("section_content_sensor_test").assertIsDisplayed()
    }

    @Test fun protectionOptionsAreMutuallyExclusiveAndDefaultToMonitoring() {
        compose.setContent {
            var selected by remember { mutableStateOf(ProtectionMode.MONITOR_ONLY) }
            MaterialTheme {
                Column {
                    ProtectionChoice(ProtectionMode.OFF, selected, "关闭", "停止认证") { selected = it }
                    ProtectionChoice(ProtectionMode.MONITOR_ONLY, selected, "仅监测（默认）", "仅记录") { selected = it }
                    ProtectionChoice(ProtectionMode.ALERT, selected, "即时警示", "弹窗提醒") { selected = it }
                }
            }
        }
        compose.onNodeWithTag("protection_monitor_only").assertIsSelected()
        compose.onNodeWithTag("protection_alert").performClick().assertIsSelected()
        compose.onNodeWithTag("protection_off").performClick().assertIsSelected()
    }

    @Test fun largeFontAndNarrowWidthKeepHeaderAndControlsReachable() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Column(Modifier.width(280.dp).verticalScroll(rememberScrollState())) {
                        ExpandableSection("large_font", "后台运行与系统权限", "点击查看使用情况和运行设置", Icons.Default.Settings) {
                            PreferenceSwitch("仅通过 Wi-Fi 上传", "没有 Wi-Fi 时暂停上传，认证结果可能延后。", false, "large_switch") {}
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("section_large_font").assertIsDisplayed().performClick()
        compose.onNodeWithTag("large_switch").performScrollTo().assertIsDisplayed().performClick()
        val bounds = compose.onNodeWithTag("large_switch").getUnclippedBoundsInRoot()
        assertTrue("The switch row must remain inside the narrow column", bounds.right - bounds.left <= 280.dp)
        assertTrue("Large-font controls must retain a usable touch target", bounds.bottom - bounds.top >= 48.dp)
        compose.onNodeWithTag("section_large_font").performScrollTo().assert(state("已展开"))
    }

    @Test fun consentNoticeKeepsBothDecisionsVisibleAndCanScrollInPortrait() {
        verifyConsentAtSize(fontScale = 1f, heightDp = 640)
    }

    @Test fun consentNoticeKeepsBothDecisionsVisibleAndCanScrollInLargeFontLandscape() {
        verifyConsentAtSize(fontScale = 2f, heightDp = 300)
    }

    private fun verifyConsentAtSize(fontScale: Float, heightDp: Int) {
        var accepted = 0
        var declined = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                MaterialTheme {
                    PrivacyAgreementContent(
                        onAccept = { accepted++ },
                        onDecline = { declined++ },
                        modifier = Modifier.width(320.dp).height(heightDp.dp)
                    )
                }
            }
        }
        val card = compose.onNodeWithTag("privacy_agreement").getUnclippedBoundsInRoot()
        listOf("privacy_decline", "privacy_accept").forEach { tag ->
            val button = compose.onNodeWithTag(tag).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("Consent controls must retain a 48dp touch target", button.bottom - button.top >= 48.dp)
            assertTrue("Consent controls must stay entirely inside the card", button.top >= card.top && button.bottom <= card.bottom)
        }
        listOf("暂不启用", "同意并继续").forEach { label ->
            val text = compose.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed().getUnclippedBoundsInRoot()
            assertTrue("The complete decision label must fit inside the card", text.left >= card.left && text.right <= card.right && text.bottom <= card.bottom)
        }
        val readingArea = compose.onNodeWithTag("privacy_notice_scroll").getUnclippedBoundsInRoot()
        assertTrue("The notice must retain room for reading on short screens", readingArea.bottom - readingArea.top >= 80.dp)
        if (fontScale == 1f) assertTrue("Portrait keeps a useful reading area", readingArea.bottom - readingArea.top >= 400.dp)
        compose.onNodeWithText("会采集什么").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("你可以随时决定").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("不同意也可以浏览页面和调整设置", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("privacy_decline").assertIsDisplayed().performClick()
        compose.onNodeWithTag("privacy_accept").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(1, declined)
            assertEquals(1, accepted)
        }
    }

    private fun state(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)
}
