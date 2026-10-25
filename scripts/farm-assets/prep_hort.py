#!/usr/bin/env python3
"""
园艺作物精灵图预处理：抠背景 → 裁边 → 缩放 → 输出到 public/farm/tex/web。

🔴 **2026-10-24：抠图算法已换成共享核心 `cutout_core`**。

    原来的「颜色距离 + 边界连通」算法残留严重（渲染可见口径）：
        plant_leafy_ripe    28.80%
        plant_leafy_young   23.24%
        plant_flower_young  12.28%
        plant_vine_ripe      9.85%
        plant_bush_ripe      8.91%
    硬伤：
      ① **投影阴影抠不掉** —— 阴影压在植物后面、不与画布边界连通 → 整片保留；
      ② **叶片之间的碎块白底抠不掉** —— 被叶子切碎，不连通到边界；
      ③ 素色主体（成熟白菜球）色度低，阈值一放就啃主体。

    新算法（`cutout_core.cutout`）用「位置约束 × 颜色确认」两段式：
        闭运算（只用于连通性判定）→ flood 出 reachable
        → outside = reachable & (绿-蓝差 < 55) & (亮度 > 140)
        → 再补投影阴影 / 孔洞 / 近白边缘环 → RGB 颜色扩散
    实测：leafy_ripe 残留 28.80% → **0.17%**，主体零损失。

⚠️ 这 8 张族图是 **fallback 链的备份**（22 种专属图 404 时才用）。
   专属图目前 44 张全齐 → 正常情况下不会触发，
   但备份质量也得过关 —— 降级路径不能是"脏图"。
"""
import glob
import json
import os

from PIL import Image

from cutout_core import cutout as core_cutout
from cutout_core import merge_sprites_json

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "hort")
OUT = os.path.join(HERE, "..", "..", "frontend-learning", "public", "farm", "tex", "web")

# ⚠️ **统一到 768**（与 `prep_crops.py` / `prep_legacy.py` 一致）。
#   原来这里是 560 —— 但族图是**降级链的备份**，
#   玩家在低配设备上看到降级画面时，画质不该比正常路径更差。
TARGET_H = 768

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


def cutout(path, target_h=None, soft=70, hard=26):
    """
    抠图（转发到共享核心 `cutout_core`）。

    ⚠️ `soft` / `hard` 两个参数保留只是**兼容旧调用签名**，
       新算法不再使用它们（阈值已内化为「绿-蓝差 + 亮度」判据）。
    """
    return core_cutout(path, target_h=target_h)


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
        img = cutout(f, target_h=TARGET_H)
        w, h = img.size
        dst = os.path.join(OUT, "plant_%s.png" % hit)
        img.save(dst, optimize=True)
        aspects["plant_%s" % hit] = round(w / h, 3)
        print("%-22s %s -> %dx%d" % (hit, base[:34], w, h))

    print("\nSPRITE_ASPECT 追加：")
    print(json.dumps(aspects, indent=2, ensure_ascii=False))

    # 合并写入 sprites.json（与另两条管线共用同一份写入逻辑）
    merged = merge_sprites_json(OUT, aspects)
    print("sprites.json 共 %d 条（纯数值）" % len(merged))


if __name__ == "__main__":
    main()
