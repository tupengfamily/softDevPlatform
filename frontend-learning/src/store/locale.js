import { defineStore } from 'pinia'
import { applyLocale, getStoredLocale, LOCALE_KEY } from '../i18n'
import { getPreference, savePreference } from '../api/learning'
import { useUserStore } from './user'

/**
 * 界面语言(zh-CN / en-US)状态
 * <p>
 * 与 store/subject.js 同套路的双重记忆:
 * - localStorage 即时生效(游客也记住),后端 t_user_preference.locale 跨设备同步
 * - 切换入口: Layout 头部语言切换器;拉取时机: Layout onMounted 调 pullFromServer()
 * - 英文文案缺失时 i18n 自动回退中文(fallbackLocale),页面永不空白
 */
export const useLocaleStore = defineStore('locale', {
  state: () => ({
    locale: getStoredLocale()
  }),

  actions: {
    /** 应用并持久化语言(登录用户同步到后端;失败静默不影响使用) */
    async setLocale(value) {
      const v = applyLocale(value)
      this.locale = v
      localStorage.setItem(LOCALE_KEY, v)
      if (useUserStore().isLogin) {
        try {
          await savePreference({ locale: v })
        } catch (_) { /* 静默,本机已生效 */ }
      }
    },

    /** 登录后拉取服务端偏好: 仅当服务端显式设置过且与本机不同才覆盖(以用户刚做的选择为准) */
    async pullFromServer() {
      if (!useUserStore().isLogin) return
      try {
        const res = await getPreference()
        const server = res.data?.locale
        if (server && server !== this.locale) {
          await this.setLocale(server)
        } else if (!localStorage.getItem(LOCALE_KEY) && server) {
          await this.setLocale(server)
        }
      } catch (_) { /* 静默,沿用本机语言 */ }
    }
  }
})
