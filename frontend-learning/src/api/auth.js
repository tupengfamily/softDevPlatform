import request from '../utils/request'

// POST /api/auth/login
export const login = (data) =>
  request({ url: '/auth/login', method: 'post', data })

// POST /api/auth/register
export const register = (data) =>
  request({ url: '/auth/register', method: 'post', data })

// GET /api/auth/me
export const me = () =>
  request({ url: '/auth/me', method: 'get' })

// POST /api/auth/logout
export const logout = () =>
  request({ url: '/auth/logout', method: 'post' })
// PUT /api/auth/profile — 本人更新个人信息(昵称/头像/个性签名/邮箱/年龄/地址)
export const updateProfile = (data) =>
  request({ url: '/auth/profile', method: 'put', data })

// POST /api/auth/avatar — 上传头像图片(multipart, 字段名 file),返回新头像 URL
export const uploadAvatar = (file) => {
  const fd = new FormData()
  fd.append('file', file)
  return request({ url: '/auth/avatar', method: 'post', data: fd })
}
