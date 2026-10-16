<template>
  <div class="preview-host" :class="{ 'is-empty': !file }">
    <!-- 加载中 -->
    <div v-if="loading" class="loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ $t('myDocs.loading') }}</span>
    </div>

    <!-- Office 三件套: 不走此组件, 由父组件拦截 -->
    <el-empty v-else-if="!file" :description="$t('myDocs.emptyFiles')" />

    <!-- 图片: 直接 <img> -->
    <div v-else-if="file.docType === 'image'" class="frame">
      <img v-if="blobUrl" :src="blobUrl" :alt="file.fullName" class="img" />
      <p v-else class="dim">{{ file.fullName }}</p>
    </div>

    <!-- PDF: 用浏览器原生渲染(PDF.js 也行, 这里先不引新依赖) -->
    <div v-else-if="file.docType === 'pdf'" class="frame pdf-frame">
      <iframe v-if="blobUrl" :src="blobUrl" class="pdf-iframe" :title="file.fullName" />
    </div>

    <!-- HTML: 受限 sandbox iframe -->
    <div v-else-if="file.docType === 'html' || file.docType === 'richtext'" class="frame html-frame">
      <iframe v-if="htmlSrc" :srcdoc="htmlSrc" sandbox="allow-same-origin" class="html-iframe" :title="file.fullName" />
      <pre v-else-if="text != null" class="text-pre">{{ text }}</pre>
    </div>

    <!-- Markdown / Text / Code: 走 markdown 渲染或纯文本 -->
    <div v-else class="frame text-frame">
      <div v-if="file.docType === 'markdown' && text != null" class="md-body" v-html="renderMd(text)" />
      <pre v-else-if="text != null" class="text-pre" :class="`lang-${file.docType}`">{{ text }}</pre>
      <p v-else class="dim">没有可显示的内容</p>
    </div>
  </div>
</template>

<script setup>
/**
 * 在线预览组件: 按 docType 走不同渲染策略
 * <p>
 * 流程:
 * 1. 父组件 watch file.id, 调 previewDocFile(id) 拿 blob
 * 2. URL.createObjectURL(blob) 给 <img> / <iframe> 用
 * 3. md 走 marked+dompurify(项目已装); text/code 走 <pre>
 * <p>
 * 注意:
 * - 父组件传 file = { id, fullName, docType, ext, sizeText }
 * - blobUrl / text 是 ref, 切文件时释放旧 URL
 */
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Loading } from '@element-plus/icons-vue'
import { previewDocFile } from '../../api/myDoc'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps({
  file: { type: Object, default: null }
})

const loading = ref(false)
const blobUrl = ref('')
const text = ref(null)

const htmlSrc = computed(() => {
  if (!props.file) return ''
  if (props.file.docType === 'html' || props.file.docType === 'richtext') {
    return text.value == null ? '' : text.value
  }
  return ''
})

const renderMd = (s) => renderMarkdown(s || '')

/** 加载 blob 并按 docType 决定走图片 / 文本 */
const load = async () => {
  // 释放旧 URL
  if (blobUrl.value) { URL.revokeObjectURL(blobUrl.value); blobUrl.value = '' }
  text.value = null
  if (!props.file || !props.file.id) return
  // Office 三件套不归本组件管
  if (props.file.docType === 'word' || props.file.docType === 'excel' || props.file.docType === 'ppt') return

  loading.value = true
  try {
    const blob = await previewDocFile(props.file.id)
    // image / pdf 用 blob URL 直接渲染; 其余读成文本
    if (props.file.docType === 'image' || props.file.docType === 'pdf') {
      blobUrl.value = URL.createObjectURL(blob)
    } else {
      text.value = await blob.text()
    }
  } catch (e) {
    text.value = null
    blobUrl.value = ''
  } finally {
    loading.value = false
  }
}

watch(() => props.file?.id, () => load(), { immediate: true })

onBeforeUnmount(() => {
  if (blobUrl.value) URL.revokeObjectURL(blobUrl.value)
})
</script>

<style scoped>
.preview-host { width: 100%; height: 100%; display: flex; align-items: stretch; justify-content: stretch; background: var(--c-bg-page, #f7f8fb); border-radius: var(--r-m); overflow: hidden; }
.preview-host.is-empty { background: #fff; }
.loading { padding: 60px; text-align: center; color: var(--c-text-3); display: flex; align-items: center; justify-content: center; gap: 8px; }
.frame { flex: 1; min-height: 0; display: flex; }
.img { max-width: 100%; max-height: 100%; margin: auto; object-fit: contain; background: #fff; }
.pdf-frame { background: #525659; }
.pdf-iframe { width: 100%; height: 100%; border: 0; background: #fff; }
.html-frame { background: #fff; }
.html-iframe { width: 100%; height: 100%; border: 0; background: #fff; }
.text-frame { background: #fff; padding: 24px 28px; overflow: auto; }
.md-body { width: 100%; max-width: 900px; margin: 0 auto; line-height: 1.7; color: var(--c-text-1); font-size: 15px; }
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3) { color: var(--c-text-1); margin: 1.2em 0 0.6em; }
.md-body :deep(p) { margin: 0.6em 0; }
.md-body :deep(code) { background: #f3f4f8; padding: 2px 6px; border-radius: 3px; font-family: 'SF Mono', Menlo, Consolas, monospace; }
.md-body :deep(pre) { background: #1f2937; color: #e5e7eb; padding: 14px 16px; border-radius: 6px; overflow: auto; }
.md-body :deep(pre code) { background: transparent; color: inherit; padding: 0; }
.md-body :deep(blockquote) { border-left: 4px solid var(--c-primary); padding: 6px 12px; background: var(--c-primary-bg); margin: 0.8em 0; color: var(--c-text-2); }
.md-body :deep(table) { border-collapse: collapse; margin: 0.8em 0; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid var(--c-border); padding: 6px 10px; }
.text-pre { width: 100%; max-width: 100%; margin: 0; padding: 14px 16px; background: #1f2937; color: #e5e7eb; border-radius: 6px; overflow: auto; font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 13px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.text-pre.lang-text { background: #f9fafb; color: var(--c-text-1); border: 1px solid var(--c-border); }
.dim { color: var(--c-text-3); margin: auto; }
</style>
