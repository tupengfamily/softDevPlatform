import request from '../utils/request'

// ==================== 论坛专区 ====================
// subjectId 约定: 不传=全部;0=综合讨论;其余=具体科目(语言)

// GET /api/forum/posts — 帖子分页
// sort: updated(默认,最近更新)/created(最新发布)/hot(最热门)/likes(点赞最多)/favorites(收藏最多)/replies(回复最多)/views(浏览最多)
export const pagePosts = ({ subjectId, keyword, sort, page = 1, size = 10 } = {}) =>
  request({ url: '/forum/posts', method: 'get', params: { subjectId, keyword, sort, page, size } })

// POST /api/forum/posts/{id}/like — 点赞/取消点赞(切换)
export const toggleLike = (id) =>
  request({ url: `/forum/posts/${id}/like`, method: 'post' })

// POST /api/forum/posts/{id}/favorite — 收藏/取消收藏(切换)
export const toggleFavorite = (id) =>
  request({ url: `/forum/posts/${id}/favorite`, method: 'post' })

// POST /api/forum/posts/{id}/share — 转发(计数 +1,链接复制由前端完成)
export const sharePost = (id) =>
  request({ url: `/forum/posts/${id}/share`, method: 'post' })

// GET /api/forum/posts/{id} — 帖子详情(浏览数 +1)
export const getPost = (id) =>
  request({ url: `/forum/posts/${id}`, method: 'get' })

// POST /api/forum/posts — 发帖
export const createPost = (data) =>
  request({ url: '/forum/posts', method: 'post', data })

// PUT /api/forum/posts/{id} — 编辑帖子
export const updatePost = (id, data) =>
  request({ url: `/forum/posts/${id}`, method: 'put', data })

// DELETE /api/forum/posts/{id} — 删除帖子(级联回复)
export const deletePost = (id) =>
  request({ url: `/forum/posts/${id}`, method: 'delete' })

// GET /api/forum/posts/{id}/replies — 回复分页
export const pageReplies = (postId, { page = 1, size = 20 } = {}) =>
  request({ url: `/forum/posts/${postId}/replies`, method: 'get', params: { page, size } })

// POST /api/forum/posts/{id}/replies — 回复
export const createReply = (postId, data) =>
  request({ url: `/forum/posts/${postId}/replies`, method: 'post', data })

// DELETE /api/forum/replies/{id} — 删除回复
export const deleteReply = (id) =>
  request({ url: `/forum/replies/${id}`, method: 'delete' })
