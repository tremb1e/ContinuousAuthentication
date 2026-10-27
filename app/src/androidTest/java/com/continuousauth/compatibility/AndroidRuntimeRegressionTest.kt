package com.continuousauth.compatibility

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.continuousauth.BuildConfig
import com.continuousauth.compression.CompressionManager
import com.continuousauth.crypto.EnvelopeCryptoBox
import com.continuousauth.service.DataCollectionService
import com.continuousauth.ui.compose.MainComposeActivity
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real platform storage/crypto/native libraries on a disposable test device. */
@RunWith(AndroidJUnit4::class)
class AndroidRuntimeRegressionTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launcherAndForegroundServiceMatchAndroid16Contract() {
        val pm = context.packageManager
        assertEquals(36, context.applicationInfo.targetSdkVersion)
        val launcher = pm.getLaunchIntentForPackage(context.packageName)!!.component
        assertEquals(ComponentName(context, MainComposeActivity::class.java), launcher)
        val activity = pm.getActivityInfo(launcher!!, 0)
        assertTrue(activity.exported)
        val service = pm.getServiceInfo(ComponentName(context, DataCollectionService::class.java), 0)
        assertFalse(service.exported)
        if (Build.VERSION.SDK_INT >= 34) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0)
            assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE))
        }
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.INTERNET))
        val info = pm.getPackageInfo(context.packageName, 0)
        assertEquals(BuildConfig.VERSION_CODE.toLong(), info.longVersionCode)
        assertEquals(BuildConfig.VERSION_NAME, info.versionName)
    }

    @Test
    fun compressedEncryptedPayloadRoundTripsAndRejectsTampering() = runBlocking {
        val compression = CompressionManager()
        val crypto = EnvelopeCryptoBox(context)
        assertTrue(crypto.initialize())
        try {
            val payload = ByteArray(32_768) { (it % 251).toByte() }
            val aad = "device|session|sequence".toByteArray()
            for (type in listOf(CompressionManager.CompressionType.NONE,
                CompressionManager.CompressionType.LZ4, CompressionManager.CompressionType.GZIP)) {
                val compressed = requireNotNull(compression.compress(payload, type))
                val encrypted = requireNotNull(crypto.encrypt(compressed, aad))
                assertFalse(compressed.contentEquals(encrypted))
                val decrypted = requireNotNull(crypto.decrypt(encrypted, aad))
                assertArrayEquals(payload, compression.decompress(decrypted, type))
            }
            val empty = requireNotNull(crypto.encrypt(byteArrayOf(), null))
            assertArrayEquals(byteArrayOf(), crypto.decrypt(empty, null))
            val encrypted = requireNotNull(crypto.encrypt(payload, aad))
            val tampered = encrypted.copyOf().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
            assertNull(crypto.decrypt(tampered, aad))
            crypto.resetFailureCount()
            val changedIv = encrypted.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }
            assertNull(crypto.decrypt(changedIv, aad))
        } finally { crypto.destroy() }
    }

    @Test
    fun nativePreferencesPersistAndCompressionHandlesEmptyAndCorruptData() {
        val id = "runtime-regression-${System.nanoTime()}"
        val mmkv = requireNotNull(MMKV.mmkvWithID(id))
        try {
            assertTrue(mmkv.encode("choice", "中文设置"))
            mmkv.sync()
            assertEquals("中文设置", mmkv.decodeString("choice"))
            mmkv.removeValueForKey("choice")
            assertFalse(mmkv.containsKey("choice"))
        } finally { mmkv.close(); MMKV.removeStorage(id) }
        val compression = CompressionManager()
        for (type in listOf(CompressionManager.CompressionType.LZ4, CompressionManager.CompressionType.GZIP)) {
            val compressed = requireNotNull(compression.compress(byteArrayOf(), type))
            assertArrayEquals(byteArrayOf(), compression.decompress(compressed, type))
            assertNull(compression.decompress(byteArrayOf(1, 2), type))
        }
    }
}
