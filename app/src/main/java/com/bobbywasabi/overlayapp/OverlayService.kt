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
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
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
    private var initialDisplay: DisplaySize? = null
    private var displayListenerRegistered = false
    private var screenOffReceiverRegistered = false
    @Volatile private var active = false
    private var lastResultTime = 0L
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
            if (!Settings.canDrawOverlays(this@OverlayService)) {
                endSession(R.string.message_overlay_required)
                return
            }
            if (SystemClock.elapsedRealtime() - lastResultTime > 900L) guidance?.showRing(null)
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
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                val image = source.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastAnalysis < 200L) return@use
                    lastAnalysis = now
                    val plane = it.planes[0]
                    RgbaFrame.copy(plane.buffer, it.width, it.height, plane.rowStride, plane.pixelStride, pixels)
                    val ring = tracker.update(analyzer.analyze(pixels, it.width, it.height))
                    mainHandler.post {
                        if (active) {
                            lastResultTime = now
                            guidance?.showRing(ring)
                        }
                    }
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Unable to analyze screen frame", error)
                mainHandler.post { if (active) endSession(R.string.message_frame_error, error = true) }
            }
        }, worker)
        guidance = GuidanceView(overlayContext, size.width, size.height).also { view ->
            windowManager.addView(view, overlayParameters())
        }
        active = true
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
        active = false
        guidance?.showRing(null)
        SessionState.update(if (error) SessionState.Phase.ERROR else SessionState.Phase.IDLE, message)
        stopSelf()
    }
    override fun onDestroy() {
        active = false
        mainHandler.removeCallbacksAndMessages(null)
        if (displayListenerRegistered) displayManager.unregisterDisplayListener(displayListener)
        if (screenOffReceiverRegistered) unregisterReceiver(screenOffReceiver)
        virtualDisplay?.release()
        virtualDisplay = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        guidance?.let { view -> if (view.isAttachedToWindow) windowManager.removeViewImmediate(view) }
        guidance = null
        val reader = imageReader
        imageReader = null
        reader?.setOnImageAvailableListener(null, null)
        worker.removeCallbacksAndMessages(null)
        // Serialize closing with any frame still being read.
        worker.post { reader?.close() }
        workerThread.quitSafely()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (SessionState.current.isActive) SessionState.update(SessionState.Phase.IDLE, R.string.message_stopped)
        super.onDestroy()
    }
    companion object {
        const val ACTION_START = "com.bobbywasabi.overlayapp.START"
        const val ACTION_STOP = "com.bobbywasabi.overlayapp.STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        private const val CHANNEL_ID = "screen_analysis"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "OverlayService"
    }
}
