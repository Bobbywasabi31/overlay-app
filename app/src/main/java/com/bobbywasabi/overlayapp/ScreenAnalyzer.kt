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

    /** Per-frame diagnostics for the debug HUD (items 21, 85). */
    data class Stats(
        val analysisMs: Long,
        val candidates: Int,
        val rejected: Map<String, Int>,
    )
    var lastStats: Stats = Stats(0, 0, emptyMap())
        private set

    fun analyze(pixels: IntArray, width: Int, height: Int): Ring? {
        val startedNs = System.nanoTime()
        require(width > 0 && height > 0 && width.toLong() * height <= pixels.size)
        val count = width * height
        if (mask.size != count) {
            mask = ByteArray(count)
            queue = IntArray(count)
        }
        // Only the center region can ever be accepted (see the cx/cy checks
        // below), so mask, seed, and flood-fill just that region.
        val x0 = (width * REGION_X0).toInt().coerceIn(0, width - 1)
        val x1 = (width * REGION_X1).toInt().coerceIn(x0, width - 1)
        val y0 = (height * REGION_Y0).toInt().coerceIn(0, height - 1)
        val y1 = (height * REGION_Y1).toInt().coerceIn(y0, height - 1)
        // #14: skip frames with no vivid pixels at all (loading screens, fades)
        // before the expensive pass. Uses max (not average) saturation so a
        // small desaturated ring on a gray background is still detected.
        if (!hasVividPixels(pixels, width, x0, x1, y0, y1)) {
            lastStats = Stats(
                analysisMs = (System.nanoTime() - startedNs) / 1_000_000,
                candidates = 0,
                rejected = mapOf("grayscale" to 1),
            )
            return null
        }
        for (y in y0..y1) {
            val row = y * width
            for (x in x0..x1) {
                val i = row + x
                mask[i] = if (isRingColor(pixels[i])) 1 else 0
            }
        }
        var best: Ring? = null
        var bestScore = 0f
        var candidates = 0
        val rejected = mutableMapOf<String, Int>()
        fun reject(reason: String) { rejected[reason] = (rejected[reason] ?: 0) + 1 }
        for (y in y0..y1) {
            for (x in x0..x1) {
                val seed = y * width + x
                if (mask[seed].toInt() == 0) continue
                var head = 0
                var tail = 1
                queue[0] = seed
                mask[seed] = 0
                var minX = x1
                var maxX = x0
                var minY = y1
                var maxY = y0
                while (head < tail) {
                    val index = queue[head++]
                    val px = index % width
                    val py = index / width
                    minX = min(minX, px)
                    maxX = max(maxX, px)
                    minY = min(minY, py)
                    maxY = max(maxY, py)
                    for (ny in max(y0, py - 1)..min(y1, py + 1)) {
                        for (nx in max(x0, px - 1)..min(x1, px + 1)) {
                            val neighbor = ny * width + nx
                            if (mask[neighbor].toInt() != 0) {
                                mask[neighbor] = 0
                                queue[tail++] = neighbor
                            }
                        }
                    }
                }
                // Components touching the analyzed region's edge may be clipped.
                if (tail < 16) { reject("tiny"); continue }
                if (minX == x0 || minY == y0 || maxX == x1 || maxY == y1) { reject("edge"); continue }
                candidates++
                val boxWidth = maxX - minX + 1
                val boxHeight = maxY - minY + 1
                if (boxWidth.toFloat() / boxHeight !in 0.85f..1.18f) { reject("aspect"); continue }
                val cx = (minX + maxX) / 2.0
                val cy = (minY + maxY) / 2.0
                if (cx / width !in REGION_X0..REGION_X1 || cy / height !in REGION_Y0..REGION_Y1) { reject("center"); continue }
                val shortSide = min(width, height)
                val outerRadius = (boxWidth + boxHeight) / 4.0
                if (outerRadius < max(4.0, shortSide * 0.009) || outerRadius > shortSide * 0.38) { reject("radius"); continue }
                // Reject filled disks and broad patches of scenery.
                if (tail / (PI * outerRadius * outerRadius) > 0.48) { reject("fill"); continue }
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
                if (relativeError > 0.10 || coverage < 0.83f) { reject("shape"); continue }
                val confidence = ((1 - relativeError / 0.15) * coverage).toFloat().coerceIn(0f, 1f)
                val ring = Ring((cx / width).toFloat(), (cy / height).toFloat(), (radius / shortSide).toFloat(), confidence)
                val score = confidence * 0.85f + ring.radius * 0.15f
                if (score > bestScore) {
                    bestScore = score
                    best = ring
                }
            }
        }
        lastStats = Stats(
            analysisMs = (System.nanoTime() - startedNs) / 1_000_000,
            candidates = candidates,
            rejected = rejected.toMap(),
        )
        return best
    }

    /** #14: true when any sampled pixel is vivid enough to belong to a ring. */
    private fun hasVividPixels(pixels: IntArray, width: Int, x0: Int, x1: Int, y0: Int, y1: Int): Boolean {
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val color = pixels[y * width + x]
                val red = (color ushr 16) and 255
                val green = (color ushr 8) and 255
                val blue = color and 255
                if (max(red, max(green, blue)) - min(red, min(green, blue)) >= VIVID_SATURATION) return true
                x += 4
            }
            y += 4
        }
        return false
    }

    companion object {
        /** Frame-analysis budget for low-end phones (item 21); the HUD flags overruns. */
        const val ANALYSIS_BUDGET_MS = 40L
        const val VIVID_SATURATION = 32
        const val REGION_X0 = 0.12f
        const val REGION_X1 = 0.88f
        const val REGION_Y0 = 0.18f
        const val REGION_Y1 = 0.82f
    }
    private fun isRingColor(color: Int): Boolean {
        val red = (color ushr 16) and 255
        val green = (color ushr 8) and 255
        val blue = color and 255
        val high = max(red, max(green, blue))
        val low = min(red, min(green, blue))
        val delta = high - low
        // Thin, antialiased game rings lose saturation when blended with the scene.
        if (high < 100 || delta < high * 0.25f) return false
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
