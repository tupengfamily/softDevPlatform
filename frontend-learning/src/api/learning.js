import request from '../utils/request'

// GET /api/learning/curriculum — 课程树(阶段 -> 章节 -> 课时)
export const getCurriculum = () =>
  request({ url: '/learning/curriculum', method: 'get' })

// GET /api/learning/progress — 学习进度总览
export const getProgress = () =>
  request({ url: '/learning/progress', method: 'get' })

// GET /api/learning/board — 学习看板(管理员=全员 / 普通用户=仅自己)
export const getBoard = () =>
  request({ url: '/learning/board', method: 'get' })

// GET /api/learning/lessons/{id} — 课时详情
export const getLesson = (id) =>
  request({ url: `/learning/lessons/${id}`, method: 'get' })

// POST /api/learning/lessons/{id}/learn — 标记已学
export const markLearned = (id) =>
  request({ url: `/learning/lessons/${id}/learn`, method: 'post' })

// DELETE /api/learning/lessons/{id}/learn — 取消已学
export const unmarkLearned = (id) =>
  request({ url: `/learning/lessons/${id}/learn`, method: 'delete' })
