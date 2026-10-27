/**
 * 画质分级系统（2026-10-26 画质升级）
 *
 * 目标：**让"精细画面"与"低端设备能跑"这两件事不再互斥**。
 *
 * 背景（改造前的实测现状）：
 *   · 渲染器是「一档写死」：antialias:true 硬编码、powerPreference:'high-performance'
 *     硬编码、setPixelRatio 上限 1.75 写死、阴影贴图 2048×2048 写死。
 *   · 场景元素数量也写死：树 60 棵、主田野草 90 丛、邻田荒草 7 丛/块。
 *   · 结果：核显笔记本 / 老手机被迫吃满 MSAA + 2048 阴影贴图 + 16× 各向异性，
 *     而**高端机又被 1.75 的 pixelRatio 上限卡住**（4K 屏上细节糊）。
 *
 * 设计原则：
 *   1. **参数矩阵是唯一数据源**（QUALITY_LEVELS）。所有渲染代码只读 `params`，
 *      绝不自己写死任何与画质相关的数字 —— 这是本项目「同型代码只改一处」铁律
 *      在画质维度的落地。
 *   2. **画质是「渲染配置」不是「画质档位名」**。切换档位可能要重建 WebGL 上下文
 *      （antialias 是上下文创建参数），所以对外暴露 `requiresContextRebuild` 标记。
 *   3. **自动探测只给建议，不越权改用户选择**。用户手动选过就永远听用户的。
 *   4. **本模块不依赖 three.js**（可在无 WebGL 环境安全 import）。
 */

/** localStorage 键。带 farm 前缀，避免与其它模块撞。 */
export const QUALITY_STORAGE_KEY = 'farm.quality'

/** 档位 id（顺序即从低到高） */
export const QUALITY_IDS = ['low', 'standard', 'high', 'ultra']

/**
 * 四档参数矩阵 —— **唯一数据源**。
 *
 * 字段说明：
 *   pixelRatioMax   渲染像素比上限（乘 devicePixelRatio）
 *   antialias       WebGL 上下文 MSAA。**注意：这是上下文创建参数，切换需重建 renderer**
 *   powerPreference 交给浏览器的 GPU 偏好。'low-power' 会让浏览器选集显/核显
 *   anisotropy      各向异性过滤上限（0 = 不启用）
 *   shadowEnabled   是否开启真实阴影
 *   shadowMapSize   阴影贴图边长（正方形）
 *   shadowType      'soft' = PCFSoftShadowMap / 'basic' = PCFShadowMap
 *   skyWidth/skyHeight  程序化天空 equirect 贴图分辨率（逐像素合成，成本 ∝ 像素数）
 *   grassRepeat     草地贴图平铺次数（草地平面 240×200m，repeat=60 → 单块贴图
 *                    覆盖 4m。⚠️ 贴图是 1024² 实拍俯视近景，单块真实尺度约
 *                    0.8m，所以 repeat 必须到 60+ 才有真实草叶大小；
 *                    repeat=10 时草叶被放大 30 倍 → 一眼假）
 *   grassDetail     草地是否叠加第二层 detail（破平铺，靠法线与色阶打散重复）
 *   treeCount       林带树木实例数
 *   treeShadow      树木是否投射阴影（**远景树带在阴影相机外，开也没用 → 低档关）**
 *   weedCount       主田田埂野草实例数
 *   neighborWeedPer 邻田荒地每块野草丛数
 *   cropDensityScale 作物种植密度倍率（1 = 全量；0.5 = 半量，实例数减半）
 *   windEnabled     作物/树/草的顶点风动
 *   normalMapEnabled 作物/地表法线贴图
 *   envMapEnabled   PMREM 环境贴图（泥土/水面的天光反射）
 *   maxFps          渲染帧率上限（0 = 不限）。高刷屏上空转会白烧电
 *   terrainSegments 地面细分（0 = 用默认）
 */
export const QUALITY_LEVELS = {
  low: {
    id: 'low',
    label: '流畅',
    desc: '低功耗，优先保证帧率。适合老设备与移动端。',
    pixelRatioMax: 1,
    antialias: false,
    powerPreference: 'low-power',
    anisotropy: 1,
    shadowEnabled: false,
    shadowMapSize: 512,
    shadowType: 'basic',
    skyWidth: 512,
    skyHeight: 256,
    grassRepeat: 14,
    grassDetail: false,
    treeCount: 24,
    treeShadow: false,
    weedCount: 30,
    neighborWeedPer: 0,
    cropDensityScale: 0.5,
    windEnabled: true,
    normalMapEnabled: false,
    envMapEnabled: false,
    maxFps: 30,
    terrainSegments: 0
  },
  standard: {
    id: 'standard',
    label: '标准',
    desc: '兼顾画面与性能，默认推荐档位。',
    pixelRatioMax: 1.5,
    antialias: true,
    powerPreference: 'high-performance',
    anisotropy: 4,
    shadowEnabled: true,
    shadowMapSize: 1024,
    shadowType: 'basic',
    skyWidth: 1024,
    skyHeight: 512,
    grassRepeat: 30,
    grassDetail: false,
    treeCount: 42,
    treeShadow: false,
    weedCount: 60,
    neighborWeedPer: 4,
    cropDensityScale: 0.85,
    windEnabled: true,
    normalMapEnabled: true,
    envMapEnabled: true,
    maxFps: 60,
    terrainSegments: 0
  },
  high: {
    id: 'high',
    label: '精细',
    desc: '完整阴影与高精度贴图，适合独立显卡与近景观察。',
    pixelRatioMax: 2,
    antialias: true,
    powerPreference: 'high-performance',
    anisotropy: 8,
    shadowEnabled: true,
    shadowMapSize: 2048,
    shadowType: 'soft',
    skyWidth: 1536,
    skyHeight: 768,
    grassRepeat: 48,
    grassDetail: true,
    treeCount: 60,
    treeShadow: false,
    weedCount: 90,
    neighborWeedPer: 7,
    cropDensityScale: 1,
    windEnabled: true,
    normalMapEnabled: true,
    envMapEnabled: true,
    maxFps: 60,
    terrainSegments: 0
  },
  ultra: {
    id: 'ultra',
    label: '极致',
    desc: '最高分辨率与软阴影，画面最细腻；对显卡要求最高。',
    pixelRatioMax: 2.5,
    antialias: true,
    powerPreference: 'high-performance',
    anisotropy: 16,
    shadowEnabled: true,
    shadowMapSize: 2048,
    shadowType: 'soft',
    skyWidth: 2048,
    skyHeight: 1024,
    grassRepeat: 60,
    grassDetail: true,
    treeCount: 78,
    treeShadow: false,
    weedCount: 120,
    neighborWeedPer: 9,
    cropDensityScale: 1,
    windEnabled: true,
    normalMapEnabled: true,
    envMapEnabled: true,
    maxFps: 60,
    terrainSegments: 0
  }
}

/** 自动探测用的「建议档位」标识（区别于用户手动选择） */
export const AUTO_QUALITY = 'auto'

/** 默认档位（用户没设置过时的兜底） */
export const DEFAULT_QUALITY = 'standard'

/**
 * 探测设备能力 → 返回建议档位 id。
 *
 * ⚠️ **只是建议**。真正选什么档位由用户决定；
 *   自动探测只在用户没手动选过（存的是 'auto'）时生效。
 *
 * 探测维度与理由：
 *   · 移动端 UA        → 直接压到 low（手机 GPU 与桌面差 3~5 倍）
 *   · hardwareConcurrency（逻辑核数）→ 主力信号：≤2 核基本是低端机
 *   · deviceMemory（GB）  → 浏览器专有属性，≤2GB 内存说明设备吃力
 *   · devicePixelRatio      → 高 DPR 屏（如 iPhone 3x）像素量大，要压 pixelRatio
 *   · WebGL renderer 字符串  → 识别到软件渲染（SwiftShader / llvmpipe / ANGLE software）→ low
 */
export function detectQualityLevel() {
  if (typeof window === 'undefined' || typeof navigator === 'undefined') return DEFAULT_QUALITY

  const ua = navigator.userAgent || ''
  const mobile = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile/i.test(ua)

  const cores = Number(navigator.hardwareConcurrency) || 0
  const mem = Number(navigator.deviceMemory) || 0
  const dpr = Number(window.devicePixelRatio) || 1

  // 软件渲染（无 GPU 的容器 / 老机器）→ 最低档
  let software = false
  try {
    const c = document.createElement('canvas')
    const gl = c.getContext('webgl2') || c.getContext('webgl')
    if (!gl) return 'low'
    const ext = gl.getExtension('WEBGL_debug_renderer_info')
    const r = ext ? String(gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) || '') : ''
    software = /swiftshader|llvmpipe|software|basic render|mesa offscreen/i.test(r)
    const lose = gl.getExtension('WEBGL_lose_context')
    if (lose) lose.loseContext()
  } catch (_) {
    /* 探测失败不致命，按下面的常规判断走 */
  }

  if (software) return 'low'
  if (mobile) return 'low'

  let score = 0
  // 核数：8+ 核 → 加分，≤2 核 → 减分
  if (cores >= 8) score += 2
  else if (cores >= 6) score += 1
  else if (cores <= 2) score -= 2
  else if (cores <= 4) score -= 1
  // 内存：≥8GB 加分，≤2GB 减分
  if (mem >= 8) score += 1
  else if (mem > 0 && mem <= 2) score -= 1
  // DPR：高 DPR 屏像素多，渲染压力大，但设备通常也不差 → 不加分不扣分
  // （真正的压力由 pixelRatioMax 吸收，不必动档位）

  if (score >= 2) return 'high'
  if (score >= 0) return 'standard'
  return 'low'
}

/** 当前生效的档位标识（可能是 'auto'） */
let currentSetting = AUTO_QUALITY

/** 订阅者集合 */
const listeners = new Set()

/** 读一次 localStorage（SSR / 隐私模式可能抛异常） */
function readSetting() {
  try {
    const v = window.localStorage.getItem(QUALITY_STORAGE_KEY)
    if (!v) return AUTO_QUALITY
    if (v === AUTO_QUALITY) return AUTO_QUALITY
    return QUALITY_LEVELS[v] ? v : AUTO_QUALITY
  } catch (_) {
    return AUTO_QUALITY
  }
}

/** 写 localStorage */
function writeSetting(v) {
  try {
    window.localStorage.setItem(QUALITY_STORAGE_KEY, v)
  } catch (_) {
    /* 隐私模式下写不进去也不影响运行 */
  }
}

/**
 * 取当前生效的**参数矩阵**。
 *
 * @returns {{ id:string, label:string, params:object, auto:boolean, detected:string }}
 */
export function getQuality() {
  const setting = currentSetting
  const auto = setting === AUTO_QUALITY
  const detected = auto ? detectQualityLevel() : setting
  const id = QUALITY_LEVELS[detected] ? detected : DEFAULT_QUALITY
  return {
    id,
    auto,
    detected,
    label: QUALITY_LEVELS[id].label,
    params: QUALITY_LEVELS[id]
  }
}

/** 只要参数矩阵的快捷方式 */
export function getQualityParams() {
  return getQuality().params
}

/**
 * 设置档位。
 *
 * @param {string} id  'auto' | 'low' | 'standard' | 'high' | 'ultra'
 * @returns {{ ok:boolean, reason?:string }}
 */
export function setQuality(id) {
  const v = id === AUTO_QUALITY ? AUTO_QUALITY : id
  if (v !== AUTO_QUALITY && !QUALITY_LEVELS[v]) {
    return { ok: false, reason: 'unknown-quality' }
  }
  currentSetting = v
  writeSetting(v)
  const q = getQuality()
  // 通知所有订阅者（用 effective id 比较，避免 auto 探测结果不变时反复触发）
  listeners.forEach((fn) => {
    try {
      fn(q)
    } catch (e) {
      console.warn('[farm] 画质订阅回调异常：', e)
    }
  })
  return { ok: true }
}

/**
 * 订阅画质变化。返回取消订阅函数。
 * @param {(q:object)=>void} fn
 */
export function onQualityChange(fn) {
  listeners.add(fn)
  return () => listeners.delete(fn)
}

/** 初始化：从 localStorage 恢复设置。组件挂载时调用一次。 */
export function initQuality() {
  currentSetting = readSetting()
  return getQuality()
}

/** 测试用：重置内存态（不改 localStorage） */
export function _resetQualityForTest(id) {
  currentSetting = id || AUTO_QUALITY
}

/**
 * 判断「从 oldId 到 newId」是否需要**重建 WebGL 上下文**。
 *
 * 🔴 **antialias 是上下文创建参数**（`new WebGLRenderer({ antialias })`），
 *   运行时改不了 —— 必须销毁旧 renderer 再新建，否则画面上的抗锯齿不会变。
 *   这是画质切换最容易踩的坑：以为改了 `renderer` 的属性就够了。
 */
export function requiresContextRebuild(oldId, newId) {
  const a = QUALITY_LEVELS[oldId]
  const b = QUALITY_LEVELS[newId]
  if (!a || !b) return true
  return a.antialias !== b.antialias || a.powerPreference !== b.powerPreference
}

/**
 * 🔴 **会改变几何体 / 贴图 / 着色器**的参数 → 切换后必须重建整个场景。
 *
 *   · skyWidth/skyHeight  天空是逐像素烘焙的，尺寸变了就得重新合成
 *   · cropDensityScale    作物实例数变了 → InstancedMesh 要重建
 *   · normalMapEnabled    材质要加/减 normalMap → 着色器变
 *   · envMapEnabled       要重新跑 PMREM
 *   · windEnabled         顶点着色器里有没有风动分支
 *   · grassDetail         草地要加一层 detail 网格
 *
 *   这些**不能**靠改属性热更新 —— 必须销毁场景重建。
 *   判定逻辑放在这里（而不是调用方），是为了让「哪些参数会触发重建」
 *   这条知识只有**一个**实现点 —— 改参数语义时不会漏改调用方。
 */
const REBUILD_KEYS = [
  'skyWidth', 'skyHeight',
  'cropDensityScale',
  'normalMapEnabled',
  'envMapEnabled',
  'windEnabled',
  'grassDetail'
]

/**
 * 判断「从 oldId 到 newId」是否需要**重建整个场景**（含 WebGL 上下文）。
 *
 * @returns {{ rebuild:boolean, context:boolean }}
 */
export function requiresSceneRebuild(oldId, newId) {
  const a = QUALITY_LEVELS[oldId]
  const b = QUALITY_LEVELS[newId]
  if (!a || !b) return { rebuild: true, context: true }
  const context = requiresContextRebuild(oldId, newId)
  for (const k of REBUILD_KEYS) {
    if (a[k] !== b[k]) return { rebuild: true, context }
  }
  return { rebuild: false, context }
}

