/**
 * 通用编辑器能力: 撤销/重做 + 格式刷 + 查找替换 + 快捷键注册
 *
 * 对齐腾讯文档 / Google Docs / WPS Web 三大主流在线文档的交互范式。
 * 不依赖外部大型组件, 适配现有 WordEditor / SheetEditor / SlideEditor。
 *
 * 用法:
 *   const history = useEditorHistory({ getModel, applyModel, max: 200 })
 *   history.record('init')              // 初始快照
 *   history.record('user-typed')        // 每次用户操作后
 *   history.undo()                      // Ctrl/Cmd+Z
 *   history.redo()                      // Ctrl/Cmd+Y 或 Ctrl/Cmd+Shift+Z
 */
import { ref, computed, reactive, watch } from 'vue'

/**
 * 撤销/重做栈(命令模式)
 * - history: past 栈(旧→新), undo 时 pop
 * - future: 待重做栈(new→old), redo 时 pop
 * - 每条 snapshot 记录: { t, model } 完整保存模型, 简单可靠(适合 blocks / cells / slides)
 */
export function useEditorHistory({ getModel, applyModel, max = 200, scope = 'doc' }) {
  const past = ref([])
  const future = ref([])
  // 抑制 record: 在 undo/redo 自身执行过程中不应再压栈
  let suppress = false

  const deepClone = (v) => JSON.parse(JSON.stringify(v ?? null))

  const record = (label = 'change') => {
    if (suppress) return
    const cur = getModel()
    // null/空值不入栈(组件刚 setup 时 props 可能还是空, 这是正常生命周期)
    if (cur == null) return
    const snap = { label, t: Date.now(), model: deepClone(cur) }
    const last = past.value[past.value.length - 1]
    if (last && shallowEq(last.model, snap.model)) return  // 无变化不入栈
    past.value.push(snap)
    if (past.value.length > max) past.value.shift()
    future.value = []  // 新操作清空 redo 栈
  }

  const undo = () => {
    if (past.value.length <= 1) return false
    const cur = past.value.pop()
    future.value.push(cur)
    const prev = past.value[past.value.length - 1]
    suppress = true
    applyModel(deepClone(prev.model))
    suppress = false
    return true
  }

  const redo = () => {
    const next = future.value.pop()
    if (!next) return false
    past.value.push(next)
    suppress = true
    applyModel(deepClone(next.model))
    suppress = false
    return true
  }

  const reset = (initialModel) => {
    // 允许传 null(组件刚 mount 时 props 还没值, 后续 model.value 变了再 reset 一次)
    past.value = [{ label: 'init', t: Date.now(), model: initialModel == null ? null : deepClone(initialModel) }]
    future.value = []
  }

  const canUndo = computed(() => past.value.length > 1)
  const canRedo = computed(() => future.value.length > 0)
  const lastLabel = computed(() => past.value[past.value.length - 1]?.label || '')

  return { record, undo, redo, reset, canUndo, canRedo, lastLabel, past, future, scope }
}

// 浅比较: 同一对象的 JSON.stringify 完全相同就视为没变
function shallowEq(a, b) {
  try { return JSON.stringify(a) === JSON.stringify(b) } catch (e) { return false }
}

/* ===================== 格式刷 =====================
 * - formatPainter.value = null: 关闭
 * - { source, capture, apply, once: true }: 一次性刷(点完一次自动关)
 * - { ..., once: false }: 永久刷(再次点击关闭)
 */
export function useFormatPainter() {
  const formatPainter = ref(null)

  const arm = (cfg) => {
    formatPainter.value = cfg
  }
  const disarm = () => { formatPainter.value = null }
  const isOn = computed(() => !!formatPainter.value)

  // 在目标上应用
  const apply = (target) => {
    const p = formatPainter.value
    if (!p || !target) return false
    p.apply(target)
    if (p.once) formatPainter.value = null
    return true
  }

  return { formatPainter, arm, disarm, apply, isOn }
}

/* ===================== 查找替换 =====================
 * 支持:
 *   - WordEditor: 在 blocks[].text 中查找(支持跨段)
 *   - SheetEditor: 在 sheets[].grid 中按行列查找
 *   - SlideEditor: 在 slides[].shapes[].paragraphs[].text 中查找
 *
 * 用法:
 *   const fr = useFindReplace({ model, getBlocks: ()=>blocks.value })
 *   fr.open() / fr.close()
 *   fr.find('foo') / fr.findNext() / fr.findPrev() / fr.replace('bar') / fr.replaceAll('baz')
 */
export function useFindReplace({ model, getDocText, setDocText, onChange }) {
  const open = ref(false)
  const query = ref('')
  const replace = ref('')
  const matchCase = ref(false)
  const matches = ref([])   // [{ range, length }]
  const cursor = ref(0)     // 当前 match 下标

  const all = () => (getDocText ? getDocText() : '')

  const rebuild = () => {
    matches.value = []
    cursor.value = 0
    const q = query.value
    if (!q) return
    const hay = all()
    if (!hay) return
    const hay2 = matchCase.value ? hay : hay.toLowerCase()
    const q2 = matchCase.value ? q : q.toLowerCase()
    let i = 0
    while ((i = hay2.indexOf(q2, i)) >= 0) {
      matches.value.push({ start: i, end: i + q.length })
      i += q.length || 1
    }
  }

  const find = (q) => {
    if (q !== undefined) query.value = q
    rebuild()
  }

  const findNext = () => {
    if (matches.value.length === 0) return -1
    cursor.value = (cursor.value + 1) % matches.value.length
    return cursor.value
  }
  const findPrev = () => {
    if (matches.value.length === 0) return -1
    cursor.value = (cursor.value - 1 + matches.value.length) % matches.value.length
    return cursor.value
  }

  const doReplace = (replaceText) => {
    if (replaceText !== undefined) replace.value = replaceText
    if (matches.value.length === 0) return 0
    const m = matches.value[cursor.value]
    if (!m) return 0
    const text = all()
    const newText = text.slice(0, m.start) + (replace.value || '') + text.slice(m.end)
    if (setDocText) setDocText(newText)
    rebuild()
    onChange && onChange()
    return 1
  }

  const doReplaceAll = (replaceText) => {
    if (replaceText !== undefined) replace.value = replaceText
    if (!query.value) return 0
    const text = all()
    const q = matchCase.value ? query.value : query.value.toLowerCase()
    const hay = matchCase.value ? text : text.toLowerCase()
    const re = new RegExp(escapeRe(q), matchCase.value ? 'g' : 'gi')
    const newText = text.replace(re, () => replace.value || '')
    if (newText === text) return 0
    if (setDocText) setDocText(newText)
    rebuild()
    onChange && onChange()
    return matches.value.length
  }

  const close = () => { open.value = false; query.value = ''; replace.value = ''; matches.value = []; cursor.value = 0 }

  return { open, query, replace, matchCase, matches, cursor, find, findNext, findPrev, doReplace, doReplaceAll, close, rebuild }
}

function escapeRe(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') }

/* ===================== 全局快捷键注册 =====================
 * 把命令注册到 document 上, 只在编辑器 active 时生效。
 *
 * 用法:
 *   useEditorShortcuts({
 *     enabled: () => isEditorOpen.value,
 *     handlers: {
 *       'ctrl+z': () => history.undo(),
 *       'ctrl+y': () => history.redo(),
 *       ...
 *     }
 *   })
 */
export function useEditorShortcuts({ enabled, handlers }) {
  if (typeof window === 'undefined') return { dispose: () => {} }
  const isMac = /Mac|iPhone|iPad/.test(navigator.platform)

  const normalize = (e) => {
    const k = e.key.toLowerCase()
    const parts = []
    if (e.metaKey && isMac) parts.push('cmd')
    else if (e.ctrlKey) parts.push('ctrl')
    if (e.altKey) parts.push('alt')
    if (e.shiftKey) parts.push('shift')
    parts.push(k)
    return parts.join('+')
  }

  const onKey = (e) => {
    if (enabled && !enabled()) return
    // 排除在可编辑元素 / 输入框中的某些键(由它们自己处理)
    const tag = (e.target?.tagName || '').toUpperCase()
    const inEditable = tag === 'INPUT' || tag === 'TEXTAREA' || e.target?.isContentEditable
    // 大部分快捷键即便在可编辑元素里也要响应(粗体/斜体等)
    const key = normalize(e)
    const handler = handlers[key]
    if (handler) {
      e.preventDefault()
      handler(e)
    }
  }

  document.addEventListener('keydown', onKey)
  return {
    dispose: () => document.removeEventListener('keydown', onKey)
  }
}

/* ===================== 状态栏统计 =====================
 * 统计模型中的字数 / 段落数 / 页数(对 Word 而言)。
 * 返回值: { text, charCount, wordCount, lineCount } 都是 plain number/ref;
 *         模板里 stats.charCount 直接是数字, i18n 插值不会触发 circular 错误。
 *         (computed 在 template 中访问会暴露 ComputedRefImpl 实例而非 .value, 因为
 *          setup 暴露的 plain object 不会被 Vue 自动 unwrap。改用 reactive 包裹即可。)
 */
export function useDocStats(getText) {
  const text = computed(() => getText ? (getText() || '') : '')
  const charCount = computed(() => Array.from(text.value).length)
  const wordCount = computed(() => {
    const t = text.value.trim()
    if (!t) return 0
    return t.split(/\s+/).filter(Boolean).length
  })
  const lineCount = computed(() => {
    const t = text.value
    if (!t) return 0
    return t.split(/\n/).length
  })
  return reactive({ charCount, wordCount, lineCount, text })
}
