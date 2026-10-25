#!/usr/bin/env python3
"""
作物精灵图预处理（2026-10-24，22 种作物版）。

**为什么另起一个脚本**（而不是改 prep_hort.py）：
`prep_hort.py` 处理的是「文件名带时间戳的平铺 PNG」→ 靠前缀匹配映射到 8 个通用形态族。
现在每种作物都有**专属贴图**，源文件自然按作物分目录：
    hort/src/<cropId>/young.png
    hort/src/<cropId>/ripe.png
目录结构本身就是映射关系，比文件名匹配更稳（改名不会丢映射）。

🔴 **抠图算法已抽到共享核心 `cutout_core`**（2026-10-24）。
    三条管线共用同一套算法，参数改动只需改一处：
      本文件      → 22 种作物专属图（44 张）
      prep_legacy.py → 10 张旧图（rice/wheat/corn/soybean + tree/grass_tuft）
      prep_hort.py   → 8 张通用族图（fallback 链备份）
    完整算法说明与实测数据见 cutout_core.py 的模块文档。

输出：
  public/farm/tex/web/plant_<cropId>_<stage>.png   （RGBA 精灵）
  public/farm/tex/web/sprites.json                  （宽高比实测值，供前端 SPRITE_ASPECT 用）

🔴 **输出宽高比必须实测**（不能估值）：
前端十字面片按 SPRITE_ASPECT 决定几何比例，估值偏差会让整株作物**比例失真**
（贴图没变形，但几何被拉伸）。所以这个脚本的输出 json 是唯一权威来源。

⚠️ **每次重跑 albedo 后必须跟两步**（否则前后端会不一致）：
  ① `python scripts/farm-assets/prep_crops_normal.py`   （法线图由 albedo 派生）
  ② `python scripts/farm-assets/sync_sprite_aspect.py`  （实测宽高比同步进 config.js）
"""
import json
import os

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "hort", "src")
OUT = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "web")

# 🔴 **提高到 768**：原 560 在 1600 视口近景下已经能看出像素锯齿。
#   作物是画面主体（不是背景板），分辨率直接决定"精细程度"。
#   768 是收益/体积的平衡点：PNG 体积约翻倍，但单株只有 ~1200 个实例、
#   GPU 显存里是同一张贴图，显存不涨，只涨首次下载体积。
TARGET_H = 768

# 22 种作物 → 各自的 stage 目录名
STAGES = ("young", "ripe")


# 🔴 **抠图算法已抽到共享核心 `cutout_core`**（2026-10-24）。
#
#   之前本文件自带一份实现，导致**两条管线行为会走偏**：
#     本文件 → 22 种作物专属图（hort/src/<crop>/{young,ripe}.png）
#     prep_legacy.py → 10 张旧图（src/plant_*.png）
#     prep_hort.py    → 8 张通用族图（hort/*.png）
#   三条管线共用同一套算法，参数改动只需改一处。
#
#   完整算法说明与实测数据见 cutout_core.py 的模块文档。
from cutout_core import cutout  # noqa: F401  （直接转发给共享实现）
from cutout_core import merge_sprites_json


def main():
    os.makedirs(OUT, exist_ok=True)
    aspects = {}
    missing = []

    if not os.path.isdir(SRC):
        print("源目录不存在：", SRC)
        return

    for crop_id in sorted(os.listdir(SRC)):
        crop_dir = os.path.join(SRC, crop_id)
        if not os.path.isdir(crop_dir):
            continue
        for stage in STAGES:
            src = os.path.join(crop_dir, "%s.png" % stage)
            if not os.path.isfile(src):
                missing.append("%s/%s" % (crop_id, stage))
                continue
            img = cutout(src)
            w, h = img.size
            nh = TARGET_H
            nw = max(1, round(w * nh / h))
            img = img.resize((nw, nh), Image.LANCZOS)
            key = "plant_%s_%s" % (crop_id, stage)
            dst = os.path.join(OUT, "%s.png" % key)
            img.save(dst, optimize=True)
            aspects[key] = round(nw / nh, 3)
            print("%-28s %4dx%-4d  aspect=%.3f  %6.1fKB"
                  % (key, nw, nh, aspects[key], os.path.getsize(dst) / 1024))

    # 合并写入 sprites.json（保留非作物条目，如 plant_tree / plant_grass_tuft）
    merged = merge_sprites_json(OUT, aspects)

    print("\n=== SPRITE_ASPECT 追加/更新 %d 条（sprites.json 共 %d 条）==="
          % (len(aspects), len(merged)))
    print(json.dumps(aspects, indent=2, ensure_ascii=False))
    if missing:
        print("\n⚠️ 缺失素材 %d 张：" % len(missing))
        for m in missing:
            print("   -", m)


if __name__ == "__main__":
    main()
