package com.continuousauth.ui

data class TransmissionStats(
    val isFastMode: Boolean = false,
    val packetsSent: Long = 0L,
    val packetsPending: Int = 0,
    val lastAckLatency: Long? = null
)
