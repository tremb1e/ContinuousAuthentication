package com.continuousauth.ui.chart

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.view.GestureDetectorCompat
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max
import kotlin.math.min

/**
 * 传感器数据图表视图
 * 支持XYZ三轴数据显示，交互式缩放和拖动
 * 实现从时刻0到当前时刻的动态缩放显示
 */
class SensorChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    data class DataPoint(
        val x: Float,
        val y: Float,
        val z: Float,
        val timestamp: Long
    )
    
    // 数据存储
    private val dataPoints = ConcurrentLinkedQueue<DataPoint>()
    private val maxDataPoints = 1000 // 最多保存1000个点
    private var startTime = 0L
    
    // 绘图对象
    private val xPaint = Paint().apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val yPaint = Paint().apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val zPaint = Paint().apply {
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val gridPaint = Paint().apply {
        color = Color.GRAY
        alpha = 50
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    
    private val textPaint = Paint().apply {
        color = Color.BLACK
        textSize = 24f
        isAntiAlias = true
    }
    
    private val axisPaint = Paint().apply {
        color = Color.BLACK
        strokeWidth = 2f
        style = Paint.Style.STROKE
    }
    
    // 交互控制
    private var scaleFactor = 1f
    private var translateX = 0f
    private var minValue = -10f
    private var maxValue = 10f
    private var isPaused = false
    private var isInteractive = false
    
    // 手势检测器
    private val scaleGestureDetector = ScaleGestureDetector(context, ScaleListener())
    private val gestureDetector = GestureDetectorCompat(context, GestureListener())
    
    // 路径对象
    private val xPath = Path()
    private val yPath = Path()
    private val zPath = Path()
    
    // 是否处于详细查看模式
    private var isDetailView = false
    
    // 统计信息
    private var accelerometerPointsCount = 0
    private var gyroscopePointsCount = 0
    private var magnetometerPointsCount = 0
    
    init {
        // 默认颜色
        setColorScheme(Color.RED, Color.GREEN, Color.BLUE)
    }
    
    /**
     * 设置颜色方案
     */
    fun setColorScheme(xColor: Int, yColor: Int, zColor: Int) {
        xPaint.color = xColor
        yPaint.color = yColor
        zPaint.color = zColor
    }
    
    /**
     * 添加数据点
     */
    fun addDataPoint(x: Float, y: Float, z: Float, timestamp: Long) {
        if (isPaused) return
        
        if (startTime == 0L) {
            startTime = timestamp
        }
        
        dataPoints.add(DataPoint(x, y, z, timestamp))
        
        // 限制数据点数量
        while (dataPoints.size > maxDataPoints) {
            dataPoints.poll()
        }
        
        // 动态调整显示范围
        val allValues = dataPoints.flatMap { listOf(it.x, it.y, it.z) }
        if (allValues.isNotEmpty()) {
            minValue = allValues.minOrNull() ?: -10f
            maxValue = allValues.maxOrNull() ?: 10f
            
            // 添加一些边距
            val range = maxValue - minValue
            minValue -= range * 0.1f
            maxValue += range * 0.1f
        }
        
        postInvalidate()
    }
    
    /**
     * 启用交互模式
     */
    fun enableInteractiveMode() {
        isInteractive = true
        setOnClickListener {
            // 点击切换详细视图模式
            isDetailView = !isDetailView
            invalidate()
        }
    }
    
    /**
     * 暂停更新
     */
    fun pauseUpdates() {
        isPaused = true
    }
    
    /**
     * 恢复更新
     */
    fun resumeUpdates() {
        isPaused = false
    }
    
    /**
     * 清理资源
     */
    fun cleanup() {
        dataPoints.clear()
        startTime = 0L
        accelerometerPointsCount = 0
        gyroscopePointsCount = 0
        magnetometerPointsCount = 0
    }
    
    /**
     * 清除数据（clearData 是 cleanup 的别名）
     */
    fun clearData() {
        cleanup()
    }
    
    /**
     * 添加加速度计数据
     */
    fun addAccelerometerData(x: Float, y: Float, z: Float) {
        addDataPoint(x, y, z, System.nanoTime())
        accelerometerPointsCount++
    }
    
    /**
     * 添加陀螺仪数据
     */
    fun addGyroscopeData(x: Float, y: Float, z: Float) {
        addDataPoint(x, y, z, System.nanoTime())
        gyroscopePointsCount++
    }
    
    /**
     * 添加磁力计数据
     */
    fun addMagnetometerData(x: Float, y: Float, z: Float) {
        addDataPoint(x, y, z, System.nanoTime())
        magnetometerPointsCount++
    }
    
    /**
     * 获取数据统计信息
     */
    fun getDataStats(): ChartStats {
        val pointsList = dataPoints.toList()
        val timeRangeSeconds = if (pointsList.size > 1) {
            (pointsList.last().timestamp - pointsList.first().timestamp) / 1_000_000_000f
        } else {
            0f
        }
        
        return ChartStats(
            totalDataPoints = dataPoints.size,
            accelerometerPoints = accelerometerPointsCount,
            gyroscopePoints = gyroscopePointsCount,
            magnetometerPoints = magnetometerPointsCount,
            minValue = minValue,
            maxValue = maxValue,
            timeRangeSeconds = timeRangeSeconds
        )
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        val padding = 60f
        val chartWidth = width - padding * 2
        val chartHeight = height - padding * 2
        
        // 绘制背景
        canvas.drawColor(Color.WHITE)
        
        // 绘制网格
        drawGrid(canvas, padding, padding, chartWidth, chartHeight)
        
        // 绘制坐标轴
        drawAxes(canvas, padding, padding, chartWidth, chartHeight)
        
        // 绘制数据
        if (isDetailView) {
            drawDataDetailed(canvas, padding, padding, chartWidth, chartHeight)
        } else {
            drawDataCompressed(canvas, padding, padding, chartWidth, chartHeight)
        }
        
        // 绘制图例
        drawLegend(canvas, padding)
        
        // 绘制时间轴标签
        drawTimeLabels(canvas, padding, padding + chartHeight, chartWidth)
    }
    
    private fun drawGrid(canvas: Canvas, startX: Float, startY: Float, width: Float, height: Float) {
        // 水平线
        for (i in 0..10) {
            val y = startY + height * i / 10f
            canvas.drawLine(startX, y, startX + width, y, gridPaint)
        }
        
        // 垂直线
        for (i in 0..10) {
            val x = startX + width * i / 10f
            canvas.drawLine(x, startY, x, startY + height, gridPaint)
        }
    }
    
    private fun drawAxes(canvas: Canvas, startX: Float, startY: Float, width: Float, height: Float) {
        // Y轴
        canvas.drawLine(startX, startY, startX, startY + height, axisPaint)
        
        // X轴
        canvas.drawLine(startX, startY + height, startX + width, startY + height, axisPaint)
        
        // Y轴标签
        for (i in 0..5) {
            val y = startY + height * i / 5f
            val value = maxValue - (maxValue - minValue) * i / 5f
            textPaint.textAlign = Paint.Align.RIGHT
            canvas.drawText("%.1f".format(value), startX - 10f, y + 5f, textPaint)
        }
    }
    
    private fun drawDataCompressed(canvas: Canvas, startX: Float, startY: Float, width: Float, height: Float) {
        if (dataPoints.isEmpty()) return
        
        // 清除路径
        xPath.reset()
        yPath.reset()
        zPath.reset()
        
        val pointsList = dataPoints.toList()
        if (pointsList.isEmpty()) return
        
        val firstTime = pointsList.first().timestamp
        val lastTime = pointsList.last().timestamp
        val timeRange = lastTime - firstTime
        
        // 如果时间范围太小，使用最小范围
        val displayRange = max(timeRange, 1000L)
        
        var isFirst = true
        pointsList.forEach { point ->
            // 计算从0时刻开始的位置，动态压缩到当前视图宽度
            val timeDiff = point.timestamp - firstTime
            val x = startX + (timeDiff.toFloat() / displayRange) * width
            
            // X轴数据
            val yX = startY + height - ((point.x - minValue) / (maxValue - minValue)) * height
            if (isFirst) {
                xPath.moveTo(x, yX)
            } else {
                xPath.lineTo(x, yX)
            }
            
            // Y轴数据
            val yY = startY + height - ((point.y - minValue) / (maxValue - minValue)) * height
            if (isFirst) {
                yPath.moveTo(x, yY)
            } else {
                yPath.lineTo(x, yY)
            }
            
            // Z轴数据
            val yZ = startY + height - ((point.z - minValue) / (maxValue - minValue)) * height
            if (isFirst) {
                zPath.moveTo(x, yZ)
                isFirst = false
            } else {
                zPath.lineTo(x, yZ)
            }
        }
        
        // 绘制路径
        canvas.drawPath(xPath, xPaint)
        canvas.drawPath(yPath, yPaint)
        canvas.drawPath(zPath, zPaint)
    }
    
    private fun drawDataDetailed(canvas: Canvas, startX: Float, startY: Float, width: Float, height: Float) {
        if (dataPoints.isEmpty()) return
        
        // 在详细视图模式下，支持缩放和拖动
        canvas.save()
        canvas.clipRect(startX, startY, startX + width, startY + height)
        
        // 应用缩放和平移
        canvas.translate(translateX, 0f)
        canvas.scale(scaleFactor, 1f, startX + width / 2, startY + height / 2)
        
        drawDataCompressed(canvas, startX, startY, width, height)
        
        canvas.restore()
    }
    
    private fun drawLegend(canvas: Canvas, startY: Float) {
        val legendY = startY - 20f
        val spacing = 120f
        
        // X轴标签
        textPaint.color = xPaint.color
        textPaint.textAlign = Paint.Align.LEFT
        canvas.drawText("X", 60f, legendY, textPaint)
        canvas.drawLine(80f, legendY - 5f, 120f, legendY - 5f, xPaint)
        
        // Y轴标签
        textPaint.color = yPaint.color
        canvas.drawText("Y", 60f + spacing, legendY, textPaint)
        canvas.drawLine(80f + spacing, legendY - 5f, 120f + spacing, legendY - 5f, yPaint)
        
        // Z轴标签
        textPaint.color = zPaint.color
        canvas.drawText("Z", 60f + spacing * 2, legendY, textPaint)
        canvas.drawLine(80f + spacing * 2, legendY - 5f, 120f + spacing * 2, legendY - 5f, zPaint)
        
        // 显示模式和缩放级别
        textPaint.color = Color.BLACK
        if (isDetailView) {
            canvas.drawText("详细模式 (缩放: %.1fx)".format(scaleFactor), width - 250f, legendY, textPaint)
        } else {
            canvas.drawText("全览模式", width - 150f, legendY, textPaint)
        }
    }
    
    private fun drawTimeLabels(canvas: Canvas, startX: Float, y: Float, width: Float) {
        if (dataPoints.isEmpty()) return
        
        val pointsList = dataPoints.toList()
        val firstTime = pointsList.first().timestamp
        val lastTime = pointsList.last().timestamp
        val timeRange = (lastTime - firstTime) / 1000.0 // 转换为秒
        
        textPaint.color = Color.BLACK
        textPaint.textAlign = Paint.Align.CENTER
        
        // 绘制时间标签
        canvas.drawText("0s", startX, y + 25f, textPaint)
        canvas.drawText("%.1fs".format(timeRange), startX + width, y + 25f, textPaint)
        
        // 中间时间点
        for (i in 1..3) {
            val x = startX + width * i / 4f
            val time = timeRange * i / 4f
            canvas.drawText("%.1fs".format(time), x, y + 25f, textPaint)
        }
    }
    
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isInteractive || !isDetailView) return super.onTouchEvent(event)
        
        scaleGestureDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        
        return true
    }
    
    // 缩放监听器
    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scaleFactor *= detector.scaleFactor
            scaleFactor = max(0.5f, min(scaleFactor, 5f))
            invalidate()
            return true
        }
    }
    
    // 手势监听器
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            translateX -= distanceX / scaleFactor
            val maxTranslate = width * (scaleFactor - 1) / 2
            translateX = max(-maxTranslate, min(translateX, maxTranslate))
            invalidate()
            return true
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            // 双击重置缩放和位置
            scaleFactor = 1f
            translateX = 0f
            invalidate()
            return true
        }
    }
}