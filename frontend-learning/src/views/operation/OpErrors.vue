<script setup>
/**
 * 错误日志
 *
 * <p>两个 tab:
 * <ul>
 *   <li>接口报错: t_operation_log status=0 的记录(AOP 切面抓的 @OperationLog 注解方法)</li>
 *   <li>代码运行报错: t_page_error(window.onerror + unhandledrejection)</li>
 * </ul>
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { getApiErrors, getErrors } from '../../api/operation'

const loading = ref(false)
let timer = null
const activeTab = ref('api')

const apiErrors = ref({ recent: [], moduleDistribution: [] })
const runtimeErrors = ref({ recent: [], typeDistribution: [] })

const apiParams = ref({ module: '', daterange: [], limit: 50 })
const rtParams = ref({ daterange: [], limit: 50 })

async function loadApiErrors() {
  loading.value = true
  try {
    const params = {}
    if (apiParams.value.module) params.module = apiParams.value.module
    if (apiParams.value.daterange && apiParams.value.daterange.length === 2) {
      params.from = apiParams.value.daterange[0]
      params.to = apiParams.value.daterange[1]
    }
    params.limit = apiParams.value.limit
    apiErrors.value = (await getApiErrors(params)).data || {}
  } catch (e) {} finally { loading.value = false }
}
async function loadRuntimeErrors() {
  loading.value = true
  try {
    const params = {}
    if (rtParams.value.daterange && rtParams.value.daterange.length === 2) {
      params.from = rtParams.value.daterange[0]
      params.to = rtParams.value.daterange[1]
    }
    params.limit = rtParams.value.limit
    runtimeErrors.value = (await getErrors(params)).data || {}
  } catch (e) {} finally { loading.value = false }
}

function reload() {
  if (activeTab.value === 'api') loadApiErrors()
  else loadRuntimeErrors()
}
onMounted(() => { reload(); timer = setInterval(reload, 5 * 60 * 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

const fmtTs = (ts) => ts ? new Date(ts).toLocaleString('zh-CN') : '-'

function distBars(dist) {
  const total = dist.reduce((s, x) => s + x.count, 0) || 1
  return dist.map(d => ({
    label: d.module || d.type, count: d.count,
    pct: Math.round(d.count * 1000 / total) / 10,
    color: d.type === 'UnhandledRejection' || d.module === 'AUTH' ? '#ef4444' : '#f59e0b'
  }))
}

const apiDist = ref([])
const rtDist = ref([])
const refreshDist = () => {
  apiDist.value = distBars(apiErrors.value.moduleDistribution || [])
  rtDist.value = distBars(runtimeErrors.value.typeDistribution || [])
}
watch([() => apiErrors.value, () => runtimeErrors.value, activeTab], refreshDist, { immediate: true, deep: true })
</script>

<template>
  <div>
    <section class="panel">
      <header class="panel-h">错误日志
        <small>两类: 接口报错(AOP 抓) + 代码运行报错(前端 window.onerror)</small>
      </header>
      <el-tabs v-model="activeTab" @tab-change="reload">
        <!-- 接口报错 -->
        <el-tab-pane label="接口报错" name="api">
          <el-form inline class="search-form">
            <el-form-item label="模块">
              <el-input v-model="apiParams.module" placeholder="如 USER / WIKI" clearable style="width:160px" @keyup.enter="loadApiErrors" />
            </el-form-item>
            <el-form-item label="时间">
              <el-date-picker v-model="apiParams.daterange" type="daterange" value-format="YYYY-MM-DD" style="width:280px" @change="loadApiErrors" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadApiErrors">查询</el-button>
            </el-form-item>
          </el-form>
          <div class="dist-block" v-if="apiDist.length">
            <div v-for="b in apiDist" :key="b.label" class="dist-bar">
              <div class="dist-bar-label">{{ b.label }}</div>
              <div class="dist-bar-bg"><div class="dist-bar-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
              <div class="dist-bar-val">{{ b.count }} ({{ b.pct }}%)</div>
            </div>
          </div>
          <el-table :data="apiErrors.recent || []" stripe size="small" max-height="500" empty-text="无错误 :)" :show-overflow-tooltip="true">
            <el-table-column label="模块" width="120" prop="module">
              <template #default="{ row }">
                <el-tag size="small" :type="row.module === 'AUTH' ? 'danger' : 'warning'">{{ row.module }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="140" prop="action" />
            <el-table-column label="HTTP" width="80" prop="requestMethod" />
            <el-table-column label="URL" min-width="240" prop="requestUrl" />
            <el-table-column label="用户" width="120">
              <template #default="{ row }">{{ row.username || '游客' }}<span v-if="row.userId" style="color:#94a3b8;margin-left:4px">#{{ row.userId }}</span></template>
            </el-table-column>
            <el-table-column label="错误" min-width="240" prop="errorMsg" show-overflow-tooltip />
            <el-table-column label="耗时" width="80">
              <template #default="{ row }">{{ row.costMs || 0 }}ms</template>
            </el-table-column>
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ fmtTs(row.createTime) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <!-- 代码运行报错 -->
        <el-tab-pane label="代码运行报错" name="runtime">
          <el-form inline class="search-form">
            <el-form-item label="时间">
              <el-date-picker v-model="rtParams.daterange" type="daterange" value-format="YYYY-MM-DD" style="width:280px" @change="loadRuntimeErrors" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" @click="loadRuntimeErrors">查询</el-button>
            </el-form-item>
          </el-form>
          <div class="dist-block" v-if="rtDist.length">
            <div v-for="b in rtDist" :key="b.label" class="dist-bar">
              <div class="dist-bar-label">{{ b.label }}</div>
              <div class="dist-bar-bg"><div class="dist-bar-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
              <div class="dist-bar-val">{{ b.count }} ({{ b.pct }}%)</div>
            </div>
          </div>
          <el-table :data="runtimeErrors.recent || []" stripe size="small" max-height="500" empty-text="无错误 :)">
            <el-table-column prop="type" label="类型" width="140">
              <template #default="{ row }">
                <el-tag :type="row.type === 'UnhandledRejection' ? 'danger' : 'warning'" size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="route" label="页面" width="160" />
            <el-table-column prop="message" label="消息" min-width="220" show-overflow-tooltip />
            <el-table-column prop="filename" label="文件" width="200" show-overflow-tooltip />
            <el-table-column label="时间" width="160">
              <template #default="{ row }">{{ fmtTs(row.occurAt) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<style scoped>
.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; }
.panel-h { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.panel-h small { font-weight: normal; color: var(--c-text-3); margin-left: 8px; font-size: 12px; }
.search-form { display: flex; flex-wrap: wrap; gap: 4px; margin-bottom: 12px; }
.dist-block { margin-bottom: 12px; padding: 10px 0; border-bottom: 1px dashed #cbd5e1; }
.dist-bar { display: grid; grid-template-columns: 110px 1fr 90px; align-items: center; gap: 6px; margin-bottom: 4px; font-size: 11px; }
.dist-bar-label { color: var(--c-text-2); }
.dist-bar-bg { background: #f1f5f9; height: 8px; border-radius: 4px; overflow: hidden; }
.dist-bar-fg { height: 100%; transition: width .3s; }
.dist-bar-val { color: var(--c-text-3); text-align: right; }
</style>