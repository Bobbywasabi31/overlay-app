package com.bobbywasabi.overlayapp

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.bobbywasabi.overlayapp.databinding.ActivityDemoBinding

class DemoActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val binding = ActivityDemoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applySystemBarInsets()
        var taps = 0
        binding.tapCount.text = getString(R.string.demo_taps, taps)
        binding.practiceRing.setOnClickListener {
            binding.practiceRing.nextColor()
            binding.tapCount.text = getString(R.string.demo_taps, ++taps)
        }
        binding.backButton.setOnClickListener { finish() }
    }
}
