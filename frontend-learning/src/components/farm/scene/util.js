/**
 * 场景通用小工具：确定性随机、平滑阶跃、程序化贴图。
 *
 * 约定：所有需要 three 的函数都把 THREE 作为第一个参数传入，
 * 这样组件仍然可以动态 import('three') 并在失败时优雅降级。
 */

/** 确定性伪随机（同一 seed 逐帧/逐次一致，保证画面可复现） */
export function mulberry32(a) {
  return function () {
    a |= 0
    a = (a + 0x6D2B79F5) | 0
    let t = Math.imul(a ^ (a >>> 15), 1 | a)
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

export function smoothstep(a, b, x) {
  const t = Math.max(0, Math.min(1, (x - a) / (b - a)))
  return t * t * (3 - 2 * t)
}

export function clamp(v, lo, hi) {
  return v < lo ? lo : v > hi ? hi : v
}

/** 径向渐变软阴影贴图（沙箱内 InstancedMesh + shadowMap 会抛异常，只能贴图模拟） */
export function makeSoftShadowTexture(THREE) {
  const S = 128
  const c = document.createElement('canvas')
  c.width = c.height = S
  const g = c.getContext('2d')
  const grd = g.createRadialGradient(S / 2, S / 2, 0, S / 2, S / 2, S / 2)
  grd.addColorStop(0, 'rgba(28,36,20,0.55)')
  grd.addColorStop(0.55, 'rgba(28,36,20,0.26)')
  grd.addColorStop(1, 'rgba(28,36,20,0)')
  g.fillStyle = grd
  g.fillRect(0, 0, S, S)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  return t
}

/**
 * 垄沟明暗条纹：白色=不变，灰色=压暗。一个周期 = 一个种植行距。
 * 用地面上方一层 MultiplyBlending 平面实现，比改 uv 或烘焙进贴图更灵活。
 */
export function makeRowShadeTexture(THREE, low = 0.55) {
  const W = 128
  const H = 8
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')
  const grd = g.createLinearGradient(0, 0, W, 0)
  grd.addColorStop(0.0, 'rgb(255,255,255)')
  grd.addColorStop(0.26, 'rgb(232,232,232)')
  const v = Math.round(255 * low)
  grd.addColorStop(0.5, `rgb(${v},${v},${v})`)
  grd.addColorStop(0.74, 'rgb(232,232,232)')
  grd.addColorStop(1.0, 'rgb(255,255,255)')
  g.fillStyle = grd
  g.fillRect(0, 0, W, H)
  for (let i = 0; i < 90; i++) {
    const a = 0.04 + Math.random() * 0.06
    g.fillStyle = `rgba(120,120,120,${a})`
    g.fillRect(Math.random() * W, Math.random() * H, 2 + Math.random() * 5, 1 + Math.random() * 2)
  }
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.wrapS = THREE.RepeatWrapping
  t.wrapT = THREE.ClampToEdgeWrapping
  t.anisotropy = 4
  return t
}

/** 带 UV 重复次数的 Box（田埂、田边路） */
export function boxWithUV(THREE, w, h, d, repX = 2, repY = 1) {
  const g = new THREE.BoxGeometry(w, h, d)
  const uv = g.getAttribute('uv')
  for (let i = 0; i < uv.count; i++) {
    uv.setXY(i, uv.getX(i) * repX, uv.getY(i) * repY)
  }
  return g
}

/**
 * 十字交叉面片：两片 90° 相交的竖直面。
 * 作物/树/野草全部用这个承载照片切图，是"照片精灵"方案的核心几何。
 *
 * @param {number} h      平面高度（米）
 * @param {number} aspect 内容宽高比（宽 = h * aspect）
 * @param {number} vTop   贴图纵向截取上界（1 = 完整，<1 截掉顶部穗部）
 */
export function buildCrossQuad(THREE, h, aspect, vTop = 1.0) {
  const w = h * aspect * 0.5
  const hh = h * vTop
  const pos = []
  const uv = []
  const idx = []
  for (let p = 0; p < 2; p++) {
    const ang = p === 0 ? 0 : Math.PI / 2
    const cs = Math.cos(ang)
    const sn = Math.sin(ang)
    const base = p * 4
    const corners = [[-w, 0], [w, 0], [w, hh], [-w, hh]]
    for (const [x, y] of corners) {
      pos.push(x * cs, y, x * sn)
    }
    uv.push(0, 0, 1, 0, 1, vTop, 0, vTop)
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3)
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  g.setIndex(idx)
  g.computeVertexNormals()
  return g
}

/**
 * 多倍频值噪声：用低分辨率随机图放大叠加近似 fBm。
 * 直接逐像素随机会有高频噪点，这里靠 drawImage 的双线性放大得到平滑场。
 */
export function fbmCanvas(W, H, seed, octaves = 4) {
  const base = document.createElement('canvas')
  base.width = W
  base.height = H
  const ctx = base.getContext('2d')
  ctx.fillStyle = '#000'
  ctx.fillRect(0, 0, W, H)
  const rnd = mulberry32(seed)
  for (let o = 0; o < octaves; o++) {
    const step = 3 * Math.pow(1.9, o)
    const nw = Math.max(2, Math.ceil(W / step))
    const nh = Math.max(2, Math.ceil(H / step))
    const nc = document.createElement('canvas')
    nc.width = nw
    nc.height = nh
    const nctx = nc.getContext('2d')
    const img = nctx.createImageData(nw, nh)
    for (let i = 0; i < nw * nh; i++) {
      const v = rnd() * 255
      img.data[i * 4] = v
      img.data[i * 4 + 1] = v
      img.data[i * 4 + 2] = v
      img.data[i * 4 + 3] = 255
    }
    nctx.putImageData(img, 0, 0)
    ctx.globalCompositeOperation = 'lighter'
    ctx.globalAlpha = 1 / (o + 1.15)
    ctx.imageSmoothingEnabled = true
    ctx.drawImage(nc, 0, 0, W, H)
  }
  return base
}

/** 取一张 fBm 场并做分位数归一化，返回 (x,y)=>0..1 的采样器 */
export function normalizedNoise(W, H, seed, octaves = 4) {
  const fbc = fbmCanvas(W, H, seed, octaves)
  const fd = fbc.getContext('2d').getImageData(0, 0, W, H).data
  const hist = new Uint32Array(256)
  for (let i = 0; i < W * H; i++) hist[fd[i * 4]]++
  let acc = 0
  let p12 = 0
  let p86 = 255
  const tot = W * H
  for (let v = 0; v < 256; v++) {
    acc += hist[v]
    if (p12 === 0 && acc > tot * 0.12) p12 = v
    if (acc > tot * 0.86) {
      p86 = v
      break
    }
  }
  const span = Math.max(10, p86 - p12)
  return (x, y) => {
    const px = Math.round(clamp(x, 0, 1) * (W - 1))
    const py = Math.round(clamp(y, 0, 1) * (H - 1))
    const v = fd[(py * W + px) * 4]
    return clamp((v - p12) / span, 0, 1)
  }
}
