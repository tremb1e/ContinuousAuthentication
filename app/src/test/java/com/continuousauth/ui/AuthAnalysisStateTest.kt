package com.continuousauth.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthAnalysisStateTest {

    @Test
    fun start_resetsCurrentStatsAndKeepsLogs() {
        val previousLog = AuthAnalysisLogEntry(
            startTimeMs = 1000L,
            durationMs = 3000L,
            passCount = 2,
            failCount = 1,
            passRatePercent = 66.666664f
        )

        val state = AuthAnalysisState(
            passCount = 9,
            failCount = 8,
            logs = listOf(previousLog)
        ).start(10_000L)

        assertTrue(state.isRunning)
        assertEquals(10_000L, state.startTimeMs)
        assertEquals(0L, state.elapsedMs)
        assertEquals(0, state.passCount)
        assertEquals(0, state.failCount)
        assertEquals(listOf(previousLog), state.logs)
    }

    @Test
    fun recordResult_countsOnlyWhileRunningAndUpdatesElapsedTime() {
        val state = AuthAnalysisState()
            .recordResult(accepted = true, nowMs = 999L)
            .start(1_000L)
            .recordResult(accepted = true, nowMs = 2_000L)
            .recordResult(accepted = false, nowMs = 3_500L)
            .recordResult(accepted = true, nowMs = 4_000L)

        assertEquals(2, state.passCount)
        assertEquals(1, state.failCount)
        assertEquals(3, state.totalCount)
        assertEquals(3_000L, state.elapsedMs)
        assertEquals(66.666664f, state.passRatePercent, 0.0001f)
    }

    @Test
    fun stop_freezesStatsAndWritesNewestLogFirst() {
        val state = AuthAnalysisState()
            .start(10_000L)
            .recordResult(accepted = true, nowMs = 11_000L)
            .recordResult(accepted = false, nowMs = 12_000L)
            .stop(15_500L)
            .recordResult(accepted = true, nowMs = 16_000L)

        assertFalse(state.isRunning)
        assertEquals(1, state.passCount)
        assertEquals(1, state.failCount)
        assertEquals(5_500L, state.elapsedMs)
        assertEquals(1, state.logs.size)

        val log = state.logs.first()
        assertEquals(10_000L, log.startTimeMs)
        assertEquals(5_500L, log.durationMs)
        assertEquals(1, log.passCount)
        assertEquals(1, log.failCount)
        assertEquals(50f, log.passRatePercent, 0.0001f)
    }

    @Test
    fun restartAfterStop_startsNewAnalysisAndPreservesHistory() {
        val stopped = AuthAnalysisState()
            .start(1_000L)
            .recordResult(accepted = false, nowMs = 2_000L)
            .stop(3_000L)

        val restarted = stopped.start(4_000L)

        assertTrue(restarted.isRunning)
        assertEquals(4_000L, restarted.startTimeMs)
        assertEquals(0, restarted.passCount)
        assertEquals(0, restarted.failCount)
        assertEquals(1, restarted.logs.size)
    }

    @Test
    fun stop_keepsOnlyRecentTenLogs() {
        var state = AuthAnalysisState()
        repeat(12) { index ->
            val start = index * 10_000L
            state = state.start(start)
                .recordResult(accepted = index % 2 == 0, nowMs = start + 1_000L)
                .stop(start + 2_000L)
        }

        assertEquals(10, state.logs.size)
        assertEquals(110_000L, state.logs.first().startTimeMs)
        assertEquals(20_000L, state.logs.last().startTimeMs)
    }

    @Test
    fun tickAndStop_neverReturnNegativeElapsedTime() {
        val ticking = AuthAnalysisState().start(5_000L).tick(4_000L)
        val stopped = ticking.stop(4_500L)

        assertEquals(0L, ticking.elapsedMs)
        assertEquals(0L, stopped.elapsedMs)
        assertEquals(0L, stopped.logs.first().durationMs)
    }
}
