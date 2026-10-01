package com.bobbywasabi.overlayapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.util.TypedValue
import android.view.View
import kotlin.math.min

/** Drawing only; WindowManager explicitly disables touches and focus. */
class GuidanceView @JvmOverloads constructor(
    context: Context,
    private val screenWidth: Int = context.resources.displayMetrics.widthPixels,
    private val screenHeight: Int = context.resources.displayMetrics.heightPixels,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff64d8eb.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xfff3f6fc.toInt()
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
    }
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xee10141d.toInt() }
    private val dash = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
    private val location = IntArray(2)
    private var ring: ScreenAnalyzer.Ring? = null
    fun showRing(value: ScreenAnalyzer.Ring?) {
        ring = value
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val target = ring
        val label: String
        val labelY: Float
        if (target == null) {
            label = context.getString(R.string.overlay_searching)
            labelY = 52f * density
        } else {
            getLocationOnScreen(location)
            val x = target.x * screenWidth - location[0]
            val y = target.y * screenHeight - location[1]
            val radius = target.radius * min(screenWidth, screenHeight)
            // Draw outside the source ring to avoid erasing its color during capture.
            line.pathEffect = dash
            canvas.drawCircle(x, y, radius + 9f * density, line)
            line.pathEffect = null
            val arm = min(8f * density, radius * 0.4f)
            canvas.drawLine(x - arm, y, x + arm, y, line)
            canvas.drawLine(x, y - arm, x, y + arm, line)
            label = context.getString(R.string.overlay_found)
            labelY = (y - radius - 28f * density).coerceAtLeast(52f * density)
        }
        val textWidth = labelPaint.measureText(label)
        val labelX = (width - textWidth) / 2f
        canvas.drawRoundRect(labelX - 12f * density, labelY - 19f * density,
            labelX + textWidth + 12f * density, labelY + 9f * density, 10f * density, 10f * density, background)
        canvas.drawText(label, labelX, labelY, labelPaint)
    }
}
