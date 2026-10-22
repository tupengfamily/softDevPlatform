import request from '../utils/request'

/**
 * API Key 管理（管理员）
 *
 * <p><b>响应约定（务必注意）</b>：后端统一包装成 {@code {code, message, data}}，
 * 响应拦截器返回的是<b>整个包装对象</b>而不是内层 data，所以消费时要写
 * {@code res.data || {}}（或 {@code (await xxx()).data}）。
 *
 * <p><b>安全</b>：列表接口<b>只返回元数据与掩码</b>（如 {@code abcd****wxyz}），
 * 永远不返回密钥明文。后端也不提供「读取明文」的接口 ——
 * 密钥只供后端业务内部使用，前端拿不到也不需要拿到。
 */

/** 列表（只含掩码） */
export const listApiKeys = () => request({ url: '/api-keys', method: 'get' })

/** 按提供方分组 */
export const listApiKeysGrouped = () => request({ url: '/api-keys/grouped', method: 'get' })

/** 新增。secret 传明文，后端加密后存库 */
export const createApiKey = (data) => request({ url: '/api-keys', method: 'post', data })

/** 修改。secret 传空串表示「不改动密钥本体」，只改元数据 */
export const updateApiKey = (id, data) =>
  request({ url: `/api-keys/${encodeURIComponent(id)}`, method: 'put', data })

/** 删除 */
export const deleteApiKey = (id) =>
  request({ url: `/api-keys/${encodeURIComponent(id)}`, method: 'delete' })

/** 探活：用保存的凭据试调用一次上游 */
export const testApiKey = (id) =>
  request({ url: `/api-keys/${encodeURIComponent(id)}/test`, method: 'post' })
