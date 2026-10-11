<script setup>
// Java 代码编辑器(基于 Monaco Editor / VS Code 内核)
// 用法: <CodeEditor v-model="code" @run="onRun" :markers="markers" />
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import * as monaco from 'monaco-editor'

// 配置 worker: Monaco 在浏览器需要 editor worker 来支持 IntelliSense / 格式化等
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker'

const props = defineProps({
  modelValue: { type: String, default: '' },
  language: { type: String, default: 'java' },
  height: { type: String, default: '380px' },
  markers: { type: Array, default: () => [] }   // [{line, column, message, severity}]
})
const emit = defineEmits(['update:modelValue', 'run', 'reset'])

// 全局 Monaco 环境只配置一次
if (typeof window !== 'undefined' && !window.__MONACO_ENV__) {
  self.MonacoEnvironment = {
    getWorker() {
      // Java 等基础语言不需要 worker; 统一返回 editor worker
      return new EditorWorker()
    }
  }
  window.__MONACO_ENV__ = true
}

const editorRef = ref(null)
let editor = null
let suppressNextChange = false

const initEditor = () => {
  if (!editorRef.value) return
  editor = monaco.editor.create(editorRef.value, {
    value: props.modelValue,
    language: props.language,
    theme: 'vs-dark',
    fontSize: 13,
    fontFamily: 'JetBrains Mono, Menlo, Consolas, monospace',
    minimap: { enabled: false },
    automaticLayout: true,
    scrollBeyondLastLine: false,
    tabSize: 4,
    lineNumbersMinChars: 3,
    padding: { top: 14, bottom: 14 },
    renderLineHighlight: 'line',
    wordWrap: 'on'
  })
  editor.onDidChangeModelContent(() => {
    if (suppressNextChange) { suppressNextChange = false; return }
    emit('update:modelValue', editor.getValue())
  })
  applyMarkers()
}

const applyMarkers = () => {
  if (!editor) return
  const model = editor.getModel()
  if (!model) return
  const markers = props.markers.map(m => ({
    startLineNumber: Math.max(1, m.line),
    endLineNumber: Math.max(1, m.line),
    startColumn: Math.max(1, m.column),
    endColumn: Math.max(1, m.column + 1),
    message: m.message,
    severity: m.severity === 'warning'
      ? monaco.MarkerSeverity.Warning
      : monaco.MarkerSeverity.Error
  }))
  monaco.editor.setModelMarkers(model, 'sandbox', markers)
}

onMounted(initEditor)
onBeforeUnmount(() => { editor?.dispose(); editor = null })

// 外部 v-model 变化(重置按钮) -> 同步到编辑器
watch(() => props.modelValue, (val) => {
  if (!editor || editor.getValue() === val) return
  suppressNextChange = true
  editor.setValue(val ?? '')
})
// 外部 markers 变化 -> 重新标记
watch(() => props.markers, applyMarkers, { deep: true })

const onRunClick = () => emit('run')
const onResetClick = () => emit('reset')
</script>

<template>
  <div class="code-wrap" :style="{ height }">
    <div class="toolbar">
      <div class="lang-pill">{{ language.toUpperCase() }}</div>
      <div class="acts">
        <button class="reset-btn" title="恢复为示例代码" @click="onResetClick"><el-icon><RefreshLeft /></el-icon> 重置</button>
        <button class="run-btn" title="运行代码" @click="onRunClick"><el-icon><VideoPlay /></el-icon> 运行</button>
      </div>
    </div>
    <div class="editor" ref="editorRef"></div>
  </div>
</template>

<style scoped>
.code-wrap { display:flex; flex-direction:column; background:#0d1117; border-radius:var(--r-s); overflow:hidden; border:1px solid #30363d }
.toolbar { display:flex; align-items:center; justify-content:space-between; padding:8px 14px; background:#161b22; border-bottom:1px solid #30363d }
.lang-pill { font-size:var(--fs-xs); color:#79c0ff; letter-spacing:.5px; font-family:'JetBrains Mono',monospace }
.acts { display:flex; gap:8px }
.reset-btn, .run-btn { display:inline-flex; align-items:center; gap:4px; padding:6px 14px; min-height:32px; font-size:var(--fs-xs); border-radius:var(--r-s); cursor:pointer; border:1px solid transparent }
.reset-btn { background:transparent; color:#9ba3b4; border-color:#30363d }
.reset-btn:hover { color:#fff; border-color:#58a6ff }
.run-btn { background:var(--c-success); color:#fff }
.run-btn:hover { background:#15803d }
.editor { flex:1; min-height:0 }
</style>