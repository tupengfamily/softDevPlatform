/**
 * 地图**量算**的唯一实现（纯函数，无副作用）。
 *
 * <p>🔴 2026-10-28 从 {@code FarmWorldMap.vue} 抽出来的。
 *
 * <p><b>抽出来的是「计算」不是「交互」</b>：
 * 落点、清除、模式切换这些仍然留在组件里（它们要动组件状态），
 * 而<b>距离 / 面积的数学</b>是纯函数，可以独立存在、独立验证。
 *
 * <p>⚠️ <b>面积口径容易写错</b>：直接在经纬度上做 Shoelace 得到的是「度²」，
 * 必须换算。本文件用与地图一致的 Web Mercator 投影算有符号面积，
 * 再乘该纬度处的 {@code cos(lat)} 平方还原到真实面积 ——
 * 这是 Web Mercator 面积计算的常规修正手法，小区尺度（几公里）内误差 &lt; 0.1%。
 */

/** 地球半径（米，WGS84 平均值） */
export const EARTH_R = 6371008.8

/** 1 亩 = 666.667 平方米 */
export const SQM_PER_MU = 666.6667

/**
 * 两点球面距离（Haversine）。
 *
 * @param {number} lon1 起经度
 * @param {number} lat1 起纬度
 * @param {number} lon2 止经度
 * @param {number} lat2 止纬度
 * @returns {number} 距离（米）
 */
export function haversine (lon1, lat1, lon2, lat2) {
  const toRad = (d) => (d * Math.PI) / 180
  const dLat = toRad(lat2 - lat1)
  const dLon = toRad(lon2 - lon1)
  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) * Math.cos(toRad(lat2)) *
    Math.sin(dLon / 2) * Math.sin(dLon / 2)
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
  return EARTH_R * c
}

/**
 * 多边形面积（Shoelace + Web Mercator 局部修正）。
 *
 * <p>真实面积 = 墨卡托面积 × cos²(中心纬度)。
 *
 * @param {Array<{lng:number, lat:number}>} pts 多边形顶点（经纬度）
 * @returns {number} 面积（平方米）
 */
export function shoelaceArea (pts) {
  if (!pts || pts.length < 3) return 0
  // 投影到 Web Mercator 的「米」空间（EPSG:3857 球面半径按地球半径缩放）
  const merc = pts.map((p) => {
    const r = (p.lat * Math.PI) / 180
    return {
      x: EARTH_R * p.lng * Math.PI / 180,
      y: EARTH_R * Math.log(Math.tan(Math.PI / 4 + r / 2))
    }
  })
  let sum = 0
  for (let i = 0; i < merc.length; i++) {
    const a = merc[i]
    const b = merc[(i + 1) % merc.length]
    sum += a.x * b.y - b.x * a.y
  }
  const mercArea = Math.abs(sum) / 2
  // 中心纬度：面积修正因子
  const cLat = pts.reduce((a, p) => a + p.lat, 0) / pts.length
  const k = Math.cos((cLat * Math.PI) / 180)
  return mercArea * k * k
}

/**
 * 距离格式化：小于 1km 用米，否则用公里。
 *
 * @param {number} m 距离（米）
 * @returns {string}
 */
export function formatDistance (m) {
  if (!Number.isFinite(m)) return '—'
  if (m < 1000) return `${m.toFixed(1)} 米`
  return `${(m / 1000).toFixed(3)} 公里`
}

/**
 * 面积格式化：同时给亩 / 平方米 / 公顷三种口径。
 *
 * <p><b>永远返回对象，不返回裸字符串</b>：调用方会直接读 {@code .text} / {@code .sub}，
 * 而 {@code String.prototype} 上有个遗留的 {@code sub()} 方法（HTML 的 {@code <sub>} 标签）——
 * 一旦这里返回字符串 {@code '—'}，{@code measureResult.sub} 取到的就不是文案，
 * 而是 {@code function sub() { [native code] }} 这段原生函数源码。
 *
 * @param {number} sqm 面积（平方米）
 * @param {string} [muUnit='亩'] 「亩」的单位文案（走 i18n）
 * @returns {{text: string, sub: string}}
 */
export function formatArea (sqm, muUnit = '亩') {
  if (!Number.isFinite(sqm) || sqm <= 0) {
    return { text: '—', sub: '面积过小或无效' }
  }
  const mu = sqm / SQM_PER_MU
  return {
    text: `${mu.toFixed(2)} ${muUnit}`,
    sub: `${sqm.toFixed(0)} 平方米 · ${(sqm / 10000).toFixed(3)} 公顷`
  }
}
