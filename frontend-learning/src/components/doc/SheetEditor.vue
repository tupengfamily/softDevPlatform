<script setup>
/**
 * Excel 在线编辑器 - 真表格体验
 *
 * 像 WPS / Excel Web / 腾讯文档表格 的体验:
 *   1. 真正的 HTML 表格(行高/列宽/单元格点击/双击编辑)
 *   2. 选区(点击 + Shift 扩展 + 拖拽 + Ctrl+A)
 *   3. 顶部工具栏: B/I/U/颜色/底色/字号/对齐/数字格式
 *   4. 公式栏: 显示当前单元格内容
 *   5. 快捷键: Ctrl+Z/Y/C/V/X/A/F/S/+方向键
 *   6. 行/列增删, 多工作表, 重命名, 状态栏
 *
 * 数据模型: model.sheets[i].{name, grid[r][c]={t,v,d,s}}
 *   保留旧结构以兼容 DocxCodec 字节级保真。
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useEditorShortcuts } from '../../composables/useEditorHistory'
import EditorToolbar, { TbButton, TbGroup, TbSep, TbSelect, TbColor } from './EditorToolbar.vue'
import {
  IconUndo, IconRedo, IconBrush, IconFind,
  IconBold, IconItalic, IconUnderline, IconStrike,
  IconAlignLeft, IconAlignCenter, IconAlignRight,
  IconNumber, IconCurrency, IconPercent, IconDate,
  IconMerge, IconFilter, IconSort, IconMore, IconChevronDown
} from './icons'

const props = defineProps({ model: { type: Object, required: true } })
const emit = defineEmits(['change', 'save'])
const { t } = useI18n()

const sheets = computed(() => (props.model?.sheets || []))
const activeSheet = ref(0)
const sheet = computed(() => sheets.value[activeSheet.value] || { name: 'Sheet1', grid: [] })

/* ===================== 选区 =====================
 * selection: { r, c } 单格; range: { r1, c1, r2, c2 } 范围
 */
const sel = reactive({ r: 0, c: 0 })
const range = reactive({ r1: 0, c1: 0, r2: 0, c2: 0 })
const editing = ref(null)
const editValue = ref('')

const isInRange = (r, c) => r >= range.r1 && r <= range.r2 && c >= range.c1 && c <= range.c2
const isSel = (r, c) => r === sel.r && c === sel.c
const normRange = () => {
  if (range.r1 > range.r2 || (range.r1 === range.r2 && range.c1 > range.c2)) {
    const t1 = range.r1, c1 = range.c1
    range.r1 = range.r2; range.c1 = range.c2
    range.r2 = t1; range.c2 = c1
  }
}

const ROW_H = 28
const COL_W = 110
const HEAD_W = 46
const SIZE_OPTIONS = [
  { value: '', label: '字号' },
  { value: 9, label: '9' }, { value: 10, label: '10' }, { value: 11, label: '11' },
  { value: 12, label: '12' }, { value: 14, label: '14' }, { value: 16, label: '16' },
  { value: 18, label: '18' }, { value: 20, label: '20' }, { value: 24, label: '24' },
  { value: 28, label: '28' }, { value: 36, label: '36' }
]
const colName = (i) => {
  let s = '', n = i
  do { s = String.fromCharCode(65 + (n % 26)) + s; n = Math.floor(n / 26) - 1 } while (n >= 0)
  return s
}

/* ===================== 撤销/重做 ===================== */
const history = reactive({ past: [], future: [] })
const record = () => {
  history.past.push(JSON.parse(JSON.stringify({ sheets: sheets.value })))
  if (history.past.length > 200) history.past.shift()
  history.future = []
}
const undo = () => {
  if (history.past.length <= 1) return
  const cur = history.past.pop()
  history.future.push(cur)
  const prev = history.past[history.past.length - 1]
  applyModel({ sheets: prev.sheets })
}
const redo = () => {
  const next = history.future.pop()
  if (!next) return
  history.past.push(next)
  applyModel({ sheets: next.sheets })
}
const applyModel = (m) => {
  if (!m) return
  Object.keys(props.model).forEach(k => delete props.model[k])
  Object.assign(props.model, m)
  emit('change')
  nextTick(focusActive)
}

// 初始化历史
watch(() => sheets.value.length, () => {
  if (!history.past.length) record()
}, { immediate: true })
const wrapChange = () => { record(); emit('change') }

/* ===================== 网格/值 ===================== */
const cellAt = (r, c) => (sheet.value.grid[r] || [])[c]
const display = (cell) => {
  if (cell == null) return ''
  if (cell.d !== undefined && cell.d !== null && cell.d !== '') return String(cell.d)
  return cell.v == null ? '' : String(cell.v)
}
const ensureCell = (r, c) => {
  const grid = sheet.value.grid
  if (!grid[r]) grid[r] = []
  if (!grid[r][c]) grid[r][c] = { t: 's', v: '', d: '' }
  return grid[r][c]
}
const setCell = (r, c, cell) => {
  const grid = sheet.value.grid
  if (!grid[r]) grid[r] = []
  grid[r][c] = cell
}
const deleteCell = (r, c) => {
  const grid = sheet.value.grid
  if (grid[r]) grid[r][c] = null
}

/* ===================== 行数/列数 ===================== */
const rowCount = computed(() => Math.max(20, (sheet.value.grid || []).length))
const colCount = computed(() => {
  const grid = sheet.value.grid || []
  return Math.max(10, grid.reduce((m, r) => Math.max(m, (r || []).length), 0))
})

/* ===================== 单元格样式 ===================== */
const styleAt = (r, c) => {
  const cell = cellAt(r, c)
  return (cell && cell.s) || {}
}
const setStyle = (key, val) => {
  for (let r = range.r1; r <= range.r2; r++) {
    for (let c = range.c1; c <= range.c2; c++) {
      const cell = ensureCell(r, c)
      cell.s = { ...(cell.s || {}) }
      if (val === null || val === undefined || val === '') delete cell.s[key]
      else cell.s[key] = val
    }
  }
  wrapChange()
}
const toggleStyle = (key) => {
  const cur = styleAt(sel.r, sel.c)[key]
  setStyle(key, cur ? null : true)
}
const cellCss = (r, c) => {
  const s = styleAt(r, c)
  return {
    fontWeight: s.b ? 700 : 400,
    fontStyle: s.i ? 'italic' : 'normal',
    textDecoration: s.u ? 'underline' : (s.strike ? 'line-through' : 'none'),
    color: s.color ? '#' + s.color : '',
    backgroundColor: s.bg ? '#' + s.bg : '',
    textAlign: s.align || 'left',
    fontSize: s.size ? s.size + 'px' : ''
  }
}

/* ===================== 选中/编辑 ===================== */
const selectCell = (r, c, extend = false) => {
  if (extend) {
    range.r2 = r; range.c2 = c; normRange()
  } else {
    sel.r = r; sel.c = c
    range.r1 = r; range.c1 = c; range.r2 = r; range.c2 = c
  }
  editing.value = null
}
const selectAll = () => {
  range.r1 = 0; range.c1 = 0
  range.r2 = rowCount.value - 1; range.c2 = colCount.value - 1
  sel.r = 0; sel.c = 0
}
const startEdit = (r, c) => {
  sel.r = r; sel.c = c
  range.r1 = r; range.c1 = c; range.r2 = r; range.c2 = c
  editing.value = { r, c }
  editValue.value = display(cellAt(r, c))
  nextTick(() => {
    const el = document.querySelector('.sheet-grid .cell-edit')
    if (el) { el.focus(); el.select() }
  })
}
const commit = () => {
  if (!editing.value) return
  const { r, c } = editing.value
  const cur = cellAt(r, c)
  const before = display(cur)
  const after = editValue.value
  editing.value = null
  if (before === after) return
  if (after === '') {
    deleteCell(r, c)
  } else if (after.startsWith('=')) {
    setCell(r, c, { t: 'f', v: after.slice(1), d: after })
  } else if (/^-?\d+(\.\d+)?$/.test(after)) {
    setCell(r, c, { t: 'n', v: Number(after), d: after })
  } else {
    setCell(r, c, { t: 's', v: after, d: after })
  }
  wrapChange()
}
const cancelEdit = () => { editing.value = null }

/* ===================== 公式栏 ===================== */
const formulaValue = computed({
  get: () => {
    const cell = cellAt(sel.r, sel.c)
    if (cell == null) return ''
    if (cell.t === 'f') return '=' + cell.v
    return display(cell)
  },
  set: (v) => {
    if (editing.value) return
    editValue.value = v
    if (sel.r >= 0 && sel.c >= 0) {
      editing.value = { r: sel.r, c: sel.c }
      nextTick(() => commit())
    }
  }
})

/* ===================== 行列增删 ===================== */
const addRow = (below = true) => {
  const grid = sheet.value.grid
  const at = below ? sel.r + 1 : sel.r
  const newRow = Array.from({ length: colCount.value }, () => null)
  grid.splice(Math.min(at, grid.length), 0, newRow)
  wrapChange()
}
const delRow = () => {
  const grid = sheet.value.grid
  if (grid.length <= 1) return
  grid.splice(sel.r, 1)
  if (sel.r >= grid.length) sel.r = Math.max(0, grid.length - 1)
  selectCell(sel.r, sel.c)
  wrapChange()
}
const addCol = () => {
  const at = sel.c + 1
  sheet.value.grid.forEach(row => row.splice(Math.min(at, row.length), 0, null))
  wrapChange()
}
const delCol = () => {
  if (colCount.value <= 1) return
  sheet.value.grid.forEach(row => row.splice(sel.c, 1))
  if (sel.c >= colCount.value) sel.c = Math.max(0, colCount.value - 1)
  selectCell(sel.r, sel.c)
  wrapChange()
}

/* ===================== 工作表管理 ===================== */
const addSheet = () => {
  sheets.value.push({ name: 'Sheet' + (sheets.value.length + 1), grid: Array.from({ length: 20 }, () => Array.from({ length: 10 }, () => null)) })
  activeSheet.value = sheets.value.length - 1
  sel.r = 0; sel.c = 0
  wrapChange()
}
const renameSheet = async (idx) => {
  const { ElMessageBox } = await import('element-plus')
  try {
    const { value } = await ElMessageBox.prompt(t('myDocs.sheetRename'), t('myDocs.sheetName'), {
      inputValue: sheets.value[idx].name,
      inputValidator: (v) => (!!v && v.trim().length > 0) || t('myDocs.dirNamePlaceholder')
    })
    sheets.value[idx].name = value.trim().slice(0, 31)
    wrapChange()
  } catch (e) { /* 取消 */ }
}
const delSheet = () => {
  if (sheets.value.length <= 1) return
  sheets.value.splice(activeSheet.value, 1)
  activeSheet.value = Math.max(0, activeSheet.value - 1)
  sel.r = 0; sel.c = 0
  wrapChange()
}

/* ===================== 复制/粘贴/删除 ===================== */
const clipboard = ref([])

/* ===================== 数字格式 =====================
 * 数字格式应用给选中区域: 自动按单元格当前值套格式字符串
 */
const NUMBER_FORMATS = [
  { value: 'general', label: '常规' },
  { value: '0', label: '数字 1,000' },
  { value: '0.00', label: '数字 1,000.00' },
  { value: '#,##0', label: '千分位 1,000' },
  { value: '#,##0.00', label: '千分位 1,000.00' },
  { value: '¥#,##0', label: '货币 ¥1,000' },
  { value: '¥#,##0.00', label: '货币 ¥1,000.00' },
  { value: '$#,##0.00', label: '美元 $1,000.00' },
  { value: '0%', label: '百分比 10%' },
  { value: '0.00%', label: '百分比 10.00%' },
  { value: 'yyyy-mm-dd', label: '日期 2024-01-01' },
  { value: 'yyyy/mm/dd', label: '日期 2024/01/01' },
  { value: 'h:mm:ss', label: '时间 13:30:00' }
]
const setNumberFormat = (fmt) => {
  for (let r = range.r1; r <= range.r2; r++) {
    for (let c = range.c1; c <= range.c2; c++) {
      const cell = ensureCell(r, c)
      cell.s = { ...(cell.s || {}) }
      if (fmt === 'general') delete cell.s.fmt
      else cell.s.fmt = fmt
    }
  }
  wrapChange()
}

/* ===================== 合并 / 取消合并 =====================
 * 模型用 sheet.merges: ["A1:B1", ...] 字符串数组
 * 这是 XlsxCodec 已经在用的格式
 */
const merges = computed(() => sheet.value.merges || [])
const setMerges = (arr) => { sheet.value.merges = arr }

const isInMerged = (r, c) => {
  return merges.value.some(m => {
    const [sr, sc, er, ec] = parseRange(m)
    return r >= sr && r <= er && c >= sc && c <= ec
  })
}
const mergeAnchor = (r, c) => {
  return merges.value.find(m => {
    const [sr, sc, er, ec] = parseRange(m)
    return r === sr && c === sc
  })
}

function parseRange(s) {
  // "A1:C3" -> [0, 0, 2, 2]
  const m = s.match(/^([A-Z]+)(\d+):([A-Z]+)(\d+)$/)
  if (!m) return null
  const c1 = colToIdx(m[1])
  const r1 = parseInt(m[2]) - 1
  const c2 = colToIdx(m[3])
  const r2 = parseInt(m[4]) - 1
  return [Math.min(r1, r2), Math.min(c1, c2), Math.max(r1, r2), Math.max(c1, c2)]
}
function colToIdx(s) {
  let n = 0
  for (let i = 0; i < s.length; i++) n = n * 26 + (s.charCodeAt(i) - 64)
  return n - 1
}
function idxToCol(n) {
  let s = '', x = n + 1
  while (x > 0) {
    const r = (x - 1) % 26
    s = String.fromCharCode(65 + r) + s
    x = Math.floor((x - 1) / 26)
  }
  return s
}

const mergeSelection = () => {
  if (range.r1 === range.r2 && range.c1 === range.c2) return
  const key = `${idxToCol(range.c1)}${range.r1 + 1}:${idxToCol(range.c2)}${range.r2 + 1}`
  // 移除选区内被部分覆盖的合并(简化: 只在选区完全等于合并块时跳过)
  const filtered = merges.value.filter(m => {
    const [sr, sc, er, ec] = parseRange(m)
    // 合并块若完全在选区内, 跳过(我们要替换成新合并)
    return !(sr >= range.r1 && er <= range.r2 && sc >= range.c1 && ec <= range.c2)
  })
  filtered.push(key)
  setMerges(filtered)
  wrapChange()
}
const unmergeSelection = () => {
  // 选区内所有合并块都取消
  const filtered = merges.value.filter(m => {
    const [sr, sc, er, ec] = parseRange(m)
    return !(sr >= range.r1 && er <= range.r2 && sc >= range.c1 && ec <= range.c2)
  })
  setMerges(filtered)
  wrapChange()
}

/* ===================== 列宽 / 行高 =====================
 * 简单方案: 列宽数组 colWidths 存到 sheet.colWidths (像素)
 * 行高 sheet.rowHeights
 */
const colWidths = computed(() => sheet.value.colWidths || [])
const rowHeights = computed(() => sheet.value.rowHeights || [])
const getColWidth = (c) => colWidths.value[c] || COL_W
const getRowHeight = (r) => rowHeights.value[r] || ROW_H
const adjustColWidth = (c, w) => {
  const arr = [...colWidths.value]
  arr[c] = Math.max(40, w)
  sheet.value.colWidths = arr
  wrapChange()
}
const adjustRowHeight = (r, h) => {
  const arr = [...rowHeights.value]
  arr[r] = Math.max(20, h)
  sheet.value.rowHeights = arr
  wrapChange()
}

/* 列宽拖拽 */
let draggingCol = null
let dragStartX = 0
let dragStartW = 0
const onColResizeStart = (c, e) => {
  e.preventDefault()
  e.stopPropagation()
  draggingCol = { c, startX: e.clientX, startW: getColWidth(c) }
  dragStartX = e.clientX
  dragStartW = getColWidth(c)
  document.addEventListener('mousemove', onColResizeMove)
  document.addEventListener('mouseup', onColResizeEnd, { once: true })
}
const onColResizeMove = (e) => {
  if (!draggingCol) return
  const dx = e.clientX - dragStartX
  adjustColWidth(draggingCol.c, dragStartW + dx)
}
const onColResizeEnd = () => {
  draggingCol = null
  document.removeEventListener('mousemove', onColResizeMove)
}

/* ===================== 排序 ===================== */
const sortSelection = (order) => {
  // 按选区第一列的值升序/降序排序整行
  const rows = []
  for (let r = range.r1; r <= range.r2; r++) rows.push({ r, cells: sheet.value.grid[r]?.slice() || [] })
  rows.sort((a, b) => {
    const av = a.cells[range.c1] ? display(a.cells[range.c1]) : ''
    const bv = b.cells[range.c1] ? display(b.cells[range.c1]) : ''
    const an = parseFloat(av), bn = parseFloat(bv)
    if (!isNaN(an) && !isNaN(bn)) return order === 'asc' ? an - bn : bn - an
    return order === 'asc' ? av.localeCompare(bv, 'zh') : bv.localeCompare(av, 'zh')
  })
  for (let i = 0; i < rows.length; i++) {
    sheet.value.grid[range.r1 + i] = rows[i].cells
  }
  wrapChange()
}
const copyRange = () => {
  clipboard.value = []
  for (let r = range.r1; r <= range.r2; r++) {
    const row = []
    for (let c = range.c1; c <= range.c2; c++) {
      row.push(cellAt(r, c) ? JSON.parse(JSON.stringify(cellAt(r, c))) : null)
    }
    clipboard.value.push(row)
  }
}
const pasteRange = () => {
  if (!clipboard.value.length) return
  const h = clipboard.value.length
  const w = clipboard.value[0].length
  for (let r = 0; r < h; r++) {
    for (let c = 0; c < w; c++) {
      setCell(sel.r + r, sel.c + c, clipboard.value[r][c] ? JSON.parse(JSON.stringify(clipboard.value[r][c])) : null)
    }
  }
  range.r2 = sel.r + h - 1; range.c2 = sel.c + w - 1
  wrapChange()
}
const deleteRange = () => {
  for (let r = range.r1; r <= range.r2; r++) {
    for (let c = range.c1; c <= range.c2; c++) {
      deleteCell(r, c)
    }
  }
  wrapChange()
}

/* ===================== 键盘导航 ===================== */
const moveSel = (dr, dc) => {
  const r = Math.max(0, Math.min(rowCount.value - 1, sel.r + dr))
  const c = Math.max(0, Math.min(colCount.value - 1, sel.c + dc))
  selectCell(r, c)
  nextTick(scrollIntoView)
}
const scrollIntoView = () => {
  const el = document.querySelector(`.sheet-grid .cell[data-r="${sel.r}"][data-c="${sel.c}"]`)
  if (el) el.scrollIntoView({ block: 'nearest', inline: 'nearest' })
}

/* ===================== 快捷键 ===================== */
useEditorShortcuts({
  enabled: () => !!sheet.value,
  handlers: {
    'ctrl+z': () => undo(),
    'ctrl+y': () => redo(),
    'ctrl+shift+z': () => redo(),
    'ctrl+c': () => copyRange(),
    'ctrl+v': () => pasteRange(),
    'ctrl+x': () => { copyRange(); deleteRange() },
    'ctrl+a': () => selectAll(),
    'ctrl+f': () => {},
    'ctrl+s': () => emit('save'),
    'arrowup': () => { if (!editing.value) moveSel(-1, 0) },
    'arrowdown': () => { if (!editing.value) moveSel(1, 0) },
    'arrowleft': () => { if (!editing.value) moveSel(0, -1) },
    'arrowright': () => { if (!editing.value) moveSel(0, 1) },
    'enter': () => { if (editing.value) { commit(); moveSel(1, 0) } else startEdit(sel.r, sel.c) },
    'tab': () => { if (editing.value) commit(); moveSel(0, 1) },
    'escape': () => cancelEdit(),
    'delete': () => { if (editing.value) cancelEdit(); else deleteRange() }
  }
})

onMounted(() => { if (!history.past.length) record() })
onBeforeUnmount(() => { editing.value = null })

/* ===================== 合并单元格的 colspan ===================== */
function cellColSpan(r, c) {
  // 只有合并锚点行才有 colspan
  const m = merges.value.find(mm => {
    const [sr, sc, er, ec] = parseRange(mm)
    return r === sr && c === sc
  })
  if (!m) return 1
  const [sr, sc, er, ec] = parseRange(m)
  return ec - sc + 1
}

/* 数字格式显示样式: 在 display 上加 0 填充等 */
const displayStyle = (r, c) => {
  const s = styleAt(r, c)
  const out = {}
  if (s.fmt === '0%' || s.fmt === '0.00%') return { color: '#666' }
  if (s.fmt && s.fmt.startsWith('¥')) return { color: '#b00020' }
  if (s.fmt === '0.00' || s.fmt === '#,##0.00') return { fontVariantNumeric: 'tabular-nums' }
  return out
}

/* ===================== 统计(状态栏) ===================== */
const rangeSize = computed(() => {
  const r = range.r2 - range.r1 + 1
  const c = range.c2 - range.c1 + 1
  return `${r} × ${c}`
})
const sumRange = computed(() => {
  let sum = 0, cnt = 0
  for (let r = range.r1; r <= range.r2; r++) {
    for (let c = range.c1; c <= range.c2; c++) {
      const cell = cellAt(r, c)
      if (cell && cell.t === 'n' && typeof cell.v === 'number') { sum += cell.v; cnt++ }
    }
  }
  if (!cnt) return ''
  return `求和: ${sum}  平均: ${Math.round(sum / cnt * 100) / 100}  计数: ${cnt}`
})

const focusActive = () => {
  const el = document.querySelector(`.sheet-grid .cell[data-r="${sel.r}"][data-c="${sel.c}"]`)
  if (el) el.focus()
}
</script>

<template>
  <div class="sheet-editor">
    <!-- 工作表 tab -->
    <div class="sheet-tabs">
      <span v-for="(s, i) in sheets" :key="i" class="tab" :class="{ on: i === activeSheet }" @click="activeSheet = i" @dblclick="renameSheet(i)">{{ s.name }}</span>
      <button class="add-tab" @click="addSheet">+ {{ $t('myDocs.sheetAdd') }}</button>
    </div>

    <!-- 工具栏(紧凑) -->
    <EditorToolbar>
      <template #start>
        <TbGroup>
          <TbButton :disabled="history.past.length <= 1" :title="$t('myDocs.shortcutUndo')" @click="undo"><IconUndo /></TbButton>
          <TbButton :disabled="!history.future.length" :title="$t('myDocs.shortcutRedo')" @click="redo"><IconRedo /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbSelect
            :model-value="styleAt(sel.r, sel.c).size || ''"
            :options="SIZE_OPTIONS"
            :placeholder="$t('myDocs.fontSize')"
            width="56px"
            @change="v => setStyle('size', v)"
          />
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :active="styleAt(sel.r, sel.c).b" :title="$t('myDocs.shortcutBold')" @click="toggleStyle('b')"><IconBold /></TbButton>
          <TbButton :active="styleAt(sel.r, sel.c).i" :title="$t('myDocs.shortcutItalic')" @click="toggleStyle('i')"><IconItalic /></TbButton>
          <TbButton :active="styleAt(sel.r, sel.c).u" :title="$t('myDocs.shortcutUnderline')" @click="toggleStyle('u')"><IconUnderline /></TbButton>
          <TbButton :active="styleAt(sel.r, sel.c).strike" :title="$t('myDocs.strike')" @click="toggleStyle('strike')"><IconStrike /></TbButton>
          <TbColor :title="$t('myDocs.fontColor')" :value="(styleAt(sel.r, sel.c).color || '000000').padStart(6, '0').replace(/^(.)(.)(.)(.)(.)(.)$/, '#$1$2$3$4$5$6')" @change="v => setStyle('color', v.replace('#','').toUpperCase())" />
          <TbColor :title="$t('myDocs.cellBg')" :value="(styleAt(sel.r, sel.c).bg || 'FFFFFF').padStart(6, '0').replace(/^(.)(.)(.)(.)(.)(.)$/, '#$1$2$3$4$5$6')" @change="v => setStyle('bg', v.replace('#','').toUpperCase())" />
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :active="styleAt(sel.r, sel.c).align === 'left'" :title="$t('myDocs.alignLeft')" @click="setStyle('align','left')"><IconAlignLeft /></TbButton>
          <TbButton :active="styleAt(sel.r, sel.c).align === 'center'" :title="$t('myDocs.alignCenter')" @click="setStyle('align','center')"><IconAlignCenter /></TbButton>
          <TbButton :active="styleAt(sel.r, sel.c).align === 'right'" :title="$t('myDocs.alignRight')" @click="setStyle('align','right')"><IconAlignRight /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbSelect
            :model-value="styleAt(sel.r, sel.c).fmt || 'general'"
            :options="NUMBER_FORMATS"
            :placeholder="$t('myDocs.numberFormat')"
            width="100px"
            @change="setNumberFormat"
          />
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.mergeCells')" @click="mergeSelection"><IconMerge /></TbButton>
          <TbButton :title="$t('myDocs.unmerge')" @click="unmergeSelection"><IconMerge /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.sortAsc')" @click="sortSelection('asc')"><IconSort /></TbButton>
          <TbButton :title="$t('myDocs.filter')"><IconFilter /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.sheetAddRow')" @click="addRow(true)"><span class="tbtn-txt">+R</span></TbButton>
          <TbButton :title="$t('myDocs.sheetAddCol')" @click="addCol"><span class="tbtn-txt">+C</span></TbButton>
        </TbGroup>
      </template>
    </EditorToolbar>

    <!-- 公式栏 -->
    <div class="formula-bar">
      <span class="addr">{{ colName(sel.c) }}{{ sel.r + 1 }}</span>
      <span class="eq">fx</span>
      <el-input v-model="formulaValue" size="small" :placeholder="$t('myDocs.sheetValue')" @keydown.enter="commit" />
    </div>

    <!-- 真表格 -->
    <div class="sheet-grid">
      <table>
        <thead>
          <tr>
            <th class="corner" :style="{ width: HEAD_W + 'px' }"></th>
            <th v-for="c in colCount" :key="c" class="colhead" :style="{ minWidth: getColWidth(c - 1) + 'px' }">
              {{ colName(c - 1) }}
              <span class="col-resize" @mousedown="onColResizeStart(c - 1, $event)"></span>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in rowCount" :key="r - 1" :style="{ height: getRowHeight(r - 1) + 'px' }">
            <th class="rowhead" :style="{ width: HEAD_W + 'px' }">{{ r }}</th>
            <td
              v-for="c in colCount" :key="c - 1"
              class="cell"
              :class="{
                sel: isInRange(r - 1, c - 1),
                edit: editing && editing.r === r - 1 && editing.c === c - 1,
                merged: isInMerged(r - 1, c - 1) && !mergeAnchor(r - 1, c - 1)
              }"
              :colspan="cellColSpan(r - 1, c - 1)"
              :style="{ ...cellCss(r - 1, c - 1), minWidth: getColWidth(c - 1) + 'px' }"
              :data-r="r - 1"
              :data-c="c - 1"
              tabindex="0"
              @click="selectCell(r - 1, c - 1, $event.shiftKey)"
              @dblclick="startEdit(r - 1, c - 1)"
            >
              <input
                v-if="editing && editing.r === r - 1 && editing.c === c - 1"
                v-model="editValue" class="cell-edit" autofocus
                @blur="commit" @keydown.enter.prevent="commit" @keydown.tab.prevent="commit" @keydown.esc="cancelEdit"
              />
              <span v-else class="cell-text" :style="displayStyle(r - 1, c - 1)">{{ display(cellAt(r - 1, c - 1)) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 状态栏 -->
    <div class="statusbar">
      <span class="st-item">{{ $t('myDocs.statusCell', { addr: colName(sel.c) + (sel.r + 1) }) }}</span>
      <span class="st-sep" />
      <span class="st-item">{{ sumRange || '—' }}</span>
      <span class="st-sep" />
      <span class="st-item">{{ rangeSize }}</span>
      <span class="grow" />
      <span class="st-item">100%</span>
    </div>
  </div>
</template>

<style scoped>
.sheet-editor { display: flex; flex-direction: column; height: 100%; background: #fff; }
.sheet-tabs { display: flex; align-items: center; gap: 2px; padding: 4px 10px; background: #f7f8fa; border-bottom: 1px solid var(--c-border); flex-wrap: wrap; }
.tab { padding: 4px 14px; border: 1px solid var(--c-border); border-radius: 4px 4px 0 0; font-size: 13px; cursor: pointer; color: var(--c-text-2); background: #fff; }
.tab.on { background: #fff; color: var(--c-primary); border-bottom: 2px solid var(--c-primary); font-weight: 600; }
.add-tab { border: 0; background: transparent; cursor: pointer; padding: 4px 10px; color: var(--c-text-3); font-size: 12px; border-radius: 4px; }
.add-tab:hover { background: rgba(0,0,0,.06); color: var(--c-text-1); }

.formula-bar { display: flex; align-items: center; gap: 8px; padding: 4px 10px; background: #fff; border-bottom: 1px solid var(--c-border); }
.addr { min-width: 56px; padding: 4px 8px; text-align: center; font-size: 12px; color: var(--c-text-2); background: #f7f8fa; border: 1px solid var(--c-border); border-radius: 4px; font-weight: 600; }
.eq { font-size: 12px; color: var(--c-text-3); font-style: italic; }

.sheet-grid { flex: 1; overflow: auto; background: #fff; }
.sheet-grid table { border-collapse: collapse; font-size: 13px; }
.sheet-grid th { background: #f7f8fa; border-right: 1px solid var(--c-border); border-bottom: 1px solid var(--c-border); padding: 4px 0; font-weight: 500; color: var(--c-text-2); font-size: 12px; text-align: center; position: relative; }
.sheet-grid .corner { position: sticky; left: 0; top: 0; z-index: 3; background: #f7f8fa; border-right: 2px solid #d0d0d0; border-bottom: 2px solid #d0d0d0; }
.sheet-grid .colhead { position: sticky; top: 0; z-index: 2; }
.sheet-grid .rowhead { position: sticky; left: 0; z-index: 1; background: #f7f8fa; border-right: 2px solid #d0d0d0; }
.sheet-grid .col-resize { position: absolute; right: 0; top: 0; bottom: 0; width: 4px; cursor: col-resize; user-select: none; }
.sheet-grid .col-resize:hover { background: var(--c-primary); opacity: 0.3; }
.sheet-grid .cell { border-right: 1px solid #e5e5e5; border-bottom: 1px solid #e5e5e5; padding: 0 6px; cursor: cell; overflow: hidden; user-select: none; position: relative; }
.sheet-grid .cell.sel { background: rgba(26, 115, 232, .08); }
.sheet-grid .cell.sel::after { content: ''; position: absolute; inset: 0; border: 2px solid #1a73e8; pointer-events: none; z-index: 2; }
.sheet-grid .cell.merged { background: #f9f9fa; }
.sheet-grid .cell-text { display: block; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; line-height: 26px; }
.sheet-grid .cell-edit { position: absolute; inset: 0; border: 2px solid #1a73e8; padding: 0 6px; font-size: 13px; font-family: inherit; outline: none; background: #fff; }
.sheet-grid .cell:focus { outline: none; }

.statusbar { display: flex; align-items: center; gap: 8px; padding: 4px 10px; background: #f7f8fa; border-top: 1px solid var(--c-border); font-size: 12px; color: var(--c-text-3); }
.st-sep { width: 1px; height: 12px; background: var(--c-border); }
.grow { flex: 1; }

/* 工具栏覆盖: 略灰白 */
.sheet-editor :deep(.editor-toolbar) { background: #fcfcfd; border-bottom: 1px solid #e8e8ec; }
.tbtn-txt { font-size: 12px; font-weight: 600; color: var(--c-text-2); }
</style>
