package com.bobbywasabi.overlayapp

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.lifecycle.Observer
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class OverlayService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var workerThread: HandlerThread
    private lateinit var worker: Handler
    private lateinit var windowManager: WindowManager
    private lateinit var displayManager: DisplayManager
    private lateinit var overlayContext: Context
    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var guidance: GuidanceView? = null
    private var controls: LinearLayout? = null
    private var autoButton: Button? = null
    private var debugButton: Button? = null
    private var dryRunButton: Button? = null
    private var dryRunHolding = false
    private var accessibilityDisclosureShown = false
    private var debugHud: android.widget.TextView? = null
    private var debugEnabled = false
    private var calibration: BallCalibrationView? = null
    private var initialDisplay: DisplaySize? = null
    private var displayListenerRegistered = false
    private var screenOffReceiverRegistered = false
    /** #34: explicit session lifecycle. All transitions funnel through [setPhase]. */
    internal enum class CapturePhase { IDLE, STARTING, RUNNING, STOPPING }
    @Volatile private var capturePhase = CapturePhase.IDLE
    private val active: Boolean get() = capturePhase == CapturePhase.RUNNING
    private fun setPhase(next: CapturePhase) {
        val prev = capturePhase
        val legal = when (prev) {
            CapturePhase.IDLE -> next == CapturePhase.STARTING || next == CapturePhase.STOPPING
            CapturePhase.STARTING -> next == CapturePhase.RUNNING || next == CapturePhase.STOPPING
            CapturePhase.RUNNING -> next == CapturePhase.STOPPING
            CapturePhase.STOPPING -> next == CapturePhase.IDLE
        }
        if (!legal) Log.w(TAG, "Unexpected session transition $prev -> $next")
        capturePhase = next
    }
    internal fun setPhaseForTest(next: CapturePhase) { capturePhase = next }
    private var lastResultTime = 0L
    private var lastActivityTime = 0L
    private var droppedFrames = 0
    private var staleFrames = 0
    private var terminating = false
    private var terminalStatus: SessionState.Status? = null
    private val throwObserver = Observer<Int> { renderThrowControls() }
    private val calibrationTimeout = Runnable { removeCalibration() }
    private data class DisplaySize(val width: Int, val height: Int, val rotation: Int)

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) endSession(R.string.message_system_stopped)
        }
    }
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { endSession(R.string.message_system_stopped) }
        override fun onCapturedContentResize(width: Int, height: Int) {
            val original = initialDisplay ?: return
            if (width != original.width || height != original.height) {
                endSession(R.string.message_full_display)
            }
        }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) endSession(R.string.message_display_changed)
        }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && active && displaySize() != initialDisplay) {
                endSession(R.string.message_display_changed)
            }
        }
    }
    private val watchdog = object : Runnable {
        override fun run() {
            if (!active) return
            if (SystemClock.elapsedRealtime() - lastActivityTime > IDLE_STOP_MS) {
                endSession(R.string.message_idle_stopped)
                return
            }
            if (!Settings.canDrawOverlays(this@OverlayService)) {
                endSession(R.string.message_overlay_required)
                return
            }
            if (!CaptureTiming.isFresh(lastResultTime, SystemClock.elapsedRealtime())) {
                guidance?.showRing(null)
                ThrowState.controller.resetTracking()
                if (GestureThrowService.current?.holding == true) ThrowState.disarm()
            }
            if (ThrowState.controller.armed && GestureThrowService.current?.targetWindow()?.target != ThrowState.target) ThrowState.disarm()
            if (calibration != null && GestureThrowService.current?.targetIsForeground != true) removeCalibration()
            mainHandler.postDelayed(this, 300L)
        }
    }
    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(DisplayManager::class.java)
        overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            createDisplayContext(displayManager.getDisplay(Display.DEFAULT_DISPLAY))
                .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        windowManager = overlayContext.getSystemService(WindowManager::class.java)
        workerThread = HandlerThread("ring-analysis").apply { start() }
        worker = Handler(workerThread.looper)
        ThrowState.changes.observeForever(throwObserver)
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (terminating) return START_NOT_STICKY
        if (intent?.action == ACTION_STOP) {
            endSession(R.string.message_stopped)
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_RESULT_DATA, Intent::class.java) }
        if (intent?.action != ACTION_START || resultCode != Activity.RESULT_OK || resultData == null || !Settings.canDrawOverlays(this)) {
            endSession(R.string.message_capture_error, error = true)
            return START_NOT_STICKY
        }
        try {
            val notification = buildNotification()
            // Consent first, foreground service second, MediaProjection last.
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else startForeground(NOTIFICATION_ID, notification)
            val mediaProjection = getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, resultData)
            projection = mediaProjection
            mediaProjection.registerCallback(projectionCallback, mainHandler)
            setPhase(CapturePhase.STARTING)
            startCapture(mediaProjection)
            SessionState.update(SessionState.Phase.RUNNING, R.string.message_running)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to start screen analysis", error)
            endSession(R.string.message_capture_error, error = true)
        }
        // Consent is single-use; never restart capture silently after process death.
        return START_NOT_STICKY
    }
    private fun startCapture(mediaProjection: MediaProjection) {
        ThrowState.resetSession()
        dryRunHolding = false
        lastActivityTime = SystemClock.elapsedRealtime()
        droppedFrames = 0
        staleFrames = 0
        val size = displaySize()
        initialDisplay = size
        val scale = min(1f, 960f / max(size.width, size.height))
        val width = (size.width * scale).roundToInt().coerceAtLeast(1)
        val height = (size.height * scale).roundToInt().coerceAtLeast(1)
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader
        val analyzer = ScreenAnalyzer()
        val tracker = RingTracker()
        val pixels = IntArray(width * height)
        var lastAnalysis = 0L
        reader.setOnImageAvailableListener({ source ->
            if (!active) return@setOnImageAvailableListener
            try {
                val image = source.acquireLatestImage() ?: run {
                    droppedFrames++
                    return@setOnImageAvailableListener
                }
                image.use {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalysis < CaptureTiming.ANALYSIS_INTERVAL_MS) return@use
                    lastAnalysis = now
                    val plane = it.planes[0]
                    RgbaFrame.copy(plane.buffer, it.width, it.height, plane.rowStride, plane.pixelStride, pixels)
                    val ring = tracker.update(analyzer.analyze(pixels, it.width, it.height), now)
                    mainHandler.post {
                        if (active) {
                            val delivered = SystemClock.elapsedRealtime()
                            if (!CaptureTiming.isFresh(now, delivered)) {
                                staleFrames++
                                guidance?.showRing(null)
                                ThrowState.controller.resetTracking()
                                return@post
                            }
                            lastResultTime = now
                            if (ring != null) lastActivityTime = delivered
                            // Display uses the held target so one dropped frame does not
                            // flicker the marker; throws still use the strict result.
                            guidance?.showRing(tracker.displayTarget(delivered))
                            updateDebugHud(analyzer.lastStats)
                            maybeThrow(ring, now)
                        }
                    }
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to analyze screen frame", error)
                mainHandler.post { if (active) endSession(R.string.message_frame_error, error = true) }
            }
        }, worker)
        guidance = GuidanceView(overlayContext, size.width, size.height).also { view ->
            view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            windowManager.addView(view, overlayParameters())
        }
        createThrowControls()
        setPhase(CapturePhase.RUNNING)
        virtualDisplay = mediaProjection.createVirtualDisplay(
            "ThrowAssistantCapture", width, height, resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, worker,
        )
        displayManager.registerDisplayListener(displayListener, mainHandler)
        displayListenerRegistered = true
        ContextCompat.registerReceiver(this, screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOffReceiverRegistered = true
        mainHandler.post(watchdog)
    }

    private fun maybeThrow(ring: ScreenAnalyzer.Ring?, frameTime: Long) {
        if (ThrowState.killed) return
        val controller = ThrowState.controller
        if (!controller.armed || calibration != null) return
        if (!CaptureTiming.isFreshForThrow(frameTime, SystemClock.elapsedRealtime())) {
            controller.resetTracking()
            return
        }
        val service = GestureThrowService.current ?: run { ThrowState.disarm(); return }
        val size = initialDisplay ?: return
        val ball = ThrowState.ball
        val window = service.targetWindow()
        val bounds = window?.bounds
        // Split-screen and unknown foregrounds cannot use a full-display calibrated swipe.
        if (ball == null || window == null || bounds == null || window.target != ThrowState.target ||
            bounds.width() < size.width * 0.9 || bounds.height() < size.height * 0.85) {
            ThrowState.disarm()
            return
        }
        // #8: if the window moved or resized since calibration, the calibrated
        // ball position is stale; ask for recalibration instead of throwing blind.
        val calibratedBounds = ThrowState.calibrationBounds
        if (calibratedBounds != null && windowBoundsChanged(calibratedBounds, bounds)) {
            Toast.makeText(this, R.string.auto_recalibrate, Toast.LENGTH_LONG).show()
            ThrowState.disarm()
            return
        }
        val ready = controller.consider(ring, frameTime)
        if (ThrowState.dryRun) {
            dryRunStep(ball, ring, ready, frameTime, size)
            return
        }
        if (!service.holding && controller.canBeginHold(frameTime)) {
            controller.resetTracking()
            try {
                if (!service.holdBall(ball, size.width, size.height, frameTime)) ThrowState.disarm()
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to hold ball", error)
                ThrowState.disarm()
            }
            return
        }
        if (!service.canContinueHold || !ready || ring == null) return
        val planned = ThrowPlanner.plan(ring, ball, size.width, size.height, ThrowState.durationMs) ?: return
        // #7: never let a swipe leave the game window.
        val swipe = planned.copy(
            startX = planned.startX.coerceIn(bounds.left.toFloat(), bounds.right.toFloat()),
            startY = planned.startY.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat()),
            endX = planned.endX.coerceIn(bounds.left.toFloat(), bounds.right.toFloat()),
            endY = planned.endY.coerceIn(bounds.top.toFloat(), bounds.bottom.toFloat()),
        )
        lastActivityTime = SystemClock.elapsedRealtime()
        controller.beginThrow(frameTime)
        // #9: the ring must still be fresh at dispatch time, not just at plan time.
        if (!CaptureTiming.isFreshForThrow(frameTime, SystemClock.elapsedRealtime())) {
            Log.w(TAG, "Throw aborted: ring went stale before dispatch")
            controller.finishThrow(false)
            ThrowState.changed()
            return
        }
        try {
            val dispatched = service.throwBall(swipe, frameTime) { success ->
                controller.finishThrow(success)
                ThrowState.changed()
            }
            if (!dispatched) { controller.finishThrow(false); ThrowState.changed() }
            else ThrowState.changed()
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to dispatch throw gesture", error)
            controller.finishThrow(false)
            ThrowState.changed()
        }
    }

    /** #8: the calibration is stale if the window moved or resized materially. */
    private fun windowBoundsChanged(old: Rect, new: Rect): Boolean {
        val scale = old.width().coerceAtLeast(1).toFloat()
        return abs(new.width() - old.width()) > scale * 0.05f ||
            abs(new.height() - old.height()) > scale * 0.05f ||
            abs(new.left - old.left) > scale * 0.05f ||
            abs(new.top - old.top) > scale * 0.05f
    }

    private fun createThrowControls() {
        val row = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        controls = row
        fun button(label: Int, action: () -> Unit): Button = Button(overlayContext).apply {
            setText(label)
            isAllCaps = false
            minHeight = (48 * resources.displayMetrics.density).roundToInt()
            setOnClickListener { action() }
            row.addView(this)
        }
        button(R.string.calibrate_ball) { startCalibration() }
        autoButton = button(R.string.auto_off) { toggleAuto() }
        debugButton = button(R.string.debug_off) { toggleDebug() }
        dryRunButton = button(R.string.dry_run_off) { toggleDryRun() }
        button(R.string.kill_switch) {
            ThrowState.kill()
            Toast.makeText(this, R.string.kill_engaged, Toast.LENGTH_LONG).show()
            endSession(R.string.kill_engaged)
        }
        button(R.string.stop) { endSession(R.string.message_stopped) }
        val params = overlayParameters().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            x = (8 * resources.displayMetrics.density).roundToInt()
            y = (72 * resources.displayMetrics.density).roundToInt()
        }
        windowManager.addView(row, params)
        renderThrowControls()
    }

    private fun toggleDebug() {
        debugEnabled = !debugEnabled
        debugButton?.setText(if (debugEnabled) R.string.debug_on else R.string.debug_off)
        if (debugEnabled) {
            if (debugHud == null) {
                val hud = android.widget.TextView(overlayContext).apply {
                    setTextColor(android.graphics.Color.GREEN)
                    textSize = 11f
                    typeface = android.graphics.Typeface.MONOSPACE
                    setBackgroundColor(0xaa10141d.toInt())
                    setPadding(12, 12, 12, 12)
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                debugHud = hud
                val params = overlayParameters().apply {
                    width = WindowManager.LayoutParams.WRAP_CONTENT
                    height = WindowManager.LayoutParams.WRAP_CONTENT
                    gravity = android.view.Gravity.TOP or android.view.Gravity.START
                }
                windowManager.addView(hud, params)
            }
            debugHud?.visibility = View.VISIBLE
        } else {
            debugHud?.visibility = View.GONE
        }
    }

    private fun updateDebugHud(stats: ScreenAnalyzer.Stats) {
        val hud = debugHud ?: return
        if (!debugEnabled) return
        hud.text = DebugHud.format(stats, droppedFrames = droppedFrames, staleFrames = staleFrames)
        hud.setTextColor(if (DebugHud.isOverBudget(stats)) android.graphics.Color.RED else android.graphics.Color.GREEN)
    }

    private fun toggleDryRun() {
        ThrowState.setDryRun(!ThrowState.dryRun)
        dryRunButton?.setText(if (ThrowState.dryRun) R.string.dry_run_on else R.string.dry_run_off)
        Toast.makeText(this,
            if (ThrowState.dryRun) R.string.dry_run_on else R.string.dry_run_off,
            Toast.LENGTH_SHORT).show()
    }

    /**
     * Item 90: run the Auto decision pipeline without dispatching any gesture.
     * Logs what would have happened and walks the controller through the same
     * hold -> stabilize -> throw states as the live path.
     */
    private fun dryRunStep(
        ball: ThrowPlanner.Ball,
        ring: ScreenAnalyzer.Ring?,
        ready: Boolean,
        frameTime: Long,
        size: DisplaySize,
    ) {
        val controller = ThrowState.controller
        if (!controller.armed) {
            dryRunHolding = false
            return
        }
        if (!dryRunHolding && controller.canBeginHold(frameTime)) {
            Log.i(TAG, getString(R.string.dry_run_would_hold, ball.x, ball.y))
            lastActivityTime = SystemClock.elapsedRealtime()
            dryRunHolding = true
            controller.resetTracking()
            ThrowState.changed()
            return
        }
        if (!dryRunHolding || !ready || ring == null) return
        val swipe = ThrowPlanner.plan(ring, ball, size.width, size.height, ThrowState.durationMs) ?: return
        Log.i(TAG, getString(R.string.dry_run_would_throw,
            swipe.endX / size.width, swipe.endY / size.height, swipe.durationMs))
        controller.beginThrow(frameTime)
        controller.finishThrow(true)
        dryRunHolding = false
        ThrowState.changed()
    }

    private fun renderThrowControls() {
        autoButton?.text = if (ThrowState.controller.armed)
            getString(if (GestureThrowService.current?.holding == true) R.string.auto_holding else R.string.auto_on,
                ThrowState.controller.throws, AutoThrowController.MAX_THROWS)
        else getString(R.string.auto_off)
    }

    private fun toggleAuto() {
        if (ThrowState.controller.armed) { ThrowState.disarm(); return }
        if (ThrowState.killed) {
            Toast.makeText(this, R.string.kill_blocked, Toast.LENGTH_LONG).show()
            return
        }
        if (!TosAck.isAcknowledged(this)) {
            // Bring the app forward so the ban-risk notice can be acknowledged.
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(TosAck.EXTRA_SHOW_TOS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Toast.makeText(this, R.string.tos_required, Toast.LENGTH_LONG).show()
            return
        }
        val service = GestureThrowService.current
        val size = initialDisplay
        if (!active || size == null) {
            Toast.makeText(this, R.string.auto_start_capture, Toast.LENGTH_LONG).show()
            return
        }
        // #77: disclose why the accessibility service exists before the system prompt.
        if (service == null && !accessibilityDisclosureShown) {
            accessibilityDisclosureShown = true
            startActivity(Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_SHOW_ACCESSIBILITY, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        val window = service?.targetWindow()
        val reason = when {
            service == null -> GesturePermission.unavailable(this)
            Build.VERSION.SDK_INT < 26 -> R.string.auto_hold_unsupported
            calibration != null -> R.string.auto_finish_calibration
            size.height <= size.width -> R.string.auto_portrait
            window == null -> R.string.auto_open_target
            window.bounds.width() < size.width * 0.9 || window.bounds.height() < size.height * 0.85 -> R.string.auto_fullscreen
            ThrowState.ball == null || ThrowState.target != window.target -> R.string.auto_set_ball
            ThrowState.controller.busy || service.busy -> R.string.auto_wait_gesture
            else -> null
        }
        if (reason != null) Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
        else { lastActivityTime = SystemClock.elapsedRealtime(); ThrowState.controller.arm(); ThrowState.changed() }
    }

    private fun startCalibration() {
        if (!active || calibration != null) return
        ThrowState.disarm()
        val service = GestureThrowService.current
        if (service == null) {
            Toast.makeText(this, GesturePermission.unavailable(this), Toast.LENGTH_LONG).show()
            return
        }
        if (service.busy) {
            Toast.makeText(this, R.string.auto_wait_gesture, Toast.LENGTH_LONG).show()
            return
        }
        val destination = service.targetWindow()?.target
        if (destination == null) {
            Toast.makeText(this, R.string.auto_open_target, Toast.LENGTH_LONG).show()
            return
        }
        val size = initialDisplay ?: return
        val view = BallCalibrationView(overlayContext) { x, y ->
            val ball = ThrowPlanner.Ball(x / size.width, y / size.height)
            val targetWindow = GestureThrowService.current?.targetWindow()
            if (ThrowPlanner.validBall(ball) && targetWindow?.target == destination) {
                lastActivityTime = SystemClock.elapsedRealtime()
                ThrowState.calibrate(ball, destination, targetWindow.bounds)
                removeCalibration()
                Toast.makeText(this, R.string.calibrated_ball, Toast.LENGTH_SHORT).show()
            } else Toast.makeText(this, R.string.calibrate_invalid, Toast.LENGTH_SHORT).show()
        }
        calibration = view
        try {
            windowManager.addView(view, overlayParameters().apply {
                flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            })
            mainHandler.postDelayed(calibrationTimeout, 15_000L)
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unable to show calibration", error)
            endSession(R.string.message_overlay_required, error = true)
        }
    }

    private fun removeCalibration() {
        mainHandler.removeCallbacks(calibrationTimeout)
        val view = calibration
        calibration = null
        view?.let { if (it.isAttachedToWindow) windowManager.removeViewImmediate(it) }
    }
    @Suppress("DEPRECATION")
    private fun displaySize(): DisplaySize {
        val display = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
        val size = if (Build.VERSION.SDK_INT >= 30) {
            windowManager.maximumWindowMetrics.bounds.let { Point(it.width(), it.height()) }
        } else Point().also { display.getRealSize(it) }
        return DisplaySize(size.x, size.y, display.rotation)
    }
    @Suppress("DEPRECATION")
    @SuppressLint("RtlHardcoded") // Screen-capture pixels use a physical left origin in every locale.
    private fun overlayParameters() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT // Physical capture coordinates, independent of RTL.
        // Android 12 can block touches under opaque application overlays, even non-touchable ones.
        alpha = if (Build.VERSION.SDK_INT >= 31) {
            min(0.75f, getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
        } else 0.75f
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
    }
    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setContentIntent(open)
            .addAction(R.drawable.ic_notification, getString(R.string.stop), stop)
            .setOngoing(true).setSilent(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
    }
    private fun endSession(@StringRes message: Int, error: Boolean = false) {
        if (terminating) return
        terminating = true
        setPhase(CapturePhase.STOPPING)
        ThrowState.resetSession()
        guidance?.showRing(null)
        terminalStatus = SessionState.Status(if (error) SessionState.Phase.ERROR else SessionState.Phase.IDLE, message)
        SessionState.update(SessionState.Phase.STOPPING, R.string.status_stopping)
        stopSelf()
    }
    override fun onDestroy() {
        terminating = true
        capturePhase = CapturePhase.IDLE
        ThrowState.changes.removeObserver(throwObserver)
        ThrowState.resetSession()
        mainHandler.removeCallbacksAndMessages(null)
        if (displayListenerRegistered) cleanup { displayManager.unregisterDisplayListener(displayListener) }
        if (screenOffReceiverRegistered) cleanup { unregisterReceiver(screenOffReceiver) }
        cleanup { virtualDisplay?.release() }
        virtualDisplay = null
        cleanup { projection?.unregisterCallback(projectionCallback) }
        cleanup { projection?.stop() }
        projection = null
        cleanup { removeCalibration() }
        controls?.let { view -> cleanup { if (view.isAttachedToWindow) windowManager.removeViewImmediate(view) } }
        controls = null
        autoButton = null
        guidance?.let { view -> cleanup { if (view.isAttachedToWindow) windowManager.removeViewImmediate(view) } }
        guidance = null
        debugHud?.let { view -> cleanup { if (view.isAttachedToWindow) windowManager.removeViewImmediate(view) } }
        debugHud = null
        debugButton = null
        debugEnabled = false
        dryRunButton = null
        dryRunHolding = false
        val reader = imageReader
        imageReader = null
        cleanup { reader?.setOnImageAvailableListener(null, null) }
        worker.removeCallbacksAndMessages(null)
        // Serialize closing with any frame still being read.
        worker.post { cleanup { reader?.close() } }
        workerThread.quitSafely()
        cleanup { stopForeground(STOP_FOREGROUND_REMOVE) }
        val status = terminalStatus ?: SessionState.Status(SessionState.Phase.IDLE, R.string.message_stopped)
        SessionState.update(status.phase, status.message)
        super.onDestroy()
    }
    private fun cleanup(action: () -> Unit) {
        try { action() } catch (error: RuntimeException) { Log.w(TAG, "Capture cleanup failed", error) }
    }
    companion object {
        const val ACTION_START = "com.bobbywasabi.overlayapp.START"
        const val ACTION_STOP = "com.bobbywasabi.overlayapp.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_analysis"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "OverlayService"
        /** Item 30: stop forgotten sessions. Keep in sync with message_idle_stopped. */
        private const val IDLE_STOP_MS = 10 * 60_000L
    }
}
