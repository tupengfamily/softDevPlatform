#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检测**主体损失**：源图里明确是植物的像素，被抠图算法抠掉了多少。

🔴 **为什么必须单独测**（2026-10-24）：

    `eval_alpha_residual.py` 统计的是「白底残留」，但白底残留高有**两种**情况：
      ① 白底真的没抠干净 → 视觉脏（要**放宽**抠除）
      ② 白色的**主体**（花瓣高光、成熟小麦穗、白色瓜霜）
         被误判成背景抠掉了 → 视觉是"植物缺了一块"（更严重！要**收紧**）

    两者在「残留」指标上都表现为「有白像素」，处理方向完全相反。

🔴 **坐标系必须一致**（2026-10-24 实测踩坑）：

    第一版用「源图掩码按 bbox 比例映射到产物坐标」→ **错位**，
    甜瓜报出 6.19% 主体损失，而它真实值是 **0.000%**（已知地面真值）。

    ✅ 正确做法：让 `cutout_core.cutout` **额外返回源图坐标系的 alpha 掩码**
       （`return_alpha=True`），在源图坐标系直接比对，零错位。

    主体判据（不依赖人工标注）：
      · 绿-蓝差 G-B >= 55（植物色）**或** 饱和度 >= 0.30（花果等非绿主体）
      · 且该像素**不与画布边界连通**（连通的是背景里的彩色噪点/水印）
      → 这就是「确定是主体」的像素

用法：
    python scripts/farm-assets/eval_subject_loss.py
"""
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cutout_core import cutout  # noqa: E402
from cutout_core import _pot_table  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
SRC_DIR = ROOT / "frontend-learning/public/farm/tex/hort/src"
LEGACY_SRC = ROOT / "scripts/farm-assets/src"
HORT_DIR = ROOT / "frontend-learning/public/farm/tex/hort"

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


def flood_border(mask):
    """从边界灌水，返回与边界连通的像素（numpy int64 栈，避免 OOM）。"""
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
        ys = idx // w
        xs = idx % w
        nxt = []
        for dy, dx in offs:
            ny = ys + dy
            nx = xs + dx
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


def pot_mask(name, h, w):
    """
    盆坨区域掩码（标注表驱动）—— 盆区像素**不算主体**。

    🔴 **为什么必须排除**（2026-10-26）：
        盆/土坨是**暗褐色**（实测 rgb ≈ [28,18,14]，sat ≈ 0.64），
        正好落进「sat >= 0.30」的主体判据 → 被算成「该保留的植物像素」。
        而第 9 步已经把它们**故意抠掉了** → 主体损失指标会**虚报**
        （实测 eggplant_young 会报出 ~13% 的「损失」，其实是盆坨）。

        → 主体判据必须先扣掉盆区，否则这个指标对 6 张带盆贴图完全失真。
    """
    entry = _pot_table().get(name)
    if not entry:
        return None
    top_pct = float(entry.get("pot_top_pct", 0))
    if top_pct <= 0:
        return None
    y_top = int(round(h * top_pct / 100.0))
    if y_top <= 0 or y_top >= h:
        return None
    m = np.zeros((h, w), dtype=bool)
    m[y_top:, :] = True
    return m


def analyze(name, src_path):
    src = np.asarray(Image.open(src_path).convert("RGB")).astype(np.float32)
    h, w, _ = src.shape
    r, g, b = src[:, :, 0], src[:, :, 1], src[:, :, 2]
    mx = np.maximum(np.maximum(r, g), b)
    mn = np.minimum(np.minimum(r, g), b)
    sat = np.where(mx > 0, (mx - mn) / np.maximum(mx, 1.0), 0.0)
    gb = g - b

    plant = (gb >= 55) | (sat >= 0.30)
    subject = plant & (~flood_border(plant))
    # 🔴 盆区不是主体 → 排除（否则带盆贴图的主体损失是虚高）
    pm = pot_mask(name, h, w)
    if pm is not None:
        subject = subject & (~pm)
    n_subject = int(subject.sum())
    if n_subject == 0:
        return None

    # 在**源图坐标系**重跑抠图，取 alpha 掩码
    # （key 由 cutout_core.derive_key 从路径推导 → 与 prep_crops.py 同口径）
    _, alpha = cutout(str(src_path), return_alpha=True)
    cut = cutoff_for(name)
    lost = subject & (alpha < int(round(cut * 255)))
    n_lost = int(lost.sum())
    return name, n_subject, n_lost, n_lost * 100.0 / n_subject, cut


def collect():
    pairs = []
    if SRC_DIR.is_dir():
        for crop_dir in sorted(SRC_DIR.iterdir()):
            if not crop_dir.is_dir():
                continue
            for stage in ("young", "ripe"):
                p = crop_dir / ("%s.png" % stage)
                if p.exists():
                    pairs.append(("plant_%s_%s" % (crop_dir.name, stage), p))
    if LEGACY_SRC.is_dir():
        for p in sorted(LEGACY_SRC.glob("plant_*.png")):
            pairs.append((p.stem, p))
    if HORT_DIR.is_dir():
        MAP = {
            "A_single_young_Chinese_cabbage": "leafy_young",
            "A_single_mature_Chinese_cabbag": "leafy_ripe",
            "A_single_young_cucumber_vine_s": "vine_young",
            "A_single_mature_cucumber_vine_": "vine_ripe",
            "A_single_young_tomato_plant_se": "bush_young",
            "A_single_mature_tomato_plant_b": "bush_ripe",
            "A_single_young_chrysanthemum_p": "flower_young",
            "A_single_mature_chrysanthemum_": "flower_ripe",
        }
        for p in sorted(HORT_DIR.glob("*.png")):
            for k, v in MAP.items():
                if p.name.startswith(k):
                    pairs.append(("plant_" + v, p))
                    break
    return pairs


def main():
    rows = []
    for name, src in collect():
        r = analyze(name, src)
        if r:
            rows.append(r)
    if not rows:
        print("[X] 没有可分析的贴图")
        return 1

    rows.sort(key=lambda x: -x[3])
    print("=== 主体损失（源图坐标系：明确是植物、但被抠掉的像素占比）===")
    print("%-30s %6s %10s %10s %8s" % ("texture", "cutoff", "subjectPx", "lostPx", "loss%"))
    for name, n_sub, n_lost, pct, cut in rows:
        flag = " <<<" if pct > 0.5 else ""
        print("%-30s %6.2f %10d %10d %7.3f%%%s" % (name, cut, n_sub, n_lost, pct, flag))

    pcts = [r[3] for r in rows]
    print("")
    print("平均 %.3f%%  最大 %.3f%% (%s)  >0.5%% 的有 %d 组"
          % (sum(pcts) / len(pcts), max(pcts), rows[0][0], sum(1 for p in pcts if p > 0.5)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
