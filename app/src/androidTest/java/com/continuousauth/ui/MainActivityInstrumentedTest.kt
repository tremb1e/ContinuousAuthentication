package com.continuousauth.ui

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.continuousauth.R
import com.continuousauth.detection.AnomalyTrigger
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.NetworkState
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.test.runTest
import org.hamcrest.Matchers.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * 主界面UI测试
 * 测试MainActivity的各种用户交互场景和状态变化
 */
@LargeTest
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivityInstrumentedTest {

    @get:Rule(order = 0)
    var hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    var activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun init() {
        hiltRule.inject()
    }

    /**
     * 测试基础UI元素是否正确显示
     */
    @Test
    fun testBasicUIElements() {
        // 检查主要UI元素是否存在
        onView(withId(R.id.btnToggleCollection)).check(matches(isDisplayed()))
        onView(withId(R.id.btnToggleChart)).check(matches(isDisplayed()))
        onView(withId(R.id.tvCollectionStatus)).check(matches(isDisplayed()))
        onView(withId(R.id.tvConnectionStatus)).check(matches(isDisplayed()))
        onView(withId(R.id.tvNetworkQuality)).check(matches(isDisplayed()))
        
        // 检查传感器状态显示
        onView(withId(R.id.tvAccelerometerStatus)).check(matches(isDisplayed()))
        onView(withId(R.id.tvGyroscopeStatus)).check(matches(isDisplayed()))
        onView(withId(R.id.tvMagnetometerStatus)).check(matches(isDisplayed()))
        
        // 检查传输统计显示
        onView(withId(R.id.tvTransmissionMode)).check(matches(isDisplayed()))
        onView(withId(R.id.tvPacketsSent)).check(matches(isDisplayed()))
        onView(withId(R.id.tvPacketsPending)).check(matches(isDisplayed()))
    }

    /**
     * 测试数据采集开始/停止功能
     */
    @Test
    fun testDataCollectionToggle() {
        // 初始状态：采集应该是停止的
        onView(withId(R.id.tvCollectionStatus))
            .check(matches(withText(containsString(context.getString(R.string.status_stopped)))))
        
        onView(withId(R.id.btnToggleCollection))
            .check(matches(withText(context.getString(R.string.start_collection))))

        // 点击开始采集
        onView(withId(R.id.btnToggleCollection)).perform(click())
        
        // 等待状态更新
        Thread.sleep(1000)
        
        // 验证状态变化
        onView(withId(R.id.btnToggleCollection))
            .check(matches(withText(context.getString(R.string.stop_collection))))
        
        // 再次点击停止采集
        onView(withId(R.id.btnToggleCollection)).perform(click())
        
        // 等待状态更新
        Thread.sleep(1000)
        
        // 验证回到初始状态
        onView(withId(R.id.btnToggleCollection))
            .check(matches(withText(context.getString(R.string.start_collection))))
    }

    /**
     * 测试图表显示/隐藏功能
     */
    @Test
    fun testChartToggle() {
        // 初始状态：图表应该是隐藏的
        onView(withId(R.id.chartContainer))
            .check(matches(not(isDisplayed())))
        
        onView(withId(R.id.btnToggleChart))
            .check(matches(withText(context.getString(R.string.show_chart))))

        // 点击显示图表
        onView(withId(R.id.btnToggleChart)).perform(click())
        
        // 验证图表显示
        onView(withId(R.id.chartContainer))
            .check(matches(isDisplayed()))
        
        onView(withId(R.id.btnToggleChart))
            .check(matches(withText(context.getString(R.string.hide_chart))))

        // 再次点击隐藏图表
        onView(withId(R.id.btnToggleChart)).perform(click())
        
        // 验证图表隐藏
        onView(withId(R.id.chartContainer))
            .check(matches(not(isDisplayed())))
    }

    /**
     * 测试菜单选项功能
     */
    @Test
    fun testMenuOptions() {
        // 打开选项菜单
        onView(isRoot()).perform(pressMenuKey())
        
        // 点击调试模式
        onView(withId(R.id.action_debug)).perform(click())
        
        // 验证调试信息卡片显示
        onView(withId(R.id.cardDebugInfo))
            .check(matches(isDisplayed()))
        
        // 再次打开菜单并关闭调试模式
        onView(isRoot()).perform(pressMenuKey())
        onView(withId(R.id.action_debug)).perform(click())
        
        // 验证调试信息卡片隐藏
        onView(withId(R.id.cardDebugInfo))
            .check(matches(not(isDisplayed())))
    }

    /**
     * 测试可视化模式切换
     */
    @Test
    fun testVisualizationToggle() {
        // 打开选项菜单
        onView(isRoot()).perform(pressMenuKey())
        
        // 点击可视化模式
        onView(withId(R.id.action_visualization)).perform(click())
        
        // 验证可视化卡片显示
        onView(withId(R.id.cardVisualization))
            .check(matches(isDisplayed()))
        
        // 再次切换
        onView(isRoot()).perform(pressMenuKey())
        onView(withId(R.id.action_visualization)).perform(click())
        
        // 验证可视化卡片隐藏
        onView(withId(R.id.cardVisualization))
            .check(matches(not(isDisplayed())))
    }

    /**
     * 测试网络状态显示
     */
    @Test
    fun testNetworkStatusDisplay() = runTest {
        activityScenarioRule.scenario.onActivity { activity ->
            val viewModel = activity.viewModel
            
            // 模拟不同网络状态
            val networkStates = listOf(
                NetworkState.WIFI_EXCELLENT,
                NetworkState.WIFI_GOOD,
                NetworkState.WIFI_POOR,
                NetworkState.CELLULAR_EXCELLENT,
                NetworkState.CELLULAR_GOOD,
                NetworkState.CELLULAR_POOR,
                NetworkState.DISCONNECTED
            )
            
            networkStates.forEach { state ->
                // 通过反射设置网络状态（模拟）
                val networkStateField = viewModel.javaClass.getDeclaredField("_networkState")
                networkStateField.isAccessible = true
                val mutableLiveData = networkStateField.get(viewModel) as androidx.lifecycle.MutableLiveData<NetworkState>
                
                activity.runOnUiThread {
                    mutableLiveData.value = state
                }
                
                Thread.sleep(500) // 等待UI更新
                
                // 验证网络状态文本显示正确
                val expectedText = when (state) {
                    NetworkState.WIFI_EXCELLENT -> context.getString(R.string.wifi_excellent)
                    NetworkState.WIFI_GOOD -> context.getString(R.string.wifi_good)
                    NetworkState.WIFI_POOR -> context.getString(R.string.wifi_poor)
                    NetworkState.CELLULAR_EXCELLENT -> context.getString(R.string.cellular_excellent)
                    NetworkState.CELLULAR_GOOD -> context.getString(R.string.cellular_good)
                    NetworkState.CELLULAR_POOR -> context.getString(R.string.cellular_poor)
                    NetworkState.DISCONNECTED -> context.getString(R.string.disconnected)
                    else -> "-"
                }
                
                onView(withId(R.id.tvNetworkQuality))
                    .check(matches(withText(expectedText)))
            }
        }
    }

    /**
     * 测试连接状态显示
     */
    @Test
    fun testConnectionStatusDisplay() = runTest {
        activityScenarioRule.scenario.onActivity { activity ->
            val viewModel = activity.viewModel
            
            val connectionStates = listOf(
                ConnectionStatus.CONNECTED,
                ConnectionStatus.DISCONNECTED,
                ConnectionStatus.CONNECTING,
                ConnectionStatus.RECONNECTING
            )
            
            connectionStates.forEach { status ->
                // 通过反射设置连接状态（模拟）
                val connectionStatusField = viewModel.javaClass.getDeclaredField("_connectionStatus")
                connectionStatusField.isAccessible = true
                val mutableLiveData = connectionStatusField.get(viewModel) as androidx.lifecycle.MutableLiveData<ConnectionStatus>
                
                activity.runOnUiThread {
                    mutableLiveData.value = status
                }
                
                Thread.sleep(500) // 等待UI更新
                
                // 验证连接状态文本显示正确
                val expectedText = when (status) {
                    ConnectionStatus.CONNECTED -> context.getString(R.string.connected)
                    ConnectionStatus.DISCONNECTED -> context.getString(R.string.disconnected)
                    ConnectionStatus.CONNECTING -> context.getString(R.string.connecting)
                    ConnectionStatus.RECONNECTING -> context.getString(R.string.reconnecting)
                    else -> status.name
                }
                
                onView(withId(R.id.tvConnectionStatus))
                    .check(matches(withText(expectedText)))
            }
        }
    }

    /**
     * 测试传感器状态显示
     */
    @Test
    fun testSensorStatusDisplay() = runTest {
        activityScenarioRule.scenario.onActivity { activity ->
            val viewModel = activity.viewModel
            
            // 模拟传感器激活状态
            val activeSensorStatus = mapOf(
                "accelerometer" to true,
                "gyroscope" to true,
                "magnetometer" to true
            )
            
            val inactiveSensorStatus = mapOf(
                "accelerometer" to false,
                "gyroscope" to false,
                "magnetometer" to false
            )
            
            listOf(activeSensorStatus, inactiveSensorStatus).forEach { sensorStatus ->
                // 通过反射设置传感器状态
                val sensorStatusField = viewModel.javaClass.getDeclaredField("_sensorStatus")
                sensorStatusField.isAccessible = true
                val mutableLiveData = sensorStatusField.get(viewModel) as androidx.lifecycle.MutableLiveData<Map<String, Boolean>>
                
                activity.runOnUiThread {
                    mutableLiveData.value = sensorStatus
                }
                
                Thread.sleep(500) // 等待UI更新
                
                val expectedText = if (sensorStatus.values.first()) 
                    context.getString(R.string.sensor_active) 
                else 
                    context.getString(R.string.sensor_inactive)
                
                // 验证各传感器状态
                onView(withId(R.id.tvAccelerometerStatus))
                    .check(matches(withText(expectedText)))
                onView(withId(R.id.tvGyroscopeStatus))
                    .check(matches(withText(expectedText)))
                onView(withId(R.id.tvMagnetometerStatus))
                    .check(matches(withText(expectedText)))
            }
        }
    }

    /**
     * 测试错误消息显示
     */
    @Test
    fun testErrorMessageHandling() = runTest {
        activityScenarioRule.scenario.onActivity { activity ->
            val viewModel = activity.viewModel
            
            // 通过反射触发错误消息
            val errorMessageField = viewModel.javaClass.getDeclaredField("_errorMessage")
            errorMessageField.isAccessible = true
            val mutableLiveData = errorMessageField.get(viewModel) as androidx.lifecycle.MutableLiveData<String?>
            
            val testErrorMessage = "测试错误消息"
            
            activity.runOnUiThread {
                mutableLiveData.value = testErrorMessage
            }
            
            Thread.sleep(500) // 等待错误处理
            
            // 注意：由于showErrorMessage目前只是log输出，这里主要验证不会崩溃
            // 在实际实现中，这里应该验证Snackbar或Dialog的显示
        }
    }

    /**
     * 测试应用在不同权限状态下的表现
     */
    @Test
    fun testPermissionHandling() {
        // 这个测试需要更复杂的权限模拟，暂时实现基础检查
        // 在实际测试中需要使用PermissionTestRule或UiAutomator来模拟权限授予/拒绝
        
        activityScenarioRule.scenario.onActivity { activity ->
            // 验证Activity正常启动，没有因权限问题崩溃
            assertThat(activity, not(nullValue()))
            
            // 验证权限检查相关的UI元素存在
            onView(withId(R.id.btnToggleCollection)).check(matches(isDisplayed()))
        }
    }

    /**
     * 测试Activity生命周期处理
     */
    @Test
    fun testActivityLifecycle() {
        // 模拟Activity暂停和恢复
        activityScenarioRule.scenario.onActivity { activity ->
            // 验证Activity正常运行
            assertThat(activity, not(nullValue()))
        }
        
        // 模拟用户离开Activity
        activityScenarioRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        
        // 模拟用户返回Activity
        activityScenarioRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        
        // 验证UI仍然正常工作
        onView(withId(R.id.btnToggleCollection)).check(matches(isDisplayed()))
    }
}