#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把「残留像素」标红输出诊断图，肉眼看是白底残留还是主体误判。

用法：
    python scripts/farm-assets/diag_residual_map.py plant_tree plant_grass_tuft
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TEX_DIR = ROOT / "frontend-learning/public/farm/tex/web"
OUT_DIR = ROOT / "docs/farm-shots/crops"

ALPHA_CUTOFF = {
    "cabbage": 0.26, "tomato": 0.28, "cucumber": 0.28, "chrysanthemum": 0.24,
    "chili": 0.42, "eggplant": 0.42, "yardlong_bean": 0.40, "radish": 0.40,
    "strawberry": 0.38, "watermelon": 0.42, "melon": 0.42, "grape": 0.40,
    "pear": 0.40, "sunflower": 0.44, "tulip": 0.46, "rose": 0.40,
    "millet": 0.46, "potato": 0.46,
    "rice": 0.46, "wheat": 0.44, "corn": 0.42, "soybean": 0.42,
}


def cutoff_for(name):
    body = "_".join(name.split("_")[1:])
    for crop, cut in ALPHA_CUTOFF.items():
        if body == crop or body.startswith(crop + "_"):
            return cut
    return 0.40


def main():
    names = sys.argv[1:] or ["plant_tree", "plant_grass_tuft"]
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for name in names:
        p = TEX_DIR / (name + ".png")
        if not p.exists():
            print("[X] 不存在: %s" % p)
            continue
        im = Image.open(p).convert("RGBA")
        a = np.asarray(im).astype(np.int16)
        alpha = a[:, :, 3]
        r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
        cut = cutoff_for(name)
        visible = alpha >= int(round(cut * 255))
        lum = 0.299 * r + 0.587 * g + 0.114 * b
        gb = g - b
        mx = np.maximum(np.maximum(r, g), b)
        mn = np.minimum(np.minimum(r, g), b)
        sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1), 0.0)

        residue = visible & ((gb < 55) & (lum > 140))
        residue = residue | (visible & (sat < 0.10) & (mx > 175))

        vis = a.copy()
        vis[residue] = [255, 0, 0, 255]
        out = Image.fromarray(vis.astype(np.uint8), "RGBA")
        # 铺到白底上，便于看边界
        bg = Image.new("RGBA", out.size, (255, 255, 255, 255))
        bg.alpha_composite(out)
        dst = OUT_DIR / ("diag-resid-%s.png" % name)
        bg.convert("RGB").save(dst)
        n = int(residue.sum())
        print("%-24s 残留 %6d px  可见 %6d px  占比 %6.2f%%  -> %s" % (
            name, n, int(visible.sum()), n * 100.0 / max(int(visible.sum()), 1), dst.name))


if __name__ == "__main__":
    main()
