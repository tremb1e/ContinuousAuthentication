package com.continuousauth.service

internal class CollectionPauseState {
    enum class Reason { MANUAL, SCREEN }

    private val reasons = mutableSetOf<Reason>()

    val isPaused: Boolean get() = reasons.isNotEmpty()
    val allowBufferedUpload: Boolean get() = reasons == setOf(Reason.SCREEN)

    fun set(reason: Reason, paused: Boolean): Boolean =
        if (paused) reasons.add(reason) else reasons.remove(reason)
}
