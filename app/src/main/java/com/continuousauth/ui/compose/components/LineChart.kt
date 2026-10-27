package com.continuousauth.ui.compose.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Geometry is cached across scrolling; empty, single-point and flat series remain drawable. */
@Composable
fun LineChart(
    data: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary,
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    gridLineColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f),
    maxValue: Float? = null,
    minValue: Float = 0f,
    showGrid: Boolean = true,
    strokeWidth: Float = 2f
) {
    val values = remember(data) { data.filter(Float::isFinite) }
    Box(modifier.fillMaxSize().background(backgroundColor).drawWithCache {
        val low = minValue.takeIf(Float::isFinite)?.toDouble() ?: 0.0
        val high = (maxValue?.takeIf(Float::isFinite) ?: values.maxOrNull() ?: 1f).toDouble()
        val span = (high - low).coerceAtLeast(0.0001)
        val points = values.mapIndexed { index, value ->
            val x = if (values.size == 1) size.width / 2f else size.width * index / (values.size - 1)
            val normalized = ((value.toDouble() - low) / span).coerceIn(0.0, 1.0).toFloat()
            Offset(x, size.height * (1f - normalized))
        }
        val path = Path().apply {
            points.forEachIndexed { index, point ->
                if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
            }
        }
        val stroke = Stroke(width = strokeWidth.coerceAtLeast(0.5f).dp.toPx(), cap = StrokeCap.Round)
        val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
        val dots = points.takeLast(20)
        onDrawBehind {
            if (showGrid) repeat(5) { index ->
                val y = size.height * index / 4
                drawLine(gridLineColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
            }
            drawPath(path, lineColor, style = stroke)
            dots.forEach { point -> drawCircle(lineColor, 2.dp.toPx(), point) }
        }
    })
}
