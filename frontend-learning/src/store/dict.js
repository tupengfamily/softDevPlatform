import { defineStore } from 'pinia'
import { dictMap } from '../api/dict'
import i18n from '../i18n'

/**
 * 数据字典 store(全站标签/选项的唯一来源)
 * <p>
 * 启动时一次性拉 /api/dicts/map,缓存到 localStorage(1 小时);
 * 业务页通过 options()/label() 取下拉选项与编码->文案映射,
 * 字典未就绪时回退到调用方传入的默认值(页面不空白、不阻塞渲染)。
 * <p>
 * 双语: 字典项带 itemValueEn(英文值),界面语言为 en-US 时优先取英文值,
 * 英文值为空回退中文值;getter 读 i18n locale(ref),切换语言即时重算。
 * <p>
 * 约定: 编码值参与业务逻辑(如 status=2 只读、RATING 评分校验)仍由代码定义,
 * 字典只维护"展示文案/可选项"——管理员改的是名称,不是编码。
 */
const CACHE_KEY = 'dictMapCache'
const CACHE_TTL = 60 * 60 * 1000   // 1 小时

/** 按当前界面语言取字典项文案(英文值为空回退中文值) */
const pick = (item) =>
  (i18n.global.locale.value === 'en-US' && item.itemValueEn) ? item.itemValueEn : item.itemValue

export const useDictStore = defineStore('dict', {
  state: () => ({
    map: {},        // { typeCode: [{ itemCode, itemValue }] }
    loaded: false
  }),

  actions: {
    /** 拉取全量字典(带本地缓存;force=true 强制刷新,管理员改字典后调用) */
    async load(force = false) {
      if (!force) {
        try {
          const raw = localStorage.getItem(CACHE_KEY)
          if (raw) {
            const { ts, data } = JSON.parse(raw)
            if (Date.now() - ts < CACHE_TTL && data) {
              this.map = data
              this.loaded = true
              return
            }
          }
        } catch (_) { /* 缓存损坏则重拉 */ }
      }
      try {
        const res = await dictMap()
        this.map = res.data || {}
        this.loaded = true
        localStorage.setItem(CACHE_KEY, JSON.stringify({ ts: Date.now(), data: this.map }))
      } catch (_) {
        // 字典接口异常不阻塞页面:沿用缓存/回退默认值
      }
    },

    /** 管理员维护字典后刷新缓存 */
    async refresh() { await this.load(true) }
  },

  getters: {
    /**
     * 某类型的选项列表 [{ value, label }](按字典 sort 排序)
     * @param fallback [{value,label}] 字典未就绪时的兜底选项
     */
    options: (state) => (typeCode, fallback = []) => {
      const items = state.map[typeCode]
      if (!items || !items.length) return fallback
      return items.map(i => ({ value: i.itemCode, label: pick(i) }))
    },

    /**
     * 编码 -> 展示文案(英文界面优先英文值)
     * @param fallback 文案兜底(通常传编码本身或代码内默认文案)
     */
    label: (state) => (typeCode, code, fallback = '') => {
      const items = state.map[typeCode]
      const hit = items?.find(i => String(i.itemCode) === String(code))
      return hit ? pick(hit) : (fallback || String(code ?? ''))
    }
  }
})
