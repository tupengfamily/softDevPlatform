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
 *   - 不开 shadowMap（沙箱内 InstancedMesh 阴影会抛 Invalid arguments），用贴图软阴影
 *   - 精灵用 alphaTest 硬切，避免透明排序糊成一团
 *   - 相机 near 不能太小（0.4），否则远处草地与邻田共面会 z-fighting 闪烁
 */
import { onMounted, onBeforeUnmount, ref, watch } from 'vue'
import {
  SEASON, PADDY_W, PADDY_D, FIELD_CZ, CAMERA, LOOK_AT, DENSITY, SPRITE_ASPECT, normalizeCrop
} from './scene/config.js'
import {
  mulberry32, makeSoftShadowTexture, makeRowShadeTexture, boxWithUV, buildCrossQuad
} from './scene/util.js'
import { makeSkyTexture, makeRidgeLayers } from './scene/sky.js'
import { createAssetPipeline } from './scene/assets.js'
import { createCropField } from './scene/crops.js'

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
const emit = defineEmits(['ready', 'fail', 'select-plot', 'create-plot'])

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
let raf = 0
let ro = null
let disposed = false
let pmrem = null
let envRT = null

let skyMesh = null
let grassMesh = null
let groundMesh = null
let waterMesh = null
let hemiLight = null
let sunLight = null
let fillLight = null
let weatherObj = null
let treeMesh = null
let treeShadow = null
let weedMesh = null
let farFieldMeshes = []
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
 * 天空球半径。
 *
 * ⚠️ **必须小于 camera.far**（当前 600）：球面一旦落在远裁剪面之外，
 * 画面上方就会露出大片黑色清屏色（看起来像天塌了个洞）。
 * 天空球在 applyCamera 里跟随相机，等价于把天空放到无穷远，
 * 所以半径只受 far 约束，取 500 留足余量。
 */
const SKY_RADIUS = 500

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
  young: '作物幼苗', ripe: '作物成株', tree: '林带树木', weed: '田埂野草'
}

/** 作物 → 需要的贴图清单（稻作与旱作各用一套土壤） */
function assetSpecs(cropId) {
  const soil = cropId === 'rice' ? 'soil_paddy' : 'soil_dry'
  return [
    { key: 'soilMap', file: `${soil}_albedo.jpg`, mirror: true },
    { key: 'soilNorm', file: `${soil}_normal.jpg`, srgb: false, mirror: true },
    { key: 'soilRough', file: `${soil}_rough.jpg`, srgb: false, mirror: true },
    { key: 'grassMap', file: 'ground_grass_albedo.jpg', mirror: true },
    { key: 'grassNorm', file: 'ground_grass_normal.jpg', srgb: false, mirror: true },
    { key: 'fieldMap', file: 'field_rice_albedo.jpg', mirror: true },
    { key: 'young', file: `plant_${cropId}_young.png` },
    { key: 'ripe', file: `plant_${cropId}_ripe.png` },
    { key: 'tree', file: 'plant_tree.png' },
    { key: 'weed', file: 'plant_grass_tuft.png' }
  ]
}

function currentCrop() {
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
  const sunEl = props.season === 'winter' ? 0.28 : 0.52
  const canvasSky = makeSkyTexture(cfg, -0.62, sunEl)
  const skyTex = new THREE.CanvasTexture(canvasSky)
  skyTex.colorSpace = THREE.SRGBColorSpace
  skyTex.mapping = THREE.EquirectangularReflectionMapping
  skyTex.anisotropy = 4

  skyMesh = new THREE.Mesh(
    // 半径必须 < camera.far（600），否则球面会被远裁剪面切掉，
    // 画面上方露出大片黑色清屏色（黑洞）。
    // 配合"天空球跟随相机"（见 applyCamera），天空等价于在无穷远，
    // 半径只需大于近处地形的距离即可。
    new THREE.SphereGeometry(SKY_RADIUS, 64, 32),
    new THREE.MeshBasicMaterial({
      map: skyTex, side: THREE.BackSide, depthWrite: false, depthTest: false, fog: false
    })
  )
  skyMesh.renderOrder = -100
  skyMesh.position.copy(camera?.position || new THREE.Vector3())
  scene.add(skyMesh)
  // 换季重建后必须重新贴回相机位置，否则新球会留在原点，
  // 相机拉远后又会露出黑色。
  if (camera) skyMesh.position.copy(camera.position)

  // 天空图直接当 equirect 环境图：泥土与水面才有天光反射
  try {
    if (!pmrem) pmrem = new THREE.PMREMGenerator(renderer)
    pmrem.compileEquirectangularShader()
    envRT?.dispose?.()
    envRT = pmrem.fromEquirectangular(skyTex)
    scene.environment = envRT.texture
  } catch (e) {
    /* 环境贴图失败不影响主流程 */
  }
}

function buildTerrain() {
  const cfg = seasonCfg()
  const A = assets
  const cropId = currentCrop()
  const paddy = cropId === 'rice'

  // ---------- 草地 ----------
  A.grassMap.repeat.set(10, 8)
  A.grassNorm.repeat.set(10, 8)
  grassMesh = new THREE.Mesh(
    new THREE.PlaneGeometry(240, 200),
    new THREE.MeshStandardMaterial({
      map: A.grassMap,
      normalMap: A.grassNorm,
      normalScale: new THREE.Vector2(0.85, 0.85),
      roughness: 0.92,
      metalness: 0,
      color: cfg.grassTint
    })
  )
  grassMesh.rotation.x = -Math.PI / 2
  grassMesh.position.y = GRASS_Y
  scene.add(grassMesh)

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
  A.fieldMap.repeat.set(2.2, 3.0)
  const farMat = new THREE.MeshStandardMaterial({
    map: A.fieldMap,
    roughness: 0.95,
    metalness: 0,
    color: paddy ? 0xffffff : 0xd9dcb4
  })
  // [x, z, 宽, 深, 平面内旋转]
  const neighbors = [
    [-26, -40, 30, 24, 0.06],
    [27, -36, 34, 26, -0.05],
    [2, -62, 58, 34, 0.02],
    [-34, -72, 36, 26, -0.08],
    [38, -70, 32, 24, 0.07],
    [0, -96, 90, 40, 0.0]
  ]
  farFieldMeshes = neighbors.map(([x, z, w, d, rot], i) => {
    // 每块独立材质 + 递增的高度，保证 6 块之间深度值绝不相同
    const m = new THREE.Mesh(
      new THREE.PlaneGeometry(w, d),
      cloneWithOffset(farMat, i, 1)
    )
    m.rotation.x = -Math.PI / 2
    m.rotation.z = rot
    m.position.set(x, FAR_FIELD_Y + i * FAR_FIELD_STEP, z)
    scene.add(m)
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
    scene.add(m)
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
  fieldGroup.add(groundMesh)

  // ---------- 垄沟（行间明暗节奏） ----------
  const rowGap = (DENSITY[cropId] || DENSITY.rice).step
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
  const bundMat = new THREE.MeshStandardMaterial({
    map: A.soilMap.clone(), roughness: 0.95, metalness: 0, color: 0xfff0cc
  })
  bundMat.map.repeat.set(9, 1)
  bundMat.map.needsUpdate = true
  const bundLong = boxWithUV(THREE, PADDY_W + 1.4, 0.36, 0.78, 12, 1)
  const bundShort = boxWithUV(THREE, 0.78, 0.36, PADDY_D + 1.4, 1, 14)
  for (const [x, z, geo] of [
    [0, FIELD_CZ - PADDY_D / 2 - 0.28, bundLong],
    [0, FIELD_CZ + PADDY_D / 2 + 0.28, bundLong],
    [-PADDY_W / 2 - 0.28, FIELD_CZ, bundShort],
    [PADDY_W / 2 + 0.28, FIELD_CZ, bundShort]
  ]) {
    const b = new THREE.Mesh(geo, bundMat)
    b.position.set(x, 0.14, z)
    fieldGroup.add(b)
  }
  const pathMat = new THREE.MeshStandardMaterial({
    map: A.soilMap.clone(), roughness: 0.96, metalness: 0, color: 0xf6ecd6
  })
  pathMat.map.repeat.set(2, 30)
  pathMat.map.needsUpdate = true
  const path = new THREE.Mesh(new THREE.BoxGeometry(1.25, 0.1, PADDY_D + 2.6), pathMat)
  path.position.set(PADDY_W / 2 + 1.5, 0.05, FIELD_CZ)
  fieldGroup.add(path)
}

function buildTrees() {
  const cfg = seasonCfg()
  const treeGeo = buildCrossQuad(THREE, 11, SPRITE_ASPECT.plant_tree, 1.0)
  const treeMat = new THREE.MeshBasicMaterial({
    map: assets.tree, alphaTest: 0.4, side: THREE.DoubleSide, color: cfg.plantTint, fog: true
  })
  const TREE_N = 60
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
    const zBase = row === 0 ? -74 : row === 1 ? -104 : -150
    const z = zBase - rnd() * (row === 0 ? 14 : row === 1 ? 24 : 44)
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
  scene.add(treeMesh)

  // 树底接触阴影：没有 shadowMap，靠贴图把树"按"在地上
  const shGeo = new THREE.PlaneGeometry(1, 1)
  shGeo.rotateX(-Math.PI / 2)
  treeShadow = new THREE.InstancedMesh(
    shGeo,
    new THREE.MeshBasicMaterial({
      map: makeSoftShadowTexture(THREE), transparent: true, depthWrite: false, opacity: 0.5
    }),
    TREE_N
  )
  treeShadow.frustumCulled = false
  const sd = new THREE.Object3D()
  for (let i = 0; i < TREE_N; i++) {
    treeMesh.getMatrixAt(i, sd.matrix)
    sd.matrix.decompose(sd.position, sd.quaternion, sd.scale)
    const w = 3.4 * sd.scale.x
    sd.position.y = 0.02
    sd.scale.set(w, 1, w)
    sd.rotation.set(0, 0, 0)
    sd.updateMatrix()
    treeShadow.setMatrixAt(i, sd.matrix)
  }
  treeShadow.instanceMatrix.needsUpdate = true
  treeShadow.renderOrder = 2
  scene.add(treeShadow)
}

function buildWeeds() {
  const weedGeo = buildCrossQuad(THREE, 0.42, SPRITE_ASPECT.plant_grass_tuft, 1.0)
  const weedMat = new THREE.MeshBasicMaterial({
    map: assets.weed, alphaTest: 0.38, side: THREE.DoubleSide, color: 0xf2f2e4, fog: true
  })
  const N = 90
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
  scene.add(weedMesh)
}

function buildScene() {
  fieldGroup = new THREE.Group()
  neighborGroup = new THREE.Group()
  scene.add(fieldGroup)
  scene.add(neighborGroup)

  buildSky()
  scene.add(...makeRidgeLayers(THREE))
  buildTerrain()

  cropField = createCropField(THREE, fieldGroup, assets, currentCrop())
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
 */
function neighborTint(visual, stage) {
  const st = String(stage || '')
  if (!visual) return 0x8a7a5e
  const ripe = /成熟|黄熟|灌浆|乳熟|蜡熟/.test(st)
  const young = /苗|出苗|播种|返青|分蘖/.test(st)
  if (visual === 'rice') {
    if (ripe) return 0xd8bd52
    if (young) return 0x86a24c
    return 0x4f8f2c
  }
  if (visual === 'wheat') {
    if (ripe) return 0xe8c451
    if (young) return 0x7d9d45
    return 0x4a8a2c
  }
  if (visual === 'corn') {
    if (ripe) return 0xb9a342
    if (young) return 0x8a9c3c
    return 0x3f8a22
  }
  // soybean
  if (ripe) return 0xc0a63c
  if (young) return 0x86a03f
  return 0x3e8526
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
      const o = plotOrigin(slot)

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
        const plane = new THREE.Mesh(
          new THREE.PlaneGeometry(PADDY_W, PADDY_D),
          cloneWithOffset(new THREE.MeshBasicMaterial({
            map: nTex,
            color: tint,
            fog: true
          }), slot, -1)
        )
        plane.rotation.x = -Math.PI / 2
        // 每块田按 slot 错开高度，绝不与任何邻块共面
        plane.position.set(o.x, NEIGHBOR_Y + (slot % 5) * NEIGHBOR_STEP, o.z + FIELD_CZ)
        plane.userData.slot = slot
        neighborGroup.add(plane)

        // 田埂：加高到 0.55 并用亮暖色，从高处俯视时形成清晰的田块轮廓
        const bundMat = new THREE.MeshBasicMaterial({ color: 0xe4cf9c, fog: true })
        const edges = [
          [PADDY_W + 1.2, 0.55, 0.72, o.x, 0.275, o.z + FIELD_CZ - PADDY_D / 2 - 0.36],
          [PADDY_W + 1.2, 0.55, 0.72, o.x, 0.275, o.z + FIELD_CZ + PADDY_D / 2 + 0.36],
          [0.72, 0.55, PADDY_D + 1.2, o.x - PADDY_W / 2 - 0.36, 0.275, o.z + FIELD_CZ],
          [0.72, 0.55, PADDY_D + 1.2, o.x + PADDY_W / 2 + 0.36, 0.275, o.z + FIELD_CZ]
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
        const plane = new THREE.Mesh(
          new THREE.PlaneGeometry(PADDY_W, PADDY_D),
          cloneWithOffset(new THREE.MeshBasicMaterial({
            map: vTex,
            color: 0x8a7355,
            transparent: true,
            opacity: 0.9,
            fog: true
          }), slot + 1, -1)
        )
        plane.rotation.x = -Math.PI / 2
        // 空地比已种田块低，且按 slot 错开，彻底避免与已种田块共面
        plane.position.set(o.x, VACANT_Y + (slot % 5) * VACANT_STEP, o.z + FIELD_CZ)
        plane.userData.slot = slot
        plane.userData.vacant = true
        neighborGroup.add(plane)

        // 荒地边框：低矮的土埂 + 冷灰色调，一眼看出"这块还没开垦"
        const edgeMat = new THREE.MeshBasicMaterial({
          color: 0x6f6250, transparent: true, opacity: 0.85, fog: true
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
        if (assets.weed) {
          const weedGeo = buildCrossQuad(THREE, 0.7, SPRITE_ASPECT.plant_grass_tuft, 1.0)
          const weedMat = new THREE.MeshBasicMaterial({
            map: assets.weed, alphaTest: 0.4, side: THREE.DoubleSide,
            color: 0x8f8a62, fog: true
          })
          const rnd = mulberry32(slot * 977 + 13)
          const dummy = new THREE.Object3D()
          const N = 7
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
  if (currentCrop() !== 'rice') {
    waterMesh.visible = false
    groundMesh.material.color.set(cfg.soilTint)
    return
  }
  waterMesh.visible = true
  const w = props.water || 0
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
  camera.aspect = w / h
  camera.updateProjectionMatrix()
  const aspect = w / h
  const base = CAMERA.base * (aspect < 1.45 ? Math.pow(1.45 / aspect, 0.55) : 1)
  cam.base = base
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

function animate(now) {
  raf = requestAnimationFrame(animate)
  if (!renderer || !scene || !camera) return
  const dt = lastFrame ? Math.min(0.05, (now - lastFrame) / 1000) : 0.016
  lastFrame = now
  const t = now * 0.001


  if (waterMesh && waterMesh.visible) {
    waterMesh.material.opacity = Math.max(
      0.02,
      Math.min(0.24, waterMesh.material.opacity + Math.sin(t * 0.7) * 0.0014)
    )
  }

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

  renderer.render(scene, camera)
}

// ---------------- 初始化 ----------------

async function init() {
  try {
    THREE = await import('three')
  } catch (e) {
    emit('fail', { reason: 'three-missing' })
    return
  }
  if (disposed || !canvas.value || !wrap.value) return

  const w = wrap.value.clientWidth || 960
  const h = wrap.value.clientHeight || 600

  try {
    renderer = new THREE.WebGLRenderer({
      canvas: canvas.value,
      antialias: true,
      alpha: false,
      powerPreference: 'high-performance'
    })
  } catch (e) {
    emit('fail', { reason: 'webgl' })
    return
  }
  if (!renderer) {
    emit('fail', { reason: 'webgl' })
    return
  }

  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.75))
  renderer.setSize(w, h, false)
  renderer.outputColorSpace = THREE.SRGBColorSpace
  renderer.toneMapping = THREE.ACESFilmicToneMapping

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
  camera = new THREE.PerspectiveCamera(48, w / h, 0.6, 600)
  applyCamera()

  hemiLight = new THREE.HemisphereLight(cfg.hemiSky, cfg.hemiGround, cfg.hemiI)
  scene.add(hemiLight)
  sunLight = new THREE.DirectionalLight(cfg.sun, cfg.sunI)
  sunLight.position.set(-30, 44, 26)
  scene.add(sunLight)
  fillLight = new THREE.DirectionalLight(0xcfe4ff, 0.35)
  fillLight.position.set(24, 16, -14)
  scene.add(fillLight)

  // 资产：串行加载 + 真实进度；单张失败只退化那一块，不整页兜底
  pipeline = createAssetPipeline(THREE, renderer)
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
  raf = requestAnimationFrame(animate)
}

function cleanup() {
  disposed = true
  cancelAnimationFrame(raf)
  ro?.disconnect()
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
  treeShadow = null
  weedMesh = null
  grassMesh = null
  groundMesh = null
  waterMesh = null
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
