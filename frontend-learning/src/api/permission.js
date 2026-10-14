import request from '../utils/request'

// GET /api/permissions
export const listPermissions = () =>
  request({ url: '/permissions', method: 'get' })