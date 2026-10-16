import request from '../utils/request'

// ==================== 目录树 ====================

/** 目录树 + 根目录文件 + 用量统计 */
export const getDocTree = () => request({ url: '/my-docs/tree', method: 'get' })

/** 新建目录 {name, parentId} */
export const createDocDir = (data) => request({ url: '/my-docs/directories', method: 'post', data })

/** 重命名目录 {name} */
export const renameDocDir = (id, name) => request({ url: `/my-docs/directories/${id}`, method: 'put', data: { name } })

/** 删除目录(非空会被拒绝) */
export const deleteDocDir = (id) => request({ url: `/my-docs/directories/${id}`, method: 'delete' })

/** 目录换父目录(parentId 为 null 表示移到顶层) */
export const moveDocDir = (id, parentId) =>
  request({ url: `/my-docs/directories/${id}/parent`, method: 'put', data: { parentId } })

// ==================== 文件 ====================

/** 某目录下的文件列表(directoryId 为空表示根目录) */
export const listDocFiles = (directoryId) =>
  request({ url: '/my-docs/files', method: 'get', params: { directoryId } })

/** 新建空白文档 {name, docType: 'word'|'excel'|'ppt', directoryId} */
export const createDocFile = (data) => request({ url: '/my-docs/files', method: 'post', data })

/** 导入 Word/Excel/PPT(FormData: file + directoryId) */
export const importDocFile = (formData) =>
  request({ url: '/my-docs/files/import', method: 'post', data: formData, timeout: 60000 })

/** 重命名文档 */
export const renameDocFile = (id, name) =>
  request({ url: `/my-docs/files/${id}`, method: 'put', data: { name } })

/** 移动文档到其他目录 */
export const moveDocFile = (id, directoryId) =>
  request({ url: `/my-docs/files/${id}/move`, method: 'put', data: { directoryId } })

/** 复制文档 */
export const copyDocFile = (id, directoryId) =>
  request({ url: `/my-docs/files/${id}/copy`, method: 'post', data: { directoryId } })

/** 删除文档 */
export const deleteDocFile = (id) => request({ url: `/my-docs/files/${id}`, method: 'delete' })

/** 下载导出(原格式二进制) */
export const downloadDocFile = (id) =>
  request({ url: `/my-docs/files/${id}/download`, method: 'get', responseType: 'blob', silent: true })

// ==================== 在线编辑 ====================

/** 读取编辑器模型 */
export const getDocModel = (id) => request({ url: `/my-docs/files/${id}/model`, method: 'get' })

/** 保存编辑器模型 */
export const saveDocModel = (id, model) =>
  request({ url: `/my-docs/files/${id}/model`, method: 'put', data: model, timeout: 60000 })

// ==================== 历史版本 ====================

export const getDocVersions = (id) => request({ url: `/my-docs/files/${id}/versions`, method: 'get' })

export const downloadDocVersion = (id, name) =>
  request({
    url: `/my-docs/files/${id}/versions/${encodeURIComponent(name)}/download`,
    method: 'get',
    responseType: 'blob',
    silent: true
  })

export const restoreDocVersion = (id, name) =>
  request({ url: `/my-docs/files/${id}/versions/${encodeURIComponent(name)}/restore`, method: 'post' })

// ==================== OnlyOffice 集成 ====================

/** 拿 OnlyOffice 编辑器入口信息(URL + access token) */
export const fetchEditorInfo = (id) => request({ url: `/my-docs/oo/${id}/info`, method: 'get' })

// ==================== 在线预览 ====================

/**
 * 在线预览图片 / PDF / Markdown / 文本 / HTML 等(走 blob URL, 配合 FilePreview 组件用)。
 * Office 三件套走 OnlyOffice, 不走此端点。
 */
export const previewDocFile = (id) =>
  request({
    url: `/my-docs/files/${id}/preview`,
    method: 'get',
    responseType: 'blob',
    silent: true
  })

// ==================== 系统设置 / 配额 ====================

export const getPublicSettings = () => request({ url: '/system-settings', method: 'get' })
export const listAllSettings = () => request({ url: '/system-settings/all', method: 'get' })
export const updateSetting = (key, value) =>
  request({ url: `/system-settings/${encodeURIComponent(key)}`, method: 'put', data: { value } })
