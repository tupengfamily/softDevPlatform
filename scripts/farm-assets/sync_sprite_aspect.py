#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把 sprites.json 的实测宽高比同步进前端 config.js 的 SPRITE_ASPECT。

背景：
    prep_crops.py 每跑一次都会重算贴图的实测宽高比并写入
    frontend-learning/public/farm/tex/web/sprites.json；
    而前端 frontend-learning/src/components/farm/scene/config.js 里的
    SPRITE_ASPECT 是**手工拷贝**过去的，两边一旦不同步，
    十字面片的几何比例就会与贴图真实比例偏差 —— 贴图没变形，是几何被拉伸。

    历史教训（2026-10-24）：albedo 重跑后 potato_young 的 aspect
    从 1.224 变成 1.228，前端还留着旧值，肉眼看是「土豆苗被压扁」。
    所以每次重跑 albedo 都必须跑一次本脚本。

用法：
    python scripts/farm-assets/sync_sprite_aspect.py
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JSON_PATH = ROOT / "frontend-learning/public/farm/tex/web/sprites.json"
CONFIG_PATH = ROOT / "frontend-learning/src/components/farm/scene/config.js"


def main() -> int:
    if not JSON_PATH.exists():
        print("[X] 找不到 sprites.json: %s" % JSON_PATH)
        return 1
    if not CONFIG_PATH.exists():
        print("[X] 找不到 config.js: %s" % CONFIG_PATH)
        return 1

    aspects = json.loads(JSON_PATH.read_text(encoding="utf-8"))
    if not isinstance(aspects, dict):
        print("[X] sprites.json 不是对象")
        return 1

    # 过滤掉非数值项，防止把对象写进前端
    aspects = {k: round(float(v), 3) for k, v in aspects.items()
               if isinstance(v, (int, float)) and not isinstance(v, bool)}

    src = CONFIG_PATH.read_text(encoding="utf-8")

    # 定位 export const SPRITE_ASPECT = { ... } 块
    m = re.search(r"(export const SPRITE_ASPECT = \{)(.*?)(\n\})", src, re.S)
    if not m:
        print("[X] 在 config.js 里找不到 SPRITE_ASPECT 块")
        return 1

    body = []
    for key in sorted(aspects.keys()):
        # 与 config.js 现有风格一致：key 不加引号（都是合法标识符）
        if not re.match(r"^[A-Za-z_$][A-Za-z0-9_$]*$", key):
            print("[X] key 不是合法标识符，无法无引号写入: %s" % key)
            return 1
        body.append('  %s: %s,' % (key, aspects[key]))
    new_block = m.group(1) + "\n" + "\n".join(body) + m.group(3)
    new_src = src[:m.start()] + new_block + src[m.end():]

    if new_src != src:
        CONFIG_PATH.write_text(new_src, encoding="utf-8")
        print("[OK] SPRITE_ASPECT 已同步 %d 条" % len(aspects))
    else:
        print("[==] SPRITE_ASPECT 已是最新（%d 条，无需改动）" % len(aspects))

    # 反查：config.js 里有没有 sprites.json 之外的残留 key
    block = re.search(r"export const SPRITE_ASPECT = \{(.*?)\n\}", new_src, re.S).group(1)
    cfg_keys = set(re.findall(r"^\s*([A-Za-z_$][A-Za-z0-9_$]*)\s*:", block, re.M))
    extra = cfg_keys - set(aspects.keys())
    missing = set(aspects.keys()) - cfg_keys
    if extra:
        print("[!] config.js 多了这些 key（应清理）: %s" % ", ".join(sorted(extra)))
    if missing:
        print("[!] config.js 缺这些 key（应补）: %s" % ", ".join(sorted(missing)))
    if not extra and not missing:
        print("[OK] key 集合完全一致（%d 条）" % len(cfg_keys))
    return 0


if __name__ == "__main__":
    sys.exit(main())
