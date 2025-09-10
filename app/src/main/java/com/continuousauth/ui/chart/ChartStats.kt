package com.continuousauth.ui.chart

/**
 * 图表统计信息数据类
 * 用于传递图表的统计数据
 */
data class ChartStats(
    val totalDataPoints: Int,        // 总数据点数
    val accelerometerPoints: Int,    // 加速度计数据点数
    val gyroscopePoints: Int,        // 陀螺仪数据点数
    val magnetometerPoints: Int,     // 磁力计数据点数
    val minValue: Float,              // 最小值
    val maxValue: Float,              // 最大值
    val timeRangeSeconds: Float       // 时间范围（秒）
)