/**
 * 场景资产管线：加载、进度、失败降级、缓存与释放。
 *
 * 设计取舍：
 * - **串行加载**：11 张图并行会挤占浏览器连接，进度条也会跳跃；串行反而更稳、进度更真实。
 * - **单张失败不整体失败**：坏一张图只让那一块退化成纯色占位，而不是整页掉到 SVG 兜底视图。
 *   （上一版是 Promise.all，一张 404 就整场白屏，这才是不该有的耦合。）
 * - **版本号带在 URL 上**：换素材后必须递增 TEX_VER，否则浏览器缓存会一直吃旧图。
 */

import { TEX_BASE, TEX_VER } from './config.js'

/** 兜底颜色：按资源名猜一个合理底色，退化时不至于出现黑块 */
function fallbackColor(file) {
  if (file.includes('soil') || file.includes('field')) return '#8a7550'
  if (file.includes('grass')) return '#6f8a4e'
  if (file.includes('sky')) return '#9dc4e4'
  if (file.includes('tree')) return '#3f5f33'
  // ⚠️ **法线贴图的兜底必须是中性灰（128,128,128）**：
  //   法线贴图被解码成 (rgb*2-1)，中性灰 = (0,0,0) = 垂直于切平面 = 无凹凸。
  //   如果给绿色（植物色），会被当成"整片朝一个方向倾斜"→ 叶片像塑料斜面。
  if (file.includes('_n.png')) return '#808080'
  if (file.includes('plant')) return '#5d8a3a'
  return '#808080'
}

function makePlaceholder(THREE, file) {
  const c = document.createElement('canvas')
  c.width = 4
  c.height = 4
  const g = c.getContext('2d')
  g.fillStyle = fallbackColor(file)
  g.fillRect(0, 0, 4, 4)
  const t = new THREE.CanvasTexture(c)
  t.colorSpace = THREE.SRGBColorSpace
  t.userData.fallback = true
  return t
}

/**
 * @param {object} THREE three 命名空间
 * @param {object} renderer 用于读 maxAnisotropy
 */
export function createAssetPipeline(THREE, renderer) {
  const loader = new THREE.TextureLoader()
  const cache = new Map()
  const maxAniso = (() => {
    try {
      return Math.min(16, renderer.capabilities.getMaxAnisotropy())
    } catch (_) {
      return 4
    }
  })()

  const urlOf = (file) => `${TEX_BASE}${file}?v=${TEX_VER}`

  function configure(tex, { srgb, mirror }) {
    if (srgb) tex.colorSpace = THREE.SRGBColorSpace
    tex.wrapS = tex.wrapT = mirror ? THREE.MirroredRepeatWrapping : THREE.ClampToEdgeWrapping
    tex.anisotropy = maxAniso
    // ⚠️ **必须显式开启 mipmap**（2026-10-24 画质升级）。
    //
    //   作物贴图现在 768 高，一块田有 1200+ 丛，同一张贴图被复用上千次。
    //   远处那些只有几像素高的植株，如果**没有 mipmap**，
    //   采样会直接在 768 高的图上取点 → 远处出现严重的**摩尔纹闪烁**，
    //   相机一动就"沙沙"响，是最典型的"贴图没做 LOD"症状。
    //
    //   mipmap + 各向异性过滤 = 远处自动降采样到合适分辨率，
    //   近处仍保持 768 的清晰度 —— 这正是"精细"二字的另一半含义。
    tex.generateMipmaps = true
    tex.minFilter = THREE.LinearMipmapLinearFilter
    tex.magFilter = THREE.LinearFilter
    tex.needsUpdate = true
    return tex
  }

  function loadOnce(file, opts) {
    const key = `${file}|${opts.srgb ? 's' : 'l'}|${opts.mirror ? 'm' : ''}`
    if (cache.has(key)) return Promise.resolve(cache.get(key))
    const p = loader.loadAsync(urlOf(file)).then((tex) => {
      const t = configure(tex, opts)
      cache.set(key, t)
      return t
    }).catch((err) => {
      cache.delete(key) // 别把 rejected Promise 留在缓存里，否则重试永远失败
      throw err
    })
    cache.set(key, p)
    return p
  }

  async function load(file, opts = {}) {
    const o = { srgb: opts.srgb !== false, mirror: !!opts.mirror }
    try {
      return await loadOnce(file, o)
    } catch (e) {
      // 重试一次（偶发网络抖动）
      try {
        return await loadOnce(file, o)
      } catch (e2) {
        return makePlaceholder(THREE, file)
      }
    }
  }

  /**
   * 批量加载。
   *
   * ⚠️ 2026-10-24：新增**降级链** —— 作物专属贴图（`plant_<cropId>_*.png`）
   * 还没全部裁好时，用 `spec.fallbackFile`（通用族图）兜底。
   * 没有降级链时，一张 404 就会退成纯色方块（占位图），田里看不到作物；
   * 有了降级链，最坏情况是「西瓜长得像黄瓜」，而不是「田里是两块方块」。
   *
   * @param {Array<{key:string,file:string,mirror?:boolean,srgb?:boolean,fallbackFile?:string}>} specs
   * @param {(done:number,total:number,key:string)=>void} [onProgress]
   * @returns {Promise<{assets:object, failed:string[], degraded:string[], usedFiles:object}>}
   *          failed    = 最终仍是占位图（彻底拿不到）
   *          degraded  = 用了降级贴图（专属图缺失，但有族图兜底）
   *          usedFiles = key → 实际用上的文件名（画布宽高比要按它查）
   */
  async function loadAll(specs, onProgress) {
    const assets = {}
    const failed = []
    const degraded = []
    const usedFiles = {}
    let done = 0
    for (const spec of specs) {
      let tex = await load(spec.file, { srgb: spec.srgb, mirror: spec.mirror })
      let usedFile = spec.file
      // 主图拿不到 → 试降级图
      if (tex.userData && tex.userData.fallback && spec.fallbackFile) {
        const fb = await load(spec.fallbackFile, { srgb: spec.srgb, mirror: spec.mirror })
        if (!(fb.userData && fb.userData.fallback)) {
          tex = fb
          usedFile = spec.fallbackFile
          degraded.push(spec.file)
        }
      }
      if (tex.userData && tex.userData.fallback) failed.push(spec.file)
      assets[spec.key] = tex
      usedFiles[spec.key] = usedFile
      done++
      if (onProgress) onProgress(done, specs.length, spec.key)
    }
    return { assets, failed, degraded, usedFiles }
  }

  function dispose() {
    cache.forEach((v) => {
      if (v && typeof v.then !== 'function' && v.dispose) v.dispose()
    })
    cache.clear()
  }

  return { load, loadAll, dispose, urlOf }
}
