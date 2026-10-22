/**
 * 作物层：照片切图的十字面片 + 按物候阶段切换形态。
 *
 * 一个阶段一个 InstancedMesh（共享同一套 UV），这样"截顶模拟抽穗前"才能成立——
 * 逐实例改 UV 在 InstancedMesh 里做不到，只能按阶段分 mesh。
 */

import { STAGE_PLAN, SPRITE_ASPECT, DENSITY, PADDY_W, PADDY_D, FIELD_CZ, SEASON } from './config.js'
import { buildCrossQuad, mulberry32, makeSoftShadowTexture } from './util.js'

/** 生成田地里的丛坐标（确定性随机，同一季每次打开位置一致） */
export function buildClumpData(cropId) {
  const rnd = mulberry32(20261019)
  const D = DENSITY[cropId]
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
 * @param {string} cropId
 */
export function createCropField(THREE, parent, assets, cropId) {
  const clumps = buildClumpData(cropId)
  const plan = STAGE_PLAN[cropId]
  const meshes = []
  const stageLists = [[], [], [], [], [], []]

  for (let s = 0; s < 6; s++) {
    const st = plan[s]
    const mat = new THREE.MeshBasicMaterial({
      map: st.tex === 'ripe' ? assets.ripe : assets.young,
      transparent: false,
      alphaTest: 0.4,
      side: THREE.DoubleSide,
      color: 0xffffff,
      fog: true
    })
    const aspect = SPRITE_ASPECT[`plant_${cropId}_${st.tex}`] || 0.8
    const geo = buildCrossQuad(THREE, st.h, aspect, st.vTop)
    const mesh = new THREE.InstancedMesh(geo, mat, Math.max(1, clumps.length))
    mesh.frustumCulled = false
    mesh.instanceMatrix.setUsage(THREE.StaticDrawUsage)
    mesh.count = 0
    meshes.push(mesh)
    parent.add(mesh)
  }

  // 接触阴影：尺寸必须远小于株距，否则密集叠加会把整片田压黑
  const shGeo = new THREE.PlaneGeometry(1, 1)
  shGeo.rotateX(-Math.PI / 2)
  const shadow = new THREE.InstancedMesh(
    shGeo,
    new THREE.MeshBasicMaterial({
      map: makeSoftShadowTexture(THREE),
      transparent: true,
      depthWrite: false,
      opacity: 0.42
    }),
    Math.max(1, clumps.length)
  )
  shadow.frustumCulled = false
  const sd = new THREE.Object3D()
  clumps.forEach((c, i) => {
    sd.position.set(c.x, 0.018, c.z)
    const sc = 0.52 * c.s
    sd.scale.set(sc, 1, sc)
    sd.updateMatrix()
    shadow.setMatrixAt(i, sd.matrix)
  })
  shadow.instanceMatrix.needsUpdate = true
  shadow.renderOrder = 2
  parent.add(shadow)

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
      mesh.count = list.length
      for (let k = 0; k < list.length; k++) {
        const c = clumps[list[k]]
        dummy.position.set(c.x, -0.02, c.z)
        dummy.rotation.set(0, c.rot, 0)
        const sc = c.s * (0.94 + s * 0.014)
        // 一半实例水平镜像，削弱"同一株重复"的观感
        const flip = c.seed > 0.5 ? -1 : 1
        dummy.scale.set(sc * flip, sc, sc)
        dummy.updateMatrix()
        mesh.setMatrixAt(k, dummy.matrix)

        col.copy(tint)
        if (winter) {
          col.lerp(winterTint, 0.45)
        } else {
          col.multiplyScalar(0.86 + c.seed * 0.10)
          col.multiply(s === 5 ? ripeWarm : warm)
        }
        mesh.setColorAt(k, col)
      }
      mesh.instanceMatrix.needsUpdate = true
      if (mesh.instanceColor) mesh.instanceColor.needsUpdate = true
    }
  }

  function dispose() {
    meshes.forEach((m) => {
      m.geometry.dispose?.()
      m.material.dispose?.()
      parent.remove(m)
    })
    shadow.geometry.dispose?.()
    shadow.material.dispose?.()
    parent.remove(shadow)
  }

  return {
    clumps,
    meshes,
    update,
    dispose,
    setShadowVisible(v) { shadow.visible = v },
    paddy: { w: PADDY_W, d: PADDY_D }
  }
}
