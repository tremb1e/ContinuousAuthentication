package com.continuousauth.versioning

import android.app.Application
import org.robolectric.RuntimeEnvironment
import com.continuousauth.BuildConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class, manifest = Config.NONE)
class VersionManagerTest {
    @Test
    fun currentBuildVersionIsReportedAndRoutineChecksPreserveUpgradeTime() = runBlocking {
        val manager = VersionManager(RuntimeEnvironment.getApplication())
        manager.checkAndPerformUpgrade()
        val first = manager.getCurrentVersionInfo()
        assertEquals(BuildConfig.VERSION_CODE, first.appVersionCode)
        assertEquals(BuildConfig.VERSION_NAME, first.appVersionName)
        assertTrue(first.lastUpgradeTime > 0L)
        assertFalse(manager.needsUpgrade())

        assertTrue(manager.checkAndPerformUpgrade().upgrades.isEmpty())
        assertEquals(first.lastUpgradeTime, manager.getCurrentVersionInfo().lastUpgradeTime)
    }

    @Test
    fun missingNegativeAndFutureSchemaVersionsAreRejected() {
        val manager = VersionManager(RuntimeEnvironment.getApplication())
        assertFalse(manager.isDataPacketSchemaCompatible(-1))
        assertFalse(manager.isDataPacketSchemaCompatible(0))
        assertTrue(manager.isDataPacketSchemaCompatible(VersionManager.CURRENT_SCHEMA_VERSION))
        assertFalse(manager.isDataPacketSchemaCompatible(VersionManager.CURRENT_SCHEMA_VERSION + 1))
    }
}
