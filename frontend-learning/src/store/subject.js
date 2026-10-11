import { defineStore } from 'pinia'
import { getSubjects, getPreference, savePreference } from '../api/learning'
import { useUserStore } from './user'

/**
 * 学习语言(科目)偏好状态
 * <p>
 * 头像旁语言选择框 / 学习中心科目切换 / 学习看板维度都以这里为唯一数据源:
 * - preferredSubjectId: 用户正在学习的语言,双重记忆(localStorage 即时生效 + 后端跨设备)
 * - subjects: 启用的科目列表
 * 下次进入平台默认展示 preferredSubjectId 对应语言的学习内容。
 */
export const useSubjectStore = defineStore('subject', {
  state: () => ({
    subjects: [],
    preferredSubjectId: Number(localStorage.getItem('preferredSubjectId')) || null,
    loaded: false
  }),
  getters: {
    /** 偏好科目对象(可能为 null) */
    preferredSubject(state) {
      return state.subjects.find(s => s.id === state.preferredSubjectId) || null
    }
  },
  actions: {
    /** 拉取科目列表 + 服务端偏好(服务端为准,未设置则沿用本地;游客仅拉列表) */
    async load() {
      try {
        const res = await getSubjects()
        this.subjects = res.data || []
      } catch (_) { /* 静默,列表为空时页面自行兜底 */ }
      if (useUserStore().isLogin) {
        try {
          const res = await getPreference()
          const sid = res.data?.subjectId
          if (sid) {
            this.preferredSubjectId = sid
            localStorage.setItem('preferredSubjectId', String(sid))
          }
        } catch (_) { /* 静默,沿用本地偏好 */ }
      }
      // 偏好科目不存在/被禁用时回退到第一个启用科目
      if (!this.subjects.some(s => s.id === this.preferredSubjectId)) {
        this.preferredSubjectId = this.subjects[0]?.id || null
      }
      this.loaded = true
    },

    /** 切换偏好语言:本地立即生效 + 同步后端(游客仅本地记忆;失败静默,不影响使用) */
    async setPreferred(subjectId) {
      this.preferredSubjectId = subjectId || null
      if (subjectId) localStorage.setItem('preferredSubjectId', String(subjectId))
      else localStorage.removeItem('preferredSubjectId')
      if (useUserStore().isLogin) {
        try { await savePreference(subjectId) } catch (_) { /* 静默 */ }
      }
    }
  }
})
