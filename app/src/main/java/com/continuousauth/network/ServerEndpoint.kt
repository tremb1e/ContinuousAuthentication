package com.continuousauth.network

import android.net.Uri
import java.util.Locale

data class ServerEndpoint(
    val host: String,
    val port: Int,
    val scheme: String
) {
    val useTls: Boolean
        get() = scheme == ServerEndpointNormalizer.SCHEME_HTTPS

    val isPublicTlsIngress: Boolean
        get() = useTls && port == ServerEndpointNormalizer.DEFAULT_HTTPS_PORT

    val endpoint: String
        get() = "$scheme://$host:$port"
}

object ServerEndpointNormalizer {
    const val DEFAULT_HOST = "ca.macrz.com"
    const val DEFAULT_HTTPS_PORT = 443
    const val DEFAULT_HTTP_PORT = 80
    const val SCHEME_HTTPS = "https"
    const val SCHEME_HTTP = "http"

    private const val LEGACY_BACKEND_HOST = "ty.macrz.com"

    fun normalize(
        addressInput: String?,
        portInput: Int? = null,
        schemeInput: String? = null
    ): ServerEndpoint {
        val requestedScheme = schemeInput.normalizedScheme() ?: SCHEME_HTTPS
        val rawAddress = addressInput?.trim().orEmpty()
        val address = rawAddress.ifBlank { DEFAULT_HOST }
        val hasExplicitScheme = address.startsWith("http://", ignoreCase = true) ||
            address.startsWith("https://", ignoreCase = true)
        val parseTarget = if (hasExplicitScheme) address else "$requestedScheme://$address"
        val uri = runCatching { Uri.parse(parseTarget) }.getOrNull()

        var scheme = uri?.scheme.normalizedScheme() ?: requestedScheme
        var host = uri?.host?.trim().orEmpty()
        if (host.isBlank()) {
            host = address
                .removePrefix("http://")
                .removePrefix("https://")
                .substringBefore("/")
                .substringBefore(":")
                .trim()
        }
        if (host.isBlank()) host = DEFAULT_HOST

        var port = when {
            uri?.port != null && uri.port > 0 -> uri.port
            portInput != null && portInput > 0 -> portInput
            scheme == SCHEME_HTTP -> DEFAULT_HTTP_PORT
            else -> DEFAULT_HTTPS_PORT
        }

        if (host.equals(LEGACY_BACKEND_HOST, ignoreCase = true)) {
            host = DEFAULT_HOST
            scheme = SCHEME_HTTPS
            port = DEFAULT_HTTPS_PORT
        } else if (host.equals(DEFAULT_HOST, ignoreCase = true) &&
            (scheme == SCHEME_HTTP || port == DEFAULT_HTTP_PORT)
        ) {
            scheme = SCHEME_HTTPS
            port = DEFAULT_HTTPS_PORT
        } else if (scheme == SCHEME_HTTPS && port == DEFAULT_HTTP_PORT) {
            port = DEFAULT_HTTPS_PORT
        }

        return ServerEndpoint(
            host = host,
            port = port,
            scheme = scheme
        )
    }

    fun defaultEndpoint(): ServerEndpoint = ServerEndpoint(
        host = DEFAULT_HOST,
        port = DEFAULT_HTTPS_PORT,
        scheme = SCHEME_HTTPS
    )

    private fun String?.normalizedScheme(): String? {
        return when (this?.lowercase(Locale.US)) {
            SCHEME_HTTP -> SCHEME_HTTP
            SCHEME_HTTPS -> SCHEME_HTTPS
            else -> null
        }
    }
}
