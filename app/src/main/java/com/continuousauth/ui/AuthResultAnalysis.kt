package com.continuousauth.ui

data class AuthAnalysisLogEntry(
    val startTimeMs: Long,
    val durationMs: Long,
    val passCount: Int,
    val failCount: Int,
    val passRatePercent: Float
)

data class AuthAnalysisState(
    val isRunning: Boolean = false,
    val startTimeMs: Long = 0L,
    val elapsedMs: Long = 0L,
    val passCount: Int = 0,
    val failCount: Int = 0,
    val logs: List<AuthAnalysisLogEntry> = emptyList()
) {
    val totalCount: Int
        get() = passCount + failCount

    val passRatePercent: Float
        get() = calculatePassRate(passCount, failCount)

    fun start(nowMs: Long): AuthAnalysisState {
        return AuthAnalysisState(
            isRunning = true,
            startTimeMs = nowMs,
            elapsedMs = 0L,
            passCount = 0,
            failCount = 0,
            logs = logs
        )
    }

    fun tick(nowMs: Long): AuthAnalysisState {
        if (!isRunning) return this
        return copy(elapsedMs = elapsedSinceStart(nowMs))
    }

    fun recordResult(accepted: Boolean, nowMs: Long): AuthAnalysisState {
        if (!isRunning) return this
        return copy(
            elapsedMs = elapsedSinceStart(nowMs),
            passCount = passCount + if (accepted) 1 else 0,
            failCount = failCount + if (accepted) 0 else 1
        )
    }

    fun stop(nowMs: Long, maxLogs: Int = 10): AuthAnalysisState {
        if (!isRunning) return this
        val duration = elapsedSinceStart(nowMs)
        val logEntry = AuthAnalysisLogEntry(
            startTimeMs = startTimeMs,
            durationMs = duration,
            passCount = passCount,
            failCount = failCount,
            passRatePercent = passRatePercent
        )
        val nextLogs = if (maxLogs <= 0) {
            emptyList()
        } else {
            (listOf(logEntry) + logs).take(maxLogs)
        }
        return copy(
            isRunning = false,
            elapsedMs = duration,
            logs = nextLogs
        )
    }

    private fun elapsedSinceStart(nowMs: Long): Long {
        return (nowMs - startTimeMs).coerceAtLeast(0L)
    }
}

private fun calculatePassRate(passCount: Int, failCount: Int): Float {
    val total = passCount + failCount
    return if (total <= 0) 0f else passCount * 100f / total
}
