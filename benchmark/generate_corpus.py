"""Deterministic synthetic corpus generator for the ring-detection benchmark.

Generates labeled cases on the fly (no binary images committed for synthetic
cases): ring colors x sizes x backgrounds + negative cases. Real encounter
screenshots live under corpus/real/ and are labeled in corpus.json.
"""

import random
from PIL import Image, ImageDraw

W, H = 480, 800  # portrait phone frame; short side = 480

COLORS = {
    "green": (60, 200, 80),
    "yellow": (250, 215, 60),
    "orange": (250, 140, 40),
    "red": (235, 60, 60),
}


def _noise(rng, img, amount):
    px = img.load()
    for y in range(0, H, 2):
        for x in range(0, W, 2):
            n = rng.randint(-amount, amount)
            r, g, b = px[x, y]
            px[x, y] = (
                max(0, min(255, r + n)),
                max(0, min(255, g + n)),
                max(0, min(255, b + n)),
            )
    return img


def background_clean(rng):
    """Desaturated vertical gradient, mild noise."""
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        t = y / H
        base = (int(120 + 40 * t), int(130 + 35 * t), int(150 + 30 * t))
        for x in range(W):
            px[x, y] = base
    return _noise(rng, img, 8)


def background_grass(rng):
    """Light pastel-green patchy texture, like real encounter-screen grass:
    bright (high luminance) so its saturation stays moderate and a vivid ring
    still clears the local-contrast high-pass."""
    img = Image.new("RGB", (W, H))
    px = img.load()
    for y in range(H):
        for x in range(W):
            patch = ((x // 37) * 7 + (y // 29) * 13) % 5
            g = 175 + patch * 10 + rng.randint(-22, 22)
            r = 135 + rng.randint(-22, 22)
            b = 115 + rng.randint(-20, 20)
            px[x, y] = (max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b)))
    return img


def background_busy(rng):
    """Grass base + saturated scenery distractors + decoys (cyan ring, solid disk)."""
    img = background_grass(rng)
    d = ImageDraw.Draw(img)
    # scenery blobs in vivid hues
    for _ in range(40):
        x = rng.randint(0, W - 1)
        y = rng.randint(0, H - 1)
        w, h = rng.randint(15, 90), rng.randint(15, 70)
        col = rng.choice([(200, 120, 40), (150, 60, 180), (60, 90, 200), (190, 190, 60)])
        d.ellipse([x - w // 2, y - h // 2, x + w // 2, y + h // 2], fill=col)
    # cyan ring decoy (hue gate must kill it): top-left quadrant
    cx, cy, r = int(W * 0.28), int(H * 0.30), 55
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(40, 210, 210), width=7)
    # solid red disk decoy (disk/fill gate must kill it): bottom-right quadrant
    cx, cy, r = int(W * 0.72), int(H * 0.72), 48
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(225, 70, 70))
    return _noise(rng, img, 10)


BACKGROUNDS = {
    "clean": background_clean,
    "grass": background_grass,
    "busy": background_busy,
}


def draw_ring(img, cx_frac, cy_frac, radius_frac, color, thickness):
    d = ImageDraw.Draw(img)
    cx, cy = int(W * cx_frac), int(H * cy_frac)
    r = int(min(W, H) * radius_frac)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=thickness)
    return img


def draw_disk(img, cx_frac, cy_frac, radius_frac, color):
    d = ImageDraw.Draw(img)
    cx, cy = int(W * cx_frac), int(H * cy_frac)
    r = int(min(W, H) * radius_frac)
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)
    return img


def make_case(case):
    """Returns a PIL image for a synthetic corpus.json case."""
    rng = random.Random(case["seed"])
    img = BACKGROUNDS[case["background"]](rng)
    kind = case.get("draw")
    if kind == "ring":
        rc = case["ring"]
        img = draw_ring(img, rc["cx"], rc["cy"], rc["radius_frac"],
                        tuple(rc["color"]), rc.get("thickness", 4))
    elif kind == "disk":
        rc = case["ring"]
        img = draw_disk(img, rc["cx"], rc["cy"], rc["radius_frac"], tuple(rc["color"]))
    elif kind == "none":
        pass
    else:
        raise ValueError(f"unknown draw kind: {kind}")
    return img
