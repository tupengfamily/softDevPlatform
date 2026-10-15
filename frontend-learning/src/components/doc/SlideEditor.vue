<script setup>
/**
 * PPT 在线编辑器 - 像 PowerPoint/Keynote 的单页全屏体验
 *
 * 1. 左侧: 缩略图列表(reveal.js 风格)
 * 2. 中央: 大画布(显示当前页所有形状, 可点击编辑)
 * 3. 右侧: 选中形状的属性面板(位置/尺寸/字体/对齐)
 * 4. 顶部: 工具栏(插入/对齐/字体)
 * 5. 快捷键: Ctrl+Z/Y/D 复制页, Delete 删除选中, 方向键微调
 * 6. 状态栏: 页码
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useEditorShortcuts } from '../../composables/useEditorHistory'
import EditorToolbar, { TbButton, TbGroup, TbSep, TbSelect, TbColor } from './EditorToolbar.vue'
import {
  IconUndo, IconRedo, IconBold, IconItalic, IconUnderline,
  IconAlignLeft, IconAlignCenter, IconAlignRight, IconAlignJustify,
  IconAnimation, IconNotes, IconLayout, IconText, IconRect, IconCircle, IconArrow, IconLine
} from './icons'

const props = defineProps({ model: { type: Object, required: true } })
const emit = defineEmits(['change', 'save'])
const { t } = useI18n()

const slides = computed(() => (props.model?.slides || []))
const active = ref(0)
const current = computed(() => slides.value[active.value] || null)

/* ===================== 选中形状 ===================== */
const selectedShape = ref(null)
const selectedPara = ref(null)

watch(active, () => { selectedShape.value = null; selectedPara.value = null })

/* ===================== 历史栈 ===================== */
const history = reactive({ past: [], future: [] })
const record = () => {
  history.past.push(JSON.parse(JSON.stringify({ slides: slides.value })))
  if (history.past.length > 200) history.past.shift()
  history.future = []
}
const undo = () => {
  if (history.past.length <= 1) return
  const cur = history.past.pop(); history.future.push(cur)
  applyModel({ slides: history.past[history.past.length - 1].slides })
}
const redo = () => {
  const n = history.future.pop()
  if (!n) return
  history.past.push(n)
  applyModel({ slides: n.slides })
}
const applyModel = (m) => {
  if (!m) return
  Object.keys(props.model).forEach(k => delete props.model[k])
  Object.assign(props.model, m)
  emit('change')
  nextTick(scrollToActive)
}
watch(() => slides.value.length, () => {
  if (!history.past.length) record()
}, { immediate: true })
const wrapChange = () => { record(); emit('change') }

/* ===================== 形状/段落 ===================== */
const pStyle = (p) => ({
  fontWeight: p.bold ? 700 : 400,
  fontStyle: p.italic ? 'italic' : 'normal',
  textDecoration: p.underline ? 'underline' : 'none',
  color: p.color ? '#' + p.color : '',
  fontSize: p.size ? p.size + 'px' : '',
  textAlign: p.align || 'left'
})
const toggleP = (p, k) => { p[k] = !p[k]; wrapChange() }
const setP = (p, k, v) => { if (v === '' || v == null) delete p[k]; else p[k] = v; wrapChange() }

/* ===================== 增删复制页 ===================== */
const addSlide = () => {
  slides.value.push({
    src: -1, title: '',
    shapes: [
      { idx: -1, kind: 'title', name: t('myDocs.slideTitle'),
        rect: { x: 40, y: 30, w: 880, h: 80 },
        paragraphs: [{ text: t('myDocs.slideNewTitle'), level: 0, size: 36, bold: true, align: 'center' }] },
      { idx: -1, kind: 'body', name: t('myDocs.slideBody'),
        rect: { x: 40, y: 130, w: 880, h: 380 },
        paragraphs: [{ text: t('myDocs.slideNewBody'), level: 0, size: 18 }] }
    ]
  })
  active.value = slides.value.length - 1
  wrapChange()
}
const dupSlide = () => {
  const s = current.value
  if (!s) return
  const copy = JSON.parse(JSON.stringify(s))
  copy.src = -1
  copy.copyOf = typeof s.src === 'number' ? s.src : -1
  copy.title = ''
  slides.value.splice(active.value + 1, 0, copy)
  active.value = active.value + 1
  wrapChange()
}
const delSlide = async () => {
  if (slides.value.length <= 1) return
  const { ElMessageBox } = await import('element-plus')
  try {
    await ElMessageBox.confirm(t('myDocs.slideDeleteConfirm', { n: active.value + 1 }), t('myDocs.slideDelete'), { type: 'warning' })
  } catch (e) { return }
  slides.value.splice(active.value, 1)
  active.value = Math.max(0, Math.min(active.value, slides.value.length - 1))
  wrapChange()
}
const moveSlide = (delta) => {
  const j = active.value + delta
  if (j < 0 || j >= slides.value.length) return
  const [b] = slides.value.splice(active.value, 1)
  slides.value.splice(j, 0, b)
  active.value = j
  wrapChange()
}

/* ===================== 形状选中/编辑 ===================== */
const CANVAS_W = 960
const CANVAS_H = 540
const selectShape = (sh) => { selectedShape.value = sh; selectedPara.value = null }
const selectPara = (sh, p) => { selectedShape.value = sh; selectedPara.value = p }
const onShapeClick = (sh, e) => { e.stopPropagation(); selectShape(sh) }
const onCanvasClick = () => { selectedShape.value = null; selectedPara.value = null }

/* ===================== 插入形状 ===================== */
const addShape = (kind) => {
  if (!current.value) return
  const defaults = {
    rect: { x: 300, y: 200, w: 360, h: 100 },
    round: { x: 300, y: 200, w: 150, h: 150 },
    arrow: { x: 200, y: 250, w: 560, h: 60 },
    line: { x: 200, y: 270, w: 560, h: 0 },
    text: { x: 300, y: 200, w: 360, h: 100 }
  }
  const shape = {
    idx: -1, kind: 'shape', name: t('myDocs.shapes.' + kind) || kind,
    rect: defaults[kind] || defaults.text,
    shape: kind,
    paragraphs: [{ text: kind === 'text' ? t('myDocs.shapeTextDefault') : '', level: 0, align: 'center' }]
  }
  current.value.shapes ||= []
  current.value.shapes.push(shape)
  selectedShape.value = shape
  wrapChange()
}
const SHAPE_KINDS = [
  { key: 'text', icon: 'T', iconComp: IconText, label: 'myDocs.shapes.text' },
  { key: 'rect', icon: '▭', iconComp: IconRect, label: 'myDocs.shapes.rect' },
  { key: 'round', icon: '◯', iconComp: IconCircle, label: 'myDocs.shapes.round' },
  { key: 'arrow', icon: '→', iconComp: IconArrow, label: 'myDocs.shapes.arrow' },
  { key: 'line', icon: '╱', iconComp: IconLine, label: 'myDocs.shapes.line' }
]

/* ===================== 版式 / 转场 / 缩放 ===================== */
const LAYOUT_OPTIONS = [
  { value: 'blank', label: '空白' },
  { value: 'title', label: '标题' },
  { value: 'title-content', label: '标题+正文' },
  { value: 'two-column', label: '两栏' },
  { value: 'section', label: '章节' }
]
const setLayout = (l) => {
  if (!current.value) return
  current.value.layout = l
  if (l === 'blank') current.value.shapes = []
  else if (l === 'title') current.value.shapes = [
    { idx: -1, kind: 'title', name: t('myDocs.slideTitle'), rect: { x: 60, y: 200, w: 840, h: 140 }, paragraphs: [{ text: t('myDocs.slideNewTitle'), level: 0, size: 54, bold: true, align: 'center' }] }
  ]
  else if (l === 'title-content') current.value.shapes = [
    { idx: -1, kind: 'title', name: t('myDocs.slideTitle'), rect: { x: 60, y: 40, w: 840, h: 100 }, paragraphs: [{ text: t('myDocs.slideNewTitle'), level: 0, size: 40, bold: true }] },
    { idx: -1, kind: 'body', name: t('myDocs.slideBody'), rect: { x: 60, y: 170, w: 840, h: 320 }, paragraphs: [{ text: t('myDocs.slideNewBody'), level: 0, size: 22 }] }
  ]
  else if (l === 'two-column') current.value.shapes = [
    { idx: -1, kind: 'title', name: t('myDocs.slideTitle'), rect: { x: 60, y: 40, w: 840, h: 100 }, paragraphs: [{ text: t('myDocs.slideNewTitle'), level: 0, size: 40, bold: true, align: 'center' }] },
    { idx: -1, kind: 'body', name: t('myDocs.slideBody'), rect: { x: 60, y: 170, w: 400, h: 320 }, paragraphs: [{ text: t('myDocs.slideNewBody'), level: 0, size: 18 }] },
    { idx: -1, kind: 'body', name: t('myDocs.slideBody'), rect: { x: 500, y: 170, w: 400, h: 320 }, paragraphs: [{ text: t('myDocs.slideNewBody'), level: 0, size: 18 }] }
  ]
  else if (l === 'section') current.value.shapes = [
    { idx: -1, kind: 'title', name: t('myDocs.slideTitle'), rect: { x: 0, y: 200, w: 960, h: 140 }, paragraphs: [{ text: t('myDocs.slideNewTitle'), level: 0, size: 54, bold: true, align: 'center' }] }
  ]
  wrapChange()
}

const TRANSITION_OPTIONS = [
  { value: 'none', label: '无' },
  { value: 'fade', label: '淡入' },
  { value: 'push', label: '推进' },
  { value: 'slide', label: '滑动' },
  { value: 'zoom', label: '缩放' }
]
const setTransition = (tr) => { if (current.value) { current.value.transition = tr; wrapChange() } }

const ZOOM_OPTIONS = [25, 50, 75, 100, 125, 150, 200, 400].map(z => ({ value: z, label: `${z}%` }))
const zoom = ref(60)
const setZoom = (z) => { zoom.value = z }

const FONT_FAMILY_OPTIONS = [
  { value: '', label: '默认' },
  { value: '宋体', label: '宋体' },
  { value: '微软雅黑', label: '微软雅黑' },
  { value: '黑体', label: '黑体' },
  { value: '楷体', label: '楷体' },
  { value: 'Arial', label: 'Arial' },
  { value: 'Calibri', label: 'Calibri' },
  { value: 'Times New Roman', label: 'Times New Roman' }
]
const FONT_SIZE_OPTIONS = [12, 14, 16, 18, 20, 24, 28, 32, 40, 48, 60, 72].map(s => ({ value: s, label: String(s) }))

/* ===================== 备注 ===================== */
const notesValue = ref('')
watch(active, () => {
  notesValue.value = current.value?.notes || ''
  // 清空属性面板的选中
  selectedShape.value = null
  selectedPara.value = null
}, { immediate: true })
const commitNotes = () => {
  if (current.value && current.value.notes !== notesValue.value) {
    current.value.notes = notesValue.value
    wrapChange()
  }
}

/* ===================== 形状填充色 ===================== */
const shapeBg = (sh) => {
  if (sh.fill) return sh.fill
  if (sh.shape === 'rect') return 'rgba(33,150,243,0.10)'
  if (sh.shape === 'round') return 'rgba(76,175,80,0.10)'
  return 'transparent'
}
const removeSelected = () => {
  if (!selectedShape.value || !current.value) return
  const i = current.value.shapes.indexOf(selectedShape.value)
  if (i >= 0) current.value.shapes.splice(i, 1)
  selectedShape.value = null
  wrapChange()
}

/* ===================== 段落操作 ===================== */
const addPara = (sh) => {
  sh.paragraphs ||= []
  sh.paragraphs.push({ text: '', level: 0 })
  wrapChange()
}
const delPara = (sh, i) => {
  sh.paragraphs.splice(i, 1)
  if (!sh.paragraphs.length) sh.paragraphs.push({ text: '', level: 0 })
  wrapChange()
}

/* ===================== 移动选中形状(键盘) ===================== */
const moveSelected = (dx, dy) => {
  if (!selectedShape.value?.rect) return
  const r = selectedShape.value.rect
  r.x = Math.max(0, Math.min(960 - r.w, r.x + dx))
  r.y = Math.max(0, Math.min(540 - r.h, r.y + dy))
  wrapChange()
}

/* ===================== 快捷键 ===================== */
useEditorShortcuts({
  enabled: () => true,
  handlers: {
    'ctrl+z': () => undo(),
    'ctrl+y': () => redo(),
    'ctrl+shift+z': () => redo(),
    'ctrl+d': () => dupSlide(),
    'ctrl+s': () => emit('save'),
    'arrowleft': () => moveSelected(-0.01, 0),
    'arrowright': () => moveSelected(0.01, 0),
    'arrowup': () => moveSelected(0, -0.01),
    'arrowdown': () => moveSelected(0, 0.01),
    'delete': () => removeSelected()
  }
})

onMounted(() => { if (!history.past.length) record() })
onBeforeUnmount(() => {})

const slideTitle = (s) => {
  if (s.title) return s.title
  for (const sh of s.shapes || []) {
    for (const p of sh.paragraphs || []) {
      if (p.text && p.text.trim()) return p.text.trim().slice(0, 30)
    }
  }
  return ''
}

const shapeLabel = (s) => {
  if (s.kind === 'title') return t('myDocs.slideTitle')
  if (s.kind === 'body') return t('myDocs.slideBody')
  return s.name || t('myDocs.slideText')
}

const scrollToActive = () => {
  nextTick(() => {
    const el = document.querySelector('.slide-thumbs .thumb.on')
    if (el) el.scrollIntoView({ block: 'nearest' })
  })
}
watch(active, scrollToActive)
</script>

<template>
  <div class="slide-editor" v-if="current">
    <!-- 顶部工具栏 -->
    <EditorToolbar>
      <template #start>
        <TbGroup>
          <TbButton :disabled="history.past.length <= 1" :title="$t('myDocs.shortcutUndo')" @click="undo"><IconUndo /></TbButton>
          <TbButton :disabled="!history.future.length" :title="$t('myDocs.shortcutRedo')" @click="redo"><IconRedo /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbButton :title="$t('myDocs.slideAddNew')" type="primary" @click="addSlide"><span style="font-size:13px;font-weight:600">+ {{ $t('myDocs.slideAddNew') }}</span></TbButton>
          <TbButton :title="$t('myDocs.slideDuplicate')" @click="dupSlide"><IconRedo /></TbButton>
          <TbButton :title="$t('myDocs.slideDelete')" :disabled="slides.length <= 1" @click="delSlide"><span style="font-size:13px;font-weight:600;color:#c4562c">×</span></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbSelect
            :model-value="current.layout || 'blank'"
            :options="LAYOUT_OPTIONS"
            :placeholder="$t('myDocs.slideLayout')"
            width="120px"
            @change="setLayout"
          />
        </TbGroup>
        <TbSep />
        <TbGroup>
          <span class="tb-label">{{ $t('myDocs.insertShape') }}</span>
          <TbButton v-for="k in SHAPE_KINDS" :key="k.key" :title="t(k.label)" @click="addShape(k.key)"><component :is="k.iconComp" /></TbButton>
        </TbGroup>
        <TbSep />
        <TbGroup>
          <TbSelect
            :model-value="current.transition || 'none'"
            :options="TRANSITION_OPTIONS"
            :placeholder="$t('myDocs.slideTransition')"
            width="100px"
            @change="setTransition"
          />
        </TbGroup>
      </template>
      <template #end>
        <TbGroup>
          <TbSelect
            :model-value="zoom"
            :options="ZOOM_OPTIONS"
            width="80px"
            @change="setZoom"
          />
        </TbGroup>
      </template>
    </EditorToolbar>

    <div class="body">
      <!-- 左侧:缩略图 -->
      <div class="thumbs">
        <div
          v-for="(s, i) in slides" :key="i"
          class="thumb" :class="{ on: i === active }"
          @click="active = i"
        >
          <div class="thumb-no">
            <span>{{ i + 1 }}</span>
            <el-tag v-if="s.src === -1" size="small" type="success">{{ $t('myDocs.slideNew') }}</el-tag>
            <span v-if="s.transition && s.transition !== 'none'" class="thumb-anim">▶</span>
          </div>
          <div class="thumb-preview">
            <div v-for="(sh, si) in s.shapes" :key="si" class="tp-shape" :style="{
              position: 'absolute',
              left: ((sh.rect?.x || 0) / 960 * 100) + '%',
              top: ((sh.rect?.y || 0) / 540 * 100) + '%',
              width: ((sh.rect?.w || 100) / 960 * 100) + '%',
              height: ((sh.rect?.h || 50) / 540 * 100) + '%',
              overflow: 'hidden',
              padding: '1px',
              boxSizing: 'border-box'
            }">
              <div v-for="(p, pi) in sh.paragraphs" :key="pi" :style="{ ...pStyle(p), fontSize: '2.5px', lineHeight: 1.1 }">{{ p.text }}</div>
            </div>
          </div>
        </div>
        <button class="add-slide" @click="addSlide">+</button>
      </div>

      <!-- 中央:大画布 + 备注 + 标尺 -->
      <div class="canvas-area">
        <div class="ruler-h"><div v-for="i in 8" :key="i" class="ruler-mark" :style="{ left: ((i-1)*12.5) + '%' }">{{ (i-1) * 120 }}</div></div>
        <div class="ruler-v"><div v-for="i in 5" :key="i" class="ruler-mark" :style="{ top: ((i-1)*25) + '%' }">{{ (i-1) * 135 }}</div></div>
        <div class="stage" @click="onCanvasClick">
          <div class="canvas" :style="{ width: CANVAS_W * zoom/100 + 'px', height: CANVAS_H * zoom/100 + 'px' }">
            <div
              v-for="(sh, si) in current.shapes" :key="si"
              class="shape"
              :class="{ selected: selectedShape === sh }"
              :style="{
                position: 'absolute',
                left: (sh.rect?.x || 0) * zoom/100 + 'px',
                top: (sh.rect?.y || 0) * zoom/100 + 'px',
                width: (sh.rect?.w || 100) * zoom/100 + 'px',
                height: (sh.rect?.h || 50) * zoom/100 + 'px',
                border: sh.shape === 'line' ? 'none' : (sh.shape === 'arrow' ? 'none' : (selectedShape === sh ? '2px solid #1a73e8' : '1px dashed transparent')),
                background: shapeBg(sh)
              }"
              @click="onShapeClick(sh, $event)"
            >
              <svg v-if="sh.shape === 'rect'" width="100%" height="100%">
                <rect x="2" y="2" width="100%" height="100%" :fill="sh.fill || 'rgba(33,150,243,0.10)'" :stroke="sh.stroke || '#2196f3'" stroke-width="2" />
              </svg>
              <svg v-else-if="sh.shape === 'round'" width="100%" height="100%">
                <ellipse cx="50%" cy="50%" rx="50%" ry="50%" :fill="sh.fill || 'rgba(76,175,80,0.10)'" :stroke="sh.stroke || '#4caf50'" stroke-width="2" />
              </svg>
              <svg v-else-if="sh.shape === 'arrow'" width="100%" height="100%">
                <defs>
                  <marker id="arrowhead" markerWidth="10" markerHeight="7" refX="9" refY="3.5" orient="auto">
                    <polygon points="0 0, 10 3.5, 0 7" :fill="sh.stroke || '#ff9800'" />
                  </marker>
                </defs>
                <line x1="0" y1="50%" x2="100%" y2="50%" :stroke="sh.stroke || '#ff9800'" stroke-width="3" marker-end="url(#arrowhead)" />
              </svg>
              <svg v-else-if="sh.shape === 'line'" width="100%" height="100%">
                <line x1="0" y1="50%" x2="100%" y2="50%" :stroke="sh.stroke || '#666'" stroke-width="2" />
              </svg>
              <div v-else class="shape-text">
                <div v-for="(p, pi) in sh.paragraphs" :key="pi" class="para" :style="pStyle(p)">
                  <textarea
                    v-model="p.text" class="shape-input" rows="1"
                    :style="pStyle(p)"
                    :placeholder="$t('myDocs.shapeTextDefault')"
                    @click.stop="selectPara(sh, p)"
                    @input="wrapChange"
                  />
                </div>
              </div>
            </div>
          </div>
        </div>
        <!-- 备注面板 -->
        <div class="notes">
          <span class="notes-label">{{ $t('myDocs.notesLabel') }}</span>
          <textarea v-model="notesValue" class="notes-input" rows="3" :placeholder="$t('myDocs.notesPlaceholder')" @blur="commitNotes" />
        </div>
      </div>

      <!-- 右侧:属性面板 -->
      <div class="props">
        <div class="props-title">{{ selectedShape ? (selectedShape.name || selectedShape.kind) : t('myDocs.slidePropsNone') }}</div>
        <template v-if="selectedShape">
          <div class="prop-row">
            <span>X</span>
            <el-input-number size="small" :model-value="Math.round(selectedShape.rect?.x || 0)" :min="0" :max="960" @change="v => { selectedShape.rect.x = v; wrapChange() }" />
            <span class="prop-unit">px</span>
          </div>
          <div class="prop-row">
            <span>Y</span>
            <el-input-number size="small" :model-value="Math.round(selectedShape.rect?.y || 0)" :min="0" :max="540" @change="v => { selectedShape.rect.y = v; wrapChange() }" />
            <span class="prop-unit">px</span>
          </div>
          <div class="prop-row">
            <span>W</span>
            <el-input-number size="small" :model-value="Math.round(selectedShape.rect?.w || 100)" :min="1" :max="960" @change="v => { selectedShape.rect.w = v; wrapChange() }" />
            <span class="prop-unit">px</span>
          </div>
          <div class="prop-row">
            <span>H</span>
            <el-input-number size="small" :model-value="Math.round(selectedShape.rect?.h || 50)" :min="1" :max="540" @change="v => { selectedShape.rect.h = v; wrapChange() }" />
            <span class="prop-unit">px</span>
          </div>
          <div class="prop-row">
            <span>{{ $t('myDocs.fillColor') }}</span>
            <input type="color" class="prop-color" :value="(selectedShape.fill || '#2196f3').slice(0, 7)" @change="e => { selectedShape.fill = e.target.value; wrapChange() }" />
          </div>
          <div class="prop-row">
            <span>{{ $t('myDocs.strokeColor') }}</span>
            <input type="color" class="prop-color" :value="(selectedShape.stroke || '#2196f3').slice(0, 7)" @change="e => { selectedShape.stroke = e.target.value; wrapChange() }" />
          </div>
          <el-divider />
          <template v-if="selectedPara">
            <div class="props-title">{{ t('myDocs.fontFormat') }}</div>
            <div class="prop-row">
              <TbButton :active="selectedPara.bold" :title="$t('myDocs.shortcutBold')" @click="toggleP(selectedPara, 'bold')"><b>B</b></TbButton>
              <TbButton :active="selectedPara.italic" :title="$t('myDocs.shortcutItalic')" @click="toggleP(selectedPara, 'italic')"><i>I</i></TbButton>
              <TbButton :active="selectedPara.underline" :title="$t('myDocs.shortcutUnderline')" @click="toggleP(selectedPara, 'underline')"><u>U</u></TbButton>
              <input type="color" class="prop-color" :value="(selectedPara.color || '#000000').slice(0, 7)" @change="e => setP(selectedPara, 'color', e.target.value.replace('#','').toUpperCase())" :title="$t('myDocs.fontColor')" />
            </div>
            <div class="prop-row">
              <TbSelect
                :model-value="selectedPara.font || ''"
                :options="FONT_FAMILY_OPTIONS"
                :placeholder="$t('myDocs.fontFamily')"
                width="100px"
                @change="v => setP(selectedPara, 'font', v)"
              />
              <TbSelect
                :model-value="selectedPara.size || ''"
                :options="FONT_SIZE_OPTIONS"
                :placeholder="$t('myDocs.fontSize')"
                width="64px"
                @change="v => setP(selectedPara, 'size', v)"
              />
            </div>
            <div class="prop-row">
              <TbButton :active="selectedPara.align === 'left'" :title="$t('myDocs.alignLeft')" @click="setP(selectedPara, 'align', 'left')"><IconAlignLeft /></TbButton>
              <TbButton :active="selectedPara.align === 'center'" :title="$t('myDocs.alignCenter')" @click="setP(selectedPara, 'align', 'center')"><IconAlignCenter /></TbButton>
              <TbButton :active="selectedPara.align === 'right'" :title="$t('myDocs.alignRight')" @click="setP(selectedPara, 'align', 'right')"><IconAlignRight /></TbButton>
              <TbButton :active="selectedPara.align === 'justify'" :title="$t('myDocs.alignJustify')" @click="setP(selectedPara, 'align', 'justify')"><IconAlignJustify /></TbButton>
            </div>
          </template>
          <el-button size="small" type="danger" plain style="margin-top: 12px" @click="removeSelected">{{ $t('myDocs.slideDelete') }}</el-button>
        </template>
        <template v-else>
          <p class="prop-hint">{{ t('myDocs.slidePropsHint') }}</p>
        </template>
      </div>
    </div>

    <!-- 底部状态栏 -->
    <div class="statusbar">
      <span class="st-item">{{ $t('myDocs.statusPage', { n: active + 1, total: slides.length }) }}</span>
      <span class="st-sep" />
      <span class="st-item">{{ current.shapes?.length || 0 }} {{ $t('myDocs.slideShapes') }}</span>
      <span class="st-sep" />
      <span class="st-item">{{ current.transition && current.transition !== 'none' ? t('myDocs.slideTransition') + ': ' + current.transition : '' }}</span>
      <span class="grow" />
      <span class="st-item">{{ zoom }}%</span>
    </div>
  </div>
  <div v-else class="slide-loading">{{ $t('myDocs.loading') }}</div>
</template>

<style scoped>
.slide-editor { display: flex; flex-direction: column; height: 100%; background: #f0f0f3; }
.slide-loading { padding: 40px; text-align: center; color: var(--c-text-3); }
.tb-label { font-size: 12px; color: var(--c-text-3); margin-right: 2px; }
.thumb-anim { margin-left: 4px; color: var(--c-primary); font-size: 10px; }

.body { flex: 1; display: flex; min-height: 0; }
.thumbs { display: flex; flex-direction: column; gap: 8px; overflow-y: auto; padding: 12px; background: #ececee; border-right: 1px solid var(--c-border); width: 160px; }
.thumb { border: 1px solid var(--c-border); border-radius: 4px; background: #fff; cursor: pointer; overflow: hidden; padding: 4px; }
.thumb.on { border-color: var(--c-primary); box-shadow: 0 0 0 2px var(--c-primary-bg); }
.thumb-no { font-size: 11px; color: var(--c-text-3); padding: 2px 4px; display: flex; align-items: center; gap: 4px; }
.thumb-preview { width: 100%; aspect-ratio: 16/9; background: #fff; position: relative; overflow: hidden; }
.add-slide { width: 100%; padding: 6px; background: #fff; border: 1px dashed var(--c-border); border-radius: 4px; color: var(--c-text-3); cursor: pointer; font-size: 18px; }
.add-slide:hover { background: var(--c-primary-bg); color: var(--c-primary); border-color: var(--c-primary); }

.canvas-area { flex: 1; display: flex; flex-direction: column; background: #ececee; position: relative; min-width: 0; }
.ruler-h { height: 16px; background: #fff; border-bottom: 1px solid #d0d0d0; position: relative; }
.ruler-v { width: 16px; position: absolute; left: 0; top: 16px; bottom: 130px; background: #fff; border-right: 1px solid #d0d0d0; }
.ruler-mark { position: absolute; font-size: 9px; color: #999; }
.ruler-h .ruler-mark { top: 0; line-height: 16px; padding-left: 2px; }
.ruler-v .ruler-mark { left: 0; padding-top: 2px; writing-mode: vertical-lr; }

.stage { flex: 1; overflow: auto; display: flex; align-items: center; justify-content: center; padding: 30px; min-height: 0; }
.canvas { background: #fff; box-shadow: 0 4px 24px rgba(0,0,0,.12); position: relative; overflow: hidden; background-image: linear-gradient(rgba(0,0,0,.04) 1px, transparent 1px), linear-gradient(90deg, rgba(0,0,0,.04) 1px, transparent 1px); background-size: 24px 24px; transform-origin: top left; }
.shape { cursor: pointer; }
.shape:hover { outline: 1px dashed rgba(0,0,0,.2); }
.shape.selected { outline: 2px solid #1a73e8 !important; z-index: 5; }
.shape-text { width: 100%; height: 100%; display: flex; flex-direction: column; justify-content: center; align-items: stretch; padding: 4px; box-sizing: border-box; }
.shape-text .para { width: 100%; }
.shape-input { width: 100%; border: 0; outline: 0; background: transparent; resize: none; font-family: inherit; color: inherit; font-size: inherit; font-weight: inherit; font-style: inherit; text-align: inherit; line-height: 1.2; padding: 0; }

.notes { height: 100px; background: #fff; border-top: 1px solid var(--c-border); padding: 8px 12px; flex-shrink: 0; }
.notes-label { display: block; font-size: 11px; color: var(--c-text-3); margin-bottom: 4px; font-weight: 600; }
.notes-input { width: 100%; border: 0; outline: 0; resize: vertical; font-family: inherit; font-size: 12px; color: var(--c-text-2); background: #fafbfc; padding: 4px 6px; border-radius: 3px; min-height: 50px; }

.props { width: 240px; background: #fff; border-left: 1px solid var(--c-border); padding: 12px; overflow-y: auto; }
.props-title { font-size: 13px; font-weight: 600; color: var(--c-text-1); margin-bottom: 8px; }
.prop-row { display: flex; align-items: center; gap: 6px; margin-bottom: 8px; flex-wrap: wrap; }
.prop-row span { font-size: 12px; color: var(--c-text-3); }
.prop-unit { font-size: 11px; color: var(--c-text-3); min-width: 16px; }
.prop-color { width: 28px; height: 24px; padding: 0; border: 1px solid var(--c-border); border-radius: 4px; background: none; cursor: pointer; }
.prop-hint { font-size: 12px; color: var(--c-text-4); line-height: 1.6; }

.statusbar { display: flex; align-items: center; gap: 8px; padding: 4px 12px; background: #fff; border-top: 1px solid var(--c-border); font-size: 12px; color: var(--c-text-3); }
.st-sep { width: 1px; height: 12px; background: var(--c-border); }
.grow { flex: 1; }

/* 工具栏灰白调 */
.slide-editor :deep(.editor-toolbar) { background: #fcfcfd; border-bottom: 1px solid #e8e8ec; }
</style>
