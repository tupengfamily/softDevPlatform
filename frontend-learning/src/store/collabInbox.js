// 全局协作申请待审批数量(Layout 顶部徽标用)
// 持久化: 当前用户 id + 缓存, 切换用户时自动重置
import { defineStore } from 'pinia'
import { getCollabInbox } from '../api/personal'

export const useCollabInboxStore = defineStore('collabInbox', {
  state: () => ({
    pendingCount: 0,
    lastFetchAt: 0
  }),
  actions: {
    /** 拉一次, 30s 节流 */
    async refresh(force = false) {
      const now = Date.now()
      if (!force && now - this.lastFetchAt < 30_000) return
      try {
        const r = await getCollabInbox()
        const list = r.data || []
        this.pendingCount = list.filter(c => c.status === 'PENDING').length
        this.lastFetchAt = now
      } catch (e) { /* 静默, 不阻塞 UI */ }
    },
    reset() { this.pendingCount = 0; this.lastFetchAt = 0 }
  }
})
