/**
 * 地图**几何与投影**的唯一实现（纯函数，无副作用）。
 *
 * <p>🔴 2026-10-28 从 {@code FarmWorldMap.vue}（当时 6719 行）抽出来的。
 * 抽出来的原因不是「文件太长」，而是这些函数<b>根本不依赖 Vue</b>：
 * 投影、射线法命中、路径生成、颜色微扰 —— 全是纯计算。
 *
 * <p><b>为什么必须保持唯一出口</b>：
 * <ul>
 *   <li>投影基准 K 是<b>全局常量</b>（不随显示哪一层而变）。全国视图 / 省视图 /
 *       区县视图共用同一个 K，坐标才连续、缩放才能穿透到区县。</li>
 *   <li>点选命中、标记定位、缩放锚点都共用同一套坐标 ——
 *       一旦出现第二份投影，「点江西命中新疆」这类 bug 立刻回来。</li>
 * </ul>
 *
 * <p>⚠️ 本文件<b>不 import 任何 Vue 东西</b> —— 这是它能脱离组件单测的前提。
 */

/* ================================================================== *
 * 1. Web Mercator 投影
 * ================================================================== */

/** 经度 → 世界 X（0~1） */
export function worldX (lng) {
  return (lng + 180) / 360
}

/** 纬度 → 世界 Y（0~1） */
export function worldY (lat) {
  const r = (lat * Math.PI) / 180
  return 0.5 - Math.log(Math.tan(Math.PI / 4 + r / 2)) / (2 * Math.PI)
}

/** 世界 X → 经度 */
export function invWorldX (wx) {
  return wx * 360 - 180
}

/** 世界 Y → 纬度 */
export function invWorldY (wy) {
  return (Math.atan(Math.sinh(Math.PI * (1 - 2 * wy))) * 180) / Math.PI
}

/**
 * 投影器（**基准范围固定**，全国/省/区县共用同一个 K）。
 *
 * <p>基准范围取中国陆域主体（73°–136°E，18°–54°N）：南海诸岛按实测经纬度
 * 远在 3.8°N，若把它算进基准 bbox 会把整张地图纵向拉长近一半，缩放手感全废。
 * 岛礁本身仍按真实坐标绘制，平移到南方能看到 —— 所以平移边界按「世界范围」
 * 而不是「基准范围」来夹。
 */
export const PROJ = (() => {
  const minLng = 73.0
  const maxLng = 136.0
  const minLat = 18.0
  const maxLat = 54.0
  const wx0 = worldX(minLng)
  const wy0 = worldY(maxLat)
  const W = 1000
  const K = W / (worldX(maxLng) - wx0)
  const H = (worldY(minLat) - wy0) * K
  // 世界范围：向下延伸到南海诸岛（3.8°N），保证平移能看见它们
  const worldH = (worldY(3.8) - wy0) * K
  return {
    base: { w: W, h: H },
    world: { w: W, h: worldH },
    K,
    wx0,
    wy0,
    project: (lng, lat) => [(worldX(lng) - wx0) * K, (worldY(lat) - wy0) * K],
    unproject: (x, y) => [invWorldX(x / K + wx0), invWorldY(y / K + wy0)]
  }
})()

/** 1 个 SVG 单位对应多少度经度 */
export const DEG_PER_UNIT = 360 / PROJ.K

/* ================================================================== *
 * 2. 路径生成
 * ================================================================== */

/** 一个 GeoJSON 环 → SVG path 的 d 属性 */
export function ringPath (ring, project) {
  let d = ''
  for (let i = 0; i < ring.length; i++) {
    const [x, y] = project(ring[i][0], ring[i][1])
    d += (i === 0 ? 'M' : 'L') + x.toFixed(1) + ' ' + y.toFixed(1)
  }
  return d + 'Z'
}

/**
 * 一个 GeoJSON 面要素 → SVG path 的 d 属性。
 *
 * <p>支持 Polygon 与 MultiPolygon 两种。
 */
export function featurePath (geom, project) {
  if (!geom) return ''
  const polys = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates
  let d = ''
  for (const poly of polys) {
    for (const ring of poly) {
      if (ring.length >= 3) d += ringPath(ring, project)
    }
  }
  return d
}

/* ================================================================== *
 * 3. 颜色微扰
 * ================================================================== */

/**
 * 同色区县之间做 ±7% 亮度微扰，避免整省糊成一个色块（纯视觉，不改变语义）。
 *
 * @param {string} hex 十六进制颜色
 * @param {number} k 亮度系数
 * @returns {string}
 */
export function shade (hex, k) {
  if (!hex || hex[0] !== '#') return hex
  const n = parseInt(hex.slice(1), 16)
  const r = Math.min(255, Math.round(((n >> 16) & 255) * k))
  const g = Math.min(255, Math.round(((n >> 8) & 255) * k))
  const b = Math.min(255, Math.round((n & 255) * k))
  return `rgb(${r}, ${g}, ${b})`
}

/**
 * 同 {@link shade}，但带 alpha。
 *
 * <p>卫星/地形底图下，区县色块必须是半透明的，否则实色块会把航拍影像整片盖住。
 * fill 是 inline 绑定的，CSS 的 fill-opacity 覆盖不掉，所以透明度只能在这里算出来。
 */
export function shadeA (hex, k, alpha) {
  if (!hex || hex[0] !== '#') return hex
  const n = parseInt(hex.slice(1), 16)
  const r = Math.min(255, Math.round(((n >> 16) & 255) * k))
  const g = Math.min(255, Math.round(((n >> 8) & 255) * k))
  const b = Math.min(255, Math.round((n & 255) * k))
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/* ================================================================== *
 * 4. 命中判定
 * ================================================================== */

/** 一个 GeoJSON 几何的外包矩形 */
export function geometryBbox (geom) {
  let minLng = 180
  let maxLng = -180
  let minLat = 90
  let maxLat = -90
  const walk = (c) => {
    if (typeof c[0] === 'number') {
      if (c[0] < minLng) minLng = c[0]
      if (c[0] > maxLng) maxLng = c[0]
      if (c[1] < minLat) minLat = c[1]
      if (c[1] > maxLat) maxLat = c[1]
      return
    }
    for (const x of c) walk(x)
  }
  walk(geom.coordinates)
  return { minLng, maxLng, minLat, maxLat }
}

/** 射线法：点是否落在多边形内 */
export function pointInRing (lng, lat, ring) {
  let inside = false
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const xi = ring[i][0]
    const yi = ring[i][1]
    const xj = ring[j][0]
    const yj = ring[j][1]
    if ((yi > lat) !== (yj > lat) &&
        lng < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) {
      inside = !inside
    }
  }
  return inside
}

/**
 * 点是否落在几何内（**含内环挖洞判定**）。
 *
 * <p>先做外环命中，再用内环排除 —— 内环（洞）命中则整个多边形不命中。
 */
export function pointInGeometry (lng, lat, geom) {
  if (!geom) return false
  const polys = geom.type === 'Polygon' ? [geom.coordinates] : geom.coordinates
  for (const poly of polys) {
    if (!poly.length) continue
    if (!pointInRing(lng, lat, poly[0])) continue
    let hole = false
    for (let k = 1; k < poly.length; k++) {
      if (pointInRing(lng, lat, poly[k])) {
        hole = true
        break
      }
    }
    if (!hole) return true
  }
  return false
}

/** 两个外包矩形是否相交 */
export function bboxHit (a, b) {
  return !(a.maxLng < b.minLng || a.minLng > b.maxLng ||
           a.maxLat < b.minLat || a.minLat > b.maxLat)
}
