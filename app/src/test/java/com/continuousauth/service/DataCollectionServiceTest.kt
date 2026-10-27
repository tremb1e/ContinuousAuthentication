package com.continuousauth.service

import android.app.Application
import android.app.KeyguardManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.PowerManager
import com.continuousauth.core.SmartTransmissionManager
import com.continuousauth.network.UploadManager
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowPowerManager

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class DataCollectionServiceTest {
    private val dispatcher = StandardTestDispatcher()
    private val manager: SmartTransmissionManager = mockk(relaxed = true)
    private val upload: UploadManager = mockk(relaxed = true)
    private val uploadWindow = MutableStateFlow(false)
    private val controllers = mutableListOf<ServiceController<DataCollectionService>>()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        DataCollectionService.completeStopRequest(DataCollectionService.stopState.value.requestedId)
        every { manager.smartUploadWindowOpen } returns uploadWindow
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().putBoolean("privacy_agreement_shown", true)
            .putBoolean("privacy_consent_granted", true).commit()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(context.getSystemService(KeyguardManager::class.java)).apply {
            setKeyguardLocked(false)
            setIsDeviceLocked(false)
        }
    }

    @After
    fun tearDown() {
        destroyControllers()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun unlockBroadcastCannotOverrideManualPause() = runServiceTest {
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        service.onStartCommand(command(DataCollectionService.ACTION_PAUSE_COLLECTION), 0, 2)
        runCurrent()
        clearMocks(manager, answers = false)

        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()

        coVerify(exactly = 0) { manager.resume() }
        coVerify { manager.pause(allowBufferedUpload = false) }
    }

    @Test
    fun stickyRecreationKeepsUserRequestAndManualPause() = runServiceTest {
        val initial = createService()
        assertEquals(
            Service.START_STICKY,
            initial.get().onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        )
        runCurrent()
        initial.get().onStartCommand(command(DataCollectionService.ACTION_PAUSE_COLLECTION), 0, 2)
        runCurrent()
        initial.destroy()
        controllers.remove(initial)
        assertTrue(collectionRequested())
        coVerify { manager.stop(endSession = false) }
        clearMocks(manager, answers = false)

        val recreated = createService().get()
        assertEquals(Service.START_STICKY, recreated.onStartCommand(null, 0, 3))
        runCurrent()
        coVerify { manager.start() }
        coVerify { manager.pause(allowBufferedUpload = false) }
        coVerify(exactly = 0) { manager.resume() }
    }

    @Test
    fun explicitStopClearsRequestAndFinishesService() = runServiceTest {
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        assertEquals(
            Service.START_NOT_STICKY,
            service.onStartCommand(command(DataCollectionService.ACTION_STOP_COLLECTION), 0, 2)
        )
        runCurrent()
        assertFalse(collectionRequested())
        coVerify { manager.stop(endSession = true) }
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(DataCollectionService.stopState.value.isPending)
    }

    @Test
    fun stopDuringSuspendedStartupBlocksSendingAndWaitsForFinalShutdown() = runServiceTest {
        val startFinished = CompletableDeferred<Unit>()
        val stopFinished = CompletableDeferred<Unit>()
        coEvery { manager.start() } coAnswers { startFinished.await() }
        coEvery { manager.stop(any()) } coAnswers { stopFinished.await() }
        val service = createService().get()
        try {
            service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
            runCurrent()
            verify { upload.setCollectionUploadAllowed(true) }

            service.onStartCommand(command(DataCollectionService.ACTION_STOP_COLLECTION), 0, 2)
            verify { upload.setCollectionUploadAllowed(false) }
            verify { upload.pauseUpload("collection_stopping") }
            assertFalse(collectionRequested())
            assertTrue(DataCollectionService.stopState.value.isPending)
            coVerify(exactly = 0) { manager.stop(any()) }

            startFinished.complete(Unit)
            runCurrent()
            coVerify(exactly = 0) { manager.resume() }
            coVerify(exactly = 0) { manager.pause(any()) }
            coVerify(exactly = 1) { manager.stop(endSession = true) }
            assertTrue(DataCollectionService.stopState.value.isPending)
            assertFalse(shadowOf(service).isStoppedBySelf)

            stopFinished.complete(Unit)
            runCurrent()
            assertFalse(DataCollectionService.stopState.value.isPending)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally {
            startFinished.complete(Unit)
            stopFinished.complete(Unit)
        }
    }

    @Test
    fun stopAfterConsentRevocationStillWaitsForShutdownAndAcknowledgesTheRequest() = runServiceTest {
        val stopFinished = CompletableDeferred<Unit>()
        coEvery { manager.stop(any()) } coAnswers { stopFinished.await() }
        val service = createService().get()
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("privacy_consent_granted", false).commit()
        try {
            service.onStartCommand(command(DataCollectionService.ACTION_STOP_COLLECTION), 0, 1)
            runCurrent()
            verify { upload.setCollectionUploadAllowed(false) }
            coVerify(exactly = 1) { manager.stop(endSession = true) }
            assertTrue(DataCollectionService.stopState.value.isPending)
            stopFinished.complete(Unit)
            runCurrent()
            assertFalse(DataCollectionService.stopState.value.isPending)
        } finally {
            stopFinished.complete(Unit)
        }
    }

    @Test
    fun explicitStopCannotBeUndoneByUnlockOrStickyRestart() = runServiceTest {
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        service.onStartCommand(command(DataCollectionService.ACTION_STOP_COLLECTION), 0, 2)
        runCurrent()
        clearMocks(manager, answers = false)

        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        com.continuousauth.receiver.DeviceUnlockReceiver()
            .onReceive(context, Intent(Intent.ACTION_USER_PRESENT))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 3))
        runCurrent()
        assertFalse(collectionRequested())
        coVerify(exactly = 0) { manager.start() }
        coVerify(exactly = 0) { manager.resume() }
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun merelySeeingThePrivacyNoticeNeverAuthorizesCollection() = runServiceTest {
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean("privacy_agreement_shown", true)
            .putBoolean("privacy_consent_granted", false).commit()
        val service = createService().get()
        assertEquals(Service.START_NOT_STICKY,
            service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1))
        runCurrent()
        coVerify(exactly = 0) { manager.start() }
        assertFalse(collectionRequested())
    }

    @Test
    fun screenOffWakeLockIsHeldOnlyDuringSmartUploadWindow() = runServiceTest {
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)

        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        context.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)

        uploadWindow.value = true
        runCurrent()
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
        uploadWindow.value = false
        runCurrent()
        assertFalse(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun queuedStartIsIgnoredAfterUserHasStoppedOrRevokedConsent() = runServiceTest {
        val service = createService().get()
        val queuedStart = command(DataCollectionService.ACTION_START_COLLECTION)
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).commit()

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(queuedStart, 0, 1))
        runCurrent()
        coVerify(exactly = 0) { manager.start() }
        assertFalse(collectionRequested())
    }

    @Test
    fun destructionDuringExplicitStopStillClearsTheSession() = runServiceTest {
        val stopFinished = CompletableDeferred<Unit>()
        coEvery { manager.stop(any()) } coAnswers { stopFinished.await() }
        val controller = createService()
        controller.get().onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        controller.get().onStartCommand(command(DataCollectionService.ACTION_STOP_COLLECTION), 0, 2)
        controller.destroy()
        controllers.remove(controller)
        try {
            runCurrent()
            coVerify { manager.stop(endSession = true) }
            coVerify(exactly = 0) { manager.stop(endSession = false) }
            assertTrue(DataCollectionService.stopState.value.isPending)
            assertTrue(DataCollectionService.hasServiceInstance)
            stopFinished.complete(Unit)
            runCurrent()
            assertFalse(DataCollectionService.stopState.value.isPending)
            assertFalse(DataCollectionService.hasServiceInstance)
        } finally {
            stopFinished.complete(Unit)
        }
    }

    @Test
    fun unlockedCollectionContinuesWithoutAnActivityOrUiSubscriber() = runServiceTest {
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()

        advanceTimeBy(60_000)
        runCurrent()

        coVerify(exactly = 1) { manager.start() }
        coVerify(exactly = 1) { manager.resume() }
        coVerify(exactly = 0) { manager.pause(any()) }
        coVerify(exactly = 0) { manager.stop(any()) }
        assertTrue(collectionRequested())
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun unlockAfterStaleBroadcastStateRecoversWithoutReopeningTheApp() = runServiceTest {
        val keyguard = shadowOf(context.getSystemService(KeyguardManager::class.java))
        val power = shadowOf(context.getSystemService(PowerManager::class.java))
        keyguard.setKeyguardLocked(true)
        keyguard.setIsDeviceLocked(true)
        power.setIsInteractive(false)
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        coVerify { manager.pause(allowBufferedUpload = true) }
        clearMocks(manager, answers = false)

        power.setIsInteractive(true)
        context.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        runCurrent()
        coVerify(exactly = 0) { manager.resume() }

        keyguard.setKeyguardLocked(false)
        keyguard.setIsDeviceLocked(false)
        advanceTimeBy(1_000)
        runCurrent()

        coVerify(exactly = 1) { manager.resume() }
        assertTrue(ShadowPowerManager.getLatestWakeLock().isHeld)
    }

    @Test
    fun missingScreenBroadcastIsReconciledWithoutUndoingManualPause() = runServiceTest {
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        advanceTimeBy(1_000)
        runCurrent()
        coVerify { manager.pause(allowBufferedUpload = true) }

        service.onStartCommand(command(DataCollectionService.ACTION_PAUSE_COLLECTION), 0, 2)
        runCurrent()
        clearMocks(manager, answers = false)
        shadowOf(context.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        advanceTimeBy(1_000)
        runCurrent()

        coVerify(exactly = 0) { manager.resume() }
        coVerify(exactly = 1) { manager.pause(allowBufferedUpload = false) }
    }

    @Test
    fun lowBatteryDoesNotDisableUserRequestedUnlockedCollection() = runServiceTest {
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
            putExtra(BatteryManager.EXTRA_BATTERY_LOW, true)
        })
        val service = createService().get()
        service.onStartCommand(command(DataCollectionService.ACTION_START_COLLECTION), 0, 1)
        runCurrent()

        context.sendBroadcast(Intent(Intent.ACTION_BATTERY_LOW))
        shadowOf(Looper.getMainLooper()).idle()
        advanceTimeBy(1_000)
        runCurrent()

        coVerify(exactly = 1) { manager.resume() }
        coVerify(exactly = 0) { manager.pause(any()) }
        assertTrue(collectionRequested())
    }

    private fun runServiceTest(testBody: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            testBody()
        } finally {
            // Cancel recurring service jobs before runTest tries to drain its scheduler.
            destroyControllers()
            runCurrent()
        }
    }

    private fun destroyControllers() {
        controllers.forEach { it.destroy() }
        controllers.clear()
    }

    private fun createService(): ServiceController<DataCollectionService> {
        val controller = Robolectric.buildService(DataCollectionService::class.java)
        val service = controller.get()
        // Supply the singleton dependency without starting the application's real network/crypto graph.
        generateSequence(service.javaClass as Class<*>?) { it.superclass }
            .firstOrNull { it.simpleName == "Hilt_DataCollectionService" }
            ?.getDeclaredField("injected")?.apply {
                isAccessible = true
                setBoolean(service, true)
            }
        service.smartTransmissionManager = manager
        service.authenticationManager = mockk(relaxed = true)
        service.uploadManager = upload
        controllers += controller
        return controller.create()
    }

    private fun command(action: String): Intent {
        if (action == DataCollectionService.ACTION_START_COLLECTION) {
            context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, true).commit()
        }
        return Intent(context, DataCollectionService::class.java).setAction(action)
    }

    private fun collectionRequested(): Boolean =
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false)
}
