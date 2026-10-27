package com.continuousauth.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.continuousauth.testing.HeartbeatEntryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in: a read-only heartbeat. No auth session, sensor collection or upload is created. */
@RunWith(AndroidJUnit4::class)
class ServerHeartbeatInstrumentedTest {
    @Test fun configuredEndpointRespondsToRealGrpcHeartbeat() = runBlocking {
        val input = InstrumentationRegistry.getArguments().getString("serverHeartbeatEndpoint")
        assumeTrue("Pass serverHeartbeatEndpoint explicitly for a read-only online check", !input.isNullOrBlank())
        val endpoint = ServerEndpointNormalizer.normalize(input)
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val uploader = EntryPointAccessors.fromApplication(context, HeartbeatEntryPoint::class.java).uploader()
        val result = withTimeout(25_000L) {
            uploader.testServerConnection(endpoint.host, endpoint.port, endpoint.useTls, true)
        }
        assertTrue(uploader.getTestResultDescription(result), result.isReachable)
    }
}
