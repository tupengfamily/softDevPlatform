#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
扫描「背景判据加饱和度上限」对主体损失 / 白底残留的影响。

🔴 **问题背景**（2026-10-24）：

    绿-蓝差（G-B < 55）判据把**黄色系主体**误判成背景：
        wheat_ripe 丢失像素：gb p50=47  sat p50=0.349  lum p50=167
        tulip_ripe 丢失像素：gb p50=49  sat p50=0.321  lum p50=147
        melon_ripe 丢失像素：gb p50=51  sat p50=0.320  lum p50=148
    —— 都是「G-B 差值恰好卡在 55 附近」的高饱和像素。

    而**真正的背景/阴影**特征：
        带绿调的投影阴影 sat p50=0.113（源图叶片之间的白底 sat 最高 0.25）
    → **饱和度**是区分「黄色主体」与「灰白背景」的关键。

    假设：给背景判据加 `sat < SAT_MAX`，能在几乎不损失去白底能力的前提下
         把黄色系主体救回来。

用法：
    python scripts/farm-assets/eval_sat_scan.py
"""
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import cutout_core as cc  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT / "frontend-learning/public/farm/tex/hort/src"
LEGACY_SRC = ROOT / "scripts/farm-assets/src"

ALPHA_CUTOFF = {
    "cabbage": 0.26, "tomato": 0.28, "cucumber": 0.28, "chrysanthemum": 0.24,
    "chili": 0.42, "eggplant": 0.42, "yardlong_bean": 0.40, "radish": 0.40,
    "strawberry": 0.38, "watermelon": 0.42, "melon": 0.42, "grape": 0.40,
    "pear": 0.40, "sunflower": 0.44, "tulip": 0.46, "rose": 0.40,
    "millet": 0.46, "potato": 0.46,
    "rice": 0.46, "wheat": 0.44, "corn": 0.42, "soybean": 0.42,
}

# 采样集：覆盖问题作物（黄色系）+ 对照组（正常绿系）
SAMPLE = [
    ("plant_wheat_ripe", LEGACY_SRC / "plant_wheat_ripe.png", 0.44),
    ("plant_tulip_ripe", SRC_DIR / "tulip/ripe.png", 0.46),
    ("plant_melon_ripe", SRC_DIR / "melon/ripe.png", 0.42),
    ("plant_cabbage_ripe", SRC_DIR / "cabbage/ripe.png", 0.26),
    ("plant_tomato_ripe", SRC_DIR / "tomato/ripe.png", 0.28),
    ("plant_rose_ripe", SRC_DIR / "rose/ripe.png", 0.40),
    ("plant_pear_young", SRC_DIR / "pear/young.png", 0.40),
    ("plant_corn_ripe", LEGACY_SRC / "plant_corn_ripe.png", 0.42),
    ("plant_rice_ripe", LEGACY_SRC / "plant_rice_ripe.png", 0.46),
    ("plant_potato_ripe", SRC_DIR / "potato/ripe.png", 0.46),
    ("plant_chili_ripe", SRC_DIR / "chili/ripe.png", 0.42),
    ("plant_sunflower_ripe", SRC_DIR / "sunflower/ripe.png", 0.44),
]


def flood_border(mask):
    h, w = mask.shape
    reach = np.zeros((h, w), dtype=bool)
    seeds = []
    for x in range(w):
        if mask[0, x]:
            seeds.append(x)
        if mask[h - 1, x]:
            seeds.append((h - 1) * w + x)
    for y in range(h):
        if mask[y, 0]:
            seeds.append(y * w)
        if mask[y, w - 1]:
            seeds.append(y * w + w - 1)
    if not seeds:
        return reach
    stack = np.array(seeds, dtype=np.int64)
    reach.reshape(-1)[stack] = True
    offs = ((-1, 0), (1, 0), (0, -1), (0, 1))
    while stack.size:
        idx = stack
        ys, xs = idx // w, idx % w
        nxt = []
        for dy, dx in offs:
            ny, nx = ys + dy, xs + dx
            ok = (ny >= 0) & (ny < h) & (nx >= 0) & (nx < w)
            if not ok.any():
                continue
            flat = ny[ok] * w + nx[ok]
            take = mask.reshape(-1)[flat] & (~reach.reshape(-1)[flat])
            if not take.any():
                continue
            sel = flat[take]
            reach.reshape(-1)[sel] = True
            nxt.append(sel)
        stack = np.concatenate(nxt) if nxt else np.array([], dtype=np.int64)
    return reach


def eval_one(path, cut, sat_max):
    """跑一次抠图，返回 (主体损失%, 渲染可见残留%)。"""
    src = np.asarray(Image.open(path).convert("RGB")).astype(np.float32)
    r, g, b = src[:, :, 0], src[:, :, 1], src[:, :, 2]
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1.0), 0.0)
    gb = g - b
    lum = 0.299 * r + 0.587 * g + 0.114 * b

    plant = (gb >= 55) | (sat >= 0.30)
    subject = plant & (~flood_border(plant))
    n_subject = int(subject.sum())
    if n_subject == 0:
        return None

    _, alpha = cc.cutout(str(path), return_alpha=True, sat_max=sat_max)
    a = alpha.astype(np.int16)
    lost = subject & (a < int(round(cut * 255)))
    loss_pct = int(lost.sum()) * 100.0 / n_subject

    # 渲染可见残留（源图坐标系近似：alpha >= cutoff 的像素里的白底）
    vis = a >= int(round(cut * 255))
    residue = vis & ((gb < 55) & (lum > 140) & (sat < sat_max))
    residue = residue | (vis & (sat < 0.10) & (mx > 175))
    nv = int(vis.sum())
    resid_pct = int(residue.sum()) * 100.0 / max(nv, 1)
    return loss_pct, resid_pct, int(lost.sum()), int(residue.sum())


def main():
    cc.SAT_MAX = 1.0  # 先测原始
    print("采样 %d 张（覆盖黄色系主体问题 + 绿系对照）\n" % len(SAMPLE))
    results = {}
    for sat_max in (1.0, 0.35, 0.32, 0.30, 0.28, 0.25):
        print(">>> SAT_MAX = %.2f" % sat_max)
        tot_loss = 0.0
        tot_resid = 0.0
        worst_loss = (0, "")
        worst_resid = (0, "")
        for name, path, cut in SAMPLE:
            if not path.exists():
                print("    %-26s [源图缺失]" % name)
                continue
            r = eval_one(path, cut, sat_max)
            if r is None:
                continue
            loss, resid, nl, nr = r
            tot_loss += loss
            tot_resid += resid
            if loss > worst_loss[0]:
                worst_loss = (loss, name)
            if resid > worst_resid[0]:
                worst_resid = (resid, name)
            print("    %-26s 主体损失 %6.2f%%  残留 %6.2f%%" % (name, loss, resid))
        n = len(SAMPLE)
        print("  → 平均主体损失 %.3f%% (最差 %.2f%% %s) | 平均残留 %.3f%% (最差 %.2f%% %s)\n"
              % (tot_loss / n, worst_loss[0], worst_loss[1],
                 tot_resid / n, worst_resid[0], worst_resid[1]))
        results[sat_max] = (tot_loss / n, tot_resid / n, worst_loss, worst_resid)

    print("=== 汇总 ===")
    print("%-10s %12s %12s %16s %16s" % ("SAT_MAX", "平均主体损失", "平均残留", "最差主体损失", "最差残留"))
    for sat_max, (ml, mr, wl, wr) in results.items():
        print("%-10.2f %11.3f%% %11.3f%% %13.2f%% %13.2f%%" % (
            sat_max, ml, mr, wl[0], wr[0]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
