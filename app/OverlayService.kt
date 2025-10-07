package com.yourname.pokemonthrow

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.ImageView
import com.yourname.pokemonthrow.databinding.OverlayLayoutBinding

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View
    private lateinit var binding: OverlayLayoutBinding
    private lateinit var screenAnalyzer: ScreenAnalyzer

    override fun onCreate() {
        super.onCreate()
        screenAnalyzer = ScreenAnalyzer()
        createOverlay()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        binding = OverlayLayoutBinding.inflate(LayoutInflater.from(this))
        overlayView = binding.root

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        )

        windowManager.addView(overlayView, params)
        startAnalysis()
    }

    private fun startAnalysis() {
        // Start screen analysis loop
        // This would use MediaProjection or other methods for screen capture
        // Note: Actual screen capture requires additional permissions
    }

    private fun updateOverlay(pokemonData: ScreenAnalyzer.PokemonData) {
        runOnUiThread {
            if (pokemonData.isExcellentSize) {
                binding.throwIndicator.visibility = View.VISIBLE
                binding.targetReticle.visibility = View.VISIBLE
                // Position reticle based on pokemonData.center
            } else {
                binding.throwIndicator.visibility = View.GONE
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            windowManager.removeView(overlayView)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
