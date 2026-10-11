import { defineStore } from 'pinia'
import { unreadCount } from '../api/notifications'

/**
 * 消息中心未读数(头部铃铛 badge 与消息页共享)
 * Layout 轮询刷新;消息页的已读操作即时同步。
 */
export const useNotificationStore = defineStore('notification', {
  state: () => ({
    unread: 0
  }),
  actions: {
    /** 拉取未读数(静默) */
    async fetchUnread() {
      try {
        const res = await unreadCount()
        this.unread = res.data || 0
      } catch (_) { /* 静默 */ }
    },
    /** 未读 -1(单条已读) */
    decUnread() {
      this.unread = Math.max(0, this.unread - 1)
    },
    /** 未读清零(全部已读) */
    clearUnread() {
      this.unread = 0
    }
  }
})
