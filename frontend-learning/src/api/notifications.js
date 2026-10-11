import request from '../utils/request'

// 消息中心 API(头部铃铛)
// 类型 type: SYSTEM=系统通知 / REPLY=回复通知 / TASK=任务通知,不传=全部

// GET /api/notifications?type=&page=&size= — 消息分页(最新在前)
export const pageNotifications = ({ type, page = 1, size = 10 } = {}) =>
  request({ url: '/notifications', method: 'get', params: { type: type || undefined, page, size } })

// GET /api/notifications/recent — 最近 8 条(头部面板)
export const recentNotifications = () =>
  request({ url: '/notifications/recent', method: 'get' })

// GET /api/notifications/unread-count — 未读数
export const unreadCount = () =>
  request({ url: '/notifications/unread-count', method: 'get' })

// PUT /api/notifications/{id}/read — 单条已读
export const markRead = (id) =>
  request({ url: `/notifications/${id}/read`, method: 'put' })

// PUT /api/notifications/read-all — 全部已读
export const markAllRead = () =>
  request({ url: '/notifications/read-all', method: 'put' })

// POST /api/notifications/system — 发布系统通知(管理员,推送全体用户)
export const sendSystemNotice = (data) =>
  request({ url: '/notifications/system', method: 'post', data })
