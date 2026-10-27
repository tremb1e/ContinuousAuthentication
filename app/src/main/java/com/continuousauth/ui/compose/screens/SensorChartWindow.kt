package com.continuousauth.ui.compose.screens

import com.continuousauth.ui.viewmodels.SensorDataViewModel.SensorData

/** A bounded display window. Sensor storage and upload never use this buffer. */
internal class SensorChartWindow(private val capacity: Int = 120) {
    init { require(capacity > 0) }
    private val points = ArrayDeque<SensorData>(capacity)

    fun add(point: SensorData): Boolean {
        if (point.timestamp <= 0 || !point.x.isFinite() || !point.y.isFinite() || !point.z.isFinite()) return false
        val previous = points.lastOrNull()
        if (previous?.timestamp == point.timestamp) return false
        // Wall clock corrections must not make a curve run backwards across the screen.
        if (previous != null && point.timestamp < previous.timestamp) points.clear()
        if (points.size == capacity) points.removeFirst()
        points.addLast(point)
        return true
    }

    fun snapshot(): List<SensorData> = points.toList()
}
