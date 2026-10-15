// 代码语法高亮工具 (基于 highlight.js v11)
// 把 codeType 字符串映射到 highlight.js 支持的语言, 然后输出高亮 HTML
//
// 设计:
// - 主题走 github (浅色), 避免"全黑"对眼睛刺激(用户反馈)
// - 优先用 highlightAuto 自动识别, 用户指定 codeType 时优先用户
// - 失败时降级到纯文本(不抛错, 保证 LessonDetail 永远能渲染)
// - 输出的 HTML 标签固定, 不会注入 XSS (highlight.js 内部已转义)
import hljs from 'highlight.js/lib/core'

// 按需注册: 我们的 codeType 集合 (12 种)
import bash from 'highlight.js/lib/languages/bash'
import css from 'highlight.js/lib/languages/css'
import dockerfile from 'highlight.js/lib/languages/dockerfile'
import html from 'highlight.js/lib/languages/xml'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import json from 'highlight.js/lib/languages/json'
import jsx from 'highlight.js/lib/languages/javascript'   // jsx 由 javascript 高亮兼容
import nodejs from 'highlight.js/lib/languages/javascript'
import python from 'highlight.js/lib/languages/python'
import sql from 'highlight.js/lib/languages/sql'
import typescript from 'highlight.js/lib/languages/typescript'
import vue from 'highlight.js/lib/languages/xml'           // vue 用 xml 高亮 (够用, 专有 vue 插件需额外打包)
import yaml from 'highlight.js/lib/languages/yaml'

hljs.registerLanguage('bash', bash)
hljs.registerLanguage('css', css)
hljs.registerLanguage('dockerfile', dockerfile)
hljs.registerLanguage('html', html)
hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('json', json)
hljs.registerLanguage('jsx', jsx)
hljs.registerLanguage('nodejs', nodejs)
hljs.registerLanguage('python', python)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('vue', vue)
hljs.registerLanguage('yaml', yaml)

/** codeType 字段值 -> highlight.js 语言 id (找不到则 null) */
const LANG_MAP = {
  bash: 'bash',
  shell: 'bash',
  css: 'css',
  docker: 'dockerfile',
  dockerfile: 'dockerfile',
  html: 'html',
  java: 'java',
  javascript: 'javascript',
  js: 'javascript',
  jsx: 'jsx',
  json: 'json',
  nodejs: 'javascript',     // node 走 javascript
  python: 'python',
  py: 'python',
  sql: 'sql',
  typescript: 'typescript',
  ts: 'typescript',
  vue: 'xml',              // 用 xml 高亮 vue 模板
  yaml: 'yaml',
}

/**
 * 高亮一段代码
 * @param {string} code 原始代码
 * @param {string} [codeType] 来自 lesson.codeType 的语言标识
 * @returns {string} 高亮后的 HTML 字符串(已转义, 安全)
 */
export function renderHighlighted(code, codeType) {
  if (!code) return ''
  const lang = codeType ? LANG_MAP[String(codeType).toLowerCase()] : null
  try {
    if (lang && hljs.getLanguage(lang)) {
      return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value
    }
    // 找不到: 降级到自动识别
    return hljs.highlightAuto(code).value
  } catch (e) {
    // 极端情况: 失败时返回原文(转义)
    return escapeHtml(code)
  }
}

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

export default { renderHighlighted }
