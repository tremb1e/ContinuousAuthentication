package com.continuousauth.ui.viewmodels

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.continuousauth.monitor.SystemMonitor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class DetailedInfoViewModelExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val monitor: SystemMonitor = mockk(relaxed = true)
    private val store = ViewModelStore()
    private lateinit var viewModel: DetailedInfoViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        every { monitor.transmissionStatus } returns MutableStateFlow(SystemMonitor.TransmissionStatus())
        every { monitor.grpcStatus } returns MutableStateFlow(SystemMonitor.GrpcConnectionStatus())
        every { monitor.bufferStats } returns MutableStateFlow(SystemMonitor.BufferStatistics())
        every { monitor.sensorInfoMap } returns MutableStateFlow(emptyMap())
        every { monitor.deviceKeyInfo } returns MutableStateFlow(SystemMonitor.DeviceKeyInfo())
        every { monitor.serverPolicy } returns MutableStateFlow(SystemMonitor.ServerPolicy())
        every { monitor.timeSyncStatus } returns MutableStateFlow(SystemMonitor.TimeSyncStatus())
        every { monitor.performanceMetrics } returns MutableStateFlow(SystemMonitor.PerformanceMetrics())
        every { monitor.cpuHistory } returns MutableStateFlow(emptyList())
        every { monitor.memoryHistory } returns MutableStateFlow(emptyList())
        every { monitor.latencyHistory } returns MutableStateFlow(emptyList())
        every { monitor.encryptionStatus } returns MutableStateFlow(SystemMonitor.EncryptionStatus())
        viewModel = DetailedInfoViewModel(RuntimeEnvironment.getApplication(), monitor)
        store.put("details", viewModel)
    }

    @After fun tearDown() {
        store.clear()
        File(RuntimeEnvironment.getApplication().cacheDir, DetailedInfoViewModel.EXPORT_CACHE_DIR).deleteRecursively()
        Dispatchers.resetMain()
    }

    @Test fun pendingDataIsPackagedAndTemporaryCopiesAreRemoved() = runTest {
        val source = temporary.newFolder("pending")
        File(source, "packet.dat").writeBytes(byteArrayOf(0, 1, 2, 127))
        coEvery { monitor.exportPendingData() } returns source.absolutePath

        val archive = viewModel.preparePendingDataExport()

        assertNotNull(archive)
        ZipFile(archive!!).use { zip ->
            assertEquals(1, zip.size())
            assertArrayEquals(byteArrayOf(0, 1, 2, 127), zip.getInputStream(zip.getEntry("packet.dat")).readBytes())
        }
        assertFalse(source.exists())
        viewModel.cancelPreparedExport(archive.absolutePath)
        assertFalse(archive.exists())
        assertEquals("已取消导出", viewModel.maintenanceMessage.value)
    }

    @Test fun noDataAndExportFailureAreNotReportedAsSuccess() = runTest {
        coEvery { monitor.exportPendingData() } returns "没有待处理的数据包"
        assertNull(viewModel.preparePendingDataExport())
        assertEquals("目前没有待上传的数据可导出", viewModel.maintenanceMessage.value)

        coEvery { monitor.exportPendingData() } returns "导出失败: 存储空间不足"
        assertNull(viewModel.preparePendingDataExport())
        assertEquals("导出失败: 存储空间不足", viewModel.maintenanceMessage.value)
    }

    @Test fun debugLogIsSavedToChosenDestinationAndTemporaryFileIsDeleted() = runTest {
        val generated = temporary.newFile("debug_log.txt").apply { writeText("运行日志\n正常") }
        coEvery { monitor.exportDebugLog() } returns generated.absolutePath
        val prepared = viewModel.prepareDebugLogExport()!!
        assertFalse(generated.exists())
        val destination = temporary.newFile("saved_log.txt")

        viewModel.savePreparedExport(prepared.absolutePath, Uri.fromFile(destination))

        assertEquals("运行日志\n正常", destination.readText())
        assertFalse(prepared.exists())
        assertTrue(viewModel.maintenanceMessage.value!!.startsWith("文件已保存"))
    }

    @Test fun missingTemporaryFileProducesActionableFailure() = runTest {
        val destination = temporary.newFile("destination.txt")
        viewModel.savePreparedExport(File(temporary.root, "missing.txt").absolutePath, Uri.fromFile(destination))
        assertTrue(viewModel.maintenanceMessage.value!!.contains("临时文件已失效"))
    }

    @Test fun maintenanceMarkerReportsActualResultWithoutClaimingKeyReplacement() = runTest {
        coEvery { monitor.forceKeyRotation() } returns false
        viewModel.forceKeyRotation()
        assertTrue(viewModel.maintenanceMessage.value!!.contains("未能更新"))
        coEvery { monitor.forceKeyRotation() } returns true
        viewModel.forceKeyRotation()
        assertTrue(viewModel.maintenanceMessage.value!!.contains("实际加密密钥未改变"))
    }
}
