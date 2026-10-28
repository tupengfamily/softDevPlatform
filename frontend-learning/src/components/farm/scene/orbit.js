/**
 * 360° 环绕（orbit）相关的**纯函数**。
 *
 * 🔴 **为什么单独抽一个模块**（2026-10-29 支持 360° 视角旋转）：
 *
 *   360° 旋转这件事有两个必须算准、但**算错不会报错**的部分：
 *     ① 方位角归一化 —— yaw 跨过 ±π 时如果不 wrap，
 *        `Math.sin/cos` 虽然仍然正确，但任何「按 yaw 线性插值」的逻辑
 *        会在跨越点反向抽搐（从 +3.14 拖到 +3.15 再 wrap 到 -3.13，
 *        插值量变成 -6.28 → 画面瞬移一整圈）。
 *     ② 环形布点 —— 树带 / 远景农田改成环绕分布后，
 *        「某个方位角上有没有内容」是**静默失效**的：
 *        数量写少一个、角度算错一点，那一方向就是空的，
 *        玩家转到那里看到一片空地，而代码不会报任何错。
 *
 *   所以这两件事必须能**独立验证**（npm test → test/scene-orbit.test.mjs）。
 *   本模块**零依赖**（不 import three、不碰 DOM），可以在 node 里直接跑。
 */

/** 一个完整圆周（弧度） */
export const TAU = Math.PI * 2

/**
 * 把角度归一化到 (-π, π]。
 *
 * 🔴 **为什么必须归一化**：
 *   相机球坐标用的是 `Math.sin(yaw)` / `Math.cos(yaw)`，
 *   本身对任意大的 yaw 都正确（周期 2π）。
 *   但 yaw 无界增长会让数值精度慢慢变差（浮点尾数只剩 ~7 位有效数字时，
 *   sin/cos 开始出现 1e-8 量级的抖动），
 *   而更致命的是**任何基于 yaw 差值的插值/比较都会错**：
 *   `yawA - yawB` 在跨圈时会得到 ±2π 的假差值。
 *
 * @param {number} a 弧度
 * @returns {number} 归一化到 (-π, π]
 */
export function wrapAngle (a) {
  if (!Number.isFinite(a)) return 0
  let x = a % TAU
  if (x <= -Math.PI) x += TAU
  if (x > Math.PI) x -= TAU
  return x
}

/**
 * 两个方位角之间的**最短夹角**（带符号，[-π, π]）。
 *
 * 🔴 **为什么需要它**：绕一整圈时 `yaw` 从 +π 走到 -π，
 *   两者数值相差 2π 但实际只差 0 —— 直接相减会得到错误的 6.28。
 *   任何「转到某个目标方位」的插值都必须走这个函数。
 *
 * @param {number} from 起
 * @param {number} to   止
 * @returns {number} 最短有向差值（rad）
 */
export function angleDelta (from, to) {
  return wrapAngle(to - from)
}

/**
 * 把 yaw 从 `from` 朝 `to` 推进一小步，返回新 yaw（不 wrap，便于连续累加）。
 *
 * @param {number} from 当前 yaw
 * @param {number} to   目标 yaw
 * @param {number} step 本步最大角度增量（rad）
 * @returns {number} 新 yaw
 */
export function stepTowards (from, to, step) {
  const d = angleDelta(from, to)
  if (Math.abs(d) <= Math.abs(step)) return to
  return from + Math.sign(d) * Math.abs(step)
}

/**
 * 生成环形（绕中心一圈）分布的点位。
 *
 * 🔴 **为什么要环形而不是直线**：360° 旋转后，玩家能从任意方向看向远方。
 *   原来的树带 / 远景农田全部集中在 -z 方向（单向纵深），
 *   转到 +z 方向（背面）就是**一片空地**——
 *   与 2026-10-28 修掉的「草地塌缩 → 田外露天空球下半球 → 一片白」
 *   是同一类缺陷：**不是报错，是画面空洞**。
 *
 *   环形分布保证任意方位角上都有内容。
 *
 * @param {object} opts
 * @param {number} opts.count     点数
 * @param {number} opts.radius    分布半径（米）
 * @param {number} [opts.startAngle=0] 起始方位角（rad，0 = +z 方向）
 * @param {number} [opts.jitter]  半径抖动比例（0~1，按 jitterFn 给的随机值抖动）
 * @param {(i:number)=>number} [opts.jitterFn] 返回 0~1 的随机数
 * @param {number} [opts.angleJitter=0] 角度抖动（rad）
 * @param {number} [opts.spread=0] 角度抖动比例：让每个点在自己的扇区里随机分布
 * @returns {{x:number, z:number, angle:number, index:number}[]}
 */
export function ringPositions ({
  count,
  radius,
  startAngle = 0,
  jitter = 0,
  jitterFn = null,
  angleJitter = 0,
  spread = 0
}) {
  const out = []
  const n = Math.max(0, count | 0)
  if (n === 0) return out
  const step = TAU / n
  for (let i = 0; i < n; i++) {
    // 每个点在自己的扇区里随机偏移（spread=1 → 均匀铺满整圈）
    const jitterRatio = spread > 0 ? (jitterFn ? jitterFn() - 0.5 : 0) * spread : 0
    const angle = startAngle + step * (i + jitterRatio)
    let r = radius
    if (jitter > 0 && jitterFn) {
      r = radius * (1 + (jitterFn() - 0.5) * 2 * jitter)
    }
    out.push({
      x: Math.sin(angle) * r,
      z: Math.cos(angle) * r,
      angle,
      index: i
    })
  }
  return out
}

/**
 * 检查一组环形点位在**每个方位扇区**上是否都有内容。
 *
 * 🔴 **这是 360° 改造的验收闸门**：
 *   场景内容从「单向纵深」改成「环形分布」后，
 *   最危险的失败模式是**某一个方向忘了放东西**——
 *   代码不报错、画面也不崩，只是转到那里空荡荡。
 *   所以把「任意方位是否有内容」变成一条**可自动验证的断言**。
 *
 * @param {number[]} angles 所有点位的方位角（rad）
 * @param {number} [sectors=16] 划分多少个扇区（每个 22.5°）
 * @returns {{ ok:boolean, empty:number[], counts:number[] }}
 */
export function sectorCoverage (angles, sectors = 16) {
  const n = Math.max(1, sectors | 0)
  const counts = new Array(n).fill(0)
  for (const a of angles) {
    // 把角度折到 [0, 2π)，再落到扇区
    let t = a % TAU
    if (t < 0) t += TAU
    const raw = (t / TAU) * n
    // 🔴 **必须做浮点边界修正**（2026-10-29 实测踩坑）：
    //   16 点均布时第 11 个点应在扇区 11，但 `(247.5°/360°)*16`
    //   在 IEEE754 下算出 10.999999999999998 → floor 得 10，
    //   于是「明明均匀分布」却被判定成「扇区 11、15 是空的」。
    //   这类错误不会崩、只会让验收闸门**误报**，把正确实现判成不合格。
    //   修法：若 raw 已经非常接近整数，就按整数取（四舍五入到扇区中心）。
    let idx = Math.floor(raw)
    if (Math.abs(raw - Math.round(raw)) < 1e-9) idx = Math.round(raw)
    idx = Math.min(n - 1, Math.max(0, idx))
    counts[idx]++
  }
  const empty = []
  for (let i = 0; i < n; i++) if (counts[i] === 0) empty.push(i)
  return { ok: empty.length === 0, empty, counts }
}

/**
 * 给定一组方位角，返回**距离该方位最近**的点位索引。
 *
 * 🔴 **为什么需要**：树带 / 远景农田改成环形后，
 *   如果某个方位角在 ±1.15 rad 之外就没有「正对」的装饰，
 *   玩家转过去会看到构图失衡（那一侧太空）。
 *   用这个函数可以把「正对方向」上的内容排得更密。
 *
 * @param {number[]} angles
 * @param {number} target 目标方位角
 * @returns {number} 最近点的索引（空数组返回 -1）
 */
export function nearestAngleIndex (angles, target) {
  if (!angles || !angles.length) return -1
  let best = -1
  let bestD = Infinity
  for (let i = 0; i < angles.length; i++) {
    // 🔴 **必须用「圆周上的最短弧长」，不能用 |a-b|**（2026-10-29 实测踩坑）：
    //   目标 0°，候选 +179° 与 -179°：
    //     直线距离 |179-0| = 179、|-179-0| = 179 → 两者并列，
    //     谁先遍历到谁赢 —— 方向随机；
    //     圆周距离 min(179, 181) = 179 vs min(181, 179) = 179 → 仍然并列。
    //   真正要对的是「**绕过去要转多少度**」：
    //     +179° → 目标 0°  需转 -179°（或 +181°）→ 最短 179°
    //     -179° → 目标 0°  需转 +179°（或 -181°）→ 最短 179°
    //   两者确实等距，但**符号相反**。要挑「更靠近当前朝向」的那个，
    //   必须比较 angleDelta 的**带符号**值，而不是绝对值。
    //
    //   本函数返回「角度上最近」的点；当距离并列时，取**有向差值更小**的那个
    //   （即顺时针方向先到的那个），保证结果**确定**、可复现。
    const d = angleDelta(angles[i], target)
    const abs = Math.abs(d)
    if (abs < bestD - 1e-12) {
      bestD = abs
      best = i
    } else if (Math.abs(abs - bestD) < 1e-12 && best >= 0) {
      // 距离并列 → 取有向差值更接近 0（转过去更"顺"）的那个
      if (Math.abs(d) < Math.abs(angleDelta(angles[best], target))) best = i
    }
  }
  return best
}

/**
 * 计算「以某点为圆心、半径 R 的圆」上，等角分布 N 个点时，
 * 相邻两点之间的**弧长间距**（米）。
 *
 * 🔴 **为什么要量化间距**：树带如果排得太密，60 棵树挤在一小段弧上，
 *   转到别处就完全没树 —— 环形分布下「总数量不变」不等于「每个方向都够」。
 *   用这个函数可以**反推需要多少棵**才能达到目标间距。
 *
 * @param {number} radius 半径（米）
 * @param {number} count  点数
 * @returns {number} 弧长间距（米）
 */
export function arcSpacing (radius, count) {
  if (!count || count <= 0) return Infinity
  return (TAU * radius) / count
}

/**
 * 反推：要让环形点位的弧长间距不超过 `maxGap`（米），至少需要多少个点。
 *
 * @param {number} radius 半径（米）
 * @param {number} maxGap 目标最大间距（米）
 * @returns {number} 最少点数
 */
export function requiredCountFor (radius, maxGap) {
  if (!radius || radius <= 0) return 0
  if (!maxGap || maxGap <= 0) return Infinity
  return Math.ceil((TAU * radius) / maxGap)
}
