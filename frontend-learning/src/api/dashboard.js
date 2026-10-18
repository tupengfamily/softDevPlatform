import request from '../utils/request'

// 一次拉全:summary + 4 个 7 日趋势 + 状态分布 + 最近反馈 + 最近对局
// 后端 /api/dashboard/all (60s 缓存) — 高频 admin 访问页面
export const getDashboardAll = () =>
  request({ url: '/dashboard/all', method: 'get' })

// 单接口(细粒度缓存, 备用)
export const getSummary = () =>
  request({ url: '/dashboard/summary', method: 'get' })
export const getRegistrations = () =>
  request({ url: '/dashboard/registrations', method: 'get' })
export const getOperations = () =>
  request({ url: '/dashboard/operations', method: 'get' })
export const getStatusDistribution = () =>
  request({ url: '/dashboard/status-distribution', method: 'get' })
export const getGamePlays = () =>
  request({ url: '/dashboard/game-plays', method: 'get' })
export const getLearning = () =>
  request({ url: '/dashboard/learning', method: 'get' })
export const getFeedbackTrend = () =>
  request({ url: '/dashboard/feedback-trend', method: 'get' })
export const getRecentFeedback = () =>
  request({ url: '/dashboard/recent-feedback', method: 'get' })
export const getRecentGames = () =>
  request({ url: '/dashboard/recent-games', method: 'get' })