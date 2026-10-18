import request from '../utils/request'

// ==================== 五子棋 · 联机对战 ====================

/** 创建房间 */
export const createGomokuRoom = (body) =>
  request({ url: '/games/gomoku/rooms', method: 'post', data: body || {} })

/** 加入房间(通过房间码) */
export const joinGomokuRoom = (code) =>
  request({ url: `/games/gomoku/rooms/${code}/join`, method: 'post' })

/** 离开房间 */
export const leaveGomokuRoom = (code) =>
  request({ url: `/games/gomoku/rooms/${code}/leave`, method: 'delete', silent: true })

/** 落子 */
export const placeGomokuMove = (code, r, c) =>
  request({ url: `/games/gomoku/rooms/${code}/place`, method: 'post', data: { r, c } })

/**
 * 查询房间状态(轮询入口)
 * sinceVersion 是上次拿到的 moveCount, 服务端无变化时不返回 board
 */
export const getGomokuState = (code, sinceVersion) =>
  request({
    url: `/games/gomoku/rooms/${code}/state`,
    method: 'get',
    params: sinceVersion != null ? { sinceVersion } : undefined
  })

/** 列出等待中的房间 */
export const listGomokuRooms = () =>
  request({ url: '/games/gomoku/rooms', method: 'get' })