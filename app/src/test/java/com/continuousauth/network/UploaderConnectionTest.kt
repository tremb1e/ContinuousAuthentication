package com.continuousauth.network

import android.app.Application
import com.continuousauth.policy.PolicyConfiguration
import com.continuousauth.policy.PolicyManager
import com.continuousauth.policy.SecurityConfiguration
import com.continuousauth.proto.AuthSessionRequest
import com.continuousauth.proto.AuthSessionResponse
import com.continuousauth.proto.DataPacket
import com.continuousauth.proto.Heartbeat
import com.continuousauth.proto.HeartbeatAck
import com.continuousauth.proto.SensorDataServiceGrpc
import com.continuousauth.proto.ServerDirective
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.Status
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.grpc.stub.ServerCallStreamObserver
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real client/server calls in memory exercise RPC semantics without sending data to production. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class UploaderConnectionTest {
    private val servers = mutableListOf<Server>()
    private val channels = mutableListOf<ManagedChannel>()
    private val endpoints = mutableListOf<ServerEndpoint>()
    private val services = mutableMapOf<String, String>()
    private val uploaderDispatcher = StandardTestDispatcher()
    private val policy = mockk<PolicyManager> {
        every { getCurrentPolicyConfiguration() } returns mockk<PolicyConfiguration> {
            every { securityConfig } returns SecurityConfiguration("", emptySet())
        }
    }
    private val uploader = UploaderImpl(
        TlsSecurityManager(), policy, mockk(relaxed = true), mockk(relaxed = true), uploaderDispatcher
    ) { endpoint, _ ->
        endpoints += endpoint
        InProcessChannelBuilder.forName(services.getValue(endpoint.host)).directExecutor().build().also(channels::add)
    }

    @After fun tearDown() = runBlocking {
        uploader.disconnect()
        channels.forEach { it.shutdownNow() }
        servers.forEach { it.shutdownNow() }
    }

    @Test fun reachableHttp2ServerWithoutOurRpcNeverCountsAsConnected() = runBlocking {
        serve("wrong.test", object : SensorDataServiceGrpc.SensorDataServiceImplBase() {})
        assertFalse(uploader.connect("https://wrong.test:443"))
        assertFalse(uploader.isConnected())
        assertTrue(uploader.getConnectionStats().lastErrorMessage.orEmpty().contains("没有提供"))
    }

    @Test fun gatewayFailureIsReportedWithoutSilentlyDowngradingHttps() = runBlocking {
        serve("gateway.test", object : HealthyService() {
            override fun sendHeartbeat(request: Heartbeat, response: StreamObserver<HeartbeatAck>) {
                response.onError(Status.UNAVAILABLE.withDescription("Received http2 header with status: 504").asRuntimeException())
            }
        })
        assertFalse(uploader.connect("https://gateway.test:8443"))
        assertEquals(1, endpoints.size)
        assertTrue(endpoints.single().useTls)
        assertTrue(uploader.getConnectionStats().lastErrorMessage.orEmpty().contains("504"))
        assertFalse(uploader.getTransportState().downgradedToCleartext)
    }

    @Test fun probingAnotherServerPreservesTheActiveConnectionAndTestsTheRequestedHost() = runBlocking {
        val service = HealthyService()
        serve("active.test", service)
        serve("wrong.test", object : SensorDataServiceGrpc.SensorDataServiceImplBase() {})
        assertTrue(uploader.connect("https://active.test:443"))
        val active = channels.single()

        val result = uploader.testServerConnection("wrong.test", 443, true, true)

        assertFalse(result.isReachable)
        assertTrue(uploader.isConnected())
        assertFalse(active.isShutdown)
        assertTrue(channels.last().isShutdown)
        assertEquals("https://active.test:443", uploader.getServerEndpoint())
        assertTrue(uploader.sendDataPacket(DataPacket.newBuilder().setPacketId("local-test-only").build()))
        assertEquals("local-test-only", withTimeout(2_000) { service.packet.await() }.packetId)
    }

    @Test fun successfulStandaloneProbeDoesNotOpenUploadStreamOrChangeActiveState() = runBlocking {
        val service = HealthyService()
        serve("healthy.test", service)
        assertTrue(uploader.testServerConnection("healthy.test", 443, true, true).isReachable)
        assertFalse(uploader.isConnected())
        assertEquals(0, service.streams)
        assertTrue(channels.single().isShutdown)
    }

    @Test fun explicitConnectToADifferentEndpointSwitchesChannels() = runBlocking {
        serve("first.test", HealthyService())
        serve("second.test", HealthyService())
        assertTrue(uploader.connect("http://first.test:10500"))
        val original = channels.single()
        assertTrue(uploader.connect("http://second.test:10500"))
        assertTrue(original.isShutdown)
        assertEquals("http://second.test:10500", uploader.getServerEndpoint())
        assertEquals(2, channels.size)
    }

    @Test fun cancellationClosesOnlyProbeAndCancelsItsPendingRpc() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        serve("slow.test", object : HealthyService() {
            override fun sendHeartbeat(request: Heartbeat, response: StreamObserver<HeartbeatAck>) {
                (response as ServerCallStreamObserver).setOnCancelHandler { cancelled.complete(Unit) }
                entered.complete(Unit)
            }
        })
        val probe = async { uploader.testServerConnection("slow.test", 443, true, true) }
        withTimeout(2_000) { entered.await() }
        probe.cancelAndJoin()
        withTimeout(2_000) { cancelled.await() }
        assertTrue(channels.single().isShutdown)
        assertFalse(uploader.isConnected())
    }

    @Test fun authenticationCancellationReachesServerWithoutDisconnectingUploads() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        serve("auth.test", object : HealthyService() {
            override fun startAuthentication(request: AuthSessionRequest, response: StreamObserver<AuthSessionResponse>) {
                (response as ServerCallStreamObserver).setOnCancelHandler { cancelled.complete(Unit) }
                entered.complete(Unit)
            }
        })
        assertTrue(uploader.connect("https://auth.test:443"))
        val request = async { uploader.startAuthentication("local-device", "local-session") }
        withTimeout(2_000) { entered.await() }
        request.cancelAndJoin()
        withTimeout(2_000) { cancelled.await() }
        assertTrue(uploader.isConnected())
    }

    @Test fun mismatchedHeartbeatEchoCannotProduceFalseSuccess() = runBlocking {
        serve("echo.test", object : HealthyService() {
            override fun sendHeartbeat(request: Heartbeat, response: StreamObserver<HeartbeatAck>) {
                response.onNext(HeartbeatAck.newBuilder().setServerTimestamp(10).setClientTimestampEcho(-1).build())
                response.onCompleted()
            }
        })
        assertFalse(uploader.connect("https://echo.test:443"))
    }

    @Test fun serverClosureLeavesReconnectionToTheUploadManager() = runTest(uploaderDispatcher) {
        val response = CompletableDeferred<StreamObserver<ServerDirective>>()
        serve("closed.test", object : HealthyService() {
            override fun streamSensorData(observer: StreamObserver<ServerDirective>): StreamObserver<DataPacket> {
                response.complete(observer)
                return super.streamSensorData(observer)
            }
        })
        assertTrue(uploader.connect("https://closed.test:443"))
        response.await().onCompleted()
        runCurrent()

        assertEquals(ConnectionStatus.DISCONNECTED.name, uploader.getConnectionStatus().state)
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals("Only the owner may open another connection", 1, channels.size)
        assertFalse(uploader.sendDataPacket(DataPacket.newBuilder().setPacketId("after-close").build()))

        // The running UploadManager can explicitly recover the connection on its next check.
        assertTrue(uploader.connect("https://closed.test:443"))
        assertEquals(2, channels.size)
        uploader.disconnect()
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(2, channels.size)
        assertEquals(ConnectionStatus.DISCONNECTED.name, uploader.getConnectionStatus().state)
    }

    private fun serve(host: String, service: SensorDataServiceGrpc.SensorDataServiceImplBase) {
        val name = InProcessServerBuilder.generateName()
        services[host] = name
        servers += InProcessServerBuilder.forName(name).directExecutor().addService(service).build().start()
    }

    private open class HealthyService : SensorDataServiceGrpc.SensorDataServiceImplBase() {
        var streams = 0
        val packet = CompletableDeferred<DataPacket>()
        override fun sendHeartbeat(request: Heartbeat, response: StreamObserver<HeartbeatAck>) {
            response.onNext(HeartbeatAck.newBuilder().setServerTimestamp(System.currentTimeMillis())
                .setClientTimestampEcho(request.clientTimestamp).build())
            response.onCompleted()
        }

        override fun streamSensorData(response: StreamObserver<ServerDirective>): StreamObserver<DataPacket> {
            streams++
            return object : StreamObserver<DataPacket> {
                override fun onNext(value: DataPacket) { packet.complete(value) }
                override fun onError(error: Throwable) = Unit
                override fun onCompleted() { response.onCompleted() }
            }
        }
    }
}
