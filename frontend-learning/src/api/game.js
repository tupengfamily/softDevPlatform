import request from '../utils/request'

// ==================== 娱乐 · 小游戏 ====================

/** 游戏中心总览:所有游戏的「我的数据」(最高分/局数/进行中存档) */
export const getGamesOverview = () =>
  request({ url: '/games/overview', method: 'get' })

/** 我的统计:最高分 + 总局数 + 最近 10 局 + 进行中存档 */
export const getGameStats = (code) =>
  request({ url: `/games/${code}/stats`, method: 'get' })

/** 保存一局结束的得分(服务端会同时清掉进度存档) */
export const saveGameScore = (code, data) =>
  request({ url: `/games/${code}/scores`, method: 'post', data })

/** 读取进行中存档 */
export const getGameProgress = (code) =>
  request({ url: `/games/${code}/progress`, method: 'get' })

/**
 * 保存进行中存档(每次有效移动后防抖调用)
 * silent: 自动保存不该弹错误 toast, 失败交给调用方静默处理(下次移动会重试)
 */
export const saveGameProgress = (code, data) =>
  request({ url: `/games/${code}/progress`, method: 'put', data, silent: true })

/** 清除进度(新开一局) */
export const clearGameProgress = (code) =>
  request({ url: `/games/${code}/progress`, method: 'delete', silent: true })

/** 排行榜(每人最高分) */
export const getLeaderboard = (code, limit = 20) =>
  request({ url: `/games/${code}/leaderboard`, method: 'get', params: { limit } })
