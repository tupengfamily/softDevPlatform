import request from '../utils/request'

// GET /api/users/page
export const pageUsers = (params) =>
  request({ url: '/users/page', method: 'get', params })

// POST /api/users
export const createUser = (data) =>
  request({ url: '/users', method: 'post', data })

// PUT /api/users/{id}
export const updateUser = (id, data) =>
  request({ url: `/users/${id}`, method: 'put', data })

// PUT /api/users/{id}/status
export const updateStatus = (id, status) =>
  request({ url: `/users/${id}/status`, method: 'put', data: { status } })

// PUT /api/users/{id}/password
export const changePassword = (id, data) =>
  request({ url: `/users/${id}/password`, method: 'put', data })

// PUT /api/users/{id}/roles
export const assignRoles = (id, roleCodes) =>
  request({ url: `/users/${id}/roles`, method: 'put', data: { roleCodes } })

// DELETE /api/users/{id}
export const deleteUser = (id) =>
  request({ url: `/users/${id}`, method: 'delete' })