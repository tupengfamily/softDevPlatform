/**
 * 地图纯函数自检脚本（**无需测试框架**，Node 直接跑）。
 *
 * <p>用法：
 * <pre>
 *   node test/map-geo.test.mjs
 * </pre>
 *
 * <p>🔴 2026-10-28 新建。项目前端没有引入测试框架（不想为几个纯函数
 * 引入 vitest/jest 全套依赖），但 {@code map/geo.js} 与 {@code map/measure.js}
 * 抽出来之后<b>本来就该能独立验证</b> —— 投影和面积算错是静默失败，
 * 玩家看到的是「点江西命中新疆」或者「测出来 1 亩是 0.9 亩」，没有任何报错。
 *
 * <p>所以这里用 Node 内置断言写一个零依赖脚本，挂在 npm script 里，
 * 提交前跑一次。
 */

import assert from 'node:assert/strict'

import {
  PROJ,
  worldX,
  worldY,
  invWorldX,
  invWorldY,
  pointInGeometry,
  featurePath,
  bboxHit,
  geometryBbox
} from '../src/components/farm/map/geo.js'

import {
  EARTH_R,
  SQM_PER_MU,
  haversine,
  shoelaceArea,
  formatArea,
  formatDistance
} from '../src/components/farm/map/measure.js'

let passed = 0
const failures = []

function ok (name, fn) {
  try {
    fn()
    passed++
  } catch (e) {
    failures.push(`${name}: ${e.message}`)
  }
}

/* ================================================================== *
 * 投影
 * ================================================================== */

ok('worldX / invWorldX 往返', () => {
  assert.ok(Math.abs(invWorldX(worldX(116.4)) - 116.4) < 1e-9)
})

ok('worldY / invWorldY 往返', () => {
  assert.ok(Math.abs(invWorldY(worldY(28.7)) - 28.7) < 1e-9)
})

ok('PROJ.project / unproject 往返', () => {
  const [x, y] = PROJ.project(116.4, 28.7)
  const [lng, lat] = PROJ.unproject(x, y)
  assert.ok(Math.abs(lng - 116.4) < 1e-9, `lng=${lng}`)
  assert.ok(Math.abs(lat - 28.7) < 1e-9, `lat=${lat}`)
})

ok('PROJ.K 为正数（投影比例不能为 0 或负）', () => {
  assert.ok(PROJ.K > 0)
})

ok('PROJ 基准范围包含中国主体', () => {
  // 南海诸岛在基准范围之外（否则整张图纵向被拉长近一半），但世界范围要能装下
  const [x, y] = PROJ.project(116.4, 3.8)
  assert.ok(x > 0 && x < PROJ.base.w, `x=${x}`)
  assert.ok(y >= PROJ.base.h, '南海诸岛应在基准高度之外，但仍在世界范围内')
  // 3.8°N 正好是世界范围的底边（world.h 就是按它算的）
  assert.ok(Math.abs(y - PROJ.world.h) < 1e-6, `y=${y} 应等于 world.h=${PROJ.world.h}`)
})

/* ================================================================== *
 * 命中判定
 * ================================================================== */

const UNIT_SQUARE = {
  type: 'Polygon',
  coordinates: [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]
}

ok('点在多边形内 → 命中', () => {
  assert.equal(pointInGeometry(0.5, 0.5, UNIT_SQUARE), true)
})

ok('点在多边形外 → 不命中', () => {
  assert.equal(pointInGeometry(1.5, 0.5, UNIT_SQUARE), false)
})

ok('内环挖洞：洞内不算命中', () => {
  const withHole = {
    type: 'Polygon',
    coordinates: [
      [[0, 0], [10, 0], [10, 10], [0, 10], [0, 0]],
      [[2, 2], [8, 2], [8, 8], [2, 8], [2, 2]]
    ]
  }
  assert.equal(pointInGeometry(5, 5, withHole), false)
  assert.equal(pointInGeometry(1, 5, withHole), true)
})

ok('geometryBbox 取外环矩形', () => {
  const bb = geometryBbox(UNIT_SQUARE)
  assert.deepEqual(bb, { minLng: 0, maxLng: 1, minLat: 0, maxLat: 1 })
})

ok('bboxHit 判定相交 / 不相交', () => {
  const bb = geometryBbox(UNIT_SQUARE)
  assert.equal(bboxHit(bb, { minLng: 0.5, maxLng: 2, minLat: 0.5, maxLat: 2 }), true)
  assert.equal(bboxHit(bb, { minLng: 5, maxLng: 6, minLat: 5, maxLat: 6 }), false)
})

ok('featurePath 生成闭合路径', () => {
  const d = featurePath(UNIT_SQUARE, PROJ.project)
  assert.ok(d.startsWith('M'), d)
  assert.ok(d.endsWith('Z'), d)
})

/* ================================================================== *
 * 距离
 * ================================================================== */

ok('Haversine：同点距离为 0', () => {
  assert.equal(haversine(116, 28, 116, 28), 0)
})

ok('Haversine：赤道上 1° 经度 ≈ 111.3 km', () => {
  const d = haversine(0, 0, 1, 0)
  assert.ok(Math.abs(d - 111319) < 200, `实际 ${d.toFixed(0)}`)
})

ok('Haversine：经度对称（东西距离相同）', () => {
  const a = haversine(116, 28, 117, 28)
  const b = haversine(117, 28, 116, 28)
  assert.ok(Math.abs(a - b) < 1e-6)
})

ok('Haversine：地球半径常量正确', () => {
  assert.equal(EARTH_R, 6371008.8)
})

/* ================================================================== *
 * 面积
 * ================================================================== */

ok('Shoelace：少于 3 个点面积为 0', () => {
  assert.equal(shoelaceArea([{ lng: 0, lat: 0 }, { lng: 1, lat: 0 }]), 0)
  assert.equal(shoelaceArea([]), 0)
  assert.equal(shoelaceArea(null), 0)
})

ok('Shoelace：1 亩方形算出来就是 1 亩（误差 < 1%）', () => {
  // 以赤道为中心、边长 = sqrt(1亩) 的正方形
  const half = Math.sqrt(SQM_PER_MU) / 2 / 111319.49
  const pts = [
    { lng: -half, lat: -half },
    { lng: half, lat: -half },
    { lng: half, lat: half },
    { lng: -half, lat: half }
  ]
  const area = shoelaceArea(pts)
  assert.ok(
    Math.abs(area - SQM_PER_MU) / SQM_PER_MU < 0.01,
    `算得 ${area.toFixed(1)} m²，期望 ${SQM_PER_MU.toFixed(1)} m²`
  )
})

ok('Shoelace：高纬度面积会缩小（cos² 修正生效）', () => {
  const half = Math.sqrt(SQM_PER_MU) / 2 / 111319.49
  const mk = (lat) => [
    { lng: -half, lat: lat - half },
    { lng: half, lat: lat - half },
    { lng: half, lat: lat + half },
    { lng: -half, lat: lat + half }
  ]
  const eq = shoelaceArea(mk(0))
  const north = shoelaceArea(mk(60))
  assert.ok(north < eq, `60°N 面积 ${north.toFixed(1)} 应小于赤道 ${eq.toFixed(1)}`)
})

/* ================================================================== *
 * 格式化
 * ================================================================== */

ok('formatArea 必须返回对象（不能返回字符串）', () => {
  // 🔴 历史坑：String.prototype 上有个遗留的 sub() 方法（HTML <sub> 标签），
  //   一旦这里返回字符串，调用方读 .sub 拿到的是原生函数源码而不是文案。
  const r = formatArea(666.6667)
  assert.equal(typeof r, 'object')
  assert.equal(typeof r.text, 'string')
  assert.equal(typeof r.sub, 'string')
})

ok('formatArea 无效输入也返回对象', () => {
  assert.equal(typeof formatArea(0), 'object')
  assert.equal(typeof formatArea(-1), 'object')
  assert.equal(typeof formatArea(NaN), 'object')
})

ok('formatArea：1 亩 → "1.00 亩"', () => {
  assert.ok(formatArea(666.6667).text.startsWith('1.00'), formatArea(666.6667).text)
})

ok('formatArea：单位文案可外部传入（i18n）', () => {
  assert.ok(formatArea(666.6667, 'mu').text.includes('mu'))
})

ok('formatDistance：米 / 公里分段', () => {
  assert.ok(formatDistance(500).includes('米'))
  assert.ok(formatDistance(5000).includes('公里'))
  assert.ok(formatDistance(NaN).includes('—'))
})

/* ================================================================== *
 * 结果
 * ================================================================== */

if (failures.length) {
  console.error(`\n❌ ${failures.length} 个用例失败（共 ${passed + failures.length} 个）：\n`)
  failures.forEach((f) => console.error('  ✗ ' + f))
  process.exit(1)
}
console.log(`\n✅ ${passed} 个用例全部通过`)
