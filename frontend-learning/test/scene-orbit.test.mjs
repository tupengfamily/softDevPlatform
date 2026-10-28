/**
 * 360° 环绕纯函数自检脚本（**无需测试框架**，Node 直接跑）。
 *
 * <p>用法：
 * <pre>
 *   node test/scene-orbit.test.mjs
 * </pre>
 *
 * <p>🔴 2026-10-29 新建。与 map-geo.test.mjs 同理：前端没引入测试框架，
 * 但 {@code scene/orbit.js} 里全是**算错不会报错**的东西
 * （角度归一化、环形布点、扇区覆盖）—— 必须在提交前跑一次。
 */

import assert from 'node:assert/strict'

import {
  TAU,
  wrapAngle,
  angleDelta,
  stepTowards,
  ringPositions,
  sectorCoverage,
  nearestAngleIndex,
  arcSpacing,
  requiredCountFor
} from '../src/components/farm/scene/orbit.js'

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

// ---------------- wrapAngle ----------------

ok('wrapAngle: 0 原样返回', () => {
  assert.equal(wrapAngle(0), 0)
})

ok('wrapAngle: π 边界归到 (-π, π]', () => {
  assert.ok(wrapAngle(Math.PI) <= Math.PI)
  assert.ok(wrapAngle(-Math.PI) > -Math.PI)
})

ok('wrapAngle: 超过一圈折回', () => {
  const a = wrapAngle(Math.PI * 2 + 0.5)
  assert.ok(Math.abs(a - 0.5) < 1e-9, `实际 ${a}`)
})

ok('wrapAngle: 负数折回', () => {
  const a = wrapAngle(-Math.PI * 2 - 0.5)
  assert.ok(Math.abs(a + 0.5) < 1e-9, `实际 ${a}`)
})

ok('wrapAngle: 非有限值退化为 0', () => {
  assert.equal(wrapAngle(NaN), 0)
  assert.equal(wrapAngle(Infinity), 0)
})

ok('wrapAngle: 结果恒在 (-π, π]', () => {
  for (let i = -40; i <= 40; i++) {
    const a = wrapAngle(i * 0.37)
    assert.ok(a > -Math.PI - 1e-12 && a <= Math.PI + 1e-12, `i=${i} a=${a}`)
  }
})

// ---------------- angleDelta ----------------

ok('angleDelta: 跨 ±π 取最短路径', () => {
  // 从 +3.0 到 -3.0：数值差 -6.0，但实际最短是 +0.28
  const d = angleDelta(3.0, -3.0)
  assert.ok(Math.abs(d - 0.2831853) < 1e-6, `实际 ${d}`)
})

ok('angleDelta: 方向性正确', () => {
  assert.ok(angleDelta(0, 1) > 0)
  assert.ok(angleDelta(1, 0) < 0)
})

ok('angleDelta: 同角度为 0', () => {
  assert.equal(angleDelta(2.5, 2.5), 0)
})

// ---------------- stepTowards ----------------

ok('stepTowards: 步长不足时按方向推进', () => {
  const y = stepTowards(0, 1, 0.1)
  assert.ok(Math.abs(y - 0.1) < 1e-9, `实际 ${y}`)
})

ok('stepTowards: 步长足够时直接到达目标', () => {
  assert.equal(stepTowards(0, 1, 2), 1)
})

ok('stepTowards: 跨 ±π 走短路径而不是绕整圈', () => {
  // 3.0 → -3.0，最短是 +0.283
  const y = stepTowards(3.0, -3.0, 0.1)
  assert.ok(y > 3.0 && y < 3.2, `实际 ${y}（不应该跳到负方向绕整圈）`)
})

// ---------------- ringPositions ----------------

ok('ringPositions: 数量正确', () => {
  const pts = ringPositions({ count: 12, radius: 100 })
  assert.equal(pts.length, 12)
})

ok('ringPositions: 每个点都在半径上', () => {
  const pts = ringPositions({ count: 8, radius: 50 })
  for (const p of pts) {
    assert.ok(Math.abs(Math.hypot(p.x, p.z) - 50) < 1e-9)
  }
})

ok('ringPositions: 均匀分布在整圈', () => {
  const pts = ringPositions({ count: 4, radius: 10 })
  // 4 个点应各差 90°
  for (let i = 1; i < 4; i++) {
    assert.ok(Math.abs(Math.abs(angleDelta(pts[i - 1].angle, pts[i].angle)) - Math.PI / 2) < 1e-9)
  }
})

ok('ringPositions: 半径抖动在允许范围内', () => {
  let seed = 0
  const jitterFn = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed / 2147483648
  }
  const pts = ringPositions({ count: 32, radius: 100, jitter: 0.1, jitterFn })
  for (const p of pts) {
    const r = Math.hypot(p.x, p.z)
    assert.ok(r >= 90 && r <= 110, `半径 ${r} 越界`)
  }
})

ok('ringPositions: count=0 返回空数组', () => {
  assert.equal(ringPositions({ count: 0, radius: 100 }).length, 0)
})

ok('ringPositions: spread=1 时角度不超出自己的扇区太多', () => {
  let seed = 7
  const jitterFn = () => {
    seed = (seed * 1103515245 + 12345) % 2147483648
    return seed / 2147483648
  }
  const pts = ringPositions({ count: 8, radius: 100, spread: 1, jitterFn })
  const step = TAU / 8
  for (const p of pts) {
    const off = Math.abs(angleDelta(p.angle, p.index * step))
    assert.ok(off <= step / 2 + 1e-9, `偏移 ${off} 超过扇区半宽 ${step / 2}`)
  }
})

// ---------------- sectorCoverage ----------------

ok('sectorCoverage: 环形均布时无空扇区', () => {
  const pts = ringPositions({ count: 16, radius: 100 })
  const r = sectorCoverage(pts.map((p) => p.angle), 16)
  assert.ok(r.ok, `空扇区 ${r.empty}`)
})

ok('sectorCoverage: 单向布点会报出空扇区', () => {
  // 全部集中在 -z（角度 π 附近）
  const angles = [Math.PI - 0.1, Math.PI, Math.PI + 0.1]
  const r = sectorCoverage(angles, 8)
  assert.ok(!r.ok, '单向布点应该被判定为覆盖不足')
  assert.ok(r.empty.length > 0)
})

ok('sectorCoverage: counts 记录每扇区数量', () => {
  const pts = ringPositions({ count: 16, radius: 100 })
  const r = sectorCoverage(pts.map((p) => p.angle), 16)
  assert.equal(r.counts.length, 16)
  assert.equal(r.counts.reduce((a, b) => a + b, 0), 16)
})

ok('sectorCoverage: 负角度也能正确落扇区', () => {
  // 4 扇区：每扇区 90°。
  //   -90° → 折到 270° → 扇区 3（270°~360°）
  //   +90° → 扇区 1（90°~180°）
  const r = sectorCoverage([-Math.PI / 2, Math.PI / 2], 4)
  assert.equal(r.counts[1], 1, `+90° 应落在扇区 1，实际 counts=${r.counts}`)
  assert.equal(r.counts[3], 1, `-90° 应落在扇区 3，实际 counts=${r.counts}`)
})

// ---------------- nearestAngleIndex ----------------

ok('nearestAngleIndex: 找到最近的点', () => {
  const angles = [0, Math.PI / 2, Math.PI, -Math.PI / 2]
  assert.equal(nearestAngleIndex(angles, 0.1), 0)
  assert.equal(nearestAngleIndex(angles, Math.PI / 2 + 0.05), 1)
})

ok('nearestAngleIndex: 跨 ±π 也正确', () => {
  // 目标 0°，候选 +179° 与 -179° —— 两者到目标的**最短弧长相等**（都是 179°）。
  //   所以这里验的是「不崩、且返回有效索引」，而不是固定返回某一个：
  //   几何上两者真的等距，断言固定索引反而是错的。
  const angles = [Math.PI - 0.05, -Math.PI + 0.05]
  const idx = nearestAngleIndex(angles, 0)
  assert.ok(idx === 0 || idx === 1, `实际 ${idx}`)
  // 而真正靠近的（±0.1）必须被选中
  const near = nearestAngleIndex([Math.PI - 0.05, -Math.PI + 0.05, 0.1], 0)
  assert.equal(near, 2, `实际 ${near}`)
})

ok('nearestAngleIndex: 空数组返回 -1', () => {
  assert.equal(nearestAngleIndex([], 0), -1)
})

// ---------------- arcSpacing / requiredCountFor ----------------

ok('arcSpacing: 周长除以点数', () => {
  assert.ok(Math.abs(arcSpacing(100, 8) - (TAU * 100 / 8)) < 1e-9)
})

ok('arcSpacing: count=0 返回 Infinity', () => {
  assert.equal(arcSpacing(100, 0), Infinity)
})

ok('requiredCountFor: 反推点数与 arcSpacing 互为逆运算', () => {
  const radius = 116
  const maxGap = 9
  const n = requiredCountFor(radius, maxGap)
  assert.ok(arcSpacing(radius, n) <= maxGap + 1e-9)
  assert.ok(arcSpacing(radius, n - 1) > maxGap)
})

ok('requiredCountFor: 半径越大需要的点越多', () => {
  assert.ok(requiredCountFor(140, 8) > requiredCountFor(95, 8))
})

// ---------------- 汇总 ----------------

console.log(`\n  360° 环绕纯函数自检：${passed} 通过，${failures.length} 失败`)
if (failures.length) {
  console.log('\n  失败明细：')
  for (const f of failures) console.log(`   ✗ ${f}`)
  process.exit(1)
}
console.log('  ✔ 全部通过\n')
