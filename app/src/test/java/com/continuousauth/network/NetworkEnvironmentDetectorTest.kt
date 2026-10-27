package com.continuousauth.network

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.telephony.TelephonyManager
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class NetworkEnvironmentDetectorTest {
    private val activeNetwork = AtomicReference<Network?>(null)
    private val capabilities = AtomicReference<NetworkCapabilities?>(null)
    private lateinit var detector: NetworkEnvironmentDetector

    @Before
    fun setUp() {
        val context = mockk<Context>()
        val connectivity = mockk<ConnectivityManager>(relaxed = true)
        every { context.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivity
        every { context.getSystemService(Context.WIFI_SERVICE) } returns mockk<WifiManager>(relaxed = true)
        every { context.getSystemService(Context.TELEPHONY_SERVICE) } returns mockk<TelephonyManager>(relaxed = true)
        every { connectivity.activeNetwork } answers { activeNetwork.get() }
        every { connectivity.getNetworkCapabilities(any()) } answers { capabilities.get() }
        detector = NetworkEnvironmentDetector(context)
    }

    @After
    fun tearDown() {
        detector.cleanup()
    }

    @Test
    fun wifiGateTracksActualTransportWithoutUiOrNetworkStateSubscriber() {
        assertFalse(detector.isWifiConnected())

        activeNetwork.set(mockk())
        capabilities.set(capabilitiesFor(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(detector.isWifiConnected())
        assertTrue(detector.isNetworkAvailableForUpload(wifiOnly = true))

        capabilities.set(capabilitiesFor(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertFalse(detector.isWifiConnected())
        assertFalse(detector.isNetworkAvailableForUpload(wifiOnly = true))

        capabilities.set(capabilitiesFor(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(detector.isWifiConnected())

        activeNetwork.set(null)
        assertFalse(detector.isWifiConnected())
    }

    @Test
    fun missingCapabilitiesNeverReusePreviousWifiResult() {
        activeNetwork.set(mockk())
        capabilities.set(capabilitiesFor(NetworkCapabilities.TRANSPORT_WIFI))
        assertTrue(detector.isWifiConnected())

        capabilities.set(null)

        assertFalse(detector.isWifiConnected())
        assertFalse(detector.isNetworkAvailableForUpload(wifiOnly = true))
    }

    @Test
    fun internetProbeFailureDoesNotBlockReachablePrivateServerOrVpn() {
        activeNetwork.set(mockk())
        capabilities.set(mockk<NetworkCapabilities>(relaxed = true) {
            every { hasTransport(NetworkCapabilities.TRANSPORT_VPN) } returns true
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } returns true
            every { hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
        })
        assertTrue(detector.isNetworkAvailableForUpload(wifiOnly = false))
        assertFalse(detector.isNetworkAvailableForUpload(wifiOnly = true))
        activeNetwork.set(null)
        capabilities.set(null)
        assertFalse(detector.isNetworkAvailableForUpload(wifiOnly = false))
    }

    private fun capabilitiesFor(transport: Int) = mockk<NetworkCapabilities>(relaxed = true) {
        every { hasTransport(any()) } answers { firstArg<Int>() == transport }
    }
}
