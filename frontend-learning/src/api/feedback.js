import request from '../utils/request'

// POST /api/feedback — 提交反馈(评分 / bug)
export const submitFeedback = (data) =>
  request({ url: '/feedback', method: 'post', data })

// GET /api/feedback/mine — 我的反馈
export const getMyFeedback = () =>
  request({ url: '/feedback/mine', method: 'get' })

// GET /api/feedback/rating-summary — 评分统计(平均分 + 评分数)
export const getRatingSummary = () =>
  request({ url: '/feedback/rating-summary', method: 'get' })

// GET /api/feedback/page — 管理端分页查询
export const getFeedbackPage = (params) =>
  request({ url: '/feedback/page', method: 'get', params })

// PUT /api/feedback/{id} — 处理反馈(状态 + 备注, 管理员)
export const handleFeedback = (id, data) =>
  request({ url: `/feedback/${id}`, method: 'put', data })

// DELETE /api/feedback/{id} — 删除反馈(管理员)
export const deleteFeedback = (id) =>
  request({ url: `/feedback/${id}`, method: 'delete' })
