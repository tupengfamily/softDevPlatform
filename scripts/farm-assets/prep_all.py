#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
作物贴图**总管线**（2026-10-24）。

按顺序跑完所有步骤，保证 albedo / 法线图 / 前端宽高比三者一致：

    ① prep_crops.py       22 种作物专属图（44 张）  src: hort/src/<crop>/{young,ripe}.png
    ② prep_legacy.py      10 张旧图                   src: src/plant_*.png
    ③ prep_hort.py         8 张通用族图（fallback）   src: hort/*.png
    ④ prep_crops_normal.py 法线图（由 albedo 派生）    → plant_*_n.png
    ⑤ sync_sprite_aspect.py 实测宽高比 → config.js 的 SPRITE_ASPECT

🔴 **为什么必须有这个总入口**（实测踩过的坑）：

    只跑 ① 会导致**三处不一致**：
      · 法线图还是旧的（albedo 变了，轮廓变了，法线对不上 → 边缘立体边框）
      · `SPRITE_ASPECT` 还是旧的（面片几何比例与贴图真实比例偏差 → 植株被拉扁）
      · 族图 / 旧图还是旧算法的产物（残留 20~37% → 叶片之间白块）

    实测：potato_young 的 aspect 在重跑后从 1.224 变成 1.228，
    前端还留着旧值 → 肉眼可见「土豆苗被压扁」。

用法：
    python scripts/farm-assets/prep_all.py
    python scripts/farm-assets/prep_all.py --skip-normal   # 法线图没变时可跳过
"""
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
PY = sys.executable

STEPS = [
    ("① 22 种作物专属图", "prep_crops.py"),
    ("② 10 张旧图", "prep_legacy.py"),
    ("③ 8 张通用族图", "prep_hort.py"),
    ("④ 法线图", "prep_crops_normal.py"),
    ("⑤ 同步 SPRITE_ASPECT", "sync_sprite_aspect.py"),
]

# 🔴 **同步必须在三条 albedo 管线都跑完之后**（2026-10-24 修）。
#   原顺序把 `sync_sprite_aspect.py` 放在最后是对的，但 `prep_hort.py`
#   原本**不写 sprites.json** → 族图的 aspect 永远同步不进去。
#   现在 `prep_hort.py` 已改为共用 `merge_sprites_json()`，三条管线都会写。
#   ⚠️ **不要提前同步** —— 那样只会拿到当时已生成的条目。


def run(title, script):
    print("\n" + "=" * 70)
    print(">>> %s  (%s)" % (title, script))
    print("=" * 70)
    t = time.time()
    r = subprocess.run([PY, os.path.join(HERE, script)], cwd=HERE)
    if r.returncode != 0:
        print("\n[X] %s 失败（退出码 %d），管线中断" % (script, r.returncode))
        return False
    print("--- %s 完成（%.1fs）---" % (script, time.time() - t))
    return True


def main():
    skip_normal = "--skip-normal" in sys.argv
    steps = STEPS[:-1] if skip_normal else STEPS
    if skip_normal:
        print("⚠️ 已指定 --skip-normal：跳过法线图重跑")
        # 只同步 aspect
        steps = [s for s in steps if s[1] != "prep_crops_normal.py"] + [
            ("⑤ 同步 SPRITE_ASPECT", "sync_sprite_aspect.py")]

    for title, script in steps:
        if not run(title, script):
            return 1
    print("\n" + "=" * 70)
    print("✅ 全部管线完成")
    print("=" * 70)
    return 0


if __name__ == "__main__":
    sys.exit(main())
