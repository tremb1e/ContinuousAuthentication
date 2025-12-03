package com.continuousauth.network

/**
 * 传输通道模式：TLS (HTTPS) 或明文 (HTTP/h2c)。
 */
enum class TransportMode(val scheme: String) {
    HTTPS("https"),
    HTTP("http")
}

/**
 * 传输通道状态，兼容 UI 展示需要的字段。
 */
data class TransportState(
    val mode: TransportMode = TransportMode.HTTPS,
    val tlsVersion: String? = null,
    val negotiatedProtocol: String? = null,
    val tlsCapable: Boolean = false,
    val preferredScheme: String = "https",
    val downgradedToCleartext: Boolean = false,
    val lastResultSuccess: Boolean = false,
    val lastError: String? = null,
    val lastAttemptMs: Long = 0L
) {
    val hasAttempted: Boolean
        get() = lastAttemptMs > 0
}
