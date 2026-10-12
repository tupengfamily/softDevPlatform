import { defineStore } from 'pinia'

/**
 * 页面主题(经典配色预设)
 * <p>
 * 通过 CSS 变量换肤:全局 --c-primary 系列 + Element Plus --el-color-primary 系列
 * 同步覆盖,一处切换全站生效。选择持久化到 localStorage,刷新/下次访问保持。
 */

export const THEME_PRESETS = [
  { id: 'classic-blue', name: '经典蓝',   primary: '#2563eb', hover: '#1d4ed8', bg: '#eef3ff' },
  { id: 'indigo',       name: '星夜靛蓝', primary: '#4f46e5', hover: '#4338ca', bg: '#eef2ff' },
  { id: 'emerald',      name: '翡翠绿',   primary: '#059669', hover: '#047857', bg: '#e7f6f0' },
  { id: 'amber',        name: '琥珀橙',   primary: '#ea580c', hover: '#c2410c', bg: '#fff1e7' },
  { id: 'rose',        name: '珊瑚红',   primary: '#e11d48', hover: '#be123c', bg: '#fde9ef' },
  { id: 'violet',       name: '紫罗兰',   primary: '#7c3aed', hover: '#6d28d9', bg: '#f1eafe' },
  { id: 'teal',         name: '青碧',     primary: '#0d9488', hover: '#0f766e', bg: '#e6f4f1' },
  { id: 'graphite',     name: '石墨黑',   primary: '#334155', hover: '#1e293b', bg: '#eef1f6' }
]

const STORAGE_KEY = 'appTheme'

/** hex 颜色与白色/黑色混合(pct 为向白色/黑色靠近的比例) */
function mix(hex, target, pct) {
  const h = hex.replace('#', '')
  const t = target.replace('#', '')
  const ch = (i) => parseInt(h.slice(i, i + 2), 16)
  const ct = (i) => parseInt(t.slice(i, i + 2), 16)
  const to2 = (n) => Math.round(n).toString(16).padStart(2, '0')
  const r = ch(0) + (ct(0) - ch(0)) * pct
  const g = ch(2) + (ct(2) - ch(2)) * pct
  const b = ch(4) + (ct(4) - ch(4)) * pct
  return `#${to2(r)}${to2(g)}${to2(b)}`
}

/** hex → "r,g,b"(供 CSS rgba(var(--c-primary-rgb), a) 使用) */
function toRgb(hex) {
  const h = hex.replace('#', '')
  return [0, 2, 4].map(i => parseInt(h.slice(i, i + 2), 16)).join(', ')
}

/** 把主题写入 CSS 变量(全局 + Element Plus 主色 + 左侧菜单) */
export function applyThemeVars(theme) {
  const root = document.documentElement.style
  root.setProperty('--c-primary', theme.primary)
  root.setProperty('--c-primary-hover', theme.hover)
  root.setProperty('--c-primary-bg', theme.bg)
  root.setProperty('--c-primary-grad', `linear-gradient(135deg, ${theme.primary}, ${theme.hover})`)
  root.setProperty('--c-primary-rgb', toRgb(theme.primary))

  // 左侧菜单主题: 侧栏深色背景带主题色调,激活/悬浮高亮跟随主题
  root.setProperty('--c-sidebar-from', mix(theme.primary, '#0b1220', 0.84))
  root.setProperty('--c-sidebar-to', mix(theme.primary, '#101a30', 0.78))

  // Element Plus 主色联动(按钮/输入框/链接等组件跟随主题)
  root.setProperty('--el-color-primary', theme.primary)
  root.setProperty('--el-color-primary-light-3', mix(theme.primary, '#ffffff', 0.3))
  root.setProperty('--el-color-primary-light-5', mix(theme.primary, '#ffffff', 0.5))
  root.setProperty('--el-color-primary-light-7', mix(theme.primary, '#ffffff', 0.7))
  root.setProperty('--el-color-primary-light-8', mix(theme.primary, '#ffffff', 0.8))
  root.setProperty('--el-color-primary-light-9', mix(theme.primary, '#ffffff', 0.9))
  root.setProperty('--el-color-primary-dark-2', mix(theme.primary, '#000000', 0.2))
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    themeId: localStorage.getItem(STORAGE_KEY) || 'classic-blue'
  }),
  getters: {
    /** 当前主题预设 */
    current: (s) => THEME_PRESETS.find(t => t.id === s.themeId) || THEME_PRESETS[0]
  },
  actions: {
    /** 应用主题(切换即生效并持久化);不传 id 表示按已保存的主题初始化 */
    apply(themeId) {
      if (themeId) this.themeId = themeId
      const theme = THEME_PRESETS.find(t => t.id === this.themeId) || THEME_PRESETS[0]
      this.themeId = theme.id
      applyThemeVars(theme)
      localStorage.setItem(STORAGE_KEY, theme.id)
    }
  }
})
