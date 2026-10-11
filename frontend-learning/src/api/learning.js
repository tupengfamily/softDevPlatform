import request from '../utils/request'

// ==================== 课程/进度(支持语言维度) ====================

// GET /api/learning/subjects — 启用的科目(语言)列表
export const getSubjects = () =>
  request({ url: '/learning/subjects', method: 'get' })

// GET /api/learning/curriculum?subjectId= — 课程树(阶段 -> 章节 -> 课时)
export const getCurriculum = (subjectId) =>
  request({ url: '/learning/curriculum', method: 'get', params: subjectId ? { subjectId } : {} })

// GET /api/learning/progress?subjectId= — 学习进度总览
export const getProgress = (subjectId) =>
  request({ url: '/learning/progress', method: 'get', params: subjectId ? { subjectId } : {} })

// GET /api/learning/board?subjectId= — 学习看板(管理员=全员 / 普通用户=仅自己)
export const getBoard = (subjectId) =>
  request({ url: '/learning/board', method: 'get', params: subjectId ? { subjectId } : {} })

// GET /api/learning/preference — 学习偏好(正在学习的语言)
export const getPreference = () =>
  request({ url: '/learning/preference', method: 'get' })

// PUT /api/learning/preference — 保存学习偏好(0/null=清除)
export const savePreference = (subjectId) =>
  request({ url: '/learning/preference', method: 'put', data: { subjectId: subjectId || 0 } })

// GET /api/learning/lessons/{id} — 课时详情
export const getLesson = (id) =>
  request({ url: `/learning/lessons/${id}`, method: 'get' })

// POST /api/learning/lessons/{id}/learn — 标记已学
export const markLearned = (id) =>
  request({ url: `/learning/lessons/${id}/learn`, method: 'post' })

// DELETE /api/learning/lessons/{id}/learn — 取消已学
export const unmarkLearned = (id) =>
  request({ url: `/learning/lessons/${id}/learn`, method: 'delete' })

// ==================== 用户补充节点(章节/子节点) ====================

// GET /api/learning/supplements?subjectId=&chapterId= — 补充节点树
export const getSupplements = ({ subjectId, chapterId } = {}) =>
  request({ url: '/learning/supplements', method: 'get', params: { subjectId, chapterId } })

// POST /api/learning/supplements — 新增补充节点
export const createSupplement = (data) =>
  request({ url: '/learning/supplements', method: 'post', data })

// PUT /api/learning/supplements/{id} — 编辑补充节点
export const updateSupplement = (id, data) =>
  request({ url: `/learning/supplements/${id}`, method: 'put', data })

// DELETE /api/learning/supplements/{id} — 删除补充节点(级联子节点)
export const deleteSupplement = (id) =>
  request({ url: `/learning/supplements/${id}`, method: 'delete' })

// ==================== 学习配置(管理员) ====================

// GET /api/learning/admin/subjects — 全部科目(含停用)
export const adminListSubjects = () =>
  request({ url: '/learning/admin/subjects', method: 'get' })

// POST /api/learning/admin/subjects — 新增科目
export const adminCreateSubject = (data) =>
  request({ url: '/learning/admin/subjects', method: 'post', data })

// PUT /api/learning/admin/subjects/{id} — 编辑科目
export const adminUpdateSubject = (id, data) =>
  request({ url: `/learning/admin/subjects/${id}`, method: 'put', data })

// DELETE /api/learning/admin/subjects/{id} — 删除科目
export const adminDeleteSubject = (id) =>
  request({ url: `/learning/admin/subjects/${id}`, method: 'delete' })

// GET /api/learning/admin/chapters?subjectId= — 章节列表
export const adminListChapters = (subjectId) =>
  request({ url: '/learning/admin/chapters', method: 'get', params: subjectId ? { subjectId } : {} })

// POST /api/learning/admin/chapters — 新增章节
export const adminCreateChapter = (data) =>
  request({ url: '/learning/admin/chapters', method: 'post', data })

// PUT /api/learning/admin/chapters/{id} — 编辑章节
export const adminUpdateChapter = (id, data) =>
  request({ url: `/learning/admin/chapters/${id}`, method: 'put', data })

// DELETE /api/learning/admin/chapters/{id} — 删除章节(级联课时)
export const adminDeleteChapter = (id) =>
  request({ url: `/learning/admin/chapters/${id}`, method: 'delete' })
