<script setup>
/**
 * Word 在线编辑器 - 基于 Tiptap 2.x (ProseMirror, 命令式 API)
 *
 * 像 Google Docs / 腾讯文档 / 飞书文档 的体验:
 *   1. 富文本光标在文档流里自由移动
 *   2. 选中文字时弹出气泡工具栏
 *   3. 顶部三段式工具栏 + 状态栏
 *   4. 快捷键 Ctrl+Z/Y/B/I/U/K 全部对齐腾讯文档
 *   5. 拖拽图片/链接, 表格, 列表, 引用块, 代码块
 *
 * 数据模型: model.html (HTML 字符串), 兼容旧 model.blocks。
 */
import { nextTick, onBeforeUnmount, onMounted, reactive, ref, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Editor } from '@tiptap/core'
import StarterKit from '@tiptap/starter-kit'
import Underline from '@tiptap/extension-underline'
import Link from '@tiptap/extension-link'
import Image from '@tiptap/extension-image'
import TextAlign from '@tiptap/extension-text-align'
import TextStyle from '@tiptap/extension-text-style'
import Color from '@tiptap/extension-color'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableCell from '@tiptap/extension-table-cell'
import TableHeader from '@tiptap/extension-table-header'
import Highlight from '@tiptap/extension-highlight'
import Code from '@tiptap/extension-code'
import Placeholder from '@tiptap/extension-placeholder'
import TaskList from '@tiptap/extension-task-list'
import TaskItem from '@tiptap/extension-task-item'
import Superscript from '@tiptap/extension-superscript'
import Subscript from '@tiptap/extension-subscript'
import { uploadImage } from '../../api/upload'
import { useEditorShortcuts } from '../../composables/useEditorHistory'
import EditorToolbar, { TbButton, TbGroup, TbSep, TbSelect, TbColor } from './EditorToolbar.vue'
import {
  IconUndo, IconRedo, IconBrush, IconFind,
  IconBold, IconItalic, IconUnderline, IconStrike, IconSup, IconSub,
  IconAlignLeft, IconAlignCenter, IconAlignRight, IconAlignJustify,
  IconBulletList, IconOrderedList, IconTaskList, IconQuote, IconCode,
  IconLink, IconImage, IconTable, IconPageBreak, IconSymbol
} from './icons'

const props = defineProps({ model: { type: Object, required: true } })
const emit = defineEmits(['change', 'save'])
const { t } = useI18n()

/* blocks → HTML (兼容旧 model) */
function blocksToHtml(blocks) {
  if (!Array.isArray(blocks) || !blocks.length) return ''
  const esc = (s) => String(s || '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const parts = []
  for (const b of blocks) {
    if (b.type === 'heading') {
      const lvl = Math.max(1, Math.min(6, b.level || 1))
      parts.push(`<h${lvl}>${esc(b.text)}</h${lvl}>`)
    } else if (b.type === 'bullet') {
      const tag = b.ordered ? 'ol' : 'ul'
      const li = `<li>${esc(b.text)}</li>`
      if (parts.length && parts[parts.length - 1].startsWith(`<${tag}>`)) {
        parts[parts.length - 1] += li
      } else {
        parts.push(`<${tag}>${li}</${tag}>`)
      }
    } else if (b.type === 'image') {
      parts.push(`<p><img src="${esc(b.url)}" alt=""></p>`)
    } else if (b.type === 'pagebreak') {
      parts.push(`<hr data-pagebreak="true">`)
    } else if (b.type === 'table') {
      const rows = b.rows || []
      const trs = rows.map(row => `<tr>${(row || []).map(c => `<td>${esc(c.text || '')}</td>`).join('')}</tr>`).join('')
      parts.push(`<table><tbody>${trs}</tbody></table>`)
    } else {
      const style = []
      if (b.align && b.align !== 'left') style.push(`text-align: ${b.align}`)
      if (b.bold) style.push('font-weight: 700')
      if (b.italic) style.push('font-style: italic')
      if (b.underline) style.push('text-decoration: underline')
      if (b.color) style.push(`color: #${b.color}`)
      const s = style.length ? ` style="${style.join(';')}"` : ''
      parts.push(`<p${s}>${esc(b.text)}</p>`)
    }
  }
  return parts.join('')
}

const canvasEl = ref(null)
let editor = null
const isReady = ref(false)
const stats = reactive({ chars: 0, words: 0, lines: 0, selected: 0 })

const fileInput = ref(null)
const findOpen = ref(false)
const findQuery = ref('')
const findReplaceWith = ref('')
const findCursor = ref(0)
const findMatches = ref([])

const SIZES = [9, 10, 11, 12, 14, 16, 18, 20, 24, 28, 32, 36, 48]
const FONTS = ['宋体', '黑体', '微软雅黑', '楷体', '思源黑体', 'Times New Roman', 'Arial', 'Helvetica', 'Georgia', 'Verdana', 'Tahoma', 'Courier New', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', 'SimSun', 'SimHei', 'KaiTi']

/* 给 TbSelect 用的 options 数组 */
const PARA_OPTIONS = computed(() => {
  const opts = [{ value: 'p', label: t('myDocs.paragraph') }]
  for (let l = 1; l <= 6; l++) opts.push({ value: l, label: `Heading ${l}` })
  return opts
})
const FONT_OPTIONS = computed(() => FONTS.map(f => ({ value: f, label: f })))
const SIZE_OPTIONS = SIZES.map(s => ({ value: s, label: String(s) }))
const SYMBOL_OPTIONS = [
  { value: '©', label: '© ©  ® ®  ™ ™  § §  ¶ ¶' },
  { value: '£', label: '£ £  ¥ ¥  € €  ¢ ¢  ₩ ₩' },
  { value: '★', label: '★ ★  ☆ ☆  ✓ ✓  ✗ ✗  ❤ ❤' },
  { value: 'α', label: 'α α  β β  γ γ  δ δ  π π' },
  { value: '—', label: '— —  … …  · ·  ※ ※  § §' }
]

const getInitialHtml = () => {
  if (typeof props.model.html === 'string' && props.model.html) return props.model.html
  if (Array.isArray(props.model.blocks) && props.model.blocks.length) {
    return blocksToHtml(props.model.blocks)
  }
  return ''
}

const recalc = () => {
  if (!editor) return
  const text = editor.getText() || ''
  stats.chars = Array.from(text).length
  stats.words = text.trim() ? text.trim().split(/\s+/).filter(Boolean).length : 0
  stats.lines = text ? text.split('\n').length : 0
  const { from, to } = editor.state.selection
  stats.selected = from === to ? 0 : Array.from(editor.state.doc.textBetween(from, to, ' ', ' ')).length
}

const getStyleAttr = (name) => {
  if (!editor) return ''
  if (name === 'paragraph') {
    for (let l = 1; l <= 6; l++) {
      if (editor.isActive('heading', { level: l })) return String(l)
    }
    return 'p'
  }
  if (name === 'fontFamily') return editor.getAttributes('textStyle').fontFamily || ''
  if (name === 'fontSize') {
    const v = editor.getAttributes('textStyle').fontSize
    return v ? String(v).replace('pt', '') : ''
  }
  if (name === 'color') return editor.getAttributes('textStyle').color || ''
  return ''
}

const setFontSize = (pt) => {
  if (!editor) return
  editor.chain().focus().setMark('textStyle', { fontSize: pt ? `${pt}pt` : null }).run()
}

const cmd = {
  undo: () => { editor?.chain().focus().undo().run(); emit('change') },
  redo: () => { editor?.chain().focus().redo().run(); emit('change') },
  toggleBold: () => { editor?.chain().focus().toggleBold().run(); emit('change') },
  toggleItalic: () => { editor?.chain().focus().toggleItalic().run(); emit('change') },
  toggleUnderline: () => { editor?.chain().focus().toggleUnderline().run(); emit('change') },
  toggleStrike: () => { editor?.chain().focus().toggleStrike().run(); emit('change') },
  toggleCode: () => { editor?.chain().focus().toggleCode().run(); emit('change') },
  toggleHighlight: () => { editor?.chain().focus().toggleHighlight().run(); emit('change') },
  toggleSuperscript: () => { editor?.chain().focus().toggleSuperscript().run(); emit('change') },
  toggleSubscript: () => { editor?.chain().focus().toggleSubscript().run(); emit('change') },
  toggleBulletList: () => { editor?.chain().focus().toggleBulletList().run(); emit('change') },
  toggleOrderedList: () => { editor?.chain().focus().toggleOrderedList().run(); emit('change') },
  toggleTaskList: () => { editor?.chain().focus().toggleTaskList().run(); emit('change') },
  toggleBlockquote: () => { editor?.chain().focus().toggleBlockquote().run(); emit('change') },
  toggleCodeBlock: () => { editor?.chain().focus().toggleCodeBlock().run(); emit('change') },
  alignLeft: () => { editor?.chain().focus().setTextAlign('left').run(); emit('change') },
  alignCenter: () => { editor?.chain().focus().setTextAlign('center').run(); emit('change') },
  alignRight: () => { editor?.chain().focus().setTextAlign('right').run(); emit('change') },
  alignJustify: () => { editor?.chain().focus().setTextAlign('justify').run(); emit('change') },
  setHeading: (level) => {
    if (!editor) return
    if (level === 'p' || level === 0) {
      editor.chain().focus().setParagraph().run()
    } else {
      editor.chain().focus().toggleHeading({ level: Number(level) }).run()
    }
    emit('change')
  },
  setFontFamily: (v) => {
    if (!editor) return
    editor.chain().focus().setMark('textStyle', { fontFamily: v || null }).run()
    emit('change')
  },
  setColor: (v) => {
    if (!editor) return
    editor.chain().focus().setColor(v).run()
    emit('change')
  },
  setHighlight: (v) => {
    if (!editor) return
    editor.chain().focus().toggleHighlight({ color: v }).run()
    emit('change')
  },
  insertTable: (rows = 3, cols = 3) => {
    if (!editor) return
    editor.chain().focus().insertTable({ rows, cols, withHeaderRow: true }).run()
    emit('change')
  },
  insertHr: () => { editor?.chain().focus().setHorizontalRule().run(); emit('change') },
  insertPageBreak: () => {
    if (!editor) return
    // 在当前位置插入分页符(用 hr + 样式不可靠, 改用插入段落)
    editor.chain().focus().insertContent('<p style="page-break-before: always"></p>').run()
    emit('change')
  },
  pickImage: () => fileInput.value && fileInput.value.click()
}

const can = {
  undo: () => editor?.can().chain().focus().undo().run() ?? false,
  redo: () => editor?.can().chain().focus().redo().run() ?? false
}

/* 工具栏上"格式刷"按钮占位, 真正格式刷是 PPT/Excel 的; 这里只是图标按钮 */
const disarm = () => { /* no-op for now */ }

/* 当前样式指示(状态栏右侧) */
const currentStyleLabel = computed(() => {
  if (!editor) return ''
  if (isActive('heading', { level: 1 })) return 'Heading 1'
  if (isActive('heading', { level: 2 })) return 'Heading 2'
  if (isActive('heading', { level: 3 })) return 'Heading 3'
  if (isActive('heading', { level: 4 })) return 'Heading 4'
  if (isActive('heading', { level: 5 })) return 'Heading 5'
  if (isActive('heading', { level: 6 })) return 'Heading 6'
  if (isActive('bulletList')) return t('myDocs.bulletList')
  if (isActive('orderedList')) return t('myDocs.orderedList')
  if (isActive('taskList')) return t('myDocs.taskList')
  if (isActive('blockquote')) return t('myDocs.quote')
  if (isActive('codeBlock')) return t('myDocs.codeBlock')
  return t('myDocs.paragraph')
})

/* 插入表格(选择行 × 列) */
const openInsertTable = () => {
  // 简化: 直接 3×3, 后期改成 4×5 网格选择器
  cmd.insertTable(3, 3)
}

/* 特殊字符插入 */
const insertSymbol = (ch) => { editor?.chain().focus().insertContent(ch).run(); emit('change') }
const SYMBOL_GROUPS = [
  ['©', '®', '™', '§', '¶'],
  ['£', '¥', '€', '¢', '₩'],
  ['α', 'β', 'γ', 'δ', 'π'],
  ['★', '☆', '✓', '✗', '❤']
]

const isActive = (name, attrs) => !!editor?.isActive(name, attrs)

const setLink = () => {
  if (!editor) return
  const prev = editor.getAttributes('link').href || ''
  const url = window.prompt(t('myDocs.linkPrompt'), prev || 'https://')
  if (url === null) return
  if (url === '') {
    editor.chain().focus().extendMarkRange('link').unsetLink().run()
  } else {
    editor.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
  }
  emit('change')
}

const onImagePicked = async (e) => {
  const f = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!f) return
  try {
    const { url } = await uploadImage(f, 'mydoc')
    editor?.chain().focus().setImage({ src: url, alt: f.name }).run()
    emit('change')
  } catch (err) { /* 静默 */ }
}

/* 查找替换:基于 editor.getText 文本搜索,selection 跳转 */
const rebuildFind = () => {
  if (!editor || !findQuery.value) { findMatches.value = []; return }
  const text = editor.getText()
  const q = findQuery.value
  const matches = []
  let i = 0
  while ((i = text.indexOf(q, i)) >= 0) {
    matches.push({ from: i, to: i + q.length })
    i += q.length || 1
  }
  findMatches.value = matches
  if (matches.length) findCursor.value = Math.min(findCursor.value, matches.length - 1)
}
const openFind = () => { findOpen.value = true; rebuildFind() }
const closeFind = () => { findOpen.value = false }
const gotoNext = () => {
  if (!findMatches.value.length || !editor) return
  findCursor.value = (findCursor.value + 1) % findMatches.value.length
  jumpToCurrent()
}
const gotoPrev = () => {
  if (!findMatches.value.length || !editor) return
  findCursor.value = (findCursor.value - 1 + findMatches.value.length) % findMatches.value.length
  jumpToCurrent()
}
// 通过逐个 text node 找位置(ProseMirror 位置 = node 起点 + offset)
const jumpToCurrent = () => {
  if (!editor || !findMatches.value.length) return
  const m = findMatches.value[findCursor.value]
  if (!m) return
  const q = findQuery.value
  let pos = 0
  let target = null
  editor.state.doc.descendants((node, nodePos) => {
    if (target) return false
    if (node.isText) {
      const idx = node.text.indexOf(q)
      if (idx >= 0) {
        target = { from: nodePos + idx, to: nodePos + idx + q.length }
        return false
      }
    }
    return true
  })
  if (target) {
    editor.commands.setTextSelection(target.from)
    editor.commands.setTextSelection(target.to)
  }
}
const doReplace = () => {
  if (!editor || !findMatches.value.length) return
  jumpToCurrent()
  const { from, to } = editor.state.selection
  if (from === to) return
  editor.chain().focus().insertContentAt({ from, to }, findReplaceWith.value || '').run()
  emit('change')
  rebuildFind()
}
const doReplaceAll = () => {
  if (!editor) return
  const q = findQuery.value
  if (!q) return
  const text = editor.getText()
  let newText = ''
  let i = 0
  while (i < text.length) {
    const idx = text.indexOf(q, i)
    if (idx < 0) { newText += text.slice(i); break }
    newText += text.slice(i, idx) + (findReplaceWith.value || '')
    i = idx + q.length
  }
  if (newText !== text) {
    editor.commands.setContent('<p>' + newText.split('\n').map(l => l || '<br>').join('</p><p>') + '</p>', true)
    emit('change')
  }
  rebuildFind()
}

useEditorShortcuts({
  enabled: () => isReady.value,
  handlers: {
    'ctrl+b': () => cmd.toggleBold(),
    'ctrl+i': () => cmd.toggleItalic(),
    'ctrl+u': () => cmd.toggleUnderline(),
    'ctrl+z': () => cmd.undo(),
    'ctrl+y': () => cmd.redo(),
    'ctrl+shift+z': () => cmd.redo(),
    'ctrl+k': () => setLink(),
    'ctrl+f': () => openFind(),
    'ctrl+s': () => emit('save')
  }
})

onMounted(() => {
  nextTick(() => {
    if (!canvasEl.value) { console.warn('[WordEditor] canvasEl missing'); return }
    console.log('[WordEditor] creating Tiptap, html=', getInitialHtml()?.slice(0, 50))
    try {
      editor = new Editor({
        element: canvasEl.value,
        extensions: [
          StarterKit.configure({ heading: { levels: [1, 2, 3, 4, 5, 6] } }),
          Underline,
          Link.configure({ openOnClick: false, HTMLAttributes: { rel: 'noopener noreferrer' } }),
          Image.configure({ allowBase64: true, inline: false }),
          TextAlign.configure({ types: ['heading', 'paragraph'] }),
          TextStyle,
          Color,
          Table.configure({ resizable: true }),
          TableRow,
          TableCell,
          TableHeader,
          Highlight.configure({ multicolor: true }),
          Code,
          TaskList,
          TaskItem.configure({ nested: true }),
          Superscript,
          Subscript,
          Placeholder.configure({ placeholder: t('myDocs.placeholderWrite') })
        ],
        content: getInitialHtml() || '',
        onUpdate: ({ editor: ed }) => {
          props.model.html = ed.getHTML()
          emit('change')
          recalc()
        },
        onSelectionUpdate: () => recalc(),
        onCreate: () => { console.log('[WordEditor] Tiptap created'); isReady.value = true; recalc() }
      })
    } catch (e) {
      console.error('[WordEditor] create error:', e)
    }
  })
})

onBeforeUnmount(() => { if (editor) editor.destroy() })
</script>

<template>
  <div class="word-editor" v-show="isReady">
    <!-- 顶部主工具栏: 撤销/重做/查找/格式刷/段落/字体/字号/粗斜下划线/颜色/对齐/列表/引用代码/链接图片表格 -->
    <EditorToolbar>
      <template #start>
        <TbGroup>
          <TbButton :disabled="!can.undo()" :title="$t('myDocs.shortcutUndo')" @click="cmd.undo"><IconUndo /></TbButton>
          <TbButton :disabled="!can.redo()" :title="$t('myDocs.shortcutRedo')" @click="cmd.redo"><IconRedo /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.formatPainter')" :type="'button'" @click="disarm"><IconBrush /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.findReplace')" @click="openFind"><IconFind /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbSelect
            :model-value="getStyleAttr('paragraph')"
            :options="PARA_OPTIONS"
            :placeholder="$t('myDocs.paragraphStyle')"
            width="120px"
            @change="cmd.setHeading"
          />
          <TbSelect
            :model-value="getStyleAttr('fontFamily')"
            :options="FONT_OPTIONS"
            :placeholder="$t('myDocs.fontFamily')"
            width="120px"
            @change="v => cmd.setFontFamily(v)"
          />
          <TbSelect
            :model-value="getStyleAttr('fontSize')"
            :options="SIZE_OPTIONS"
            :placeholder="$t('myDocs.fontSize')"
            width="64px"
            @change="v => setFontSize(v)"
          />
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :active="isActive('bold')" :title="$t('myDocs.shortcutBold')" @click="cmd.toggleBold"><IconBold /></TbButton>
          <TbButton :active="isActive('italic')" :title="$t('myDocs.shortcutItalic')" @click="cmd.toggleItalic"><IconItalic /></TbButton>
          <TbButton :active="isActive('underline')" :title="$t('myDocs.shortcutUnderline')" @click="cmd.toggleUnderline"><IconUnderline /></TbButton>
          <TbButton :active="isActive('strike')" :title="$t('myDocs.strike')" @click="cmd.toggleStrike"><IconStrike /></TbButton>
          <TbButton :active="isActive('superscript')" :title="$t('myDocs.superscript')" @click="cmd.toggleSuperscript"><IconSup /></TbButton>
          <TbButton :active="isActive('subscript')" :title="$t('myDocs.subscript')" @click="cmd.toggleSubscript"><IconSub /></TbButton>
          <TbColor :title="$t('myDocs.fontColor')" :value="getStyleAttr('color') || '#000000'" @change="v => cmd.setColor(v)" />
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :active="isActive('paragraph', { textAlign: 'left' })" :title="$t('myDocs.alignLeft')" @click="cmd.alignLeft"><IconAlignLeft /></TbButton>
          <TbButton :active="isActive('paragraph', { textAlign: 'center' })" :title="$t('myDocs.alignCenter')" @click="cmd.alignCenter"><IconAlignCenter /></TbButton>
          <TbButton :active="isActive('paragraph', { textAlign: 'right' })" :title="$t('myDocs.alignRight')" @click="cmd.alignRight"><IconAlignRight /></TbButton>
          <TbButton :active="isActive('paragraph', { textAlign: 'justify' })" :title="$t('myDocs.alignJustify')" @click="cmd.alignJustify"><IconAlignJustify /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :active="isActive('bulletList')" :title="$t('myDocs.bulletList')" @click="cmd.toggleBulletList"><IconBulletList /></TbButton>
          <TbButton :active="isActive('orderedList')" :title="$t('myDocs.orderedList')" @click="cmd.toggleOrderedList"><IconOrderedList /></TbButton>
          <TbButton :active="isActive('taskList')" :title="$t('myDocs.taskList')" @click="cmd.toggleTaskList"><IconTaskList /></TbButton>
          <TbButton :active="isActive('blockquote')" :title="$t('myDocs.quote')" @click="cmd.toggleBlockquote"><IconQuote /></TbButton>
          <TbButton :active="isActive('codeBlock')" :title="$t('myDocs.codeBlock')" @click="cmd.toggleCodeBlock"><IconCode /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.linkBtn')" @click="setLink"><IconLink /></TbButton>
          <TbButton :title="$t('myDocs.insertImage')" @click="cmd.pickImage"><IconImage /></TbButton>
          <TbButton :title="$t('myDocs.blkAddTable')" @click="openInsertTable"><IconTable /></TbButton>
        </TbGroup>
        <input ref="fileInput" type="file" accept="image/*" hidden @change="onImagePicked" />
      </template>
    </EditorToolbar>

    <!-- 第二行: 插入 + 页面 -->
    <EditorToolbar>
      <template #start>
        <TbGroup>
          <TbButton :title="$t('myDocs.insertHr')" @click="cmd.insertHr">{{ $t('myDocs.hr') }}</TbButton>
          <TbButton :title="$t('myDocs.pageBreak')" @click="cmd.insertPageBreak"><IconPageBreak /></TbButton>
          <TbSelect
            :model-value="''"
            :options="SYMBOL_OPTIONS"
            :placeholder="$t('myDocs.insertSymbol')"
            width="120px"
            @change="insertSymbol"
          />
        </TbGroup>
      </template>
    </EditorToolbar>

    <!-- 查找替换面板 -->
    <div v-if="findOpen" class="find-bar">
      <el-input v-model="findQuery" size="small" :placeholder="$t('myDocs.findPlaceholder')" class="w200" @input="rebuildFind" clearable />
      <el-input v-model="findReplaceWith" size="small" :placeholder="$t('myDocs.replaceWithPlaceholder')" class="w200" />
      <span class="fp-count">{{ findMatches.length ? `${findCursor + 1}/${findMatches.length}` : '0/0' }}</span>
      <el-button size="small" @click="gotoPrev">↑</el-button>
      <el-button size="small" @click="gotoNext">↓</el-button>
      <el-button size="small" @click="doReplace" type="primary" plain>{{ $t('myDocs.replaceOne') }}</el-button>
      <el-button size="small" @click="doReplaceAll" type="primary" plain>{{ $t('myDocs.replaceAll') }}</el-button>
      <el-button size="small" link @click="closeFind">×</el-button>
    </div>

    <!-- 文档画布:像 Word 一样的 A4 页面 -->
    <div class="page-scroll">
      <div class="page">
        <div ref="canvasEl" class="word-canvas" v-show="isReady"></div>
      </div>
    </div>

    <!-- 状态栏 -->
    <div class="statusbar">
      <span class="st-item" :title="$t('myDocs.unitChars')">{{ stats.chars }} {{ $t('myDocs.unitChars') }}</span>
      <span class="st-sep" />
      <span class="st-item">{{ stats.words }} {{ $t('myDocs.unitWords') }}</span>
      <span class="st-sep" />
      <span class="st-item">{{ stats.lines }} {{ $t('myDocs.unitLines') }}</span>
      <template v-if="stats.selected">
        <span class="st-sep" />
        <span class="st-item">{{ stats.selected }} {{ $t('myDocs.unitSelected') }}</span>
      </template>
      <span class="grow" />
      <span class="st-item">{{ currentStyleLabel }}</span>
      <span class="st-sep" />
      <span class="st-item">100%</span>
    </div>
  </div>
</template>

<style scoped>
.word-editor { display: flex; flex-direction: column; height: 100%; background: #f0f0f3; }
.word-loading { padding: 40px; text-align: center; color: var(--c-text-3); }

.find-bar { display: flex; align-items: center; gap: 6px; padding: 6px 10px; background: #f7f8fa; border-bottom: 1px solid var(--c-border); }
.fp-count { font-size: 12px; color: var(--c-text-3); min-width: 50px; }
.w200 { width: 200px; }

.page-scroll { flex: 1; overflow: auto; padding: 24px 0; display: flex; justify-content: center; }
.page { background: #fff; width: 794px; min-height: 1000px; box-shadow: 0 2px 12px rgba(0,0,0,.10); padding: 80px 90px; box-sizing: border-box; }
.word-canvas { font-size: 14px; line-height: 1.7; color: #1f1f1f; min-height: 700px; }
.word-canvas :deep(.ProseMirror) { outline: none; min-height: 700px; }
.word-canvas :deep(p) { margin: 0 0 10px; }
.word-canvas :deep(h1) { font-size: 28px; font-weight: 700; margin: 18px 0 10px; }
.word-canvas :deep(h2) { font-size: 22px; font-weight: 700; margin: 16px 0 8px; }
.word-canvas :deep(h3) { font-size: 18px; font-weight: 600; margin: 14px 0 6px; }
.word-canvas :deep(ul), .word-canvas :deep(ol) { padding-left: 26px; margin: 0 0 10px; }
.word-canvas :deep(ul[data-type="taskList"]) { padding-left: 0; list-style: none; }
.word-canvas :deep(ul[data-type="taskList"] li) { display: flex; align-items: center; gap: 6px; }
.word-canvas :deep(ul[data-type="taskList"] li > label) { margin: 0; }
.word-canvas :deep(ul[data-type="taskList"] li > div) { flex: 1; }
.word-canvas :deep(blockquote) { border-left: 4px solid #ccc; padding-left: 12px; color: #555; margin: 10px 0; }
.word-canvas :deep(pre) { background: #f5f5f5; padding: 10px; border-radius: 4px; font-family: 'Courier New', monospace; font-size: 12px; overflow-x: auto; }
.word-canvas :deep(code) { background: rgba(0,0,0,.06); padding: 1px 4px; border-radius: 3px; font-size: 90%; }
.word-canvas :deep(table) { border-collapse: collapse; width: 100%; margin: 10px 0; }
.word-canvas :deep(td), .word-canvas :deep(th) { border: 1px solid #d0d0d0; padding: 6px 10px; min-width: 60px; vertical-align: top; }
.word-canvas :deep(th) { background: #f5f5f5; font-weight: 600; }
.word-canvas :deep(img) { max-width: 100%; height: auto; border-radius: 4px; }
.word-canvas :deep(a) { color: #1a73e8; text-decoration: underline; }
.word-canvas :deep(p.is-editor-empty:first-child::before) { content: attr(data-placeholder); color: #aaa; float: left; height: 0; pointer-events: none; }
.word-canvas :deep(hr) { border: 0; border-top: 1px solid #ccc; margin: 12px 0; }
.word-canvas :deep(sup) { font-size: 70%; vertical-align: super; }
.word-canvas :deep(sub) { font-size: 70%; vertical-align: sub; }
.word-canvas :deep(mark) { background: #fff59d; padding: 0 2px; border-radius: 2px; }
.word-canvas :deep(table p) { margin: 0; }

.statusbar { display: flex; align-items: center; gap: 8px; padding: 4px 10px; background: #fff; border-top: 1px solid var(--c-border); font-size: 12px; color: var(--c-text-3); }
.st-sep { width: 1px; height: 12px; background: var(--c-border); }
.grow { flex: 1; }

/* 工具栏覆盖默认变量, 让工具栏背景是白偏冷调 */
.word-editor :deep(.editor-toolbar) { background: #fcfcfd; border-bottom: 1px solid #e8e8ec; }
.word-editor :deep(.et-row) { padding: 4px 8px; min-height: 32px; gap: 2px; }
</style>
