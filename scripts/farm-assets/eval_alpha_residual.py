#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
按「渲染可见口径」统计抠图残留。

背景：
    抠图残留有两种口径，含义完全不同：
      ① 源图口径 —— 统计整张源图里「本该被抠掉却没抠掉」的白底像素占比。
         这会包含画面四周一大圈本来就该抠掉的纯白背景，
         数字天然很大，不代表「看起来脏」。
      ② 渲染可见口径 —— 只看**会被 alphaTest 保留下来**的像素
         （alpha >= ALPHA_CUTOFF）里面，还有多少是白底/阴影色。
         这才是玩家真正能看到的「脏」。

🔴 **判据必须是「与源图背景色的距离」，不能用绝对颜色阈值**（2026-10-24 修正）。

    旧判据 `(gb<55) & (lum>140)` 会把**金黄色的小麦穗**（RGB 191,166,120）
    误判成"白底残留" → 报出 14.09% 的假残留，实际主体好好的。

    背景是**每张图自己的**灰白/暖白渐变，各图不同（有的偏冷白、有的偏米白）。
    所以正确判据是：**该像素与「这张源图的背景参考色」有多接近**。
    背景参考色取源图四角的均值（与抠图算法同源）。

    这个判据**不依赖任何抠图参数** → 没有循环论证。

    ⚠️ 主体损失另用 `eval_subject_loss.py` 单独测（方向相反，别混）。

用法：
    python scripts/farm-assets/eval_alpha_residual.py
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
TEX_DIR = ROOT / "frontend-learning/public/farm/tex/web"
SRC_DIR = ROOT / "frontend-learning/public/farm/tex/hort/src"
LEGACY_SRC = ROOT / "scripts/farm-assets/src"
HORT_DIR = ROOT / "frontend-learning/public/farm/tex/hort"

# ALPHA_CUTOFF 的镜像（与 config.js 保持一致）
ALPHA_CUTOFF = {
    "cabbage": 0.26, "tomato": 0.28, "cucumber": 0.28, "chrysanthemum": 0.24,
    "chili": 0.42, "eggplant": 0.42, "yardlong_bean": 0.40, "radish": 0.40,
    "strawberry": 0.38, "watermelon": 0.42, "melon": 0.42, "grape": 0.40,
    "pear": 0.40, "sunflower": 0.44, "tulip": 0.46, "rose": 0.40,
    "millet": 0.46, "potato": 0.46,
    "rice": 0.46, "wheat": 0.44, "corn": 0.42, "soybean": 0.42,
}

# 与背景参考色的距离阈值（RGB 欧氏距离）。
# 背景渐变会略有色偏，投影阴影会更暗 —— 60 覆盖「亮背景 + 中灰阴影」。
BG_DIST = 60


def cutoff_for(name):
    """从 plant_xxx_yyy 推出 crop key，再查 ALPHA_CUTOFF。"""
    parts = name.split("_")
    if parts[0] != "plant":
        return 0.4
    body = "_".join(parts[1:])
    for crop, cut in ALPHA_CUTOFF.items():
        if body == crop or body.startswith(crop + "_"):
            return cut
    return 0.4


def collect_sources():
    """{产物名: 源图路径} —— 用于取背景参考色。"""
    pairs = {}
    if SRC_DIR.is_dir():
        for crop_dir in sorted(SRC_DIR.iterdir()):
            if not crop_dir.is_dir():
                continue
            for stage in ("young", "ripe"):
                p = crop_dir / ("%s.png" % stage)
                if p.exists():
                    pairs["plant_%s_%s" % (crop_dir.name, stage)] = p
    if LEGACY_SRC.is_dir():
        for p in sorted(LEGACY_SRC.glob("plant_*.png")):
            pairs[p.stem] = p
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
                    pairs["plant_" + v] = p
                    break
    return pairs


def bg_reference(src_path):
    """取源图四角 24×24 均值作为背景参考色（与抠图算法同源）。"""
    im = Image.open(src_path).convert("RGB")
    a = np.asarray(im).astype(np.float32)
    h, w, _ = a.shape
    k = min(24, h // 4, w // 4)
    corners = np.concatenate([
        a[:k, :k].reshape(-1, 3), a[:k, -k:].reshape(-1, 3),
        a[-k:, :k].reshape(-1, 3), a[-k:, -k:].reshape(-1, 3),
    ])
    return corners.mean(axis=0)


def main():
    if not TEX_DIR.exists():
        print("[X] 找不到贴图目录: %s" % TEX_DIR)
        return 1

    srcs = collect_sources()
    files = sorted(p for p in TEX_DIR.glob("plant_*.png") if not p.name.endswith("_n.png"))
    if not files:
        print("[X] 没有找到任何 plant_*.png")
        return 1

    rows = []
    for p in files:
        name = p.stem
        im = Image.open(p).convert("RGBA")
        a = np.asarray(im).astype(np.int16)
        alpha = a[:, :, 3]
        r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]

        cut = cutoff_for(name)
        visible = alpha >= int(round(cut * 255))          # 渲染后会被保留的像素
        total_visible = int(visible.sum())
        if total_visible == 0:
            rows.append((name, cut, 0, 0.0, 0.0))
            continue

        # 🔴 与「这张图的背景参考色」的距离（不依赖任何抠图参数）
        src = srcs.get(name)
        if src is None:
            # 找不到源图 → 退回保守的绝对判据
            lum = 0.299 * r + 0.587 * g + 0.114 * b
            gb = g - b
            residue = visible & ((gb < 45) & (lum > 170))
        else:
            ref = bg_reference(src)
            dist = np.sqrt((r - ref[0]) ** 2 + (g - ref[1]) ** 2 + (b - ref[2]) ** 2)
            residue = visible & (dist < BG_DIST)

        n_res = int(residue.sum())
        pct = n_res * 100.0 / total_visible
        pct_all = n_res * 100.0 / (im.width * im.height)
        rows.append((name, cut, total_visible, pct, pct_all))

    rows.sort(key=lambda x: -x[3])
    print("=== 渲染可见口径残留（与该图源图背景色的距离 < %d）===" % BG_DIST)
    print("%-30s %6s %10s %10s %10s" % ("texture", "cutoff", "visible", "resid%", "ofAll%"))
    for name, cut, tv, pct, pct_all in rows:
        flag = " <<<" if pct > 0.5 else ""
        print("%-30s %6.2f %10d %9.3f%% %9.3f%%%s" % (name, cut, tv, pct, pct_all, flag))

    pcts = [r[3] for r in rows]
    print("")
    print("平均 %.3f%%  最大 %.3f%% (%s)  >0.5%% 的有 %d 组" % (
        sum(pcts) / len(pcts), max(pcts), rows[0][0], sum(1 for p in pcts if p > 0.5)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
