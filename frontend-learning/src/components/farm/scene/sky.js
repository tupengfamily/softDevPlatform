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

import { fbmCanvas, smoothstep, clamp, normalizedNoise, normalizedNoiseSmooth } from './util.js'

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
export function makeSkyTexture(cfg, sunAz = -0.62, sunEl = 0.735) {
  const W = 1024
  const H = 512
  const c = document.createElement('canvas')
  c.width = W
  c.height = H
  const g = c.getContext('2d')

  const cTop = HEX(cfg.skyTop)
  const cMid = HEX(cfg.skyMid)
  const cLow = HEX(cfg.skyLow)
  // ⚠️ **地平线色必须由雾色派生，不能各填各的**。
  //
  // 曾经 skyHorizon 是独立配置的冷灰白（autumn `#dee6e2`），而场景雾色是暖米色
  //（autumn `0xe6d9c0`），两者蓝通道差 +34。相机只覆盖 0~15° 仰角，
  // 天空几乎整个可见区都是"地平线色"，于是：
  //     被暖雾染白的地面  ←→  冷蓝的天空
  // 在地平线（树线）处割裂出一条**青蓝色横带**，远看像水面（2026-10-22 实测）。
  //
  // 物理上也应该一致：远处地面褪成雾色 → 雾色就是地平线附近天空的颜色。
  // 所以这里直接取雾色，**让天空与雾在色相上天然对齐**。
  const fogRgb = [
    (cfg.fog >> 16) & 255,
    (cfg.fog >> 8) & 255,
    cfg.fog & 255
  ]
  const cHor = fogRgb
  // ⚠️ **地平线以下（球面下半）也用雾色**。
  //   原来填固定的暗绿灰 [120,128,104]，与上方地平线色差极大，
  //   在 el=0 处形成一条 126→208 亮度的**硬边**（实测 min/max 突变）。
  //   远处地面被雾完全吞掉，本来就不该有独立的暗色 —— 用雾色最自然。
  const cGround = fogRgb

  // 云场：分位归一化后才有"局部有云"的效果，否则 fBm 值普遍偏高会铺满整片天
  //
  // ⚠️ **云场必须各向异性**（实测踩坑：第三版云还是贴着一条线）。
  //
  //   normalizedNoise(256, 128, ...) 生成的是 256×128 的**各向同性** fBm 场。
  //   云层在贴图上只有 49px 高，而噪声场的垂直特征尺度约 128/8 = 16px ——
  //   也就是说**云的高度变化和它的厚度同量级**，根本没有空间长出"塔"。
  //   结果：不管怎么调阈值，云都是一条贴着凝结高度的薄边。
  //
  //   正确做法：**水平用低频、垂直用高频**。
  //   · cloudLow  : 横向 6 个大云团（水平特征尺度 ≈ 1024/6 ≈ 170px ≈ 60° 经度）
  //   · cloudHigh : 横向 22 个中等云团 + 垂直高频细节
  //   这样同一朵云在水平方向绵延很远、垂直方向被"压"在薄薄一层里 ——
  //   而云顶高度由**独立的水平轮廓场**决定，与垂直噪声解耦。
  const cloudLow = normalizedNoiseSmooth(64, 32, 20261020, 3)
  const cloudHigh = normalizedNoiseSmooth(256, 96, 20261023, 4)

  // 云层垂直范围（canvas yy 坐标，yy 越小仰角越高）
  //
  // ⚠️ **云层必须够厚**（实测踩坑：第二版还是一条窄横带）。
  //
  //   第二版：BASE=0.4865 / TOP=0.4345 → 层厚 0.052 = 9.4° = 贴图上 26px。
  //   在 26px 高的带子里塞"云塔"，塔高最多十几像素 ——
  //   渲染出来就是地平线上方一条白边，横贯整个画面。
  //
  //   真实积云从凝结高度到对流顶部能长 30°~45°（一朵云比一座山还高）。
  //   现在取 BASE=0.4820（仰角 3.2°）/ TOP=0.3860（仰角 20.5°），
  //   层厚 17.3° = 贴图 49px，云塔有足够空间往上发展。
  //   可见区（0~11.7°）正好切在云的中下部 —— 那正是真实照片里看到的样子：
  //   画框上缘露出云腹，画面主体是完整云朵的下缘。
  const CLOUD_BASE = 0.4975   // 仰角 0.45°（凝结高度，紧贴地平线）
  const CLOUD_TOP = 0.3830    // 仰角 21.1°（对流顶部）
  const SPAN = CLOUD_BASE - CLOUD_TOP

  const img = g.createImageData(W, H)
  const data = img.data
  const sunAzDeg = (sunAz * 180) / Math.PI
  const sunElDeg = (sunEl * 180) / Math.PI

  for (let y = 0; y < H; y++) {
    const el = (0.5 - y / H) * 180 // 仰角（度）
    // ⚠️ **渐变归一化必须按「实际可见仰角」压缩**。
    //
    // 相机 fov=48°、俯角 pitch=0.215 rad（12.3°），**可见仰角只有 -11.7°~+11.7°**。
    // 如果按 25° 归一化，可见区只走到 v=0.39，
    // 全部落在 cHor→cLow 的最底一档 —— 整个可见天空是同一块颜色（实测 range 仅 3）。
    //
    // 改成按 VISIBLE_EL（16°）归一化，可见区就能吃满
    // cHor→cLow→cMid→cTop 的完整色阶，天空才有"由地平线向天顶变深"。
    const VISIBLE_EL = 16
    const v = Math.min(1, Math.pow(Math.max(0, el) / VISIBLE_EL, 0.92))
    let col
    if (el <= 0) {
      col = cGround
    } else if (v < 0.30) {
      col = mix(cHor, cLow, v / 0.30)
    } else if (v < 0.68) {
      col = mix(cLow, cMid, (v - 0.30) / 0.38)
    } else {
      col = mix(cMid, cTop, (v - 0.68) / 0.32)
    }

    // 地平线雾：0–3.2° 向 horizon 色靠拢，避免天地交界出现硬线。
    // ⚠️ 范围必须收窄到 3°（原来 6°）：相机能看到 0~11.7°，
    //   6° 雾带会把近半屏天空压成雾色 → 天看起来像"白墙"。
    if (el > 0 && el < 3.2) {
      const hz = 1 - el / 3.2
      col = mix(col, cHor, Math.pow(hz, 1.6) * 0.55)
    }

    const yy = y / H
    // ⚠️ **云不能只是"一条窄高斯带"**（实测踩坑）。
    //
    // 第一版：三条高斯带（宽度 0.011~0.0245）→ 云被压成地平线上方
    // **一条白色细边**，横贯整个画面，既不像积云也不像层云。
    //
    // 真实积云是**一朵朵有垂直发展**的云团：底部平坦（凝结高度），
    // 中间鼓起的塔状，顶部有起伏。
    //
    // 现在改成「云团场」方案：
    //   1. 2D fBm 生成**云量场** amt(x,y) —— 哪里有云团
    //   2. **垂直剖面**描述云的形状：底部平、顶部随云量鼓起
    //   3. 密度 = 云量强度 × 剖面高度分布
    // 于是云有"一朵一朵"的横向分布 + 有体积的垂直发展。
    const inCloud = (CLOUD_BASE - yy) / SPAN   // 0=底, 1=顶

    for (let x = 0; x < W; x++) {
      let r = col[0]
      let gg = col[1]
      let b = col[2]

      // ---- 云（逐列计算：云的横向分布 + 垂直剖面）----
      //
      // ⚠️ **不要用「云量 × 固定剖面」**（实测踩坑：第四、五版云被切方块）。
      //
      //   之前的做法：amt = cloudLow(x/W, **0.5**) —— y 固定在 0.5，
      //   于是**同一个 x 上的所有像素，云量完全相同** →
      //   云变成一根根"竖直的柱子"，再乘一个垂直剖面就成了方块。
      //
      //   正确做法：**沿垂直线采样云量场**（yy 参与取值），
      //   云的形状就由 2D 场决定：
      //   · 底部：场值高的地方云从凝结高度就出现（平坦的云底）
      //   · 顶部：场值低的地方云就到此为止（起伏的云顶）
      //   · 因为场的垂直特征尺度（32px）远小于云层厚（55px），
      //     云顶自然呈现出"一朵一朵"的鼓包，而不是水平线。
      let cloudA = 0
      let cloudTopness = 0
      if (inCloud > -0.10 && inCloud < 1.3) {
        // ⚠️ **垂直采样坐标必须用「云层内归一化位置」，不能用贴图行号 yy**
        // （2026-10-23 画质升级，实测全景图发现云被拉成竖条）。
        //
        //   噪声场是 64×32 / 256×96，**垂直只有 32 / 96 个格点**。
        //   而云层只占贴图的 11%（yy 0.383~0.4975）——
        //   于是垂直方向实际只采样到 32×0.11 ≈ **3.7 个格点**，
        //   相邻两行像素落在相邻格点上，噪声几乎是"逐行独立"的。
        //   结果：同一列上每行的云量毫无关联 → 云被拉成一根根**竖直细条**，
        //   而不是一朵一朵的云团（全景截图实测确认）。
        //
        //   修法：把垂直采样坐标映射到 **云层自己的高度区间**，
        //   让整场的垂直格点都花在云层里（32 格点全用上），
        //   云就能长出真正的垂直体积。
        const cyT = (yy - CLOUD_TOP) / (CLOUD_BASE - CLOUD_TOP)   // 0=云顶, 1=云底
        const vy = 0.18 + (1 - cyT) * 0.64                        // 场的垂直采样坐标

        // 云量场：沿垂直线取值，低频定大团、高频打碎边
        const amt = cloudLow(x / W, vy) * 0.62 + cloudHigh(x / W, vy) * 0.38
        // 阈值：只有场值够高的地方才有云
        const has = smoothstep(0.42, 0.62, amt)
        if (has > 0.002) {
          // 垂直剖面：从云底（inCloud=0）到云顶线性淡出。
          // 云顶高度由**该列上方的场值**决定 —— 用上方若干层的均值近似，
          // 避免出现"云顶随像素抖动"的毛边。
          let above = 0
          let cnt = 0
          for (let k = 1; k <= 4; k++) {
            const vyUp = vy + (k * 0.64) / 5
            above += cloudLow(x / W, vyUp) * 0.62 + cloudHigh(x / W, vyUp) * 0.38
            cnt++
          }
          const aboveAmt = cnt ? above / cnt : amt
          // 云顶相对高度：上方场值越强（云越厚），云顶越高
          const topH = 0.26 + aboveAmt * 1.15
          // 密度：底部 1，接近云顶时淡出
          const vertProfile = 1 - smoothstep(topH - 0.38, topH, Math.max(0, inCloud))
          // 云底：凝结高度以下是晴空
          const bottomCut = smoothstep(-0.20, 0.10, inCloud)
          cloudA = has * vertProfile * bottomCut
          // 云顶白、云底灰：按距云顶的距离渐变
          cloudTopness = smoothstep(topH - 0.70, topH - 0.08, inCloud)
          // 高空卷云：细丝状，只出现在 10° 以上
          if (inCloud > 0.55) {
            const cirrus = Math.exp(-Math.pow((yy - 0.3985) / 0.0085, 2)) * 0.5
            const cA = cirrus * smoothstep(0.58, 0.78, cloudHigh(x / W, vy))
            if (cA > cloudA) { cloudA = cA; cloudTopness = 0.95 }
          }
        }
      }

      // 太阳光晕：按角度距离衰减。
      //
      // ⚠️ **半径必须按「可见区」设计，不能按天空全周设计**（2026-10-23 实测）。
      //
      //   相机可见仰角只有 -11.7°~+13.2°，而太阳在 42° 高度 ——
      //   也就是说**太阳本身根本不在取景框里**。
      //   但光晕是按角度距离算的：一个 38° 半径的宽晕，
      //   从 42° 高度往下**覆盖了整个可见区**（42-38 = 4° < 13.2°），
      //   于是每一列天空都被加上 0.08 的白 → 整片天空被抬亮、洗白。
      //
      //   实测：可见区天空横向亮度 range 只有 17~21（几乎没有对比），
      //   而天空贴图本身的 range 是 21~118（很健康）——
      //   证实是光晕把对比度抹平了。
      //
      //   现在把宽晕半径压到 13°（只覆盖太阳下方一小片），
      //   并去掉"无差别加白"的大范围项。
      const az = (x / W - 0.5) * 360
      const dAz = Math.abs(((az - sunAzDeg + 540) % 360) - 180)
      const dEl = el - sunElDeg
      const dist = Math.sqrt(dAz * dAz + dEl * dEl)
      const glow = Math.exp(-Math.pow(dist / 3.2, 2)) * 0.85 +
        Math.exp(-Math.pow(dist / 8.5, 2)) * 0.22
      if (glow > 0.002) {
        const k = Math.min(1, glow)
        r += (255 - r) * k * 0.96
        gg += (252 - gg) * k * 0.95
        b += (238 - b) * k * 0.86
      }

      // 云
      if (cloudA > 0.004) {
        const a = clamp(cloudA, 0, 1)
        // 云顶亮白、云底偏灰蓝（受下方地面反照）
        const cr = 190 + 58 * cloudTopness
        const cg = 195 + 56 * cloudTopness
        const cb = 206 + 48 * cloudTopness
        r += (cr - r) * a
        gg += (cg - gg) * a
        b += (cb - b) * a
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

/**
 * 三层远山：一次生成，避免组件里堆三段几乎相同的参数。
 *
 * @param {object} THREE
 * @param {string} [tint] 季节色相微调（hex）。秋天给暖褐、冬天给冷灰，
 *                         让山体在当季雾色下仍有存在感又不偏色。
 */
export function makeRidgeLayers(THREE, tint = '#ffffff') {
  // ⚠️ z 是**局部坐标**（挂在 fieldGroup 下，随主田平移）。
  // 必须排在树带之后：树带最远到 z≈-160，
  // 远山若更近会把树挡在山后面 —— 那是"树被山盖住"问题的另一种形态。
  //
  // 三层从远到近递减距离，配合 fog 做出空气透视。
  //
  // ⚠️ **z 与 fogFar 是配套的**（2026-10-22 标定）：
  //   相机水平距离 ry = cos(0.215)*11.8 ≈ 11.5m，
  //   雾浓度 = (距相机 - fogNear) / (fogFar - fogNear)，fogNear=46 / fogFar=240。
  //   三层分别落在 **69% / 78% / 87%** 雾浓度上 —— 越远越淡。
  //   （实测：87% 的远层会和天空几乎同色 → 只剩一点山尖，这也符合空气透视）
  //   树带最远 z=-162（雾 66%）仍在远山之前，不会穿模。
  //
  // ⚠️ **山体必须吃场景雾（fog: true），基色用自然绿褐（不是纯灰）**。
  //
  // 原来是 `fog:false` + 写死的冷色山体（`#5f8068`/`#a2bba2`）：
  //   · 不吃雾 → 换季时山体颜色不变，秋天暖雾里配冷绿山 → 割裂
  //   · 写死颜色 → 每季都要单独调一遍，漏一季就穿帮
  //
  // 现在改成：山体吃场景雾，three.js 会按距离自动把山体往当季雾色里"褪"，
  // 远近三层的层次也由雾自然生成 —— 既贴合季节又不用硬编码。
  //
  // ⚠️ **基色不能是纯灰**（试过 '#7d878a' 这类中性灰，结果夏季远山直接消失）：
  //   夏季雾是青蓝（189,217,232），纯灰山被青蓝雾一冲就与天空完全同色 → 隐形。
  //   用**自然绿褐**（山该有的颜色）才能在青蓝雾下保留轮廓：
  //   67% 雾时山体还剩 ~33% 的本色，与青蓝天空拉开色相差。
  //
  // ⚠️ **山高必须让仰角落进「可见区」**（2026-10-23 画质升级，量化实测）：
  //
  //   相机 fov=48°、pitch=0.215 rad → **可见仰角只有 -11.7°~+11.7°**，
  //   眼高 = 0.95 + sin(0.215)*11.8 ≈ **3.47m**。
  //   原来的三层高度 104 / 66 / 44m，对应仰角：
  //     近层(z=-166,h=44) 12.9°  ← 已在可见区之上
  //     中层(z=-180,h=66) 18.1°  ← 完全看不见
  //     远层(z=-196,h=104) 25.8° ← 完全看不见
  //   结果：远山**整个落在画面上方之外**，用户只看到一片灰白天空。
  //   （这不是"被雾冲没"，是根本不在取景框里 —— 之前一直误判为雾的问题。）
  //
  //   现在把山高压到 26 / 40 / 58m，仰角变成 7.2° / 10.5° / 14.2°：
  //     近层 7.2°  → 稳稳落在可见区中部 ✔
  //     中层 10.5° → 落在可见区上缘 ✔
  //     远层 14.2° → 略高于可见区，只有山尖露出
  //       （这正是真实远景的样子：越远的山只露出更高的山顶）✔
  //
  //   **z 不能改**：z=-168 是树带最远端(-162)之后最近的位置，
  //   再往前挪就会与树带穿模（"树长在山上"）。
  //
  // ⚠️ **近层山必须最深**（这是远山能不能被看见的关键）：
  //   空气透视是"越远越淡"，所以最近的层要最深，才能形成深→浅的梯度。
  //   近层用深墨绿（#2f3d35），在 69% 雾下还剩 ~31% 本色
  //   → 深色山脊线清晰可辨；远层用浅色，融进天空。
  const defs = [
    // 近层：最深，轮廓最清晰
    { w: 620, h: 26, z: -168, seed: 3, top: '#2f3d35', bottom: '#5c6b60', rough: 0.62, sharp: 0.85, op: 0.97 },
    // 中层：稍淡
    { w: 700, h: 40, z: -186, seed: 11, top: '#3b4a41', bottom: '#6b7a6f', rough: 0.58, sharp: 0.95, op: 0.94 },
    // 远层：最淡，只露山尖
    { w: 780, h: 58, z: -204, seed: 29, top: '#4a584f', bottom: '#7d8b80', rough: 0.55, sharp: 1.05, op: 0.9 }
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
        // 季节色相微调：贴图是自然绿褐，秋天乘暖褐、冬天乘冷灰，
        // 让山体在当季雾色里既不隐形也不偏冷。
        color: new THREE.Color(tint),
        transparent: true,
        opacity: d.op,
        depthWrite: false,
        // ⚠️ **depthTest 必须保持 true**。
        //
        // 曾经的写法是 depthTest:false，理由是"让远山彻底不参与深度竞争，
        // 这样 camera.far 可以收得很紧"。但这会把远山变成一层**贴在屏幕上的贴纸**：
        // three.js 把 transparent 物体排到不透明 pass **之后**渲染，
        // 而 depthTest:false 又让它无视深度缓冲 —— 于是 z=-74~-150 的林带树木
        // （不透明 pass）画完后，山体在透明 pass 里直接盖在它们头上，
        // 表现为"远山遮住近处的树"，树只剩上半截。
        //
        // 正确做法：远山参与深度测试。
        //   · 树（不透明）先画并写入深度 → 远山片段深度更大 → 被剔除 → 树完整可见
        //   · 远山 z=-168/-186/-204，与相机距离约 180/198/216，
        //     都在 camera.far=600 之内，不会被远裁剪面切掉。
        // 注意远山是**竖直平面**，整片平面的视空间深度几乎相同
        // （宽度方向不改变视线方向的深度），所以侧面不会因 far 而缺角。
        depthTest: true,
        // ⚠️ **远山吃场景雾**（fog: true）。
        // three.js 会按距离把山体往当季雾色里褪：越远的山越接近雾色/天空色，
        // 于是空气透视自动成立，且**换季不用重调山色**。
        // 代价是雾浓度 100% 的最远层会完全融进天空（这正是想要的效果）。
        fog: true
      })
    )
    mesh.position.set(0, d.h / 2 - 1.0, d.z)
    // renderOrder 只在"同一渲染列表"内生效。远山在透明列表里，
    // -3 让它排在其他透明物件（田面垄沟 0 / 水面 1 / 接触阴影 2）之前，
    // 保证山永远在最底层，不会盖住近处的水面与阴影。
    mesh.renderOrder = -3
    return mesh
  })
}

export { fbmCanvas }
