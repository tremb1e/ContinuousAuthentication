package com.continuousauth.ui.compose.screens

import com.continuousauth.ui.viewmodels.SensorDataViewModel.SensorData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorChartWindowTest {
    @Test fun longRunningPreviewKeepsOnlyTheLatestWindow() {
        val window = SensorChartWindow()
        repeat(100_000) { index -> window.add(point(index + 1L)) }
        val points = window.snapshot()
        assertEquals(120, points.size)
        assertEquals(99_881L, points.first().timestamp)
        assertEquals(100_000L, points.last().timestamp)
    }

    @Test fun missingDuplicateAndNonFiniteSamplesNeverReachTheRenderer() {
        val window = SensorChartWindow()
        assertFalse(window.add(SensorData()))
        assertFalse(window.add(point(1).copy(x = Float.NaN)))
        assertFalse(window.add(point(1).copy(y = Float.POSITIVE_INFINITY)))
        assertFalse(window.add(point(1).copy(z = Float.NEGATIVE_INFINITY)))
        assertTrue(window.add(point(1)))
        assertFalse(window.add(point(1)))
        assertEquals(listOf(point(1)), window.snapshot())
    }

    @Test fun clockCorrectionStartsANewChronologicalWindow() {
        val window = SensorChartWindow()
        window.add(point(1_000))
        window.add(point(2_000))
        window.add(point(500))
        assertEquals(listOf(point(500)), window.snapshot())
    }

    @Test fun publishedSnapshotDoesNotChangeWhenNewPointsArrive() {
        val window = SensorChartWindow(capacity = 2)
        window.add(point(1))
        val first = window.snapshot()
        window.add(point(2))
        window.add(point(3))
        assertEquals(listOf(point(1)), first)
        assertEquals(listOf(point(2), point(3)), window.snapshot())
    }

    private fun point(timestamp: Long) = SensorData(1f, -2f, 3f, timestamp)
}
