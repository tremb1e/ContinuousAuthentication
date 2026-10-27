package com.continuousauth.network

import io.grpc.Status
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionErrorsTest {
    @Test fun actualGatewayResponseIsIdentifiedAsServerFailure() {
        listOf("Received http2 header with status: 504", "HTTP status code 502", "HTTP status code 503").forEach {
            val message = describeConnectionFailure(Status.UNAVAILABLE.withDescription(it).asRuntimeException())
            assertTrue(message, message.contains("服务器暂时无法提供认证服务"))
        }
    }

    @Test fun nestedDnsAndCertificateCausesHaveActionableMessages() {
        assertTrue(describeConnectionFailure(Status.UNAVAILABLE.withCause(UnknownHostException()).asRuntimeException()).contains("找不到"))
        assertTrue(describeConnectionFailure(Status.UNAVAILABLE.withCause(SSLHandshakeException("certificate")).asRuntimeException()).contains("安全连接"))
    }

    @Test fun unsupportedRpcAndTimeoutAreNotReportedAsIdentityFailures() {
        assertTrue(describeConnectionFailure(Status.UNIMPLEMENTED.asRuntimeException()).contains("没有提供"))
        assertTrue(describeConnectionFailure(Status.DEADLINE_EXCEEDED.asRuntimeException()).contains("响应超时"))
    }
}
