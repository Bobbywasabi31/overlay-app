package com.bobbywasabi.overlayapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bobbywasabi.overlayapp.databinding.ActivityDemoBinding

class DemoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityDemoBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Only the HUD receives insets: the encounter keeps full-display geometry.
        binding.practiceHud.applySystemBarInsets()
        binding.practiceRing.feedback = { result, touches, attempts, hits, timing ->
            binding.tapCount.text = getString(R.string.demo_stats, touches, attempts, hits)
            val resultText = when (result) {
                PracticeScene.Result.INNER_RING -> R.string.demo_inner_hit
                PracticeScene.Result.OUTER_TARGET -> R.string.demo_outer_hit
                PracticeScene.Result.MISS -> R.string.demo_miss
                null -> null
            }
            val timingText = when (timing) {
                PracticeScene.Timing.EARLY -> getString(R.string.demo_timing_early)
                PracticeScene.Timing.EXCELLENT -> getString(R.string.demo_timing_excellent)
                PracticeScene.Timing.LATE -> getString(R.string.demo_timing_late)
                null -> null
            }
            binding.practiceResult.text = when {
                timingText != null && resultText != null -> getString(R.string.demo_drill_feedback, timingText, getString(resultText))
                timingText != null -> timingText
                resultText != null -> getString(resultText)
                else -> getString(R.string.demo_ready)
            }
        }
        binding.tapCount.text = getString(R.string.demo_stats, 0, 0, 0)
        binding.backButton.setOnClickListener { finish() }
        binding.smallRingButton.setOnClickListener { binding.practiceRing.toggleSmallPale() }
        binding.movementButton.setOnClickListener { binding.practiceRing.toggleMovement() }
        binding.colorButton.setOnClickListener { binding.practiceRing.nextColor() }
        binding.drillButton.setOnClickListener {
            binding.drillButton.setText(if (binding.practiceRing.toggleTimingDrill())
                R.string.demo_drill_on else R.string.demo_drill)
        }
        binding.ringModeButton.setOnClickListener {
            binding.ringModeButton.setText(if (binding.practiceRing.toggleRingMode())
                R.string.demo_ring_hold else R.string.demo_ring_always)
        }
    }
    override fun onResume() { super.onResume(); PracticeSession.resume(); binding.practiceRing.resume() }
    override fun onPause() { binding.practiceRing.pause(); PracticeSession.pause(); super.onPause() }
}
