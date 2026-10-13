import request from '../utils/request'

// ==================== 个人收藏 ====================

export const getFavorites = () => request({ url: '/favorites', method: 'get' })
export const createFavorite = (data) => request({ url: '/favorites', method: 'post', data })
export const updateFavorite = (id, data) => request({ url: `/favorites/${id}`, method: 'put', data })
export const deleteFavorite = (id) => request({ url: `/favorites/${id}`, method: 'delete' })
export const moveFavorite = (id, direction) => request({ url: `/favorites/${id}/move`, method: 'put', data: { direction } })

// ==================== 个人 Wiki ====================

export const getWikiTree = () => request({ url: '/wiki/tree', method: 'get' })
export const createWikiDir = (data) => request({ url: '/wiki/directories', method: 'post', data })
export const updateWikiDir = (id, data) => request({ url: `/wiki/directories/${id}`, method: 'put', data })
export const deleteWikiDir = (id) => request({ url: `/wiki/directories/${id}`, method: 'delete' })
export const moveWikiDir = (id, position) => request({ url: `/wiki/directories/${id}/move`, method: 'put', data: { position } })

export const createWikiArticle = (data) => request({ url: '/wiki/articles', method: 'post', data })
export const updateWikiArticle = (id, data) => request({ url: `/wiki/articles/${id}`, method: 'put', data })
export const deleteWikiArticle = (id) => request({ url: `/wiki/articles/${id}`, method: 'delete' })
export const moveWikiArticle = (id, position) => request({ url: `/wiki/articles/${id}/move`, method: 'put', data: { position } })
export const shareWikiArticle = (id) => request({ url: `/wiki/articles/${id}/share`, method: 'post' })
export const unshareWikiArticle = (id) => request({ url: `/wiki/articles/${id}/share`, method: 'delete' })

// 分享页公开只读(游客)
export const getSharedArticle = (token) => request({ url: `/public/wiki/${token}`, method: 'get' })

// ==================== 个人数据导入导出 ====================

export const exportUserData = () => request({ url: '/user-data/export', method: 'post' })
export const importUserData = (data) => request({ url: '/user-data/import', method: 'post', data })

// 导入导出记录
export const getDataRecords = () => request({ url: '/user-data/records', method: 'get' })
export const getDataRecord = (id) => request({ url: `/user-data/records/${id}`, method: 'get' })
