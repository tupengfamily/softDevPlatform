<script setup>
/**
 * 操作日志
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { pageLogs, cleanLogs } from '../api/log'

const { t } = useI18n()

const tableData = ref([])
const total = ref(0)
const loading = ref(false)
const page = ref(1)
const size = ref(20)

const search = reactive({
  module: '',
  username: '',
  timeRange: []
})

const loadData = async () => {
  loading.value = true
  try {
    const params = {
      module: search.module || null,
      username: search.username || null,
      startTime: search.timeRange?.[0] || null,
      endTime: search.timeRange?.[1] || null,
      page: page.value,
      size: size.value
    }
    const r = await pageLogs(params)
    tableData.value = r.data.records || []
    total.value = r.data.total || 0
  } catch (_) { tableData.value = []; total.value = 0 } finally { loading.value = false }
}

const resetSearch = () => {
  search.module = ''
  search.username = ''
  search.timeRange = []
  page.value = 1
  loadData()
}

onMounted(loadData)

const onClean = async () => {
  try {
    await ElMessageBox.confirm(t('operationLog.msg.cleanConfirm'), t('operationLog.msg.cleanTitle'), { type: 'warning' })
    const before = new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString().slice(0, 19)
    const r = await cleanLogs(before)
    ElMessage.success(t('operationLog.msg.cleaned', { n: r.data }))
    loadData()
  } catch (_) {}
}

// 详情
const detailVisible = ref(false)
const currentRow = ref(null)
const showDetail = (row) => { currentRow.value = row; detailVisible.value = true }
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('operationLog.title') }}</div>
      <div class="page-subtitle">{{ $t('operationLog.subtitle') }}</div>
    </div>

    <el-form :inline="true" :model="search" class="search-form">
      <el-form-item :label="$t('operationLog.field.module')">
        <el-select v-model="search.module" clearable :placeholder="$t('common.all')" style="width:120px">
          <el-option value="用户管理" :label="$t('operationLog.module.userManagement')" />
          <el-option value="角色管理" :label="$t('operationLog.module.roleManagement')" />
          <el-option value="数据字典" :label="$t('operationLog.module.dict')" />
          <el-option value="认证" :label="$t('operationLog.module.auth')" />
        </el-select>
      </el-form-item>
      <el-form-item :label="$t('operationLog.field.username')">
        <el-input v-model="search.username" :placeholder="$t('operationLog.search.usernamePlaceholder')" clearable style="width:130px" @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item :label="$t('operationLog.field.timeRange')">
        <el-date-picker
          v-model="search.timeRange"
          type="datetimerange"
          :range-separator="$t('operationLog.search.rangeSeparator')"
          :start-placeholder="$t('operationLog.search.start')"
          :end-placeholder="$t('operationLog.search.end')"
          style="width:330px"
        />
      </el-form-item>
      <el-form-item class="search-btns">
        <el-button type="primary" @click="loadData">{{ $t('operationLog.action.query') }}</el-button>
        <el-button @click="resetSearch">{{ $t('common.reset') }}</el-button>
      </el-form-item>
    </el-form>

    <div class="toolbar">
      <el-button type="danger" plain @click="onClean"><el-icon><Delete /></el-icon>{{ $t('operationLog.action.clean') }}</el-button>
      <span class="record-count">{{ $t('operationLog.total', { n: total }) }}</span>
    </div>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column :label="$t('operationLog.field.time')" width="170">
        <template #default="{ row }">{{ row.createTime?.slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column prop="username" :label="$t('operationLog.field.user')" width="100" />
      <el-table-column prop="module" :label="$t('operationLog.field.module')" width="100" />
      <el-table-column prop="action" :label="$t('operationLog.field.action')" width="80" />
      <el-table-column prop="requestMethod" :label="$t('operationLog.field.method')" width="80" />
      <el-table-column prop="requestUrl" label="URL" min-width="180" />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column prop="costMs" :label="$t('operationLog.field.costMs')" width="90" />
      <el-table-column :label="$t('operationLog.field.status')" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? $t('operationLog.status.success') : $t('operationLog.status.failed') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="$t('common.actions')" width="80">
        <template #default="{ row }">
          <el-button size="small" link @click="showDetail(row)">{{ $t('operationLog.action.detail') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10,20,50,100]"
      layout="total, sizes, prev, pager, next"
      style="margin-top:16px; justify-content: flex-end;"
      @current-change="loadData"
      @size-change="loadData"
    />

    <!-- 详情抽屉 -->
    <el-drawer v-model="detailVisible" :title="$t('operationLog.detail.title')" size="500px">
      <div v-if="currentRow" class="detail">
        <p><b>{{ $t('operationLog.field.user') }}:</b> {{ currentRow.username }} (id={{ currentRow.userId }})</p>
        <p><b>{{ $t('operationLog.detail.moduleAction') }}:</b> {{ currentRow.module }} / {{ currentRow.action }}</p>
        <p><b>{{ $t('operationLog.detail.requestMethod') }}:</b> {{ currentRow.requestMethod }} {{ currentRow.requestUrl }}</p>
        <p><b>IP:</b> {{ currentRow.ip }}</p>
        <p><b>{{ $t('operationLog.detail.cost') }}:</b> {{ currentRow.costMs }} ms</p>
        <p><b>{{ $t('operationLog.field.time') }}:</b> {{ currentRow.createTime }}</p>
        <p><b>{{ $t('operationLog.detail.requestParams') }}:</b></p>
        <pre class="json-block">{{ currentRow.requestParams }}</pre>
        <p v-if="currentRow.status === 0"><b style="color:#f56c6c">{{ $t('operationLog.detail.errorMsg') }}:</b></p>
        <pre v-if="currentRow.status === 0" class="json-block err">{{ currentRow.errorMsg }}</pre>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
/* 查询区: flex 布局保证表单控件与按钮水平居中对齐 */
.search-form {
  display:flex; flex-wrap:wrap; align-items:center;
  margin-bottom:16px; padding:16px 16px 6px; background:#f5f7fa; border-radius:var(--r-s);
}
.search-form :deep(.el-form-item) { margin-bottom:10px; margin-right:12px; vertical-align:middle }
.search-form :deep(.el-form-item__label) { align-items:center }
.search-btns { margin-right:0 }
.toolbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; }
.record-count { color: #6b7280; font-size: 13px; }
.detail p { margin: 8px 0; color: #606266; line-height: 1.6; }
.json-block {
  background: #f5f7fa; padding: 12px; border-radius: var(--r-s);
  font-size: 12px; max-height: 280px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
.json-block.err { background: #fef0f0; color: #f56c6c; }
</style>