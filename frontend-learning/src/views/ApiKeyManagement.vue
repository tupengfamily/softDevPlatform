<script setup>
/**
 * API 密钥管理（管理员专属）
 *
 * <p>系统后续接入的各类第三方服务凭据（地图、大模型、支付、短信、对象存储……）
 * 统一在这里维护，不再散落到 application.yml 或部署环境变量里。
 *
 * <p><b>安全设计</b>：
 * <ul>
 *   <li>密钥在<b>后端</b>用 AES-256-GCM 加密后落库，数据库里没有明文；</li>
 *   <li>本页面<b>永远拿不到密钥明文</b>，只显示掩码（如 abcd****wxyz）——
 *       即使有人截屏或导出表格也拿不到可用凭据；</li>
 *   <li>修改时若不填新密钥，则<b>保留原值</b>（不会因为「只改备注」而清空）；</li>
 *   <li>所有写操作进操作日志，日志里的密钥字段已被后端脱敏成 ***。</li>
 * </ul>
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listApiKeys, createApiKey, updateApiKey, deleteApiKey, testApiKey
} from '../api/apiKey'

const { t } = useI18n()

// ========== 数据 ==========
const rows = ref([])
const loading = ref(false)
const keyword = ref('')

/** 探活结果：id → { status, message } */
const probeResult = ref({})

const filtered = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return rows.value
  return rows.value.filter(r =>
    (r.keyCode || '').toLowerCase().includes(k) ||
    (r.displayName || '').toLowerCase().includes(k) ||
    (r.provider || '').toLowerCase().includes(k))
})

const loadData = async () => {
  loading.value = true
  try {
    const res = await listApiKeys()
    rows.value = res.data || []
  } catch (_) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

onMounted(loadData)

// ========== 新增 / 编辑对话框 ==========
const dialogVisible = ref(false)
const dialogMode = ref('create')
const formRef = ref(null)
const submitting = ref(false)

const form = reactive({
  id: null,
  keyCode: '',
  displayName: '',
  provider: '',
  secret: '',
  secretHint: '',
  enabled: true
})

const rules = computed(() => ({
  keyCode: [
    { required: true, message: t('apiKeyManagement.rule.keyCodeRequired'), trigger: 'blur' },
    {
      pattern: /^[A-Za-z0-9._-]{2,100}$/,
      message: t('apiKeyManagement.rule.keyCodePattern'),
      trigger: 'blur'
    }
  ],
  displayName: [{ required: true, message: t('apiKeyManagement.rule.nameRequired'), trigger: 'blur' }],
  secret: [{
    validator: (_rule, value, callback) => {
      // 新增必须给密钥；编辑时留空表示保留原值
      if (dialogMode.value === 'create' && !value) {
        return callback(new Error(t('apiKeyManagement.rule.secretRequired')))
      }
      callback()
    },
    trigger: 'blur'
  }]
}))

const openCreate = () => {
  Object.assign(form, {
    id: null, keyCode: '', displayName: '', provider: '',
    secret: '', secretHint: '', enabled: true
  })
  dialogMode.value = 'create'
  dialogVisible.value = true
}

const openEdit = (row) => {
  Object.assign(form, {
    id: row.id,
    keyCode: row.keyCode,
    displayName: row.displayName || '',
    provider: row.provider || '',
    secret: '',          // 留空 = 保留原密钥
    secretHint: row.secretHint || '',
    enabled: !!row.enabled
  })
  dialogMode.value = 'edit'
  dialogVisible.value = true
}

const submit = async () => {
  const ok = await formRef.value?.validate().catch(() => false)
  if (!ok) return
  submitting.value = true
  try {
    const payload = {
      keyCode: form.keyCode.trim(),
      displayName: form.displayName.trim(),
      provider: form.provider.trim(),
      secret: form.secret,           // 空串 = 不改密钥本体
      secretHint: form.secretHint.trim(),
      enabled: form.enabled
    }
    if (dialogMode.value === 'create') {
      await createApiKey(payload)
    } else {
      await updateApiKey(form.id, payload)
    }
    ElMessage.success(t('common.saved'))
    dialogVisible.value = false
    await loadData()
  } catch (_) {
    // 错误提示已由响应拦截器统一弹出
  } finally {
    submitting.value = false
  }
}

// ========== 启停 ==========
const toggleEnabled = async (row) => {
  const next = !row.enabled
  try {
    await updateApiKey(row.id, {
      displayName: row.displayName,
      provider: row.provider,
      secretHint: row.secretHint,
      enabled: next
    })
    ElMessage.success(t('common.saved'))
    await loadData()
  } catch (_) {}
}

// ========== 删除 ==========
const remove = async (row) => {
  try {
    await ElMessageBox.confirm(
      t('apiKeyManagement.deleteConfirm', { name: row.displayName || row.keyCode }),
      t('apiKeyManagement.deleteTitle'),
      { type: 'warning', confirmButtonText: t('common.confirm'), cancelButtonText: t('common.cancel') }
    )
  } catch (_) { return }
  try {
    await deleteApiKey(row.id)
    ElMessage.success(t('common.success'))
    await loadData()
  } catch (_) {}
}

// ========== 探活 ==========
const probing = ref({})
const doTest = async (row) => {
  probing.value = { ...probing.value, [row.id]: true }
  try {
    const res = await testApiKey(row.id)
    const r = res.data || {}
    probeResult.value = { ...probeResult.value, [row.id]: r }
    if (r.status === 'OK') {
      ElMessage.success(t('apiKeyManagement.testOk'))
    } else if (r.status === 'UNKNOWN') {
      ElMessage.info(r.message || t('apiKeyManagement.testUnknown'))
    } else {
      ElMessage.warning(r.message || t('apiKeyManagement.testFail'))
    }
  } catch (_) {
    probeResult.value = { ...probeResult.value, [row.id]: { status: 'ERROR', message: t('apiKeyManagement.testFail') } }
  } finally {
    probing.value = { ...probing.value, [row.id]: false }
  }
}

// ========== 展示辅助 ==========
const formatTime = (v) => {
  if (!v) return '-'
  return String(v).replace('T', ' ').slice(0, 19)
}

const statusTagType = (row) => {
  if (!row.hasSecret) return 'info'
  return row.enabled ? 'success' : 'warning'
}

const statusText = (row) => {
  if (!row.hasSecret) return t('apiKeyManagement.status.noSecret')
  return row.enabled ? t('apiKeyManagement.status.enabled') : t('apiKeyManagement.status.disabled')
}
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('apiKeyManagement.title') }}</div>
      <div class="page-subtitle">{{ $t('apiKeyManagement.subtitle') }}</div>
    </div>

    <!-- 工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>{{ $t('apiKeyManagement.action.create') }}
        </el-button>
        <el-button @click="loadData">
          <el-icon><Refresh /></el-icon>{{ $t('common.search') }}
        </el-button>
      </div>
      <div class="toolbar-right">
        <el-input
          v-model="keyword"
          :placeholder="$t('apiKeyManagement.searchPlaceholder')"
          clearable
          style="width: 220px"
          :aria-label="$t('apiKeyManagement.searchPlaceholder')"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>
    </div>

    <!-- 表格 -->
    <el-table :data="filtered" v-loading="loading" border stripe>
      <el-table-column :label="$t('apiKeyManagement.field.name')" min-width="170">
        <template #default="{ row }">
          <div class="name-cell">
            <strong>{{ row.displayName || row.keyCode }}</strong>
            <code class="key-code">{{ row.keyCode }}</code>
          </div>
        </template>
      </el-table-column>

      <el-table-column :label="$t('apiKeyManagement.field.provider')" prop="provider" min-width="100">
        <template #default="{ row }">
          <el-tag v-if="row.provider" size="small" type="info">{{ row.provider }}</el-tag>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>

      <el-table-column :label="$t('apiKeyManagement.field.secret')" min-width="180">
        <template #default="{ row }">
          <code v-if="row.secretMask" class="mask">{{ row.secretMask }}</code>
          <span v-else class="muted">{{ $t('apiKeyManagement.status.noSecret') }}</span>
          <div v-if="row.secretHint" class="hint-line">{{ row.secretHint }}</div>
        </template>
      </el-table-column>

      <el-table-column :label="$t('apiKeyManagement.field.status')" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row)" size="small">{{ statusText(row) }}</el-tag>
        </template>
      </el-table-column>

      <el-table-column :label="$t('apiKeyManagement.field.lastUsed')" width="160">
        <template #default="{ row }">
          <span class="muted">{{ formatTime(row.lastUsedTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column :label="$t('apiKeyManagement.field.updateTime')" width="160">
        <template #default="{ row }">
          <span class="muted">{{ formatTime(row.updateTime) }}</span>
        </template>
      </el-table-column>

      <el-table-column :label="$t('common.actions')" width="230" align="right" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" size="small" @click="openEdit(row)">
            {{ $t('apiKeyManagement.action.edit') }}
          </el-button>
          <el-button link type="info" size="small" :loading="!!probing[row.id]" @click="doTest(row)">
            {{ $t('apiKeyManagement.action.test') }}
          </el-button>
          <el-button link type="warning" size="small" @click="toggleEnabled(row)">
            {{ row.enabled ? $t('apiKeyManagement.action.disable') : $t('apiKeyManagement.action.enable') }}
          </el-button>
          <el-button link type="danger" size="small" @click="remove(row)">
            {{ $t('apiKeyManagement.action.delete') }}
          </el-button>
        </template>
      </el-table-column>

      <template #empty>
        <div class="empty-box">
          <el-icon class="empty-icon"><Key /></el-icon>
          <p>{{ $t('apiKeyManagement.empty') }}</p>
          <p class="muted">{{ $t('apiKeyManagement.emptyTip') }}</p>
        </div>
      </template>
    </el-table>

    <!-- 探活结果 -->
    <div v-if="Object.keys(probeResult).length" class="probe-box">
      <h4>{{ $t('apiKeyManagement.probeTitle') }}</h4>
      <ul>
        <li v-for="(v, k) in probeResult" :key="k">
          <code>{{ k }}</code>
          <el-tag size="small" :type="v.status === 'OK' ? 'success' : 'warning'">{{ v.status }}</el-tag>
          <span class="muted">{{ v.message }}</span>
        </li>
      </ul>
    </div>

    <!-- 新增 / 编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogMode === 'create' ? $t('apiKeyManagement.dialog.createTitle') : $t('apiKeyManagement.dialog.editTitle')"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="110px">
        <el-form-item :label="$t('apiKeyManagement.field.keyCode')" prop="keyCode">
          <el-input
            v-model="form.keyCode"
            :disabled="dialogMode === 'edit'"
            :placeholder="$t('apiKeyManagement.dialog.keyCodePlaceholder')"
          />
          <div class="form-tip">{{ $t('apiKeyManagement.dialog.keyCodeTip') }}</div>
        </el-form-item>

        <el-form-item :label="$t('apiKeyManagement.field.name')" prop="displayName">
          <el-input v-model="form.displayName" :placeholder="$t('apiKeyManagement.dialog.namePlaceholder')" />
        </el-form-item>

        <el-form-item :label="$t('apiKeyManagement.field.provider')">
          <el-input v-model="form.provider" :placeholder="$t('apiKeyManagement.dialog.providerPlaceholder')" />
        </el-form-item>

        <el-form-item :label="$t('apiKeyManagement.field.secret')" prop="secret">
          <el-input
            v-model="form.secret"
            type="password"
            show-password
            :placeholder="dialogMode === 'edit'
              ? $t('apiKeyManagement.dialog.secretKeepPlaceholder')
              : $t('apiKeyManagement.dialog.secretPlaceholder')"
          />
          <div class="form-tip">{{ $t('apiKeyManagement.dialog.secretTip') }}</div>
        </el-form-item>

        <el-form-item :label="$t('apiKeyManagement.field.hint')">
          <el-input v-model="form.secretHint" :placeholder="$t('apiKeyManagement.dialog.hintPlaceholder')" />
        </el-form-item>

        <el-form-item :label="$t('apiKeyManagement.field.enabled')">
          <el-switch v-model="form.enabled" />
          <div class="form-tip">{{ $t('apiKeyManagement.dialog.enabledTip') }}</div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">{{ $t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.name-cell { display: flex; flex-direction: column; gap: 2px; }
.key-code {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  background: var(--c-bg-3, #f3f4f8);
  padding: 1px 5px;
  border-radius: 3px;
  color: var(--c-text-3);
  align-self: flex-start;
}
.mask {
  font-family: 'SF Mono', Menlo, Consolas, monospace;
  font-size: 12.5px;
  letter-spacing: 0.5px;
  color: var(--c-text-2);
  background: var(--c-bg-3, #f3f4f8);
  padding: 2px 7px;
  border-radius: 3px;
}
.hint-line { margin-top: 4px; font-size: 12px; color: var(--c-text-4); }
.muted { color: var(--c-text-4); font-size: 12px; }
.form-tip { font-size: 12px; color: var(--c-text-4); line-height: 1.5; margin-top: 2px; }

.empty-box { padding: 28px 0; color: var(--c-text-3); }
.empty-icon { font-size: 34px; color: var(--c-text-4); }
.empty-box p { margin: 6px 0 0; font-size: 13px; }

.probe-box {
  margin-top: 14px;
  padding: 12px 14px;
  background: var(--c-bg-2, #f8f9fb);
  border: 1px solid var(--c-border);
  border-radius: var(--r-m);
}
.probe-box h4 { margin: 0 0 8px; font-size: 13px; color: var(--c-text-2); font-weight: 600; }
.probe-box ul { margin: 0; padding-left: 18px; }
.probe-box li { margin: 4px 0; font-size: 12.5px; display: flex; align-items: center; gap: 8px; }
.probe-box code { font-family: 'SF Mono', Menlo, Consolas, monospace; color: var(--c-text-2); }

/* 移动端: 操作列按钮换行, 表格允许横向滚动 */
@media (max-width: 768px) {
  .toolbar { flex-direction: column; align-items: stretch; gap: 10px; }
  .toolbar-right :deep(.el-input) { width: 100% !important; }
}
</style>
