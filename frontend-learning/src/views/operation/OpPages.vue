<script setup>
/**
 * 页面清单
 *
 * <p>列出所有路由的 PV/UV/平均停留, 点击行弹 el-drawer 显示该页面详情:
 * 每日趋势 + 访问者 + 相关错误
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { getPages, getPageDetail } from '../../api/operation'
import { fmtMs, fmtNum } from '../../utils/format'

const loading = ref(false)
let timer = null
const pages = ref([])

const pageDrawerVisible = ref(false)
const pageDrawerLoading = ref(false)
const pageDrawerRoute = ref('')
const pageDrawerData = ref({ trend: [], visitors: [], errors: [] })

async function reload() {
  loading.value = true
  try { pages.value = (await getPages()).data || [] }
  catch (e) {} finally { loading.value = false }
}
onMounted(() => { reload(); timer = setInterval(reload, 5 * 60 * 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

async function openPageDetail(row) {
  pageDrawerRoute.value = row.route
  pageDrawerVisible.value = true
  pageDrawerLoading.value = true
  try { pageDrawerData.value = (await getPageDetail({ route: row.route })).data || {} }
  catch (e) {} finally { pageDrawerLoading.value = false }
}

const pageDrawerTrend = computed(() => {
  const days = pageDrawerData.value.trend || []
  if (days.length < 2) return null
  const maxPv = Math.max(...days.map(d => d.pv), 1)
  const w = 560, h = 130, padX = 24, padY = 16
  const stepX = (w - padX * 2) / (days.length - 1)
  const points = days.map((d, i) => ({
    x: padX + i * stepX,
    y: h - padY - ((d.pv / maxPv) * (h - padY * 2)),
    day: d.day.slice(5), pv: d.pv, uv: d.uv
  }))
  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
  return { points, path, w, h }
})
const fmtTs = (ts) => ts ? new Date(ts).toLocaleString('zh-CN') : '-'
</script>

<template>
  <div>
    <section class="panel">
      <header class="panel-h">页面清单 <small>最近 7 天 · {{ pages.length }} 页 · 点击行查看详情</small></header>
      <el-table :data="pages" stripe height="600" size="small" empty-text="暂无数据" style="cursor:pointer" @row-click="openPageDetail">
        <el-table-column prop="route" label="路由" min-width="180" />
        <el-table-column label="URL" min-width="280" show-overflow-tooltip>
          <template #default="{ row }"><span style="color:#64748b">{{ row.url }}</span></template>
        </el-table-column>
        <el-table-column prop="pv" label="PV" width="90" sortable />
        <el-table-column prop="uv" label="UV" width="80" />
        <el-table-column label="平均停留" width="110">
          <template #default="{ row }">{{ fmtMs(row.avgDurationMs) }}</template>
        </el-table-column>
        <el-table-column prop="completed" label="完整浏览" width="100" />
        <el-table-column label="占比" min-width="120">
          <template #default="{ row }">
            <el-progress :percentage="Math.min(100, Math.round(row.pv * 100 / Math.max(1, pages[0]?.pv || 1)))" :show-text="false" />
          </template>
        </el-table-column>
        <el-table-column label="" width="60" align="center">
          <template #default><el-icon color="#94a3b8"><Right /></el-icon></template>
        </el-table-column>
      </el-table>
    </section>

    <el-drawer v-model="pageDrawerVisible" :title="`页面详情 - ${pageDrawerRoute}`" direction="rtl" size="640">
      <div v-loading="pageDrawerLoading" class="pd-wrap">
        <div class="pd-section">
          <div class="pd-section-title">每日 PV / UV 趋势</div>
          <div v-if="pageDrawerTrend" class="trend">
            <svg :viewBox="`0 0 ${pageDrawerTrend.w} ${pageDrawerTrend.h}`" preserveAspectRatio="none" class="trend-svg">
              <path :d="pageDrawerTrend.path" fill="none" stroke="#3b82f6" stroke-width="2" />
              <g v-for="p in pageDrawerTrend.points" :key="p.day">
                <circle :cx="p.x" :cy="p.y" r="3.5" fill="#3b82f6" />
                <text :x="p.x" :y="p.y - 8" text-anchor="middle" font-size="11" fill="#1e293b">{{ p.pv }}</text>
                <text :x="p.x" :y="pageDrawerTrend.h - 4" text-anchor="middle" font-size="10" fill="#64748b">{{ p.day }}</text>
              </g>
            </svg>
          </div>
          <div v-else class="empty">无趋势数据</div>
        </div>
        <div class="pd-section">
          <div class="pd-section-title">访问者(最近 30 次)</div>
          <el-table :data="pageDrawerData.visitors || []" size="small" max-height="260" empty-text="暂无访问者">
            <el-table-column prop="sessionId" label="会话" width="120" show-overflow-tooltip />
            <el-table-column label="用户 ID" width="80">
              <template #default="{ row }">
                <el-tag v-if="row.userId" size="small">#{{ row.userId }}</el-tag>
                <span v-else style="color:#94a3b8">游客</span>
              </template>
            </el-table-column>
            <el-table-column prop="os" label="OS" width="130" />
            <el-table-column prop="browser" label="浏览器" width="140" />
            <el-table-column prop="device" label="设备" width="80" />
            <el-table-column prop="ip" label="IP" width="120" />
            <el-table-column label="访问时间" min-width="140">
              <template #default="{ row }">{{ fmtTs(row.enterAt) }}</template>
            </el-table-column>
          </el-table>
        </div>
        <div class="pd-section">
          <div class="pd-section-title">相关错误</div>
          <el-table :data="pageDrawerData.errors || []" size="small" max-height="200" empty-text="无错误 :)">
            <el-table-column prop="type" label="类型" width="140">
              <template #default="{ row }">
                <el-tag :type="row.type === 'UnhandledRejection' ? 'danger' : 'warning'" size="small">{{ row.type }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="message" label="消息" min-width="220" show-overflow-tooltip />
            <el-table-column label="时间" width="140">
              <template #default="{ row }">{{ fmtTs(row.occurAt) }}</template>
            </el-table-column>
          </el-table>
        </div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; }
.panel-h { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.panel-h small { font-weight: normal; color: var(--c-text-3); margin-left: 8px; font-size: 12px; }
.trend { padding: 4px 0; }
.trend-svg { width: 100%; height: 130px; }
.empty { text-align: center; color: var(--c-text-3); padding: 24px 0; font-size: 13px; }
.pd-wrap { padding: 0 0 16px; }
.pd-section { margin-bottom: 20px; }
.pd-section-title { font-size: 13px; font-weight: 600; margin-bottom: 8px; color: var(--c-text-2); }
</style>