<script setup>
// 个人文档: 树形(目录+文件) + 在线预览 + Word/Excel/PPT/MD/富文本编辑 + 导入导出 + 历史版本
import { computed, onBeforeUnmount, onMounted, ref, watch, h } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '../store/user'
import {
  getDocTree, createDocDir, renameDocDir, deleteDocDir, moveDocDir,
  createDocFile, importDocFile, renameDocFile, moveDocFile, copyDocFile,
  deleteDocFile, downloadDocFile, getDocModel, saveDocModel,
  getDocVersions, downloadDocVersion, restoreDocVersion, fetchEditorInfo,
  getPublicSettings
} from '../api/myDoc'
import WordEditor from '../components/doc/WordEditor.vue'
import SheetEditor from '../components/doc/SheetEditor.vue'
import SlideEditor from '../components/doc/SlideEditor.vue'
import OnlyOfficeEditor from '../components/doc/OnlyOfficeEditor.vue'
import MarkdownEditor from '../components/doc/MarkdownEditor.vue'
import RichTextEditor from '../components/doc/RichTextEditor.vue'
import FilePreview from '../components/doc/FilePreview.vue'

const { t } = useI18n()
const userStore = useUserStore()
const currentUserId = computed(() => userStore.userId || 0)
const currentUsername = computed(() => userStore.username || 'anon')

import {
  Folder, FolderAdd, Document, DocumentAdd, Grid, Picture, Plus, Upload, UploadFilled,
  Refresh, View, Edit, VideoPlay, ChatLineRound, Tickets, Download, ArrowRight, Delete, More
} from '@element-plus/icons-vue'

const ROOT = 0  // 0 = 全部文档(根视图); 目录 id 为正整数; 文件不在 currentKey 范围

const loading = ref(false)
const directories = ref([])
const rootFiles = ref([])
const stats = ref({})
const currentKey = ref(ROOT)
const keyword = ref('')

// 系统配额(从 /api/system-settings 拉)
const quota = ref({ maxQuotaBytes: 500 * 1024 * 1024, remainingBytes: 0, maxFileBytes: 20 * 1024 * 1024, maxFileCount: 300 })

// ==================== 数据加载 ====================

const loadTree = async (keepKey = true) => {
  loading.value = true
  try {
    const res = await getDocTree()
    const data = res.data || {}
    directories.value = data.directories || []
    rootFiles.value = data.rootFiles || []
    stats.value = data.stats || {}
    quota.value = {
      maxQuotaBytes: stats.value.maxQuotaBytes || quota.value.maxQuotaBytes,
      remainingBytes: stats.value.remainingBytes ?? quota.value.remainingBytes,
      maxFileBytes: stats.value.maxFileBytes || quota.value.maxFileBytes,
      maxFileCount: stats.value.maxFileCount || quota.value.maxFileCount
    }
    if (!keepKey) currentKey.value = ROOT
    if (currentKey.value !== ROOT && !directories.value.some(d => d.id === currentKey.value)) {
      currentKey.value = ROOT
    }
    // 文件被改名/移走/删除时, 同步右栏预览的数据
    if (previewNode.value) {
      const fresh = findFileRow(previewNode.value.id)
      previewNode.value = fresh || null
    }
  } catch (e) { /* 统一弹错 */ } finally {
    loading.value = false
  }
}

const loadSettings = async () => {
  try {
    const r = await getPublicSettings()
    if (r) quota.value = { ...quota.value, ...r }
  } catch (_) { /* 公开接口, 不报错 */ }
}

const dirMap = computed(() => {
  const m = {}
  directories.value.forEach(d => { m[d.id] = d })
  return m
})

/** 类型 → icon 组件映射(树节点 / 文件图标共用) */
const iconOf = (docType) => ({
  word: Document, excel: Grid, ppt: Picture,
  markdown: Tickets, richtext: ChatLineRound,
  image: Picture, pdf: Document,
  html: ChatLineRound, text: Document, code: Document
}[docType] || Document)

/** 类型 → 颜色 class */
const colorOf = (docType) => ({
  word: 'i-word', excel: 'i-excel', ppt: 'i-ppt',
  markdown: 'i-md', richtext: 'i-rt',
  image: 'i-image', pdf: 'i-pdf',
  html: 'i-html', text: 'i-text', code: 'i-code'
}[docType] || 'i-text')

/** 类型 → i18n label */
const typeLabel = (docType) => ({
  word: t('myDocs.typeWord'),
  excel: t('myDocs.typeExcel'),
  ppt: t('myDocs.typePpt'),
  markdown: t('myDocs.typeMarkdown'),
  richtext: t('myDocs.typeRichText'),
  image: t('myDocs.typeImage'),
  pdf: t('myDocs.typePdf'),
  html: t('myDocs.typeHtml'),
  text: t('myDocs.typeText'),
  code: t('myDocs.typeCode')
}[docType] || docType)

const tagOf = (docType) => ({
  word: 'primary', excel: 'success', ppt: 'warning',
  markdown: 'info', richtext: 'info',
  image: 'success', pdf: 'danger',
  html: '', text: 'info', code: 'info'
}[docType] || '')

/** 树形: 目录节点 + 文件节点 混排, 目录在上文件在下, 无 ROOT 虚拟层 */
const fileNodeOf = (f) => ({
  key: 'file-' + f.id,
  kind: 'file',
  id: f.id, name: f.name, fullName: f.fullName,
  ext: f.ext, docType: f.docType,
  sizeBytes: f.sizeBytes, sizeText: f.sizeText,
  version: f.version, updateTime: f.updateTime,
  isLeaf: true
})
const sortDirsFirst = (a, b) => {
  if (a.kind === b.kind) return 0
  return a.kind === 'dir' ? -1 : 1
}
const treeData = computed(() => {
  const dirNodes = {}
  directories.value.forEach(d => {
    dirNodes[d.id] = { key: d.id, kind: 'dir', id: d.id, name: d.name, raw: d, children: [] }
  })
  directories.value.forEach(d => {
    const dirNode = dirNodes[d.id]
    if (!dirNode) return
    const fileNodes = (d.files || []).map(fileNodeOf)
    dirNode.children = [...dirNode.children, ...fileNodes].sort(sortDirsFirst)
  })
  const rootDirs = []
  directories.value.forEach(d => { if (!d.parentId) rootDirs.push(dirNodes[d.id]) })
  const rootFileNodes = rootFiles.value.map(fileNodeOf)
  return [...rootDirs, ...rootFileNodes].sort(sortDirsFirst)
})

/** 目录树选择器数据 */
const dirOptions = computed(() => {
  const nodes = {}
  directories.value.forEach(d => { nodes[d.id] = { value: d.id, label: d.name, children: [] } })
  const roots = []
  directories.value.forEach(d => {
    if (d.parentId && nodes[d.parentId]) nodes[d.parentId].children.push(nodes[d.id])
    else roots.push(nodes[d.id])
  })
  return [{ value: 0, label: t('myDocs.rootDir'), children: roots }]
})

const currentName = computed(() => {
  if (currentKey.value === ROOT) return t('myDocs.allFiles')
  const d = dirMap.value[currentKey.value]
  return d ? d.name : t('myDocs.allFiles')
})

const currentFiles = computed(() => {
  let list = []
  if (currentKey.value === ROOT) list = rootFiles.value
  else list = (dirMap.value[currentKey.value]?.files || [])
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(f => f.fullName.toLowerCase().includes(kw))
})

const currentDirInfo = computed(() => {
  let dir
  let files = []
  if (currentKey.value === ROOT) {
    dir = { name: t('myDocs.allFiles'), id: 0, parentId: null }
    files = rootFiles.value
  } else {
    dir = dirMap.value[currentKey.value]
    if (!dir) return null
    files = dir.files || []
  }
  const dirCount = currentKey.value === ROOT
    ? directories.value.filter(d => !d.parentId).length
    : directories.value.filter(d => d.parentId === currentKey.value).length
  const totalBytes = files.reduce((s, f) => s + (f.sizeBytes || 0), 0)
  const sizeText = humanSize(totalBytes)
  return {
    id: dir.id, name: dir.name, dirCount, fileCount: files.length, sizeText,
    files  // 暴露给 list 用
  }
})

/** 单文件大小格式化 */
function humanSize(bytes) {
  if (!bytes || bytes <= 0) return '0 B'
  if (bytes < 1024) return bytes + ' B'
  const kb = bytes / 1024
  if (kb < 1024) return kb.toFixed(1) + ' KB'
  const mb = kb / 1024
  if (mb < 1024) return mb.toFixed(1) + ' MB'
  return (mb / 1024).toFixed(2) + ' GB'
}

const findFileRow = (id) => {
  const inRoot = rootFiles.value.find(f => f.id === id)
  if (inRoot) return inRoot
  for (const d of directories.value) {
    const f = (d.files || []).find(x => x.id === id)
    if (f) return f
  }
  return null
}

/**
 * 节点点击 -> 右侧详情区切换:
 *   文件 = 内联预览 / 目录 = 该目录详情(含文件列表)
 */
const onTreeClick = (node) => {
  if (node.kind === 'file') {
    openPreview(findFileRow(node.id))
    return
  }
  currentKey.value = node.key
  keyword.value = ''
  previewNode.value = null
}

// ==================== 目录操作 ====================

const dirDialog = ref(false)
const dirForm = ref({ id: null, name: '', parentId: 0 })

const openNewDir = (parentId = 0) => {
  dirForm.value = { id: null, name: '', parentId: currentKey.value === ROOT ? parentId : currentKey.value }
  dirDialog.value = true
}
const openRenameDir = (row) => {
  dirForm.value = { id: row.key, name: row.name, parentId: row.raw?.parentId ?? 0 }
  dirDialog.value = true
}
const submitDir = async () => {
  const f = dirForm.value
  if (!f.name.trim()) { ElMessage.warning(t('myDocs.dirNamePlaceholder')); return }
  try {
    if (f.id == null) {
      await createDocDir({ name: f.name.trim(), parentId: f.parentId || null })
      ElMessage.success(t('myDocs.createBtn') + ' ✓')
    } else {
      await renameDocDir(f.id, f.name.trim())
      ElMessage.success(t('common.saved'))
    }
    dirDialog.value = false
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}
const removeDir = async (node) => {
  try {
    await ElMessageBox.confirm(t('myDocs.delDirConfirm', { name: node.name }), t('myDocs.actionDelete'), { type: 'warning' })
  } catch (e) { return }
  try {
    await deleteDocDir(node.key)
    ElMessage.success(t('myDocs.delOk'))
    if (currentKey.value === node.key) currentKey.value = ROOT
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

const allowDrop = (draggingNode, dropNode, type) => type === 'inner' && dropNode.data.key !== draggingNode.data.key
const onDrop = async (draggingNode, dropNode) => {
  const target = dropNode.data.key === ROOT ? null : dropNode.data.key
  try {
    await moveDocDir(draggingNode.data.key, target)
    ElMessage.success(t('common.saved'))
  } catch (e) { /* 统一弹错 */ }
  await loadTree()
}

// ==================== 文件操作 ====================

// ---- 新建文档 ----
const newDocDialog = ref(false)
const newDocForm = ref({ name: '', docType: 'word', directoryId: 0 })
const creating = ref(false)

const openNewDoc = () => {
  newDocForm.value = { name: '', docType: 'word', directoryId: currentKey.value === ROOT ? 0 : currentKey.value }
  newDocDialog.value = true
}
const submitNewDoc = async () => {
  if (!newDocForm.value.name.trim()) { ElMessage.warning(t('myDocs.docNamePlaceholder')); return }
  creating.value = true
  try {
    await createDocFile({
      name: newDocForm.value.name.trim(),
      docType: newDocForm.value.docType,
      directoryId: newDocForm.value.directoryId || null
    })
    ElMessage.success(t('myDocs.createBtn') + ' ✓')
    newDocDialog.value = false
    await loadTree()
  } catch (e) { /* 统一弹错 */ } finally {
    creating.value = false
  }
}

// ---- 导入 ----
const importDialog = ref(false)
const importFile = ref(null)
const importDir = ref(0)
const importing = ref(false)
const fileInput = ref(null)

// 文件类型白名单(对应 MyDocType.ofExt)
const acceptExtensions = '.docx,.xlsx,.pptx,.md,.html,.htm,.pdf,.png,.jpg,.jpeg,.gif,.webp,.bmp,.svg,.txt,.log,.csv,.json,.xml,.yml,.yaml,.java,.py,.js,.ts,.jsx,.tsx,.vue,.css,.scss,.less,.sh,.bash,.sql,.go,.rs,.c,.cpp,.h,.hpp,.kt,.rb,.php,.ini,.conf,.properties'

const openImport = () => {
  importFile.value = null
  importDir.value = currentKey.value === ROOT ? 0 : currentKey.value
  importDialog.value = true
}
const pickFile = () => fileInput.value?.click()
const onFilePicked = (e) => {
  const f = e.target.files?.[0]
  if (!f) return
  importFile.value = f
  e.target.value = ''
}
const onDropFile = (e) => {
  const f = e.dataTransfer?.files?.[0]
  if (!f) return
  importFile.value = f
}
const submitImport = async () => {
  if (!importFile.value) { ElMessage.warning(t('myDocs.importHint')); return }
  const fd = new FormData()
  fd.append('file', importFile.value)
  if (importDir.value) fd.append('directoryId', String(importDir.value))
  importing.value = true
  try {
    await importDocFile(fd)
    ElMessage.success(t('myDocs.importBtn') + ' ✓')
    importDialog.value = false
    await loadTree()
  } catch (e) { /* 统一弹错 */ } finally {
    importing.value = false
  }
}

// ---- 重命名 / 移动 ----
const renameDialog = ref(false)
const renameForm = ref({ id: null, name: '', ext: '' })
const openRename = (row) => {
  renameForm.value = { id: row.id, name: row.name, ext: row.ext }
  renameDialog.value = true
}
const submitRename = async () => {
  try {
    await renameDocFile(renameForm.value.id, renameForm.value.name.trim())
    ElMessage.success(t('common.saved'))
    renameDialog.value = false
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

const moveDialog = ref(false)
const moveForm = ref({ id: null, name: '', directoryId: 0 })
const openMove = (row) => {
  moveForm.value = { id: row.id, name: row.fullName, directoryId: row.directoryId || 0 }
  moveDialog.value = true
}
const submitMove = async () => {
  try {
    await moveDocFile(moveForm.value.id, moveForm.value.directoryId || null)
    ElMessage.success(t('common.saved'))
    moveDialog.value = false
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

const doCopy = async (row) => {
  try {
    await copyDocFile(row.id, row.directoryId || null)
    ElMessage.success(t('myDocs.copyOk'))
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

const doDelete = async (row) => {
  try {
    await ElMessageBox.confirm(t('myDocs.delConfirm', { name: row.fullName }), t('myDocs.actionDelete'), { type: 'warning' })
  } catch (e) { return }
  try {
    await deleteDocFile(row.id)
    ElMessage.success(t('myDocs.delOk'))
    // 预览的就是这个文件 -> 退回详情区
    if (previewNode.value && previewNode.value.id === row.id) previewNode.value = null
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

const saveBlob = (blob, filename) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

const doDownload = async (row) => {
  try {
    const blob = await downloadDocFile(row.id)
    saveBlob(blob, row.fullName)
    ElMessage.success(t('myDocs.actionDownload') + ' ✓')
  } catch (e) { /* 统一弹错 */ }
}

// ==================== 内联预览(点文件名 -> 右侧详情区切换成预览) ====================

const previewNode = ref(null)

/** Office 三件套(word/excel/ppt) */
const isOffice = (docType) => ['word', 'excel', 'ppt'].includes(docType)
/** 可在线编辑的类型(图片/pdf/文本/代码/HTML 只读) */
const isEditable = (docType) => !['image', 'pdf', 'text', 'code', 'html'].includes(docType)

/** OnlyOffice 是否可用于内联预览(首次用到时探测一次并缓存) */
const ooReady = ref(false)
const ooChecked = ref(false)
const checkOO = async (row) => {
  if (ooChecked.value) return
  ooChecked.value = true
  try {
    const info = await fetchEditorInfo(row.id, 'view')
    ooReady.value = !!info?.enabled
  } catch (e) {
    ooReady.value = false
  }
}

/** 在右侧详情区预览: 非弹窗, 切换右栏内容 */
const openPreview = (row) => {
  if (!row) return
  previewNode.value = row
  if (isOffice(row.docType)) checkOO(row)
}
const closePreview = () => { previewNode.value = null }

// ==================== 在线编辑 ====================

const editorVisible = ref(false)
const editorLoading = ref(false)
const saving = ref(false)
const editing = ref(null)
const model = ref(null)
const dirty = ref(false)
const lastSaved = ref('')
const ooEnabled = ref(false)

/**
 * 进入全屏编辑:
 * - Office 三件套: 优先走 OnlyOffice(由后端 enabled 决定, 关闭则降级 v5 自绘)
 * - Markdown / RichText: 走应用内编辑器
 * - Image / PDF / HTML / Text / Code: 不允许进入编辑(只能预览)
 */
/**
 * 树节点右侧操作按钮配置。
 * - 直接返回结构化列表,模板统一渲染,不再每种 case 写一份按钮
 * - 点击文件名本身就是预览,所以不再单独给"查看"按钮
 * - 列表超 3 个时,前面的直接显示,剩余的塞进 "..." 下拉(便于以后加操作)
 */
const TREE_OPS_INLINE_MAX = 3
const buildTreeOps = (data) => {
  const ops = []
  if (data.kind === 'dir') {
    ops.push({ key: 'rename', icon: Edit, type: '',      tipKey: 'actionRename', handler: () => openRenameDir(data) })
    ops.push({ key: 'delete', icon: Delete, type: 'danger', tipKey: 'actionDelete', handler: () => removeDir(data) })
  } else {
    // 文档:可编辑才显示编辑;删除永远有(预览/编辑都点文件名直达,删除只能从树节点触发)
    if (!['image', 'pdf', 'text', 'code', 'html'].includes(data.docType)) {
      ops.push({ key: 'edit', icon: Edit, type: 'primary', tipKey: 'actionEdit', handler: () => openEditor(data) })
    }
    ops.push({ key: 'delete', icon: Delete, type: 'danger', tipKey: 'actionDelete', handler: () => doDelete(data) })
  }
  return ops
}

const openEditor = async (row) => {
  editing.value = row
  model.value = null
  dirty.value = false
  lastSaved.value = ''
  ooEnabled.value = false
  editorVisible.value = true
  editorLoading.value = true
  try {
    if (row.docType === 'image' || row.docType === 'pdf' || row.docType === 'text'
        || row.docType === 'code' || row.docType === 'html') {
      ElMessage.warning(t('myDocs.readonlyHint'))
      editorVisible.value = false
      return
    }
    if (['word', 'excel', 'ppt'].includes(row.docType)) {
      // OnlyOffice 探测: 失败时降级到 v5 自绘编辑器, 不弹错误(常见原因: 后端未启用 OO、容器未启动、JWT 未配)
      try {
        const oo = await fetchEditorInfo(row.id)
        if (oo?.enabled) {
          ooEnabled.value = true
          editorLoading.value = false
          return
        }
      } catch (ooErr) {
        console.warn('[MyDocuments] OnlyOffice info 探测失败, 降级到内置编辑器:', ooErr?.message || ooErr)
      }
    }
    const res = await getDocModel(row.id)
    model.value = res.data
  } catch (e) {
    ElMessage.error(t('myDocs.loadFail'))
    editorVisible.value = false
  } finally {
    editorLoading.value = false
  }
}
const markDirty = () => { dirty.value = true }

/** OnlyOffice 自动保存回调: 文件已写到磁盘, 这里只需要刷新树 + 清 dirty + 显示提示 */
const onOOSaved = async () => {
  dirty.value = false
  lastSaved.value = t('myDocs.autoSaved')
  try {
    const tree = await getDocTree()
    if (editing.value) {
      const row = (tree.data?.directories || []).flatMap(d => d.files || [])
        .concat(tree.data?.rootFiles || []).find(f => f.id === editing.value.id)
      if (row) {
        editing.value = { ...editing.value, version: row.version, sizeText: row.sizeText, sizeBytes: row.sizeBytes, updateTime: row.updateTime }
      }
    }
    await loadTree(false)
  } catch (_) { /* 静默 */ }
  ElMessage.success(t('myDocs.autoSaved') + ' ✓')
}

const doSave = async () => {
  if (!editing.value || !model.value) return
  saving.value = true
  try {
    const res = await saveDocModel(editing.value.id, model.value)
    const d = res.data || {}
    lastSaved.value = t('myDocs.savedAt', { version: d.version || '-', size: d.sizeText || '-' })
    dirty.value = false
    ElMessage.success(t('myDocs.saveBtn') + ' ✓')
    await loadTree()
  } catch (e) {
    ElMessage.error(t('myDocs.saveFail'))
  } finally {
    saving.value = false
  }
}

const beforeCloseEditor = async (done) => {
  if (dirty.value) {
    try {
      await ElMessageBox.confirm(t('myDocs.unsavedTip'), t('myDocs.unsavedTitle'), {
        type: 'warning', confirmButtonText: t('myDocs.saveBtn'), cancelButtonText: t('myDocs.discardBtn'),
        distinguishCancelAndClose: true
      })
      await doSave()
      dirty.value = false
      done()
    } catch (action) {
      if (action === 'cancel') { dirty.value = false; done() }
    }
  } else {
    done()
  }
}

const onKeydown = (e) => {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    if (editorVisible.value) { e.preventDefault(); doSave() }
  }
}

/** 搜索时自动退出预览, 让结果列表可见 */
watch(keyword, (v) => { if (v && previewNode.value) previewNode.value = null })

// ==================== 历史版本 ====================

const versionDialog = ref(false)
const versions = ref([])
const versionFile = ref(null)
const versionLoading = ref(false)

const openVersions = async (row) => {
  versionFile.value = row
  versions.value = []
  versionDialog.value = true
  versionLoading.value = true
  try {
    const res = await getDocVersions(row.id)
    versions.value = res.data || []
  } catch (e) { /* 统一弹错 */ } finally {
    versionLoading.value = false
  }
}
const doDownloadVersion = async (v) => {
  try {
    const blob = await downloadDocVersion(versionFile.value.id, v.name)
    saveBlob(blob, `${versionFile.value.name}-${v.name}`)
  } catch (e) { /* 统一弹错 */ }
}
const doRestore = async (v) => {
  try {
    await ElMessageBox.confirm(t('myDocs.versionRestoreConfirm', { label: v.label }), t('myDocs.versionRestore'), { type: 'warning' })
  } catch (e) { return }
  try {
    await restoreDocVersion(versionFile.value.id, v.name)
    ElMessage.success(t('myDocs.versionRestoreOk'))
    versionDialog.value = false
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

// ==================== 生命周期 ====================

onMounted(() => {
  loadTree(false)
  loadSettings()
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))

// 配额百分比(用于头部展示)
const quotaPercent = computed(() => {
  const used = Number(stats.value.totalBytes || 0)
  const max = Number(quota.value.maxQuotaBytes || 1)
  if (max <= 0) return 0
  return Math.min(100, Math.round(used * 100 / max))
})
</script>

<template>
  <div class="page">
    <div class="body">
      <aside class="tree-pane">
        <div class="pane-head">
          <span class="root-title" @click="previewNode = null">
            <el-icon><Document /></el-icon>{{ $t('myDocs.allFiles') }}
          </span>
          <span class="head-tools">
            <el-tooltip :content="$t('myDocs.refresh')" placement="top">
              <el-button link size="small" :loading="loading" @click="loadTree()"><el-icon><Refresh /></el-icon></el-button>
            </el-tooltip>
            <el-tooltip :content="$t('myDocs.newDir')" placement="top">
              <el-button link size="small" @click="openNewDir()"><el-icon><FolderAdd /></el-icon></el-button>
            </el-tooltip>
          </span>
        </div>
        <el-tree
          :data="treeData"
          node-key="key"
          :props="{ label: 'name', children: 'children' }"
          :expand-on-click-node="false"
          default-expand-all
          draggable
          :allow-drop="allowDrop"
          :current-node-key="currentKey"
          @node-click="onTreeClick"
          @node-drop="onDrop"
        >
          <template #default="{ data }">
            <span class="tree-node" :class="{ 'is-file': data.kind === 'file' }">
              <span class="tree-label">
                <el-icon v-if="data.kind === 'dir'"><Folder /></el-icon>
                <el-icon v-else :class="`ficon ${colorOf(data.docType)}`">
                  <component :is="iconOf(data.docType)" />
                </el-icon>
                <span class="t-name" @click.stop="data.kind === 'file' && openPreview(findFileRow(data.id))">
                  {{ data.name }}<span v-if="data.kind === 'file' && data.ext" class="ext">.{{ data.ext }}</span>
                </span>
                <em v-if="data.kind === 'dir' && data.raw?.files?.length" class="count">{{ data.raw.files.length }}</em>
                <em v-else-if="data.kind === 'file'" class="meta">{{ data.sizeText }}</em>
              </span>
              <span class="tree-ops">
                <template v-for="(op, i) in buildTreeOps(data).slice(0, TREE_OPS_INLINE_MAX)" :key="op.key">
                  <el-tooltip :content="$t('myDocs.' + op.tipKey)" placement="top">
                    <el-button link size="small" :type="op.type" @click.stop="op.handler">
                      <el-icon><component :is="op.icon" /></el-icon>
                    </el-button>
                  </el-tooltip>
                </template>
                <el-dropdown v-if="buildTreeOps(data).length > TREE_OPS_INLINE_MAX" trigger="click" @click.stop>
                  <el-button link size="small">
                    <el-icon><More /></el-icon>
                  </el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="op in buildTreeOps(data).slice(TREE_OPS_INLINE_MAX)" :key="op.key" @click="op.handler">
                        <el-icon style="margin-right:6px;vertical-align:-2px"><component :is="op.icon" /></el-icon>
                        {{ $t('myDocs.' + op.tipKey) }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </span>
            </span>
          </template>
        </el-tree>
      </aside>

      <section class="list-pane">
        <div class="pane-head">
          <span class="cur">{{ previewNode ? previewNode.fullName : currentName }}</span>
          <el-input v-model="keyword" :placeholder="$t('myDocs.searchPlaceholder')" size="small" clearable style="width:200px" />
        </div>

        <!-- ---------- 文档详情区(默认视图) ---------- -->
        <div class="info-card" v-if="!previewNode && currentDirInfo">
          <div class="info-row">
            <el-icon class="info-icon"><Folder /></el-icon>
            <span class="info-name">{{ currentDirInfo.name }}</span>
          </div>
          <div class="info-body">
            <div class="info-stats">
              <div class="info-stat"><span class="num">{{ currentDirInfo.dirCount }}</span><span class="lab">{{ $t('myDocs.statDirs') }}</span></div>
              <div class="info-stat"><span class="num">{{ currentDirInfo.fileCount }}</span><span class="lab">{{ $t('myDocs.statFiles') }}</span></div>
              <div class="info-stat"><span class="num">{{ currentDirInfo.sizeText }}</span><span class="lab">{{ $t('myDocs.statSize') }}</span></div>
            </div>

            <!-- 个人配额 -->
            <div class="info-quota" :class="{ 'warn': quotaPercent > 80, 'danger': quotaPercent >= 95 }">
              <div class="quota-row">
                <span class="lab">{{ $t('myDocs.statQuota') }}</span>
                <span class="num">{{ stats.totalText || '0 B' }} / {{ stats.maxQuotaText || '500 MB' }}</span>
              </div>
              <div class="quota-bar"><div class="quota-fill" :style="{ width: quotaPercent + '%' }"></div></div>
              <div class="quota-sub">{{ $t('myDocs.statRemaining', { size: stats.remainingText || '0 B' }) }}</div>
            </div>
          </div>
          <div class="info-actions">
            <el-button size="small" @click="openNewDoc">
              <el-icon style="margin-right:4px"><Plus /></el-icon>{{ $t('myDocs.newDoc') }}
            </el-button>
            <el-button size="small" @click="openNewDir()">
              <el-icon style="margin-right:4px"><FolderAdd /></el-icon>{{ $t('myDocs.newDir') }}
            </el-button>
            <el-button size="small" @click="openImport">
              <el-icon style="margin-right:4px"><Upload /></el-icon>{{ $t('myDocs.importDoc') }}
            </el-button>
          </div>

          <!-- 当前目录下的文件列表: 点文件名在右侧预览 -->
          <div v-if="currentFiles.length" class="file-rows">
            <div v-for="row in currentFiles" :key="row.id" class="file-row" @click="openPreview(row)">
              <el-icon :class="`ficon ${colorOf(row.docType)}`"><component :is="iconOf(row.docType)" /></el-icon>
              <span class="fname" :title="row.fullName">{{ row.name }}<span class="ext">.{{ row.ext }}</span></span>
              <el-tag :type="tagOf(row.docType)" size="small" effect="plain">{{ typeLabel(row.docType) }}</el-tag>
              <span class="meta">{{ row.sizeText }}</span>
              <span class="meta dim">v{{ row.version }}</span>
              <span class="grow"></span>
              <el-button v-if="isOffice(row.docType) || !isEditable(row.docType)" link size="small" @click.stop="openPreview(row)">{{ $t('myDocs.actionPreview') }}</el-button>
              <el-button v-if="isEditable(row.docType)" link size="small" type="primary" @click.stop="openEditor(row)">{{ $t('myDocs.actionEdit') }}</el-button>
              <el-dropdown trigger="click" @click.stop>
                <el-button link size="small">{{ $t('myDocs.more') }} ▾</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="doDownload(row)">{{ $t('myDocs.actionDownload') }}</el-dropdown-item>
                    <el-dropdown-item @click="openRename(row)">{{ $t('myDocs.actionRename') }}</el-dropdown-item>
                    <el-dropdown-item @click="openMove(row)">{{ $t('myDocs.actionMove') }}</el-dropdown-item>
                    <el-dropdown-item @click="doCopy(row)">{{ $t('myDocs.actionCopy') }}</el-dropdown-item>
                    <el-dropdown-item v-if="isOffice(row.docType)" @click="openVersions(row)">{{ $t('myDocs.actionVersions') }}</el-dropdown-item>
                    <el-dropdown-item divided @click="doDelete(row)"><span style="color:#e85d5d">{{ $t('myDocs.actionDelete') }}</span></el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </div>
          <el-empty v-else :description="keyword ? $t('myDocs.emptySearch') : $t('myDocs.emptyFiles')" :image-size="80">
            <p class="empty-hint">{{ $t('myDocs.emptyHint') }}</p>
          </el-empty>
        </div>

        <!-- ---------- 内联预览区(点文件名进这里, 不再是弹窗) ---------- -->
        <div v-else-if="previewNode" class="preview-card">
          <div class="pv-head">
            <el-icon :class="`ficon ${colorOf(previewNode.docType)}`"><component :is="iconOf(previewNode.docType)" /></el-icon>
            <span class="pv-name" :title="previewNode.fullName">{{ previewNode.fullName }}</span>
            <el-tag :type="tagOf(previewNode.docType)" size="small" effect="plain">{{ typeLabel(previewNode.docType) }}</el-tag>
            <span class="pv-meta dim">{{ previewNode.sizeText }} · v{{ previewNode.version }}</span>
            <span class="grow"></span>
            <el-button size="small" @click="previewNode = null">
              <el-icon style="margin-right:4px"><ArrowRight class="flip" /></el-icon>{{ $t('myDocs.backToDetail') }}
            </el-button>
            <el-button v-if="isEditable(previewNode.docType)" size="small" type="primary" @click="openEditor(previewNode)">
              <el-icon style="margin-right:4px"><Edit /></el-icon>{{ $t('myDocs.actionEdit') }}
            </el-button>
            <el-button size="small" @click="doDownload(previewNode)">
              <el-icon style="margin-right:4px"><Download /></el-icon>{{ $t('myDocs.actionDownload') }}
            </el-button>
            <el-button v-if="isOffice(previewNode.docType)" size="small" @click="openVersions(previewNode)">
              {{ $t('myDocs.actionVersions') }}
            </el-button>
          </div>
          <div class="pv-body">
            <OnlyOfficeEditor
              v-if="isOffice(previewNode.docType) && ooReady"
              :key="previewNode.id"
              mode="view"
              :file-id="previewNode.id"
              :doc-type="previewNode.docType"
              :user-id="currentUserId"
              :username="currentUsername"
            />
            <FilePreview v-else-if="!isOffice(previewNode.docType)" :file="previewNode" />
            <div v-else class="pv-fallback">
              <el-empty :description="$t('myDocs.previewOfficeHint')" :image-size="80">
                <el-button type="primary" @click="openEditor(previewNode)">{{ $t('myDocs.actionEdit') }}</el-button>
              </el-empty>
            </div>
          </div>
        </div>

        <el-empty v-else :description="keyword ? $t('myDocs.emptySearch') : $t('myDocs.emptyFiles')" :image-size="80">
          <p class="empty-hint">{{ $t('myDocs.emptyHint') }}</p>
        </el-empty>
      </section>
    </div>

    <!-- ============== 弹窗区 ============== -->

    <el-dialog v-model="dirDialog" :title="dirForm.id == null ? $t('myDocs.newDir') : $t('myDocs.renameTitle')" width="440px">
      <el-form label-width="88px">
        <el-form-item :label="$t('myDocs.dirName')">
          <el-input v-model="dirForm.name" :placeholder="$t('myDocs.dirNamePlaceholder')" maxlength="100" @keyup.enter="submitDir" />
        </el-form-item>
        <el-form-item v-if="dirForm.id == null" :label="$t('myDocs.parentDir')">
          <el-tree-select v-model="dirForm.parentId" :data="dirOptions" check-strictly :render-after-expand="false" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dirDialog = false">{{ $t('myDocs.cancelBtn') }}</el-button>
        <el-button type="primary" @click="submitDir">{{ $t('myDocs.confirmBtn') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="newDocDialog" :title="$t('myDocs.newDocTitle')" width="500px">
      <el-form label-width="88px">
        <el-form-item :label="$t('myDocs.docType')">
          <el-select v-model="newDocForm.docType" style="width:100%">
            <el-option value="word" :label="$t('myDocs.typeWord')" />
            <el-option value="excel" :label="$t('myDocs.typeExcel')" />
            <el-option value="ppt" :label="$t('myDocs.typePpt')" />
            <el-option value="markdown" :label="$t('myDocs.typeMarkdown')" />
            <el-option value="richtext" :label="$t('myDocs.typeRichText')" />
          </el-select>
        </el-form-item>
        <el-form-item :label="$t('myDocs.docName')">
          <el-input v-model="newDocForm.name" :placeholder="$t('myDocs.docNamePlaceholder')" maxlength="100" @keyup.enter="submitNewDoc" />
        </el-form-item>
        <el-form-item :label="$t('myDocs.belongDir')">
          <el-tree-select v-model="newDocForm.directoryId" :data="dirOptions" check-strictly :render-after-expand="false" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="newDocDialog = false">{{ $t('myDocs.cancelBtn') }}</el-button>
        <el-button type="primary" :loading="creating" @click="submitNewDoc">{{ $t('myDocs.createBtn') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importDialog" :title="$t('myDocs.importTitle')" width="560px">
      <p class="dlg-desc">{{ $t('myDocs.importTip2') }}</p>
      <div class="drop-area" @click="pickFile" @dragover.prevent @drop.prevent="onDropFile">
        <el-icon class="drop-icon"><UploadFilled /></el-icon>
        <p v-if="!importFile" class="drop-text">{{ $t('myDocs.importHint') }}</p>
        <p v-else class="drop-file"><el-icon><Document /></el-icon> {{ importFile.name }} · {{ humanSize(importFile.size) }}</p>
        <input ref="fileInput" type="file" :accept="acceptExtensions" style="display:none" @change="onFilePicked" />
      </div>
      <el-form label-width="88px" style="margin-top:14px">
        <el-form-item :label="$t('myDocs.importInto')">
          <el-tree-select v-model="importDir" :data="dirOptions" check-strictly :render-after-expand="false" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importDialog = false">{{ $t('myDocs.cancelBtn') }}</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">{{ $t('myDocs.importBtn') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="renameDialog" :title="$t('myDocs.renameTitle')" width="440px">
      <el-form label-width="88px">
        <el-form-item :label="$t('myDocs.nameLabel')">
          <el-input v-model="renameForm.name" maxlength="100" @keyup.enter="submitRename">
            <template #append>.{{ renameForm.ext }}</template>
          </el-input>
        </el-form-item>
      </el-form>
      <p class="dlg-desc">{{ $t('myDocs.renameExtTip') }}</p>
      <template #footer>
        <el-button @click="renameDialog = false">{{ $t('myDocs.cancelBtn') }}</el-button>
        <el-button type="primary" @click="submitRename">{{ $t('myDocs.confirmBtn') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="moveDialog" :title="$t('myDocs.moveTitle')" width="460px">
      <el-form label-width="88px">
        <el-form-item :label="$t('myDocs.colName')">
          <el-input :model-value="moveForm.name" disabled />
        </el-form-item>
        <el-form-item :label="$t('myDocs.moveTarget')">
          <el-tree-select v-model="moveForm.directoryId" :data="dirOptions" check-strictly :render-after-expand="false" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="moveDialog = false">{{ $t('myDocs.cancelBtn') }}</el-button>
        <el-button type="primary" @click="submitMove">{{ $t('myDocs.confirmBtn') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="versionDialog" :title="$t('myDocs.versionTitle')" width="560px">
      <div v-loading="versionLoading">
        <el-table v-if="versions.length" :data="versions" size="small">
          <el-table-column prop="label" :label="$t('myDocs.colUpdated')" min-width="180" />
          <el-table-column :label="$t('myDocs.colSize')" width="100" align="right">
            <template #default="{ row }">{{ (row.size / 1024).toFixed(1) }} KB</template>
          </el-table-column>
          <el-table-column :label="$t('common.actions')" width="170" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="doDownloadVersion(row)">{{ $t('myDocs.versionDownload') }}</el-button>
              <el-button link type="danger" size="small" @click="doRestore(row)">{{ $t('myDocs.versionRestore') }}</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else :description="$t('myDocs.versionEmpty')" :image-size="70" />
      </div>
      <template #footer>
        <el-button @click="versionDialog = false">{{ $t('myDocs.closeBtn') }}</el-button>
      </template>
    </el-dialog>

    <!-- ============== 全屏编辑器 ============== -->
    <el-dialog
      v-model="editorVisible"
      fullscreen
      :close-on-click-modal="false"
      :before-close="beforeCloseEditor"
      class="editor-dialog"
    >
      <template #header>
        <div class="editor-header">
          <span class="editor-title">{{ $t('myDocs.editorTitle', { name: editing ? editing.fullName : '' }) }}</span>
          <span class="editor-save-state">
            <span v-if="dirty" class="dirty">● {{ $t('myDocs.unsavedTitle') }}</span>
            <span v-else-if="lastSaved" class="saved">{{ lastSaved }}</span>
          </span>
          <span class="grow" />
          <span class="editor-tip">{{ $t('myDocs.shortcutTip') }}</span>
          <el-button size="small" :loading="saving" type="primary" @click="doSave">{{ $t('myDocs.saveBtn') }}</el-button>
          <el-button size="small" plain @click="editorVisible = false">{{ $t('myDocs.closeBtn') }}</el-button>
        </div>
      </template>
      <div v-loading="editorLoading" class="editor-body">
        <OnlyOfficeEditor
          v-if="ooEnabled && editing"
          :key="editing.id"
          :file-id="editing.id"
          :doc-type="editing.docType"
          :user-id="currentUserId"
          :username="currentUsername"
          @save="markDirty"
          @saved="onOOSaved"
        />
        <template v-else-if="model">
          <WordEditor v-if="editing.docType === 'word'" :model="model" @change="markDirty" />
          <SheetEditor v-else-if="editing.docType === 'excel'" :model="model" @change="markDirty" />
          <SlideEditor v-else-if="editing.docType === 'ppt'" :model="model" @change="markDirty" />
          <MarkdownEditor v-else-if="editing.docType === 'markdown'" :model="model" @change="(p) => { model.content = p.content; markDirty() }" />
          <RichTextEditor v-else-if="editing.docType === 'richtext'" :model="model" @change="(p) => { model.content = p.content; markDirty() }" />
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style>
/* 编辑器弹窗全局样式 (el-dialog 子组件, scoped 不会注入 data-v 到子组件根) */
.editor-dialog { display: flex; flex-direction: column; }
.editor-dialog .el-dialog__body { padding: 0; flex: 1; min-height: 0; }
.editor-dialog .el-dialog__header { padding: 0; border-bottom: 1px solid var(--c-border); margin-right: 0; flex: 0 0 auto; }
.editor-dialog .el-dialog__headerbtn { top: 10px; right: 14px; }
.editor-dialog .el-dialog__footer { padding: 0; border-top: 1px solid var(--c-border); }
/* 预览弹窗 */
.preview-dialog { display: flex; flex-direction: column; }
.preview-dialog .el-dialog__body { padding: 0; flex: 1; min-height: 0; display: flex; flex-direction: column; }
.preview-dialog .preview-body { flex: 1; min-height: 540px; display: flex; }
</style>

<style scoped>
.page { padding: 16px 32px 24px; }
.body { display: flex; gap: 16px; align-items: flex-start; }
.tree-pane { width: 280px; min-width: 240px; flex: none; background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 10px 8px 14px; box-shadow: 0 2px 6px rgba(20,30,60,.04); max-height: calc(100vh - 220px); overflow-x: visible; overflow-y: auto; align-self: flex-start; }
.tree-pane .el-tree { background: transparent; }
.tree-pane .pane-head .head-tools { display: inline-flex; align-items: center; gap: 2px; }
.tree-pane .pane-head .root-title { display: inline-flex; align-items: center; gap: 6px; font-weight: 600; color: var(--c-text-1); font-size: 14px; cursor: pointer; }
.tree-pane .pane-head .root-title:hover { color: var(--c-primary); }
/* 悬浮操作按钮要能完整显示, 不能被节点宽度裁掉 */
.tree-pane :deep(.el-tree-node__content) { position: relative; overflow: visible; min-height: 36px; }
.tree-pane :deep(.el-tree-node__content):hover { background: var(--c-primary-bg); border-radius: 4px; }
.tree-pane :deep(.el-tree-node.is-current > .el-tree-node__content) { background: var(--c-primary-bg); border-radius: 4px; color: var(--c-primary); }
/* 行高需要装下 el-button--small(32px) + 2px 上下 padding = 36px, 否则 hover 时按钮会向上下溢出 5px, 蓝色高亮也比按钮矮一截 */
.tree-node { display: flex; align-items: center; justify-content: space-between; width: 100%; padding-right: 4px; min-height: 36px; position: relative; }
/* 悬浮时给右侧按钮组留出空间, 名称用省略号收缩而不是把按钮挤出去 */
/* 留 90px:可装下 3 个紧凑图标按钮 (~22px each) + "..." 溢出菜单 (24px) + 容器内边距 */
.tree-node:hover .tree-label { padding-right: 90px; }
.tree-node .tree-ops {
  position: absolute; right: 2px; top: 0; bottom: 0;
  display: none; align-items: center; gap: 0; flex-wrap: nowrap; white-space: nowrap;
  background: var(--c-primary-bg); padding: 0 4px; border-radius: 4px; z-index: 2;
}
.tree-node:hover .tree-ops { display: inline-flex; }
/* 缩小图标按钮的左右 padding, 让操作按钮更紧凑(默认 2px 是 el-button--small 的内置值) */
.tree-node .tree-ops .el-button { padding: 0 4px; }
.tree-node .tree-ops .el-button .el-icon { font-size: 14px; }
.tree-node .tree-label .t-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; cursor: pointer; }
.tree-node .tree-label .t-name:hover { color: var(--c-primary); }
.tree-node .tree-label .ext { color: var(--c-text-4); font-size: 12px; }
.tree-node .tree-label .count, .tree-node .tree-label .meta { font-style: normal; font-size: 11px; color: var(--c-text-4); background: var(--c-bg-3, #f3f4f8); border-radius: 8px; padding: 0 6px; flex: 0 0 auto; }
.tree-node .tree-label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--c-text-1); flex: 1; min-width: 0; }
.tree-node.is-file .tree-label { color: var(--c-text-2); }
.tree-node.is-file .tree-label .ficon { font-size: 14px; }
.list-pane { flex: 1; min-width: 0; background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 10px 14px 16px; box-shadow: 0 2px 6px rgba(20,30,60,.04); }
.pane-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 4px 6px 10px; border-bottom: 1px solid var(--c-border); margin-bottom: 10px; }
.pane-head .cur { font-weight: 600; color: var(--c-text-1); font-size: 14px; }

.info-card { background: linear-gradient(180deg, #f7f9ff 0%, #fff 100%); border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 22px 20px; }
.info-card .info-row { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.info-card .info-row .info-icon { font-size: 28px; color: var(--c-primary); }
.info-card .info-row .info-name { font-size: 18px; font-weight: 600; color: var(--c-text-1); }
/* 详情区: 左侧三项统计 + 右侧配额 */
.info-card .info-body { display: flex; align-items: stretch; gap: 12px; flex-wrap: wrap; }
.info-card .info-stats { display: flex; gap: 12px; flex-wrap: wrap; }
.info-card .info-stat { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-s); padding: 10px 18px; display: flex; align-items: baseline; gap: 6px; min-width: 120px; }
.info-card .info-stat .num { font-size: 20px; font-weight: 600; color: var(--c-primary); }
.info-card .info-stat .lab { font-size: 12px; color: var(--c-text-2); }
.info-card .info-quota { flex: 1; min-width: 240px; background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-s); padding: 10px 16px; display: flex; flex-direction: column; gap: 4px; }
.info-card .info-quota .quota-row { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.info-card .info-quota .quota-row .lab { font-size: 12px; color: var(--c-text-2); }
.info-card .info-quota .quota-row .num { font-size: 13px; font-weight: 600; color: var(--c-text-1); }
.info-card .info-quota .quota-bar { height: 4px; background: var(--c-bg-3, #eceef3); border-radius: 2px; overflow: hidden; }
.info-card .info-quota .quota-fill { height: 100%; background: var(--c-primary); transition: width .3s; }
.info-card .info-quota.warn .quota-fill { background: #f59e0b; }
.info-card .info-quota.danger .quota-fill { background: #e85d5d; }
.info-card .info-quota.warn .quota-row .num { color: #b45309; }
.info-card .info-quota.danger .quota-row .num { color: #c0392b; }
.info-card .info-quota .quota-sub { font-size: 11px; color: var(--c-text-4); }
.info-card .info-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 16px; }

/* 内联预览区(替代原弹窗) */
.preview-card { border: 1px solid var(--c-border); border-radius: var(--r-m); background: #fff; overflow: hidden; }
.preview-card .pv-head { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--c-border); flex-wrap: wrap; }
.preview-card .pv-head .pv-name { font-size: 15px; font-weight: 600; color: var(--c-text-1); max-width: 42%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.preview-card .pv-head .pv-meta { font-size: 12px; }
.preview-card .pv-head .grow { flex: 1; }
.preview-card .pv-body { height: min(70vh, 680px); min-height: 420px; background: var(--c-bg-page, #f7f8fb); }
.preview-card .pv-fallback { height: 100%; display: flex; align-items: center; justify-content: center; }
.flip { transform: rotate(180deg); }

.file-rows { margin-top: 14px; border-top: 1px dashed var(--c-border); padding-top: 12px; display: flex; flex-direction: column; gap: 4px; }
.file-row { display: flex; align-items: center; gap: 10px; padding: 6px 8px; border-radius: 4px; cursor: pointer; }
.file-row:hover { background: var(--c-primary-bg); }
.file-row .fname { font-size: 14px; color: var(--c-text-1); max-width: 280px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-row .fname .ext { color: var(--c-text-4); font-size: 12px; }
.file-row .meta { font-size: 12px; color: var(--c-text-3); min-width: 70px; }
.file-row .meta.dim { color: var(--c-text-4); }
.file-row .grow { flex: 1; }
.ficon { color: var(--c-text-3); font-size: 16px; }
.ficon.i-word { color: #2b579a; }
.ficon.i-excel { color: #1d7044; }
.ficon.i-ppt { color: #c4562c; }
.ficon.i-md { color: #6b46c1; }
.ficon.i-rt { color: #b45309; }
.ficon.i-image { color: #ec4899; }
.ficon.i-pdf { color: #dc2626; }
.ficon.i-html { color: #0e7490; }
.ficon.i-text { color: #475569; }
.ficon.i-code { color: #1f2937; }

.empty-hint { color: var(--myDocs-text-3); font-size: 13px; }

.dlg-desc { margin: 0 0 10px; color: var(--c-text-2); font-size: var(--fs-sm); line-height: 1.7; }
.drop-area { border: 1px dashed var(--c-border-strong); border-radius: var(--r-m); padding: 26px 16px; text-align: center; cursor: pointer; background: var(--c-bg-page); }
.drop-area:hover { border-color: var(--c-primary); background: var(--c-primary-bg); }
.drop-icon { font-size: 30px; color: var(--c-primary); }
.drop-text { margin: 8px 0 0; color: var(--c-text-2); font-size: 13px; }
.drop-file { margin: 8px 0 0; color: var(--c-primary); font-size: 13px; display: flex; align-items: center; justify-content: center; gap: 6px; }

.editor-body { width: 100%; height: 100%; min-height: 0; display: flex; flex-direction: column; }
.editor-header { display: flex; align-items: center; gap: 14px; padding: 8px 50px 8px 20px; background: #fff; min-height: 48px; }
.editor-header .editor-title { font-size: 15px; font-weight: 600; color: var(--c-text-1); max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.editor-header .editor-save-state { font-size: 12px; color: var(--c-text-4); display: flex; align-items: center; gap: 8px; }
.editor-header .editor-save-state .dirty { color: var(--c-warning); }
.editor-header .editor-save-state .saved { color: var(--c-success); }
.editor-header .editor-tip { font-size: 12px; color: var(--c-text-4); margin-right: 4px; }
.editor-header .grow { flex: 1; }

@media (max-width: 768px) {
  .page { padding: 16px; }
  .body { flex-direction: column; }
  .tree-pane { width: 100%; max-height: 260px; }
  .info-card .info-body { flex-direction: column; }
  .preview-card .pv-body { height: calc(100vh - 220px); min-height: 340px; }
  .preview-card .pv-head .pv-name { max-width: 100%; }
}
</style>
