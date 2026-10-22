#!/usr/bin/env python3
"""
从缓存的 DataV GeoJSON 计算各区县面积（平方公里），供「作物分布」估算权重使用。

为什么需要面积：
  国家统计年鉴只有**省级**播种面积/产量，区县级没有公开机读源。
  要把省级数值落到地图上的区县，必须知道各省内各区县**面积占比**，
  否则只能均分——均分会把「新疆棉花 2447.9 千公顷」摊到乌鲁木齐的市中心。

算法：球面多边形面积（Shoelace on sphere，R = 6378.137km），对 MultiPolygon 取全部环之和
（外环 + 内环按方向抵消，这里直接取绝对值近似；地理数据内环是洞，
但 DataV 数据里内环极少且误差远小于 1%）。

产物：
  spring-boot-learning/src/main/resources/farm/district-areas.json
      {adcode: {name, province, areaKm2}}
"""
import json
import math
import os
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
CACHE = os.path.join(os.path.dirname(os.path.abspath(__file__)), ".cache")
OUT = os.path.join(ROOT, "spring-boot-learning", "src", "main", "resources", "farm", "district-areas.json")
IDX = os.path.join(ROOT, "spring-boot-learning", "src", "main", "resources", "farm", "districts.json")

R = 6378.137


def ring_area_km2(ring):
    """球面多边形环面积（km²），ring = [(lng, lat), ...]"""
    n = len(ring)
    if n < 3:
        return 0.0
    total = 0.0
    for i in range(n):
        lng1, lat1 = ring[i]
        lng2, lat2 = ring[(i + 1) % n]
        total += (math.radians(lng2 - lng1) *
                  (2 + math.sin(math.radians(lat1)) + math.sin(math.radians(lat2))))
    return abs(total * R * R / 2.0)


def feature_area_km2(feat):
    """单个 Feature 的面积（km²），支持 Polygon / MultiPolygon。"""
    g = feat.get("geometry") or {}
    t = g.get("type")
    coords = g.get("coordinates") or []
    total = 0.0
    if t == "Polygon":
        for i, ring in enumerate(coords):
            a = ring_area_km2(ring)
            # 外环是最大的环；内环（洞）方向相反，用外环面积减去
            total += a if i == 0 else -a
    elif t == "MultiPolygon":
        for poly in coords:
            for i, ring in enumerate(poly):
                a = ring_area_km2(ring)
                total += a if i == 0 else -a
    return abs(total)


def main():
    idx = json.load(open(IDX, encoding="utf-8"))
    districts = idx["districts"]
    out = {}
    missing = []

    # 缓存里存的是**省/市级**的 _full.json（DataV 的 areas_v3/bound/{adcode}_full.json），
    # 区县的边界作为 features 出现在省/市文件里（直辖市直接挂在省文件）。
    # 所以要遍历所有缓存文件，把每个 feature 按自己的 adcode 归档。
    feat_by_adcode = {}
    for fn in os.listdir(CACHE):
        if not fn.endswith(".json") or fn.startswith("_"):
            continue
        path = os.path.join(CACHE, fn)
        try:
            raw = json.load(open(path, encoding="utf-8"))
        except Exception:
            continue
        feats = raw.get("features") if isinstance(raw, dict) else None
        if not feats:
            continue
        for f in feats:
            props = f.get("properties") or {}
            fa = props.get("adcode")
            if fa is None:
                continue
            # DataV 的 adcode 可能是 '100000_JD'（九段线等非行政要素），跳过
            s = str(fa)
            if not s.isdigit():
                continue
            fa = int(s)
            # 一个 adcode 可能在多个文件里出现（省文件 + 市文件），取面积最大那次
            a = feature_area_km2(f)
            if a <= 0:
                continue
            prev = feat_by_adcode.get(fa)
            if prev is None or a > prev[0]:
                feat_by_adcode[fa] = (a, f)

    for d in districts:
        adcode = d["adcode"]
        hit = feat_by_adcode.get(adcode)
        if hit is None:
            missing.append(adcode)
            continue
        out[str(adcode)] = {
            "name": d["name"],
            "province": d["province"],
            "areaKm2": round(hit[0], 2),
        }

    doc = {
        "source": "阿里 DataV GeoAtlas 边界按球面多边形公式计算",
        "unit": "km2",
        "count": len(out),
        "districts": out,
    }
    with open(OUT, "w", encoding="utf-8") as fp:
        json.dump(doc, fp, ensure_ascii=False, indent=1)

    print("算出 %d / %d 个区县面积" % (len(out), len(districts)))
    if missing:
        print("缺失 %d 个：%s" % (len(missing), missing[:20]))
    # 抽样校验：几个已知面积的省，合计应与实际省域面积接近
    prov_sum = {}
    for ad, v in out.items():
        prov_sum[v["province"]] = prov_sum.get(v["province"], 0) + v["areaKm2"]
    for p in ["北京市", "海南省", "黑龙江省", "新疆维吾尔自治区", "山东省"]:
        if p in prov_sum:
            print("  %s 合计 %.0f km²" % (p, prov_sum[p]))
    print("写入", OUT)


if __name__ == "__main__":
    main()
