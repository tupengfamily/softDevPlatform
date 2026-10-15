import JSEncrypt from 'jsencrypt'
import request from './request'

/** 公钥内存缓存(避免每次登录都 fetch) */
let cachedPem = null
let cachedAt = 0
const TTL_MS = 5 * 60 * 1000

/**
 * 从后端拿 RSA 公钥(PEM), 缓存 5 分钟。
 * 后端启动期会生成 2048 位 RSA-OAEP-SHA256 keypair, 私钥落盘持久化,
 * 公钥明文返回(本来就是公开的)。
 */
export async function getPublicKeyPem() {
  const now = Date.now()
  if (cachedPem && now - cachedAt < TTL_MS) return cachedPem
  const res = await request({ url: '/auth/public-key', method: 'get' })
  cachedPem = res.data?.pem
  cachedAt = now
  if (!cachedPem) throw new Error('后端未返回 PEM 公钥')
  return cachedPem
}

/**
 * 用公钥加密一段文本(用于密码传输)。
 * 返回 Base64 字符串, 后端用 {@code Base64.getDecoder().decode()} + 私钥还原。
 */
export async function encryptWithPublicKey(plain) {
  const pem = await getPublicKeyPem()
  const enc = new JSEncrypt()
  enc.setPublicKey(pem)
  // 2048 位 RSA 明文长度上限约 245 字节, 密码通常不超过, 默认 RSA-OAEP
  const cipher = enc.encrypt(plain)
  if (!cipher) throw new Error('RSA 加密失败(明文过长或 PEM 异常)')
  return cipher
}

/**
 * 主动清空公钥缓存(比如后端提示私钥已轮换 / 切换服务器时)
 */
export function clearPublicKeyCache() {
  cachedPem = null
  cachedAt = 0
}