import { createI18n } from 'vue-i18n'
import common from './messages/common'
import learning from './messages/learning'
import lesson from './messages/lesson'
import system from './messages/system'
import admin from './messages/admin'
import social from './messages/social'
import misc from './messages/misc'
import doc from './messages/doc'

/**
 * 国际化(i18n)入口 — 中文(zh-CN)/英文(en-US)双语
 * <p>
 * - 文案按页面分组放在 messages/*.js,每个文件导出 { 'zh-CN': {...}, 'en-US': {...} }
 * - 组件内用法: 模板 $t('key') 或 setup 里 const { t } = useI18n()
 * - 切换语言走 store/locale.js(setLocale),持久化 localStorage + 后端用户偏好
 * - 词表(术语统一): 科目=Subject 课时=Lesson 学习看板=Learning Board 数据字典=Dictionary
 *   操作日志=Operation Log 反馈=Feedback 帖子=Post 版块=Board 界面语言=Language
 * <p>
 * 约定: 用户内容(课程正文/帖子/Wiki/评论)不翻译,只翻译 UI 文案。
 */
export const LOCALES = [
  { value: 'zh-CN', label: '中文' },
  { value: 'en-US', label: 'English' }
]

/** 合并各分组文案为 i18n messages 结构 */
function merge(...parts) {
  const zh = {}
  const en = {}
  for (const p of parts) {
    Object.assign(zh, p['zh-CN'] || {})
    Object.assign(en, p['en-US'] || {})
  }
  return { 'zh-CN': zh, 'en-US': en }
}

export const messages = merge(common, learning, lesson, system, admin, social, misc, doc)

export const LOCALE_KEY = 'appLocale'

export function getStoredLocale() {
  const v = localStorage.getItem(LOCALE_KEY)
  return v === 'en-US' ? 'en-US' : 'zh-CN'
}

const i18n = createI18n({
  legacy: false,            // 组合式 API
  globalInjection: true,    // 模板里可用 $t
  locale: getStoredLocale(),
  fallbackLocale: 'zh-CN',
  messages,
  missingWarn: false,
  fallbackWarn: false
})

/** 应用语言(仅本机 i18n 层;持久化/服务端同步走 store/locale.js) */
export function applyLocale(locale) {
  const v = locale === 'en-US' ? 'en-US' : 'zh-CN'
  i18n.global.locale.value = v
  document.documentElement.setAttribute('lang', v === 'en-US' ? 'en' : 'zh-CN')
  return v
}

// 启动时同步一次 <html lang>
applyLocale(getStoredLocale())

export default i18n
