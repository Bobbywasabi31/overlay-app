package com.bobbywasabi.overlayapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Temporary touch interception, only while the user is selecting the ball origin. */
class BallCalibrationView(context: Context, private val selected: (Float, Float) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff64d8eb.toInt()
        textSize = 18f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    init { contentDescription = context.getString(R.string.calibrate_prompt); isClickable = true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0x2210141d)
        canvas.drawText(context.getString(R.string.calibrate_prompt), width / 2f, height * 0.32f, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            performClick()
            selected(event.rawX, event.rawY)
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
