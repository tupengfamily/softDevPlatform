import request from '../utils/request'

// GET /api/roles
export const listRoles = () =>
  request({ url: '/roles', method: 'get' })

// POST /api/roles
export const createRole = (data) =>
  request({ url: '/roles', method: 'post', data })

// PUT /api/roles/{id}
export const updateRole = (id, data) =>
  request({ url: `/roles/${id}`, method: 'put', data })

// DELETE /api/roles/{id}
export const deleteRole = (id) =>
  request({ url: `/roles/${id}`, method: 'delete' })

// PUT /api/roles/{id}/permissions
export const assignPermissions = (id, permissionIds) =>
  request({ url: `/roles/${id}/permissions`, method: 'put', data: { permissionIds } })