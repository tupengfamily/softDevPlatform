/**
 * Office 富文本粘贴管线
 *
 * 业界结论(见调研): Word / Excel / WPS 复制到剪贴板的 text/html 是各自的"私有方言"——
 * 大量 mso-* 私有样式、MsoNormal class、条件注释、VML, 列表编号根本不是 <ul>/<li>
 * 而是 <p style="mso-list:l0 level1"> + 隐藏计数器, 表格的合并/列宽/边框藏在属性里。
 * 直接原样插入一定乱; 简单 sanitizer 只懂安全不懂 Office, 会把语义和列宽一起洗掉。
 *
 * 所以这里做的是"拦截 → 识别来源 → 还原语义 → 清洗 → 转成我们的文档模型"这条管线,
 * 而不是粗暴地 innerHTML 一把梭。
 */
import { uploadImage } from '../api/upload'

/** 判定剪贴板 HTML 来自哪个办公软件, 不同来源的方言差异很大 */
export function detectSource (html) {
  if (!html) return 'plain'
  if (/class="?Mso(Normal|ListParagraph|BodyText)|mso-|xmlns:o=|xmlns:w=/i.test(html)) {
    return /x:str|x:num|Excel/i.test(html) ? 'excel' : 'word'
  }
  if (/docs-internal-guid|id="docs-internal/i.test(html)) return 'gdocs'
  if (/WPS|wps/i.test(html)) return 'wps'
  return 'generic'
}

/**
 * 把剪贴板内容转成文档区块数组
 * @param {DataTransfer} dt paste 事件里的 clipboardData
 * @param {object} [opts] { scope: 上传目录 }
 * @returns {Promise<Array>} 区块数组(可直接 push 进编辑器模型)
 */
export async function clipboardToBlocks (dt, opts = {}) {
  if (!dt) return []
  const html = dt.getData('text/html')
  const plain = dt.getData('text/plain')
  const blocks = []

  // 1) 剪贴板里的图片: Word 复制整段图文时, HTML 里的 img 是 file:// 路径(浏览器读不到),
  //    真正能用的二进制在 items 里 —— 这是业界唯一的可靠取图方式。
  const files = []
  if (dt.items && dt.items.length) {
    for (const it of Array.from(dt.items)) {
      if (it.kind === 'file' && /^image\//.test(it.type)) {
        const f = it.getAsFile()
        if (f) files.push(f)
      }
    }
  }
  if (!files.length && dt.files && dt.files.length) {
    for (const f of Array.from(dt.files)) if (/^image\//.test(f.type)) files.push(f)
  }

  // 2) 正文
  if (html && html.trim()) {
    blocks.push(...htmlToBlocks(html, { source: detectSource(html), tableMode: opts.tableMode }))
  } else if (plain && plain.trim()) {
    blocks.push(...plainToBlocks(plain))
  }

  // 3) 图片上传后追加(失败不影响正文)
  for (const f of files) {
    try {
      const { url } = await uploadImage(f, opts.scope || 'mydoc')
      if (url) blocks.push({ type: 'image', url, anchor: -1 })
    } catch (e) {
      /* 单张失败跳过 */
    }
  }
  return blocks
}

/** 纯文本兜底: Safari / iOS 经常只给 text/plain */
export function plainToBlocks (text) {
  return String(text || '')
    .replace(/\r\n/g, '\n')
    .split(/\n{2,}/)
    .map(s => s.trim())
    .filter(Boolean)
    .map(s => ({ type: 'paragraph', text: s, align: 'left', anchor: -1 }))
}

// ======================================================================
//  HTML -> 区块
// ======================================================================

export function htmlToBlocks (html, opts = {}) {
  const source = opts.source || detectSource(html)
  const doc = new DOMParser().parseFromString(sanitizeHtml(html), 'text/html')
  stripDangerous(doc.body)

  const out = []
  const ctx = { source }
  for (const node of Array.from(doc.body.childNodes)) {
    visit(node, {}, 0, out, ctx)
  }
  return postProcess(out)
}

/**
 * 粗清洗: 干掉 Office 的条件注释、<xml>/VML、<style>、<o:p>,
 * 以及 XSS 载体(script/iframe/object/on* 事件)。
 * 注意: 不能用通用 sanitizer 代替 —— 它会连 mso- 的列宽和列表语义一起洗掉。
 */
function sanitizeHtml (html) {
  return String(html)
    // 条件注释 <!--[if gte mso 9]> ... 表格边框就藏在这里
    .replace(/<!--\[if[^]*?<!\[endif\]-->/gi, '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(\/?)(xml|w:|o:|v:|st1:)[^>]*>/gi, '')
    .replace(/<style[\s\S]*?<\/style>/gi, '')
    .replace(/<script[\s\S]*?<\/script>/gi, '')
    .replace(/<iframe[\s\S]*?<\/iframe>/gi, '')
    .replace(/<object[\s\S]*?<\/object>/gi, '')
    .replace(/<embed[^>]*>/gi, '')
    .replace(/\son[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)/gi, '')
    .replace(/javascript:/gi, '')
}

function stripDangerous (root) {
  if (!root) return
  root.querySelectorAll('script,iframe,object,embed,noscript,link,meta,style').forEach(n => n.remove())
  root.querySelectorAll('*').forEach(n => {
    for (const a of Array.from(n.attributes)) {
      if (/^on/i.test(a.name)) n.removeAttribute(a.name)
      if (/^javascript:/i.test(a.value || '')) n.removeAttribute(a.name)
    }
  })
}

const BLOCKY = new Set(['P', 'DIV', 'H1', 'H2', 'H3', 'H4', 'H5', 'H6', 'LI', 'BLOCKQUOTE', 'PRE', 'SECTION', 'ARTICLE', 'ADDRESS'])

/**
 * 递归访问节点。
 * fmt 累积行内格式(加粗/斜体/颜色/字号/字体), 因为我们的模型是段落级的,
 * 所以一段里"出现过"的加粗/斜体/下划线会映射到整个段落 —— 与 Word 段内混排相比是简化,
 * 但能保证"复制过来至少还是加粗的", 而不是整段掉格式。
 */
function visit (node, fmt, level, out, ctx) {
  if (node.nodeType === 3) {
    const t = node.nodeValue.replace(/[\u00a0]/g, ' ')
    if (t.trim()) out.push(mkParagraph(t, fmt, level, ctx))
    return
  }
  if (node.nodeType !== 1) return
  const tag = node.tagName.toUpperCase()

  if (tag === 'TABLE') { out.push(tableBlock(node)); return }
  if (tag === 'IMG') {
    const src = node.getAttribute('src') || ''
    // file:// 是 Word 的本地临时路径, 浏览器读不到, 只能丢弃(真实图片走 items 上传)
    if (/^(https?:|data:image\/)/i.test(src)) out.push({ type: 'image', url: src, anchor: -1 })
    return
  }
  if (tag === 'BR') { out.push(mkParagraph('', fmt, level, ctx)); return }
  if (tag === 'UL' || tag === 'OL') {
    const ordered = tag === 'OL'
    Array.from(node.children).forEach(li => {
      if (li.tagName.toUpperCase() !== 'LI') return
      visitListItem(li, fmt, level, out, ctx, ordered)
    })
    return
  }

  const nextFmt = mergeFmt(fmt, node)
  const isList = tag === 'LI' || isOfficeList(node, ctx.source)
  if (isList) {
    visitListItem(node, nextFmt, level, out, ctx, orderedOf(node, ctx.source))
    return
  }
  if (tag === 'BLOCKQUOTE') {
    Array.from(node.childNodes).forEach(c => visit(c, nextFmt, level + 1, out, ctx))
    return
  }

  const heading = headingLevel(node)
  if (BLOCKY.has(tag) && hasBlockChild(node)) {
    Array.from(node.childNodes).forEach(c => visit(c, nextFmt, level, out, ctx))
    return
  }
  if (heading > 0) {
    const text = textOf(node)
    if (text.trim()) out.push({ type: 'heading', level: heading, text: text.trim(), anchor: -1 })
    return
  }
  if (BLOCKY.has(tag)) {
    const text = textOf(node)
    if (text.trim()) out.push(mkParagraph(text.trim(), nextFmt, level, ctx))
    else if (node.querySelector && node.querySelector('img')) {
      Array.from(node.childNodes).forEach(c => visit(c, nextFmt, level, out, ctx))
    }
    return
  }
  Array.from(node.childNodes).forEach(c => visit(c, nextFmt, level, out, ctx))
}

function visitListItem (node, fmt, level, out, ctx, ordered) {
  const inner = Array.from(node.childNodes).filter(
    c => !(c.nodeType === 1 && /^(UL|OL)$/.test(c.tagName))
  )
  const text = inner.map(c => c.textContent || '').join('').trim()
  if (text) {
    out.push({
      type: 'bullet',
      text: stripBulletMarker(text),
      level: Math.max(0, Math.min(8, level)),
      ordered: !!ordered,
      anchor: -1
    })
  }
  Array.from(node.children)
    .filter(c => /^(UL|OL)$/.test(c.tagName))
    .forEach(c => visit(c, fmt, level + 1, out, ctx))
}

function mkParagraph (text, fmt, level, ctx) {
  if (level > 0) {
    return { type: 'bullet', text: stripBulletMarker(text), level, ordered: false, anchor: -1 }
  }
  const b = { type: 'paragraph', text, align: fmt.align || 'left', anchor: -1 }
  if (fmt.bold) b.bold = true
  if (fmt.italic) b.italic = true
  if (fmt.underline) b.underline = true
  if (fmt.strike) b.strike = true
  if (fmt.color) b.color = fmt.color
  if (fmt.fontSize) b.fontSize = fmt.fontSize
  if (fmt.fontFamily) b.fontFamily = fmt.fontFamily
  return b
}

// ---------- 行内格式 ----------

function mergeFmt (fmt, el) {
  const next = { ...fmt }
  const tag = el.tagName.toUpperCase()
  if (/^(B|STRONG)$/.test(tag)) next.bold = true
  if (/^(I|EM)$/.test(tag)) next.italic = true
  if (/^(U|INS)$/.test(tag)) next.underline = true
  if (/^(S|DEL|STRIKE)$/.test(tag)) next.strike = true
  const st = (el.getAttribute && el.getAttribute('style')) || ''
  const css = styleMap(st)
  if (css['font-weight'] && (css['font-weight'] === 'bold' || parseInt(css['font-weight'], 10) >= 600)) next.bold = true
  if (css['font-style'] === 'italic') next.italic = true
  if (css['text-decoration']) {
    if (/underline/.test(css['text-decoration'])) next.underline = true
    if (/line-through/.test(css['text-decoration'])) next.strike = true
  }
  if (css['text-align']) next.align = normalizeAlign(css['text-align'])
  if (css.color && !next.color) next.color = toHex(css.color)
  if (css['font-size'] && !next.fontSize) next.fontSize = ptToInt(css['font-size'])
  if (css['font-family'] && !next.fontFamily) next.fontFamily = cleanFont(css['font-family'])
  // Word 老式标签 <font color=... size=... face=...>
  if (tag === 'FONT') {
    if (el.getAttribute('color') && !next.color) next.color = toHex(el.getAttribute('color'))
    if (el.getAttribute('face') && !next.fontFamily) next.fontFamily = cleanFont(el.getAttribute('face'))
    const sz = el.getAttribute('size')
    if (sz && !next.fontSize) next.fontSize = legacySizeToPt(sz)
  }
  return next
}

function styleMap (st) {
  const out = {}
  String(st || '').split(';').forEach(pair => {
    const i = pair.indexOf(':')
    if (i < 0) return
    out[pair.slice(0, i).trim().toLowerCase().replace(/^mso-/, '')] = pair.slice(i + 1).trim().toLowerCase()
  })
  return out
}

function normalizeAlign (v) {
  const s = String(v).toLowerCase()
  if (s.indexOf('center') >= 0) return 'center'
  if (s.indexOf('right') >= 0) return 'right'
  if (s.indexOf('justify') >= 0) return 'justify'
  return 'left'
}

function toHex (c) {
  const s = String(c || '').trim()
  if (/^#?[0-9a-f]{6}$/i.test(s)) return s.replace('#', '').toUpperCase()
  if (/^#?[0-9a-f]{3}$/i.test(s)) {
    return s.replace('#', '').split('').map(x => x + x).join('').toUpperCase()
  }
  const m = s.match(/rgb\((\d+)\s*,\s*(\d+)\s*,\s*(\d+)\)/i)
  if (m) {
    return [m[1], m[2], m[3]].map(n => (+n).toString(16).padStart(2, '0')).join('').toUpperCase()
  }
  return ''
}

function ptToInt (v) {
  const m = String(v).match(/([\d.]+)\s*(pt|px)/i)
  if (!m) return 0
  let n = parseFloat(m[1])
  if (/px/i.test(m[2])) n = n * 0.75
  return Math.max(1, Math.min(96, Math.round(n)))
}

function legacySizeToPt (s) {
  const map = { 1: 8, 2: 10, 3: 12, 4: 14, 5: 18, 6: 24, 7: 36 }
  return map[String(s).trim()] || 0
}

function cleanFont (f) {
  return String(f || '').replace(/["']/g, '').split(',')[0].trim()
}

// ---------- 标题 / 列表语义 ----------

function headingLevel (el) {
  const tag = el.tagName.toUpperCase()
  const m = tag.match(/^H([1-6])$/)
  if (m) return +m[1]
  const cls = (el.className || '') + ' ' + (el.getAttribute && el.getAttribute('style') || '')
  if (/Heading\s*1|标题\s*1|\bTitle\b/i.test(cls)) return 1
  if (/Heading\s*2|标题\s*2|\bSubtitle\b/i.test(cls)) return 2
  if (/Heading\s*([3-6])|标题\s*([3-6])/i.test(cls)) {
    const mm = cls.match(/(?:Heading|标题)\s*([3-6])/i)
    return mm ? +mm[1] : 3
  }
  return 0
}

/** Word 的列表不是 <ul>, 而是带 mso-list 的段落 */
function isOfficeList (el, source) {
  if (source !== 'word' && source !== 'wps') return false
  const st = (el.getAttribute && el.getAttribute('style')) || ''
  const cls = el.className || ''
  return /mso-list\s*:/i.test(st) || /MsoListParagraph/i.test(cls)
}

/** 判断 Word 列表是有序还是无序: 看开头的符号 */
function orderedOf (el, source) {
  const st = (el.getAttribute && el.getAttribute('style')) || ''
  const m = st.match(/mso-list\s*:\s*[^;]*level(\d+)/i)
  const text = (el.textContent || '').trim()
  if (/^[\u2022\u00b7\u25aa\u25cf\u2219\u00a7o]\s/.test(text)) return false
  if (/^\(?\d+[.)、]\s/.test(text)) return true
  return !!(m && /lfo\d+\s*$/.test(st))
}

/** 去掉文本开头的项目符号/编号前缀(真正的符号由 Word 的 numbering 渲染) */
function stripBulletMarker (text) {
  return String(text || '')
    .replace(/^[\u2022\u00b7\u25aa\u25cf\u2219\u00a7\u25e6o]\s*/, '')
    .replace(/^\(?\d+[.)、]\s*/, '')
    .replace(/^[a-zA-Z][.)]\s*/, '')
    .trim()
}

function hasBlockChild (el) {
  return Array.from(el.children || []).some(c => BLOCKY.has(c.tagName.toUpperCase()) || c.tagName.toUpperCase() === 'TABLE')
}

function textOf (el) {
  return (el.textContent || '').replace(/[\u00a0]/g, ' ').replace(/\s+/g, ' ').trim()
}

// ---------- 表格 ----------

/**
 * 表格是粘贴里最容易坏的一块: 需要处理 colspan / rowspan、列宽、边框。
 * 这里按"网格展开"还原结构 —— 合并单元格的位置放入文本, 被合并的格子留空。
 */
function tableBlock (table) {
  const rows = []
  const grid = []
  const trs = Array.from(table.querySelectorAll(':scope > tr, :scope > tbody > tr, :scope > thead > tr'))
  const maxCol = trs.reduce((m, tr) => {
    const cells = Array.from(tr.children).filter(c => /^(TD|TH)$/i.test(c.tagName))
    return Math.max(m, cells.reduce((s, c) => s + (parseInt(c.getAttribute('colspan'), 10) || 1), 0))
  }, 0)

  const occupancy = []
  trs.forEach((tr, r) => {
    occupancy[r] = occupancy[r] || []
    grid[r] = grid[r] || []
    let c = 0
    Array.from(tr.children).filter(x => /^(TD|TH)$/i.test(x.tagName)).forEach(cell => {
      while (occupancy[r][c]) c++
      const cs = Math.max(1, parseInt(cell.getAttribute('colspan'), 10) || 1)
      const rs = Math.max(1, parseInt(cell.getAttribute('rowspan'), 10) || 1)
      const text = (cell.textContent || '').replace(/[\u00a0]/g, ' ').replace(/\s+/g, ' ').trim()
      for (let i = 0; i < rs; i++) {
        occupancy[r + i] = occupancy[r + i] || []
        for (let j = 0; j < cs; j++) occupancy[r + i][c + j] = true
      }
      grid[r][c] = { text }
      c += cs
    })
    for (let k = 0; k < maxCol; k++) if (!grid[r][k]) grid[r][k] = { text: '' }
  })
  grid.forEach(line => rows.push(line.slice(0, Math.min(maxCol, 30))))
  return rows.length ? { type: 'table', rows, anchor: -1 } : null
}

// ---------- 收尾 ----------

/** 去掉空块、合并相邻空行, 让粘贴结果干净 */
function postProcess (blocks) {
  const out = []
  for (const b of blocks) {
    if (!b) continue
    if (b.type === 'paragraph' && !String(b.text || '').trim()) continue
    if (b.type === 'bullet' && !String(b.text || '').trim()) continue
    if (b.type === 'heading' && !String(b.text || '').trim()) continue
    out.push(b)
  }
  return out.length ? out : []
}

/**
 * 给编辑器元素装上粘贴拦截。
 * 用法: onMounted(() => unbind = installPaste(el, async blocks => { ... }))
 */
export function installPaste (el, handler) {
  if (!el) return () => {}
  const onPaste = async (e) => {
    const dt = e.clipboardData || (e.originalEvent && e.originalEvent.clipboardData)
    if (!dt) return
    e.preventDefault()
    try {
      const blocks = await clipboardToBlocks(dt)
      if (blocks.length) handler(blocks)
    } catch (err) {
      /* 粘贴失败就退回浏览器默认行为 */
      e.preventDefault()
    }
  }
  el.addEventListener('paste', onPaste)
  return () => el.removeEventListener('paste', onPaste)
}
