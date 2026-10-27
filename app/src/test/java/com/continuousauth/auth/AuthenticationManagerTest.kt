package com.continuousauth.auth

import android.app.Application
import android.content.Context
import com.continuousauth.network.UploadManager
import com.continuousauth.privacy.ConsentState
import com.continuousauth.privacy.PrivacyManager
import com.continuousauth.proto.AuthResult
import com.continuousauth.proto.AuthSessionResponse
import com.continuousauth.settings.AppSettingsRepository
import com.continuousauth.service.DataCollectionService
import com.continuousauth.settings.ProtectionMode
import com.continuousauth.utils.ForegroundAppDetector
import com.continuousauth.utils.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class AuthenticationManagerTest {
    private val dispatcher = StandardTestDispatcher()
    private val results = MutableSharedFlow<AuthResult>(extraBufferCapacity = 16)
    private val consent = MutableStateFlow(ConsentState.GRANTED)
    private val upload: UploadManager = mockk(relaxed = true)
    private val privacy: PrivacyManager = mockk(relaxed = true)
    private val sessions: SessionManager = mockk(relaxed = true)
    private val detector: ForegroundAppDetector = mockk(relaxed = true)
    private val alerts: AuthenticationAlertManager = mockk(relaxed = true)
    private val history: AuthHistoryRepository = mockk(relaxed = true)
    private val stored = mutableListOf<AuthHistoryEntry>()
    private lateinit var context: Context
    private lateinit var settings: AppSettingsRepository

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("authentication_state", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit().clear()
            .putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, true).commit()
        context.getSharedPreferences(AppSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        settings = AppSettingsRepository(context)
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        every { upload.authResultFlow } returns results
        every { privacy.consentState } returns consent
        every { sessions.getCurrentSessionId() } returns "session"
        coEvery { upload.startAuthentication("device", "session") } returns
            AuthSessionResponse.newBuilder().setAccepted(true).setSessionId("session").build()
        coEvery { detector.getForegroundAppAt(any()) } returns "com.example.reader"
        every { detector.getAppName("com.example.reader") } returns "阅读"
        coEvery { history.record(any(), any(), any()) } answers {
            val record = firstArg<AuthHistoryEntry>()
            if (!thirdArg<() -> Boolean>().invoke() || stored.any { it.id == record.id }) false else {
                stored += record
                true
            }
        }
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun monitorRecordsWithoutAnActivityAndNeverShowsAnAlert() = withManager { manager ->
        manager.startAuthentication("device", "session")
        results.emit(result(1, accepted = false))
        results.emit(result(2, accepted = true))
        runCurrent()
        assertEquals(2, stored.size)
        assertEquals("阅读", stored.first().appName)
        assertEquals("com.example.reader", stored.first().appPackage)
        assertFalse(stored.first().accepted)
        assertTrue(stored.last().accepted)
        verify(exactly = 0) { alerts.showFailure(any()) }
    }

    @Test fun alertModeOnlyWarnsForDistinctFailedWindows() = withManager { manager ->
        settings.setProtectionMode(ProtectionMode.ALERT)
        manager.startAuthentication("device", "session")
        val failure = result(1, accepted = false)
        results.emit(failure)
        results.emit(failure)
        results.emit(result(2, accepted = true))
        runCurrent()
        assertEquals(2, stored.size)
        verify(exactly = 1) { alerts.showFailure("阅读") }
    }

    @Test fun stoppingRejectsLateResultsAndDisabledSensorsPreventANewSession() = withManager { manager ->
        manager.startAuthentication("device", "session")
        manager.stopAuthentication()
        settings.setAllSensorsEnabled(false)
        results.emit(result(1, accepted = false))
        runCurrent()
        assertFalse(manager.active.value)
        assertNull(manager.startAuthentication("device", "session"))
        assertTrue(stored.isEmpty())
        verify(exactly = 0) { alerts.showFailure(any()) }
        coVerify(exactly = 1) { upload.startAuthentication(any(), any()) }
    }

    @Test fun unsolicitedInvalidAndOtherSessionResultsAreIgnored() = withManager { manager ->
        results.emit(result(1, accepted = false))
        runCurrent()
        assertTrue(stored.isEmpty())
        manager.startAuthentication("device", "session")
        results.emit(result(2, false).toBuilder().setSessionId("different-session").build())
        results.emit(result(3, false).toBuilder().setDeviceIdHash("different-device").build())
        results.emit(result(4, false).toBuilder().setNormalizedScore(Float.NaN).build())
        runCurrent()
        assertTrue(stored.isEmpty())
        assertNull(manager.latestResult.value)
    }

    @Test fun failedConnectionIsNotRecordedAsFailedIdentity() = withManager { manager ->
        coEvery { upload.startAuthentication(any(), any()) } returns null
        assertNull(manager.startAuthentication("device", "session"))
        results.emit(result(1, false))
        runCurrent()
        assertFalse(manager.active.value)
        assertTrue(stored.isEmpty())
    }

    @Test fun stoppedSessionCannotBeReactivatedByAnInFlightStartResponse() = withManager { manager ->
        val response = CompletableDeferred<AuthSessionResponse>()
        coEvery { upload.startAuthentication(any(), any()) } coAnswers { response.await() }
        val request = async { manager.startAuthentication("device", "session") }
        runCurrent()
        manager.stopAuthentication()
        response.complete(AuthSessionResponse.newBuilder().setAccepted(true).setSessionId("session").build())
        assertNull(request.await())
        assertFalse(manager.active.value)
    }

    @Test fun aPersistedSessionDoesNotResumeAfterTheUserStoppedCollection() {
        context.getSharedPreferences("authentication_state", Context.MODE_PRIVATE).edit()
            .putString("active_session", "session").putString("device_hash", "device").commit()
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).commit()
        withManager { manager ->
            assertFalse(manager.active.value)
            results.emit(result(1, false))
            runCurrent()
            assertTrue(stored.isEmpty())
            coVerify(exactly = 0) { upload.startAuthentication(any(), any()) }
        }
    }

    @Test fun aStopRequestPreventsStartingAnotherRpcWhileTheServiceDrains() = withManager { manager ->
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).commit()
        assertNull(manager.startAuthentication("device", "session"))
        coVerify(exactly = 0) { upload.startAuthentication(any(), any()) }
        assertFalse(manager.active.value)
    }

    @Test fun aStopRequestRejectsAnInFlightResponseEvenBeforeServiceCleanupRuns() = withManager { manager ->
        val response = CompletableDeferred<AuthSessionResponse>()
        coEvery { upload.startAuthentication(any(), any()) } coAnswers { response.await() }
        val request = async { manager.startAuthentication("device", "session") }
        runCurrent()
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).commit()
        response.complete(AuthSessionResponse.newBuilder().setAccepted(true).setSessionId("session").build())
        assertNull(request.await())
        assertFalse(manager.active.value)
    }

    @Test fun aStopRequestRejectsIncomingResultsBeforeServiceCleanupRuns() = withManager { manager ->
        settings.setProtectionMode(ProtectionMode.ALERT)
        manager.startAuthentication("device", "session")
        context.getSharedPreferences(DataCollectionService.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(DataCollectionService.COLLECTION_REQUESTED_KEY, false).commit()
        results.emit(result(1, false))
        runCurrent()
        assertTrue(stored.isEmpty())
        verify(exactly = 0) { alerts.showFailure(any()) }
    }

    @Test fun revokingConsentImmediatelyBlocksRecordsAndAlerts() = withManager { manager ->
        settings.setProtectionMode(ProtectionMode.ALERT)
        manager.startAuthentication("device", "session")
        consent.value = ConsentState.WITHDRAWN
        results.emit(result(1, false))
        runCurrent()
        assertFalse(manager.active.value)
        assertTrue(stored.isEmpty())
        verify(exactly = 0) { alerts.showFailure(any()) }
    }

    @Test fun aResultWaitingForStorageCannotReappearAfterStopAndDeletion() = withManager { manager ->
        val enterStorage = CompletableDeferred<Unit>()
        val resumeStorage = CompletableDeferred<Unit>()
        coEvery { history.record(any(), any(), any()) } coAnswers {
            enterStorage.complete(Unit)
            resumeStorage.await()
            if (thirdArg<() -> Boolean>().invoke()) {
                stored += firstArg<AuthHistoryEntry>()
                true
            } else false
        }
        settings.setProtectionMode(ProtectionMode.ALERT)
        manager.startAuthentication("device", "session")
        results.emit(result(1, false))
        runCurrent()
        assertTrue(enterStorage.isCompleted)
        manager.stopAuthentication()
        consent.value = ConsentState.WITHDRAWN
        stored.clear()
        resumeStorage.complete(Unit)
        runCurrent()
        assertTrue(stored.isEmpty())
        assertNull(manager.latestResult.value)
        verify(exactly = 0) { alerts.showFailure(any()) }
    }

    @Test fun stoppedAuthenticationIgnoresLaterWindowsEvenWhenMonitoringRemainsSelected() = withManager { manager ->
        manager.startAuthentication("device", "session")
        manager.stopAuthentication()
        results.emit(result(1, false))
        runCurrent()
        assertTrue(stored.isEmpty())
        assertFalse(manager.active.value)
        assertEquals(ProtectionMode.MONITOR_ONLY, settings.protectionMode.value)
    }

    @Test fun unknownAppIsKeptUnknownAndAnInterruptAlwaysCountsAsFailure() = withManager { manager ->
        coEvery { detector.getForegroundAppAt(any()) } returns ""
        manager.startAuthentication("device", "session")
        results.emit(result(1, true).toBuilder().setInterrupt(true).build())
        runCurrent()
        assertFalse(stored.single().accepted)
        assertEquals("", stored.single().appPackage)
        assertEquals("", stored.single().appName)
    }

    private fun withManager(body: suspend TestScope.(AuthenticationManager) -> Unit) = runTest(dispatcher) {
        val manager = AuthenticationManager(context, upload, settings, history, privacy, sessions, detector, alerts, dispatcher)
        try {
            runCurrent()
            body(manager)
        } finally {
            manager.close()
            runCurrent()
        }
    }

    private fun result(window: Long, accepted: Boolean) = AuthResult.newBuilder()
        .setSessionId("session").setDeviceIdHash("device")
        .setServerTimestampMs(System.currentTimeMillis())
        .setWindowId(window).setAccept(accepted).setNormalizedScore(0.7f).setThreshold(0.5f).build()
}
