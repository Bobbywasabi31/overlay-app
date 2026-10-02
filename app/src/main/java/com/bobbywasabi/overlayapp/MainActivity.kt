package com.bobbywasabi.overlayapp

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.bobbywasabi.overlayapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var requestingStart = false

    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (requestingStart) {
            if (Settings.canDrawOverlays(this)) requestNotificationPermission()
            else finishRequest(R.string.message_overlay_required)
        }
        render()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Notification denial is independent of screen-capture consent.
        if (requestingStart) requestCapture()
    }
    private val capturePermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        requestingStart = false
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            finishRequest(R.string.message_cancelled)
        } else if (!Settings.canDrawOverlays(this)) {
            finishRequest(R.string.message_overlay_required)
        } else if (!SessionState.current.isActive) {
            SessionState.update(SessionState.Phase.STARTING, R.string.status_starting)
            try {
                ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
                    action = OverlayService.ACTION_START
                    putExtra(OverlayService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(OverlayService.EXTRA_RESULT_DATA, data)
                })
            } catch (_: RuntimeException) {
                SessionState.update(SessionState.Phase.ERROR, R.string.message_capture_error)
            }
        }
        render()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestingStart = savedInstanceState?.getBoolean(REQUESTING_START) ?: false
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        binding.startButton.setOnClickListener {
            if (!requestingStart && !SessionState.current.isActive) {
                requestingStart = true
                render()
                if (Settings.canDrawOverlays(this)) requestNotificationPermission()
                else openOverlaySettings()
            }
        }
        binding.permissionButton.setOnClickListener { openOverlaySettings() }
        binding.stopButton.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }
        binding.practiceButton.setOnClickListener { startActivity(Intent(this, DemoActivity::class.java)) }
        binding.throwerPermissionButton.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.thrower_settings_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        binding.throwDuration.progress = (ThrowState.durationMs - 150L).toInt()
        binding.throwDuration.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) ThrowState.setDuration(150L + progress)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        ThrowState.changes.observe(this) { render() }
        SessionState.status.observe(this) { render() }
    }
    override fun onResume() {
        super.onResume()
        render()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(REQUESTING_START, requestingStart)
        super.onSaveInstanceState(outState)
    }
    private fun openOverlaySettings() {
        try {
            overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: ActivityNotFoundException) {
            finishRequest(R.string.message_overlay_required)
            Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_LONG).show()
        }
    }
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else requestCapture()
    }
    private fun requestCapture() {
        try {
            val manager = getSystemService(MediaProjectionManager::class.java)
            // A global overlay needs full-display coordinates, not a cropped app window.
            val intent = if (Build.VERSION.SDK_INT >= 34) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else manager.createScreenCaptureIntent()
            capturePermission.launch(intent)
        } catch (_: RuntimeException) {
            finishRequest(R.string.message_capture_error)
        }
    }
    private fun finishRequest(message: Int) {
        requestingStart = false
        SessionState.update(SessionState.Phase.IDLE, message)
        render()
    }
    private fun render() {
        val status = SessionState.current
        binding.statusTitle.setText(when (status.phase) {
            SessionState.Phase.IDLE -> R.string.status_idle
            SessionState.Phase.STARTING -> R.string.status_starting
            SessionState.Phase.RUNNING -> R.string.status_running
            SessionState.Phase.STOPPING -> R.string.status_stopping
            SessionState.Phase.ERROR -> R.string.status_error
        })
        binding.statusMessage.setText(status.message)
        binding.permissionStatus.setText(if (Settings.canDrawOverlays(this)) R.string.permission_granted else R.string.permission_missing)
        binding.startButton.isEnabled = !status.isActive && !requestingStart
        binding.stopButton.isEnabled = status.isActive
        binding.permissionButton.isEnabled = !requestingStart && !status.isActive
        binding.throwerStatus.setText(if (GestureThrowService.current != null) R.string.thrower_connected else R.string.thrower_disconnected)
        binding.throwDurationLabel.text = getString(R.string.throw_duration, ThrowState.durationMs)
    }
    companion object { private const val REQUESTING_START = "requesting_start" }
}
