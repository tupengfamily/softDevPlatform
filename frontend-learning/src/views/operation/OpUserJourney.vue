<script setup>
/**
 * 用户旅程
 *
 * <p>按 用户 ID + 时间范围 查询该用户访问路径时间线
 * <p>左侧: 搜索表单 + 活跃用户排行(可点)
 * <p>右侧: 时间线展示(按 sessionId 分段)
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { getActiveUsers, getUserJourney } from '../../api/operation'
import { fmtMs, fmtNum } from '../../utils/format'

const loading = ref(false)
let timer = null

// 搜索条件
const queryUserId = ref(null)
const queryDateRange = ref([])  // [from, to]

// 活跃用户
const activeUsers = ref([])

// 当前查看的用户旅程数据
const journey = ref({ items: [], from: '', to: '', total: 0 })
const journeyLoading = ref(false)

async function reloadActiveUsers() {
  try { activeUsers.value = (await getActiveUsers({ limit: 30 })).data || [] }
  catch (e) {}
}
async function reload() {
  loading.value = true
  await reloadActiveUsers()
  loading.value = false
}
onMounted(() => { reload(); timer = setInterval(reloadActiveUsers, 5 * 60 * 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

async function searchUserJourney(userId) {
  queryUserId.value = userId
  await loadJourney()
}

async function loadJourney() {
  if (!queryUserId.value) return
  journeyLoading.value = true
  const params = {}
  if (queryDateRange.value && queryDateRange.value.length === 2) {
    const [f, t] = queryDateRange.value
    if (f) params.from = f
    if (t) params.to = t
  }
  try { journey.value = (await getUserJourney(queryUserId.value, params)).data || {} }
  catch (e) {} finally { journeyLoading.value = false }
}

// 时间线按 session 分组
const journeyBySession = computed(() => {
  const groups = []
  let current = null
  for (const v of (journey.value.items || [])) {
    if (!current || current.sessionId !== v.sessionId) {
      current = { sessionId: v.sessionId, startAt: v.enterAt, items: [] }
      groups.push(current)
    }
    current.items.push(v)
  }
  return groups
})

const fmtTs = (ts) => ts ? new Date(ts).toLocaleString('zh-CN') : '-'
const today = () => new Date().toISOString().slice(0, 10)
const daysAgo = (n) => { const d = new Date(); d.setDate(d.getDate() - n); return d.toISOString().slice(0, 10) }
const setQuickRange = (days) => { queryDateRange.value = [daysAgo(days), today()] }
</script>

<template>
  <div>
    <section class="panel">
      <header class="panel-h">查询条件</header>
      <el-form inline class="search-form">
        <el-form-item label="用户 ID">
          <el-input v-model.number="queryUserId" type="number" placeholder="输入 userId" clearable style="width:180px" @keyup.enter="loadJourney" />
        </el-form-item>
        <el-form-item label="时间范围">
          <el-date-picker v-model="queryDateRange" type="daterange" range-separator="至" start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD" style="width:300px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="journeyLoading" @click="loadJourney">查询</el-button>
          <el-button text @click="setQuickRange(7)">最近 7 天</el-button>
          <el-button text @click="setQuickRange(30)">最近 30 天</el-button>
        </el-form-item>
      </el-form>
    </section>

    <div class="grid">
      <!-- 活跃用户(点击行直接查该用户) -->
      <section class="panel">
        <header class="panel-h">活跃用户 <small>最近 7 天 · Top {{ activeUsers.length }}</small></header>
        <el-table :data="activeUsers" stripe size="small" max-height="600" empty-text="暂无数据" style="cursor:pointer" @row-click="searchUserJourney">
          <el-table-column label="用户" width="90">
            <template #default="{ row }"><el-tag size="small">#{{ row.userId }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="pv" label="PV" width="80" sortable />
          <el-table-column prop="uv" label="UV" width="100" />
          <el-table-column label="平均停留" width="120">
            <template #default="{ row }">{{ fmtMs(row.avgDurationMs) }}</template>
          </el-table-column>
          <el-table-column label="最后活跃" min-width="160">
            <template #default="{ row }">{{ fmtTs(row.lastSeen) }}</template>
          </el-table-column>
        </el-table>
      </section>

      <!-- 单用户旅程时间线 -->
      <section class="panel">
        <header class="panel-h">
          用户 #{{ queryUserId || '?' }} 访问轨迹
          <small v-if="journey.total">{{ journey.total }} 条 · {{ journey.from || '不限' }} 至 {{ journey.to || '不限' }}</small>
        </header>
        <div v-loading="journeyLoading">
          <div v-if="!queryUserId" class="empty">请输入用户 ID 或点击左侧列表</div>
          <div v-else-if="journeyBySession.length === 0" class="empty">该用户在此时间范围内暂无访问</div>
          <div v-for="(g, gi) in journeyBySession" :key="g.sessionId" class="uj-session">
            <div class="uj-session-h">
              <span class="uj-sid">会话 {{ gi + 1 }} · {{ g.sessionId.slice(0, 16) }}</span>
              <span class="uj-time">开始: {{ fmtTs(g.startAt) }} · 共 {{ g.items.length }} 页</span>
            </div>
            <div class="uj-timeline">
              <div v-for="(item, ii) in g.items" :key="ii" class="uj-step">
                <div class="uj-dot" :class="{ last: ii === g.items.length - 1 }"></div>
                <div class="uj-content">
                  <div class="uj-row">
                    <span class="uj-route">{{ item.route || item.url }}</span>
                    <el-tag size="small" type="info">{{ item.pageTitle || '-' }}</el-tag>
                    <span class="uj-dur" v-if="item.durationMs">{{ fmtMs(item.durationMs) }}</span>
                  </div>
                  <div class="uj-meta">
                    <span v-if="item.os">{{ item.os }} · {{ item.browser }}</span>
                    <span v-if="item.ip"> · IP: {{ item.ip }}</span>
                    <span> · {{ fmtTs(item.enterAt) }} 进入</span>
                  </div>
                  <div v-if="item.url" class="uj-url">{{ item.url }}</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; margin-bottom: 16px; }
.panel-h { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.panel-h small { font-weight: normal; color: var(--c-text-3); margin-left: 8px; font-size: 12px; }
.search-form { display: flex; flex-wrap: wrap; gap: 4px; }
.empty { text-align: center; color: var(--c-text-3); padding: 24px 0; font-size: 13px; }

.grid { display: grid; grid-template-columns: 1fr 1.4fr; gap: 16px; }

.uj-session { margin-bottom: 16px; padding: 12px 16px; background: #f8fafc; border-radius: var(--r-s); }
.uj-session-h { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; padding-bottom: 8px; border-bottom: 1px dashed #cbd5e1; }
.uj-sid { font-weight: 600; color: var(--c-primary); font-size: 12px; }
.uj-time { font-size: 11px; color: var(--c-text-3); }
.uj-timeline { display: flex; flex-direction: column; }
.uj-step { display: flex; gap: 12px; padding: 6px 0; }
.uj-dot { width: 10px; height: 10px; border-radius: 50%; background: #3b82f6; margin-top: 6px; flex: none; position: relative; }
.uj-dot::after { content: ''; position: absolute; top: 18px; left: 4px; width: 2px; height: calc(100% + 12px); background: #e2e8f0; }
.uj-dot.last::after { display: none; }
.uj-content { flex: 1; min-width: 0; }
.uj-row { display: flex; align-items: center; gap: 8px; }
.uj-route { font-weight: 600; color: var(--c-text-1); }
.uj-dur { color: var(--c-text-3); font-size: 11px; }
.uj-meta { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.uj-url { font-size: 11px; color: #94a3b8; margin-top: 2px; word-break: break-all; }

@media (max-width: 1100px) { .grid { grid-template-columns: 1fr; } }
</style>