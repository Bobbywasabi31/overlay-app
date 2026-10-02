package com.bobbywasabi.overlayapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

class PracticeRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val colors = intArrayOf(0xff6df28a.toInt(), 0xffffd35a.toInt(), 0xfff84856.toInt())
    private var colorIndex = 0
    private var smallPale = false
    fun toggleSmallPale() { smallPale = !smallPale; invalidate() }
    fun nextColor() {
        colorIndex = (colorIndex + 1) % colors.size
        invalidate()
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val time = SystemClock.uptimeMillis() / 1600.0
        val shortSide = min(width, height).toFloat()
        val x = width * (0.5f + sin(time).toFloat() * 0.1f)
        val radius = if (smallPale) shortSide * (0.028f + sin(time * 0.7).toFloat() * 0.006f)
            else shortSide * (0.20f + sin(time * 0.7).toFloat() * 0.055f)
        paint.color = if (smallPale) 0xff9ed395.toInt() else colors[colorIndex]
        paint.strokeWidth = if (smallPale) maxOf(2f, shortSide * 0.003f) else maxOf(3f, shortSide * 0.013f)
        canvas.drawCircle(x, height * 0.5f, radius, paint)
        if (isShown) postInvalidateDelayed(33)
    }
}
