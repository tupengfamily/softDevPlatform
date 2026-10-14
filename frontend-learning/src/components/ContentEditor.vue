<script setup>
// 内容编辑器: 支持 Markdown (bytemd) 与 富文本 (Tiptap) 两种格式切换
// - Markdown 模式: bytemd `<Editor>` 组件(基于 CodeMirror 6 + remark) + GFM/代码高亮/medium-zoom/frontmatter
//   + 内置工具条;图片上传走通用 /api/uploads/image
//   + 顶部"预览"按钮:用 `<Viewer>` 组件渲染效果(同一套样式)
// - 富文本模式: Tiptap(ProseMirror)+ StarterKit + Image/Table/Underline/TextAlign/Color/Link
//   + 自定义工具条(粗/斜/下划线/删除线/代码/H1-3/列表/引用/代码块/对齐/颜色/链接/图片/表格/撤销/重做)
// - 全屏编辑: 两种模式均支持, Esc 退出
// - props.modelValue: 正文(HTML/Markdown); contentType: 'markdown' | 'richtext'
//
// Tiptap 是 GitHub 关注度最高的开源富文本(ProseMirror 内核);
// bytemd 来自字节跳动,基于 CodeMirror 6 + remark,Notion 风格双栏/即时渲染。
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Editor as TiptapEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import Table from '@tiptap/extension-table'
import TableRow from '@tiptap/extension-table-row'
import TableCell from '@tiptap/extension-table-cell'
import TableHeader from '@tiptap/extension-table-header'
import Link from '@tiptap/extension-link'
import Underline from '@tiptap/extension-underline'
import TextAlign from '@tiptap/extension-text-align'
import Color from '@tiptap/extension-color'
import TextStyle from '@tiptap/extension-text-style'
import { Editor as ByteMDEditor, Viewer as ByteMDViewer } from '@bytemd/vue-next'
import gfm from '@bytemd/plugin-gfm'
import highlight from '@bytemd/plugin-highlight'
import mediumZoom from '@bytemd/plugin-medium-zoom'
import frontmatter from '@bytemd/plugin-frontmatter'
import 'bytemd/dist/index.css'
import 'highlight.js/styles/github.css'
import { uploadImage } from '../api/upload'

const props = defineProps({
  modelValue: { type: String, default: '' },
  contentType: { type: String, default: 'markdown' },
  placeholder: { type: String, default: '' },
  height: { type: Number, default: 360 },
  // 锁定类型: 已创建内容的格式不允许再切换(仅展示当前格式标签)
  lockType: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'update:contentType'])

const { t } = useI18n()
const placeholderText = computed(() => t(props.placeholder || 'contentEditor.placeholder'))

const type = ref(props.contentType)
const text = ref(props.modelValue)                                    // markdown text
const html = ref(props.contentType === 'richtext' ? props.modelValue : '') // tiptap html

// ============== 全屏编辑 ==============
const fullscreen = ref(false)
let bodyLocked = false
const toggleFullscreen = () => {
  fullscreen.value = !fullscreen.value
  if (fullscreen.value) {
    if (document.body.style.overflow !== 'hidden') { document.body.style.overflow = 'hidden'; bodyLocked = true }
  } else if (bodyLocked) {
    document.body.style.overflow = ''; bodyLocked = false
  }
}
const onGlobalKeydown = (e) => {
  if (!fullscreen.value || e.key !== 'Escape') return
  e.preventDefault(); e.stopPropagation(); toggleFullscreen()
}
onMounted(() => window.addEventListener('keydown', onGlobalKeydown, true))
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onGlobalKeydown, true)
  if (bodyLocked) document.body.style.overflow = ''
})

// 外部值/类型变化同步到本地状态(父组件决定)
watch(() => props.contentType, (v) => {
  if (v === type.value) return
  // 模式切换:保留各自原文,不互转(避免失真);父组件应明确选择格式
  if (v === 'richtext') html.value = props.modelValue
  else text.value = props.modelValue
  type.value = v
  // 切回时强制重新挂载(Tiptap/bytemd 内部状态)
  nextTick(() => { remountKey.value++ })
})
watch(() => props.modelValue, (v) => {
  if (type.value === 'richtext' && v !== html.value) html.value = v
  if (type.value === 'markdown' && v !== text.value) text.value = v
})

// 用 remountKey 强制重建编辑器实例(切换模式/bytemd 内部状态错乱时使用)
const remountKey = ref(0)

// ============== 图片上传通用 ==============
const uploadOneImage = async (file) => {
  const okTypes = ['image/png', 'image/jpeg', 'image/jpg', 'image/gif', 'image/webp', 'image/bmp']
  if (!okTypes.includes(file.type)) { ElMessage.error(t('contentEditor.image.typeError')); throw new Error('type') }
  if (file.size > 5 * 1024 * 1024) { ElMessage.error(t('contentEditor.image.sizeError')); throw new Error('size') }
  const res = await uploadImage(file)
  return res.data?.url
}

// ============== bytemd 配置 ==============
const bytemdPlugins = [gfm(), highlight(), mediumZoom(), frontmatter()]

// bytemd 内部 uploadImages:files -> {url, ...}[]
const bytemdUpload = async (files) => {
  const out = []
  for (const f of files) {
    try { out.push({ url: await uploadOneImage(f), name: f.name }) } catch (_) { out.push({ url: '' }) }
  }
  return out
}

// ============== Tiptap 配置 ==============
const tiptapEditor = shallowRef(null)

const tiptapExtensions = [
  StarterKit.configure({ codeBlock: { HTMLAttributes: { class: 'tiptap-code' } } }),
  Underline,
  TextStyle,
  Color,
  Link.configure({ openOnClick: false, autolink: true, HTMLAttributes: { rel: 'noopener noreferrer', target: '_blank' } }),
  Image.configure({ inline: false, allowBase64: false }),
  Table.configure({ resizable: true, HTMLAttributes: { class: 'tiptap-table' } }),
  TableRow,
  TableHeader,
  TableCell,
  TextAlign.configure({ types: ['heading', 'paragraph'] })
]

const initTiptap = () => {
  if (tiptapEditor.value) { try { tiptapEditor.value.destroy() } catch (_) {} }
  tiptapEditor.value = new TiptapEditor({
    extensions: tiptapExtensions,
    content: html.value || '<p></p>',
    editorProps: { attributes: { class: 'tiptap-content' } },
    onUpdate: ({ editor: e }) => {
      const next = e.getHTML()
      html.value = next
      emit('update:modelValue', next)
    }
  })
}

const tiptapToolbar = computed(() => [
  { key: 'bold',   icon: 'B',  title: t('contentEditor.tool.bold'),     cls: 'b', cmd: () => tiptapEditor.value?.chain().focus().toggleBold().run(),     active: () => tiptapEditor.value?.isActive('bold') },
  { key: 'italic', icon: 'I',  title: t('contentEditor.tool.italic'),   cls: 'i', cmd: () => tiptapEditor.value?.chain().focus().toggleItalic().run(),   active: () => tiptapEditor.value?.isActive('italic') },
  { key: 'underline', icon: 'U', title: t('contentEditor.tool.underline'), cls: 'u', cmd: () => tiptapEditor.value?.chain().focus().toggleUnderline().run(), active: () => tiptapEditor.value?.isActive('underline') },
  { key: 'strike', icon: 'S̶', title: t('contentEditor.tool.strike'),    cls: 's', cmd: () => tiptapEditor.value?.chain().focus().toggleStrike().run(),    active: () => tiptapEditor.value?.isActive('strike') },
  { key: 'code',   icon: '</>', title: t('contentEditor.tool.code'),     cmd: () => tiptapEditor.value?.chain().focus().toggleCode().run(),          active: () => tiptapEditor.value?.isActive('code') },
  { key: 'h1', icon: 'H1', title: t('contentEditor.tool.h1'), cmd: () => tiptapEditor.value?.chain().focus().toggleHeading({ level: 1 }).run(), active: () => tiptapEditor.value?.isActive('heading', { level: 1 }) },
  { key: 'h2', icon: 'H2', title: t('contentEditor.tool.h2'), cmd: () => tiptapEditor.value?.chain().focus().toggleHeading({ level: 2 }).run(), active: () => tiptapEditor.value?.isActive('heading', { level: 2 }) },
  { key: 'h3', icon: 'H3', title: t('contentEditor.tool.h3'), cmd: () => tiptapEditor.value?.chain().focus().toggleHeading({ level: 3 }).run(), active: () => tiptapEditor.value?.isActive('heading', { level: 3 }) },
  { key: 'ul', icon: '•—', title: t('contentEditor.tool.ul'),  cmd: () => tiptapEditor.value?.chain().focus().toggleBulletList().run(),  active: () => tiptapEditor.value?.isActive('bulletList') },
  { key: 'ol', icon: '1.', title: t('contentEditor.tool.ol'),  cmd: () => tiptapEditor.value?.chain().focus().toggleOrderedList().run(), active: () => tiptapEditor.value?.isActive('orderedList') },
  { key: 'quote', icon: '❝',  title: t('contentEditor.tool.quote'), cmd: () => tiptapEditor.value?.chain().focus().toggleBlockquote().run(),  active: () => tiptapEditor.value?.isActive('blockquote') },
  { key: 'codeblk', icon: '{ }', title: t('contentEditor.tool.codeblk'), cmd: () => tiptapEditor.value?.chain().focus().toggleCodeBlock().run(), active: () => tiptapEditor.value?.isActive('codeBlock') },
  { key: 'left',   icon: '⇤',  title: t('contentEditor.tool.alignLeft'),   cmd: () => tiptapEditor.value?.chain().focus().setTextAlign('left').run() },
  { key: 'center', icon: '↔',  title: t('contentEditor.tool.alignCenter'), cmd: () => tiptapEditor.value?.chain().focus().setTextAlign('center').run() },
  { key: 'right',  icon: '⇥',  title: t('contentEditor.tool.alignRight'),  cmd: () => tiptapEditor.value?.chain().focus().setTextAlign('right').run() },
  { key: 'color',  icon: '🎨', title: t('contentEditor.tool.color'), cmd: () => pickColor() },
  { key: 'link',   icon: '🔗', title: t('contentEditor.tool.link'),  cmd: () => promptLink() },
  { key: 'image',  icon: '🖼',  title: t('contentEditor.tool.image'), cmd: () => pickImage() },
  { key: 'table',  icon: '⊞',  title: t('contentEditor.tool.table'), cmd: () => tiptapEditor.value?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run() },
  { key: 'undo',   icon: '↶',  title: t('contentEditor.tool.undo'),  cmd: () => tiptapEditor.value?.chain().focus().undo().run() },
  { key: 'redo',   icon: '↷',  title: t('contentEditor.tool.redo'),  cmd: () => tiptapEditor.value?.chain().focus().redo().run() }
])

const pickColor = () => {
  if (!tiptapEditor.value) return
  const input = document.createElement('input')
  input.type = 'color'
  input.value = tiptapEditor.value.getAttributes('textStyle').color || '#000000'
  input.onchange = () => tiptapEditor.value.chain().focus().setColor(input.value).run()
  input.click()
}
const promptLink = () => {
  if (!tiptapEditor.value) return
  const prev = tiptapEditor.value.getAttributes('link').href || ''
  const url = (window.prompt(t('contentEditor.tool.linkPrompt') || 'URL', prev || 'https://') || '').trim()
  if (!url) { tiptapEditor.value.chain().focus().unsetLink().run(); return }
  tiptapEditor.value.chain().focus().extendMarkRange('link').setLink({ href: url }).run()
}
const pickImage = () => {
  const input = document.createElement('input')
  input.type = 'file'
  input.accept = 'image/png,image/jpeg,image/gif,image/webp,image/bmp'
  input.onchange = async () => {
    const f = input.files?.[0]
    if (!f) return
    try {
      const url = await uploadOneImage(f)
      tiptapEditor.value?.chain().focus().setImage({ src: url, alt: '' }).run()
    } catch (_) { /* 弹错已显示 */ }
  }
  input.click()
}

// Tiptap 容器:类型切到 richtext 时立即初始化
const tiptapContainer = ref(null)
watch(type, async (v) => {
  if (v === 'richtext') {
    await nextTick(); initTiptap()
  } else {
    if (tiptapEditor.value) { try { tiptapEditor.value.destroy() } catch (_) {} tiptapEditor.value = null }
  }
}, { immediate: true })

// ============== Markdown 模式(由 bytemd 提供) ==============
const mdTab = ref('edit')   // edit | preview
const onMdChange = (v) => {
  text.value = v
  emit('update:modelValue', v)
}

onBeforeUnmount(() => {
  if (tiptapEditor.value) { try { tiptapEditor.value.destroy() } catch (_) {} }
})

defineExpose({
  isEmpty() {
    if (type.value === 'richtext') return !tiptapEditor.value || tiptapEditor.value.isEmpty
    return !text.value.trim()
  },
  getType: () => type.value
})
</script>

<template>
  <div class="content-editor" :class="{ fullscreen }">
    <div class="type-switch">
      <template v-if="!lockType">
        <button type="button" :class="{ active: type === 'markdown' }" @click="type = 'markdown'; emit('update:contentType', 'markdown')">Markdown</button>
        <button type="button" :class="{ active: type === 'richtext' }" @click="type = 'richtext'; emit('update:contentType', 'richtext')">{{ $t('contentEditor.richtext') }}</button>
      </template>
      <span v-else class="locked">
        <el-icon><Lock /></el-icon>
        {{ type === 'richtext' ? $t('wiki.richType') : $t('wiki.mdType') }}
        <small>{{ $t('contentEditor.typeLocked') }}</small>
      </span>
      <span class="mode-tip" v-if="type === 'markdown'">bytemd · {{ mdTab === 'edit' ? $t('contentEditor.edit') : $t('contentEditor.preview') }}</span>
      <span class="mode-tip" v-else>Tiptap · ProseMirror</span>
      <button type="button" class="fs-btn" :title="fullscreen ? $t('contentEditor.exitFsTitle') : $t('contentEditor.enterFsTitle')" @click="toggleFullscreen">
        {{ fullscreen ? $t('contentEditor.exitFs') : $t('contentEditor.enterFs') }}
      </button>
    </div>

    <!-- ========== Markdown: bytemd ========== -->
    <template v-if="type === 'markdown'">
      <div class="md-tabs">
        <button type="button" :class="{ active: mdTab === 'edit' }" @click="mdTab = 'edit'">{{ $t('contentEditor.edit') }}</button>
        <button type="button" :class="{ active: mdTab === 'preview' }" @click="mdTab = 'preview'">{{ $t('contentEditor.preview') }}</button>
      </div>
      <div v-show="mdTab === 'edit'" class="byte-body" :style="{ minHeight: height + 'px' }">
        <ByteMDEditor
          :key="`md-${remountKey}`"
          :value="text"
          :placeholder="placeholderText + ' ' + $t('contentEditor.mdHint')"
          :plugins="bytemdPlugins"
          :upload-images="bytemdUpload"
          @change="onMdChange"
        />
      </div>
      <div v-show="mdTab === 'preview'" class="md-preview" :style="{ minHeight: height + 'px' }">
        <ByteMDViewer :key="`preview-${remountKey}`" :value="text" :plugins="bytemdPlugins" />
      </div>
    </template>

    <!-- ========== 富文本: Tiptap ========== -->
    <template v-else>
      <div class="tt-toolbar">
        <button v-for="item in tiptapToolbar" :key="item.key" type="button" class="tt-tool" :class="[item.cls, { active: item.active?.() }]" :title="item.title" @click="item.cmd">{{ item.icon }}</button>
      </div>
      <div ref="tiptapContainer" class="tt-body" :style="{ minHeight: height + 'px' }">
        <EditorContent v-if="tiptapEditor" :editor="tiptapEditor" />
      </div>
    </template>
  </div>
</template>

<style scoped>
.content-editor { border: 1px solid var(--c-border); border-radius: var(--r-s); overflow: hidden; width: 100%; box-sizing: border-box; background: #fff; display: flex; flex-direction: column; }
.type-switch { display: flex; gap: 0; align-items: center; background: #f8fafc; border-bottom: 1px solid var(--c-border); padding-right: 8px; }
.type-switch button { padding: 6px 16px; font-size: var(--fs-xs); color: var(--c-text-2); background: transparent; border: none; cursor: pointer; border-bottom: 2px solid transparent; }
.type-switch button.active { color: var(--c-primary); border-bottom-color: var(--c-primary); background: #fff; font-weight: 600; }
.mode-tip { margin-left: 12px; font-size: 11px; color: var(--c-text-3); }
.locked { display: inline-flex; align-items: center; gap: 6px; padding: 6px 14px; font-size: var(--fs-xs); color: var(--c-primary); font-weight: 600; border-bottom: 2px solid var(--c-primary); background: #fff; }
.locked small { color: var(--c-text-3); font-weight: 400; }
.fs-btn { margin-left: auto; padding: 6px 12px; border: none; background: transparent; color: var(--c-text-2); font-size: var(--fs-xs); cursor: pointer; }
.fs-btn:hover { color: var(--c-primary); background: var(--c-primary-bg); }

.content-editor.fullscreen { position: fixed; inset: 0; z-index: 9999; border: none; border-radius: 0; background: #fff; }
.content-editor.fullscreen .type-switch,
.content-editor.fullscreen .tt-toolbar,
.content-editor.fullscreen .md-tabs { flex: none; }
.content-editor.fullscreen .byte-body,
.content-editor.fullscreen .tt-body,
.content-editor.fullscreen .md-preview { flex: 1 1 auto; min-height: 0 !important; overflow: auto; }

/* Tiptap 工具条 */
.tt-toolbar { display: flex; flex-wrap: wrap; gap: 2px; padding: 5px 8px; background: #fbfcfe; border-bottom: 1px solid var(--c-border); }
.tt-tool { min-width: 30px; height: 26px; padding: 0 6px; border: 1px solid transparent; border-radius: 4px; background: transparent; color: var(--c-text-2); font-size: 12px; cursor: pointer; }
.tt-tool:hover { background: var(--c-primary-bg); color: var(--c-primary); }
.tt-tool.active { background: var(--c-primary-bg); color: var(--c-primary); border-color: var(--c-primary); }
.tt-tool.b { font-weight: 700; }
.tt-tool.i { font-style: italic; font-family: Georgia, serif; }
.tt-tool.u { text-decoration: underline; }
.tt-tool.s { text-decoration: line-through; }

.tt-body { padding: 12px 18px; font-size: var(--fs-base); line-height: 1.75; color: var(--c-text-1); flex: 1 1 auto; }
.tt-body :deep(.tiptap-content) { outline: none; min-height: 100%; }
.tt-body :deep(.tiptap-content p) { margin: 0 0 10px; }
.tt-body :deep(.tiptap-content h1),
.tt-body :deep(.tiptap-content h2),
.tt-body :deep(.tiptap-content h3) { margin: 18px 0 8px; line-height: 1.4; }
.tt-body :deep(.tiptap-content h1) { font-size: 22px; }
.tt-body :deep(.tiptap-content h2) { font-size: 19px; }
.tt-body :deep(.tiptap-content h3) { font-size: 16px; }
.tt-body :deep(.tiptap-content ul),
.tt-body :deep(.tiptap-content ol) { padding-left: 24px; margin: 0 0 10px; }
.tt-body :deep(.tiptap-content blockquote) { border-left: 3px solid var(--c-primary); background: var(--c-primary-bg); margin: 8px 0; padding: 6px 12px; color: var(--c-text-2); }
.tt-body :deep(.tiptap-content code) { background: #f1f4f9; color: #b93a86; padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', Menlo, monospace; font-size: .9em; }
.tt-body :deep(.tiptap-content pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: var(--r-s); overflow-x: auto; font-family: 'JetBrains Mono', Menlo, monospace; font-size: 13px; line-height: 1.6; }
.tt-body :deep(.tiptap-content pre code) { background: none; color: inherit; padding: 0; }
.tt-body :deep(.tiptap-content a) { color: var(--c-primary); text-decoration: underline; }
.tt-body :deep(.tiptap-content img) { max-width: 100%; border-radius: 4px; }
.tt-body :deep(.tiptap-content .tiptap-table) { border-collapse: collapse; margin: 10px 0; width: 100%; table-layout: fixed; }
.tt-body :deep(.tiptap-content .tiptap-table td),
.tt-body :deep(.tiptap-content .tiptap-table th) { border: 1px solid var(--c-border); padding: 6px 10px; vertical-align: top; }
.tt-body :deep(.tiptap-content .tiptap-table th) { background: #f8fafd; font-weight: 600; }
.tt-body :deep(.tiptap-content .selectedCell) { background: var(--c-primary-bg); }
.tt-body :deep(.tiptap-content p.is-editor-empty:first-child::before) { content: attr(data-placeholder); color: var(--c-text-3); float: left; pointer-events: none; height: 0; }

/* bytemd */
.md-tabs { display: inline-flex; gap: 2px; padding: 5px 8px 0; background: #fbfcfe; border-bottom: 1px solid var(--c-border); }
.md-tabs button { padding: 3px 12px; border: none; border-radius: var(--r-full); background: transparent; color: var(--c-text-3); font-size: var(--fs-xs); cursor: pointer; }
.md-tabs button.active { background: var(--c-primary); color: #fff; }
.byte-body { background: #fff; flex: 1 1 auto; min-height: 0; }
.byte-body :deep(.bytemd) { height: 100%; }
.byte-body :deep(.bytemd-editor) { min-height: 220px; }

.md-preview { padding: 18px 22px; overflow: auto; box-sizing: border-box; background: #fff; }
.md-preview :deep(.bytemd) { height: auto; }
.md-preview :deep(.markdown-body) { line-height: 1.8; color: var(--c-text-1); }
.md-preview :deep(.markdown-body h1),
.md-preview :deep(.markdown-body h2),
.md-preview :deep(.markdown-body h3) { margin: 18px 0 8px; }
.md-preview :deep(.markdown-body p) { margin: 0 0 10px; }
.md-preview :deep(.markdown-body code) { background: #f1f4f9; color: #b93a86; padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', Menlo, monospace; }
.md-preview :deep(.markdown-body pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow-x: auto; }
.md-preview :deep(.markdown-body blockquote) { border-left: 3px solid var(--c-primary); background: var(--c-primary-bg); margin: 8px 0; padding: 8px 12px; color: var(--c-text-2); }
.md-preview :deep(.markdown-body img) { max-width: 100%; border-radius: 4px; }
.md-preview :deep(.markdown-body table) { border-collapse: collapse; width: 100%; }
.md-preview :deep(.markdown-body th),
.md-preview :deep(.markdown-body td) { border: 1px solid var(--c-border); padding: 6px 10px; }
</style>
