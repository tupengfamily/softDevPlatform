// Markdown 渲染器 — 基于开源组件 marked + DOMPurify
// - marked: 通用 Markdown 解析,支持 GFM 基本语法(标题/列表/表格/任务列表/围栏代码/
//   行内代码/图片/链接/引用/分割线/粗斜体/删除线/自动链接等)
// - DOMPurify: 输出消毒,过滤脚本与危险属性,可安全配合 v-html 使用
import { marked } from 'marked'
import DOMPurify from 'dompurify'

marked.setOptions({
  gfm: true,          // GitHub 风格扩展(表格/任务列表/删除线)
  breaks: true,       // 单换行转 <br>,贴合输入习惯
  pedantic: false
})

// 链接统一新窗口打开 + 安全 rel
// (marked v13+ renderer 方法接收 token 对象,这里兼容旧的位置参数签名)
const renderer = new marked.Renderer()
renderer.link = (...args) => {
  const tok = (args[0] && typeof args[0] === 'object')
    ? args[0]
    : { href: args[0], title: args[1], text: args[2] }
  const title = tok.title ? ` title="${tok.title}"` : ''
  return `<a href="${tok.href}"${title} target="_blank" rel="noopener noreferrer">${tok.text}</a>`
}

/** 纯消毒(富文本 HTML 白名单过滤,防 XSS;任何 v-html 前都应先过这里) */
export function sanitizeHtml(html) {
  if (!html) return ''
  return DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel']
  })
}

/** 渲染 Markdown 文本为安全 HTML */
export function renderMarkdown(text) {
  if (!text) return ''
  const raw = marked.parse(text, { renderer })
  return DOMPurify.sanitize(raw, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['target', 'rel']
  })
}

// 统一渲染入口: 两种格式均经 DOMPurify 消毒后才可安全配合 v-html
// (富文本虽发布端已有服务端消毒,渲染端仍兜底一道 — 防御纵深)
export function renderContent(content, contentType) {
  if (contentType === 'richtext') return sanitizeHtml(content)
  return renderMarkdown(content)
}
