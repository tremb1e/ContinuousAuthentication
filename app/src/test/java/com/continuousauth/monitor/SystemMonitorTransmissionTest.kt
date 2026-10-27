package com.continuousauth.monitor

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.hardware.SensorManager
import android.os.BatteryManager
import com.continuousauth.network.ConnectionStats
import com.continuousauth.network.ConnectionStatus
import com.continuousauth.network.TransportState
import com.continuousauth.network.UploadManager
import com.continuousauth.network.UploadStatus
import com.continuousauth.network.Uploader
import com.continuousauth.storage.QueueStats
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class SystemMonitorTransmissionTest {
    private val upload: UploadManager = mockk(relaxed = true)
    private val uploader: Uploader = mockk(relaxed = true)
    private lateinit var monitor: SystemMonitor

    @Before
    fun setUp() {
        val context: Context = mockk()
        every { context.getSystemService(Context.SENSOR_SERVICE) } returns mockk<SensorManager>(relaxed = true)
        every { context.getSystemService(Context.BATTERY_SERVICE) } returns mockk<BatteryManager>(relaxed = true)
        every { context.getSystemService(Context.ACTIVITY_SERVICE) } returns mockk<ActivityManager>(relaxed = true)
        every { uploader.getTransportState() } returns TransportState(lastAttemptMs = 9_999L)
        every { upload.getUploadStatus() } returns UploadStatus(
            true, false, ConnectionStatus.DISCONNECTED, 0L, 4,
            ConnectionStats(ConnectionStatus.DISCONNECTED), QueueStats(pendingPackets = 4)
        )
        monitor = SystemMonitor(context, uploader, upload,
            mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun connectionAttemptDoesNotCountAsSuccessfulUpload() {
        every { upload.getLastAckTimestamp() } returns 0L
        monitor.updateTransmissionStatus()
        assertEquals(0L, monitor.transmissionStatus.value.lastUploadTime)
        assertEquals(9_999L, monitor.transmissionStatus.value.transportLastAttemptMs)
    }

    @Test
    fun refreshKeepsActualAcknowledgementTime() {
        every { upload.getLastAckTimestamp() } returns 500L
        monitor.updateTransmissionStatus()
        monitor.updateTransmissionStatus()
        assertEquals(500L, monitor.transmissionStatus.value.lastUploadTime)
    }

    @Test
    fun mirroredMemoryAndDiskPacketsAreNotCountedTwice() {
        monitor.updateTransmissionStatus()
        assertEquals(4, monitor.transmissionStatus.value.uploadQueueSize)
    }
}
