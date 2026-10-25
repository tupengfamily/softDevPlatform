#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
旧图（10 张）抠图管线 —— 复用 `cutout_core` 的新算法。

🔴 **为什么需要这个脚本**（2026-10-24）：

    `prep_crops.py` 只处理 22 种作物专属图（src: `hort/src/<crop>/{young,ripe}.png`）。
    但还有 10 张图走的是**旧色度法**（`prep.py` 的 `cutout`），产物残留严重：

    | 贴图                  | 渲染可见残留（最终管线前） |
    |----------------------|---------------------------|
    | plant_wheat_ripe      | 37.43%                   |
    | plant_grass_tuft      | 26.49%                   |
    | plant_corn_ripe       | 20.81%                   |
    | plant_corn_young      | 15.62%                   |
    | plant_wheat_young     | 4.89%                    |
    | plant_tree            | 7.17%                    |

    其中 `plant_tree` 和 `plant_grass_tuft` 是**场景装饰物**
    （FarmScene3D.vue 直接引用），残留会直接显示在 3D 场景里。

    旧色度法（`chroma = max-min`，阈值 9~25）的硬伤：
      ① 抓不到带绿调的投影阴影（G≈B 但 sat 0.10~0.25）
      ② 抓不到叶片之间的碎块白底（不与边界连通）
      ③ 素色主体（成熟小麦穗）本身色度低 → 阈值一放就啃主体

    新算法（`cutout_core.cutout`）用「位置约束 × 颜色确认」两段式，
    实测主体损失 0.000%、残留 0.018%（44 组全分辨率）。

输出：
    public/farm/tex/web/plant_<name>.png   （RGBA 精灵，高度 768）
    public/farm/tex/web/sprites.json        （合并实测宽高比，纯数值）
"""
import json
import os

from cutout_core import cutout
from cutout_core import merge_sprites_json

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "src")
OUT = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "web")

TARGET_H = 768

# 源文件名 → 输出名（与 prep_crops.py 的 `plant_<crop>_<stage>` 命名保持同一套）
SPRITES = [
    ("plant_rice_young.png", "plant_rice_young"),
    ("plant_rice_ripe.png", "plant_rice_ripe"),
    ("plant_wheat_young.png", "plant_wheat_young"),
    ("plant_wheat_ripe.png", "plant_wheat_ripe"),
    ("plant_corn_young.png", "plant_corn_young"),
    ("plant_corn_ripe.png", "plant_corn_ripe"),
    ("plant_soybean_young.png", "plant_soybean_young"),
    ("plant_soybean_ripe.png", "plant_soybean_ripe"),
    ("plant_tree.png", "plant_tree"),
    ("plant_grass_tuft.png", "plant_grass_tuft"),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    aspects = {}
    missing = []

    for src_name, out_name in SPRITES:
        src = os.path.join(SRC, src_name)
        if not os.path.isfile(src):
            missing.append(src_name)
            continue
        img = cutout(src, target_h=TARGET_H)
        w, h = img.size
        dst = os.path.join(OUT, "%s.png" % out_name)
        img.save(dst, optimize=True)
        aspects[out_name] = round(w / h, 3)
        print("%-24s %4dx%-4d  aspect=%.3f  %6.1fKB"
              % (out_name, w, h, aspects[out_name], os.path.getsize(dst) / 1024))

    # 合并写入 sprites.json（与另两条管线共用同一份写入逻辑）
    merged = merge_sprites_json(OUT, aspects)

    print("\n=== 更新 sprites.json %d 条（总计 %d 条）===" % (len(aspects), len(merged)))
    if missing:
        print("\n⚠️ 缺失源图 %d 张：" % len(missing))
        for m in missing:
            print("   -", m)


if __name__ == "__main__":
    main()
