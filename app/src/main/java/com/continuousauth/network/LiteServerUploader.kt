package com.continuousauth.network

import android.content.Context
import android.util.Log
import com.continuousauth.processing.LiteEncryptedPacket
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import java.util.concurrent.TimeUnit

/**
 * 面向 server-lite 的简单 HTTP 上传器。
 * 请求体: application/octet-stream (iv|tag|ciphertext)
 * Header: X-Device-ID-Hash / X-Session-ID / X-Packet-Sequence
 */
@Singleton
class LiteServerUploader @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "LiteServerUploader"
        private const val DEFAULT_HOST = "10.0.2.2"
        private const val DEFAULT_PORT = 8000
        private const val API_PATH = "/api/v1/sensor-data"
    }

    private val mediaType = "application/octet-stream".toMediaType()
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    suspend fun upload(packet: LiteEncryptedPacket): Boolean = withContext(Dispatchers.IO) {
        val baseUrl = resolveBaseUrl()
        val primaryUrl = baseUrl.newBuilder()
            .addPathSegments(API_PATH.removePrefix("/"))
            .build()

        val request = Request.Builder()
            .url(primaryUrl)
            .header("Content-Type", "application/octet-stream")
            .header("X-Device-ID-Hash", packet.deviceIdHash)
            .header("X-Session-ID", packet.sessionId)
            .header("X-Packet-Sequence", packet.packetSeqNo.toString())
            .post(packet.payload.toRequestBody(mediaType))
            .build()

        return@withContext tryUpload(request) ?: run {
            // 若使用 http 失败且服务器可能要求 https，则尝试 https 一次
            if (primaryUrl.isHttps) {
                null
            } else {
                val httpsUrl = primaryUrl.newBuilder().scheme("https").build()
                val httpsRequest = request.newBuilder().url(httpsUrl).build()
                tryUpload(httpsRequest)
            }
        } ?: false
    }

    private fun tryUpload(request: Request): Boolean? {
        return try {
            Log.d(TAG, "上传到: ${request.url}")
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val bodySnippet = response.body?.string()?.take(200)
                    Log.e(
                        TAG,
                        "上传失败: HTTP ${response.code} ${response.message} -> ${request.url}" +
                            (bodySnippet?.let { " | body=$it" } ?: "")
                    )
                    false
                } else {
                    true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "上传过程中出现异常: ${request.url} | ${e.message}", e)
            null
        }
    }

    private fun resolveBaseUrl(): HttpUrl {
        val prefs = context.getSharedPreferences("server_config", Context.MODE_PRIVATE)
        val rawHost = prefs.getString("server_ip", DEFAULT_HOST.removePrefix("http://")) ?: DEFAULT_HOST
        val portPref = prefs.getInt("server_port", DEFAULT_PORT)
        val scheme = prefs.getString("server_scheme", "http")?.lowercase() ?: "http"
        val port = if (portPref > 0) portPref else if (scheme == "https") 443 else DEFAULT_PORT

        // 允许用户在 host 中写入协议
        val parsed = if (rawHost.startsWith("http://") || rawHost.startsWith("https://")) {
            rawHost.toHttpUrlOrNull()
        } else null

        val finalScheme = parsed?.scheme ?: scheme
        val finalHost = parsed?.host ?: rawHost
        val finalPort = parsed?.port ?: port

        return HttpUrl.Builder()
            .scheme(if (finalScheme == "https") "https" else "http")
            .host(finalHost)
            .port(finalPort)
            .build()
    }
}
