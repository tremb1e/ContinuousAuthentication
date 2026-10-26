package com.continuousauth.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.continuousauth.core.SmartTransmissionManager
import com.continuousauth.core.SmartTransmissionManager.CollectionState
import com.continuousauth.core.SmartTransmissionManager.CollectionStatus
import com.continuousauth.database.UploadProgress
import com.continuousauth.monitor.SystemMonitor
import com.continuousauth.network.ConnectionStats
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.NetworkEnvironmentDetector
import com.continuousauth.network.NetworkState
import com.continuousauth.network.UploadManager
import com.continuousauth.network.UploadStatus
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.DeletionState
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.service.DataCollectionService
import com.continuousauth.storage.FileQueueManager
import com.continuousauth.storage.QueueStatisticsDetail
import com.continuousauth.storage.QueueStats
import com.continuousauth.auth.AuthHistoryRepository
import com.continuousauth.auth.AuthenticationManager
import com.continuousauth.proto.AuthResult
import com.continuousauth.settings.AppSettingsRepository
import com.continuousauth.settings.ProtectionMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class MainViewModelConsentTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val collection = MutableStateFlow(CollectionState())
    private val consent = MutableStateFlow(ConsentState.NOT_GRANTED)
    private val deletion = MutableStateFlow(DeletionState.IDLE)
    private val progress = MutableStateFlow(UploadProgress())
    private val privacy: PrivacyManager = mockk(relaxed = true)
    private val authActive = MutableStateFlow(false)
    private val latestResult = MutableStateFlow<AuthResult?>(null)
    private val authentication: AuthenticationManager = mockk(relaxed = true)
    private lateinit var application: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        application.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        every { authentication.active } returns authActive
        every { authentication.latestResult } returns latestResult
        every { authentication.stopAuthentication() } answers { authActive.value = false }
        every { privacy.consentState } returns consent
        every { privacy.deletionState } returns deletion
        coEvery { privacy.withdrawConsentAndDeleteData() } coAnswers {
            deletion.value = DeletionState.PARTIAL_SUCCESS
            Result.success(Unit)
        }
    }

    @After
    fun tearDown() {
        store.clear()
        dispatcher.scheduler.runCurrent()
        Dispatchers.resetMain()
    }

    @Test
    fun repeatedConsentChecksObserveOnceAndDoNotCreateAnIdleService() = runTest(dispatcher) {
        try {
            val model = createModel()
            runCurrent()
            model.checkPrivacyConsent()
            model.checkPrivacyConsent()
            runCurrent()
            assertEquals(1, consent.subscriptionCount.value)
            assertEquals(1, deletion.subscriptionCount.value)
            assertNull(shadowOf(application).nextStartedService)
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun withdrawalWaitsForFinalFlushAndReportsPendingRemoteDeletion() = runTest(dispatcher) {
        try {
            consent.value = ConsentState.GRANTED
            collection.value = CollectionState(CollectionStatus.RUNNING)
            val model = createModel()
            runCurrent()
            model.withdrawConsentAndDeleteData()
            runCurrent()
            coVerify(exactly = 0) { privacy.withdrawConsentAndDeleteData() }
            assertEquals(DataCollectionService.ACTION_STOP_COLLECTION, shadowOf(application).nextStartedService.action)

            collection.value = CollectionState(CollectionStatus.STOPPING)
            runCurrent()
            coVerify(exactly = 0) { privacy.withdrawConsentAndDeleteData() }
            collection.value = CollectionState(CollectionStatus.STOPPED)
            runCurrent()
            coVerify(exactly = 1) { privacy.withdrawConsentAndDeleteData() }
            assertEquals("本地数据已删除，服务端删除尚未完成", model.collectionStatus.value)
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun failedStopDoesNotDeleteDataWhileProducerMayStillBeRunning() = runTest(dispatcher) {
        try {
            consent.value = ConsentState.GRANTED
            collection.value = CollectionState(CollectionStatus.RUNNING)
            val model = createModel()
            runCurrent()
            model.withdrawConsentAndDeleteData()
            runCurrent()
            advanceTimeBy(30_001L)
            runCurrent()
            coVerify(exactly = 0) { privacy.withdrawConsentAndDeleteData() }
            assertTrue(model.errorMessage.value.orEmpty().contains("停止超时"))
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun uploadCountCatchesUpAfterUiStopsObservingAndViewModelIsRecreated() = runTest(dispatcher) {
        try {
            consent.value = ConsentState.GRANTED
            collection.value = CollectionState(CollectionStatus.RUNNING)
            val model = createModel()
            runCurrent()
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle = registry
            }
            owner.registry.currentState = Lifecycle.State.STARTED
            val observed = mutableListOf<Long>()
            model.transmissionStats.observe(owner) { observed += it.packetsSent }
            assertEquals(0L, observed.last())

            owner.registry.currentState = Lifecycle.State.CREATED
            progress.value = UploadProgress(acknowledgedPackets = 65, lastAckTimestamp = 10_000)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(0L, observed.last())
            assertEquals(65L, model.transmissionStats.value!!.packetsSent)

            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(65L, observed.last())
            owner.registry.currentState = Lifecycle.State.DESTROYED
            store.clear()
            runCurrent()
            progress.value = UploadProgress(acknowledgedPackets = 66, lastAckTimestamp = 11_000)
            val recreated = createModel()
            runCurrent()
            assertEquals(66L, recreated.transmissionStats.value!!.packetsSent)
            assertTrue(recreated.isEncryptedUploading.value == true)
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun persistedCountRefreshesWhileCollectionIsStopped() = runTest(dispatcher) {
        try {
            consent.value = ConsentState.GRANTED
            val model = createModel()
            runCurrent()
            progress.value = UploadProgress(acknowledgedPackets = 42)
            runCurrent()
            assertEquals(42L, model.transmissionStats.value!!.packetsSent)
        } finally {
            store.clear()
            runCurrent()
        }
    }

    @Test
    fun startingWithoutConsentOrWithProtectionOffNeverCreatesAService() = runTest(dispatcher) {
        val model = createModel()
        runCurrent()
        model.startAuthentication()
        runCurrent()
        assertTrue(model.errorMessage.value.orEmpty().contains("同意"))
        assertNull(shadowOf(application).nextStartedService)
        consent.value = ConsentState.GRANTED
        runCurrent()
        model.setProtectionMode(ProtectionMode.OFF)
        model.startAuthentication()
        runCurrent()
        assertTrue(model.errorMessage.value.orEmpty().contains("防护已关闭"))
        assertNull(shadowOf(application).nextStartedService)
        store.clear()
        runCurrent()
    }

    @Test
    fun disablingAllSensorsPreventsStartingCollectionAndAuthentication() = runTest(dispatcher) {
        consent.value = ConsentState.GRANTED
        val model = createModel()
        runCurrent()
        listOf("accelerometer", "gyroscope", "magnetometer").forEach {
            model.setSensorCollectionEnabled(it, false)
        }
        model.startEncryptedUpload()
        model.startAuthentication()
        runCurrent()
        assertTrue(model.errorMessage.value.orEmpty().contains("至少一种"))
        assertNull(shadowOf(application).nextStartedService)
        store.clear()
        runCurrent()
    }

    @Test
    fun stoppingAuthenticationRotatesSessionWithoutStoppingCollection() = runTest(dispatcher) {
        consent.value = ConsentState.GRANTED
        collection.value = CollectionState(CollectionStatus.RUNNING)
        authActive.value = true
        val model = createModel()
        runCurrent()
        model.stopAuthentication()
        runCurrent()
        assertEquals(DataCollectionService.ACTION_RESET_AUTHENTICATION, shadowOf(application).nextStartedService.action)
        assertTrue(model.isCollectionRunning.value == true)
        assertTrue(!model.authUiState.value.authActive)
        store.clear()
        runCurrent()
    }

    @Test
    fun persistedAuthenticationWithoutAServiceCanBeStartedAgain() = runTest(dispatcher) {
        consent.value = ConsentState.GRANTED
        collection.value = CollectionState(CollectionStatus.STOPPED)
        authActive.value = true
        val model = createModel()
        runCurrent()
        model.startAuthentication()
        runCurrent()
        assertTrue(model.authUiState.value.isStarting)
        assertEquals(DataCollectionService.ACTION_START_COLLECTION, shadowOf(application).nextStartedService.action)
        store.clear()
        runCurrent()
    }

    @Test
    fun beginningAuthenticationResumesManuallyPausedCollection() = runTest(dispatcher) {
        consent.value = ConsentState.GRANTED
        collection.value = CollectionState(CollectionStatus.PAUSED)
        val model = createModel()
        runCurrent()
        model.startAuthentication()
        runCurrent()
        assertEquals(DataCollectionService.ACTION_RESUME_COLLECTION, shadowOf(application).nextStartedService.action)
        store.clear()
        runCurrent()
    }

    private fun createModel(): MainViewModel {
        val upload: UploadManager = mockk(relaxed = true)
        every { upload.authResultFlow } returns MutableSharedFlow()
        every { upload.getUploadStatus() } answers {
            UploadStatus(true, false, ConnectionStatus.DISCONNECTED,
                progress.value.acknowledgedPackets, 0,
                ConnectionStats(ConnectionStatus.DISCONNECTED), QueueStats())
        }
        val network: NetworkEnvironmentDetector = mockk(relaxed = true)
        every { network.networkStateFlow } returns MutableStateFlow(NetworkState.UNKNOWN)
        val manager: SmartTransmissionManager = mockk(relaxed = true)
        every { manager.collectionState } returns collection
        val monitor: SystemMonitor = mockk(relaxed = true)
        every { monitor.transmissionStatus } returns MutableStateFlow(SystemMonitor.TransmissionStatus())
        every { monitor.timeSyncStatus } returns MutableStateFlow(SystemMonitor.TimeSyncStatus())
        val queue: FileQueueManager = mockk(relaxed = true)
        every { queue.uploadProgress } returns progress
        coEvery { queue.getQueueStatistics() } returns QueueStatisticsDetail()
        val model = MainViewModel(
            application, upload, network, mockk(relaxed = true), mockk(relaxed = true),
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
            queue, mockk(relaxed = true), privacy, monitor, manager,
            mockk(relaxed = true), AppSettingsRepository(application),
            mockk<AuthHistoryRepository>(relaxed = true).also {
                every { it.entries } returns MutableStateFlow(emptyList())
            }, authentication
        )
        store.put("subject", model)
        return model
    }
}
