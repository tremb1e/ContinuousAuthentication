package com.continuousauth.network

import io.grpc.Status
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Keep a server failure distinct from a phone being offline. Never expose arbitrary server text. */
internal fun describeConnectionFailure(error: Throwable): String {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    val description = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
    val gatewayCode = Regex("(?:status(?: code)?[:= ]+|http[/0-9. ]+)(502|503|504)\\b")
        .find(description)?.groupValues?.get(1)
    return when {
        gatewayCode != null -> "服务器暂时无法提供认证服务（$gatewayCode），请稍后重试或联系服务管理员"
        causes.any { it is UnknownHostException } -> "找不到服务器地址，请检查地址和网络后重试"
        causes.any { it is SSLException } -> "无法验证服务器的安全连接，请检查手机时间或联系服务管理员"
        description.contains("invalid content-type") || description.contains("http status code 404") ->
            "这个地址没有提供认证服务，请检查服务器地址和端口"
        else -> when (Status.fromThrowable(error).code) {
            Status.Code.UNIMPLEMENTED -> "这个地址没有提供所需的认证服务，请联系服务管理员"
            Status.Code.UNAUTHENTICATED, Status.Code.PERMISSION_DENIED -> "服务器拒绝了连接，请联系服务管理员"
            Status.Code.DEADLINE_EXCEEDED -> "服务器响应超时，请稍后重试。采集的数据会暂存在本机"
            Status.Code.UNAVAILABLE -> "暂时无法连接认证服务器，请检查网络或稍后重试"
            else -> "服务器未能完成请求，请稍后重试或联系服务管理员"
        }
    }
}
