package com.bobbywasabi.overlayapp

import kotlin.math.PI
import kotlin.math.abs
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
    // Arc-fallback scratch (task 20): every mask pixel visited by the main
    // flood fill, plus the surviving fragments as (start, length) slices.
    private var arcPts = IntArray(0)
    private var arcInliers = IntArray(0)
    private val arcFrags = ArrayList<Frag>()
    private var arcCount = 0
    private var arcSeedsTried = 0

    /** A surviving mask fragment: arcPts entries [start, start+length). */
    private data class Frag(val start: Int, val length: Int)

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
        if (arcPts.size != count) {
            arcPts = IntArray(count)
            arcInliers = IntArray(count)
        }
        arcCount = 0
        arcFrags.clear()
        arcSeedsTried = 0
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
        // Robust background estimate over the 3x3 block neighborhood (#13
        // cause #1): a thin ring raises the saturation of the neighboring
        // blocks it passes through, so a plain mean lets the ring pollute its
        // own background estimate and suppress itself below the high-pass
        // threshold (fragmentation -> shape rejection). Take the minimum over
        // opposing-neighbor pair sums instead: the ring can spoil the pairs it
        // touches, but a pair facing the true background stays clean. Broad
        // vivid scenery raises every pair, so the grass fix from v0.4.1 is
        // preserved. Interior blocks need no bounds checks (4 pair-sums + 3
        // comparisons -- cheaper than the old plain mean); border blocks keep
        // the old mean. No gate thresholds changed. The center block is
        // excluded as before.
        for (by in 1 until bh - 1) {
            for (bx in 1 until bw - 1) {
                val b = by * bw + bx
                var best = blockSat[b - bw - 1] + blockSat[b + bw + 1] // TL+BR
                var pair = blockSat[b - bw] + blockSat[b + bw] // T+B
                if (pair < best) best = pair
                pair = blockSat[b - bw + 1] + blockSat[b + bw - 1] // TR+BL
                if (pair < best) best = pair
                pair = blockSat[b - 1] + blockSat[b + 1] // L+R
                if (pair < best) best = pair
                blockBg[b] = best / 32
            }
        }
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                if (by in 1 until bh - 1 && bx in 1 until bw - 1) continue
                var sum = 0
                var cnt = 0
                for (ny in max(0, by - 1)..min(bh - 1, by + 1)) {
                    for (nx in max(0, bx - 1)..min(bw - 1, bx + 1)) {
                        if (nx == bx && ny == by) continue
                        sum += blockSat[ny * bw + nx]
                        cnt++
                    }
                }
                blockBg[by * bw + bx] = if (cnt > 0) sum / (cnt * BLOCK * BLOCK) else 0
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
                // Record every mask pixel for the arc fallback (task 20);
                // fragments are contiguous slices of arcPts.
                arcPts[arcCount++] = index
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
            if (tail >= ARC_MIN_FRAG_PX) arcFrags.add(Frag(arcCount - tail, tail))
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
            if (interiorLooksLikeDisk(pixels, width, height, cx, cy, radius, satSum / tail)) {
                reject("disk"); continue
            }
            val confidence = ((1 - relativeError / 0.15) * coverage).toFloat().coerceIn(0f, 1f)
            val ring = Ring((cx / width).toFloat(), (cy / height).toFloat(), (radius / shortSide).toFloat(), confidence)
            val score = confidence * 0.85f + ring.radius * 0.15f
            if (score > bestScore) {
                bestScore = score
                best = ring
            }
        }
        // Fragment-tolerant arc fallback (task 20): only when the
        // connected-component pass found nothing.
        if (best == null) {
            best = arcFallback(pixels, width, height)
            if (arcSeedsTried > 0) rejected["arc"] = arcSeedsTried
        }
        lastStats = Stats(
            analysisMs = (System.nanoTime() - startedNs) / 1_000_000,
            candidates = candidates,
            rejected = rejected.toMap(),
        )
        return best
    }

    /**
     * Thin-ring verification shared by both passes: true when the interior is
     * clearly as saturated as the ring itself, i.e. the candidate is a solid
     * disk rather than a ring. Relative to the ring's own saturation, so a
     * ring around a Pokemon still passes.
     */
    private fun interiorLooksLikeDisk(
        pixels: IntArray, width: Int, height: Int,
        cx: Double, cy: Double, radius: Double, ringSat: Double,
    ): Boolean {
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
        return vividInside > insideTotal / 2
    }

    /**
     * Fragment-tolerant second pass. On busy scenes the thin ring shatters
     * into sub-threshold fragments that fail the main pass's aspect/shape
     * gates individually but still lie on one circle. Seeds an algebraic
     * (Kasa) circle fit from each large fragment (then fragment pairs — Kasa
     * is biased toward small circles on short arcs), refits against all mask
     * points near the circle, and accepts on inlier count, angular coverage,
     * radial tightness, annulus fill, and the thin-ring interior check.
     */
    private fun arcFallback(pixels: IntArray, width: Int, height: Int): Ring? {
        if (arcFrags.isEmpty()) return null
        val shortSide = min(width, height)
        val rMin = max(4.0, shortSide * 0.009)
        val rMax = shortSide * 0.38
        val bySize = arcFrags.sortedByDescending { it.length }
        val singles = bySize.take(ARC_MAX_SEEDS)
        val pairFrags = bySize.take(ARC_MAX_PAIR_SEEDS)
        arcSeedsTried = singles.size + pairFrags.size * (pairFrags.size - 1) / 2
        var best: Ring? = null
        var bestConfidence = 0f
        for (frag in singles) {
            val ring = tryArcSeed(pixels, width, height, arcPts, frag.start, frag.start + frag.length,
                rMin, rMax, shortSide)
            if (ring != null && ring.confidence > bestConfidence) {
                bestConfidence = ring.confidence
                best = ring
            }
        }
        for (i in pairFrags.indices) {
            for (j in i + 1 until pairFrags.size) {
                val a = pairFrags[i]
                val b = pairFrags[j]
                val pairPts = IntArray(a.length + b.length)
                arcPts.copyInto(pairPts, 0, a.start, a.start + a.length)
                arcPts.copyInto(pairPts, a.length, b.start, b.start + b.length)
                val ring = tryArcSeed(pixels, width, height, pairPts, 0, pairPts.size,
                    rMin, rMax, shortSide)
                if (ring != null && ring.confidence > bestConfidence) {
                    bestConfidence = ring.confidence
                    best = ring
                }
            }
        }
        return best
    }

    /** Fits a circle to one seed and validates it against all mask points. */
    private fun tryArcSeed(
        pixels: IntArray, width: Int, height: Int,
        seedPts: IntArray, seedFrom: Int, seedTo: Int,
        rMin: Double, rMax: Double, shortSide: Int,
    ): Ring? {
        val seedFit = kasaCircleFit(seedPts, seedFrom, seedTo, 1, width) ?: return null
        var cx = seedFit.first
        var cy = seedFit.second
        var r = seedFit.third
        repeat(ARC_REFIT_ITERS) {
            if (r < rMin || r > rMax) return null
            val tol = max(1.5, 0.05 * r)
            var n = 0
            for (i in 0 until arcCount) {
                val idx = arcPts[i]
                val dx = idx % width - cx
                val dy = idx / width - cy
                if (abs(hypot(dx, dy) - r) <= tol) arcInliers[n++] = idx
            }
            if (n < ARC_REFIT_MIN_PTS) return null
            val refit = kasaCircleFit(arcInliers, 0, n, max(1, n / 800), width) ?: return null
            cx = refit.first
            cy = refit.second
            r = refit.third
        }
        if (r < rMin || r > rMax) return null
        if (cx / width < REGION_X0 || cx / width > REGION_X1 ||
            cy / height < REGION_Y0 || cy / height > REGION_Y1
        ) return null
        val tol = max(1.5, 0.05 * r)
        var n = 0
        for (i in 0 until arcCount) {
            val idx = arcPts[i]
            val dx = idx % width - cx
            val dy = idx / width - cy
            if (abs(hypot(dx, dy) - r) <= tol) arcInliers[n++] = idx
        }
        if (n < ARC_MIN_INLIERS) return null
        var sectors = 0
        var sumSq = 0.0
        var satSum = 0.0
        for (i in 0 until n) {
            val idx = arcInliers[i]
            val dx = idx % width - cx
            val dy = idx / width - cy
            val d = hypot(dx, dy)
            sumSq += (d - r) * (d - r)
            val sector = (((atan2(dy, dx) + PI) / (2 * PI)) * 24).toInt().coerceIn(0, 23)
            sectors = sectors or (1 shl sector)
            val color = pixels[idx]
            val red = (color ushr 16) and 255
            val green = (color ushr 8) and 255
            val blue = color and 255
            satSum += max(red, max(green, blue)) - min(red, min(green, blue))
        }
        val tightness = sqrt(sumSq / n) / r
        val coverage = Integer.bitCount(sectors) / 24f
        val annulusFill = n / (2 * PI * r * 2 * tol)
        if (coverage < ARC_MIN_COVERAGE || tightness > ARC_MAX_TIGHTNESS ||
            annulusFill < ARC_MIN_ANNULUS_FILL
        ) return null
        if (interiorLooksLikeDisk(pixels, width, height, cx, cy, r, satSum / n)) return null
        val confidence = ((1.0 - tightness / 0.12) * coverage).toFloat().coerceIn(0f, ARC_CONFIDENCE_CAP)
        return Ring(
            (cx / width).toFloat(), (cy / height).toFloat(),
            (r / shortSide).toFloat(), confidence,
        )
    }

    /** Algebraic (Kasa) least-squares circle fit over pts entries [from, to), stride [step]. */
    private fun kasaCircleFit(pts: IntArray, from: Int, to: Int, step: Int, width: Int): Triple<Double, Double, Double>? {
        var m11 = 0.0
        var m12 = 0.0
        var m13 = 0.0
        var m22 = 0.0
        var m23 = 0.0
        var m33 = 0.0
        var v1 = 0.0
        var v2 = 0.0
        var v3 = 0.0
        var count = 0
        var i = from
        while (i < to) {
            val idx = pts[i]
            val x = (idx % width).toDouble()
            val y = (idx / width).toDouble()
            val z = -(x * x + y * y)
            m11 += x * x
            m12 += x * y
            m13 += x
            m22 += y * y
            m23 += y
            m33 += 1.0
            v1 += x * z
            v2 += y * z
            v3 += z
            count++
            i += step
        }
        if (count < 6) return null
        val det = det3(m11, m12, m13, m12, m22, m23, m13, m23, m33)
        if (abs(det) < 1e-9) return null
        val a = det3(v1, m12, m13, v2, m22, m23, v3, m23, m33) / det
        val b = det3(m11, v1, m13, m12, v2, m23, m13, v3, m33) / det
        val c = det3(m11, m12, v1, m12, m22, v2, m13, m23, v3) / det
        val rr = (a * a + b * b) / 4.0 - c
        if (rr <= 0.0 || !rr.isFinite()) return null
        val r = sqrt(rr)
        if (!r.isFinite()) return null
        return Triple(-a / 2.0, -b / 2.0, r)
    }

    private fun det3(
        a: Double, b: Double, c: Double,
        d: Double, e: Double, f: Double,
        g: Double, h: Double, i: Double,
    ): Double = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)

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
        /**
         * Fragment-tolerant arc fallback (task 20): second pass when the
         * connected-component pass finds nothing. Seeds algebraic (Kasa)
         * circle fits from surviving mask fragments, refits against all mask
         * points near the circle, and accepts on inlier count, angular
         * coverage, radial tightness, annulus fill, and the thin-ring
         * interior check. Main-pass gates (radius, center region) are reused
         * unchanged; nothing is loosened. Validated against benchmark/corpus
         * (task 19) via the Python port before porting here.
         */
        const val ARC_MIN_FRAG_PX = 8
        const val ARC_MAX_SEEDS = 12
        const val ARC_MAX_PAIR_SEEDS = 6
        const val ARC_REFIT_ITERS = 3
        const val ARC_REFIT_MIN_PTS = 12
        const val ARC_MIN_INLIERS = 32
        /** 15 of 24 sectors: rejects a half-ring (14), accepts distributed fragments. */
        const val ARC_MIN_COVERAGE = 0.625f
        const val ARC_MAX_TIGHTNESS = 0.08f
        /**
         * Fraction of the tolerance band around the fitted circle occupied by
         * mask points. True rings measure 0.5-0.9; dense-noise hallucinations
         * sit near the background mask density (0.11-0.15 measured).
         */
        const val ARC_MIN_ANNULUS_FILL = 0.30f
        const val ARC_CONFIDENCE_CAP = 0.85f
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
