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

// HTML 实体转义(链接 title/text 拼接前转义,防御纵深)
const escapeHtml = (s) => String(s ?? '')
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&#39;')

// href 协议白名单: 只允许 http/https/mailto/锚点/相对路径,其余(javascript: 等)拒绝
const safeHref = (href) => {
  const h = String(href ?? '').trim().replace(/[\u0000-\u001F\u007F]/g, '')
  if (!h) return '#'
  if (h.startsWith('#')) return h                                  // 页内锚点
  if (/^(?:https?:|mailto:)/i.test(h)) return h                    // 白名单协议
  if (!/^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(h) && !h.startsWith('//')) return h // 相对路径
  return '#'                                                       // 其余协议/协议相对 URL 拒绝
}

// 链接统一新窗口打开 + 安全 rel
// (marked v13+ renderer 方法接收 token 对象,这里兼容旧的位置参数签名)
const renderer = new marked.Renderer()
renderer.link = (...args) => {
  const tok = (args[0] && typeof args[0] === 'object')
    ? args[0]
    : { href: args[0], title: args[1], text: args[2] }
  const href = safeHref(tok.href)
  const title = tok.title ? ` title="${escapeHtml(tok.title)}"` : ''
  return `<a href="${escapeHtml(href)}"${title} target="_blank" rel="noopener noreferrer">${escapeHtml(tok.text)}</a>`
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

/** 把标题文本转成可作 id 的 slug(去标签/空白/特殊字符,保留 CJK) */
export function slugifyHeading(text) {
  const t = String(text || '').replace(/<[^>]+>/g, '').trim()
  const base = t
    .toLowerCase()
    .replace(/[\s\u3000]+/g, '-')         // 空白(含全角)→ -
    .replace(/[^\w\u4e00-\u9fa5-]/g, '')  // 仅保留字母数字_、中文、横线
    .replace(/-+/g, '-')
    .replace(/^-|-$/g, '')
  return base || 'section'
}

/** 给 HTML 字符串里的 h1-h6 自动补上 id(重复则 -2/-3 后缀);返回新串,原 html 不变 */
export function attachHeadingIds(html) {
  if (!html) return html
  const counter = Object.create(null)
  return String(html).replace(
    /<h([1-6])([^>]*)>([\s\S]*?)<\/h\1>/gi,
    (_m, lvl, attrs, inner) => {
      const text = inner.replace(/<[^>]+>/g, '')
      const base = slugifyHeading(text)
      let id = base
      if (counter[base] != null) id = `${base}-${counter[base]}`
      counter[base] = (counter[base] || 1) + 1
      return `<h${lvl}${attrs} id="${id}">${inner}</h${lvl}>`
    }
  )
}

/** 从 HTML 中抽取 h1-h3 标题大纲(依赖 attachHeadingIds 已生成 id) */
export function extractToc(html) {
  if (!html) return []
  const items = []
  const re = /<h([1-3])([^>]*)id="([^"]+)"[^>]*>([\s\S]*?)<\/h\1>/gi
  let m
  while ((m = re.exec(html))) {
    items.push({
      level: parseInt(m[1], 10),
      id: m[3],
      text: m[4].replace(/<[^>]+>/g, '').trim()
    })
  }
  return items
}

// 统一渲染入口: 两种格式均经 DOMPurify 消毒后才可安全配合 v-html
// (富文本虽发布端已有服务端消毒,渲染端仍兜底一道 — 防御纵深)
export function renderContent(content, contentType) {
  if (contentType === 'richtext') return sanitizeHtml(content)
  return renderMarkdown(content)
}

/** 同 renderContent,但额外给所有 h1-h6 注入 id(给右侧目录用) */
export function renderContentWithToc(content, contentType) {
  return attachHeadingIds(renderContent(content, contentType))
}
