<script setup>
/**
 * 操作日志
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { pageLogs, cleanLogs } from '../api/log'

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
    await ElMessageBox.confirm('清理 30 天前的日志,确认?', '清理确认', { type: 'warning' })
    const before = new Date(Date.now() - 30 * 24 * 3600 * 1000).toISOString().slice(0, 19)
    const r = await cleanLogs(before)
    ElMessage.success('已清理 ' + r.data + ' 条')
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
      <div class="page-title">操作日志</div>
      <div class="page-subtitle">AOP 自动记录每一次写操作</div>
    </div>

    <el-form :inline="true" :model="search" class="search-form">
      <el-form-item label="模块">
        <el-select v-model="search.module" clearable placeholder="全部" style="width:140px">
          <el-option value="用户管理" />
          <el-option value="角色管理" />
          <el-option value="数据字典" />
          <el-option value="认证" />
        </el-select>
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="search.username" placeholder="模糊匹配" clearable style="width:160px" @keyup.enter="loadData" />
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="search.timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始"
          end-placeholder="结束"
          style="width:380px"
        />
      </el-form-item>
      <el-button type="primary" @click="loadData">查询</el-button>
      <el-button @click="resetSearch">重置</el-button>
    </el-form>

    <div class="toolbar">
      <el-button type="danger" plain @click="onClean"><el-icon><Delete /></el-icon>清理 30 天前</el-button>
      <span class="record-count">共 {{ total }} 条</span>
    </div>

    <el-table :data="tableData" v-loading="loading" border stripe>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ row.createTime?.slice(0, 19) }}</template>
      </el-table-column>
      <el-table-column prop="username" label="用户" width="100" />
      <el-table-column prop="module" label="模块" width="100" />
      <el-table-column prop="action" label="操作" width="80" />
      <el-table-column prop="requestMethod" label="方法" width="80" />
      <el-table-column prop="requestUrl" label="URL" min-width="180" />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column prop="costMs" label="耗时(ms)" width="90" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">
            {{ row.status === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button size="small" link @click="showDetail(row)">详情</el-button>
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
    <el-drawer v-model="detailVisible" title="日志详情" size="500px">
      <div v-if="currentRow" class="detail">
        <p><b>用户:</b> {{ currentRow.username }} (id={{ currentRow.userId }})</p>
        <p><b>模块/操作:</b> {{ currentRow.module }} / {{ currentRow.action }}</p>
        <p><b>请求方法:</b> {{ currentRow.requestMethod }} {{ currentRow.requestUrl }}</p>
        <p><b>IP:</b> {{ currentRow.ip }}</p>
        <p><b>耗时:</b> {{ currentRow.costMs }} ms</p>
        <p><b>时间:</b> {{ currentRow.createTime }}</p>
        <p><b>请求参数:</b></p>
        <pre class="json-block">{{ currentRow.requestParams }}</pre>
        <p v-if="currentRow.status === 0"><b style="color:#f56c6c">错误信息:</b></p>
        <pre v-if="currentRow.status === 0" class="json-block err">{{ currentRow.errorMsg }}</pre>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.search-form { margin-bottom: 16px; padding: 16px; background: #f5f7fa; border-radius: var(--r-s); }
.toolbar { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; }
.record-count { color: #6b7280; font-size: 13px; }
.detail p { margin: 8px 0; color: #606266; line-height: 1.6; }
.json-block {
  background: #f5f7fa; padding: 12px; border-radius: var(--r-s);
  font-size: 12px; max-height: 280px; overflow: auto; white-space: pre-wrap; word-break: break-all;
}
.json-block.err { background: #fef0f0; color: #f56c6c; }
</style>