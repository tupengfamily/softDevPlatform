#!/usr/bin/env python3
"""
抓取中国行政区划边界（阿里 DataV GeoAtlas），简化后供前端地图与后端作物推算使用。

产物：
  frontend-learning/public/geo/cn.json        全国省级边界（首屏）
  frontend-learning/public/geo/p{adcode}.json 该省全部地级市边界（省级视图下钻时加载）
  frontend-learning/public/geo/c{adcode}.json 该市全部区县边界（继续下钻时加载）
  spring-boot-learning/src/main/resources/farm/districts.json
                                              区县索引：adcode/name/层级/省/市/经纬度

为什么不直接引用在线地址：
  1. 生产环境不该依赖第三方 CDN 的可用性；
  2. 原始数据每省 100–600KB，全国 40MB+，必须简化；
  3. 后端做「区县 → 农业区」映射需要离线索引，不能靠前端回传。
"""
import json
import math
import os
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "https://geo.datav.aliyun.com/areas_v3/bound/{adcode}_full.json"
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
OUT_GEO = os.path.join(ROOT, "frontend-learning", "public", "geo")
OUT_IDX = os.path.join(ROOT, "spring-boot-learning", "src", "main", "resources", "farm", "districts.json")

# 台港澳按规范加「中国」前缀（仅展示名，adcode 不变）
NAME_FIX = {
    710000: "中国台湾",
    810000: "中国香港",
    820000: "中国澳门",
}

TOLERANCE = 0.006  # 度，约 600m；区县级显示足够
MIN_RING_POINTS = 5


CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")


MISSING = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache", "_missing.json")


def load_missing():
    if os.path.exists(MISSING):
        with open(MISSING, encoding="utf-8") as fp:
            return set(json.load(fp))
    return set()


def save_missing(s):
    with open(MISSING, "w", encoding="utf-8") as fp:
        json.dump(sorted(s), fp)


def fetch(adcode):
    """
    带磁盘缓存：脚本会重跑多次调参，没必要每次重打 370+ 请求。

    <p>空表缓存要区分两种含义：真 404（不设区的市）记进 _missing.json；
    其余空表只可能是上次被限流写坏的，必须重抓——否则一次 403 就让全国
    少掉三分之一的区县，而且后续重跑永远补不回来。
    """
    os.makedirs(CACHE, exist_ok=True)
    path = os.path.join(CACHE, "%d.json" % adcode)
    if os.path.exists(path):
        with open(path, encoding="utf-8") as fp:
            cached = json.load(fp)
        if cached.get("features") or int(adcode) in load_missing():
            return cached
    url = BASE.format(adcode=adcode)
    req = urllib.request.Request(url, headers={"User-Agent": "farm-geo-builder/1.0"})
    with urllib.request.urlopen(req, timeout=45) as r:
        raw = r.read().decode("utf-8")
    with open(path, "w", encoding="utf-8") as fp:
        fp.write(raw)
    return json.loads(raw)


def fetch_children(adcode):
    """
    取下级行政区。

    <p>只把 **HTTP 404** 当作"这级不存在"并缓存（不设区的市：中山、儋州、嘉峪关、
    新疆兵团各市）。超时 / 5xx / 429 一律重试后抛出——否则一次网络抖动就会把
    整个市的下辖区县永久记成空表（第一版就踩了这个坑，全国少了 990 个区县）。
    """
    last = None
    for attempt in range(5):
        try:
            return fetch(adcode)["features"]
        except urllib.error.HTTPError as e:
            if e.code == 404:
                os.makedirs(CACHE, exist_ok=True)
                with open(os.path.join(CACHE, "%d.json" % int(adcode)), "w", encoding="utf-8") as fp:
                    fp.write('{"type":"FeatureCollection","features":[]}')
                miss = load_missing()
                miss.add(int(adcode))
                save_missing(miss)
                return []
            last = e
        except Exception as e:
            last = e
        # 403 是 DataV 的限流（并发一高就触发），必须退避重试；
        # 只有 404 才代表「这一级不存在」。
        time.sleep(1.8 * (attempt + 1))
    raise RuntimeError("adcode %s 抓取失败: %s" % (adcode, last))


def perp_dist(p, a, b):
    (px, py), (ax, ay), (bx, by) = p, a, b
    dx, dy = bx - ax, by - ay
    if dx == 0 and dy == 0:
        return math.hypot(px - ax, py - ay)
    t = ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def simplify_ring(ring, tol=TOLERANCE):
    """Douglas-Peucker。环较小（<40 点）时保留，避免小岛被抹掉。"""
    if len(ring) <= 40:
        return ring
    keep = [False] * len(ring)
    keep[0] = keep[-1] = True
    stack = [(0, len(ring) - 1)]
    while stack:
        i, j = stack.pop()
        if j <= i + 1:
            continue
        dmax, idx = 0.0, -1
        for k in range(i + 1, j):
            d = perp_dist(ring[k], ring[i], ring[j])
            if d > dmax:
                dmax, idx = d, k
        if idx != -1 and dmax > tol:
            keep[idx] = True
            stack.append((i, idx))
            stack.append((idx, j))
    out = [p for p, k in zip(ring, keep) if k]
    return out if len(out) >= MIN_RING_POINTS else ring[:]


def round_ring(ring):
    return [[round(x, 4), round(y, 4)] for x, y in ring]


def simplify_geom(geom):
    if not geom:
        return geom
    t = geom["type"]
    if t == "Polygon":
        rings = [round_ring(simplify_ring(r)) for r in geom["coordinates"]]
        return {"type": "Polygon", "coordinates": rings}
    if t == "MultiPolygon":
        polys = []
        for poly in geom["coordinates"]:
            rings = [round_ring(simplify_ring(r)) for r in poly]
            # 丢掉面积过小的碎块（<3 点无法成面）
            if rings and len(rings[0]) >= 4:
                polys.append(rings)
        return {"type": "MultiPolygon", "coordinates": polys}
    return geom


def norm_name(adcode, name):
    try:
        return NAME_FIX.get(int(adcode), name)
    except (TypeError, ValueError):
        return name


def int_adcode(adcode):
    """adcode 正常是 6 位数字；九段线等辅助要素是 '100000_JD' 这类字符串。"""
    try:
        return int(adcode)
    except (TypeError, ValueError):
        return None


def main():
    os.makedirs(OUT_GEO, exist_ok=True)
    os.makedirs(os.path.dirname(OUT_IDX), exist_ok=True)

    print("[1/4] 全国省级…")
    cn = fetch(100000)
    provinces = cn["features"]
    for f in provinces:
        p = f["properties"]
        p["name"] = norm_name(p["adcode"], p["name"])
        f["geometry"] = simplify_geom(f["geometry"])
    cn["features"] = [
        f for f in provinces
        if f.get("geometry") and int_adcode(f["properties"].get("adcode")) is not None
    ]
    with open(os.path.join(OUT_GEO, "cn.json"), "w", encoding="utf-8") as fp:
        json.dump(cn, fp, ensure_ascii=False, separators=(",", ":"))
    print("    省级 %d 个，%.0f KB" % (len(cn["features"]), os.path.getsize(os.path.join(OUT_GEO, "cn.json")) / 1024))

    print("[2/4] 各省下辖市 / 直辖市区县…")
    prov_children = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        futs = {}
        for f in cn["features"]:
            ac = int_adcode(f["properties"]["adcode"])
            futs[ac] = ex.submit(fetch_children, ac)
        for f in cn["features"]:
            ac = int_adcode(f["properties"]["adcode"])
            try:
                prov_children[ac] = futs[ac].result()
            except Exception as e:
                print("    ! %s 下钻失败，按省级单位收录: %s" % (f["properties"]["name"], e))
                prov_children[ac] = []

    # 直辖市 / 特别行政区：省级 children 已经是区县
    city_tasks = []
    districts = []
    # 省级下级（地级市）要素，按省聚合 → p{省adcode}.json
    prov_layer = {}
    for f in cn["features"]:
        pac = int_adcode(f["properties"]["adcode"])
        pname = f["properties"]["name"]
        children = prov_children.get(pac, [])
        if not children:
            # 台湾等无下级边界数据的省级单位：省自身作为最小可选单位
            districts.append((f, pac, pname, pname))
            print("    · %s 无下级，按省级单位收录" % pname)
            continue
        levels = {c["properties"].get("level") for c in children}
        if levels <= {"district"}:
            # 直辖市：children 即区县，本身就是省级下辖的最小面。
            # 它的「省级下级层」就是这些区县，不再往下钻。
            for c in children:
                districts.append((c, pac, pname, pname))
            prov_layer[pac] = children
        else:
            for c in children:
                city_tasks.append((c, pac, pname))
            prov_layer[pac] = children

    print("    待抓取市/州 %d 个（省级子项已得区县 %d 个）" % (len(city_tasks), len(districts)))

    print("[3/4] 各市下辖区县…")
    results = {}
    with ThreadPoolExecutor(max_workers=4) as ex:
        futs = {}
        for c, pac, pname in city_tasks:
            ac = int_adcode(c["properties"]["adcode"])
            futs[ac] = ex.submit(fetch_children, ac)
        for c, pac, pname in city_tasks:
            ac = int_adcode(c["properties"]["adcode"])
            cname = c["properties"]["name"]
            kids = futs[ac].result()
            if not kids:
                # 不设区的市（中山、儋州、嘉峪关、新疆兵团各市）没有下级边界，
                # 它自己就是「区县级」单位，直接收进来，否则这些地方无法建农场。
                districts.append((c, pac, pname, cname))
            for k in kids:
                districts.append((k, pac, pname, cname))
            results[ac] = kids

    # ---- 输出层 1：p{省adcode}.json = 该省的地级市边界 ----
    # 直辖市没有地级市层，它的 p 文件放自己的区县（保持老行为）
    def feat_item(f, extra=None):
        """统一的 feature 构造：属性 + 简化后的几何。"""
        p = f["properties"]
        ac = int_adcode(p["adcode"])
        if ac is None:
            return None
        geom = simplify_geom(f.get("geometry"))
        if not geom:
            return None
        center = p.get("center") or p.get("centroid") or [0, 0]
        props = {
            "adcode": ac,
            "name": norm_name(ac, p["name"]),
            "lng": round(float(center[0]), 4),
            "lat": round(float(center[1]), 4),
        }
        if extra:
            props.update(extra)
        return {"type": "Feature", "properties": props, "geometry": geom}

    total = 0
    prov_files = 0
    for pac, feats in prov_layer.items():
        out = []
        for f in feats:
            it = feat_item(f)
            if it:
                out.append(it)
        if not out:
            continue
        path = os.path.join(OUT_GEO, "p%d.json" % pac)
        with open(path, "w", encoding="utf-8") as fp:
            json.dump({"type": "FeatureCollection", "features": out}, fp,
                      ensure_ascii=False, separators=(",", ":"))
        prov_files += 1
        total += os.path.getsize(path)

    # ---- 输出层 2：c{市adcode}.json = 该市下辖区县边界 ----
    # 地图「省级视图」点地级市 → 加载该文件显示其下辖区县，
    # 实现「全国 → 省 → 市 → 区县」四级下钻。
    city_files = 0
    for cac, kids in results.items():
        out = []
        for k in kids:
            it = feat_item(k)
            if it:
                out.append(it)
        if not out:
            continue
        path = os.path.join(OUT_GEO, "c%d.json" % cac)
        with open(path, "w", encoding="utf-8") as fp:
            json.dump({"type": "FeatureCollection", "features": out}, fp,
                      ensure_ascii=False, separators=(",", ":"))
        city_files += 1
        total += os.path.getsize(path)

    # ---- 区县索引（后端作物推算用）----
    idx = []
    for feat, pac, pname, cname in districts:
        p = feat["properties"]
        ac = int_adcode(p["adcode"])
        if ac is None:
            continue
        center = p.get("center") or p.get("centroid") or [0, 0]
        idx.append({
            "adcode": ac,
            "name": norm_name(ac, p["name"]),
            "province": pname,
            "city": cname,
            "lng": round(float(center[0]), 4),
            "lat": round(float(center[1]), 4),
        })

    idx.sort(key=lambda d: d["adcode"])
    with open(OUT_IDX, "w", encoding="utf-8") as fp:
        json.dump({"version": 1, "tolerance": TOLERANCE, "districts": idx}, fp,
                  ensure_ascii=False, separators=(",", ":"))
    print("[4/4] 完成：%d 省文件 + %d 市文件，合计 %.1f MB；索引 %d 条 %.0f KB"
          % (prov_files, city_files, total / 1024 / 1024,
             len(idx), os.path.getsize(OUT_IDX) / 1024))


if __name__ == "__main__":
    main()
