/**
 * 天空与远山。
 *
 * 两条经验（踩过的坑，改前先读）：
 *
 * 1. **不要用照片贴球**。平面摄影的云透视与球面纬度对不上，地平线会被拉成一条白带。
 * 2. **不要用多层 CanvasGradient 叠加天空**。太阳光晕 / 地平线雾 / 云三层各自看似合理，
 *    叠起来会把整片可见天区冲成白色——而画面实际只能看到 0~24° 仰角，正好全落在被冲白的区间。
 *    这里改成逐像素合成，按仰角做非线性色阶，低空也保留天蓝。
 */

import { fbmCanvas, smoothstep, clamp, normalizedNoise } from './util.js'

const HEX = (h) => [
  parseInt(h.slice(1, 3), 16),
  parseInt(h.slice(3, 5), 16),
  parseInt(h.slice(5, 7), 16)
]

const mix = (a, b, t) => [
  a[0] + (b[0] - a[0]) * t,
  a[1] + (b[1] - a[1]) * t,
  a[2] + (b[2] - a[2]) * t
]

/**
 * 程序化大气天空（球面等距柱状投影，逐像素合成）。
 *
 * 坐标约定与 SphereGeometry 的 UV 严格对应：
 *   canvas y=0 → 天顶(90°)，y=H/2 → 地平线(0°)，y>H/2 → 地面
 *   canvas x 与经度线性对应，可直接作 equirect 环境图。
 *
 * @param {object} cfg      SEASON 项（skyTop/skyMid/skyLow/skyHorizon）
 * @param {number} sunAz    太阳方位角（弧度）
 * @param {number} sunEl    太阳高度角（弧度）
 */
export function makeSkyTexture(cfg, sunAz = -0.62, sunEl = 0.52) {
  const W = 1024
  const H = 512
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')

  const cTop = HEX(cfg.skyTop)
  const cMid = HEX(cfg.skyMid)
  const cLow = HEX(cfg.skyLow)
  const cHor = HEX(cfg.skyHorizon)
  const cGround = [120, 128, 104]

  // 云场：分位归一化后才有"局部有云"的效果，否则 fBm 值普遍偏高会铺满整片天
  const noise = normalizedNoise(256, 128, 20261020, 4)

  const img = g.createImageData(W, H)
  const data = img.data
  const sunAzDeg = (sunAz * 180) / Math.PI
  const sunElDeg = (sunEl * 180) / Math.PI

  for (let y = 0; y < H; y++) {
    const el = (0.5 - y / H) * 180 // 仰角（度）
    // 非线性色阶：低空也要有蓝，不能用线性纬度
    const v = Math.min(1, Math.pow(Math.max(0, el) / 56, 0.62))
    let col
    if (el <= 0) {
      col = cGround
    } else if (v < 0.52) {
      col = mix(cHor, cLow, v / 0.52)
    } else if (v < 0.82) {
      col = mix(cLow, cMid, (v - 0.52) / 0.30)
    } else {
      col = mix(cMid, cTop, (v - 0.82) / 0.18)
    }

    // 地平线雾：0–6° 向 horizon 色靠拢，避免天地交界出现硬线
    if (el > 0 && el < 6) {
      const hz = 1 - el / 6
      col = mix(col, cHor, Math.pow(hz, 1.8) * 0.6)
    }

    const yy = y / H
    // 云带：中低空为主，靠近地平线被透视压成层云
    const band = Math.exp(-Math.pow((yy - 0.428) / 0.036, 2)) * 0.44 +
      Math.exp(-Math.pow((yy - 0.466) / 0.016, 2)) * 0.24

    for (let x = 0; x < W; x++) {
      let r = col[0]
      let gg = col[1]
      let b = col[2]

      // 太阳光晕：按角度距离衰减。半径必须压在画面宽度 10% 以内，
      // 否则会把整个可见天区打亮（上一版就是 30% 半径导致天空全白）。
      const az = (x / W - 0.5) * 360
      const dAz = Math.abs(((az - sunAzDeg + 540) % 360) - 180)
      const dEl = el - sunElDeg
      const dist = Math.sqrt(dAz * dAz + dEl * dEl)
      const glow = Math.exp(-Math.pow(dist / 2.6, 2)) * 1.0 +
        Math.exp(-Math.pow(dist / 11, 2)) * 0.30 +
        Math.exp(-Math.pow(dist / 34, 2)) * 0.09
      if (glow > 0.002) {
        const k = Math.min(1, glow)
        r += (255 - r) * k * 0.98
        gg += (252 - gg) * k * 0.98
        b += (238 - b) * k * 0.9
      }

      // 云
      if (band > 0.01) {
        const n = noise(x / W, yy)
        const a = clamp(smoothstep(0.50, 0.86, n) * band, 0, 1)
        if (a > 0.004) {
          const top = smoothstep(0.47, 0.40, yy) // 云顶更白，云底偏灰
          const cr = 200 + 36 * top
          const cg = 205 + 34 * top
          const cb = 210 + 30 * top
          r += (cr - r) * a
          gg += (cg - gg) * a
          b += (cb - b) * a
        }
      }

      const o = (y * W + x) * 4
      data[o] = r
      data[o + 1] = gg
      data[o + 2] = b
      data[o + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  return c
}

/**
 * 远山：分形山脊轮廓 + 大气透视渐变。
 *
 * 原来的 `Math.abs(Math.sin(i*0.62))` 多边形剪影一眼假——山脊是规则正弦，
 * 边缘是硬直线。这里用 7 个倍频正弦叠加，并按"山脊深、山脚融雾"做垂直渐变。
 */
export function makeRidgeTexture(W, H, seed, topColor, bottomColor, roughness = 0.55, sharpness = 1.0) {
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')
  let s = seed >>> 0 || 1
  const rnd = () => {
    s = (s * 1664525 + 1013904223) >>> 0
    return s / 4294967296
  }
  const comps = []
  for (let o = 0; o < 7; o++) {
    comps.push({
      f: 1.1 * Math.pow(1.86, o),
      a: Math.pow(0.56, o),
      p: rnd() * Math.PI * 2
    })
  }
  const norm = comps.reduce((acc, k) => acc + k.a, 0)

  const heightAt = (xRatio) => {
    let sum = 0
    for (const k of comps) sum += k.a * Math.sin(xRatio * Math.PI * 2 * k.f + k.p)
    let v = sum / norm
    v = Math.sign(v) * Math.pow(Math.abs(v), sharpness)
    // 两端收敛到 0，山体左右自然消失在雾里（否则平面边缘会出现垂直硬切）
    const edge = Math.pow(Math.sin(Math.PI * xRatio), 0.5)
    return H * (0.42 + 0.42 * v * roughness) * edge
  }

  g.beginPath()
  g.moveTo(0, H)
  for (let x = 0; x <= W; x += 2) {
    g.lineTo(x, H - Math.max(4, heightAt(x / W)))
  }
  g.lineTo(W, H)
  g.closePath()
  const grd = g.createLinearGradient(0, 0, 0, H)
  grd.addColorStop(0, topColor)
  grd.addColorStop(0.55, bottomColor)
  grd.addColorStop(1, bottomColor)
  g.fillStyle = grd
  g.fill()

  // 山脚淡出
  const fade = g.createLinearGradient(0, H * 0.25, 0, H)
  fade.addColorStop(0, 'rgba(0,0,0,0)')
  fade.addColorStop(1, 'rgba(0,0,0,0.55)')
  g.globalCompositeOperation = 'destination-out'
  g.fillStyle = fade
  g.fillRect(0, 0, W, H)
  g.globalCompositeOperation = 'source-over'
  return c
}

/** 三层远山：一次生成，避免组件里堆三段几乎相同的参数 */
export function makeRidgeLayers(THREE) {
  const defs = [
    { w: 760, h: 104, z: -540, seed: 3, top: '#7a97ad', bottom: '#c6d9e4', rough: 0.55, sharp: 0.8, op: 0.9 },
    { w: 660, h: 66, z: -340, seed: 11, top: '#6f8b96', bottom: '#b3c9d1', rough: 0.62, sharp: 0.9, op: 0.92 },
    { w: 600, h: 40, z: -215, seed: 29, top: '#5f8068', bottom: '#a2bba2', rough: 0.58, sharp: 1.05, op: 0.94 }
  ]
  return defs.map((d) => {
    const canvas = makeRidgeTexture(1024, 200, d.seed, d.top, d.bottom, d.rough, d.sharp)
    const tex = new THREE.CanvasTexture(canvas)
    tex.colorSpace = THREE.SRGBColorSpace
    tex.anisotropy = 4
    const mesh = new THREE.Mesh(
      new THREE.PlaneGeometry(d.w, d.h),
      new THREE.MeshBasicMaterial({
        map: tex,
        transparent: true,
        opacity: d.op,
        depthWrite: false,
        // 远山是纯背景剪影，renderOrder=-3 保证它先画。
        // 关掉 depthTest 让它彻底不参与深度竞争 —— 这样相机 far 可以收得很紧
        // （从 1000 收到 400），大幅提升远处地面（z=-60~-96）的深度缓冲精度，
        // 消除远景地块拖动时的 z-fighting 闪烁。
        depthTest: false,
        fog: false
      })
    )
    mesh.position.set(0, d.h / 2 - 1.2, d.z)
    mesh.renderOrder = -3
    return mesh
  })
}

export { fbmCanvas }
