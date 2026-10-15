<script setup>
// 个人文档: 目录树 + 文件列表 + Word/Excel/PPT 在线编辑 + 导入导出 + 历史版本
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getDocTree, createDocDir, renameDocDir, deleteDocDir, moveDocDir,
  createDocFile, importDocFile, renameDocFile, moveDocFile, copyDocFile,
  deleteDocFile, downloadDocFile, getDocModel, saveDocModel,
  getDocVersions, downloadDocVersion, restoreDocVersion
} from '../api/myDoc'
import WordEditor from '../components/doc/WordEditor.vue'
import SheetEditor from '../components/doc/SheetEditor.vue'
import SlideEditor from '../components/doc/SlideEditor.vue'

const { t } = useI18n()

const ROOT = 'root'

const loading = ref(false)
const directories = ref([])
const rootFiles = ref([])
const stats = ref({})
const currentKey = ref(ROOT)
const keyword = ref('')

// ==================== 数据加载 ====================

const loadTree = async (keepKey = true) => {
  loading.value = true
  try {
    const res = await getDocTree()
    const data = res.data || {}
    directories.value = data.directories || []
    rootFiles.value = data.rootFiles || []
    stats.value = data.stats || {}
    if (!keepKey) currentKey.value = ROOT
    // 当前目录被删掉时退回根目录
    if (currentKey.value !== ROOT && !directories.value.some(d => d.id === currentKey.value)) {
      currentKey.value = ROOT
    }
  } catch (e) { /* 统一弹错 */ } finally {
    loading.value = false
  }
}

const dirMap = computed(() => {
  const m = {}
  directories.value.forEach(d => { m[d.id] = d })
  return m
})

/** 目录树(带 children); 顶层加一个虚拟"全部文档"节点 */
const treeData = computed(() => {
  const nodes = {}
  directories.value.forEach(d => {
    nodes[d.id] = { key: d.id, name: d.name, raw: d, files: d.files || [], children: [] }
  })
  const roots = []
  directories.value.forEach(d => {
    const node = nodes[d.id]
    if (d.parentId && nodes[d.parentId]) nodes[d.parentId].children.push(node)
    else roots.push(node)
  })
  return [{ key: ROOT, name: t('myDocs.allFiles'), files: rootFiles.value, children: roots, isRoot: true }]
})

/** 目录树选择器数据(移动/新建时选目标目录); 0 = 根目录 */
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
  const list = currentKey.value === ROOT ? rootFiles.value : (dirMap.value[currentKey.value]?.files || [])
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(f => f.fullName.toLowerCase().includes(kw))
})

const onTreeClick = (node) => { currentKey.value = node.key; keyword.value = '' }

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

// 拖拽调整目录层级(只允许拖到目录内部)
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

const typeLabel = (docType) => ({
  word: t('myDocs.typeWord'),
  excel: t('myDocs.typeExcel'),
  ppt: t('myDocs.typePpt')
}[docType] || docType)

const typeTag = (docType) => ({ word: 'primary', excel: 'success', ppt: 'warning' }[docType] || 'info')

// ---- 新建文档 ----
const newDocDialog = ref(false)
const newDocForm = ref({ name: '', docType: 'word', directoryId: 0 })
const creating = ref(false)

const openNewDoc = () => {
  newDocForm.value = { name: '', docType: 'word', directoryId: currentKey.value === ROOT ? 0 : currentKey.value }
  newDocDialog.value = true
}
const submitNewDoc = async () => {
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

const openImport = () => {
  importFile.value = null
  importDir.value = currentKey.value === ROOT ? 0 : currentKey.value
  importDialog.value = true
}
const pickFile = () => fileInput.value?.click()
const onFilePicked = (e) => {
  const f = e.target.files?.[0]
  if (!f) return
  if (!/\.(docx|xlsx|pptx)$/i.test(f.name)) { ElMessage.error(t('myDocs.importLegacy')); e.target.value = ''; return }
  importFile.value = f
  e.target.value = ''
}
const onDropFile = (e) => {
  const f = e.dataTransfer?.files?.[0]
  if (!f) return
  if (!/\.(docx|xlsx|pptx)$/i.test(f.name)) { ElMessage.error(t('myDocs.importLegacy')); return }
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
    await loadTree()
  } catch (e) { /* 统一弹错 */ }
}

/** 触发浏览器下载 */
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

// ==================== 在线编辑 ====================

const editorVisible = ref(false)
const editorLoading = ref(false)
const saving = ref(false)
const editing = ref(null)      // 当前文件
const model = ref(null)        // 编辑器模型
const dirty = ref(false)
const lastSaved = ref('')

const openEditor = async (row) => {
  editing.value = row
  model.value = null
  dirty.value = false
  lastSaved.value = ''
  editorVisible.value = true
  editorLoading.value = true
  try {
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
      // 点"放弃修改" → 关闭; 关闭图标 → 不关闭
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
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>{{ $t('myDocs.title') }}</h1>
        <p>{{ $t('myDocs.subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <el-button @click="openNewDir()">
          <el-icon style="margin-right:4px"><FolderAdd /></el-icon>{{ $t('myDocs.newDir') }}
        </el-button>
        <el-button type="primary" @click="openNewDoc">
          <el-icon style="margin-right:4px"><DocumentAdd /></el-icon>{{ $t('myDocs.newDoc') }}
        </el-button>
        <el-button @click="openImport">
          <el-icon style="margin-right:4px"><Upload /></el-icon>{{ $t('myDocs.importDoc') }}
        </el-button>
        <el-button :loading="loading" @click="loadTree()">
          <el-icon style="margin-right:4px"><Refresh /></el-icon>{{ $t('myDocs.refresh') }}
        </el-button>
      </div>
    </div>

    <div class="stats">
      <div class="stat"><span class="num">{{ stats.dirCount ?? 0 }}</span><span class="lab">{{ $t('myDocs.statDirs') }}</span></div>
      <div class="stat"><span class="num">{{ stats.fileCount ?? 0 }}</span><span class="lab">{{ $t('myDocs.statFiles') }}</span></div>
      <div class="stat"><span class="num">{{ stats.totalText ?? '0 B' }}</span><span class="lab">{{ $t('myDocs.statSize') }}</span></div>
      <div class="stat wide">
        <span class="lab">{{ $t('myDocs.statDetail', { word: stats.wordCount ?? 0, excel: stats.excelCount ?? 0, ppt: stats.pptCount ?? 0 }) }}</span>
      </div>
    </div>

    <div class="body">
      <aside class="tree-pane">
        <div class="pane-head">
          <span>{{ $t('myDocs.allFiles') }}</span>
          <el-button link size="small" @click="openNewDir()">{{ $t('myDocs.newDir') }}</el-button>
        </div>
        <el-tree
          :data="treeData"
          node-key="key"
          :props="{ label: 'name', children: 'children' }"
          :expand-on-click-node="false"
          default-expand-all
          draggable
          :allow-drop="allowDrop"
          @node-click="onTreeClick"
          @node-drop="onDrop"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-label">
                <el-icon><Folder v-if="!data.isRoot" /><Files v-else /></el-icon>
                {{ data.name }}
                <em v-if="data.files?.length" class="count">{{ data.files.length }}</em>
              </span>
              <span v-if="!data.isRoot" class="tree-ops">
                <el-button link size="small" @click.stop="openRenameDir(data)">{{ $t('myDocs.actionRename') }}</el-button>
                <el-button link size="small" type="danger" @click.stop="removeDir(data)">{{ $t('myDocs.actionDelete') }}</el-button>
              </span>
            </span>
          </template>
        </el-tree>
      </aside>

      <section class="list-pane">
        <div class="pane-head">
          <span class="cur">{{ currentName }}</span>
          <el-input v-model="keyword" :placeholder="$t('myDocs.searchPlaceholder')" size="small" clearable style="width:200px" />
        </div>
        <el-table :data="currentFiles" v-loading="loading" style="width:100%" size="default">
          <el-table-column :label="$t('myDocs.colName')" min-width="240">
            <template #default="{ row }">
              <span class="fname" @click="openEditor(row)">
                <el-icon class="ficon" :class="`i-${row.docType}`"><Document /></el-icon>
                <b>{{ row.name }}</b><span class="ext">.{{ row.ext }}</span>
              </span>
            </template>
          </el-table-column>
          <el-table-column :label="$t('myDocs.colType')" width="140">
            <template #default="{ row }">
              <el-tag size="small" :type="typeTag(row.docType)">{{ typeLabel(row.docType) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sizeText" :label="$t('myDocs.colSize')" width="100" align="right" />
          <el-table-column prop="updateTime" :label="$t('myDocs.colUpdated')" width="180" />
          <el-table-column :label="$t('common.actions')" width="330" align="right">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click="openEditor(row)">{{ $t('myDocs.actionEdit') }}</el-button>
              <el-button link type="primary" size="small" @click="doDownload(row)">{{ $t('myDocs.actionDownload') }}</el-button>
              <el-dropdown trigger="click">
                <el-button link type="primary" size="small">{{ $t('common.actions') }}</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item @click="openRename(row)">{{ $t('myDocs.actionRename') }}</el-dropdown-item>
                    <el-dropdown-item @click="openMove(row)">{{ $t('myDocs.actionMove') }}</el-dropdown-item>
                    <el-dropdown-item @click="doCopy(row)">{{ $t('myDocs.actionCopy') }}</el-dropdown-item>
                    <el-dropdown-item @click="openVersions(row)">{{ $t('myDocs.actionVersions') }}</el-dropdown-item>
                    <el-dropdown-item divided @click="doDelete(row)">{{ $t('myDocs.actionDelete') }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="keyword ? $t('myDocs.emptySearch') : $t('myDocs.emptyFiles')" :image-size="80">
              <p class="empty-hint">{{ $t('myDocs.emptyHint') }}</p>
            </el-empty>
          </template>
        </el-table>
      </section>
    </div>

    <!-- 新建 / 重命名目录 -->
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

    <!-- 新建文档 -->
    <el-dialog v-model="newDocDialog" :title="$t('myDocs.newDocTitle')" width="460px">
      <el-form label-width="88px">
        <el-form-item :label="$t('myDocs.docType')">
          <el-radio-group v-model="newDocForm.docType">
            <el-radio-button value="word">{{ $t('myDocs.typeWord') }}</el-radio-button>
            <el-radio-button value="excel">{{ $t('myDocs.typeExcel') }}</el-radio-button>
            <el-radio-button value="ppt">{{ $t('myDocs.typePpt') }}</el-radio-button>
          </el-radio-group>
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

    <!-- 导入 -->
    <el-dialog v-model="importDialog" :title="$t('myDocs.importTitle')" width="520px">
      <p class="dlg-desc">{{ $t('myDocs.importTip', { size: '20 MB' }) }}</p>
      <div class="drop-area" @click="pickFile" @dragover.prevent @drop.prevent="onDropFile">
        <el-icon class="drop-icon"><UploadFilled /></el-icon>
        <p v-if="!importFile" class="drop-text">{{ $t('myDocs.importHint') }}</p>
        <p v-else class="drop-file"><el-icon><Document /></el-icon> {{ importFile.name }}</p>
        <input ref="fileInput" type="file" accept=".docx,.xlsx,.pptx" style="display:none" @change="onFilePicked" />
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

    <!-- 重命名文件 -->
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

    <!-- 移动文件 -->
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

    <!-- 历史版本 -->
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

    <!-- 在线编辑器
         没有底部 footer:保存按钮放在顶部标题栏(更像腾讯文档/Google Docs)
         关闭依赖 el-dialog 标题栏右侧的 × 图标(避免和 × 重叠)
    -->
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
        <template v-if="model">
          <WordEditor v-if="editing.docType === 'word'" :model="model" @change="markDirty" />
          <SheetEditor v-else-if="editing.docType === 'excel'" :model="model" @change="markDirty" />
          <SlideEditor v-else :model="model" @change="markDirty" />
        </template>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; }
.hero { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.hero h1 { margin: 0 0 4px; font-size: var(--fs-xl); font-weight: 600; color: var(--c-text-1); }
.hero p { margin: 0; color: var(--c-text-2); font-size: var(--fs-base); }
.hero-actions { display: flex; gap: 10px; flex-wrap: wrap; flex-shrink: 0; }

.stats { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.stat { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 10px 18px; display: flex; align-items: baseline; gap: 8px; box-shadow: 0 2px 6px rgba(20,30,60,.04); }
.stat .num { font-size: 20px; font-weight: 600; color: var(--c-primary); }
.stat .lab { font-size: 12px; color: var(--c-text-2); }
.stat.wide { flex: 1; justify-content: flex-end; }

.body { display: flex; gap: 16px; align-items: flex-start; }
.tree-pane { width: 268px; flex: none; background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 10px 8px 14px; box-shadow: 0 2px 6px rgba(20,30,60,.04); max-height: calc(100vh - 260px); overflow: auto; }
.list-pane { flex: 1; min-width: 0; background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 10px 14px 16px; box-shadow: 0 2px 6px rgba(20,30,60,.04); }
.pane-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; padding: 4px 6px 10px; border-bottom: 1px solid var(--c-border); margin-bottom: 10px; }
.pane-head .cur { font-weight: 600; color: var(--c-text-1); font-size: 14px; }
.tree-node { display: flex; align-items: center; justify-content: space-between; width: 100%; padding-right: 6px; }
.tree-label { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--c-text-1); }
.tree-label .count { font-style: normal; font-size: 11px; color: var(--c-text-4); background: var(--c-bg-3, #f3f4f8); border-radius: 8px; padding: 0 6px; }
.tree-ops { display: none; }
.tree-node:hover .tree-ops { display: inline-flex; }

.fname { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; color: var(--c-text-1); }
.fname:hover { color: var(--c-primary); }
.fname .ext { color: var(--c-text-4); font-size: 12px; }
.ficon { color: var(--c-text-3); }
.ficon.i-word { color: #2b579a; }
.ficon.i-excel { color: #1d7044; }
.ficon.i-ppt { color: #c4562c; }
.empty-hint { margin: 6px 0 0; font-size: 12px; color: var(--c-text-4); }

.dlg-desc { margin: 0 0 10px; color: var(--c-text-2); font-size: var(--fs-sm); line-height: 1.7; }
.drop-area { border: 1px dashed var(--c-border-strong); border-radius: var(--r-m); padding: 26px 16px; text-align: center; cursor: pointer; background: var(--c-bg-page); }
.drop-area:hover { border-color: var(--c-primary); background: var(--c-primary-bg); }
.drop-icon { font-size: 30px; color: var(--c-primary); }
.drop-text { margin: 8px 0 0; color: var(--c-text-2); font-size: 13px; }
.drop-file { margin: 8px 0 0; color: var(--c-primary); font-size: 13px; display: flex; align-items: center; justify-content: center; gap: 6px; }

.editor-dialog :deep(.el-dialog__body) { padding: 0; height: calc(100vh - 49px); }
.editor-body { min-height: calc(100vh - 49px); }
.save-state { float: left; display: flex; align-items: center; gap: 14px; font-size: 12px; color: var(--c-text-4); }
.save-state .dirty { color: var(--c-warning); }
.save-state .saved { color: var(--c-success); }

/* 自定义编辑器标题栏:左侧标题,中部保存状态,右侧快捷键提示 + 保存/关闭按钮 */
.editor-dialog :deep(.el-dialog__header) { padding: 0; border-bottom: 1px solid var(--c-border); margin-right: 0; }
.editor-dialog :deep(.el-dialog__headerbtn) { top: 12px; right: 14px; }
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
  .tree-pane { width: 100%; max-height: 240px; }
  .hero { flex-direction: column; }
  .stat.wide { justify-content: flex-start; }
}
</style>
