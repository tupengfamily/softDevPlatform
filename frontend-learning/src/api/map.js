import request from '../utils/request'

// ==================== 通用地图能力 · 天地图（Tianditu）后端代理 ====================

/**
 * 架构：<b>后端代理</b>，浏览器永远拿不到天地图 key。
 *
 * <p>天地图的 key 分「浏览器端」和「服务端」两种：
 * 浏览器端 key 直接在前端调会暴露在 DevTools 里；服务端 key 写进前端 JS 等于公开。
 * 所以本项目由后端持 key 代调天地图，浏览器只跟自己的后端说话。
 *
 * <p><b>响应约定（务必注意）</b>：后端统一包装成 {@code {code, message, data}}，
 * 响应拦截器返回的是<b>整个包装对象</b>而不是内层 data，所以消费时要写
 * {@code res.data || {}}（或 {@code (await xxx()).data}）。写错会让所有字段都是 undefined。
 *
 * <p><b>瓦片不走这个模块</b>：瓦片是图片字节，由浏览器 {@code <img>} 直接加载，
 * 拿不到 Authorization 头，所以后端把瓦片端点放进了 JWT 白名单（匿名可访问）。
 */

// ------------------------------------------------------------------
//  图层定义
// ------------------------------------------------------------------

/**
 * 天地图图层白名单（与后端 TianDiTuService.LAYER_WHITELIST 一一对应）。
 * 后端是固定枚举而非配置项 —— 这是 SSRF 防护边界，前端也照抄一份避免拼错。
 */
export const TDT_LAYERS = Object.freeze({
  /** 矢量底图 */
  vec_w: '矢量',
  /** 卫星影像 */
  img_w: '影像',
  /** 地形晕渲 */
  ter_w: '地形',
  /** 中文注记（配合矢量底图） */
  cva_w: '矢量注记',
  /** 中文注记（配合卫星影像） */
  cia_w: '影像注记',
  /** 中文注记（配合地形晕渲） */
  cta_w: '地形注记'
})

/**
 * 瓦片代理地址。
 *
 * <p>不返回完整 URL 而是返回<b>路径</b>：开发态由 vite 代理转发到 8080，
 * 生产态由 nginx 反代到后端，路径形态在两种环境下一致。
 *
 * @param {string} layerType 图层类型（见 TDT_LAYERS）
 * @param {number} z  缩放层级（天地图有效范围 3~18）
 * @param {number} x  瓦片列号
 * @param {number} y  瓦片行号
 * @returns {string} 形如 `/api/map/tianditu/tile/img_w/6/52/23`
 */
export function tdtTilePath(layerType, z, x, y) {
  const layer = TDT_LAYERS[layerType] ? layerType : 'vec_w'
  return `/api/map/tianditu/tile/${layer}/${z}/${x}/${y}`
}

/**
 * 代理层自检：后端是否配了服务端 key、允许哪些图层。
 *
 * <p>用于「后端未配置 key」的兜底提示 —— 前端拿不到 key 本身（也不该拿到），
 * 只能问后端「你准备好了吗」。返回体里<b>不含 key 内容</b>。
 *
 * @returns {Promise<{enabled:boolean, serverKeyConfigured:boolean, allowedLayers:string[]}>}
 */
export const getMapTdtStatus = () =>
  request({ url: '/map/tianditu/status', method: 'get' })

/**
 * 探一次瓦片端点，拿到**后端返回的具体错误原因**。
 *
 * <p><b>为什么需要它</b>：页面上的瓦片是 SVG {@code <image>} 加载的，
 * 加载失败只触发一个 {@code error} 事件，<b>拿不到响应体</b>，
 * 所以前端只知道「失败了」，不知道是「没配 key」「key 类型不对」还是「上游网络不通」。
 * 这个函数用 fetch 单独请求同一路径，读取 JSON 里的 message，
 * 让提示能指到具体原因。
 *
 * <p><b>注意</b>：瓦片端点在 JWT 白名单里（匿名可访问），这里也不带 token。
 *
 * @returns {Promise<{ok:boolean, status:number, message:string}>}
 */
export async function probeMapTdtTile(layerType = 'vec_w', z = 3, x = 4, y = 2) {
  const path = tdtTilePath(layerType, z, x, y)
  try {
    const resp = await fetch(path, { method: 'GET' })
    if (resp.ok) {
      return { ok: true, status: resp.status, message: '' }
    }
    // 后端统一包装 {code, message, data}
    let message = ''
    try {
      const body = await resp.json()
      message = body?.message || body?.msg || ''
    } catch {
      message = ''
    }
    return { ok: false, status: resp.status, message }
  } catch (e) {
    // 连后端都连不上 → 网络层问题
    return { ok: false, status: 0, message: e?.message || '网络请求失败' }
  }
}

// ------------------------------------------------------------------
//  地名搜索
// ------------------------------------------------------------------

/**
 * 天地图地名搜索（v2/search）。
 *
 * <p>返回天地图<b>原始 JSON 结构</b>，按 {@code resultType} 分支：
 * <ul>
 *   <li>{@code 1} → POI 列表，数据在 {@code pois[]}（含 name / lonlat / address / phone）</li>
 *   <li>{@code 2} → 当前范围无结果、按城市统计，数据在 {@code statistics.priorityCitys[]}</li>
 *   <li>{@code 3} → 行政区划，数据在 {@code area}（含 name / adminCode / lonlat / bound），
 *       其中 {@code lonlat} 是 {@code "lng,lat"} 字符串</li>
 * </ul>
 *
 * @param {object}  params
 * @param {string}  params.keyword   搜索关键词（行政区名 / POI 名）
 * @param {number} [params.level=12] 搜索层级：12=行政区划，18=POI 兴趣点
 * @param {string} [params.mapBound] 搜索范围 {@code "minLng,minLat,maxLng,maxLat"}；
 *        不传则后端用全国默认范围 {@code "73.66,3.86,135.05,53.55"}
 * @param {number} [params.start=0]  分页起始
 * @param {number} [params.count=10] 返回条数（后端上限 50）
 * @returns {Promise<{data: object}>} 包装体，原始 JSON 在 data 里
 */
export const searchMapPlaces = (params) =>
  request({
    url: '/map/tianditu/search',
    method: 'get',
    params: {
      keyword: params.keyword,
      level: params.level ?? 12,
      mapBound: params.mapBound,
      start: params.start ?? 0,
      count: params.count ?? 10
    }
  })

// ------------------------------------------------------------------
//  逆地理编码（坐标 → 地址）
// ------------------------------------------------------------------

/**
 * 逆地理编码：坐标 → 结构化地址。
 *
 * <p>返回天地图原始 JSON：
 * {@code result.formatted_address} 是完整地址；
 * {@code result.addressComponent} 含 province / city / county / county_code / town / address / poi。
 *
 * @param {number} lon 经度（度）
 * @param {number} lat 纬度（度）
 * @returns {Promise<{data: object}>} 包装体，原始 JSON 在 data 里
 */
export const reverseGeocode = (lon, lat) =>
  request({
    url: '/map/tianditu/reverse',
    method: 'get',
    params: { lon, lat }
  })

// ------------------------------------------------------------------
//  地址编码（地名 → 坐标）
// ------------------------------------------------------------------

/**
 * 地址编码：地名 → 坐标。
 *
 * <p>搜索接口返回的是「带坐标的候选列表」，本接口是「直接把一个地名解析成唯一坐标」，
 * 适合「玩家输入一个地址 → 直接定位」这种场景。
 *
 * @param {string} keyword 地址关键词
 * @param {number} [level=12] 层级：12=区县及以上；传 0 交给天地图自动判定
 * @returns {Promise<{data: object}>} 包装体，原始 JSON 在 data 里
 */
export const geocodeAddress = (keyword, level = 12) =>
  request({
    url: '/map/tianditu/geocode',
    method: 'get',
    params: { keyword, level }
  })
