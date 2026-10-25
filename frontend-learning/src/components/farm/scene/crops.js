/**
 * 作物层：照片切图的十字面片 + 按物候阶段切换形态。
 *
 * 一个阶段一个 InstancedMesh（共享同一套 UV），这样"截顶模拟抽穗前"才能成立——
 * 逐实例改 UV 在 InstancedMesh 里做不到，只能按阶段分 mesh。
 *
 * ⚠️ **画质要点（2026-10-23 升级）**：
 *   1. 材质从 MeshBasicMaterial 换成 **MeshStandardMaterial** ——
 *      Basic 完全不受光，作物就是一块死平的色块（像贴纸）。
 *      换成 Standard 后吃太阳光 + 半球光 + PMREM 环境图，明暗关系与田面一致。
 *   2. 法线**球面化**（spherifyNormals）—— 十字面片本身是平面，
 *      不处理的话整株作物所有像素 N·L 相同，受光后依然是一块平色。
 *      球面化后中心亮、边缘暗，才有"一团 foliage"的体积感。
 *   3. **定向接触阴影** —— 阴影不画在正下方，而是按太阳方位偏到一侧并拉长，
 *      这样阴影像真的一样有方向感，不会显得"贴纸浮在土上"。
 */

import { STAGE_PLAN, SPRITE_ASPECT, PADDY_W, PADDY_D, FIELD_CZ, SEASON, densityOf, alphaCutoffOf, QUAD_SEGMENTS, postureOf } from './config.js'
import { buildCrossQuad, buildGroundQuad, mulberry32, spherifyNormals, addWindShader } from './util.js'

/**
 * 生成田地里的丛坐标（确定性随机，同一季每次打开位置一致）。
 *
 * @param {string} cropId   贴图族名（决定用哪张精灵图）
 * @param {string} densityKey 作物 id（决定种多密）—— 与贴图族可以不同：
 *                           梨借用灌木的图，但株距是 2.0m，不是灌木的 0.62m。
 */
export function buildClumpData(cropId, densityKey) {
  const rnd = mulberry32(20261019)
  const D = densityOf(densityKey || cropId)
  const out = []
  const x0 = -((D.cols - 1) * D.step) / 2
  const z0 = FIELD_CZ - ((D.rows - 1) * D.step) / 2
  for (let ix = 0; ix < D.cols; ix++) {
    for (let iz = 0; iz < D.rows; iz++) {
      out.push({
        x: x0 + ix * D.step + (rnd() - 0.5) * D.step * 0.34,
        z: z0 + iz * D.step + (rnd() - 0.5) * D.step * 0.34,
        s: 0.88 + rnd() * 0.28,
        rot: rnd() * Math.PI,
        seed: rnd()
      })
    }
  }
  return out
}

/**
 * 创建作物层。
 * @param {object} THREE
 * @param {object} scene
 * @param {object} assets  含 `plant_<crop>_young` / `plant_<crop>_ripe`
 * @param {string} cropId    贴图族名（用哪张精灵图）
 * @param {string} densityKey 作物 id（种多密），默认与 cropId 相同
 * @param {object} [texKeys] 实际用上的贴图文件名（key → 'plant_xxx_young.png'）。
 *        降级到通用族图时，画布宽高比必须按**族图的实测值**取，
 *        否则面片会被拉扁（aspect 错了 → 十字面片变形 → 一眼看假）。
 */
export function createCropField(THREE, parent, assets, cropId, densityKey, texKeys, renderer) {
  const clumps = buildClumpData(cropId, densityKey)
  const plan = STAGE_PLAN[cropId]
  const meshes = []
  const stageLists = [[], [], [], [], [], []]

  /**
   * 取某张贴图的实际宽高比。
   * 优先按**实际用上的文件**查（降级后是族图），查不到再按 cropId 猜。
   */
  function aspectOf(tex) {
    const file = texKeys?.[tex === 'ripe' ? 'ripe' : 'young']
    if (file) {
      const key = file.replace(/\.png$/, '')
      if (SPRITE_ASPECT[key]) return SPRITE_ASPECT[key]
    }
    return SPRITE_ASPECT[`plant_${cropId}_${tex}`] || 0.8
  }

  // 🔴 **alphaToCoverage 需要 WebGL2 + MSAA**。
  //   开启后，alpha 落在 [alphaTest, 1) 区间的像素不会被丢弃，而是
  //   按 alpha 比例"部分覆盖"MSAA 样本 —— 边缘变成抗锯齿软边，
  //   同时不会像 transparent:true 那样引入排序问题。
  //   WebGL1 或没开 MSAA 时自动退回普通 alphaTest（无副作用）。
  const canUseA2C = (() => {
    try {
      const gl = renderer?.getContext?.()
      if (!gl || typeof WebGL2RenderingContext === 'undefined') return false
      if (!(gl instanceof WebGL2RenderingContext)) return false
      return gl.getContextAttributes?.()?.antialias === true
    } catch (_) {
      return false
    }
  })()
  const cutoff = alphaCutoffOf(cropId)

  /**
   * 判断某张法线图是否真的可用。
   *
   * ⚠️ **必须检查是不是占位图**：资产管线里法线图是**独立 key**，
   *   它自己的降级链跟 albedo 不同步 ——
   *   albedo 退到族图时法线图可能仍然 404 → 拿到纯色占位图（0x808080）。
   *   把占位图当法线图用 = 整个叶片被当成一块斜面打光，比没有法线图更糟。
   */
  function usableNormal(tex) {
    if (!tex) return false
    if (tex.userData && tex.userData.fallback) return false
    return true
  }

  for (let s = 0; s < 6; s++) {
    const st = plan[s]
    const isRipe = st.tex === 'ripe'
    const normalTex = isRipe ? assets.ripeNorm : assets.youngNorm
    const mat = new THREE.MeshStandardMaterial({
      map: isRipe ? assets.ripe : assets.young,
      transparent: false,
      // ⚠️ 阈值按作物分档（见 config.js ALPHA_CUTOFF 的说明）：
      //   真实照片边缘是半透明羽化，阈值太高会被啃成锯齿缺口。
      alphaTest: cutoff,
      alphaToCoverage: canUseA2C,
      side: THREE.DoubleSide,
      color: 0xffffff,
      fog: true,
      // 叶片受光要柔和：roughness 偏高、metalness=0，
      // 否则会像塑料片一样出现高光死白。
      roughness: 0.86,
      metalness: 0,
      envMapIntensity: 0.55
    })
    // 🔴 **法线贴图**（2026-10-24 画质升级）：
    //   让叶片**内部**的叶脉、卷边、褶皱也能吃到光照。
    //   没有它时球面化法线只给出"整株"级别的体积感，叶片内部完全是平的。
    //   normalScale 压到 0.55 —— 亮度当高度重建出来的凹凸本来就偏夸张，
    //   直接 1.0 会让叶片看起来像被敲过的金属片。
    if (usableNormal(normalTex)) {
      mat.normalMap = normalTex
      mat.normalScale = new THREE.Vector2(0.55, 0.55)
    }
    const aspect = aspectOf(st.tex)
    // 🔴 **姿态必须与素材的拍摄角度一致**（2026-10-24 画质升级）。
    //   甜瓜/西瓜/黄瓜的成熟素材是**俯视图**，塞进竖直十字面片就会变成
    //   "立起来的海报" —— 用户说的"纸片感"。
    //   这类作物改用**贴地面片**：几何角度与素材一致，瓜蔓趴在地上，
    //   从田埂看过去就是一片叶毯（真实农田里匍���作物本该如此）。
    const posture = postureOf(cropId, st.tex)
    let geo
    if (posture === 'ground') {
      // 贴地面片：边长 = 植株高度换算的冠幅（aspect 决定长短边）
      const groundSize = Math.max(0.25, st.h * 1.9)
      geo = buildGroundQuad(THREE, groundSize, QUAD_SEGMENTS.x)
    } else {
      // 🔴 **顶点细分**：让球面化法线在片内可密集插值（体积感连续），
      //   同时让风动的"梢摆根定"真正成立。见 util.js buildCrossQuad。
      geo = buildCrossQuad(
        THREE, st.h, aspect, st.vTop, QUAD_SEGMENTS.x, QUAD_SEGMENTS.y
      )
      // 球面化法线：让平面面片受光后有体积感（见文件头说明 2）
      spherifyNormals(THREE, geo, 0.9)
    }
    // 风动：作物幅度比树小得多（禾本科只有几厘米）
    addWindShader(mat, { amp: 0.055, speed: 1.35, height: Math.max(0.2, st.h) })
    const mesh = new THREE.InstancedMesh(geo, mat, Math.max(1, clumps.length))
    mesh.frustumCulled = false
    mesh.instanceMatrix.setUsage(THREE.StaticDrawUsage)
    mesh.count = 0
    // ⚠️ **开启真实阴影**（2026-10-23 画质升级）。
    //   InstancedMesh 的阴影投射在 three 0.160 已实测可用。
    //   作物投影到田面上，一行行错开 —— 这是"真实农田"最强的视觉线索：
    //   没有投影的田地永远像一张贴图。
    mesh.castShadow = true
    mesh.receiveShadow = false
    meshes.push(mesh)
    parent.add(mesh)
  }

  // ⚠️ **贴图式假接触阴影已移除**（2026-10-23）。
  //
  //   原来每个植株脚下一个径向渐变贴图（makeSoftShadowTexture），
  //   用来模拟"植株压在土上"的感觉。换成真实 shadowMap 后，
  //   这个假阴影会**和真阴影叠在一起**：
  //   正下方的黑圆 + 斜向的真实投影 = 一个双重阴影，比不画还脏。
  //
  //   而且假阴影有个致命问题：它是**正下方**的圆，
  //   斜射阳光下物理上不可能 —— 真实影子的方向由太阳决定。
  //
  //   现在只保留真实阴影。真阴影已经有"植株压在土上"的效果
  //   （shadowMap 的 PCF 软化 + normalBias 避免悬浮感）。

  const dummy = new THREE.Object3D()
  const col = new THREE.Color()
  const warm = new THREE.Color(0.985, 0.98, 0.955)
  const ripeWarm = new THREE.Color(1.02, 0.985, 0.86)
  const winterTint = new THREE.Color(0xd6e0e2)

  /**
   * 按阶段刷新实例。
   * 每丛 86% 落在基准阶段，少量前后错开一档——真实田块不会同一天整齐划一。
   */
  function update(stage, season) {
    const base = Math.max(0, Math.min(5, stage | 0))
    const cfg = SEASON[season] || SEASON.summer
    const winter = season === 'winter'
    const tint = new THREE.Color(cfg.plantTint)

    for (const l of stageLists) l.length = 0
    clumps.forEach((c, i) => {
      let st = base
      if (c.seed > 0.86 && base < 5) st = base + 1
      else if (c.seed < 0.12 && base > 0 && base < 5) st = base - 1
      stageLists[st].push(i)
    })

    for (let s = 0; s < 6; s++) {
      const mesh = meshes[s]
      const list = stageLists[s]
      const st = plan[s]
      // 该阶段的姿态（与建几何时一致）
      const ground = postureOf(cropId, st.tex) === 'ground'
      mesh.count = list.length
      for (let k = 0; k < list.length; k++) {
        const c = clumps[list[k]]
        // ⚠️ **贴地面片要抬到地面上方一点**（y=0.012）：
        //   平躺的面片如果正好贴在 y=0，会与田面 z-fighting（闪烁）。
        //   竖直面片则沉到 y=-0.02（根部埋进土里，避免"浮空"）。
        dummy.position.set(c.x, ground ? 0.012 : -0.02, c.z)
        dummy.rotation.set(0, c.rot, 0)
        const sc = c.s * (0.94 + s * 0.014)
        // 一半实例水平镜像，削弱"同一株重复"的观感
        const flip = c.seed > 0.5 ? -1 : 1
        // ⚠️ **贴地面片不能水平镜像**：
        //   俯视素材里有明确的光影方向（叶片自身的明暗），
        //   镜像后光影和光照方向打架，画面会显得"脏"。
        //   改成绕 Y 轴旋转来制造变化（俯视素材旋转后看不出）。
        dummy.scale.set(ground ? sc : sc * flip, sc, sc)
        dummy.updateMatrix()
        mesh.setMatrixAt(k, dummy.matrix)

        col.copy(tint)
        if (winter) {
          col.lerp(winterTint, 0.45)
        } else {
          // ⚠️ 用 Standard 材质后，逐实例的明暗**不能再手工加**，
          // 否则会和真实光照叠成"双重阴影"（基线图里就是这么发闷的）。
          // 这里只保留很小的随机亮度差（模拟叶片本身的色差），幅度压到 ±6%。
          col.multiplyScalar(0.94 + c.seed * 0.06)
          col.multiply(s === 5 ? ripeWarm : warm)
        }
        mesh.setColorAt(k, col)
      }
      mesh.instanceMatrix.needsUpdate = true
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
    }
  }

  /**
   * 逐帧推进风动时间。
   * ⚠️ 用 material.userData.windTime.value 而不是每帧 setMatrixAt ——
   *   上千个实例改矩阵会吃光帧预算；位移在顶点着色器里做，零 CPU 成本。
   */
  function tick(elapsed) {
    for (const m of meshes) {
      const t = m.material?.userData?.windTime
      if (t) t.value = elapsed
    }
  }

  function dispose() {
    meshes.forEach((m) => {
      m.geometry.dispose?.()
      m.material.dispose?.()
      parent.remove(m)
    })
  }

  return {
    clumps,
    meshes,
    update,
    tick,
    dispose,
    setShadowVisible() { /* 真实阴影由 shadowMap 控制，无可切换的假阴影 */ },
    paddy: { w: PADDY_W, d: PADDY_D }
  }
}
