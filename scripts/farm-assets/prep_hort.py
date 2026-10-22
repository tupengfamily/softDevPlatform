#!/usr/bin/env python3
"""
园艺作物精灵图预处理：抠背景 → 裁边 → 缩放 → 输出到 public/farm/tex/web。

背景是浅灰白渐变（ImageGen 的 transparent 参数这批没生效），所以不能简单按
"白色阈值"抠图——大白菜的叶球也是浅黄绿，用固定白阈值会把菜心一起抠掉。
做法是「颜色距离 + 边界连通」：
  1. 四角取背景参考色，算每像素到它的距离；
  2. 只把「与画布边界连通」的近背景区域判为背景，植物内部的浅色保留；
  3. 边界带按距离做半透明过渡，避免硬锯齿。
"""
import glob
import json
import os

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "hort")
OUT = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "web")
TARGET_H = 560

# 源文件名 → 输出名（visual_stage）
MAP = {
    "young_Chinese_cabbage": ("leafy_young", "A_single_young_Chinese_cabbage"),
    "mature_Chinese_cabbag": ("leafy_ripe", "A_single_mature_Chinese_cabbag"),
    "young_cucumber_vine": ("vine_young", "A_single_young_cucumber_vine"),
    "mature_cucumber_vine": ("vine_ripe", "A_single_mature_cucumber_vine"),
    "young_tomato_plant": ("bush_young", "A_single_young_tomato_plant_se"),
    "mature_tomato_plant": ("bush_ripe", "A_single_mature_tomato_plant_b"),
    "young_chrysanthemum": ("flower_young", "A_single_young_chrysanthemum_p"),
    "mature_chrysanthemum": ("flower_ripe", "A_single_mature_chrysanthemum"),
}


def cutout(path, soft=70, hard=26):
    im = Image.open(path).convert("RGB")
    a = np.array(im).astype(np.float32)
    h, w, _ = a.shape

    # 背景参考色：四角各取 24×24 均值
    k = 24
    corners = np.concatenate([
        a[:k, :k].reshape(-1, 3), a[:k, -k:].reshape(-1, 3),
        a[-k:, :k].reshape(-1, 3), a[-k:, -k:].reshape(-1, 3),
    ])
    bg = corners.mean(axis=0)

    dist = np.sqrt(((a - bg) ** 2).sum(axis=2))

    # 「可能是背景」的区域：距离小于 soft
    cand = (dist < soft).astype(np.uint8) * 255
    mask = Image.fromarray(cand)
    # 从四边中部 + 四角灌水，只标记与边界连通的候选区
    seeds = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1),
             (w // 2, 0), (w // 2, h - 1), (0, h // 2), (w - 1, h // 2)]
    for s in seeds:
        if mask.getpixel(s) == 255:
            ImageDraw.floodfill(mask, s, 128, thresh=0)
    outside = np.array(mask) == 128

    # alpha：外部全透明；内部按距离软过渡（hard 以下算纯前景）
    alpha = np.full((h, w), 255, dtype=np.float32)
    edge = (~outside) & (dist < soft)
    alpha[outside] = 0
    alpha[edge] = np.clip((dist[edge] - hard) / max(1.0, soft - hard), 0, 1) * 255

    # 收一下边缘：alpha 太低的地方直接清掉，避免出现一圈灰边
    alpha[alpha < 26] = 0

    rgba = np.dstack([a, alpha]).astype(np.uint8)
    out = Image.fromarray(rgba, "RGBA")

    bbox = out.getchannel("A").getbbox()
    if bbox:
        out = out.crop(bbox)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    files = sorted(glob.glob(os.path.join(SRC, "*.png")))
    aspects = {}
    for f in files:
        base = os.path.basename(f)
        hit = None
        for key, (out_name, prefix) in MAP.items():
            if base.startswith(prefix):
                hit = out_name
                break
        if not hit:
            print("skip", base)
            continue
        img = cutout(f)
        w, h = img.size
        nh = TARGET_H
        nw = max(1, round(w * nh / h))
        img = img.resize((nw, nh), Image.LANCZOS)
        dst = os.path.join(OUT, "plant_%s.png" % hit)
        img.save(dst, optimize=True)
        aspects["plant_%s" % hit] = round(nw / nh, 3)
        print("%-22s %s -> %dx%d" % (hit, base[:34], nw, nh))

    print("\nSPRITE_ASPECT 追加：")
    print(json.dumps(aspects, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
