"""Faithful Python port of ScreenAnalyzer.kt for offline benchmark scoring.

Mirrors the Kotlin algorithm gate-for-gate (integer semantics preserved) so the
corpus scores predict what the on-device analyzer will do. Any change to
ScreenAnalyzer.kt gates/thresholds MUST be mirrored here before trusting the
numbers again.

Ported from app/src/main/java/com/bobbywasabi/overlayapp/ScreenAnalyzer.kt
(v0.4.13-alpha.1, commit f155294 era logic).
"""

import math

# --- companion constants (must match ScreenAnalyzer.kt) ---
VIVID_SATURATION = 32
REGION_X0 = 0.12
REGION_X1 = 0.88
REGION_Y0 = 0.18
REGION_Y1 = 0.82
BLOCK = 4
HP_SATURATION = 25
MIN_BRIGHTNESS = 75


def is_ring_hue(red, green, blue, delta):
    high = max(red, max(green, blue))
    if high == red:
        hue = 60.0 * (green - blue) / delta
    elif high == green:
        hue = 120.0 + 60.0 * (blue - red) / delta
    else:
        hue = 240.0 + 60.0 * (red - green) / delta
    if hue < 0:
        hue += 360.0
    return hue <= 145.0 or hue >= 345.0


def analyze(pixels, width, height):
    """pixels: flat list of (r,g,b) tuples, row-major. Returns (ring|None, stats)."""
    count = width * height
    x0 = max(0, min(width - 1, int(width * REGION_X0)))
    x1 = max(x0, min(width - 1, int(width * REGION_X1)))
    y0 = max(0, min(height - 1, int(height * REGION_Y0)))
    y1 = max(y0, min(height - 1, int(height * REGION_Y1)))

    # #14 vivid pre-check: max (not average) saturation, step 4 in x and y
    vivid = False
    y = y0
    while y <= y1:
        x = x0
        while x <= x1:
            r, g, b = pixels[y * width + x]
            if max(r, max(g, b)) - min(r, min(g, b)) >= VIVID_SATURATION:
                vivid = True
                break
            x += 4
        if vivid:
            break
        y += 4
    if not vivid:
        return None, {"candidates": 0, "rejected": {"grayscale": 1}}

    # Local-contrast mask: block-average saturation, robust neighborhood bg.
    bw = (width + BLOCK - 1) // BLOCK
    bh = (height + BLOCK - 1) // BLOCK
    block_sat = [0] * (bw * bh)
    for yy in range(height):
        block_row = (yy // BLOCK) * bw
        base = yy * width
        for xx in range(width):
            r, g, b = pixels[base + xx]
            block_sat[block_row + xx // BLOCK] += max(r, max(g, b)) - min(r, min(g, b))

    block_bg = [0] * (bw * bh)
    for by in range(1, bh - 1):
        for bx in range(1, bw - 1):
            b = by * bw + bx
            best = block_sat[b - bw - 1] + block_sat[b + bw + 1]  # TL+BR
            pair = block_sat[b - bw] + block_sat[b + bw]          # T+B
            if pair < best:
                best = pair
            pair = block_sat[b - bw + 1] + block_sat[b + bw - 1]  # TR+BL
            if pair < best:
                best = pair
            pair = block_sat[b - 1] + block_sat[b + 1]            # L+R
            if pair < best:
                best = pair
            block_bg[b] = best // 32
    for by in range(bh):
        for bx in range(bw):
            if 1 <= by < bh - 1 and 1 <= bx < bw - 1:
                continue
            s = 0
            cnt = 0
            for ny in range(max(0, by - 1), min(bh - 1, by + 1) + 1):
                for nx in range(max(0, bx - 1), min(bw - 1, bx + 1) + 1):
                    if nx == bx and ny == by:
                        continue
                    s += block_sat[ny * bw + nx]
                    cnt += 1
            block_bg[by * bw + bx] = s // (cnt * BLOCK * BLOCK) if cnt > 0 else 0

    mask = bytearray(count)
    for yy in range(height):
        block_row = (yy // BLOCK) * bw
        base = yy * width
        for xx in range(width):
            r, g, b = pixels[base + xx]
            high = max(r, max(g, b))
            sat = high - min(r, min(g, b))
            if (sat - block_bg[block_row + xx // BLOCK] > HP_SATURATION
                    and high >= MIN_BRIGHTNESS
                    and is_ring_hue(r, g, b, sat)):
                mask[base + xx] = 1

    # Connected components (8-connectivity), gate sequence mirrors Kotlin.
    best = None
    best_score = 0.0
    candidates = 0
    rejected = {}

    def reject(reason):
        rejected[reason] = rejected.get(reason, 0) + 1

    queue = [0] * count
    short_side = min(width, height)
    for seed in range(count):
        if mask[seed] == 0:
            continue
        head, tail = 0, 1
        queue[0] = seed
        mask[seed] = 0
        min_x, max_x = width, 0
        min_y, max_y = height, 0
        while head < tail:
            index = queue[head]
            head += 1
            px = index % width
            py = index // width
            if px < min_x:
                min_x = px
            if px > max_x:
                max_x = px
            if py < min_y:
                min_y = py
            if py > max_y:
                max_y = py
            for ny in range(max(0, py - 1), min(height - 1, py + 1) + 1):
                for nx in range(max(0, px - 1), min(width - 1, px + 1) + 1):
                    neighbor = ny * width + nx
                    if mask[neighbor] != 0:
                        mask[neighbor] = 0
                        queue[tail] = neighbor
                        tail += 1
        if tail < 16:
            reject("tiny")
            continue
        if min_x == 0 or min_y == 0 or max_x == width - 1 or max_y == height - 1:
            reject("edge")
            continue
        candidates += 1
        box_w = max_x - min_x + 1
        box_h = max_y - min_y + 1
        if not (0.85 <= box_w / box_h <= 1.18):
            reject("aspect")
            continue
        cx = (min_x + max_x) / 2.0
        cy = (min_y + max_y) / 2.0
        if not (REGION_X0 <= cx / width <= REGION_X1) or not (REGION_Y0 <= cy / height <= REGION_Y1):
            reject("center")
            continue
        outer_radius = (box_w + box_h) / 4.0
        if outer_radius < max(4.0, short_side * 0.009) or outer_radius > short_side * 0.38:
            reject("radius")
            continue
        if tail / (math.pi * outer_radius * outer_radius) > 0.48:
            reject("fill")
            continue
        s = 0.0
        sq = 0.0
        sat_sum = 0.0
        sectors = 0
        for i in range(tail):
            qi = queue[i]
            dx = qi % width - cx
            dy = qi // width - cy
            d = math.hypot(dx, dy)
            s += d
            sq += d * d
            r, g, b = pixels[qi]
            sat_sum += max(r, max(g, b)) - min(r, min(g, b))
            sector = int(((math.atan2(dy, dx) + math.pi) / (2 * math.pi)) * 24)
            if sector > 23:
                sector = 23
            sectors |= (1 << sector)
        radius = s / tail
        rel_err = math.sqrt(max(0.0, sq / tail - radius * radius)) / radius
        coverage = bin(sectors).count("1") / 24.0
        if rel_err > 0.10 or coverage < 0.70:
            reject("shape")
            continue
        ring_sat = sat_sum / tail
        vivid_inside = 0
        inside_total = 0
        for k in range(8):
            angle = k * math.pi / 4
            ix = int(cx + math.cos(angle) * radius * 0.55)
            iy = int(cy + math.sin(angle) * radius * 0.55)
            if 0 <= ix < width and 0 <= iy < height:
                inside_total += 1
                r, g, b = pixels[iy * width + ix]
                if max(r, max(g, b)) - min(r, min(g, b)) > ring_sat * 0.8:
                    vivid_inside += 1
        if vivid_inside > inside_total // 2:
            reject("disk")
            continue
        confidence = max(0.0, min(1.0, (1 - rel_err / 0.15) * coverage))
        ring = (cx / width, cy / height, radius / short_side, confidence)
        score = confidence * 0.85 + ring[2] * 0.15
        if score > best_score:
            best_score = score
            best = ring
    return best, {"candidates": candidates, "rejected": rejected}
