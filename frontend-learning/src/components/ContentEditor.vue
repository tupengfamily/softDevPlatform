<script setup>
// 内容编辑器: 支持 Markdown 与富文本(Quill)两种格式切换
// - Markdown 模式: 基础语法工具条(标题/粗斜体/行内代码/代码块/列表/链接/引用)
//   + 实时预览(开源 marked + DOMPurify 渲染)
// - 富文本模式: Quill 工具栏
// - 全屏编辑: 两种模式均支持,Esc 退出(不触发外层弹窗关闭)
// - props.modelValue: 正文; contentType: 'markdown' | 'richtext'
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { QuillEditor } from '@vueup/vue-quill'
import '@vueup/vue-quill/dist/vue-quill.snow.css'
import { renderMarkdown } from '../utils/markdown'

const props = defineProps({
  modelValue: { type: String, default: '' },
  contentType: { type: String, default: 'markdown' },   // markdown | richtext
  placeholder: { type: String, default: '' },
  height: { type: Number, default: 220 }
})
const emit = defineEmits(['update:modelValue', 'update:contentType'])

const { t } = useI18n()
// 占位符: 外部传入原文/翻译 key 均可,缺省用组件内文案
const placeholderText = computed(() => t(props.placeholder || 'contentEditor.placeholder'))

const type = ref(props.contentType)
const text = ref(props.modelValue)
const quillText = ref(props.contentType === 'richtext' ? props.modelValue : '')
const mdTab = ref('edit')          // markdown 模式: edit | preview
const textareaRef = ref(null)

// ============== 全屏编辑 ==============
const fullscreen = ref(false)
let bodyLocked = false
const toggleFullscreen = () => {
  fullscreen.value = !fullscreen.value
  if (fullscreen.value) {
    if (document.body.style.overflow !== 'hidden') {
      document.body.style.overflow = 'hidden'
      bodyLocked = true
    }
  } else if (bodyLocked) {
    document.body.style.overflow = ''
    bodyLocked = false
  }
}
// Esc 退出全屏;capture+stopPropagation 避免连带关闭外层 el-dialog
const onGlobalKeydown = (e) => {
  if (!fullscreen.value || e.key !== 'Escape') return
  e.preventDefault()
  e.stopPropagation()
  toggleFullscreen()
}
onMounted(() => window.addEventListener('keydown', onGlobalKeydown, true))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown, true)
  if (bodyLocked) document.body.style.overflow = ''
})

// contentType 与 modelValue 可能同帧变化(如草稿恢复),两个 watcher 均回填内容,
// 保证无论执行顺序如何,内容最终落在当前模式的缓冲区
watch(() => props.contentType, (v) => {
  type.value = v
  if (v === 'richtext') quillText.value = props.modelValue
  else text.value = props.modelValue
})
watch(() => props.modelValue, (v) => {
  if (type.value === 'richtext') quillText.value = v
  else text.value = v
})

const previewHtml = computed(() => renderMarkdown(text.value))

// 安全取 HTML 纯文本: DOMParser 解析不执行脚本,规避 innerHTML XSS sink
const htmlToText = (html) => {
  const doc = new DOMParser().parseFromString(html || '', 'text/html')
  return doc.body.textContent || ''
}

const editorOptions = computed(() => ({
  placeholder: placeholderText.value,
  modules: {
    toolbar: [
      [{ header: [1, 2, 3, false] }],
      ['bold', 'italic', 'underline', 'strike'],
      [{ color: [] }, { background: [] }],
      [{ list: 'ordered' }, { list: 'bullet' }],
      ['blockquote', 'code-block', 'link'],
      ['clean']
    ]
  }
}))

const switchType = (t) => {
  if (t === type.value) return
  // 原文保留: Markdown <-> 富文本 不做自动转换
  if (t === 'richtext') {
    quillText.value = text.value ? `<p>${text.value.replace(/\n/g, '</p><p>')}</p>` : ''
    emit('update:modelValue', quillText.value)
  } else {
    // 富文本 -> Markdown: 取纯文本
    text.value = htmlToText(quillText.value)
    emit('update:modelValue', text.value)
    mdTab.value = 'edit'
  }
  type.value = t
  emit('update:contentType', t)
}

const onQuillInput = (html) => {
  quillText.value = html
  emit('update:modelValue', html)
}
const onTextInput = (e) => {
  text.value = e.target.value
  emit('update:modelValue', e.target.value)
}

// ============== Markdown 基础语法工具条 ==============
// 在光标/选区处插入语法;若无选区则插入占位示例
const toolbarItems = computed(() => [
  { key: 'h2',    label: 'H2',   title: t('contentEditor.tool.h2'), before: '## ',     after: '',        sample: t('contentEditor.sample.heading') },
  { key: 'h3',    label: 'H3',   title: t('contentEditor.tool.h3'), before: '### ',    after: '',        sample: t('contentEditor.sample.heading') },
  { key: 'bold',  label: 'B',    title: t('contentEditor.tool.bold'), before: '**',    after: '**',      sample: t('contentEditor.sample.bold'), cls: 'b' },
  { key: 'italic',label: 'I',    title: t('contentEditor.tool.italic'), before: '*',   after: '*',       sample: t('contentEditor.sample.italic'), cls: 'i' },
  { key: 'code',  label: '</>',  title: t('contentEditor.tool.code'), before: '`',     after: '`',       sample: 'code' },
  { key: 'codeblk', label: '{ }', title: t('contentEditor.tool.codeblk'), before: '```\n', after: '\n```', sample: t('contentEditor.sample.codeblk') },
  { key: 'ul',    label: '•—',   title: t('contentEditor.tool.ul'), before: '- ',      after: '',        sample: t('contentEditor.sample.list') },
  { key: 'ol',    label: '1.',   title: t('contentEditor.tool.ol'), before: '1. ',     after: '',        sample: t('contentEditor.sample.list') },
  { key: 'quote', label: '❝',    title: t('contentEditor.tool.quote'), before: '> ',    after: '',        sample: t('contentEditor.sample.quote') },
  { key: 'link',  label: '🔗',   title: t('contentEditor.tool.link'), before: '[',      after: '](https://)', sample: t('contentEditor.sample.link') },
  { key: 'hr',    label: '—',    title: t('contentEditor.tool.hr'), before: '\n---\n', after: '',        sample: '' }
])

const applyMd = (item) => {
  const ta = textareaRef.value
  if (!ta) return
  const start = ta.selectionStart
  const end = ta.selectionEnd
  const selected = text.value.slice(start, end) || item.sample
  const next = text.value.slice(0, start) + item.before + selected + item.after + text.value.slice(end)
  text.value = next
  emit('update:modelValue', next)
  // 保持选区落在插入内容上,便于连续编辑
  requestAnimationFrame(() => {
    ta.focus()
    const selStart = start + item.before.length
    ta.setSelectionRange(selStart, selStart + selected.length)
  })
}

defineExpose({
  /** 供表单校验: 是否有内容(去 HTML 标签后非空) */
  isEmpty() {
    if (type.value === 'richtext') {
      return !htmlToText(quillText.value).trim()
    }
    return !text.value.trim()
  },
  getType: () => type.value
})
</script>

<template>
  <div class="content-editor" :class="{ fullscreen }">
    <div class="type-switch">
      <button type="button" :class="{ active: type === 'markdown' }" @click="switchType('markdown')">Markdown</button>
      <button type="button" :class="{ active: type === 'richtext' }" @click="switchType('richtext')">{{ $t('contentEditor.richtext') }}</button>
      <button type="button" class="fs-btn" :title="fullscreen ? $t('contentEditor.exitFsTitle') : $t('contentEditor.enterFsTitle')" @click="toggleFullscreen">
        {{ fullscreen ? $t('contentEditor.exitFs') : $t('contentEditor.enterFs') }}
      </button>
    </div>

    <template v-if="type === 'markdown'">
      <!-- 基础语法工具条 + 编辑/预览切换 -->
      <div class="md-toolbar">
        <button
          v-for="item in toolbarItems"
          :key="item.key"
          type="button"
          class="md-tool"
          :class="item.cls"
          :title="item.title"
          @click="applyMd(item)"
        >{{ item.label }}</button>
        <span class="md-tabs">
          <button type="button" :class="{ active: mdTab === 'edit' }" @click="mdTab = 'edit'">{{ $t('contentEditor.edit') }}</button>
          <button type="button" :class="{ active: mdTab === 'preview' }" @click="mdTab = 'preview'">{{ $t('contentEditor.preview') }}</button>
        </span>
      </div>

      <textarea
        v-show="mdTab === 'edit'"
        ref="textareaRef"
        class="md-input"
        :style="{ minHeight: height + 'px' }"
        :value="text"
        :placeholder="placeholderText + ' ' + $t('contentEditor.mdHint')"
        @input="onTextInput"
      ></textarea>
      <div
        v-show="mdTab === 'preview'"
        class="md-preview md-body"
        :style="{ minHeight: height + 'px' }"
        v-html="previewHtml"
      ></div>
    </template>

    <!-- 包裹层承载样式(QuillEditor 根节点不带 scope 属性) -->
    <div v-else class="quill-box">
      <QuillEditor
        :key="'quill'"
        :content="quillText"
        content-type="html"
        :options="editorOptions"
        :style="{ minHeight: height + 'px' }"
        @update:content="onQuillInput"
      />
    </div>
  </div>
</template>

<style scoped>
.content-editor { border: 1px solid var(--c-border); border-radius: var(--r-s); overflow: hidden; width: 100%; box-sizing: border-box; }
.type-switch { display: flex; gap: 0; background: #f8fafc; border-bottom: 1px solid var(--c-border); }
.type-switch button {
  padding: 6px 16px; font-size: var(--fs-xs); color: var(--c-text-2);
  background: transparent; border: none; cursor: pointer; border-bottom: 2px solid transparent;
}
.type-switch button.active { color: var(--c-primary); border-bottom-color: var(--c-primary); background: #fff; font-weight: 600; }
.fs-btn {
  margin-left: auto; padding: 6px 12px; border: none; background: transparent;
  color: var(--c-text-2); font-size: var(--fs-xs); cursor: pointer;
}
.fs-btn:hover { color: var(--c-primary); background: var(--c-primary-bg); }

/* 全屏编辑模式 */
.content-editor.fullscreen {
  position: fixed; inset: 0; z-index: 9999; border: none; border-radius: 0;
  background: #fff; display: flex; flex-direction: column; overflow: hidden;
}
.content-editor.fullscreen .type-switch,
.content-editor.fullscreen .md-toolbar,
.content-editor.fullscreen :deep(.ql-toolbar) { flex: none; }
.content-editor.fullscreen .md-input,
.content-editor.fullscreen .md-preview {
  flex: 1 1 auto; min-height: 0 !important; height: auto !important; resize: none;
}
.content-editor.fullscreen .quill-box {
  flex: 1 1 auto; min-height: 0 !important; display: flex; flex-direction: column; overflow: hidden;
}
.content-editor.fullscreen .quill-box :deep(.ql-container) {
  flex: 1 1 auto; min-height: 0 !important; overflow-y: auto;
}
.content-editor.fullscreen .quill-box :deep(.ql-editor) { min-height: 100% !important; }

/* Markdown 工具条 */
.md-toolbar {
  display: flex; align-items: center; gap: 2px; flex-wrap: wrap;
  padding: 5px 8px; background: #fbfcfe; border-bottom: 1px solid var(--c-border);
}
.md-tool {
  min-width: 28px; height: 24px; padding: 0 6px; border: none; border-radius: 4px;
  background: transparent; color: var(--c-text-2); font-size: var(--fs-xs); cursor: pointer;
}
.md-tool:hover { background: var(--c-primary-bg); color: var(--c-primary); }
.md-tool.b { font-weight: 700; }
.md-tool.i { font-style: italic; font-family: Georgia, serif; }
.md-tabs { margin-left: auto; display: inline-flex; gap: 2px; }
.md-tabs button {
  padding: 3px 12px; border: none; border-radius: var(--r-full); background: transparent;
  color: var(--c-text-3); font-size: var(--fs-xs); cursor: pointer;
}
.md-tabs button.active { background: var(--c-primary); color: #fff; }

.md-input {
  width: 100%; border: none; outline: none; resize: vertical; padding: 12px 14px;
  font-size: var(--fs-base); font-family: 'JetBrains Mono', Menlo, monospace; line-height: 1.7; color: var(--c-text-1);
  box-sizing: border-box;
}
.md-preview { padding: 12px 16px; overflow: auto; box-sizing: border-box; }

/* 预览区 Markdown 样式(与课时正文一致) */
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3), .md-body :deep(h4) {
  font-size: var(--fs-md); margin: 14px 0 8px; color: var(--c-text-1);
}
.md-body :deep(p) { margin: 0 0 10px; line-height: 1.8; font-size: var(--fs-base); }
.md-body :deep(ul), .md-body :deep(ol) { padding-left: 20px; margin: 0 0 10px; }
.md-body :deep(code) {
  font-family: 'JetBrains Mono', Menlo, monospace; font-size: .88em;
  background: #f1f4f9; color: #b93a86; padding: 1px 6px; border-radius: 4px;
}
.md-body :deep(pre) {
  margin: 0 0 12px; padding: 12px 14px; background: #0f172a; color: #e2e8f0;
  border-radius: var(--r-s); overflow-x: auto; font-size: var(--fs-sm); line-height: 1.6;
}
.md-body :deep(pre code) { background: none; color: inherit; padding: 0; }
.md-body :deep(blockquote) {
  margin: 8px 0 12px; padding: 8px 12px; border-left: 3px solid var(--c-primary);
  background: var(--c-primary-bg); color: var(--c-text-2);
}
.md-body :deep(blockquote p) { margin: 0; }
.md-body :deep(a) { color: var(--c-primary); text-decoration: underline; }
.md-body :deep(hr) { border: none; border-top: 1px solid var(--c-border); margin: 14px 0; }
.md-body :deep(table) { border-collapse: collapse; margin: 0 0 12px; width: 100%; font-size: var(--fs-sm); }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid var(--c-border); padding: 6px 10px; text-align: left; }
.md-body :deep(th) { background: #f8fafd; }
.md-body :deep(img) { max-width: 100%; }

:deep(.ql-toolbar.ql-snow), :deep(.ql-container.ql-snow) { border: none !important; }
:deep(.ql-container.ql-snow) { font-size: var(--fs-base); }
:deep(.ql-editor) { min-height: v-bind(height + 'px'); }
</style>
