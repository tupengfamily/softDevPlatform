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
/** 目录移动到新的父目录(拖动跨层级): parentId 为 null 表示移到顶层 */
export const moveWikiDirParent = (id, parentId) => request({ url: `/wiki/directories/${id}/parent`, method: 'put', data: { parentId } })

export const createWikiArticle = (data) => request({ url: '/wiki/articles', method: 'post', data })
/** 导入文档为文章(Markdown/Word/Excel): FormData = file + directoryId */
export const importWikiArticle = (formData) => request({ url: '/wiki/articles/import', method: 'post', data: formData, timeout: 60000 })
export const updateWikiArticle = (id, data) => request({ url: `/wiki/articles/${id}`, method: 'put', data })
export const deleteWikiArticle = (id) => request({ url: `/wiki/articles/${id}`, method: 'delete' })
export const moveWikiArticle = (id, position) => request({ url: `/wiki/articles/${id}/move`, method: 'put', data: { position } })
export const shareWikiArticle = (id, requireLogin = false) =>
  request({ url: `/wiki/articles/${id}/share`, method: 'post', data: { requireLogin } })
export const unshareWikiArticle = (id) => request({ url: `/wiki/articles/${id}/share`, method: 'delete' })

// 分享页公开只读(游客)
export const getSharedArticle = (token) => request({ url: `/public/wiki/${token}`, method: 'get' })

// ==================== Wiki 协作申请 / 审批 ====================

/** 发起协作申请(resourceType: DIR/ART, permission: READ/EDIT) */
export const applyCollaboration = (data) => request({ url: '/wiki/collab/apply', method: 'post', data })

/** 申请人撤销自己的 PENDING 申请 */
export const cancelCollaboration = (id) => request({ url: `/wiki/collab/${id}/cancel`, method: 'post' })

/** 资源所有者审批通过(comment 可选) */
export const approveCollaboration = (id, comment) =>
  request({ url: `/wiki/collab/${id}/approve`, method: 'post', data: { comment } })

/** 资源所有者审批拒绝 */
export const rejectCollaboration = (id, comment) =>
  request({ url: `/wiki/collab/${id}/reject`, method: 'post', data: { comment } })

/** 资源所有者的审批中心: 待办 + 历史 */
export const getCollabInbox = () => request({ url: '/wiki/collab/inbox', method: 'get' })

/** 我提交的申请历史 */
export const getMyCollaborations = () => request({ url: '/wiki/collab/mine', method: 'get' })

/** 共享给我的资源(树中显示) */
export const getSharedToMe = () => request({ url: '/wiki/collab/shared-to-me', method: 'get' })

/** 资源上的协作者列表(所有者管理) */
export const getResourceCollaborators = (resourceType, resourceId) =>
  request({ url: '/wiki/collab/resource', method: 'get', params: { resourceType, resourceId } })

// ==================== 个人数据导入导出 ====================

export const exportUserData = () => request({ url: '/user-data/export', method: 'post' })
export const importUserData = (data) => request({ url: '/user-data/import', method: 'post', data })

// 导入导出记录
export const getDataRecords = () => request({ url: '/user-data/records', method: 'get' })
export const getDataRecord = (id) => request({ url: `/user-data/records/${id}`, method: 'get' })
