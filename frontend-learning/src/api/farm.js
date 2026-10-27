import request from '../utils/request'

// ==================== 娱乐 · 稼穑（种植模拟） ====================

/**
 * 多地块约定：
 * - 一个用户最多 100 块田，用 slot（0..99）标识；
 * - 每块地独立作物 / 播期 / 农事，操作类接口都带 ?slot=N；
 * - 不传 slot 时后端按 0 处理（兼容旧调用）。
 */

/** 地块总览（按槽位升序，含每块地的作物与物候） */
export const listFarmPlots = () =>
  request({ url: '/games/farm/plots', method: 'get' })

/** 指定地块的完整状态：物候 / 天气 / 产量预览 / 今日变化 / 历史成绩 */
export const getFarmState = (slot = 0) =>
  request({ url: '/games/farm/state', method: 'get', params: { slot } })

/**
 * 演示模式：手动推进天数（1–60）
 * 正式环境（farm.demo-mode=false）会返回 400 —— 1:1 真实时间下不允许推进
 */
export const advanceFarm = (slot = 0, days = 1) =>
  request({ url: `/games/farm/advance?slot=${slot}&days=${days}`, method: 'post' })

/** 收割：产量记入游戏成绩，地块进入下一季 */
export const harvestFarm = (slot = 0) =>
  request({ url: `/games/farm/harvest?slot=${slot}`, method: 'post' })

/** 提前重开一季（放弃当前作物，不记成绩） */
export const restartFarm = (slot = 0) =>
  request({ url: `/games/farm/restart?slot=${slot}`, method: 'post' })

/**
 * 结掉一季失败（绝收 / 苗期枯死），2026-10-22。
 *
 * 苗期枯死的地块 DVS 永远停在低位，收割会被「没到黄熟期」拒绝。
 * 这一季恰恰最该进档案（枯死原因、出苗率、沉没成本），所以单开一个入口：
 * 产量记 0、状态 FAILED、不记游戏成绩（失败不占榜）。
 */
export const closeFailedFarmSeason = (slot = 0) =>
  request({ url: `/games/farm/close-failed?slot=${slot}`, method: 'post' })

/**
 * 季节档案：每块地每一季的种植与收获记录（2026-10-22）。
 *
 * @param {number} [farmId] 农场 id；不传则返回该用户全部农场的记录
 */
export const getFarmSeasons = (farmId) =>
  request({
    url: '/games/farm/seasons',
    method: 'get',
    params: farmId == null ? undefined : { farmId }
  })

// ==================== 测试用 mock 天气 ====================
// 天气按「用户 + 农业区」，不按地块：一次注入对所有田生效。

/**
 * 注入一段模拟天气（演示/测试模式专用）。区间内每天强制使用同一组气象值；
 * 区间外回落到演示回放。多次调用叠加。
 */
export const mockFarmWeather = (body) =>
  request({ url: '/games/farm/weather/mock', method: 'post', data: body })

/** 列出当前生效的 mock 段。 */
export const listFarmMockWeather = () =>
  request({ url: '/games/farm/weather/mocks', method: 'get' })

/** 清空当前用户所有 mock 段（不影响其他用户）。 */
export const resetFarmMockWeather = () =>
  request({ url: '/games/farm/weather/reset', method: 'post' })

// ==================== 农事操作 ====================

/**
 * 玩家做一次农事。type ∈ IRRIGATE | DRAIN | FERTILIZE | PESTICIDE | UNCOVER。
 * 后端会按物候窗口校验, 不在窗口返回 400。
 */
export const performFarmAction = (slot, type, note) =>
  request({ url: `/games/farm/action?slot=${slot}`, method: 'post', data: { type, note } })

/** 列出该地块做过的农事（最近 50 条） */
export const listFarmActions = (slot = 0) =>
  request({ url: '/games/farm/actions', method: 'get', params: { slot } })

// ==================== 建田 / 适区（S2 / S23） ====================

/** 作物库 */
export const listFarmCrops = () =>
  request({ url: '/games/farm/crops', method: 'get' })

/** 可选农业区 + 各区在推荐播期下对该作物的评级摘要 */
export const listFarmZones = (crop = 'rice_mid') =>
  request({ url: '/games/farm/zones', method: 'get', params: { crop } })

/** 指定区 + 播期 + 作物的完整适区报告（播期改动时防抖调用） */
export const getFarmSuitability = (zone, sowDate, crop = 'rice_mid') =>
  request({ url: '/games/farm/suitability', method: 'get', params: { zone, sowDate, crop } })

/**
 * 种植风险提示（2026-10-22）。
 *
 * 把「选错区」与「选错时机」分开报：
 * - AREA：当前区与该作物最佳区的评级差
 * - TIMING：当前播期与该区推荐播期的偏离
 * - STRUCTURE：结构性硬闸（pH / 盐碱 / 土层 / 水源）
 *
 * 每项带针对性说明与可操作建议。**只提示不拦截**（S23 §2.1）。
 */
export const getFarmPlantingRisk = (zone, sowDate, crop = 'rice_mid') =>
  request({ url: '/games/farm/planting-risk', method: 'get', params: { zone, sowDate, crop } })

/**
 * 建田 / 换地：确定区域与播期。
 * @param {object} body  { zoneId, sowDate?, fieldName?, cropId? }
 * @param {number} slot  -1 表示新建一块地（自动分配空槽位）；≥0 表示在该槽位上重建
 */
export const createFarmField = (body, slot = -1) =>
  request({ url: `/games/farm/field?slot=${slot}`, method: 'post', data: body })

// ==================== 农资（S12） ====================

/** 施用农资到指定地块。itemId 来自 /state 的 inputs 列表；dose 省略则用推荐量 */
export const applyFarmInput = (slot, itemId, dose) =>
  request({ url: `/games/farm/input?slot=${slot}`, method: 'post', data: { itemId, dose } })

// ==================== 中国地图选点 · 农场（区县 → 农场 → 地块） ====================

/**
 * 农场层级约定：
 * - 区县（adcode，6 位）是地图选点的最小单位，决定农业气候区；
 * - 一个用户可以在地图上建多个农场，同一区县只允许一个（后端幂等）；
 * - 地块通过 farmId 归属农场，同一农场内每块地仍只种一种作物。
 */

/** 全国省级聚合：区县数量 + 我的农场数量 */
export const getFarmMapProvinces = () =>
  request({ url: '/games/farm/map/provinces', method: 'get' })

/** 某省全部区县：主栽作物、气候区、我的农场标记 */
export const getFarmMapDistricts = (province) =>
  request({ url: '/games/farm/map/districts', method: 'get', params: { province } })

/** 单区县详情：气候、四作物评级与当前物候、我的农场 */
export const getFarmMapDistrict = (adcode) =>
  request({ url: '/games/farm/map/district', method: 'get', params: { adcode } })

/** 区县名称搜索（地图输入框） */
export const searchFarmDistricts = (q) =>
  request({ url: '/games/farm/map/search', method: 'get', params: { q } })

/**
 * 作物分布：某省（或某市）下辖区县的播种面积与产量，用于地图着色。
 *
 * @param {number} adcode 省级（370000）或地级市（370100）编码
 * @param {string[]} [crops] 作物 id 列表，不传则返回全部
 */
export const getFarmCropDistribution = (adcode, crops) =>
  request({
    url: '/games/farm/map/crop-distribution',
    method: 'get',
    params: { adcode, crop: crops && crops.length ? crops : undefined }
  })

/** 省级作物分布概览（全国视图按省着色用） */
export const getFarmCropDistributionProvinces = (crops) =>
  request({
    url: '/games/farm/map/crop-distribution/provinces',
    method: 'get',
    params: { crop: crops && crops.length ? crops : undefined }
  })

/** 我的农场列表（含地块） */
export const listFarmSteads = () =>
  request({ url: '/games/farm/farms', method: 'get' })

/**
 * 在地图上选点建农场；重复点击同一区县返回已有农场。
 *
 * @param {string|number} adcode 6 位 adcode
 * @param {string} name 农场名
 * @param {string} [openedOn] 开垦日期 YYYY-MM-DD（不能早于世界日期）。
 *   缺省 = 世界日期（= 今天）。
 */
export const createFarmStead = (adcode, name, openedOn) =>
  request({ url: '/games/farm/farms', method: 'post', data: { adcode, name, openedOn } })

/** 农场详情（含地块与所在区县作物画像） */
export const getFarmStead = (id) =>
  request({ url: `/games/farm/farms/${id}`, method: 'get' })

export const renameFarmStead = (id, name) =>
  request({ url: `/games/farm/farms/${id}/rename`, method: 'post', data: { name } })

export const deleteFarmStead = (id) =>
  request({ url: `/games/farm/farms/${id}`, method: 'delete' })

/** 统一看板：农场分组 → 地块 → 作物状态 */
export const getFarmBoard = () =>
  request({ url: '/games/farm/board', method: 'get' })

// ==================== 仓库（S24：收获物入库 / 变卖 / 变质） ====================

/**
 * 仓库面板：存货清单 + 估值 + 临期提醒。
 *
 * 每个批次含：数量、入库日、已仓储天数、距变质天数、品质分级、
 * 当前单价与总价、地区与供给压力系数。
 */
export const getFarmWarehouse = () =>
  request({ url: '/games/farm/warehouse', method: 'get' })

/**
 * 变卖：把仓库里的货卖掉。
 *
 * @param {object} body  { cropId?, kg? }
 *   cropId 缺省 = 全部卖掉；kg 缺省或 ≤0 = 该作物全部卖掉
 * 后端按「先过期先卖」选择批次。
 */
export const sellFarmWarehouse = (body = {}) =>
  request({ url: '/games/farm/warehouse/sell', method: 'post', data: body })

/**
 * 购买农资入库（S25）。
 *
 * @param {object} body  { itemId, qty, farmId? }
 *   farmId 缺省 = 全局库存
 */
export const buyFarmStock = (body = {}) =>
  request({ url: '/games/farm/warehouse/stocks/buy', method: 'post', data: body })

/**
 * 农资库存视图（S25）：仓库面板的「农资」区。
 *
 * @param {object} params  { farmId?, kind? }
 */
export const getFarmStocks = (params = {}) =>
  request({ url: '/games/farm/warehouse/stocks', method: 'get', params })

// ==================== 商店（2026-10-23 需求 R） ====================

/**
 * 商店面板：买种子、农具、农资、水资源。
 *
 * @param {object} params  { kind? }
 *   kind = SEED / TOOL / FERT / PEST / HERB / WATER，缺省 = 全部
 * 响应含 `balance`（钱包余额）与每件商品的 `owned` / `affordable` / `alreadyOwned`。
 */
export const getFarmShop = (params = {}) =>
  request({ url: '/games/farm/shop', method: 'get', params })

/**
 * 购买商品。
 *
 * @param {object} body  { itemId, qty }
 *   农具固定 qty=1（耐用品，重复买会被后端拒绝）
 */
export const buyFarmShopItem = (body = {}) =>
  request({ url: '/games/farm/shop/buy', method: 'post', data: body })

/**
 * 钱包余额（顶部栏显示）。
 */
export const getFarmWallet = () =>
  request({ url: '/games/farm/shop/wallet', method: 'get' })

// ==================== 成就 / 目标（S26） ====================

/**
 * 成就面板：概览 + 成就列表 + 作物图鉴。
 *
 * 🔴 口径说明（前端展示时必须遵守）：
 * - 产量类成就的进度是**比值**（实收 ÷ 该作物参照产量），不是绝对公斤数 ——
 *   22 种作物产量单位不可比（水稻 600 vs 白菜 5000 kg/亩），用绝对值等于
 *   「只有种水稻的人能得成就」。
 * - 「连季不断茬」是**当前连续**季数，断过茬就回退，不是历史最长段。
 * - 图鉴只认正常收获（HARVESTED），失败/放弃的一季不算收集到。
 */
export const getFarmAchievements = () =>
  request({ url: '/games/farm/achievements', method: 'get' })
