package com.bobbywasabi.overlayapp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Worker-thread colored-ring heuristic; does not identify game state or throw grades. */
class ScreenAnalyzer {
    /** Coordinates divide by frame dimensions; radius divides by the shorter side. */
    data class Ring(val x: Float, val y: Float, val radius: Float, val confidence: Float)
    private var mask = ByteArray(0)
    private var queue = IntArray(0)

    fun analyze(pixels: IntArray, width: Int, height: Int): Ring? {
        require(width > 0 && height > 0 && width.toLong() * height <= pixels.size)
        val count = width * height
        if (mask.size != count) {
            mask = ByteArray(count)
            queue = IntArray(count)
        }
        for (i in 0 until count) mask[i] = if (isRingColor(pixels[i])) 1 else 0
        var best: Ring? = null
        var bestScore = 0f
        for (seed in 0 until count) {
            if (mask[seed].toInt() == 0) continue
            var head = 0
            var tail = 1
            queue[0] = seed
            mask[seed] = 0
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                for (ny in max(0, y - 1)..min(height - 1, y + 1)) {
                    for (nx in max(0, x - 1)..min(width - 1, x + 1)) {
                        val neighbor = ny * width + nx
                        if (mask[neighbor].toInt() != 0) {
                            mask[neighbor] = 0
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
            if (tail < 24 || minX == 0 || minY == 0 || maxX == width - 1 || maxY == height - 1) continue
            val boxWidth = maxX - minX + 1
            val boxHeight = maxY - minY + 1
            if (boxWidth.toFloat() / boxHeight !in 0.85f..1.18f) continue
            val cx = (minX + maxX) / 2.0
            val cy = (minY + maxY) / 2.0
            if (cx / width !in 0.12..0.88 || cy / height !in 0.18..0.82) continue
            val shortSide = min(width, height)
            val outerRadius = (boxWidth + boxHeight) / 4.0
            if (outerRadius < max(6.0, shortSide * 0.018) || outerRadius > shortSide * 0.38) continue
            // Reject filled disks and broad patches of scenery.
            if (tail / (PI * outerRadius * outerRadius) > 0.48) continue
            var sum = 0.0
            var sumSquares = 0.0
            var sectors = 0
            for (i in 0 until tail) {
                val dx = queue[i] % width - cx
                val dy = queue[i] / width - cy
                val distance = hypot(dx, dy)
                sum += distance
                sumSquares += distance * distance
                val sector = (((atan2(dy, dx) + PI) / (2 * PI)) * 24).toInt().coerceIn(0, 23)
                sectors = sectors or (1 shl sector)
            }
            val radius = sum / tail
            val relativeError = sqrt(max(0.0, sumSquares / tail - radius * radius)) / radius
            val coverage = Integer.bitCount(sectors) / 24f
            if (relativeError > 0.10 || coverage < 0.83f) continue
            val confidence = ((1 - relativeError / 0.15) * coverage).toFloat().coerceIn(0f, 1f)
            val ring = Ring((cx / width).toFloat(), (cy / height).toFloat(), (radius / shortSide).toFloat(), confidence)
            val score = confidence * 0.85f + ring.radius * 0.15f
            if (score > bestScore) {
                bestScore = score
                best = ring
            }
        }
        return best
    }
    private fun isRingColor(color: Int): Boolean {
        val red = (color ushr 16) and 255
        val green = (color ushr 8) and 255
        val blue = color and 255
        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))
        val delta = high - low
        if (high < 100 || delta < high * 0.45f) return false
        var hue = when (high) {
            red -> 60f * (green - blue) / delta
            green -> 120f + 60f * (blue - red) / delta
            else -> 240f + 60f * (red - green) / delta
        }
        if (hue < 0) hue += 360f
        // Cyan guidance is excluded to prevent self-detection.
        return hue <= 145f || hue >= 345f
    }
}
