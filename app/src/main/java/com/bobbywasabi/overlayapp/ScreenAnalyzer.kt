package com.bobbywasabi.overlayapp

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Worker-thread colored-ring heuristic; does not identify game state or throw grades. */
class ScreenAnalyzer {
    /** Coordinates divide by frame dimensions; radius divides by the shorter side. */
    data class Ring(val x: Float, val y: Float, val radius: Float, val confidence: Float)
    private var mask = ByteArray(0)
    private var queue = IntArray(0)
    private var blockSat = IntArray(0)
    private var blockBg = IntArray(0)
    private var blockTmp = IntArray(0)

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
        // Only rings centered in the middle of the frame can ever be accepted
        // (see the cx/cy checks below). The vivid pre-check stays regional:
        // any acceptable ring has vivid pixels in the center region. But the
        // mask, seed, and flood-fill cover the full frame: large rings extend
        // beyond the center region and must not be clipped.
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
        // Local-contrast mask. The target ring glows: it is more saturated than
        // its surroundings. A plain vivid-pixel mask merges the ring with
        // saturated scenery (grass fills most of the frame), so we high-pass
        // the saturation instead: block-average it, smooth the neighborhood
        // (excluding the center block) for the local background, and keep
        // pixels well above it.
        val bw = (width + BLOCK - 1) / BLOCK
        val bh = (height + BLOCK - 1) / BLOCK
        if (blockSat.size != bw * bh) {
            blockSat = IntArray(bw * bh)
            blockBg = IntArray(bw * bh)
            blockTmp = IntArray(bw * bh)
        }
        blockSat.fill(0)
        for (y in 0 until height) {
            val row = y * width
            val blockRow = (y / BLOCK) * bw
            for (x in 0 until width) {
                val color = pixels[row + x]
                val red = (color ushr 16) and 255
                val green = (color ushr 8) and 255
                val blue = color and 255
                blockSat[blockRow + x / BLOCK] += max(red, max(green, blue)) - min(red, min(green, blue))
            }
        }
        // Smooth the block averages over the 5x5 block neighborhood. A 3x3
        // neighborhood lets a thin ring pollute its own background estimate via
        // neighboring blocks; 5x5 dilutes the ring's contribution so it does
        // not suppress itself. The separable implementation excludes the
        // central cross (center block plus 4 orthogonal neighbors), which are
        // the blocks most likely to contain the ring itself.
        // Separable box blur (horizontal then vertical) for speed: 10 taps
        // per block instead of 24.
        val temp = blockTmp
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                var sum = 0
                var cnt = 0
                for (dx in -2..2) {
                    val nx = bx + dx
                    if (nx in 0 until bw) {
                        sum += blockSat[by * bw + nx]
                        cnt++
                    }
                }
                // Exclude the center block's own contribution by subtracting it
                // and reducing the count; the vertical pass then averages.
                temp[by * bw + bx] = (sum - blockSat[by * bw + bx]) / max(1, (cnt - 1) * BLOCK * BLOCK)
            }
        }
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                var sum = 0
                var cnt = 0
                for (dy in -2..2) {
                    val ny = by + dy
                    if (ny in 0 until bh) {
                        sum += temp[ny * bw + bx]
                        cnt++
                    }
                }
                blockBg[by * bw + bx] = sum / max(1, cnt)
            }
        }
        for (y in 0 until height) {
            val row = y * width
            val blockRow = (y / BLOCK) * bw
            for (x in 0 until width) {
                val color = pixels[row + x]
                val red = (color ushr 16) and 255
                val green = (color ushr 8) and 255
                val blue = color and 255
                val high = max(red, max(green, blue))
                val sat = high - min(red, min(green, blue))
                // Cheap integer gates first; the hue check (with division) runs
                // only on the few pixels that pass the high-pass.
                mask[row + x] = if (sat - blockBg[blockRow + x / BLOCK] > HP_SATURATION &&
                    high >= MIN_BRIGHTNESS && isRingHue(red, green, blue, sat)
                ) 1 else 0
            }
        }
        var best: Ring? = null
        var bestScore = 0f
        var candidates = 0
        val rejected = mutableMapOf<String, Int>()
        fun reject(reason: String) { rejected[reason] = (rejected[reason] ?: 0) + 1 }
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
                val px = index % width
                val py = index / width
                minX = min(minX, px)
                maxX = max(maxX, px)
                minY = min(minY, py)
                maxY = max(maxY, py)
                for (ny in max(0, py - 1)..min(height - 1, py + 1)) {
                    for (nx in max(0, px - 1)..min(width - 1, px + 1)) {
                        val neighbor = ny * width + nx
                        if (mask[neighbor].toInt() != 0) {
                            mask[neighbor] = 0
                            queue[tail++] = neighbor
                        }
                    }
                }
            }
            // Components touching the frame edge are clipped by the screen.
            if (tail < 16) { reject("tiny"); continue }
            if (minX == 0 || minY == 0 || maxX == width - 1 || maxY == height - 1) { reject("edge"); continue }
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
            var satSum = 0.0
            var sectors = 0
            for (i in 0 until tail) {
                val qi = queue[i]
                val dx = qi % width - cx
                val dy = qi / width - cy
                val distance = hypot(dx, dy)
                sum += distance
                sumSquares += distance * distance
                val color = pixels[qi]
                val red = (color ushr 16) and 255
                val green = (color ushr 8) and 255
                val blue = color and 255
                satSum += max(red, max(green, blue)) - min(red, min(green, blue))
                val sector = (((atan2(dy, dx) + PI) / (2 * PI)) * 24).toInt().coerceIn(0, 23)
                sectors = sectors or (1 shl sector)
            }
            val radius = sum / tail
            val relativeError = sqrt(max(0.0, sumSquares / tail - radius * radius)) / radius
            val coverage = Integer.bitCount(sectors) / 24f
            if (relativeError > 0.10 || coverage < 0.70f) { reject("shape"); continue }
            // Thin-ring verification: a solid disk's edge also high-passes as a
            // circle, so require the interior to be clearly less saturated than
            // the ring itself (relative, so a ring around a Pokemon still passes).
            val ringSat = satSum / tail
            var vividInside = 0
            var insideTotal = 0
            for (k in 0 until 8) {
                val angle = k * PI / 4
                val ix = (cx + cos(angle) * radius * 0.55).toInt()
                val iy = (cy + sin(angle) * radius * 0.55).toInt()
                if (ix in 0 until width && iy in 0 until height) {
                    insideTotal++
                    val color = pixels[iy * width + ix]
                    val red = (color ushr 16) and 255
                    val green = (color ushr 8) and 255
                    val blue = color and 255
                    if (max(red, max(green, blue)) - min(red, min(green, blue)) > ringSat * 0.8) vividInside++
                }
            }
            if (vividInside > insideTotal / 2) { reject("disk"); continue }
            val confidence = ((1 - relativeError / 0.15) * coverage).toFloat().coerceIn(0f, 1f)
            val ring = Ring((cx / width).toFloat(), (cy / height).toFloat(), (radius / shortSide).toFloat(), confidence)
            val score = confidence * 0.85f + ring.radius * 0.15f
            if (score > bestScore) {
                bestScore = score
                best = ring
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
        /** Local-contrast mask tuning (validated against a real encounter shot). */
        const val BLOCK = 4
        const val HP_SATURATION = 25
        const val MIN_BRIGHTNESS = 75
    }

    /**
     * Hue gate for the local-contrast mask. Target rings are red/yellow/green;
     * cyan guidance is excluded to prevent self-detection. Saturation and
     * brightness are handled by the high-pass, so this is hue only; [delta] is
     * always positive here (the high-pass threshold guarantees it).
     */
    private fun isRingHue(red: Int, green: Int, blue: Int, delta: Int): Boolean {
        val high = max(red, max(green, blue))
        var hue = when (high) {
            red -> 60f * (green - blue) / delta
            green -> 120f + 60f * (blue - red) / delta
            else -> 240f + 60f * (red - green) / delta
        }
        if (hue < 0) hue += 360f
        return hue <= 145f || hue >= 345f
    }
}
