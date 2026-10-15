import { defineStore } from 'pinia'
import { login as apiLogin, register as apiRegister, me as apiMe, logout as apiLogout, logoutAll as apiLogoutAll, updateProfile as apiUpdateProfile } from '../api/auth'
import { encryptWithPublicKey } from '../utils/rsa'

/**
 * 用户状态管理(Pinia)
 * <p>
 * 状态: token + userInfo(持久化到 localStorage)
 * Getter: isLogin / roles / permissions / username
 * Action: login / register / fetchMe / logout
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    userInfo: safeJson(localStorage.getItem('userInfo'))
  }),
  getters: {
    isLogin: (s) => !!s.token,
    userId: (s) => s.userInfo?.userId,
    username: (s) => s.userInfo?.username || '',
    /** 展示名:昵称优先,回退用户名 */
    displayName: (s) => s.userInfo?.nickname || s.userInfo?.username || '',
    /** 头像:URL/emoji 字符,为空时前端显示首字母 */
    avatar: (s) => s.userInfo?.avatar || '',
    signature: (s) => s.userInfo?.signature || '',
    roles: (s) => s.userInfo?.roles || [],
    permissions: (s) => s.userInfo?.permissions || []
  },
  actions: {
    /**
     * 登录: 调用后端 /auth/login,保存 token + 用户信息
     */
    async login(payload) {
      // RSA 加密密码再 POST(避免明文传输); payload 仍兼容 { password } / { encryptedPassword }
      const safe = { ...payload }
      if (safe.password && !safe.encryptedPassword) {
        try {
          safe.encryptedPassword = await encryptWithPublicKey(safe.password)
          delete safe.password
        } catch (e) {
          // 公钥获取 / 加密失败,保留明文向后兼容
          console.warn('[RSA] 公钥加密失败, 回退明文:', e?.message)
        }
      }
      const resp = await apiLogin(safe)
      const data = resp.data
      this.token = data.token
      this.userInfo = data
      localStorage.setItem('token', data.token)
      // userInfo 落盘剔除 token,避免 token 重复存储(登录态统一从 'token' 读取)
      localStorage.setItem('userInfo', JSON.stringify({ ...data, token: undefined }))
      return data
    },

    /**
     * 注册: 调用 /auth/register,自动登录
     */
    async register(payload) {
      // 注册同样加密密码
      const safe = { ...payload }
      if (safe.password && !safe.encryptedPassword) {
        try {
          safe.encryptedPassword = await encryptWithPublicKey(safe.password)
          delete safe.password
        } catch (e) {
          console.warn('[RSA] 公钥加密失败, 回退明文:', e?.message)
        }
      }
      const resp = await apiRegister(safe)
      const data = resp.data
      this.token = data.token
      this.userInfo = data
      localStorage.setItem('token', data.token)
      localStorage.setItem('userInfo', JSON.stringify({ ...data, token: undefined }))
      return data
    },

    /** 拉取当前用户信息 */
    async fetchMe() {
      const resp = await apiMe()
      // 保留 token 不变,只更新 userInfo
      this.userInfo = { ...this.userInfo, ...resp.data }
      localStorage.setItem('userInfo', JSON.stringify({ ...this.userInfo, token: undefined }))
      return this.userInfo
    },

    /** 更新个人信息(昵称/头像/个性签名/邮箱/年龄/地址) */
    async updateProfile(payload) {
      const resp = await apiUpdateProfile(payload)
      const data = resp.data
      // 后端返回完整用户信息(无 token 字段),合并保存
      this.userInfo = { ...this.userInfo, ...data, token: this.token }
      localStorage.setItem('userInfo', JSON.stringify({ ...this.userInfo, token: undefined }))
      return this.userInfo
    },

    /** 登出: 调用后端(递增 tokenVersion 让该用户所有 token 失效) + 清本地状态 */
    async logout() {
      try { await apiLogout() } catch (_) { /* ignore */ }
      this.clear()
    },

    /** 退出所有设备: 显式调后端 + 清本地状态 */
    async logoutAll() {
      try { await apiLogoutAll() } catch (_) { /* ignore */ }
      this.clear()
    },

    /** 仅清本地状态(401 时使用) */
    clear() {
      this.token = ''
      this.userInfo = null
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    }
  }
})

function safeJson(s) {
  try {
    return s ? JSON.parse(s) : null
  } catch (_) {
    return null
  }
}