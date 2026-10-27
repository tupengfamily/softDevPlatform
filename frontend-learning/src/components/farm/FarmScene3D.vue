<template>
  <div ref="wrap" class="farm3d">
    <canvas ref="canvas" class="farm3d-canvas"></canvas>
    <div v-if="!ready" class="farm3d-loading">
      <span class="spinner"></span>
      <div class="loading-text">
        <strong>正在生成实景农田</strong>
        <em>{{ loadText }}</em>
      </div>
      <div class="loading-bar"><i :style="{ width: progress + '%' }"></i></div>
    </div>
  </div>
</template>

<script setup>
/**
 * 「稼穑」实景农田场景（Three.js + 真实照片素材）
 *
 * 本组件只负责**编排**：相机、交互、天气粒子、生命周期。
 * 静态配置在 ./scene/config.js，纯函数在 ./scene/util.js / sky.js，
 * 资产加载在 ./scene/assets.js，作物层在 ./scene/crops.js。
 *
 * 关键约束（历史踩坑）：
 *   - canvas 必须 absolute inset:0，尺寸取父容器而不是 window
 *   - shadowMap 已开启（2026-10-23 实测沙箱内可用，见 init() 注释）
 *   - 精灵用 alphaTest 硬切，避免透明排序糊成一团
 *   - 相机 near 不能太小（0.4），否则远处草地与邻田共面会 z-fighting 闪烁
 */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import {
  SEASON, PADDY_W, PADDY_D, FIELD_CZ, CAMERA, LOOK_AT, SPRITE_ASPECT,
  normalizeCrop, cropFamily, densityOf, fallbackTextureOf, CROP_TINT
} from './scene/config.js'
import {
  mulberry32, makeRowShadeTexture, makeSoilWetnessTexture, boxWithUV, buildCrossQuad,
  spherifyNormals, addWindShader, addUvJitter, tickAllWind, clearWindRegistry, clamp
} from './scene/util.js'
import { makeSkyTexture, makeRidgeLayers } from './scene/sky.js'
import { createAssetPipeline } from './scene/assets.js'
import { createCropField } from './scene/crops.js'
import { getQuality, getQualityParams, initQuality, onQualityChange, setQuality, requiresSceneRebuild } from './scene/quality.js'

const props = defineProps({
  stage: { type: Number, default: 2 },
  season: { type: String, default: 'summer' },
  crop: { type: String, default: 'rice' },
  water: { type: Number, default: 20 },
  weather: { type: Object, default: () => ({}) },
  cells: { type: Array, default: () => [] },
  /** 全部地块摘要（用于渲染相邻田块） */
  plots: { type: Array, default: () => [] },
  /** 当前选中的地块槽位 */
  slot: { type: Number, default: 0 },
  /** field = 贴近看作物；map = 俯瞰整片农场，用来选点建田 */
  view: { type: String, default: 'field' }
})
const emit = defineEmits([
  'ready', 'fail', 'select-plot', 'create-plot',
  'fps', 'auto-downgrade', 'quality-changed', 'rebuild-scene'
])

const wrap = ref(null)
const canvas = ref(null)
const ready = ref(false)
const progress = ref(0)
const loadText = ref('准备中')

let THREE = null
let renderer = null
let scene = null
let camera = null
let pipeline = null
let cropField = null
/**
 * 实际用上的贴图文件名（key → 'plant_xxx_young.png'）。
 * 降级到通用族图时，画布宽高比要按**族图**的实测值取，否则面片会被拉扁。
 */
let usedTexFiles = null
let raf = 0
let ro = null
let disposed = false
let pmrem = null
let envRT = null

let skyMesh = null
let grassMesh = null
let groundMesh = null
let waterMesh = null
let soilWetMesh = null
let hemiLight = null
let sunLight = null
let fillLight = null
let weatherObj = null
let treeMesh = null
let weedMesh = null
/** 草地第二层 detail（破平铺），仅精细档以上才有 */
let grassDetailMesh = null
let farFieldMeshes = []
/**
 * 🔴 **当前画质参数矩阵**（2026-10-26 画质分级）。
 *
 *   全部渲染相关数字都从这里读，组件里**不再写死任何画质相关的常量**。
 *   画质切换时若参数变化需要重建场景（几何/贴图/着色器变了），
 *   由 requiresSceneRebuild() 判定 —— 判定逻辑只在 quality.js 一处。
 */
let Q = getQualityParams()
/** 取消订阅画质变化的句柄 */
let unsubscribeQuality = null
/** 主田（含作物/水面/田埂）挂在它下面，切换地块时整体平移到目标位置 */
let fieldGroup = null
/** 相邻田块（只看外观，不参与操作） */
let neighborGroup = null

/** 每排几块地、田块之间的中心距 */
const PLOT_COLS = 4
const MAX_SLOT = 100
const PITCH_X = PADDY_W + 2.4
const PITCH_Z = PADDY_D + 3.2

/**
 * 地面各层的 Y 高度（分层是为了杜绝共面 z-fighting）。
 *
 * 远景农田（纯装饰，铺到 z=-96）和玩家可交互的邻田在平面上是重叠的，
 * 拖动相机时两者互相穿插，表现为远处地块成片闪烁。
 *
 * ⚠️ 关键认知：24-bit 深度缓冲下，**只要两个重叠面 dy > 0（哪怕 1 毫米），
 *    就绝不可能 z-fighting**。真正的问题是 dy == 0（完全共面）。
 *    之前 6 块远景农田共用一个 `farMat`，y 全是 0、polygonOffset 也相同
 *    → 偏移后深度值依然相等 → 纹丝不动地闪。
 *
 * 所以分层规则只有一条：**任何两个在 XZ 上重叠的面，y 必须严格不同**。
 *
 *   草地        : -0.020（最底，整张大地毯）
 *   远景 6 块   :  0.000 ~ 0.015（步进 0.003，彼此错开，全在草地之上）
 *   装饰近景田  :  0.017 / 0.019（x=±17，固定的左右两块装饰）
 *   邻田·空地   :  0.021 ~ 0.030（按 slot 错开）
 *   邻田·已种   :  0.035 ~ 0.044（最上层，玩家最关心的地块）
 *
 * 另外每块面片还要用**独立的克隆材质**（各自不同的 polygonOffset）做第二重保险。
 */
const GRASS_Y = -0.02
const FAR_FIELD_Y = 0.0
const FAR_FIELD_STEP = 0.003
const DECOR_FIELD_Y = 0.017
const DECOR_FIELD_STEP = 0.002
const VACANT_Y = 0.021
const VACANT_STEP = 0.003
const NEIGHBOR_Y = 0.035
const NEIGHBOR_STEP = 0.003
const PLOT_Y = NEIGHBOR_Y

/**
 * 田埂规格（米）：高 × 顶宽。
 *
 * ⚠️ **主田与邻田必须用同一组值**，否则俯视时会出现"这块田的埂比那块高一截"。
 * 原来主田 0.36 高 / 0.78 宽，邻田 0.55 高 / 0.72 宽 —— 邻田高一倍，
 * 从高处看整片农场像"高低错落的积木"，不真实。
 * 真实水田田埂约 30~40cm 高、50~80cm 宽，取 0.38 / 0.76。
 */
const BUND_H = 0.38
const BUND_W = 0.76

/**
 * 天空球半径。
 *
 * ⚠️ **必须小于 camera.far**（当前 600）：球面一旦落在远裁剪面之外，
 * 画面上方就会露出大片黑色清屏色（看起来像天塌了个洞）。
 * 天空球在 applyCamera 里跟随相机，等价于把天空放到无穷远，
 * 所以半径只受 far 约束，取 500 留足余量。
 */
const SKY_RADIUS = 500

/**
 * 太阳方向（单一数据源）。
 *
 * ⚠️ **光照、阴影、天空三处必须用同一个值**，否则会出现
 *   "太阳在画面右上、影子却往左下长"这种一眼假的破绽。
 *
 * 约定：单位向量，y > 0 表示太阳在天空上方。
 *   · SUN_DIR      指向太阳的方向（从场景指向太阳）
 *   · SHADOW_DIR   影子的地面投影方向 = 太阳方位角的反方向
 *   · SHADOW_STRETCH 影子被拉长的倍数（= 1/tan(太阳高度角)）
 *
 * 高度角按季节变化（见 SEASON_SUN_EL）：
 *   夏 58° → 影子只有物体高的 0.6 倍，短促（正午前后）
 *   冬 26° → 影子拉到 2.0 倍，长斜（真实冬日的感觉）
 *
 * ⚠️ **高度角必须季节化**（2026-10-23 画质升级）。
 *   原来固定 42°：冬天也用 42°，影子长度和夏天一样，
 *   且太阳仍在可见仰角之外 → 天空光晕盖住整片天，冬天也像夏日炎炎。
 */
const SUN_AZ_BASE = -2.53
/** 各季节太阳高度角（弧度） */
const SEASON_SUN_EL = {
  spring: 0.72,   // 41°
  summer: 1.01,   // 58°
  autumn: 0.66,   // 38°
  winter: 0.45    // 26°
}
/**
 * 待开垦荒地的拼花色（模块作用域，buildNeighbors 用）。
 *
 * ⚠️ **荒地也要拼花**（2026-10-23 画质升级）。
 *   10 块荒地共用同一个 tint → 中景出现一条**单色土带**，
 *   像"塑料布铺在草地上"。真实世界里相邻的休耕地
 *   因为翻耕时间、湿度、残留作物不同，颜色是**深浅不一的**。
 *
 * 注意 three 的 color 是**乘算**（贴图 × tint），
 * 所以这里的 tint 都得比贴图亮，且差异要克制（别变成花田）。
 */
const VACANT_TINTS = [
  0xc4b295, 0xd6c4a6, 0xbfae92, 0xcdb99b,
  0xd9c9ac, 0xb8a68b, 0xd1bea0, 0xc8b797,
  0xdccdad, 0xbbaa8e
]
/**
 * 当前太阳状态（**可变**，换季时由 applySunDirection 原地更新）。
 *
 * ⚠️ 不能用 const：季节切换要改太阳高度角，
 *   而太阳方向被 4 处引用（DirectionalLight / 天空光晕 / 影子方向 / 影子拉长）。
 *   集中在这个对象里原地改，才能保证换季后这几处仍然一致。
 */
const SUN = {
  dir: { x: 0, y: 0, z: 0 },
  el: 0,
  az: 0,
  shadowDir: { x: 0, z: 0 },
  stretch: 1
}

/**
 * 按季节重算太阳状态，并同步 DirectionalLight（若有）。
 *
 * ⚠️ **换季必须整体重算**（2026-10-23 画质升级）：
 *   只改颜色不改方向 → 冬天太阳还在 42° 高，影子和夏天一样长，一眼假。
 *
 * @param {string} season 季节 key
 */
function applySunDirection(season) {
  const el = SEASON_SUN_EL[season] ?? 0.73
  const az = SUN_AZ_BASE
  const c = Math.cos(el)
  // ⚠️ **原地改，不能整个换对象**：
  //   SUN_DIR / SHADOW_DIR 等常量持有的是对象引用，
  //   重新赋值会让它们指向旧对象，换季后又出现"光影不同源"。
  SUN.dir.x = c * Math.sin(az)
  SUN.dir.y = Math.sin(el)
  SUN.dir.z = -c * Math.cos(az)
  SUN.el = el
  SUN.az = Math.atan2(SUN.dir.x, -SUN.dir.z)
  SUN.shadowDir.x = -SUN.dir.x
  SUN.shadowDir.z = -SUN.dir.z
  SUN.stretch = 1 / Math.tan(el)
  // 同步光照（光源位置与 target 的相对关系 = 太阳方向）
  if (sunLight && fieldGroup) {
    const o = fieldGroup.position
    sunLight.position.set(o.x + SUN.dir.x * 60, SUN.dir.y * 60, o.z + SUN.dir.z * 60)
    sunLight.target.position.set(o.x, 0, o.z)
    sunLight.target.updateMatrixWorld()
  }
}
applySunDirection(props.season)
const SUN_DIR = SUN.dir
/** 影子往哪边偏（单位向量，XZ 平面） */
const SHADOW_DIR = SUN.shadowDir


/**
 * 给同层的面片分配互不相同的深度偏移（**微调级**，不是分层手段）。
 *
 * 每一块都要 clone 一份材质：只有材质不同，polygonOffset 才会真正生效。
 * 同一材质内的所有面片共享同一个偏移量，偏移后仍然共面。
 *
 * ⚠️ **量级红线**（这是踩过坑才写下来的）：
 * `polygonOffsetUnits` 的单位是**深度缓冲的像素槽**，不是米，也不是比例。
 * 24-bit 深度缓冲在 60 米外的相邻两个深度槽间距远小于 1 像素，
 * 于是 units=22 相当于"往深度远端推 22 个像素"——**远远推过头**，
 * 结果不是分层，而是把面片推到草地平面（y=-0.02, po=0）**之后**，
 * 远景田直接被草地整片盖住，一个像素都看不见。
 *
 * 所以这里的偏移只保留**个位数**：
 *   - units 每层最大 4（相当于 4 个深度槽以内，远不足以跨过草地）
 *   - factor 每层最大 1.0
 * 真正的分层靠上面那 9 个 **y 高度常量**（几何错高，dy > 0 是唯一可靠手段），
 * polygonOffset 只用来对付"同高度但不同材质"的最后一点残余抖动。
 *
 * @param {THREE.Material} base   基础材质
 * @param {number} index          在同层中的序号
 * @param {number} sign           1 = 往深度远端推，-1 = 往深度近端提
 */
function cloneWithOffset(base, index, sign = 1) {
  const m = base.clone()
  m.polygonOffset = true
  // 微调级：units 封顶 4，factor 封顶 1.0。绝不能超过这个量级。
  m.polygonOffsetFactor = sign * Math.min(1, 0.25 + index * 0.15)
  m.polygonOffsetUnits = sign * Math.min(4, 1 + index)
  m.needsUpdate = true
  return m
}

/**
 * 当前渲染上下文能否用 `alphaToCoverage`（2026-10-24 画质升级）。
 *
 * ⚠️ **必须同时满足三个条件**：
 *   ① WebGL2 —— `SAMPLE_ALPHA_TO_COVERAGE` 在 WebGL1 里不存在，
 *      three 会直接 `gl.enable(undefined)` 抛错。
 *   ② MSAA 开启 —— 抗锯齿样本不存在时 alphaToCoverage 无意义。
 *   ③ 上下文属性 antialias=true —— 我们的 renderer 就是这么建的。
 *
 * ⚠️ **不满足时必须退回纯 alphaTest**：
 *   alphaToCoverage 依赖 MSAA 样本做"部分覆盖"，没有 MSAA 时
 *   边缘会退化成纯 discard（比不开还硬）。
 *
 * @returns {boolean}
 */
function canUseAlphaToCoverage() {
  try {
    const gl = renderer?.getContext?.()
    if (!gl || typeof WebGL2RenderingContext === 'undefined') return false
    if (!(gl instanceof WebGL2RenderingContext)) return false
    return gl.getContextAttributes?.()?.antialias === true
  } catch (_) {
    return false
  }
}

/** 槽位 → 世界坐标（田块网格依次排列） */
function plotOrigin(slot) {
  const i = Math.max(0, slot | 0)
  return {
    x: (i % PLOT_COLS) * PITCH_X,
    z: -Math.floor(i / PLOT_COLS) * PITCH_Z
  }
}

const cam = { ...CAMERA }
const look = { ...LOOK_AT }

/** 资源 → 加载文案 */
const ASSET_LABEL = {
  soilMap: '翻耕田面', soilNorm: '土壤细节', soilRough: '土壤墒情',
  grassMap: '田边草地', grassNorm: '草地细节', fieldMap: '远处农田',
  young: '作物幼苗', ripe: '作物成株', tree: '林带树木', weed: '田埂野草',
  youngNorm: '幼苗叶脉', ripeNorm: '成株叶脉'
}

/**
 * 作物 → 需要的贴图清单（稻作与旱作各用一套土壤）。
 *
 * ⚠️ 2026-10-24：作物从 8 个通用形态族扩到 **22 种作物专属形态**，
 * 贴图名按 cropId 拼（`plant_<cropId>_young.png`）——这部分天然支持 22 种。
 *
 * 但**专属贴图还没全部裁好**，所以给每种新作物挂一个 `fallbackFile`
 * （形态最接近的通用族图）。专属图 404 时自动退到族图：
 * 最坏情况是「西瓜长得像黄瓜」，而不是「田里立着两块纯色方块」。
 */
function assetSpecs(cropId) {
  const soil = cropId === 'rice' ? 'soil_paddy' : 'soil_dry'
  const fb = fallbackTextureOf(cropId)
  const fallbackFile = fb ? `plant_${fb}_young.png` : null
  const fallbackRipe = fb ? `plant_${fb}_ripe.png` : null
  // ⚠️ **法线贴图必须与 albedo 同名同生命周期**（2026-10-24 画质升级）。
  //   法线贴图承载"叶片内部纹理"（叶脉、卷边、褶皱）——
  //   没有它，作物受光后只有整株级别的明暗，叶片内部完全是平的。
  //   降级时法线图跟着一起降级（族图没有法线图 → 留 null = 不设 normalMap）。
  const fallbackNorm = fb ? `plant_${fb}_young_n.png` : null
  const fallbackNormRipe = fb ? `plant_${fb}_ripe_n.png` : null
  return [
    { key: 'soilMap', file: `${soil}_albedo.jpg`, mirror: true },
    { key: 'soilNorm', file: `${soil}_normal.jpg`, srgb: false, mirror: true },
    { key: 'soilRough', file: `${soil}_rough.jpg`, srgb: false, mirror: true },
    { key: 'grassMap', file: 'ground_grass_albedo.jpg', mirror: true },
    { key: 'grassNorm', file: 'ground_grass_normal.jpg', srgb: false, mirror: true },
    // 🔴 **草地粗糙度图**（2026-10-26 真实感升级）：给草地 detail 层用。
    //   细节层要打散平铺感，用**草地自己的**粗糙度图（不能借土的 ——
    //   土的粗糙度分布叠在草地上会显出"土块"，更假）。
    { key: 'grassRough', file: 'ground_grass_rough.jpg', srgb: false, mirror: true },
    { key: 'fieldMap', file: 'field_rice_albedo.jpg', mirror: true },
    { key: 'young', file: `plant_${cropId}_young.png`, fallbackFile },
    { key: 'ripe', file: `plant_${cropId}_ripe.png`, fallbackFile: fallbackRipe },
    { key: 'youngNorm', file: `plant_${cropId}_young_n.png`, fallbackFile: fallbackNorm, srgb: false },
    { key: 'ripeNorm', file: `plant_${cropId}_ripe_n.png`, fallbackFile: fallbackNormRipe, srgb: false },
    { key: 'tree', file: 'plant_tree.png' },
    { key: 'weed', file: 'plant_grass_tuft.png' }
  ]
}

/** 贴图族名（用哪张精灵图） */
function currentCrop() {
  return normalizeCrop(props.crop)
}

/**
 * 作物 id（决定种多密）。
 *
 * ⚠️ 2026-10-24：22 种作物现在**贴图与密度都按作物走**，
 * 所以这里直接用 props.crop（未知的走 normalizeCrop 兜底）。
 */
function currentDensityKey() {
  return normalizeCrop(props.crop)
}

function seasonCfg() {
  return SEASON[props.season] || SEASON.summer
}

// ---------------- 场景搭建 ----------------

let assets = null

function buildSky() {
  const cfg = seasonCfg()
  if (skyMesh) {
    scene.remove(skyMesh)
    skyMesh.geometry.dispose()
    skyMesh.material.map?.dispose()
    skyMesh.material.dispose()
    skyMesh = null
  }
  // ⚠️ **太阳高度角必须用 SUN.el 单一数据源**（2026-10-23 修正 + 季节化）。
  //
  //   原来是 `props.season === 'winter' ? 0.28 : 0.52`，
  //   而 DirectionalLight 用的是 SUN_DIR（高度角 42.1° = 0.735 rad）。
  //   两处不一致 → **天空里的太阳光晕画在 30° 高度，
  //   实际光照却从 42° 来**，影子方向与太阳位置对不上，
  //   一眼假。现在统一走 SUN 状态对象（季节化后可随换季更新）。
  const sunEl = SUN.el
  const canvasSky = makeSkyTexture(cfg, SUN.az, sunEl, Q.skyWidth, Q.skyHeight)
  const skyTex = new THREE.CanvasTexture(canvasSky)
  skyTex.colorSpace = THREE.SRGBColorSpace
  skyTex.mapping = THREE.EquirectangularReflectionMapping
  skyTex.anisotropy = Math.min(Q.anisotropy, 4)

  skyMesh = new THREE.Mesh(
    // 半径必须 < camera.far（600），否则球面会被远裁剪面切掉，
    // 画面上方露出大片黑色清屏色（黑洞）。
    // 配合"天空球跟随相机"（见 applyCamera），天空等价于在无穷远，
    // 半径只需大于近处地形的距离即可。
    new THREE.SphereGeometry(SKY_RADIUS, 64, 32),
    new THREE.MeshBasicMaterial({
      map: skyTex, side: THREE.BackSide, depthWrite: false, depthTest: false, fog: false,
      // ⚠️ **天空不参与 tone mapping**（2026-10-23 实测）。
      //
      //   天空贴图是用 canvas 按"最终显示色"逐像素烘焙的（已经是 sRGB 显示空间），
      //   再走一遍 ACESFilmic 会把它**向白色压**：
      //     实测同一行天空的横向亮度 range
      //       toneMapped=true  → min171 max213 range 42（对比度被吃掉一半）
      //       toneMapped=false → min154 max215 range 60
      //   云的高光和云的暗部被拉平 → 天空"洗白"，没有层次。
      //   这也是"天空看起来像白墙"的直接原因（不是雾的问题）。
      toneMapped: false
    })
  )
  skyMesh.renderOrder = -100
  skyMesh.position.copy(camera?.position || new THREE.Vector3())
  scene.add(skyMesh)
  // 换季重建后必须重新贴回相机位置，否则新球会留在原点，
  // 相机拉远后又会露出黑色。
  if (camera) skyMesh.position.copy(camera.position)

  // 天空图直接当 equirect 环境图：泥土与水面才有天光反射
  // 🔴 **PMREM 按画质档位**（2026-10-26）：PMREM 是全屏多次模糊 pass，
  //   在低端机上是一次明显的固定开销（换季重建时尤其明显）。
  try {
    if (Q.envMapEnabled) {
      if (!pmrem) pmrem = new THREE.PMREMGenerator(renderer)
      pmrem.compileEquirectangularShader()
      envRT?.dispose?.()
      envRT = pmrem.fromEquirectangular(skyTex)
      scene.environment = envRT.texture
    } else {
      envRT?.dispose?.()
      envRT = null
      scene.environment = null
    }
  } catch (e) {
    /* 环境贴图失败不影响主流程 */
  }
}

/**
 * 🔴 **地面微起伏**（2026-10-26 真实感升级）
 *
 *   对草地平面做低频顶点位移，让地面不再是一张绝对平的纸。
 *   完全平的地面是「3D 场景」最明显的破绽：掠射角下没有任何明暗变化，
 *   地平线附近也是一条死平的线。
 *
 *   位移量取两层不同频率的正弦叠加（比噪声便宜，且不会有高频毛刺）：
 *   · 主层：波长约 47m、振幅 6cm（大尺度缓坡）
 *   · 次层：波长约 13m、振幅 2.5cm（中尺度起伏）
 *
 *   ⚠️ **必须避开主田区域**：
 *   主田是 x∈[-8.3,8.3] / z∈[-15.8,6.8] 的平整面（田面 y=0.004，
 *   草地 y=-0.020，间距 2.4cm）。位移若超过 2.4cm 就会与田面穿插。
 *   这里用一个椭圆「避开区」把主田附近的位移平滑压到 0。
 *
 *   ⚠️ **位移是在平面局部坐标上做的**（PlaneGeometry 未经旋转时 y=0），
 *   旋转 -90° 之后 x→x、z→-y。所以这里改的是**局部 z**（旋转后变成世界 y）。
 */
function displaceGrass(geo) {
  const pos = geo.attributes.position
  const arr = pos.array
  for (let i = 0; i < pos.count; i++) {
    const o = i * 3
    const x = arr[o]
    const y = arr[o + 1]   // 平面未旋转时这是"未来的世界 z"
    const d1 = Math.sin(x * 0.1337 + y * 0.0913) * 0.060
    const d2 = Math.sin(x * 0.4831 - y * 0.3721 + 1.7) * 0.025
    // 避开主田：椭圆距离 → 0~1 的权重（1 = 完全位移，0 = 不动）
    const dx = (x - 0) / 13.5
    const dz = (y - (FIELD_CZ - 4)) / 20
    const dist = Math.sqrt(dx * dx + dz * dz)
    const w = Math.max(0, Math.min(1, (dist - 1) / 0.55))
    arr[o + 1] = (d1 + d2) * w * w
  }
  pos.needsUpdate = true
  geo.computeVertexNormals()
  return geo
}

function buildTerrain() {
  const cfg = seasonCfg()
  const A = assets
  const cropId = currentCrop()
  const paddy = cropId === 'rice'

  // ---------- 草地 ----------
  //
  // 🔴 **草地 repeat 按画质档位**（2026-10-26）：平铺次数越高，
  //   单块贴图覆盖的面积越小 → 近处草叶越细密、远处越不易露出重复网格。
  //
  //   ⚠️ **量级必须算对**（2026-10-26 实测贴图尺寸后修正）：
  //   草地 albedo 是 1024×1024 的**实拍俯视近景**，单块真实尺度约 0.8m。
  //   草地平面 240×200m → 要让草叶保持真实大小，repeat 必须到 **60**（单块 4m）。
  //   原来的 repeat=10 意味着单块贴图铺满 24m → **草叶被放大了 30 倍**，
  //   画面上就是「一大片一尺长的草」，这是草地不像真实的第一号原因。
  const grassRep = Q.grassRepeat
  A.grassMap.repeat.set(grassRep, Math.round(grassRep * 0.8))
  A.grassNorm.repeat.set(grassRep, Math.round(grassRep * 0.8))
  grassMesh = new THREE.Mesh(
    new THREE.PlaneGeometry(240, 200, 40, 34),
    new THREE.MeshStandardMaterial({
      map: A.grassMap,
      normalMap: A.grassNorm,
      normalScale: new THREE.Vector2(0.85, 0.85),
      roughness: 0.92,
      metalness: 0,
      color: cfg.grassTint
    })
  )
  // 🔴 **地面微起伏**（2026-10-26 真实感升级）
  //
  //   完全平整的地面是「3D 游戏场景」最明显的标志之一：
  //   真实田野有微起伏 → 掠射角下的明暗会随地形起伏变化，
  //   草地的边缘轮廓也不是一条死平的线。
  //
  //   实现：对草地平面做**低频顶点位移**（不是噪声贴图 ——
  //   顶点位移能改变**轮廓线**，法线贴图只能改明暗）。
  //   幅度必须极小（±6cm）：大了会与田埂、主田面穿插；
  //   小到 6cm 时只有掠射角才看得出来，正是真实感需要的量级。
  //
  //   ⚠️ **位移必须避开主田区域**：主田在 x∈[-8.3,8.3] / z∈[-15.8,6.8]，
  //   那里要保持绝对平整（否则田面会与地面穿插）。
  //   用一个平滑的"避开区"把主田附近的位移压到 0。
  displaceGrass(grassMesh.geometry)
  grassMesh.rotation.x = -Math.PI / 2
  grassMesh.position.y = GRASS_Y
  grassMesh.receiveShadow = true
  // ⚠️ **草地也必须随主田平移**。
  // 地块网格是 4 列 × N 行，slot 100 在 z=-645，而草地只有 200 米深。
  // 挂在 scene 上（固定原点）时，切到后排地块就会站到草地之外 ——
  // 脚下直接是清屏色。挂在 fieldGroup 下，地块走到哪，草地就跟到哪。
  fieldGroup.add(grassMesh)

  // 🔴 **草地第二层 detail**（2026-10-26 画质分级 · 真实感升级）
  //
  //   问题：单张草地贴图 repeat 30~60 遍后，**平铺网格肉眼可见** ——
  //   远处草地上出现规则的重复色块，一眼假（"游戏场景"而非"照片"）。
  //
  //   解法（业界标准做法）：叠一层**不同平铺比例**的 detail 层，
  //   用低对比度噪声打散重复感。两层的 repeat 比取**无理数比**，
  //   这样两层永远不会在同一个位置重合 → 重复感被彻底打散。
  //
  //   ⚠️ **必须用草地的 roughness 图**（不是 soilRough）：
  //   soilRough 是干土的粗糙度分布，叠在草地上会显出一片片"土块"，
  //   反而更假。ground_grass_rough.jpg 才是草地自身的微观明暗。
  if (Q.grassDetail) {
    const detailTex = A.grassRough.clone()
    // 无理数比：与主层的 repeat 不成整数倍关系
    detailTex.repeat.set(grassRep * 0.73 + 0.31, grassRep * 0.47 + 0.17)
    detailTex.offset.set(0.37, 0.61)
    detailTex.needsUpdate = true
    const detailGeo = new THREE.PlaneGeometry(240, 200, 40, 34)
    // 🔴 **detail 层必须做与主草地完全相同的位移**：
    //   否则草地起伏的地方，detail 层会浮在草地上方/沉在下方 → 穿插闪烁。
    //   用同一个 displaceGrass 函数（唯一实现，不复制参数）。
    displaceGrass(detailGeo)
    const detail = new THREE.Mesh(
      detailGeo,
      cloneWithOffset(new THREE.MeshBasicMaterial({
        map: detailTex,
        blending: THREE.MultiplyBlending,
        transparent: true,
        depthWrite: false,
        // 🔴 **压暗幅度要小**（0.28 而不是 0.34）：
        //   MultiplyBlending 是"逐通道相乘"→ detail 图的暗处会把草地压到
        //   近乎全黑。roughness 图本身是灰度，明暗范围有限，
        //   0.28 已是"能看出层次又不脏"的上限。
        opacity: 0.28,
        color: 0xffffff,
        fog: true
      }), 90, 2)
    )
    detail.rotation.x = -Math.PI / 2
    // 🔴 **必须与草地错开高度**（严格错高是本场景防 z-fighting 的唯一铁律）：
    //   GRASS_Y = -0.020，detail 层取 -0.019（高 1mm）。
    detail.position.y = GRASS_Y + 0.001
    detail.receiveShadow = false
    detail.renderOrder = -1
    fieldGroup.add(detail)
    grassDetailMesh = detail
  }

  // ---------- 邻田（远处 + 左右两侧） ----------
  //
  // 远景农田与"玩家可交互的邻田"在平面上是重叠的（远景铺到 z=-96，邻田也排到那一带）。
  // 两者原本只差 0.03m 的高度，投影到 60 米外不足一个像素，深度缓冲分不开 →
  // 拖动相机时远景地块和邻田互相穿插，表现为成片闪烁。
  //
  // 修法是让它们在**深度上彻底分层**：
  //   1. 远景 6 块彼此在 XZ 上大面积重叠（实测 7 对，最深重叠 261 m²），
  //      所以每块都要错开高度 + 用**独立的克隆材质**（不同 polygonOffset）。
  //      ⚠️ 共用同一材质时 polygonOffset 数值相同 = 偏移后仍然共面，完全无效。
  //   2. 远景整体往深度远端推，邻田往近端提，中间永不争抢同一个深度槽。
  //
  // ⚠️ **坐标系分工（本场景最容易搞混的地方，务必读）**：
  //
  //   【局部坐标】挂在 fieldGroup 下，随主田一起平移（fieldGroup.position = plotOrigin(slot)）：
  //     · 主田自身（田面 / 田埂 / 作物 / 水面 / 野草）
  //     · 邻田（可交互的那些）→ 相对主田的网格偏移 (dx*PITCH_X, -dr*PITCH_Z)
  //     · 远景农田（6 块装饰农田）+ 左右两块装饰近景田
  //     · 树带（林带树木）
  //     · 远山
  //     · 草地
  //
  //   为什么全部用局部坐标？因为地块网格是 4 列 × N 行，slot 100 在 z=-645。
  //   早先树带 / 远景农田 / 远山用**绝对世界坐标**固定在 z=-74~-540，
  //   切到后排地块时（slot 13 主田在 z=-93）树带正好落在主田里 ——
  //   表现为"树长在田中间"、"远山跑到山那头"。
  //   改成局部坐标后，整条纵深链恒定：
  //     主田 → 邻田 → 远景农田 → 树带 → 远山
  //   无论站在哪块田，往远处看都是同一个景深结构。
  //
  A.fieldMap.repeat.set(2.2, 3.0)
  const farMat = new THREE.MeshStandardMaterial({
    map: A.fieldMap,
    roughness: 0.95,
    metalness: 0,
    color: paddy ? 0xffffff : 0xd9dcb4
  })
  // [x, z, 宽, 深, 平面内旋转]
  // ⚠️ z 是**局部坐标**（相对主田），整条纵深链按「雾浓度」分层排布，
  //    这样才有真实的空气透视（越远越淡）：
  //
  //      雾浓度 = (距相机 - fogNear) / (fogFar - fogNear)，fogNear=46 / fogFar=240
  //      相机在主田后方 11.5m，故「距相机」≈ |局部 z| + 11.5
  //
  //      邻田        -4 ~ -67   → 雾 0~17%    （清晰，是玩家的田）
  //      远景农田    -64 ~ -92   → 雾 17~30%   （中景农田）
  //      树带        -95 ~ -162  → 雾 31~66%   （林带，三排递远）
  //      远山        -166 ~ -196 → 雾 67~80%   （三层，越远越淡）
  //
  //    关键是**各层之间不能有平面重叠**，否则又是共面 z-fighting。
  //    树带起点 -95 必须大于远景农田最远端 -92（含半深 -103），
  //    所以远景农田压到 -92 以内，树带从 -95 起。
  //
  // ⚠️ **远景农田要拼花配色**（2026-10-23 画质升级）。
  //
  //   真实农田不是一整片同色的田 —— 是不同作物、不同收割期的**色块拼布**。
  //   原来 6 块远景农田共用同一个材质色，中景就是一大片单色，
  //   看起来像"绿色的塑料布铺在地面"。
  //   现在每块给一个不同的作物色（青秧 / 老麦 / 收割后的裸土 / 深绿蔬菜），
  //   中景立刻有了"这是很多块田拼起来的"感觉。
  //
  //   注意 three 的 color 是**乘算**（贴图 × tint），
  //   所以这里的 tint 要**比贴图更亮**才提得亮，
  //   不能拿深色去"调亮"。
  const FAR_TINTS = [0xd7e6b4, 0xe8e0a8, 0xc4d49a, 0xdfd9b0, 0xcddba6, 0xe2dcae]
  const neighbors = [
    [-26, -64, 30, 24, 0.06],
    [27, -67, 34, 26, -0.05],
    [2, -73, 58, 34, 0.02],
    [-34, -80, 36, 26, -0.08],
    [38, -83, 32, 24, 0.07],
    [0, -92, 90, 40, 0.0]
  ]
  farFieldMeshes = neighbors.map(([x, z, w, d, rot], i) => {
    // 每块独立材质 + 递增的高度，保证 6 块之间深度值绝不相同
    const m = new THREE.Mesh(
      new THREE.PlaneGeometry(w, d),
      cloneWithOffset(farMat, i, 1)
    )
    // 拼花：每块田一个不同的作物色
    m.material.color.setHex(FAR_TINTS[i % FAR_TINTS.length])
    m.rotation.x = -Math.PI / 2
    m.rotation.z = rot
    m.position.set(x, FAR_FIELD_Y + i * FAR_FIELD_STEP, z)
    // 局部坐标：跟随主田（见上方坐标系分工）
    fieldGroup.add(m)
    return m
  })

  const nearFieldTex = A.fieldMap.clone()
  nearFieldTex.repeat.set(1.5, 2.0)
  nearFieldTex.needsUpdate = true
  const nearMat = new THREE.MeshStandardMaterial({
    map: nearFieldTex, roughness: 0.95, metalness: 0,
    color: paddy ? 0xffffff : 0xdcdfb8
  })
  for (const [i, x] of [-17.0, 17.0].entries()) {
    // 左右两块装饰近景田：同样要独立材质 + 错开高度。
    // 原来它们与 x=±19 的可交互邻田**完全共面**（y 都是 0.02、po 也相同），
    // 是第二处 z-fighting 源头。
    const m = new THREE.Mesh(
      new THREE.PlaneGeometry(16.6, 23.6),
      cloneWithOffset(nearMat, i, 1)
    )
    m.rotation.x = -Math.PI / 2
    m.position.set(x, DECOR_FIELD_Y + i * DECOR_FIELD_STEP, FIELD_CZ)
    // 跟随主田（与远景农田同一坐标系，见上方分工说明）
    fieldGroup.add(m)
    farFieldMeshes.push(m)
  }

  // ---------- 主田面 ----------
  A.soilMap.repeat.set(13, 17)
  A.soilNorm.repeat.set(13, 17)
  A.soilRough.repeat.set(13, 17)
  groundMesh = new THREE.Mesh(
    new THREE.PlaneGeometry(PADDY_W, PADDY_D),
    // 主田三层（田面 0.004 / 垄沟 0.006 / 水面 0.012）高度只差几毫米，
    // 在 18 米外投影不足一个像素。给每层独立的 polygonOffset 分层。
    cloneWithOffset(new THREE.MeshStandardMaterial({
      map: A.soilMap,
      normalMap: A.soilNorm,
      normalScale: new THREE.Vector2(1.0, 1.0),
      roughnessMap: A.soilRough,
      roughness: 1.0,
      metalness: 0,
      color: cfg.soilTint,
      envMapIntensity: 0.32
    }), 0, -1)
  )
  groundMesh.rotation.x = -Math.PI / 2
  groundMesh.position.set(0, 0.004, FIELD_CZ)
  // ⚠️ **田面接收阴影**（2026-10-23 画质升级）。
  //   没有这一步，作物的真实投影没有落脚点 —— 整块田还是平的。
  groundMesh.castShadow = false
  groundMesh.receiveShadow = true
  fieldGroup.add(groundMesh)

  // ---------- 垄沟（行间明暗节奏） ----------
  const rowGap = densityOf(currentDensityKey()).step
  const rowTex = makeRowShadeTexture(THREE, paddy ? 0.82 : 0.68)
  rowTex.repeat.set(PADDY_W / rowGap, 1)
  const rowShade = new THREE.Mesh(
    new THREE.PlaneGeometry(PADDY_W, PADDY_D),
    cloneWithOffset(new THREE.MeshBasicMaterial({
      map: rowTex,
      blending: THREE.MultiplyBlending,
      transparent: true,
      depthWrite: false,
      fog: false
    }), 1, -1)
  )
  rowShade.rotation.x = -Math.PI / 2
  rowShade.position.set(0, 0.006, FIELD_CZ)
  rowShade.renderOrder = 0
  fieldGroup.add(rowShade)

  // ---------- 土壤湿度斑块 ----------
  //
  // ⚠️ **真实农田的土不是一整块均匀的颜色**（2026-10-23 画质升级）。
  //   浇过水的地方深而饱和、没浇到的干白发松，
  //   整片均匀的土在画面上就是"一张贴图铺满"，一眼假。
  //
  //   用 fBm 噪声分档成"湿 / 潮 / 正常 / 干"四个区，
  //   以 MultiplyBlending 叠在田面上（深=湿，浅=干）。
  //
  //   湿度直接由 props.water 驱动：玩家浇水 → 湿区扩大 → 画面真的会变。
  const wetTex = makeSoilWetnessTexture(THREE, clamp((props.water || 0) / 120, 0, 1), 20261023 + props.slot)
  wetTex.repeat.set(2.2, 3.0)
  const wetPatch = new THREE.Mesh(
    new THREE.PlaneGeometry(PADDY_W, PADDY_D),
    cloneWithOffset(new THREE.MeshBasicMaterial({
      map: wetTex,
      blending: THREE.MultiplyBlending,
      transparent: true,
      depthWrite: false,
      fog: false,
      opacity: 0.92
    }), 2, -1)
  )
  wetPatch.rotation.x = -Math.PI / 2
  wetPatch.position.set(0, 0.005, FIELD_CZ)
  wetPatch.renderOrder = 0
  fieldGroup.add(wetPatch)
  soilWetMesh = wetPatch

  // ---------- 水面 ----------
  waterMesh = new THREE.Mesh(
    new THREE.PlaneGeometry(PADDY_W - 0.1, PADDY_D - 0.1),
    new THREE.MeshPhysicalMaterial({
      color: cfg.water,
      transparent: true,
      opacity: 0.10,
      // 水面之前 envMapIntensity 1.1 + clearcoat 0.6，把整片天光反射回来 → 稻田过曝。
      // 泥水本来就浑，反射应该弱而散。
      roughness: 0.18,
      metalness: 0.03,
      clearcoat: 0.2,
      clearcoatRoughness: 0.35,
      envMapIntensity: 0.35,
      depthWrite: false
    })
  )
  waterMesh.rotation.x = -Math.PI / 2
  waterMesh.position.set(0, 0.012, FIELD_CZ)
  waterMesh.renderOrder = 1
  fieldGroup.add(waterMesh)

  // ---------- 田埂 + 田边路 ----------
  //
  // ⚠️ **田埂必须带法线贴图**。只有 albedo 的土埂在斜射阳光下是一块平色，
  // 看起来像贴在地上的塑料板；加上 soilNorm 后才有真实的土粒起伏与明暗过渡。
  // ⚠️ **主田与邻田的田埂必须同高同色**（BUND_H / BUND_W / 同一张贴图），
  // 否则俯视时会出现"这块田的埂高、那块田的埂矮"，一眼假。
  //
  // ⚠️ **田埂 tint 不能接近纯白**（2026-10-23 画质升级修正）。
  //
  //   原来用 0xfff0cc —— 乘上 soilMap（平均 91,94,88 的暗褐）后是"带点黄的土白"，
  //   在 40~90 米的中景里被雾一提，就成了画面上一道道**刺眼的白色亮条**
  //   （实测白像素 1096 个，亮度 218，比旁边暗土高 160 级）。
  //   真实田埂是**晒干的黄土**，不是石灰墙。
  //
  //   现在统一到 0xd8c49a（干黄土），中景不再抢戏。
  const bundMat = new THREE.MeshStandardMaterial({
    map: A.soilMap.clone(),
    normalMap: A.soilNorm.clone(),
    normalScale: new THREE.Vector2(1.1, 1.1),
    roughness: 0.95, metalness: 0, color: 0xd8c49a
  })
  bundMat.map.repeat.set(9, 1)
  bundMat.normalMap.repeat.set(9, 1)
  bundMat.map.needsUpdate = true
  bundMat.normalMap.needsUpdate = true
  const bundLong = boxWithUV(THREE, PADDY_W + 1.4, BUND_H, BUND_W, 12, 1)
  const bundShort = boxWithUV(THREE, BUND_W, BUND_H, PADDY_D + 1.4, 1, 14)
  for (const [x, z, geo] of [
    [0, FIELD_CZ - PADDY_D / 2 - BUND_W / 2, bundLong],
    [0, FIELD_CZ + PADDY_D / 2 + BUND_W / 2, bundLong],
    [-PADDY_W / 2 - BUND_W / 2, FIELD_CZ, bundShort],
    [PADDY_W / 2 + BUND_W / 2, FIELD_CZ, bundShort]
  ]) {
    const b = new THREE.Mesh(geo, bundMat)
    b.position.set(x, BUND_H / 2, z)
    fieldGroup.add(b)
  }
  const pathMat = new THREE.MeshStandardMaterial({
    map: A.soilMap.clone(),
    normalMap: A.soilNorm.clone(),
    normalScale: new THREE.Vector2(0.9, 0.9),
    // 田边路也是干土色，不能比田埂还白
    roughness: 0.96, metalness: 0, color: 0xcdb894
  })
  pathMat.map.repeat.set(2, 30)
  pathMat.normalMap.repeat.set(2, 30)
  pathMat.map.needsUpdate = true
  pathMat.normalMap.needsUpdate = true
  const path = new THREE.Mesh(new THREE.BoxGeometry(1.25, 0.1, PADDY_D + 2.6), pathMat)
  path.position.set(PADDY_W / 2 + 1.5, 0.05, FIELD_CZ)
  fieldGroup.add(path)
}

function buildTrees() {
  const cfg = seasonCfg()
  // 🔴 **树也细分**（2026-10-26 画质分级）：面片分段数按档位走。
  //   低端档 2×3（顶点少一半），精细/极致档 4×5。
  //   树冠是画面里最大的一团绿，4 个顶点时球面化法线只有"一竖条亮 + 两块死平"。
  const segX = Q.treeCount <= 30 ? 2 : 4
  const segY = Q.treeCount <= 30 ? 3 : 5
  const treeGeo = buildCrossQuad(THREE, 11, SPRITE_ASPECT.plant_tree, 1.0, segX, segY)
  // 球面化法线：树冠是球状的，平面法线受光后是一块死平的色块（像贴纸）。
  // 球面化后顶部亮、底部暗、边缘侧受光，才有"一团树冠"的体积感。
  spherifyNormals(THREE, treeGeo, 1.0)
  const treeMat = new THREE.MeshStandardMaterial({
    map: assets.tree,
    alphaTest: 0.4,
    alphaToCoverage: canUseAlphaToCoverage(),
    side: THREE.DoubleSide,
    color: cfg.plantTint,
    fog: true,
    // 树叶：roughness 高、metalness 0，避免出现塑料片似的死白高光
    roughness: 0.92,
    metalness: 0,
    envMapIntensity: 0.5
  })
  // 风动：树冠摆幅比作物大（枝条长、树冠软），但频率更低（惯性大）
  if (Q.windEnabled) {
    addWindShader(treeMat, { amp: 0.13, speed: 0.62, height: 11 })
  }
  // 🔴 **树的 UV 抖动**（2026-10-26 真实感升级）：
  //   60 棵树用同一张 511×768 的贴图 → 每棵树上的枝叶分布完全一样，
  //   只是整体缩放/旋转不同。远看就是"同一棵树复制了 60 份"。
  //   按实例位置哈希抖动 UV → 每棵树的枝叶图案不同。
  addUvJitter(treeMat, { amount: 0.05, scale: 1.7 })
  // 🔴 **树木数量按画质档位**（2026-10-26）：60 棵十字面片是 60 次绘制实例，
  //   每棵还要参与阴影贴图重绘（若开启）。低端档降到 24 棵 —— 林带
  //   在 95~165m 外，24 棵与 60 棵在画面上几乎看不出密度差。
  const TREE_N = Q.treeCount
  treeMesh = new THREE.InstancedMesh(treeGeo, treeMat, TREE_N)
  treeMesh.frustumCulled = false

  const dummy = new THREE.Object3D()
  const rnd = mulberry32(77)
  const tint = new THREE.Color()
  for (let i = 0; i < TREE_N; i++) {
    // 三排错落：近排稀疏矮、中排主林带、远排成墙
    const row = i % 3
    const k = Math.floor(i / 3)
    const span = row === 0 ? 96 : row === 1 ? 150 : 210
    const count = Math.ceil(TREE_N / 3)
    const x = -span / 2 + (span * k) / count + (rnd() - 0.5) * (span / count) * 1.5
    // ⚠️ **树带必须排在远景农田之后、远山之前**。
    //
    // 完整纵深链（局部坐标，按雾浓度递远，fogNear=46 / fogFar=240）：
    //   主田 ±16            雾  0%
    //   邻田 -4~-67         雾  0~17%
    //   远景农田 -64~-92    雾 17~30%
    //   树带 -95~-162       雾 31~66%    ← 这里
    //   远山 -166~-196      雾 67~80%
    //
    // 远景农田最深一块在 z=-92（半深 20 → 实际到 -112），
    // 树带若用 -74 起跳就会**穿过远景农田**，出现"树长在农田里"的穿模。
    // 远山最近层在 z=-166，树带最远必须小于它（-162 < -166 才不穿模，
    // 实际取 -162 是给远山留 4m 余量）。
    const zBase = row === 0 ? -95 : row === 1 ? -116 : -140
    const z = zBase - rnd() * (row === 0 ? 10 : row === 1 ? 16 : 22)
    const s = (row === 0 ? 0.40 : row === 1 ? 0.44 : 0.40) + rnd() * 0.34
    dummy.position.set(x, -0.05, z)
    dummy.scale.set(s, s * (0.85 + rnd() * 0.4), s)
    dummy.rotation.y = rnd() * Math.PI
    dummy.updateMatrix()
    treeMesh.setMatrixAt(i, dummy.matrix)
    const v = 0.78 + rnd() * 0.3
    tint.setRGB(v * (0.94 + rnd() * 0.1), v, v * (0.88 + rnd() * 0.1))
    treeMesh.setColorAt(i, tint)
  }
  treeMesh.instanceMatrix.needsUpdate = true
  if (treeMesh.instanceColor) treeMesh.instanceColor.needsUpdate = true
  // ⚠️ **林带必须挂在 fieldGroup 下，不能挂在 scene 上**。
  //
  // 树的位置是"主田前方 74~150 米"的固定偏移（z=-74/-104/-150）。
  // 如果挂在 scene 上用绝对世界坐标，主田切到靠后的地块时会平移到
  // z=-77（slot 13 → -Math.floor(13/4) * PITCH_Z = -77.4），
  // **正好撞进林带里** —— 表现为"树长在田中间"。
  //
  // 挂在 fieldGroup 下后，树随主田一起平移，相对关系恒定：
  // 「脚下这块田 → 前方几十米是邻田 → 再远是林带」，
  // 无论切到哪块田都成立，也更符合真实场景（你在任何一块田里，
  // 往远处看都是那条林带）。
  // ⚠️ **真实阴影**（2026-10-23 画质升级）。
  //   树木投影到地面上，方向由太阳决定 —— 这是"真实林带"的关键线索。
  //
  //   但树带在 z=-95~-162（距相机 95~165m），
  //   **远超阴影相机 34m 的覆盖范围** —— 给它们开 castShadow
  //   不会产生可见投影，只会白白浪费阴影贴图分辨率。
  //   （树影要落地需要树在 34m 内；远处树的影子在这个距离上只有几像素。）
  //
  // 🔴 **treeShadow 档位参数目前恒为 false**（2026-10-26）：
  //   原因是几何事实 —— 树带在阴影正交视锥（±26m）之外，
  //   开 castShadow 只增加阴影 pass 的绘制调用，产出 0 像素可见收益。
  //   保留这个参数是为了将来「近景树带」方案落地时不必再改参数矩阵。
  treeMesh.castShadow = false
  treeMesh.receiveShadow = false
  fieldGroup.add(treeMesh)

  // ⚠️ **贴图式假树影已移除**（2026-10-23）。
  //
  //   原来树脚下一张径向渐变贴图模拟接触阴影。真实 shadowMap 接管后，
  //   这个正下方的黑圆会和真实投影**叠成双重阴影**，比不画更脏。
  //   而且假影子是"正下方"，斜射阳光下物理上不可能。
}

function buildWeeds() {
  // 野草也细分（草叶细，4 顶点时风动整片平移，看不出"草叶各自摆"）
  const weedGeo = buildCrossQuad(THREE, 0.42, SPRITE_ASPECT.plant_grass_tuft, 1.0, 2, 3)
  spherifyNormals(THREE, weedGeo, 1.0)
  const weedMat = new THREE.MeshStandardMaterial({
    map: assets.weed,
    alphaTest: 0.38,
    alphaToCoverage: canUseAlphaToCoverage(),
    side: THREE.DoubleSide,
    color: 0xf2f2e4,
    fog: true,
    roughness: 0.94,
    metalness: 0,
    envMapIntensity: 0.45
  })
  // 野草风动最明显（草叶最软），幅度小但频率快
  if (Q.windEnabled) {
    addWindShader(weedMat, { amp: 0.05, speed: 2.1, height: 0.42 })
  }
  // 🔴 **野草 UV 抖动**（2026-10-26）：90 丛草共用一张贴图 → 同样的草叶形状重复。
  addUvJitter(weedMat, { amount: 0.07, scale: 5.2 })
  // 🔴 **野草数量按画质档位**（2026-10-26）
  const N = Q.weedCount
  weedMesh = new THREE.InstancedMesh(weedGeo, weedMat, N)
  weedMesh.frustumCulled = false
  const dummy = new THREE.Object3D()
  const tint = new THREE.Color()
  const rnd = mulberry32(2026)
  for (let i = 0; i < N; i++) {
    // 沿田埂四条边散布，1/5 长在田里当杂草
    let x = 0
    let z = 0
    const side = i % 5
    if (side === 4) {
      x = -PADDY_W / 2 + rnd() * PADDY_W
      z = FIELD_CZ - PADDY_D / 2 + rnd() * PADDY_D
    } else if (side === 0) {
      x = -PADDY_W / 2 + rnd() * PADDY_W
      z = FIELD_CZ - PADDY_D / 2 - 0.15 - rnd() * 0.7
    } else if (side === 1) {
      x = -PADDY_W / 2 + rnd() * PADDY_W
      z = FIELD_CZ + PADDY_D / 2 + 0.15 + rnd() * 0.7
    } else if (side === 2) {
      x = -PADDY_W / 2 - 0.15 - rnd() * 0.7
      z = FIELD_CZ - PADDY_D / 2 + rnd() * PADDY_D
    } else {
      x = PADDY_W / 2 + 0.15 + rnd() * 0.7
      z = FIELD_CZ - PADDY_D / 2 + rnd() * PADDY_D
    }
    const s = (side === 4 ? 0.45 : 0.6) + rnd() * 0.8
    dummy.position.set(x, 0.02, z)
    dummy.scale.set(s, s * (0.8 + rnd() * 0.5), s)
    dummy.rotation.y = rnd() * Math.PI
    dummy.updateMatrix()
    weedMesh.setMatrixAt(i, dummy.matrix)
    const v = 0.86 + rnd() * 0.24
    tint.setRGB(v, v * (0.98 + rnd() * 0.06), v * 0.9)
    weedMesh.setColorAt(i, tint)
  }
  weedMesh.instanceMatrix.needsUpdate = true
  if (weedMesh.instanceColor) weedMesh.instanceColor.needsUpdate = true
  // 野草长在主田的四条田埂上，必须随主田一起平移（同 buildTrees 的说明）。
  // 真实阴影：野草矮，投影很小但能让田埂边缘有细节。
  weedMesh.castShadow = true
  weedMesh.receiveShadow = false
  fieldGroup.add(weedMesh)
}

function buildScene() {
  fieldGroup = new THREE.Group()
  neighborGroup = new THREE.Group()
  scene.add(fieldGroup)
  // ⚠️ **neighborGroup 挂在 fieldGroup 下**，与主田同一局部坐标系。
  // 邻田坐标是「相对当前主田的网格偏移」(dx*PITCH_X, -dr*PITCH_Z)，
  // 挂 scene 上会叠加出双倍偏移，邻田会飞到极远处。
  fieldGroup.add(neighborGroup)

  buildSky()
  // ⚠️ **远山也必须随主田平移**。
  // 远山在 z=-215/-340/-540（相对主田的前方远处）。挂在 scene 上时，
  // 切到后排地块（slot>=12，主田已到 z=-77）后，远山会落到主田**后面**，
  // 变成"翻过山头看田"——视角关系彻底反了。
  // 挂在 fieldGroup 下，远山永远是"这片田的远方背景"。
  fieldGroup.add(...makeRidgeLayers(
    THREE,
    seasonCfg().ridgeTint || '#ffffff',
    Q.skyWidth,
    Math.min(Q.anisotropy, 4)
  ))
  buildTerrain()

  cropField = createCropField(
    THREE, fieldGroup, assets, currentCrop(), currentDensityKey(), usedTexFiles, renderer, Q
  )
  cropField.update(props.stage, props.season)

  buildTrees()
  buildWeeds()
  buildWeather()
  syncPlotLayout()
  applyWaterVisual()
}

/**
 * 相机跟随选中地块，同时重建周围 8 块邻田。
 *
 * 邻田刻意不画作物精灵——一块田上千丛，8 块就是上万丛，而它们在画面上只占边缘
 * 一小块面积。用「田面 + 作物色调」表达"这块地种的什么、长到哪个阶段"更划算。
 */
function syncPlotLayout() {
  if (!fieldGroup) return
  const o = plotOrigin(props.slot)
  fieldGroup.position.set(o.x, 0, o.z)
  // ⚠️ **阴影相机的 target 必须跟着主田走**（见 init() 里的详细说明）。
  //   DirectionalLight 的阴影相机是围绕 target 展开的正交视锥，
  //   target 不动 = 阴影永远投在世界原点附近，主田在 76 米外 → 完全没有影子。
  //   手动同步一次即可（灯光不需要进 fieldGroup，
  //   因为 target 只需要世界坐标正确）。
  if (sunLight) {
    sunLight.target.position.set(o.x, 0, o.z)
    sunLight.target.updateMatrixWorld()
    // 光源本身也要跟着平移，否则 position 与 target 的相对关系变了，
    // 太阳方向会被拧歪（方向 = position - target）。
    sunLight.position.set(
      o.x + SUN_DIR.x * 60,
      SUN_DIR.y * 60,
      o.z + SUN_DIR.z * 60
    )
  }
  buildNeighbors()
}

/**
 * 地图模式下把主田"压平"：隐藏逐株精灵与水面，只留田面。
 * 否则一屏上千丛精灵堆成一块深色，与周围格子的贴图风格割裂。
 */
function applyViewMode() {
  const isMap = props.view === 'map'
  if (waterMesh) waterMesh.visible = !isMap && currentCrop() === 'rice'
  if (cropField) {
    cropField.meshes.forEach((m) => { m.visible = !isMap })
    // 接触阴影在地图里会变成一片黑斑
    cropField.setShadowVisible?.(!isMap)
  }
  applyCamera()
  buildNeighbors()
}

watch(() => props.view, () => {
  if (!THREE || !scene) return
  applyViewMode()
  if (props.view !== 'map') applyWaterVisual()
})

/**
 * 邻田田面色：按作物与阶段给一个判断得出来的颜色。
 *
 * 已种地块用**高饱和**作物色，空地是**低饱和土褐**——两者在俯瞰视角必须一眼分得开。
 * 之前已种/未种都是绿系，远看糊成一片。
 *
 * ⚠️ 2026-10-24：visual 的语义变了（园艺作物 = 作物 id），
 * 22 种作物各有专属的三档色，见 config.js 的 CROP_TINT。
 *
 * 取色顺序：
 * 1. visual 本身命中（22 种作物专属 id，或存量的旧族名）；
 * 2. 旧 id 归一（rice_mid → rice）后再取；
 * 3. 兜底到稻的色。
 *
 * ⚠️ 第 1 步要优先：存量旧 `flower` 在地图上是**紫色**（CROP_COLOR.flower），
 * 若这里先归一成 chrysanthemum 就会变成金色，地图与 3D 场景对不上。
 */
function neighborTint(visual, stage) {
  const st = String(stage || '')
  if (!visual) return 0x8a7a5e
  const ripe = /成熟|黄熟|灌浆|乳熟|蜡熟/.test(st)
  const young = /苗|出苗|播种|返青|分蘖/.test(st)
  const tints = CROP_TINT[visual] || CROP_TINT[cropFamily(visual)] || CROP_TINT.rice
  // tints = [成熟色, 生长色, 苗期色]（见 config.js 的排列说明）
  if (ripe) return tints[0]
  if (young) return tints[2]
  return tints[1]
}

/**
 * 渲染视野内的地块网格（5×5）。
 *
 * 已建的地块画成真实田块；未建的地块画成「待开垦」的浅色地，点一下就能在那里建田——
 * 格子编号直接对应 slot，所以"选点"不需要额外坐标字段。
 */
function buildNeighbors() {
  for (const m of neighborGroup.children.slice()) {
    neighborGroup.remove(m)
    m.geometry?.dispose?.()
    m.material?.dispose?.()
  }
  const bySlot = new Map()
  for (const p of props.plots) bySlot.set(p.slot, p)

  const c = props.slot
  const col = c % PLOT_COLS
  const row = Math.floor(c / PLOT_COLS)
  const RANGE = props.view === 'map' ? 4 : 2

  for (let dr = -RANGE; dr <= RANGE; dr++) {
    for (let dx = -RANGE; dx <= RANGE; dx++) {
      if (!dr && !dx) continue
      const nc = col + dx
      const nr = row + dr
      if (nc < 0 || nc >= PLOT_COLS || nr < 0) continue
      const slot = nr * PLOT_COLS + nc
      if (slot === c || slot >= MAX_SLOT) continue
      const p = bySlot.get(slot)
      // ⚠️ **邻田用局部坐标**（相对当前主田的网格偏移），
      // 不是 plotOrigin(slot) 的绝对世界坐标。
      // 因为 neighborGroup 现在挂在 fieldGroup 下（随主田平移），
      // 用绝对坐标会叠加出双倍偏移 —— 邻田会飞到很远的地方去。
      // 局部偏移 = (列差 × PITCH_X, 行差 × PITCH_Z)
      const o = { x: dx * PITCH_X, z: -dr * PITCH_Z }

      if (p && p.chosen) {
        // ---- 已建田块 ----
        // 饱和作物色 + 高饱和度，俯瞰时一眼能和褐色荒地分开
        const tint = neighborTint(p.visual, p.stage)
        const nTex = assets.fieldMap.clone()
        nTex.repeat.set(0.9, 1.2)
        nTex.needsUpdate = true
        // 每块田用独立材质 + 唯一高度：
        // 已种田块之间、已种与空地之间，原先共用同一 polygonOffset 且高度只差 0.005m，
        // 投影到 60 米外不到一个像素 → 同样是共面 z-fighting。
        //
        // ⚠️ 用 **MeshStandardMaterial**（与主田一致），不要用 Basic。
        // Basic 不受光照 → 邻田永远是那个固定亮度，而主田会被太阳照亮，
        // 两套材质放在一起就是"贴图块 + 塑料条"的割裂感。
        // 换成 Standard 后受同一套 hemi/sun/fill 光照，邻田与主田的明暗关系才一致。
        const plane = new THREE.Mesh(
          new THREE.PlaneGeometry(PADDY_W, PADDY_D),
          cloneWithOffset(new THREE.MeshStandardMaterial({
            map: nTex,
            roughness: 0.95,
            metalness: 0,
            color: tint,
            fog: true
          }), slot, -1)
        )
        plane.rotation.x = -Math.PI / 2
        // 每块田按 slot 错开高度，绝不与任何邻块共面
        plane.position.set(o.x, NEIGHBOR_Y + (slot % 5) * NEIGHBOR_STEP, o.z + FIELD_CZ)
        // 已种田块也要吃阴影，否则它与荒地的受光关系不一致，
        // 邻田看起来像"贴上去的色块"而不是躺在地上的田。
        plane.receiveShadow = true
        plane.userData.slot = slot
        neighborGroup.add(plane)

        // 田埂：与主田同规格（BUND_H / BUND_W），俯视时整片农场的田埂高度统一。
        // ⚠️ **田埂要有土壤贴图**（复用主田的 soilMap），不能是纯色块 ——
        // 纯色田埂没有任何表面细节，在中景（30~60 米）看就是四条塑料条。
        // 加了贴图 + Standard 光照后，田埂才有真实的土黄色颗粒感。
        const bundTex = assets.soilMap.clone()
        bundTex.repeat.set(8, 1)
        bundTex.needsUpdate = true
        const bundNorm = assets.soilNorm.clone()
        bundNorm.repeat.set(8, 1)
        bundNorm.needsUpdate = true
        const bundMat = new THREE.MeshStandardMaterial({
          map: bundTex,
          normalMap: bundNorm,
          normalScale: new THREE.Vector2(1.1, 1.1),
          // 与主田田埂同色（见 buildTerrain 的说明：不能接近纯白）
          roughness: 0.95, metalness: 0, color: 0xd8c49a, fog: true
        })
        const edges = [
          [PADDY_W + 1.2, BUND_H, BUND_W, o.x, BUND_H / 2, o.z + FIELD_CZ - PADDY_D / 2 - BUND_W / 2],
          [PADDY_W + 1.2, BUND_H, BUND_W, o.x, BUND_H / 2, o.z + FIELD_CZ + PADDY_D / 2 + BUND_W / 2],
          [BUND_W, BUND_H, PADDY_D + 1.2, o.x - PADDY_W / 2 - BUND_W / 2, BUND_H / 2, o.z + FIELD_CZ],
          [BUND_W, BUND_H, PADDY_D + 1.2, o.x + PADDY_W / 2 + BUND_W / 2, BUND_H / 2, o.z + FIELD_CZ]
        ]
        for (const [w, h, d, x, y, z] of edges) {
          const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), bundMat)
          b.position.set(x, y, z)
          neighborGroup.add(b)
        }
      } else {
        // ---- 待开垦的空地 ----
        // 荒地：裸土褐 + 低饱和，与绿色作物块形成强对比。
        // 复用干燥土壤贴图（水稻/旱作都有），避免额外素材依赖。
        const vTex = assets.soilMap.clone()
        vTex.repeat.set(1.6, 2.1)
        vTex.needsUpdate = true
        // 与已种田块同用 Standard 材质（见上），保持光照一致。
        //
        // ⚠️ **荒地 tint 不能太暗**（2026-10-23 实测修正）。
        //
        //   原来用 0x8a7355（深土褐），而 soilMap 本身平均色已经是
        //   RGB(91,94,88) 的暗灰褐 —— 两者**相乘**得到约 (49,54,46)，
        //   在画面上就是中景一条"巧克力色暗带"（实测 ny=0.25 处
        //   像素 RGB=(33,33,28)，亮度只有 31，比天空暗 170 级）。
        //
        //   更要命的是荒地**不开 receiveShadow**，
        //   于是它既被贴图压暗、又完全不吃阳光/阴影，
        //   整片像一块贴在地面上的深色板。
        //
        //   现在：tint 提到 0xc4b295（亮土色，只做轻微偏暖），
        //   并开启 receiveShadow —— 实测该区亮度从 31 提到 53，
        //   暗带不再是"脏色块"，而是正常的待开垦裸土。
        //
        // ⚠️ **荒地也要拼花**（2026-10-23 画质升级）。
        //   10 块荒地共用同一个 tint → 中景出现一条**单色土带**，
        //   像"塑料布铺在草地上"。真实世界里相邻的休耕地
        //   因为翻耕时间、湿度、残留作物不同，颜色是**深浅不一的**。
        //   用 slot 做索引取不同 tint 打破单调。
        const plane = new THREE.Mesh(
          new THREE.PlaneGeometry(PADDY_W, PADDY_D),
          cloneWithOffset(new THREE.MeshStandardMaterial({
            map: vTex,
            roughness: 0.98,
            metalness: 0,
            color: VACANT_TINTS[slot % VACANT_TINTS.length],
            fog: true
          }), slot + 1, -1)
        )
        plane.rotation.x = -Math.PI / 2
        // 空地比已种田块低，且按 slot 错开，彻底避免与已种田块共面
        plane.position.set(o.x, VACANT_Y + (slot % 5) * VACANT_STEP, o.z + FIELD_CZ)
        plane.receiveShadow = true
        plane.userData.slot = slot
        plane.userData.vacant = true
        neighborGroup.add(plane)

        // ⚠️ **荒地必须带湿度斑块**（2026-10-23 画质升级）。
        //   主田的土基本被作物盖住，湿度斑块加在主田上看不见；
        //   而荒地是**裸露的土面** —— 那里才是湿度差异最该被看见的地方
        //   （翻耕过的湿土 vs 晒干发白的土）。
        //
        //   贴图按 slot 取不同种子 → 每块地的斑块形态都不一样，
        //   避免整片荒地看起来是复制粘贴的。
        const vWetTex = makeSoilWetnessTexture(THREE, 0.28, 41000 + slot)
        vWetTex.repeat.set(1.6, 2.1)
        const vWet = new THREE.Mesh(
          new THREE.PlaneGeometry(PADDY_W, PADDY_D),
          cloneWithOffset(new THREE.MeshBasicMaterial({
            map: vWetTex,
            blending: THREE.MultiplyBlending,
            transparent: true,
            depthWrite: false,
            fog: true,
            opacity: 0.9
          }), slot + 2, -1)
        )
        vWet.rotation.x = -Math.PI / 2
        vWet.position.set(o.x, plane.position.y + 0.002, o.z + FIELD_CZ)
        vWet.renderOrder = 0
        neighborGroup.add(vWet)

        // 荒地边框：低矮的土埂 + 冷灰色调，一眼看出"这块还没开垦"。
        // 同样给上土壤贴图（复用），避免纯色塑料条。
        const edgeTex = assets.soilMap.clone()
        edgeTex.repeat.set(6, 1)
        edgeTex.needsUpdate = true
        const edgeMat = new THREE.MeshStandardMaterial({
          map: edgeTex, roughness: 0.98, metalness: 0, color: 0x8d8067, fog: true
        })
        const edges = [
          [PADDY_W, 0.16, 0.3, o.x, 0.08, o.z + FIELD_CZ - PADDY_D / 2],
          [PADDY_W, 0.16, 0.3, o.x, 0.08, o.z + FIELD_CZ + PADDY_D / 2],
          [0.3, 0.16, PADDY_D, o.x - PADDY_W / 2, 0.08, o.z + FIELD_CZ],
          [0.3, 0.16, PADDY_D, o.x + PADDY_W / 2, 0.08, o.z + FIELD_CZ]
        ]
        for (const [w, h, d, x, y, z] of edges) {
          const b = new THREE.Mesh(new THREE.BoxGeometry(w, h, d), edgeMat)
          b.position.set(x, y, z)
          neighborGroup.add(b)
        }

        // 荒草暗示：稀疏撒几丛野草，直接传达"荒着没人管"
        // 🔴 **每块荒地的荒草数按画质档位**（2026-10-26）。
        //   注意这是**每块 Mesh**（不是 InstancedMesh）→ 7 块邻田 × 7 丛 = 49 个
        //   独立 Mesh = 49 次绘制调用。低端档直接置 0（省 49 个 draw call），
        //   标准档 4 丛。这是「绘制调用数」比「像素数」更值钱的一个例子。
        if (assets.weed && Q.neighborWeedPer > 0) {
          const weedGeo = buildCrossQuad(THREE, 0.7, SPRITE_ASPECT.plant_grass_tuft, 1.0, 2, 3)
          spherifyNormals(THREE, weedGeo, 1.0)
          // 与主田野草一样用受光材质（Basic 不受光 → 荒草像贴纸）
          const weedMat = new THREE.MeshStandardMaterial({
            map: assets.weed, alphaTest: 0.4, alphaToCoverage: canUseAlphaToCoverage(),
            side: THREE.DoubleSide,
            color: 0x8f8a62, fog: true,
            roughness: 0.94, metalness: 0, envMapIntensity: 0.4
          })
          if (Q.windEnabled) {
            addWindShader(weedMat, { amp: 0.07, speed: 1.8, height: 0.7 })
          }
          const rnd = mulberry32(slot * 977 + 13)
          const dummy = new THREE.Object3D()
          const N = Q.neighborWeedPer
          for (let i = 0; i < N; i++) {
            dummy.position.set(
              o.x + (rnd() - 0.5) * (PADDY_W - 1.6),
              0.02,
              o.z + FIELD_CZ + (rnd() - 0.5) * (PADDY_D - 1.6)
            )
            const s = 0.55 + rnd() * 0.5
            dummy.scale.set(s, s, s)
            dummy.rotation.y = rnd() * Math.PI
            dummy.updateMatrix()
            const w = new THREE.Mesh(weedGeo, weedMat)
            w.applyMatrix4(dummy.matrix)
            w.userData.slot = slot
            w.userData.vacant = true
            neighborGroup.add(w)
          }
        }
      }
    }
  }
}

// ---------------- 天气粒子 ----------------

function buildWeather() {
  if (!weatherObj) {
    /* noop */
  } else {
    scene.remove(weatherObj)
    weatherObj.geometry?.dispose()
    weatherObj.material?.dispose()
    weatherObj = null
  }
  const w = props.weather || {}
  const precip = w.precip || 0
  const tmin = w.tmin ?? 15
  let kind = null
  if (precip > 4) kind = 'rain'
  else if (tmin < 1) kind = 'snow'
  if (!kind) return

  const N = kind === 'rain' ? 460 : 280
  const pos = new Float32Array(N * 3)
  for (let i = 0; i < N; i++) {
    pos[i * 3] = (Math.random() - 0.5) * 48
    pos[i * 3 + 1] = Math.random() * 20
    pos[i * 3 + 2] = (Math.random() - 0.5) * 42 + 2
  }
  const geo = new THREE.BufferGeometry()
  if (kind === 'rain') {
    const verts = new Float32Array(N * 6)
    for (let i = 0; i < N; i++) {
      verts[i * 6] = pos[i * 3]
      verts[i * 6 + 1] = pos[i * 3 + 1]
      verts[i * 6 + 2] = pos[i * 3 + 2]
      verts[i * 6 + 3] = pos[i * 3] - 0.06
      verts[i * 6 + 4] = pos[i * 3 + 1] - 0.6
      verts[i * 6 + 5] = pos[i * 3 + 2]
    }
    geo.setAttribute('position', new THREE.Float32BufferAttribute(verts, 3))
    weatherObj = new THREE.LineSegments(geo, new THREE.LineBasicMaterial({
      color: 0xd6e8f7, transparent: true, opacity: 0.45, fog: false
    }))
    weatherObj.userData = { stride: 6, kind: 'rain' }
  } else {
    geo.setAttribute('position', new THREE.Float32BufferAttribute(pos, 3))
    weatherObj = new THREE.Points(geo, new THREE.PointsMaterial({
      color: 0xffffff, size: 0.16, transparent: true, opacity: 0.8,
      depthWrite: false, fog: false
    }))
    weatherObj.userData = { stride: 3, kind: 'snow' }
  }
  weatherObj.frustumCulled = false
  scene.add(weatherObj)
}

// ---------------- 状态 → 视觉 ----------------

/** 水层 → 水面高度/透明度；旱作无明水 */
function applyWaterVisual() {
  if (!waterMesh || !groundMesh) return
  const cfg = seasonCfg()
  const w = props.water || 0
  // ⚠️ **湿度斑块对水稻/旱作都要更新**（2026-10-23）。
  //   所以放在旱作提前 return **之前** ——
  //   旱作的土壤墒情同样该看得见（干裂的土 vs 浇透的土）。
  refreshSoilWetness(w)
  if (currentCrop() !== 'rice') {
    waterMesh.visible = false
    groundMesh.material.color.set(cfg.soilTint)
    return
  }
  waterMesh.visible = true
  let opacity = 0.03
  let y = 0.008
  if (w > 80) {
    opacity = 0.20
    y = 0.030
  } else if (w > 10) {
    opacity = 0.12
    y = 0.014
  } else if (w > 2) {
    opacity = 0.07
    y = 0.010
  }
  waterMesh.material.opacity = opacity
  waterMesh.position.y = y
  waterMesh.material.color.set(cfg.water)
  // 落干后田面泛白，有水时保持湿润深色
  const dry = new THREE.Color(0xc9bfa6)
  const wet = new THREE.Color(cfg.soilTint)
  groundMesh.material.color.copy(wet).lerp(dry, w < 3 ? 0.55 : 0.0)
  // ⚠️ **湿度斑块要跟着含水量重算**（2026-10-23 画质升级）。
  //   否则斑块只在建场时烘一次，玩家浇水后画面不变 → 功能不可见。
  refreshSoilWetness(w)
}

/**
 * 按当前含水量重算土壤湿度斑块贴图。
 *
 * ⚠️ **props.water 的单位是毫米（waterMm），不是百分比**：
 *   前端传的是 `state.soil.waterMm`（当前约 108），
 *   所以必须按**田间持水量**归一化，不能直接 /100
 *   （否则 108mm 会被钳成"最湿"，玩家浇水前后画面没区别）。
 *
 * @param {number} w 含水量（mm）
 */
function refreshSoilWetness(w) {
  if (!soilWetMesh || !THREE) return
  const old = soilWetMesh.material.map
  // 田间持水量约 120mm：0mm 全干，120mm 以上饱和
  const tex = makeSoilWetnessTexture(THREE, clamp((w || 0) / 120, 0, 1), 20261023 + props.slot)
  tex.repeat.set(2.2, 3.0)
  soilWetMesh.material.map = tex
  soilWetMesh.material.needsUpdate = true
  old?.dispose?.()
}

function applyCamera() {
  if (!camera) return
  const o = plotOrigin(props.slot)
  // 地图模式：抬到高空俯视，一屏能装下 3–4 排格子
  const isMap = props.view === 'map'
  const base = isMap ? 62 : cam.base
  const pitch = isMap ? 1.05 : cam.pitch
  const dist = base * (isMap ? 1 : cam.zoom)
  const ry = Math.cos(pitch) * dist
  let y = look.y + Math.sin(pitch) * dist
  // 地图模式对齐整行中心：网格从 x=0 向右排，若仍看向当前田，视野左半会是空的
  const cx = isMap ? ((PLOT_COLS - 1) / 2) * PITCH_X : o.x
  // 低仰角保护：pitch 接近 0 时相机会沉到与目标点同高甚至钻进土里。
  // 这里给相机加一个"至少高出目标点 MIN_EYE_H"的下限，
  // 于是拖到最平也只是一个贴地平视，而不是穿地。
  const MIN_EYE_H = 0.35
  const targetY = look.y - 0.15
  if (y < targetY + MIN_EYE_H) y = targetY + MIN_EYE_H
  camera.position.set(cx + Math.sin(cam.yaw) * ry, y, o.z + Math.cos(cam.yaw) * ry)
  camera.lookAt(cx, targetY, o.z + FIELD_CZ + 1.2)
  // 天空球跟随相机：它的半径 640 必须始终落在 far 之内，
  // 否则 far 一收紧（600）天空就会被近裁剪掉，屏幕上出现大块黑空洞。
  // 让球心跟着相机走，就等价于把天空放到"无穷远"，与 far 无关。
  if (skyMesh) skyMesh.position.copy(camera.position)
}

function onResize() {
  if (!renderer || !camera || !wrap.value) return
  const w = wrap.value.clientWidth
  const h = wrap.value.clientHeight
  if (!w || !h) return
  renderer.setSize(w, h, false)
  const aspect = w / h
  camera.aspect = aspect
  // ⚠️ **竖屏（窄视口）必须加大垂直 fov**（2026-10-23 双视口验收实测）。
  //
  //   three 的 fov 是**垂直**视场角。横屏 1600×? 时 aspect≈1.5，
  //   竖屏 375×455 时 aspect=0.79 —— 垂直视野没变，
  //   但画面被压成窄条，天空/远山那条带只剩几十像素高，
  //   看起来像"根本没有天空"。
  //
  //   而且竖屏时相机的**俯角**也该小一些（看得更远、更平），
  //   否则视线死盯着脚下的田。
  //
  //   规则：aspect < 1（竖屏）→ fov 提到 58°，pitch 压到 0.16 rad。
  camera.fov = aspect < 1 ? 58 : 48
  camera.updateProjectionMatrix()
  const base = CAMERA.base * (aspect < 1.45 ? Math.pow(1.45 / aspect, 0.55) : 1)
  cam.base = base
  // 竖屏时把俯角压小 → 视线更平 → 天空与远山露出来
  cam.pitch = aspect < 1 ? 0.16 : CAMERA.pitch
  applyCamera()
}

// ---------------- 交互 ----------------

let dragging = false
let lastX = 0
let lastY = 0
let downX = 0
let downY = 0
let raycaster = null

function onPointerDown(e) {
  dragging = true
  lastX = e.clientX
  lastY = e.clientY
  downX = e.clientX
  downY = e.clientY
  canvas.value?.setPointerCapture?.(e.pointerId)
}

/** 点击（不是拖拽）时拾取邻田，直接切过去 */
function pickNeighbor(e) {
  if (!THREE || !camera || !neighborGroup) return
  const dx = e.clientX - downX
  const dy = e.clientY - downY
  if (dx * dx + dy * dy > 36) return
  const rect = canvas.value?.getBoundingClientRect()
  if (!rect || !rect.width) return
  if (!raycaster) raycaster = new THREE.Raycaster()
  const ndc = new THREE.Vector2(
    ((e.clientX - rect.left) / rect.width) * 2 - 1,
    -((e.clientY - rect.top) / rect.height) * 2 + 1
  )
  raycaster.setFromCamera(ndc, camera)
  const hits = raycaster.intersectObjects(neighborGroup.children, false)
  for (const h of hits) {
    const slot = h.object.userData?.slot
    if (slot == null) continue
    if (h.object.userData.vacant) {
      emit('create-plot', slot)
    } else {
      emit('select-plot', slot)
    }
    return
  }
}
function onPointerMove(e) {
  if (!dragging) return
  const dx = e.clientX - lastX
  const dy = e.clientY - lastY
  lastX = e.clientX
  lastY = e.clientY
  // yaw：允许更大范围环绕（原来 ±0.55 rad 只能左右偏一点）
  cam.yaw = Math.max(-1.15, Math.min(1.15, cam.yaw - dx * 0.0025))
  // pitch：下限放到接近水平（0.015 rad ≈ 0.9°），配合 applyCamera 的
  // MIN_EYE_H 保护，可以真正贴地平视作物，而不是被钳在 7.5° 死角里。
  // 上限 0.85 rad ≈ 48.7°，允许略微俯视。
  cam.pitch = Math.max(0.015, Math.min(0.85, cam.pitch + dy * 0.0018))
  applyCamera()
}
function onPointerUp(e) {
  if (dragging) pickNeighbor(e)
  dragging = false
  try {
    canvas.value?.releasePointerCapture?.(e.pointerId)
  } catch (_) { /* noop */ }
}
function onWheel(e) {
  e.preventDefault()
  cam.zoom = Math.max(0.72, Math.min(2.2, cam.zoom + e.deltaY * 0.0012))
  applyCamera()
}

// ---------------- 主循环 ----------------

let lastFrame = 0

/**
 * 🔴 **帧率上限**（2026-10-26 画质分级）
 *
 *   requestAnimationFrame 在 120Hz/144Hz 屏上会以 120/144 次/秒回调，
 *   但**画面内容没变**（只是风动和水波在动）→ GPU 白白渲染 2~3 倍的帧。
 *   笔记本插电跑独显时这是实打实的发热与耗电。
 *
 *   实现：按 maxFps 计算最小帧间隔，到点才真正 render；
 *   跳过的帧仍然走 tickAllWind（风动时间用真实时间，不受影响）。
 *
 *   ⚠️ 用**真实时间**而不是帧计数来算间隔 —— 帧计数在掉帧时会算错。
 */
let lastRenderAt = 0

/**
 * 🔴 **FPS 采样**（2026-10-26）：用于画质面板显示实测帧率 + 自动降档判定。
 *
 *   用**滑动窗口**而不是瞬时值：瞬时 FPS 在一次 GC 或着色器编译后会瞬间掉到 5，
 *   直接拿去判定降档会误触发。
 */
const fpsSamples = []
let fpsValue = 0
let fpsWindowStart = 0
let fpsFrameCount = 0

/** 自动降档判定状态（见 #131） */
let autoDowngradeArmed = false
let autoDowngradeDone = false

function sampleFps(now) {
  if (!fpsWindowStart) {
    fpsWindowStart = now
    fpsFrameCount = 0
    return
  }
  fpsFrameCount++
  const elapsed = now - fpsWindowStart
  // 1 秒一个窗口
  if (elapsed >= 1000) {
    fpsValue = Math.round((fpsFrameCount * 1000) / elapsed)
    fpsSamples.push(fpsValue)
    if (fpsSamples.length > 5) fpsSamples.shift()
    fpsWindowStart = now
    fpsFrameCount = 0
    checkAutoDowngrade()
    // 🔴 **上报实测帧率**（2026-10-26）：画质面板要显示「实测 FPS」，
    //   让用户能判断当前档位是否合适 —— 而不是凭感觉猜。
    emit('fps', { fps: fpsValue, quality: Q.id })
  }
}

/**
 * 🔴 **自动降档**（2026-10-26 · #131）
 *
 *   规则：
 *   · 只降**一档**，且**只降一次**（避免在临界点反复抖动）
 *   · 需要连续 2 个窗口都低于阈值才触发（一次卡顿不算）
 *   · 阈值 24 FPS（低于此值用户已经能明显感到卡）
 *   · **用户手动选过画质时不自动降档** —— 用户明确表达过偏好，
 *     系统不该擅自改回去。这条比降档本身更重要。
 */
function checkAutoDowngrade() {
  if (!autoDowngradeArmed || autoDowngradeDone) return
  if (fpsSamples.length < 2) return
  const q = getQuality()
  // 🔴 **用户手动选过就不自动降**（见规则最后一条）
  if (!q.auto) return
  const last = fpsSamples[fpsSamples.length - 1]
  const prev = fpsSamples[fpsSamples.length - 2]
  if (last >= 24 || prev >= 24) {
    // 恢复正常 → 重新武装（允许之后再降）
    autoDowngradeArmed = true
    return
  }
  autoDowngradeDone = true
  const order = ['low', 'standard', 'high', 'ultra']
  const idx = order.indexOf(q.id)
  if (idx <= 0) return
  const next = order[idx - 1]
  console.info(`[farm] 实测帧率 ${last} FPS 偏低，画质自动降至「${next}」`)
  // 🔴 **必须真的调用 setQuality**（只 emit 不会生效）：
  //   setQuality 会写 localStorage + 通知所有订阅者 → 本组件的
  //   onQualityChange 回调会走「热更新 / 重建」路径。
  //   ⚠️ 写成固定档位而不是 'auto'：降完就固定下来，
  //   否则下次探测又会回到高档 → 反复降档（抖动）。
  setQuality(next)
  emit('auto-downgrade', { from: q.id, to: next, fps: last })
}

function animate(now) {
  raf = requestAnimationFrame(animate)
  if (!renderer || !scene || !camera) return

  // ⚠️ **dt 与 lastFrame 必须每帧都更新**（哪怕这一帧跳过渲染）。
  //   否则跳过的那几帧时间会累积到下一帧的 dt 里 → 天气粒子突然跳一大段，
  //   看起来像"卡了一下然后瞬移"。
  const dt = lastFrame ? Math.min(0.05, (now - lastFrame) / 1000) : 0.016
  lastFrame = now
  const t = now * 0.001

  // 帧率上限：到点才渲染，中间帧跳过 GPU 工作
  //
  // ⚠️ **跳过的帧里仍要推进所有 CPU 侧逻辑**（风动 + 天气粒子 + 水面）。
  //   只推进风动会让雨滴在低帧率档位下"卡住不动"（粒子位置只在渲染帧更新），
  //   看起来像雨停了 —— 这是「帧率上限」最容易漏掉的配套改动。
  const maxFps = Q.maxFps
  if (maxFps > 0 && lastRenderAt > 0) {
    const minGap = 1000 / maxFps
    const since = now - lastRenderAt
    if (since < minGap) {
      stepScene(t, dt)
      return
    }
    // 🔴🔴 **时间轴对齐**（2026-10-26 修复，实测依据）：
    //   原来的写法是「渲染后 lastRenderAt = now」—— 只有当 rAF 频率是
    //   maxFps 的整数倍时，名义帧率才等于实际帧率。
    //   实测（无头 Chrome，rAF = 43.2Hz，maxFps = 30）：
    //       43.2Hz 的帧间隔 23ms  <  minGap 33.3ms
    //       → 每 2 个 rAF 帧才渲染 1 次 → **实际只有 21.5fps（比名义低 28%）**
    //   修法：渲染后把 lastRenderAt **回退到最近的时间轴网格点**，
    //         让帧间隔在长时间尺度上均匀分布 → 平均帧率 ≈ maxFps。
    //   （`since % minGap` 保证回退量 < minGap，且 lastRenderAt 永远 ≤ now。）
    lastRenderAt = now - (since % minGap)
  } else {
    lastRenderAt = now
  }
  sampleFps(now)

  stepScene(t, dt)
  renderer.render(scene, camera)
}

/**
 * 推进场景的 CPU 侧动态（与是否渲染解耦）。
 *
 * 🔴 **抽成独立函数的原因**（2026-10-26 帧率上限）：
 *   帧率上限会跳过一部分帧的渲染，但**动态元素必须照常推进** ——
 *   否则雨滴会卡住、风动会变慢、水面会僵住。
 *   把「推进」和「渲染」分成两步，跳过渲染时只调推进。
 */
function stepScene(t, dt) {
  if (waterMesh && waterMesh.visible) {
    waterMesh.material.opacity = Math.max(
      0.02,
      Math.min(0.24, waterMesh.material.opacity + Math.sin(t * 0.7) * 0.0014)
    )
  }

  // 风动：把时间推进给所有带风动材质的物体（作物 / 树 / 野草 / 邻田荒草）。
  // ⚠️ 位移在顶点着色器里做，这里只更新一个 uniform —— CPU 开销接近 0。
  // ⚠️ **必须走全局登记表**（2026-10-23 修正）：
  //   原来只 tick 主田作物 / 树 / 主田野草三个对象，
  //   邻田里 70 丛荒草的 uniform 永远停在 0 → 荒草一动不动，很假。
  //   现在 addWindShader 会自动登记，tickAllWind 一行推动全部。
  tickAllWind(t)

  if (weatherObj) {
    const attr = weatherObj.geometry.getAttribute('position')
    const arr = attr.array
    const isRain = weatherObj.userData.kind === 'rain'
    const stride = weatherObj.userData.stride
    const speed = isRain ? 13 : 1.6
    const count = attr.count / (isRain ? 2 : 1)
    for (let i = 0; i < count; i++) {
      const o = i * stride
      let y = arr[o + 1] - speed * dt * (0.8 + (i % 5) * 0.1)
      let x = arr[o]
      if (isRain) x -= 1.2 * dt
      if (y < -0.2) {
        y = 18 + Math.random() * 4
        x = (Math.random() - 0.5) * 48
      }
      arr[o] = x
      arr[o + 1] = y
      if (isRain) {
        arr[o + 3] = x - 0.06
        arr[o + 4] = y - 0.6
      }
    }
    attr.needsUpdate = true
  }
}

// ---------------- 初始化 ----------------

async function init() {
  // 🔴 **必须先恢复画质设置**（2026-10-26 画质分级）：
  //   quality.js 的内存态 currentSetting 默认是 'auto'，
  //   用户在面板里选过的档位存在 localStorage 里。
  //   不调用 initQuality() → 每次刷新都回到自动探测，
  //   **用户的选择形同虚设**。
  initQuality()
  Q = getQualityParams()
  try {
    THREE = await import('three')
  } catch (e) {
    emit('fail', { reason: 'three-missing' })
    return
  }
  if (disposed || !canvas.value || !wrap.value) return

  const w = wrap.value.clientWidth || 960
  const h = wrap.value.clientHeight || 600

  // 🔴 **画质参数在此生效**（2026-10-26 画质分级）。
  //   改造前这里是「一档写死」：antialias:true / powerPreference:'high-performance'
  //   / setPixelRatio 上限 1.75 / 阴影 2048 —— 低端机被迫吃满 MSAA，
  //   高端机却被 1.75 的 pixelRatio 卡住（4K 屏上细节糊）。
  try {
    renderer = new THREE.WebGLRenderer({
      canvas: canvas.value,
      // antialias 是**上下文创建参数**，运行时改不了 →
      // 画质切换时若这一项变了，必须重建 renderer（见 requiresSceneRebuild）。
      antialias: Q.antialias,
      alpha: false,
      // 'low-power' 让浏览器挑集显/核显，避免强制独显把核显机器拖垮。
      powerPreference: Q.powerPreference
    })
  } catch (e) {
    emit('fail', { reason: 'webgl' })
    return
  }
  if (!renderer) {
    emit('fail', { reason: 'webgl' })
    return
  }

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, Q.pixelRatioMax))
  renderer.setSize(w, h, false)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping
  // ⚠️ **开启真实阴影**（2026-10-23 画质升级，实测已验证可用）。
  //
  //   历史注释说"沙箱内 InstancedMesh 阴影会抛 Invalid arguments"，
  //   所以一直用"贴图式假阴影"（每个植株脚下一个径向渐变贴图）。
  //   2026-10-23 实测（src/__lab/shadow.html）：
  //   **shadowMap + InstancedMesh.castShadow 完全正常**，
  //   200 个实例的投影清晰可见，无任何报错。
  //
  //   假阴影的问题：影子是正下方的一个圆，**没有方向**，
  //   斜射阳光下物理上不可能 —— 这是画面"贴纸感"的最大来源之一。
  //   换成真实阴影后，太阳光从哪个方向来，影子就往哪个方向倒。
  //
  // 🔴 **阴影按画质档位**（2026-10-26）：
  //   低端档整个关掉 —— 2048² 阴影贴图每帧要重画一遍所有投射体，
  //   在核显上这一项就能吃掉 30%+ 帧预算，而阴影在窄屏小画面上
  //   几乎看不清，性价比极低。
  renderer.shadowMap.enabled = Q.shadowEnabled
  renderer.shadowMap.type = Q.shadowType === 'soft'
    ? THREE.PCFSoftShadowMap
    : THREE.PCFShadowMap

  const cfg = seasonCfg()
  renderer.toneMappingExposure = cfg.exposure

  scene = new THREE.Scene()
  scene.fog = new THREE.Fog(cfg.fog, cfg.fogNear, cfg.fogFar)

  // near/far 比值决定深度缓冲精度（1/z 分布）。原来的 0.4 : 1000 = 2500:1，
  // 60 米外的深度间隔被压缩到不足一个像素，任何两片共面的地面都会互相穿插闪烁。
  // far 收到 600（远山最远 z=-540，够用），near 提到 0.6，比值降到 1000:1，
  // 深度分辨率提升 2.5 倍；配合地面各层的严格错高，共面问题彻底消除。
  // ⚠️ near 不能太大：贴地平视时相机 eye 高 1.15m，near 超过 1.0 会把
  //    近处田埂整片裁掉，所以这里取 0.6 —— 兼顾精度与近景不被裁。
  // ⚠️ **初始 fov / pitch 必须与 onResize 用同一套窄屏规则**，
  //   否则首帧是横屏参数、resize 后才变 → 进页面会看到一次"跳变"。
  const initAspect = w / h
  camera = new THREE.PerspectiveCamera(initAspect < 1 ? 58 : 48, initAspect, 0.6, 600)
  if (initAspect < 1) cam.pitch = 0.16
  applyCamera()

  hemiLight = new THREE.HemisphereLight(cfg.hemiSky, cfg.hemiGround, cfg.hemiI)
  scene.add(hemiLight)
  sunLight = new THREE.DirectionalLight(cfg.sun, cfg.sunI)
  // ⚠️ **太阳方向必须与 SUN_DIR 一致**（单一数据源）。
  //   DirectionalLight 的方向 = position - target，
  //   所以把 position 设成 SUN_DIR × 某个距离即可。
  //   之前这里是 (-30, 44, 26)（方向不一致），
  //   而影子又按另一个方向画 → 太阳在右上、影子往左下，一眼假。
  //
  // ⚠️⚠️ **target 必须跟着主田走**（2026-10-23 实测发现的真 bug）。
  //
  //   DirectionalLight 默认 target 在**世界原点**，且默认**不在场景图里**
  //   （three.js 不会自动把它加进 scene）。后果：
  //     · 地块网格是 4 列 × N 行，主田在 z = -Math.floor(slot/4) * 25.8。
  //       slot=13 时主田在 z=-77.4，而阴影相机仍钉在原点（z=0），
  //       阴影相机覆盖 z=-1.5~74.7 —— **和主田完全错开**，
  //       主田根本不在阴影贴图范围内 → 作物一点影子都没有。
  //     · 实测 shadowCamera 范围 = x[-66,16] / z[-1.5,74.7]，
  //       而 fieldGroup.position = (19, 0, -77.4) → 主田在覆盖范围外 76 米。
  //   表现：画面里看不到任何投影，"真实阴影"等于没开。
  //
  //   修法：把 target 加进场景并让它跟随主田中心（syncPlotLayout 里同步）。
  sunLight.position.set(SUN_DIR.x * 60, SUN_DIR.y * 60, SUN_DIR.z * 60)
  sunLight.target.position.set(0, 0, 0)
  scene.add(sunLight.target)
  // 阴影相机：正交视锥必须**只覆盖需要投影的区域**，
  // 否则 2048×2048 的阴影贴图摊到几百米上，
  // 每像素覆盖的范围太大 → 阴影边缘极度模糊、糊成一片。
  //
  // 取 26m 半宽：主田是 ±16.6 × ±22.6m，取 26 刚好盖住主田 + 田埂。
  // 远处树带（-95m 起）与远山不参与投影（它们离得太远，
  // 影子小到看不见，硬要投影只会浪费贴图分辨率）。
  sunLight.castShadow = Q.shadowEnabled
  // 🔴 **阴影贴图尺寸按画质档位**（2026-10-26）。
  //   2048² = 16MB 显存/帧重绘；1024² = 4MB。
  //   正交视锥只有 52m 宽（±26m），1024² 时每像素 5cm，
  //   对作物投影来说已经够用了（作物本身才 20~40cm）。
  sunLight.shadow.mapSize.set(Q.shadowMapSize, Q.shadowMapSize)
  sunLight.shadow.camera.left = -26
  sunLight.shadow.camera.right = 26
  sunLight.shadow.camera.top = 26
  sunLight.shadow.camera.bottom = -26
  sunLight.shadow.camera.near = 1
  sunLight.shadow.camera.far = 220
  // 阴影偏移：避免阴影 acne（自阴影的斑马纹）
  sunLight.shadow.bias = -0.0008
  sunLight.shadow.normalBias = 0.02
  scene.add(sunLight)
  fillLight = new THREE.DirectionalLight(0xcfe4ff, 0.35)
  fillLight.position.set(24, 16, -14)
  scene.add(fillLight)

  // 资产：串行加载 + 真实进度；单张失败只退化那一块，不整页兜底
  pipeline = createAssetPipeline(THREE, renderer, Q)
  const specs = assetSpecs(currentCrop())
  const total = specs.length
  progress.value = 4
  loadText.value = '准备贴图'
  const result = await pipeline.loadAll(specs, (done, all, key) => {
    progress.value = 4 + Math.round((done / all) * 88)
    loadText.value = ASSET_LABEL[key] || key
  })
  if (disposed) return
  assets = result.assets
  usedTexFiles = result.usedFiles || null
  if (result.degraded && result.degraded.length) {
    // 专属贴图缺失 → 退到通用族图（最坏情况是形态像别的作物，但能看）
    console.warn('[farm] 专属贴图缺失，已退到通用族图：', result.degraded.join(', '))
  }
  if (result.failed.length) {
    console.warn('[farm] 贴图降级为纯色占位：', result.failed.join(', '))
  }

  loadText.value = '生成天空与地形'
  progress.value = 94

  buildScene()

  progress.value = 100
  applyCamera()
  onResize()
  ready.value = true
  emit('ready', { failed: result.failed })

  ro = new ResizeObserver(() => onResize())
  ro.observe(wrap.value)

  const cv = canvas.value
  cv.addEventListener('pointerdown', onPointerDown)
  cv.addEventListener('pointermove', onPointerMove)
  cv.addEventListener('pointerup', onPointerUp)
  cv.addEventListener('pointercancel', onPointerUp)
  cv.addEventListener('wheel', onWheel, { passive: false })

  lastFrame = 0
  lastRenderAt = 0
  raf = requestAnimationFrame(animate)

  // 🔴 **订阅画质变化**（2026-10-26 画质分级）。
  //   画质切换有两条路径：
  //   · **热更新**（只改 pixelRatio / 阴影尺寸 / 各向异性 / 帧率上限）→ 直接改
  //   · **重建场景**（改了几何/贴图/着色器）→ 销毁并重新初始化
  //   判定逻辑全在 quality.js 的 requiresSceneRebuild()，这里只负责执行。
  unsubscribeQuality = onQualityChange((q) => {
    if (disposed) return
    const oldId = Q.id
    const newId = q.id
    if (oldId === newId) return
    const { rebuild, context } = requiresSceneRebuild(oldId, newId)
    if (!rebuild) {
      // 热更新路径
      Q = q.params
      applyQualityHotUpdate()
      emit('quality-changed', { id: newId, label: q.label, fps: fpsValue })
      return
    }
    // 重建路径：整个场景重来
    console.info(`[farm] 画质从「${oldId}」切到「${newId}」，重建场景（context=${context}）`)
    Q = q.params
    rebuildScene()
    emit('quality-changed', { id: newId, label: q.label, fps: fpsValue })
  })

  // ⚠️ **调试钩子**（仅开发环境）：把场景挂到 window，
  //   方便在浏览器控制台里 raycast 定位"屏幕上这块像素到底是什么物体"。
  //   生产构建会被 import.meta.env.DEV 摇掉。
  if (import.meta.env.DEV) {
    window.__farmScene = {
      get scene() { return scene },
      get camera() { return camera },
      get renderer() { return renderer },
      get fieldGroup() { return fieldGroup },
      get sunLight() { return sunLight },
      SUN_DIR, SUN,
      // 屏幕坐标 → 命中物体名
      probe(nx, ny) {
        const rc = new THREE.Raycaster()
        rc.setFromCamera(new THREE.Vector2(nx, ny), camera)
        const hits = rc.intersectObjects(scene.children, true)
        return hits.slice(0, 4).map((h) => ({
          name: h.object.name || '(unnamed)',
          type: h.object.type,
          matType: h.object.material?.type,
          dist: +h.distance.toFixed(2),
          pt: [+h.point.x.toFixed(1), +h.point.y.toFixed(1), +h.point.z.toFixed(1)],
          order: h.object.renderOrder,
          uuid: h.object.uuid.slice(0, 8)
        }))
      },
      list() {
        const out = []
        scene.traverse((o) => {
          if (o.isMesh || o.isLine || o.isPoints) {
            out.push({
              uuid: o.uuid.slice(0, 8),
              type: o.type,
              order: o.renderOrder,
              y: +o.position.y.toFixed(3),
              z: +o.position.z.toFixed(1),
              mat: o.material?.type,
              visible: o.visible,
              inst: o.count ?? null
            })
          }
        })
        return out
      }
    }
  }
}

/**
 * 🔴 **画质热更新**（2026-10-26 画质分级）
 *
 *   只处理「改属性就生效」的那几项，不需要重建几何体/贴图：
 *   · pixelRatio       —— setPixelRatio 即可
 *   · 阴影开关/尺寸/类型 —— 需要 dispose 旧 shadow.map 让 three 重建
 *   · 各向异性         —— 贴图上的 anisotropy 可逐张改
 *   · 帧率上限         —— animate 里每帧读 Q.maxFps，自动生效
 *
 *   ⚠️ **阴影贴图尺寸改了必须 dispose 旧 map**：
 *   three.js 只在 `shadow.map` 为 null 时才按新的 mapSize 创建贴图。
 *   不 dispose 的话，旧的 2048² 贴图会一直用着，改了尺寸没反应。
 */
function applyQualityHotUpdate() {
  if (renderer) {
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, Q.pixelRatioMax))
    renderer.shadowMap.enabled = Q.shadowEnabled
    renderer.shadowMap.type = Q.shadowType === 'soft'
      ? THREE.PCFSoftShadowMap
      : THREE.PCFShadowMap
  }
  if (sunLight) {
    sunLight.castShadow = Q.shadowEnabled
    const size = Q.shadowMapSize
    if (sunLight.shadow.mapSize.x !== size) {
      sunLight.shadow.mapSize.set(size, size)
      // 🔴 **必须 dispose 旧阴影贴图**，否则新尺寸不生效
      sunLight.shadow.map?.dispose()
      sunLight.shadow.map = null
    }
  }
  // 各向异性：贴图上的值要逐张改（管线里的 maxAniso 固定了，改不了这里，
  // 但可以按新上限把已有的贴图压下来）
  const aniso = Math.max(1, Q.anisotropy)
  scene?.traverse((o) => {
    const mats = o.material ? (Array.isArray(o.material) ? o.material : [o.material]) : []
    mats.forEach((m) => {
      ;['map', 'normalMap', 'roughnessMap'].forEach((k) => {
        const t = m[k]
        if (t && t.anisotropy > aniso) {
          t.anisotropy = aniso
          t.needsUpdate = true
        }
      })
    })
  })
}

/**
 * 🔴 **画质重建**（2026-10-26 画质分级）
 *
 *   画质切换改变了几何/贴图/着色器 → 必须整个场景重来。
 *   做法：cleanup() 的资源释放逻辑抽出来复用，然后重新 init()。
 *
 *   ⚠️ **不能直接调 init()**：init() 里会 `new THREE.WebGLRenderer({ canvas })`，
 *   而同一个 canvas 元素上不能创建第二个 WebGL 上下文（会拿到已废弃的上下文）。
 *   正确做法：先 `renderer.dispose()` + `forceContextLoss()` 释放上下文，
 *   再把 canvas 换成一个新的元素（或重建整个组件）。
 *
 *   ⚠️ **最稳的做法是让父组件用 :key 强制重建组件** ——
 *   组件卸载 → 全新 canvas → 全新上下文，没有任何上下文复用的坑。
 *   所以这里只做「请求父组件重建」，不自己折腾上下文。
 */
function rebuildScene() {
  // 通过 emit 请求父组件用新的 :key 重挂载组件（见 GameFarm.vue）
  emit('rebuild-scene', { quality: Q.id })
}

function cleanup() {
  disposed = true
  cancelAnimationFrame(raf)
  ro?.disconnect()
  unsubscribeQuality?.()
  unsubscribeQuality = null
  const cv = canvas.value
  if (cv) {
    cv.removeEventListener('pointerdown', onPointerDown)
    cv.removeEventListener('pointermove', onPointerMove)
    cv.removeEventListener('pointerup', onPointerUp)
    cv.removeEventListener('pointercancel', onPointerUp)
    cv.removeEventListener('wheel', onWheel)
  }
  cropField?.dispose()
  cropField = null
  scene?.traverse((o) => {
    if (o.geometry) o.geometry.dispose?.()
    if (o.material) {
      const mats = Array.isArray(o.material) ? o.material : [o.material]
      mats.forEach((m) => {
        m.dispose?.()
      })
    }
  })
  scene?.traverse((o) => {
    if (o.material && o.material.map) o.material.map.dispose?.()
  })
  // ⚠️ 必须清空全局风动登记表：
  //   addWindShader 把材质的 windTime uniform 登记进了模块级 Set，
  //   组件卸载后如果不清理，旧材质的 uniform 仍会被 tickAllWind 推进，
  //   既是内存泄漏也是"幽灵动画"（重建场景后新旧材质同帧跳动）。
  clearWindRegistry()
  // 调试钩子也要摘掉：否则 window.__farmScene 会残留一个 scene=null 的空壳，
  // 后续诊断误以为场景还在（实测踩过：地图页上 __farmScene 仍为 true）。
  if (import.meta.env.DEV && window.__farmScene) {
    delete window.__farmScene
  }
  pipeline?.dispose()
  pipeline = null
  envRT?.dispose?.()
  pmrem?.dispose?.()
  renderer?.dispose?.()
  renderer = null
  scene = null
  camera = null
  assets = null
  weatherObj = null
  treeMesh = null
  weedMesh = null
  grassMesh = null
  grassDetailMesh = null
  groundMesh = null
  waterMesh = null
  soilWetMesh = null
  skyMesh = null
  farFieldMeshes = []
}

onMounted(init)
onBeforeUnmount(cleanup)

watch(() => props.stage, () => {
  cropField?.update(props.stage, props.season)
})

watch(() => props.slot, () => {
  if (!THREE || !scene) return
  syncPlotLayout()
  applyCamera()
})

watch(() => props.plots, () => {
  if (!THREE || !fieldGroup) return
  syncPlotLayout()
}, { deep: true })

watch(() => props.season, (s) => {
  if (!THREE || !scene) return
  const cfg = SEASON[s] || SEASON.summer
  // ⚠️ **先重算太阳方向，再重建天空**（2026-10-23 画质升级）。
  //   顺序不能反：buildSky() 读的是 SUN.el / SUN.az，
  //   如果不先更新，光晕还是旧季节的位置。
  applySunDirection(s)
  if (scene.fog) {
    scene.fog.color.set(cfg.fog)
    scene.fog.near = cfg.fogNear
    scene.fog.far = cfg.fogFar
  }
  if (hemiLight) {
    hemiLight.color.set(cfg.hemiSky)
    hemiLight.groundColor.set(cfg.hemiGround)
    hemiLight.intensity = cfg.hemiI
  }
  if (sunLight) {
    sunLight.color.set(cfg.sun)
    sunLight.intensity = cfg.sunI
  }
  if (renderer) renderer.toneMappingExposure = cfg.exposure
  if (grassMesh) grassMesh.material.color.set(cfg.grassTint)
  if (treeMesh) treeMesh.material.color.set(cfg.plantTint)
  // 远山换季要换色相微调（ridgeTint），否则秋天暖雾里配冷绿山 → 割裂。
  // 远山是 fieldGroup 下 renderOrder=-3 的透明平面，按材质特征筛出来即可。
  if (cfg.ridgeTint) {
    fieldGroup?.traverse((o) => {
      if (o.isMesh && o.renderOrder === -3 && o.material?.color) {
        o.material.color.set(cfg.ridgeTint)
      }
    })
  }
  // 天空的色阶是逐像素烘焙的，换季必须重建（一季一次，成本可接受）
  buildSky()
  applyWaterVisual()
  cropField?.update(props.stage, s)
})

watch(() => props.weather, () => {
  if (!THREE || !scene) return
  buildWeather()
}, { deep: true })

watch(() => props.water, () => applyWaterVisual())
</script>

<style scoped>
.farm3d {
  position: absolute;
  inset: 0;
  overflow: hidden;
  background: #8fb6c9;
}
.farm3d-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  display: block;
  touch-action: none;
  cursor: grab;
}
.farm3d-canvas:active {
  cursor: grabbing;
}
.farm3d-loading {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14px;
  color: #f2f7fb;
  background: linear-gradient(180deg, #5f92b8, #7fa87c);
}
.loading-text {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
.loading-text strong {
  font-size: 14px;
  font-weight: 600;
  letter-spacing: 2px;
}
.loading-text em {
  font-style: normal;
  font-size: 12px;
  opacity: 0.78;
}
.loading-bar {
  width: 180px;
  height: 3px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.24);
  overflow: hidden;
}
.loading-bar i {
  display: block;
  height: 100%;
  background: #fff;
  transition: width 0.25s ease;
}
.spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(255, 255, 255, 0.45);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.9s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
