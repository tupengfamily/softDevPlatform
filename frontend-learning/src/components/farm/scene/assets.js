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
   * @param {Array<{key:string,file:string,mirror?:boolean,srgb?:boolean}>} specs
   * @param {(done:number,total:number,key:string)=>void} [onProgress]
   * @returns {Promise<{assets:object, failed:string[]}>}
   */
  async function loadAll(specs, onProgress) {
    const assets = {}
    const failed = []
    let done = 0
    for (const spec of specs) {
      const tex = await load(spec.file, { srgb: spec.srgb, mirror: spec.mirror })
      if (tex.userData && tex.userData.fallback) failed.push(spec.file)
      assets[spec.key] = tex
      done++
      if (onProgress) onProgress(done, specs.length, spec.key)
    }
    return { assets, failed }
  }

  function dispose() {
    cache.forEach((v) => {
      if (v && typeof v.then !== 'function' && v.dispose) v.dispose()
    })
    cache.clear()
  }

  return { load, loadAll, dispose, urlOf }
}
