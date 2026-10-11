import request from '../utils/request'

// GET /api/releases — 更新日志列表(最新在前)
export const getReleases = () =>
  request({ url: '/releases', method: 'get' })

// GET /api/releases/latest — 最新公告(新版本弹窗用)
export const getLatestRelease = () =>
  request({ url: '/releases/latest', method: 'get' })

// POST /api/releases — 发布新版本公告(管理员)
export const createRelease = (data) =>
  request({ url: '/releases', method: 'post', data })

// PUT /api/releases/{id} — 编辑公告(管理员)
export const updateRelease = (id, data) =>
  request({ url: `/releases/${id}`, method: 'put', data })

// DELETE /api/releases/{id} — 删除公告(管理员)
export const deleteRelease = (id) =>
  request({ url: `/releases/${id}`, method: 'delete' })
