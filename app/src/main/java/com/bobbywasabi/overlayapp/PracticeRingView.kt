package com.bobbywasabi.overlayapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Full-window canvas: injected display-coordinate swipes arrive as ordinary touch events. */
class PracticeRingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scene = PracticeScene(SystemClock.uptimeMillis())
    private val colors = intArrayOf(0xff6df28a.toInt(), 0xffffd35a.toInt(), 0xfff84856.toInt())
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var downTime = 0L
    private var movementStart: Long? = null
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var animating = false
    var touches = 0
        private set
    var feedback: ((PracticeScene.Result?, Int, Int, Int, PracticeScene.Timing?) -> Unit)? = null

    fun toggleSmallPale() { scene.smallPale = !scene.smallPale; restart() }
    fun toggleMovement() { scene.moving = !scene.moving; restart() }
    fun nextColor() { scene.colorIndex = (scene.colorIndex + 1) % colors.size; restart() }
    /** Timing drill uses the standard vivid ring, so it turns small-pale off on enable. */
    fun toggleTimingDrill(): Boolean {
        scene.timingDrill = !scene.timingDrill
        if (scene.timingDrill) scene.smallPale = false
        scene.resetDrill()
        restart()
        return scene.timingDrill
    }
    fun toggleRingMode(): Boolean {
        scene.ringOnHold = !scene.ringOnHold
        restart()
        return scene.ringOnHold
    }
    fun resume() { animating = true; scene.restart(SystemClock.uptimeMillis()); invalidate() }
    fun pause() { animating = false; dragging = false }
    private fun restart() {
        dragging = false
        scene.restart(SystemClock.uptimeMillis())
        ThrowState.disarm()
        invalidate()
    }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val now = SystemClock.uptimeMillis()
        val g = scene.geometry(width, height, now)
        paint.style = Paint.Style.FILL
        canvas.drawColor(0xff404b53.toInt())
        paint.color = 0xff303c35.toInt()
        canvas.drawRect(0f, height * 0.65f, width.toFloat(), height.toFloat(), paint)
        // Neutral scenery cannot connect to the ring's colored component.
        paint.color = 0xff61686c.toInt()
        canvas.drawOval(width * 0.2f, height * 0.18f, width * 0.8f, height * 0.25f, paint)
        if (scene.ready(now)) {
            paint.color = 0xff85898d.toInt()
            canvas.drawOval(g.targetX - g.targetRadius * 0.85f, g.targetY - g.targetRadius,
                g.targetX + g.targetRadius * 0.85f, g.targetY + g.targetRadius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = max(2f, width * 0.003f)
            paint.color = 0xffc4c9cc.toInt()
            canvas.drawCircle(g.targetX, g.targetY, g.targetRadius, paint)
            if (scene.ringVisible(now, dragging)) {
                paint.strokeWidth = max(2f, width * if (scene.smallPale) 0.003f else 0.006f)
                paint.color = if (scene.smallPale) 0xff9ed395.toInt() else colors[scene.colorIndex]
                canvas.drawCircle(g.targetX, g.targetY, g.ringRadius, paint)
                if (scene.timingDrill) {
                    // Excellent-window guides so the eye can see the [min, max] release window.
                    val short = min(width, height).toFloat()
                    paint.strokeWidth = max(1f, width * 0.002f)
                    paint.color = 0x66ffffff
                    canvas.drawCircle(g.targetX, g.targetY, short * AutoThrowController.EXCELLENT_RADIUS_MAX, paint)
                    canvas.drawCircle(g.targetX, g.targetY, short * AutoThrowController.EXCELLENT_RADIUS_MIN, paint)
                }
            }
            paint.style = Paint.Style.FILL
            if (dragging) drawBall(canvas, fingerX, fingerY, g.ballRadius)
            else drawBall(canvas, g.ballX, g.ballY, g.ballRadius)
        } else {
            val release = scene.release
            if (release != null) {
                val progress = (now - release.timeMs).toFloat() / PracticeScene.FLIGHT_MS
                if (progress < 1f) drawBall(canvas, release.x * width, release.y * height,
                    g.ballRadius * (1f - progress * 0.7f))
            }
        }
        if (animating && isShown) postInvalidateOnAnimation()
    }

    private fun drawBall(canvas: Canvas, x: Float, y: Float, radius: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xfff0f0f0.toInt()
        canvas.drawCircle(x, y, radius, paint)
        paint.color = 0xffd95258.toInt()
        canvas.drawArc(x - radius, y - radius, x + radius, y + radius, 180f, 180f, true, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = radius * 0.12f
        paint.color = 0xff252b32.toInt()
        canvas.drawCircle(x, y, radius, paint)
        canvas.drawLine(x - radius, y, x + radius, y, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, y, radius * 0.24f, paint)
        paint.color = 0xfff0f0f0.toInt()
        canvas.drawCircle(x, y, radius * 0.15f, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width <= 0 || height <= 0) return false
        val now = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touches++
                downTime = now
                movementStart = null
                downX = event.x; downY = event.y
                fingerX = event.x; fingerY = event.y
                val g = scene.geometry(width, height, now)
                dragging = scene.ready(now) && hypot(downX - g.ballX, downY - g.ballY) <= g.ballRadius * 1.4f
                if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
                feedback?.invoke(null, touches, scene.attempts, scene.innerHits, scene.lastTiming)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                if (movementStart == null && hypot(event.x - downX, event.y - downY) > touchSlop) movementStart = now
                fingerX = event.x; fingerY = event.y; invalidate()
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                if (dragging) {
                    val result = scene.submit(width, height, downX, downY, event.x, event.y, downTime, now,
                        movementStart ?: downTime, if (movementStart != null) 33 else 0)
                    feedback?.invoke(result, touches, scene.attempts, scene.innerHits, scene.lastTiming)
                }
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
            }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
