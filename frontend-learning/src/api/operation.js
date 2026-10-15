import request from '../utils/request'

/** 站点总览: 今日 / 本周 / 本月 UV/PV/时长/错误数 */
export const getOverview = () => request({ url: '/admin/operation/overview', method: 'get' })

/** 页面排行: 默认最近 7 天, limit 默认 50 */
export const getPages = (params = {}) => request({ url: '/admin/operation/pages', method: 'get', params })

/** 用户旅程样本: 默认 20 个 session */
export const getJourney = (sessionLimit = 20) =>
  request({ url: '/admin/operation/journey', method: 'get', params: { sessionLimit } })

/** 性能面板: 默认最近 7 天 */
export const getPerf = (params = {}) => request({ url: '/admin/operation/perf', method: 'get', params })

/** 错误日志(代码运行报错): 默认最近 7 天 */
export const getErrors = (params = {}) => request({ url: '/admin/operation/errors', method: 'get', params })

/** 错误日志(接口报错): 来自 t_operation_log, 可按模块筛 */
export const getApiErrors = (params = {}) => request({ url: '/admin/operation/errors/api', method: 'get', params })

/** OS / Browser / 设备分布 */
export const getClientMetrics = (params = {}) => request({ url: '/admin/operation/client-metrics', method: 'get', params })

/** 单页面详情: 趋势 + 访问者 + 错误 */
export const getPageDetail = (params = {}) => request({ url: '/admin/operation/page-detail', method: 'get', params })

/** 活跃用户排行 */
export const getActiveUsers = (params = {}) => request({ url: '/admin/operation/users', method: 'get', params })

/** 单用户旅程: 支持 from / to 时间范围筛选 */
export const getUserJourney = (userId, params = {}) =>
  request({ url: `/admin/operation/user-journey/${userId}`, method: 'get', params })