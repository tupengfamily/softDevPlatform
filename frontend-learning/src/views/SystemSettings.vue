<script setup>
/**
 * 系统设置(管理员专属)
 * <p>
 * 当前可调项:
 * <ul>
 *   <li>myDoc.maxQuotaBytes — 个人文档空间上限(默认 500MB, 范围 50MB~5GB)</li>
 *   <li>myDoc.maxFileBytes — 单文件上限(默认 20MB)</li>
 *   <li>myDoc.maxFileCount — 单用户文件数上限(默认 300)</li>
 * </ul>
 * 调整后, 所有用户的下一次操作即生效(后端每请求读一次 settings)。
 */
import { ref, onMounted, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listAllSettings, updateSetting } from '../api/myDoc'

const { t } = useI18n()

const settings = ref([])
const loading = ref(false)

const quotaRows = computed(() => settings.value.filter(s => s.key && s.key.startsWith('myDoc.')))

const humanBytes = (n) => {
  const v = Number(n || 0)
  if (v < 1024) return v + ' B'
  if (v < 1024 * 1024) return (v / 1024).toFixed(1) + ' KB'
  if (v < 1024 * 1024 * 1024) return (v / 1024 / 1024).toFixed(1) + ' MB'
  return (v / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

const load = async () => {
  loading.value = true
  try {
    const r = await listAllSettings()
    settings.value = r.data || []
  } catch (_) { settings.value = [] } finally { loading.value = false }
}

const openEdit = async (row) => {
  let promptText, validator, suffix
  if (row.key === 'myDoc.maxQuotaBytes') {
    promptText = t('systemSettings.editQuotaPrompt', { current: humanBytes(row.value) })
    validator = (v) => {
      const n = Number(v)
      if (!Number.isFinite(n) || n < 50 * 1024 * 1024 || n > 5 * 1024 * 1024 * 1024) {
        return t('systemSettings.quotaRange')
      }
      return true
    }
    suffix = t('systemSettings.unitBytes')
  } else if (row.key === 'myDoc.maxFileBytes') {
    promptText = t('systemSettings.editFileBytesPrompt', { current: humanBytes(row.value) })
    validator = (v) => {
      const n = Number(v)
      if (!Number.isFinite(n) || n < 1024 * 1024 || n > 100 * 1024 * 1024) {
        return t('systemSettings.fileBytesRange')
      }
      return true
    }
    suffix = t('systemSettings.unitBytes')
  } else if (row.key === 'myDoc.maxFileCount') {
    promptText = t('systemSettings.editFileCountPrompt', { current: row.value })
    validator = (v) => {
      const n = Number(v)
      if (!Number.isFinite(n) || n < 1 || n > 5000) return t('systemSettings.fileCountRange')
      return true
    }
    suffix = t('systemSettings.unitCount')
  } else {
    promptText = t('systemSettings.editGenericPrompt', { key: row.key, current: row.value })
    validator = () => true
    suffix = ''
  }
  try {
    const { value } = await ElMessageBox.prompt(promptText, t('systemSettings.editTitle'), {
      confirmButtonText: t('systemSettings.save'),
      cancelButtonText: t('common.cancel'),
      inputValue: String(row.value),
      inputValidator: validator,
      inputErrorMessage: t('systemSettings.invalid')
    })
    await updateSetting(row.key, String(value))
    ElMessage.success(t('common.saved'))
    await load()
  } catch (_) { /* 用户取消 */ }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="hero">
      <div>
        <h1>{{ $t('systemSettings.title') }}</h1>
        <p>{{ $t('systemSettings.subtitle') }}</p>
      </div>
    </div>

    <div v-loading="loading" class="card">
      <el-table :data="quotaRows" stripe>
        <el-table-column :label="$t('systemSettings.colKey')" prop="key" min-width="220">
          <template #default="{ row }">
            <code class="key-code">{{ row.key }}</code>
          </template>
        </el-table-column>
        <el-table-column :label="$t('systemSettings.colValue')" min-width="200">
          <template #default="{ row }">
            <strong v-if="row.key === 'myDoc.maxQuotaBytes' || row.key === 'myDoc.maxFileBytes'">{{ humanBytes(row.value) }}</strong>
            <strong v-else>{{ row.value }}</strong>
            <span v-if="row.key === 'myDoc.maxQuotaBytes'" class="muted">({{ Number(row.value) / 1024 / 1024 }} MB)</span>
            <span v-if="row.key === 'myDoc.maxFileBytes'" class="muted">({{ Number(row.value) / 1024 / 1024 }} MB)</span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('systemSettings.colDescription')" prop="description" min-width="380" />
        <el-table-column :label="$t('common.actions')" width="120" align="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">{{ $t('systemSettings.actionEdit') }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-alert class="hint" type="info" :closable="false">
        <p>{{ $t('systemSettings.tip1') }}</p>
        <p>{{ $t('systemSettings.tip2') }}</p>
      </el-alert>
    </div>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px; }
.hero { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.hero h1 { margin: 0 0 4px; font-size: var(--fs-xl); font-weight: 600; color: var(--c-text-1); }
.hero p { margin: 0; color: var(--c-text-2); font-size: var(--fs-base); }
.card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 18px; box-shadow: 0 2px 6px rgba(20,30,60,.04); }
.key-code { font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 12px; background: var(--c-bg-3, #f3f4f8); padding: 2px 6px; border-radius: 3px; color: var(--c-text-2); }
.muted { color: var(--c-text-4); font-size: 12px; margin-left: 6px; }
.hint { margin-top: 14px; }
.hint p { margin: 4px 0; font-size: 13px; color: var(--c-text-2); }
</style>
