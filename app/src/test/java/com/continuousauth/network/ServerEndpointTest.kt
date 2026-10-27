package com.continuousauth.network

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class ServerEndpointTest {
    @Test fun ipv6EndpointRemainsUnambiguousWhenSerializedAndReopened() {
        val endpoint = ServerEndpointNormalizer.normalize("https://[2001:db8::1]", 8443)
        assertEquals("2001:db8::1", endpoint.host)
        assertEquals("https://[2001:db8::1]:8443", endpoint.endpoint)
        assertEquals(endpoint, ServerEndpointNormalizer.normalize(endpoint.endpoint))
        assertEquals(endpoint, ServerEndpointNormalizer.normalize(endpoint.host, endpoint.port, endpoint.scheme))
    }

    @Test fun defaultsAndLegacyIngressUsePublicTlsPort() {
        assertEquals("https://ca.macrz.com:443", ServerEndpointNormalizer.normalize(null).endpoint)
        assertEquals("https://ca.macrz.com:443", ServerEndpointNormalizer.normalize("http://ca.macrz.com:80").endpoint)
    }

    @Test fun explicitlyConfiguredPrivatePlaintextPortIsPreserved() {
        assertEquals("http://192.168.1.8:10500", ServerEndpointNormalizer.normalize("192.168.1.8", 10500, "http").endpoint)
    }

    @Test(expected = IllegalArgumentException::class)
    fun outOfRangePortIsRejectedBeforeOpeningSocket() {
        ServerEndpointNormalizer.normalize("example.test", 65536)
    }
}
