/**
 * 通用 SVG 图标库(24×24 viewBox, stroke 1.6)
 * 风格: 线性图标, 类似 WPS / 腾讯文档 / Microsoft Office Web
 * 用法: <component :is="IconUndo" />  或 <IconUndo />
 */
import { h } from 'vue'

const stroke = (props) => ({
  fill: 'none',
  stroke: 'currentColor',
  'stroke-width': 1.6,
  'stroke-linecap': 'round',
  'stroke-linejoin': 'round',
  width: 16,
  height: 16,
  viewBox: '0 0 24 24'
})

const make = (paths) => (props) => h('svg', stroke(props), paths.map(d => h('path', { d })))
const makeMulti = (nodes) => (props) => h('svg', stroke(props), nodes)

// === 撤销 / 重做 / 格式刷 / 查找 ===
export const IconUndo = make(['M3 7h13a4 4 0 0 1 0 8H8M3 7l4-4M3 7l4 4'])
export const IconRedo = make(['M21 7H8a4 4 0 0 0 0 8h8M21 7l-4-4M21 7l-4 4'])
export const IconBrush = make(['M9 21a2 2 0 0 0 2 2h2a2 2 0 0 0 2-2l1-5H8l1 5zM5 18a2 2 0 1 1 4 0M19 18a2 2 0 1 1-4 0M5 18V8a4 4 0 0 1 4-4h6a4 4 0 0 1 4 4v10'])
export const IconFind = make(['M11 19a8 8 0 1 1 0-16 8 8 0 0 1 0 16zM21 21l-4.35-4.35'])
export const IconReplace = make(['M3 8h10M9 4L5 8l4 4M21 16H11M15 20l4-4-4-4'])

// === 字体格式 ===
export const IconBold = make(['M7 5h6a3.5 3.5 0 0 1 0 7H7V5zM7 12h7a3.5 3.5 0 0 1 0 7H7v-7z'])
export const IconItalic = make(['M19 5h-9M14 19H5M15 5L9 19'])
export const IconUnderline = make(['M7 4v7a5 5 0 0 0 10 0V4M5 20h14'])
export const IconStrike = make(['M5 12h14M16 6a4 4 0 0 0-4-2H8M8 18h4a4 4 0 0 1 4 2'])
export const IconSup = make(['M3 18l8-12M3 6l8 12M11 6h4v12M19 6h2v12'])
export const IconSub = makeMulti([h('path', { d: 'M3 18l8-12M3 6l8 12M11 6h4v12' }), h('path', { d: 'M19 6h2v6h-2z' })])

// === 对齐 ===
export const IconAlignLeft = make(['M3 5h18M3 9h12M3 13h18M3 17h12M3 21h18'])
export const IconAlignCenter = make(['M3 5h18M6 9h12M3 13h18M6 17h12M3 21h18'])
export const IconAlignRight = make(['M3 5h18M9 9h12M3 13h18M9 17h12M3 21h18'])
export const IconAlignJustify = make(['M3 5h18M3 9h18M3 13h18M3 17h18M3 21h18'])

// === 列表 / 引用 / 代码 ===
export const IconBulletList = make(['M3 6h2M3 12h2M3 18h2M8 6h13M8 12h13M8 18h13'])
export const IconOrderedList = makeMulti([h('path', { d: 'M3 6h2M3 12h2M3 18h2M8 6h13M8 12h13M8 18h13' }), h('path', { d: 'M5 4l1 1v3M5 8l-1 1v-3' })])
export const IconTaskList = make(['M9 5l-1 1-3-3M9 12l-1 1-3-3M9 19l-1 1-3-3M15 5h6M15 12h6M15 19h6'])
export const IconQuote = make(['M7 7h4v4H7zM7 11c0 2 2 4 4 4M13 7h4v4h-4zM13 11c0 2 2 4 4 4'])
export const IconCode = make(['M9 8l-4 4 4 4M15 8l4 4-4 4M14 5l-4 14'])

// === 插入 ===
export const IconLink = make(['M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1'])
export const IconImage = makeMulti([h('rect', { x: 3, y: 3, width: 18, height: 18, rx: 2 }), h('circle', { cx: 9, cy: 9, r: 2 }), h('path', { d: 'M3 16l5-5 5 5 4-4 4 4' })])
export const IconTable = make(['M3 5h18v14H3zM3 9h18M3 13h18M3 17h18M9 5v14M15 5v14'])
export const IconPageBreak = make(['M3 12h18M3 12l4 4M3 12l4-4M21 12l-4 4M21 12l-4-4'])
export const IconHeader = make(['M4 4h16v3H4zM4 10h7M4 14h7M4 18h16'])
export const IconPageNum = make(['M3 5h4v14H3zM8 5h12M8 19h12'])
export const IconComment = make(['M21 11a3 3 0 0 0-3-3H9a3 3 0 0 0-3 3v5a3 3 0 0 0 3 3h2l4 3v-3h3a3 3 0 0 0 3-3v-5z'])
export const IconSymbol = make(['M3 4h7v7H3zM14 4h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z'])

// === 颜色相关 ===
export const IconChevronDown = make(['M5 9l7 7 7-7'])
export const IconChevronUp = make(['M5 15l7-7 7 7'])
export const IconPalette = make(['M12 22a10 10 0 1 1 10-10c0 3-2 4-4 4h-2a2 2 0 0 0-2 2c0 1 1 2 1 4M7 11a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM11 7a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM16 7a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM16 15a1 1 0 1 0 0-2 1 1 0 0 0 0 2z'])

// === Excel ===
export const IconMerge = make(['M4 5h6v6H4zM14 5h6v6h-6zM9 11v6M15 11v6M4 17h6v4H4zM14 17h6v4h-6zM9 17h6M9 5h6'])
export const IconNumber = make(['M9 4l-2 16M17 4l-2 16M4 9h16M3 15h18'])
export const IconCurrency = make(['M12 3v18M17 6a5 5 0 0 0-5-3 5 5 0 0 0-5 5c0 6 10 4 10 9a5 5 0 0 1-10 2'])
export const IconPercent = make(['M19 5L5 19M6 7a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM18 19a1 1 0 1 0 0-2 1 1 0 0 0 0 2z'])
export const IconDate = makeMulti([h('rect', { x: 3, y: 5, width: 18, height: 16, rx: 2 }), h('path', { d: 'M3 9h18M8 3v4M16 3v4' })])
export const IconFreeze = make(['M3 3h18v18H3zM3 9h18M3 15h18M9 3v18M15 3v18'])
export const IconFilter = make(['M3 5h18l-7 9v6l-4-2v-4z'])
export const IconSort = make(['M3 6h12M3 12h8M3 18h4M17 4v16M13 16l4 4 4-4'])
export const IconMore = make(['M5 12a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM12 12a1 1 0 1 0 0-2 1 1 0 0 0 0 2zM19 12a1 1 0 1 0 0-2 1 1 0 0 0 0 2z'])

// === PPT ===
export const IconText = make(['M5 4h14M12 4v16M9 20h6'])
export const IconRect = make(['M4 5h16v14H4z'])
export const IconCircle = make(['M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z'])
export const IconArrow = make(['M4 12h15M14 6l6 6-6 6'])
export const IconLine = make(['M5 19L19 5'])
export const IconPlay = make(['M5 3v18l15-9L5 3z'])
export const IconAnimation = makeMulti([h('path', { d: 'M5 3l14 9-14 9V3z' }), h('path', { d: 'M5 7v10M9 7v10M13 7v10M17 7v10' })])
export const IconNotes = make(['M3 5h18v14H3zM7 9h10M7 13h10M7 17h6'])
export const IconLayout = makeMulti([h('rect', { x: 3, y: 3, width: 18, height: 18, rx: 1 }), h('path', { d: 'M3 9h18M3 15h18' })])
export const IconZoom = makeMulti([h('circle', { cx: 11, cy: 11, r: 6 }), h('path', { d: 'M21 21l-4-4M8 11h6' })])
export const IconRuler = make(['M3 17L17 3l4 4L7 21l-4-4zM7 13l2 2M11 9l2 2M15 5l2 2'])

export default {
  IconUndo, IconRedo, IconBrush, IconFind, IconReplace,
  IconBold, IconItalic, IconUnderline, IconStrike, IconSup, IconSub,
  IconAlignLeft, IconAlignCenter, IconAlignRight, IconAlignJustify,
  IconBulletList, IconOrderedList, IconTaskList, IconQuote, IconCode,
  IconLink, IconImage, IconTable, IconPageBreak, IconHeader, IconPageNum,
  IconComment, IconSymbol,
  IconChevronDown, IconChevronUp, IconPalette,
  IconMerge, IconNumber, IconCurrency, IconPercent, IconDate, IconFreeze,
  IconFilter, IconSort, IconMore,
  IconText, IconRect, IconCircle, IconArrow, IconLine, IconPlay,
  IconAnimation, IconNotes, IconLayout, IconZoom, IconRuler
}
