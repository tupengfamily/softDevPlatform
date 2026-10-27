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
 * 土壤湿度斑块贴图（2026-10-23 画质升级）。
 *
 * ⚠️ **真实农田不是一块均匀的土**：
 *   刚浇过水的地方颜色深、饱和度高；没浇到的地方干白发松。
 *   均匀的田面在画面上就是"一张贴图铺满"，一眼假。
 *
 *   实现：fBm 噪声场 → 分档成 3~4 个湿度区 → 每档一个明度乘数。
 *   用 MultiplyBlending 叠在田面上（与垄沟同理），
 *   深色=湿、浅色=干。
 *
 * @param {THREE} THREE
 * @param {number} wetness 0~1，整体湿度（影响深色区占比）
 * @param {number} seed    随机种子（保证可复现）
 */
export function makeSoilWetnessTexture(THREE, wetness = 0.5, seed = 20261023) {
  const W = 256
  const H = 256
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')

  // 多倍频值噪声（自己实现，避免依赖 fbmCanvas 的尺寸假设）
  const rnd = mulberry32(seed)
  const perm = new Float32Array(64 * 64)
  for (let i = 0; i < perm.length; i++) perm[i] = rnd()
  const at = (x, y) => perm[((y & 63) * 64 + (x & 63))]
  const smooth = (t) => t * t * (3 - 2 * t)
  // 平滑插值的值噪声
  const vnoise = (x, y) => {
    const xi = Math.floor(x)
    const yi = Math.floor(y)
    const xf = smooth(x - xi)
    const yf = smooth(y - yi)
    const a = at(xi, yi)
    const b = at(xi + 1, yi)
    const c = at(xi, yi + 1)
    const d = at(xi + 1, yi + 1)
    return (a + (b - a) * xf) + ((c + (d - c) * xf) - (a + (b - a) * xf)) * yf
  }
  // fBm：4 个倍频
  const fbm = (x, y) => {
    let sum = 0
    let amp = 0.5
    let freq = 1
    for (let o = 0; o < 4; o++) {
      sum += vnoise(x * freq, y * freq) * amp
      amp *= 0.5
      freq *= 2.07
    }
    return sum / 0.9375
  }

  const img = g.createImageData(W, H)
  const data = img.data
  // 湿度分档：湿(深) → 潮 → 正常 → 干(浅)
  // wetness 越高，深色区占比越大
  const tWet = 0.78 - wetness * 0.30      // 低于此值 = 湿
  const tDamp = 0.62 - wetness * 0.16     // 低于此值 = 潮
  const tDry = 0.34 + (1 - wetness) * 0.14 // 高于此值 = 干

  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      // 低频大斑 + 高频碎斑
      const n = fbm(x / 34, y / 34) * 0.68 + fbm(x / 11, y / 11) * 0.32
      let v
      if (n < tWet) {
        // 湿：明显压暗并偏冷（湿土反射天空 → 略偏蓝）
        const k = smoothstep(tWet, tWet - 0.16, n)
        v = 1 - 0.26 * k
        const o = (y * W + x) * 4
        data[o] = 255 * v * 0.985
        data[o + 1] = 255 * v * 0.995
        data[o + 2] = 255 * v * 1.02
        data[o + 3] = 255
        continue
      }
      if (n < tDamp) {
        const k = smoothstep(tDamp, tWet, n)
        v = 1 - 0.13 * k
      } else if (n > tDry) {
        // 干：提亮并发白（干土粉化）
        const k = smoothstep(tDry, Math.min(1, tDry + 0.20), n)
        v = 1 + 0.13 * k
      } else {
        v = 1
      }
      const o = (y * W + x) * 4
      const b = 255 * v
      data[o] = b
      data[o + 1] = b
      data[o + 2] = b
      data[o + 3] = 255
    }
  }
  g.putImageData(img, 0, 0)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.wrapS = THREE.RepeatWrapping
  t.wrapT = THREE.RepeatWrapping
  t.anisotropy = 4
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
 * ⚠️ **细分参数 segX/segY（2026-10-24 画质升级）**：
 *
 *   原来每片只有 4 个顶点。这在 560 时代的贴图上看不出来，
 *   但换成 768 高的真实照片后暴露了两个问题：
 *
 *   ① **球面化法线只能线性插值**。spherifyNormals 是按"距面片中心的水平偏移"
 *      把法线往外旋转的 —— 只有 4 个顶点时，中心到边缘之间只有**一条**过渡，
 *      受光后整株作物呈现"中心一条亮带 + 两侧死平"的割裂感。
 *      细分成网格后，法线在片内可以密集插值，体积感才连续。
 *
 *   ② **风动顶点位移失真**。addWindShader 按 localY 做弯曲，
 *      4 顶点时整片只能整体上下平移，看不出"梢部摆、根部固定"。
 *      细分后上排顶点能真正沿高度梯度弯曲。
 *
 *   代价：单个面片顶点数从 8 涨到 2×(segX+1)×(segY+1)。
 *   默认 segX=3 / segY=4 → 每片 20 顶点、两片 40 顶点 / 24 三角形。
 *   一块田 ~1200 丛 × 40 顶点 = 4.8 万顶点，InstancedMesh 下完全无压力。
 *
 * @param {number} h      平面高度（米）
 * @param {number} aspect 内容宽高比（宽 = h * aspect）
 * @param {number} vTop   贴图纵向截取上界（1 = 完整，<1 截掉顶部穗部）
 * @param {number} segX   横向细分数（沿宽度方向的列数）
 * @param {number} segY   纵向细分数（沿高度方向的行数）
 */
export function buildCrossQuad(THREE, h, aspect, vTop = 1.0, segX = 3, segY = 4) {
  const w = h * aspect * 0.5
  const hh = h * vTop
  const nx = Math.max(1, segX | 0)
  const ny = Math.max(1, segY | 0)
  const pos = []
  const uv = []
  const idx = []
  for (let p = 0; p < 2; p++) {
    const ang = p === 0 ? 0 : Math.PI / 2
    const cs = Math.cos(ang)
    const sn = Math.sin(ang)
    const base = pos.length / 3
    for (let iy = 0; iy <= ny; iy++) {
      const fy = iy / ny
      const y = fy * hh
      for (let ix = 0; ix <= nx; ix++) {
        const fx = ix / nx
        const x = (fx - 0.5) * 2 * w
        pos.push(x * cs, y, x * sn)
        uv.push(fx, fy * vTop)
      }
    }
    // 网格索引
    for (let iy = 0; iy < ny; iy++) {
      for (let ix = 0; ix < nx; ix++) {
        const a = base + iy * (nx + 1) + ix
        const b = a + 1
        const c = a + (nx + 1)
        const d = c + 1
        idx.push(a, c, b, b, c, d)
      }
    }
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  g.setIndex(idx)
  g.computeVertexNormals()
  // 🔴 **必须记下网格元数据**：spherifyNormals 靠它才知道每个顶点属于
  //   哪个面片、位于该面片的哪个横向位置。没有它就只能退回 4 顶点旧算法，
  //   细分带来的平滑法线全部白做。
  g.userData.grid = { nx, ny, planes: 2, halfW: w }
  return g
}

/**
 * 贴地面片：一片**平躺在地面上**的面片（俯视视角的作物专用）。
 *
 * ⚠️ **为什么需要它**（2026-10-24 画质升级）：
 *
 *   甜瓜、西瓜、黄瓜的源素材是**俯视图**（从正上方拍的瓜蔓与叶片），
 *   而十字面片是**竖直**的。把俯视图硬塞进竖直面片 →
 *   瓜是「躺」着的却立起来显示，画面上就是一张**立起来的海报** ——
 *   这正是用户说的"纸片感"。
 *
 *   而真实农田里，匍匐作物的瓜蔓**确实是趴在地上的**：
 *   从田埂上看过去，就是一片从上方看到的叶毯，
 *   偶尔露出一个瓜。所以正确解法不是硬掰角度，
 *   而是**让这类作物改用贴地面片** —— 几何与素材的拍摄角度一致。
 *
 *   面片贴地 + 轻微倾斜（默认 0°，完全平躺），
 *   法线朝上吃顶光，叶片受光均匀，瓜的圆形轮廓自然。
 *
 * @param {object} THREE
 * @param {number} size  面片边长（米）
 * @param {number} seg   细分（让法线/风动有梯度）
 */
export function buildGroundQuad(THREE, size, seg = 3) {
  const n = Math.max(1, seg | 0)
  const half = size / 2
  const pos = []
  const uv = []
  const idx = []
  for (let iy = 0; iy <= n; iy++) {
    const fy = iy / n
    for (let ix = 0; ix <= n; ix++) {
      const fx = ix / n
      pos.push((fx - 0.5) * size, 0, (fy - 0.5) * size)
      uv.push(fx, fy)
    }
  }
  for (let iy = 0; iy < n; iy++) {
    for (let ix = 0; ix < n; ix++) {
      const a = iy * (n + 1) + ix
      const b = a + 1
      const c = a + (n + 1)
      const d = c + 1
      idx.push(a, c, b, b, c, d)
    }
  }
  const g = new THREE.BufferGeometry()
  g.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
  g.setAttribute('uv', new THREE.Float32BufferAttribute(uv, 2))
  g.setIndex(idx)
  g.computeVertexNormals()
  // ⚠️ **不要走球面化**：贴地面片的法线就该**全部朝上**（吃顶光）。
  //   球面化会让边缘法线朝外 → 叶子边缘变暗，贴地的东西不该有"侧影"。
  g.userData.ground = true
  return g
}

/**
 * 球面化法线：把十字面片的法线从"垂直于面"改成"从中心向外发散"。
 *
 * ⚠️ **为什么需要它**（画质升级 2026-10-23）：
 *
 *   十字面片（cross quad）是两片 90° 相交的**平面**，法线恒定垂直于该平面。
 *   用 MeshBasicMaterial 时无所谓（不受光）；
 *   一旦换成 MeshStandardMaterial 吃光照，整片叶子/树冠的所有像素
 *   拿到**完全相同的 N·L**，结果就是"一块死平的色块"——
 *   这正是基线图里作物和树看起来像贴纸的根本原因。
 *
 *   把法线按"距面片中心的水平偏移"向外旋转，
 *   中心附近法线朝上（顶光）、边缘法线朝外（侧光），
 *   于是同一片叶子上出现"上亮下暗、边缘暗、中心亮"的体积感。
 *
 * @param {object} THREE
 * @param {object} geo  buildCrossQuad 产出的 BufferGeometry（会被就地修改）
 */
export function spherifyNormals(THREE, geo, strength = 0.85) {
  const pos = geo.getAttribute('position')
  const nrm = geo.getAttribute('normal')
  if (!pos || !nrm) return geo
  const grid = geo.userData && geo.userData.grid
  if (!grid) return spherifyLegacy(pos, nrm, strength)
  const { nx, ny, planes, halfW } = grid
  const vpl = (nx + 1) * (ny + 1)
  for (let p = 0; p < planes; p++) {
    // 面片自身的水平展开方向 t（世界坐标 XZ 平面内）
    const ang = p * (Math.PI / planes)
    const tx = Math.cos(ang)
    const tz = Math.sin(ang)
    const base = p * vpl
    for (let iy = 0; iy <= ny; iy++) {
      const vT = iy / ny // 0 = 根部，1 = 梢部
      for (let ix = 0; ix <= nx; ix++) {
        const i = base + iy * (nx + 1) + ix
        // 该顶点沿面片展开方向的坐标：中心 0、边缘 ±halfW
        const s = (ix / nx - 0.5) * 2 * halfW
        const u = Math.min(1, Math.abs(s) / Math.max(1e-6, halfW))
        const sgn = s >= 0 ? 1 : -1
        // ⚠️ **权重用 smoothstep 而不是线性**：
        //   线性时中心到边缘的法线扭转是匀速的，整片看起来像"被压弯的铁皮"。
        //   smoothstep 让中心保持基本平坦、只在靠边处快速卷起 —— 更像叶片自然鼓起。
        const radial = u * u * (3 - 2 * u) * strength
        // ⚠️ **纵向分量**：原实现完全没有 Y 分量，导致
        //   "整株作物上下一样亮"（法线恒定水平 → 顶光对全株作用完全相同）。
        //   真实植物是**底叶朝下、上部朝上**：
        //   根部法线略微下俯（与土壤接触处更暗），梢部法线上扬（受顶光最亮）。
        const vertical = (vT - 0.38) * 0.55
        const nx2 = nrm.getX(i) + tx * sgn * radial
        const nz2 = nrm.getZ(i) + tz * sgn * radial
        const ny2 = nrm.getY(i) + vertical
        const l = Math.hypot(nx2, ny2, nz2) || 1
        nrm.setXYZ(i, nx2 / l, ny2 / l, nz2 / l)
      }
    }
  }
  nrm.needsUpdate = true
  return geo
}

/**
 * 旧版球面化（每片 4 顶点的平面片）。
 * 保留给不经过 buildCrossQuad 的几何体使用。
 */
function spherifyLegacy(pos, nrm, strength) {
  for (let base = 0; base + 3 < pos.count; base += 4) {
    let cx = 0
    let cz = 0
    for (let k = 0; k < 4; k++) {
      cx += pos.getX(base + k)
      cz += pos.getZ(base + k)
    }
    cx /= 4
    cz /= 4
    const halfW = Math.max(0.001, Math.abs(pos.getX(base + 1) - pos.getX(base)) / 2)
    for (let k = 0; k < 4; k++) {
      const i = base + k
      let rx = pos.getX(i) - cx
      let rz = pos.getZ(i) - cz
      const len = Math.hypot(rx, rz)
      if (len < 1e-6) continue
      rx /= len
      rz /= len
      const w = Math.min(1, len / halfW) * strength
      const nx = nrm.getX(i) + rx * w
      const nz = nrm.getZ(i) + rz * w
      const ny = nrm.getY(i)
      const l = Math.hypot(nx, ny, nz) || 1
      nrm.setXYZ(i, nx / l, ny / l, nz / l)
    }
  }
  nrm.needsUpdate = true
  return null
}

/**
 * 给材质注入风动（顶点位移）。
 *
 * ⚠️ **为什么用 onBeforeCompile 而不是每帧改实例矩阵**：
 *   一块田有 900~1500 丛作物，逐帧 setMatrixAt 上千次会吃掉整个帧预算；
 *   而 InstancedMesh 的顶点着色器里直接读 instanceMatrix 即可拿到世界位置，
 *   在 GPU 上做位移几乎零成本。
 *
 * 位移规律：
 *   · 高度越高摆幅越大（根部固定，梢部摆动）→ 乘以 localY 的归一化
 *   · 用世界坐标 x/z 做相位差 → 不会整片同步摆动（否则像海浪）
 *   · 两个不同频率叠加 → 阵风感，而不是机械正弦
 *
 * @param {object} material  要注入的材质
 * @param {object} opts
 * @param {number} opts.amp     摆幅（米），末梢最大位移
 * @param {number} opts.speed   主频率（rad/s）
 * @param {number} opts.height  该材质承载几何的参考高度（米），用来归一化
 */
/**
 * 全局风动材质登记表。
 *
 * ⚠️ **为什么需要这个**（2026-10-23 画质升级实测发现）：
 *
 *   `addWindShader` 只往材质上挂一个 `userData.windTime` uniform，
 *   **谁来推进它**是调用方的事。原来只有主田作物 / 树 / 主田野草
 *   在 animate() 里被逐个 tick —— 于是邻田里 70 丛荒草
 *   （每块荒地 7 丛 × 10 块地）的 uniform 永远停在 0，
 *   **完全不摆动**（实测 78 个风动材质里 70 个 value=0）。
 *
 *   现在改成**注册制**：addWindShader 自动登记，
 *   animate() 里只需一行 `tickAllWind(t)` 就能推动全部。
 *   组件卸载时用 `clearWindRegistry()` 清空，避免旧材质的 uniform 被继续推进。
 */
const windRegistry = new Set()

/** 推进全部风动材质的时间 */
export function tickAllWind(elapsed) {
  for (const t of windRegistry) t.value = elapsed
}

/** 清空登记表（组件卸载时调用） */
export function clearWindRegistry() {
  windRegistry.clear()
}

export function addWindShader(material, { amp = 0.05, speed = 1.1, height = 1.0 } = {}) {
  material.userData.windTime = { value: 0 }
  // 登记到全局表，animate() 里统一推进
  windRegistry.add(material.userData.windTime)
  const prevCompile = material.onBeforeCompile
  material.onBeforeCompile = (shader, renderer) => {
    // 🔴 **必须链式调用原有的 onBeforeCompile**（2026-10-26）。
    //   addUvJitter 也是通过 onBeforeCompile 注入的；
    //   如果风动直接覆盖 material.onBeforeCompile，
    //   UV 抖动就会**随机消失**（取决于谁后设置）。
    if (prevCompile) prevCompile.call(material, shader, renderer)
    shader.uniforms.uWindTime = material.userData.windTime
    shader.uniforms.uWindAmp = { value: amp }
    shader.uniforms.uWindSpeed = { value: speed }
    shader.uniforms.uWindHeight = { value: height }
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        `#include <common>
        uniform float uWindTime;
        uniform float uWindAmp;
        uniform float uWindSpeed;
        uniform float uWindHeight;`
      )
      .replace(
        '#include <begin_vertex>',
        `#include <begin_vertex>
        {
          // 局部高度归一化：根部 0、梢部 1
          float hNorm = clamp(transformed.y / max(uWindHeight, 0.001), 0.0, 1.0);
          // ⚠️ **三次方**：原来用 hNorm*hNorm（二次）时，
          //   中段摆幅偏大，梢部反而收得不够，看起来像"整根硬掰"。
          //   三次方让根部几乎不动、中段缓起、梢部最明显 —— 更像真实植物。
          hNorm = hNorm * hNorm * hNorm;
          // instanceMatrix 给出实例在世界中的位置，用它做相位差
          #ifdef USE_INSTANCING
            vec3 instPos = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
          #else
            vec3 instPos = vec3(modelMatrix[3][0], modelMatrix[3][1], modelMatrix[3][2]);
          #endif
          float phase = instPos.x * 0.7 + instPos.z * 0.53;
          float sway = sin(uWindTime * uWindSpeed + phase) * 0.62
                     + sin(uWindTime * uWindSpeed * 2.37 + phase * 1.9) * 0.38;
          // 风向沿 +x 略偏 +z，主风向恒定，摆动只是幅度变化
          transformed.x += sway * uWindAmp * hNorm;
          transformed.z += sway * uWindAmp * hNorm * 0.42;
        }`
      )
  }
  // onBeforeCompile 改 shader 必须让材质重新编译，否则缓存里还是旧程序
  // 🔴 **cacheKey 必须链式合并**（2026-10-26）：
  //   addUvJitter 也会设置 customProgramCacheKey；
  //   如果风动直接覆盖，UV 抖动的 key 就丢了 →
  //   带抖动的材质可能命中不带抖动的缓存 → 抖动随机失效。
  const prevKey = material.customProgramCacheKey
  material.customProgramCacheKey = () => {
    const base = prevKey ? prevKey.call(material) : material.type
    return `${base}-wind-${amp}-${speed}-${height}`
  }
  return material
}

/**
 * 🔴 **实例级 UV 抖动**（2026-10-26 真实感升级）
 *
 *   问题：田里上千丛作物**共用同一张贴图、同一个 UV**。
 *   即使每丛随机旋转 + 水平镜像，看上去仍然是「同一张照片复制了 1000 遍」——
 *   尤其作物照片里有**固定的暗斑/枯叶/虫眼**，这些特征会整齐地重复出现，
 *   是「贴图感」最强的来源之一。
 *
 *   解法：在顶点着色器里按**实例位置**给 UV 加一个确定性偏移。
 *   偏移量由实例在世界中的位置哈希得到 → 同一丛每次打开偏移相同（可复现），
 *   不同丛偏移不同 → 贴图里的固定特征被"打散"到不同位置。
 *
 *   ⚠️ **必须用位置哈希而不是 gl_InstanceID**：
 *   gl_InstanceID 在每帧/每次 setMatrixAt 后可能重排，用它会导致
 *   作物"抖动"（同一丛每帧 UV 都在变）。位置哈希是稳定的。
 *
 *   ⚠️ **UV 偏移量要小**（默认 ±0.06）：偏移太大，
 *   贴图边缘（羽化边）会被移出面片 → alphaTest 啃出缺口。
 *   作物贴图是居中裁切的白底图，边缘本来就有内容，
 *   所以 0.03~0.08 是安全区。
 *
 * @param {object} material 要改的材质
 * @param {object} [opts]
 * @param {number} [opts.amount=0.06]  UV 抖动幅度
 * @param {number} [opts.scale=3.1]    哈希的空间尺度（越大相邻丛越不同）
 */
export function addUvJitter(material, { amount = 0.06, scale = 3.1 } = {}) {
  const prev = material.onBeforeCompile
  material.onBeforeCompile = (shader, renderer) => {
    // 先让原有的风动等回调跑完
    if (prev) prev.call(material, shader, renderer)
    shader.uniforms.uUvJitter = { value: amount }
    shader.uniforms.uUvJitterScale = { value: scale }
    // 声明：把偏移量塞进 varying（片元着色器也要用）
    shader.vertexShader = shader.vertexShader
      .replace(
        '#include <common>',
        `#include <common>
        uniform float uUvJitter;
        uniform float uUvJitterScale;
        // 位置哈希 → [0,1) 的两个独立随机数
        vec2 farmUvHash(vec3 p) {
          // 三个不同的大质数相乘再取小数，避免明显的规律
          float h1 = fract(sin(dot(p, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
          float h2 = fract(sin(dot(p, vec3(63.7264, 10.873, 91.532))) * 24634.6345);
          return vec2(h1, h2);
        }`
      )
      // 注入到 uv_vertex 之后：改 vMapUv / vNormalMapUv
      .replace(
        '#include <uv_vertex>',
        `#include <uv_vertex>
        #ifdef USE_INSTANCING
          vec3 farmInstPos = vec3(instanceMatrix[3][0], instanceMatrix[3][1], instanceMatrix[3][2]);
        #else
          vec3 farmInstPos = vec3(modelMatrix[3][0], modelMatrix[3][1], modelMatrix[3][2]);
        #endif
        {
          vec2 farmHash = farmUvHash(farmInstPos * uUvJitterScale);
          // 偏移量在 [-amount, +amount] 之间
          vec2 farmUvOffset = (farmHash - 0.5) * 2.0 * uUvJitter;
          #ifdef USE_MAP
            vMapUv += farmUvOffset;
          #endif
          #ifdef USE_NORMALMAP
            vNormalMapUv += farmUvOffset;
          #endif
        }`
      )
  }
  // 🔴 **customProgramCacheKey 必须区分开**：
  //   否则带 UV 抖动的材质会与不带抖动的材质**共用同一个编译后的 shader**
  //   → 抖动的效果随机出现/消失（取决于谁先编译）。
  const prevKey = material.customProgramCacheKey
  material.customProgramCacheKey = () => {
    const base = prevKey ? prevKey.call(material) : material.type
    return `${base}-uvjit-${amount}-${scale}`
  }
  return material
}

/**
 * 多倍频值噪声：用低分辨率随机图放大叠加近似 fBm。
 * 直接逐像素随机会有高频噪点，这里靠 drawImage 的双线性放大得到平滑场。
 */export function fbmCanvas(W, H, seed, octaves = 4) {
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

/**
 * 取一张 fBm 场并做分位数归一化，返回 (x,y)=>0..1 的采样器
 *
 * ⚠️ **采样是最近邻的**（直接四舍五入到像素），所以低分辨率场
 *   （比如 64×16）采样出来的等高线会有明显的**方块状锯齿**。
 *   云层用这种场做云量时，云轮廓就会呈现"一格一格"的直角台阶。
 *
 *   → 用 `normalizedNoiseSmooth` 代替，见下。
 */
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

/**
 * 归一化噪声的**双线性插值**版本。
 *
 * ⚠️ 天空云量场必须用这个：最近邻采样会让云轮廓出现直角台阶（实测）。
 *   低分辨率场（64×16）尤其明显 —— 云边缘全是"方块"。
 *
 * @param {number} W,H  场分辨率
 * @param {number} seed
 * @param {number} octaves  fBm 倍频数
 */
export function normalizedNoiseSmooth(W, H, seed, octaves = 4) {
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
  const at = (px, py) => {
    const cx = clamp(px, 0, W - 1)
    const cy = clamp(py, 0, H - 1)
    const x0 = Math.floor(cx)
    const y0 = Math.floor(cy)
    const x1 = Math.min(W - 1, x0 + 1)
    const y1 = Math.min(H - 1, y0 + 1)
    const fx = cx - x0
    const fy = cy - y0
    const v00 = fd[(y0 * W + x0) * 4]
    const v10 = fd[(y0 * W + x1) * 4]
    const v01 = fd[(y1 * W + x0) * 4]
    const v11 = fd[(y1 * W + x1) * 4]
    const top = v00 + (v10 - v00) * fx
    const bot = v01 + (v11 - v01) * fx
    return top + (bot - top) * fy
  }
  return (x, y) => {
    const px = clamp(x, 0, 1) * (W - 1)
    const py = clamp(y, 0, 1) * (H - 1)
    return clamp((at(px, py) - p12) / span, 0, 1)
  }
}
