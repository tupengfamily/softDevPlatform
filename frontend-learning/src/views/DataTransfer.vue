<script setup>
// 个人数据导入导出: 只显示导入导出记录(功能/时间/大小/下载/查看),顶部保留导出/导入操作
import { ref, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { exportUserData, importUserData, getDataRecords, getDataRecord } from '../api/personal'

const { t } = useI18n()

const records = ref([])
const loading = ref(false)

// 导入对话框
const importing = ref(false)
const importDialog = ref(false)
const importText = ref('')
const importResult = ref(null)

// 查看对话框
const viewDialog = ref(false)
const viewRecord = ref(null)

const typeMap = computed(() => ({
  EXPORT: { label: t('dataTransfer.typeExport'), type: 'success' },
  IMPORT: { label: t('dataTransfer.typeImport'), type: 'warning' },
}))

const formatBytes = (n) => {
  if (n == null) return '-'
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(2) + ' MB'
}

const loadRecords = async () => {
  loading.value = true
  try {
    const res = await getDataRecords()
    records.value = res.data || []
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

// 导出: 拉取 JSON 并触发下载(后端同时落一条导出记录)
const doExport = async () => {
  try {
    const res = await exportUserData()
    const data = res.data
    const blob = new Blob([data.content ?? JSON.stringify(data, null, 2)], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = data.fileName || 'learning-backup.json'
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(a.href)
    ElMessage.success(t('dataTransfer.exportDone'))
    loadRecords()
  } catch (e) { /* 已统一弹错 */ }
}

// 选文件填入文本框
const onFile = (e) => {
  const file = e.target.files?.[0]
  if (!file) return
  const reader = new FileReader()
  reader.onload = () => { importText.value = String(reader.result || '') }
  reader.readAsText(file)
  e.target.value = ''
}

// 导入: 合并模式(后端同时落一条导入记录)
const doImport = async () => {
  if (!importText.value.trim()) { ElMessage.warning(t('dataTransfer.importEmptyWarn')); return }
  let payload
  try {
    payload = JSON.parse(importText.value)
  } catch (e) {
    ElMessage.error(t('dataTransfer.importInvalidJson'))
    return
  }
  importing.value = true
  importResult.value = null
  try {
    const res = await importUserData(payload)
    importResult.value = res.data
    ElMessage.success(t('dataTransfer.importDone'))
    loadRecords()
  } catch (e) { /* 已统一弹错 */ } finally {
    importing.value = false
  }
}

// 查看记录内容
const openView = async (row) => {
  try {
    const res = await getDataRecord(row.id)
    viewRecord.value = res.data
    viewDialog.value = true
  } catch (e) { /* 已统一弹错 */ }
}

// 下载记录内容
const downloadRecord = async (row) => {
  try {
    const res = await getDataRecord(row.id)
    const d = res.data
    const blob = new Blob([d.content || ''], { type: 'application/json' })
    const a = document.createElement('a')
    a.href = URL.createObjectURL(blob)
    a.download = `learning-${(d.type === 'EXPORT' ? 'backup' : 'import')}-${row.id}.json`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(a.href)
    ElMessage.success(t('dataTransfer.downloadDone'))
  } catch (e) { /* 已统一弹错 */ }
}

const r = (k) => importResult.value?.[k] ?? 0

onMounted(loadRecords)
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>{{ $t('dataTransfer.title') }}</h1>
        <p>{{ $t('dataTransfer.subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <el-button type="primary" @click="doExport">
          <el-icon style="margin-right:4px"><Download /></el-icon>{{ $t('dataTransfer.exportBtn') }}
        </el-button>
        <el-button @click="importDialog = true; importResult = null">
          <el-icon style="margin-right:4px"><Upload /></el-icon>{{ $t('dataTransfer.importBtn') }}
        </el-button>
      </div>
    </div>

    <div class="card">
      <el-table :data="records" v-loading="loading" style="width:100%">
        <el-table-column :label="$t('dataTransfer.colType')" width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="typeMap[row.type]?.type || 'info'" size="small">
              {{ typeMap[row.type]?.label || row.type }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" :label="$t('dataTransfer.colTime')" width="180" />
        <el-table-column :label="$t('dataTransfer.colSize')" width="110" align="right">
          <template #default="{ row }">{{ formatBytes(row.sizeBytes) }}</template>
        </el-table-column>
        <el-table-column prop="summary" :label="$t('dataTransfer.colSummary')" min-width="260" show-overflow-tooltip />
        <el-table-column :label="$t('common.actions')" width="160" align="center">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openView(row)">{{ $t('dataTransfer.viewBtn') }}</el-button>
            <el-button link type="primary" size="small" @click="downloadRecord(row)">{{ $t('dataTransfer.downloadBtn') }}</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="$t('dataTransfer.emptyRecords')" :image-size="80" />
        </template>
      </el-table>
    </div>

    <!-- 导入对话框 -->
    <el-dialog v-model="importDialog" :title="$t('dataTransfer.importBtn')" width="560px">
      <p class="dlg-desc">{{ $t('dataTransfer.importDesc') }}</p>
      <div class="import-row">
        <input type="file" accept=".json,application/json" @change="onFile" />
      </div>
      <el-input v-model="importText" type="textarea" :rows="8" :placeholder="$t('dataTransfer.importPlaceholder')" />
      <div v-if="importResult" class="result">
        <b>{{ $t('dataTransfer.resultLabel') }}</b>
        {{ $t('dataTransfer.resultFavorites', { added: r('favoritesAdded'), skipped: r('favoritesSkipped') }) }} ·
        {{ $t('dataTransfer.resultDirectories', { added: r('directoriesAdded'), skipped: r('directoriesSkipped') }) }} ·
        {{ $t('dataTransfer.resultArticles', { added: r('articlesAdded'), skipped: r('articlesSkipped') }) }} ·
        {{ $t('dataTransfer.resultProgress', { added: r('progressAdded'), skipped: r('progressSkipped') }) }}
      </div>
      <template #footer>
        <el-button @click="importDialog = false">{{ $t('dataTransfer.closeBtn') }}</el-button>
        <el-button type="primary" :loading="importing" @click="doImport">{{ $t('dataTransfer.startImportBtn') }}</el-button>
      </template>
    </el-dialog>

    <!-- 查看对话框 -->
    <el-dialog v-model="viewDialog" :title="viewRecord ? $t('dataTransfer.viewTitleOfType', { type: typeMap[viewRecord.type]?.label || '' }) : $t('dataTransfer.viewTitle')" width="680px">
      <div v-if="viewRecord" class="view-meta">
        <span>{{ $t('dataTransfer.colTime') }}: {{ viewRecord.createTime }}</span>
        <span>{{ $t('dataTransfer.colSize') }}: {{ formatBytes(viewRecord.sizeBytes) }}</span>
      </div>
      <pre class="view-content">{{ viewRecord?.content || $t('dataTransfer.emptyContent') }}</pre>
      <template #footer>
        <el-button @click="viewDialog = false">{{ $t('dataTransfer.closeBtn') }}</el-button>
        <el-button type="primary" @click="downloadRecord(viewRecord)">{{ $t('dataTransfer.downloadBtn') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; }
.hero { margin-bottom: 18px; display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.hero h1 { margin: 0 0 4px; font-size: var(--fs-xl); font-weight: 600; color: var(--c-text-1); }
.hero p { margin: 0; color: var(--c-text-2); font-size: var(--fs-base); }
.hero-actions { display: flex; gap: 10px; flex-shrink: 0; }
.card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 18px; box-shadow: 0 2px 6px rgba(20,30,60,.04); }
.dlg-desc { margin: 0 0 12px; color: var(--c-text-2); font-size: var(--fs-sm); line-height: 1.7; }
.import-row { margin-bottom: 10px; }
.result { margin-top: 12px; padding: 10px 12px; background: var(--c-primary-bg); border-radius: var(--r-s); font-size: var(--fs-sm); color: var(--c-text-2); }
.result b { color: var(--c-primary); }
.view-meta { display: flex; gap: 18px; margin-bottom: 10px; color: var(--c-text-2); font-size: var(--fs-sm); }
.view-content { max-height: 420px; overflow: auto; background: var(--c-bg-3, #f7f8fa); border-radius: var(--r-s); padding: 12px 14px; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; margin: 0; }
</style>
