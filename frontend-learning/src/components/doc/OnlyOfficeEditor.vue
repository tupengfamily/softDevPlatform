<template>
  <div class="oo-editor">
    <div v-if="errorMsg" class="err">
      <el-alert type="error" :closable="false" :title="$t('myDocs.ooError')" :description="errorMsg" />
    </div>
    <div v-else-if="!editorInfo" class="loading">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ $t('myDocs.loading') }}</span>
    </div>
    <div v-else-if="!editorInfo.enabled" class="fallback-hint">
      <el-alert type="warning" :closable="false" :title="$t('myDocs.ooFallback')" :description="$t('myDocs.ooFallbackDesc')" />
    </div>
    <div v-else class="host" :style="hostStyle">
      <!-- 这是 OnlyOffice DocsAPI.DocEditor 注入 iframe 的占位节点 -->
      <div ref="host" class="placeholder"></div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { Loading } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { fetchEditorInfo } from '../../api/myDoc'

const props = defineProps({
  fileId: { type: Number, required: true },
  docType: { type: String, required: true },
  userId: { type: Number, required: true },
  username: { type: String, default: '' },
  /** 'edit' = 可编辑(带工具栏) | 'view' = 只读预览(隐藏工具栏) */
  mode: { type: String, default: 'edit' }
})
const emit = defineEmits(['save', 'saved', 'ready', 'error'])
const { t } = useI18n()

const host = ref(null)
const editorInfo = ref(null)
const errorMsg = ref('')
const docEditor = shallowRef(null)
const apiReady = ref(false)
const apijsLoading = ref(false)

const hostStyle = computed(() => ({ width: '100%', height: '100%', minHeight: '600px' }))

/**
 * OO 9.x 创建的 iframe 默认是 150px, 不会自动 100%.
 * 解决: ResizeObserver 监控 .host / .editor-body 尺寸变化, 强制把 iframe 高度同步.
 */
let ro = null
const syncIframeSize = () => {
  if (!host.value) return
  const h = host.value.clientHeight
  const w = host.value.clientWidth
  if (h < 200) return  // 还没真正展开
  host.value.querySelectorAll('iframe').forEach(f => {
    f.style.width = w + 'px'
    f.style.height = h + 'px'
    f.removeAttribute('scrolling')  // 一些版本设了 scrolling="no" 导致内容固定 150px
  })
}

/** 动态加载 apijs(OnlyOffice 9.x 的 JS API) */
const loadApiJs = () => {
  if (window.DocsAPI) return Promise.resolve()
  if (apijsLoading.value) return new Promise(() => {})
  apijsLoading.value = true
  return new Promise((resolve, reject) => {
    const url = (editorInfo.value?.docServerUrl || '') + '/web-apps/apps/api/documents/api.js'
    const s = document.createElement('script')
    s.src = url
    s.async = true
    s.onload = () => { apijsLoading.value = false; apiReady.value = true; resolve() }
    s.onerror = (e) => { apijsLoading.value = false; reject(new Error('Failed to load OnlyOffice api.js from ' + url)) }
    document.head.appendChild(s)
  })
}

/** 调用后端 /api/my-docs/oo/{id}/info 拿 editorUrl + accessToken */
const loadInfo = async () => {
  try {
    const r = await fetchEditorInfo(props.fileId, props.mode)
    editorInfo.value = r
    emit('ready', r)
    // 等 editorInfo 设置 + apijs 加载 + 挂载
    await loadApiJs()
    if (!window.DocsAPI) {
      errorMsg.value = 'DocsAPI 未定义 (apijs 加载失败)'
      return
    }
    // 等 DOM 实际渲染 + 应用 100% 高度, 再 init OO
    await nextTick()
    requestAnimationFrame(() => initEditor())
  } catch (ex) {
    console.error('OnlyOffice info load failed', ex)
    errorMsg.value = ex?.message || String(ex)
    emit('error', ex)
  }
}

/** 初始化 OO 编辑器 */
const initEditor = () => {
  if (!editorInfo.value || !window.DocsAPI || !host.value) return
  if (docEditor.value) {
    try { docEditor.value.destroyEditor() } catch (e) { /* ignore */ }
    docEditor.value = null
  }
  // OO 9.x 推荐方式: 用 JS API 注入 config (含 url + editorConfig + customization)
  const cfg = editorInfo.value.config
  // 文档类型 (word/excel/cell/presentation/slide)
  const documentType = (() => {
    switch (props.docType) {
      case 'word': return 'word'
      case 'excel': return 'cell'
      case 'ppt': return 'slide'
      default: return 'word'
    }
  })()
  const config = {
    width: '100%',
    height: '100%',
    documentType,
    document: cfg.document,
    editorConfig: cfg.editorConfig,
    token: cfg.token || undefined,  // JWT 启用时, payload.token 这里直接传
    events: {
      onAppReady: () => { console.log('[OO] app ready'); syncIframeSize() },
      onDocumentReady: () => { console.log('[OO] document ready'); syncIframeSize(); emit('ready', editorInfo.value) },
      // 用户编辑触发: emit 'save'(="可能改了"), 用于前端"已修改"提示
      onSave: (data) => {
        console.log('[OO] user saved (or autosave)', data)
        emit('save', data)
        // 紧接着拉一次 tree, 让版本号 / size 立即反映
        emit('saved', data)
      },
      // OO 也会在文档状态变更时回调
      onDocumentStateChange: (e) => { console.log('[OO] doc state', e) },
      onError: (e) => { console.warn('[OO] error', JSON.stringify(e, null, 2)); emit('error', e) }
    }
  }
  // OO 9.x 的 DocEditor 需要 placeholder div 的 id 字符串作为第一个参数
  const placeholderId = `oo-placeholder-${props.fileId}`
  if (host.value) host.value.id = placeholderId
  try {
    console.log('[OO] init editor with id=' + placeholderId, config)
    docEditor.value = new window.DocsAPI.DocEditor(placeholderId, config)
    console.log('[OO] editor created')
    // 多轮重试同步 (OO 9.x iframe 异步创建, 有时延迟数帧)
    let n = 0
    const timer = setInterval(() => { syncIframeSize(); if (++n > 30) clearInterval(timer) }, 200)
  } catch (ex) {
    errorMsg.value = 'DocEditor 初始化失败: ' + (ex?.message || ex)
    console.error('[OO] init error:', ex)
  }
}

onMounted(() => {
  loadInfo()
  // 监听窗口尺寸变化 + 父容器尺寸变化
  ro = new ResizeObserver(() => syncIframeSize())
  // 监听 .editor-body (如果存在) 和 .host
  const body = document.querySelector('.editor-body')
  if (body) ro.observe(body)
  if (host.value) ro.observe(host.value)
  window.addEventListener('resize', syncIframeSize)
})

onBeforeUnmount(() => {
  if (docEditor.value) {
    try { docEditor.value.destroyEditor() } catch (e) { /* ignore */ }
    docEditor.value = null
  }
  if (ro) { ro.disconnect(); ro = null }
  window.removeEventListener('resize', syncIframeSize)
})

watch(() => props.fileId, (v) => {
  if (v) loadInfo()
})

// 预览 <-> 编辑切换: 重新拉不同模式的 config(mode 决定是否有工具栏)
watch(() => props.mode, () => loadInfo())
</script>

<style scoped>
.oo-editor { width: 100%; height: 100%; display: flex; flex-direction: column; }
.loading, .err { padding: 40px; text-align: center; color: var(--c-text-3); display: flex; align-items: center; justify-content: center; gap: 8px; }
.fallback-hint { padding: 24px; }
.host { flex: 1; position: relative; }
.placeholder { width: 100%; height: 100%; }
</style>
