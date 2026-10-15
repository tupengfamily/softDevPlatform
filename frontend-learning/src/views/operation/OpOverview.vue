<script setup>
/**
 * 运营概览
 *
 * <p>3 张 KPI 卡 + 7 日趋势 + 客户端画像 + 性能面板 + 错误日志摘要
 */
import { ref, reactive, computed, onMounted, onBeforeUnmount } from 'vue'
import {
  getOverview, getErrors, getPerf, getClientMetrics
} from '../../api/operation'
import { fmtMs, fmtPct, fmtNum } from '../../utils/format'

const loading = ref(false)
let timer = null

const overview = ref({ today: {}, week: {}, month: {}, dailyTrend7d: [] })
const perf = ref({ overall: {}, byRoute: [] })
const errors = ref({ recent: [], typeDistribution: [] })
const clientMetrics = ref({ os: [], browser: [], device: [] })

async function reload() {
  loading.value = true
  try {
    const [ov, er, pf] = await Promise.all([
      getOverview(),
      getErrors({ limit: 10 }),
      getPerf()
    ])
    const cm = await getClientMetrics()
    overview.value = ov.data || {}
    errors.value = er.data || { recent: [], typeDistribution: [] }
    perf.value = pf.data || { overall: {}, byRoute: [] }
    clientMetrics.value = cm.data || { os: [], browser: [], device: [] }
  } catch (e) {} finally { loading.value = false }
}

onMounted(() => { reload(); timer = setInterval(reload, 5 * 60 * 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

const trendLine = computed(() => {
  const days = (overview.value.dailyTrend7d || []).slice(-7)
  if (days.length < 2) return null
  const maxPv = Math.max(...days.map(d => d.pv), 1)
  const w = 600, h = 160, padX = 32, padY = 20
  const stepX = (w - padX * 2) / (days.length - 1)
  const points = days.map((d, i) => ({
    x: padX + i * stepX,
    y: h - padY - ((d.pv / maxPv) * (h - padY * 2)),
    day: d.day.slice(5), pv: d.pv, uv: d.uv
  }))
  const path = points.map((p, i) => `${i === 0 ? 'M' : 'L'} ${p.x.toFixed(1)} ${p.y.toFixed(1)}`).join(' ')
  return { points, path, w, h }
})

function perfScore(value, thresholds) {
  if (value == null) return { label: '-', color: 'info' }
  if (value <= thresholds.good) return { label: '好', color: 'success' }
  if (value <= thresholds.poor) return { label: '良', color: 'warning' }
  return { label: '差', color: 'danger' }
}
const lcpScore = computed(() => perfScore(perf.value.overall.lcp, { good: 700, poor: 2500 }))
const fidScore = computed(() => perfScore(perf.value.overall.fid, { good: 100, poor: 300 }))
const clsScore = computed(() => perfScore(perf.value.overall.cls, { good: 100, poor: 250 }))
const fcpScore = computed(() => perfScore(perf.value.overall.fcp, { good: 1800, poor: 3000 }))

function distBars(items, colorMap) {
  const total = items.reduce((s, x) => s + (x.pv || 0), 0) || 1
  return items.map(x => ({
    name: x.name, pv: x.pv || 0,
    pct: Math.round(((x.pv || 0) * 1000 / total)) / 10,
    color: colorMap && colorMap[x.name] ? colorMap[x.name] : '#3b82f6'
  }))
}
const osBars = computed(() => distBars(clientMetrics.value.os || [], {
  'Windows 11': '#3b82f6', 'Windows 10': '#60a5fa',
  'macOS': '#8b5cf6', 'Linux': '#10b981', 'iOS': '#f59e0b', 'Android': '#ef4444'
}))
const browserBars = computed(() => distBars(clientMetrics.value.browser || [], {
  'Chrome': '#3b82f6', 'Edge': '#06b6d4', 'Safari': '#8b5cf6',
  'Firefox': '#f59e0b', 'Opera': '#ef4444'
}))
const deviceBars = computed(() => distBars(clientMetrics.value.device || [], {
  'Desktop': '#3b82f6', 'Mobile': '#10b981', 'Tablet': '#f59e0b'
}))

const fmtTs = (ts) => ts ? new Date(ts).toLocaleString('zh-CN') : '-'
</script>

<template>
  <div>
    <!-- KPI -->
    <section class="kpi-grid">
      <div class="kpi-card">
        <div class="kpi-label">今日</div>
        <div class="kpi-row">
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.today.pv) }}</span><span class="kpi-cap">PV</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.today.uv) }}</span><span class="kpi-cap">UV</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtMs(overview.today.avgDurationMs) }}</span><span class="kpi-cap">平均停留</span></div>
          <div class="kpi-cell" :class="{ alert: overview.today.errors > 0 }">
            <span class="kpi-num">{{ fmtNum(overview.today.errors) }}</span>
            <span class="kpi-cap">错误</span>
          </div>
        </div>
        <div class="kpi-foot">
          <span>登录用户: {{ fmtNum(overview.today.loggedUv) }}</span>
          <span>错误率: {{ fmtPct(overview.today.errorRate) }}</span>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">本周</div>
        <div class="kpi-row">
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.week.pv) }}</span><span class="kpi-cap">PV</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.week.uv) }}</span><span class="kpi-cap">UV</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtMs(overview.week.avgDurationMs) }}</span><span class="kpi-cap">平均停留</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.week.errors) }}</span><span class="kpi-cap">错误</span></div>
        </div>
      </div>
      <div class="kpi-card">
        <div class="kpi-label">本月</div>
        <div class="kpi-row">
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.month.pv) }}</span><span class="kpi-cap">PV</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.month.uv) }}</span><span class="kpi-cap">UV</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtMs(overview.month.avgDurationMs) }}</span><span class="kpi-cap">平均停留</span></div>
          <div class="kpi-cell"><span class="kpi-num">{{ fmtNum(overview.month.errors) }}</span><span class="kpi-cap">错误</span></div>
        </div>
      </div>
    </section>

    <!-- 7 日趋势 -->
    <section class="panel">
      <header class="panel-h">最近 7 日趋势</header>
      <div v-if="trendLine" class="trend">
        <svg :viewBox="`0 0 ${trendLine.w} ${trendLine.h}`" preserveAspectRatio="none" class="trend-svg">
          <path :d="trendLine.path" fill="none" stroke="#3b82f6" stroke-width="2" />
          <g v-for="p in trendLine.points" :key="p.day">
            <circle :cx="p.x" :cy="p.y" r="3.5" fill="#3b82f6" />
            <text :x="p.x" :y="p.y - 8" text-anchor="middle" font-size="11" fill="#1e293b">{{ p.pv }}</text>
            <text :x="p.x" :y="trendLine.h - 4" text-anchor="middle" font-size="10" fill="#64748b">{{ p.day }}</text>
          </g>
        </svg>
      </div>
      <div v-else class="empty">暂无数据</div>
    </section>

    <!-- 客户端画像 -->
    <section class="panel">
      <header class="panel-h">客户端画像 <small>最近 7 天</small></header>
      <div class="client-cols">
        <div class="client-col">
          <div class="client-title">操作系统</div>
          <div v-for="b in osBars.slice(0, 8)" :key="b.name" class="dist-row">
            <span class="dist-label">{{ b.name }}</span>
            <div class="dist-bg"><div class="dist-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
            <span class="dist-val">{{ b.pv }}</span>
          </div>
          <div v-if="osBars.length === 0" class="empty">无数据</div>
        </div>
        <div class="client-col">
          <div class="client-title">浏览器</div>
          <div v-for="b in browserBars.slice(0, 8)" :key="b.name" class="dist-row">
            <span class="dist-label">{{ b.name }}</span>
            <div class="dist-bg"><div class="dist-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
            <span class="dist-val">{{ b.pv }}</span>
          </div>
          <div v-if="browserBars.length === 0" class="empty">无数据</div>
        </div>
        <div class="client-col">
          <div class="client-title">设备类型</div>
          <div v-for="b in deviceBars.slice(0, 5)" :key="b.name" class="dist-row">
            <span class="dist-label">{{ b.name }}</span>
            <div class="dist-bg"><div class="dist-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
            <span class="dist-val">{{ b.pv }}</span>
          </div>
          <div v-if="deviceBars.length === 0" class="empty">无数据</div>
        </div>
      </div>
    </section>

    <!-- 性能摘要 -->
    <section class="panel">
      <header class="panel-h">页面性能 <small>最近 7 天</small></header>
      <div class="perf-grid">
        <div class="perf-card">
          <div class="perf-label">FCP</div>
          <div class="perf-num">{{ perf.overall.fcp || 0 }}<span class="perf-unit">ms</span></div>
          <el-tag :type="fcpScore.color" size="small">{{ fcpScore.label }}</el-tag>
        </div>
        <div class="perf-card">
          <div class="perf-label">LCP</div>
          <div class="perf-num">{{ perf.overall.lcp || 0 }}<span class="perf-unit">ms</span></div>
          <el-tag :type="lcpScore.color" size="small">{{ lcpScore.label }}</el-tag>
        </div>
        <div class="perf-card">
          <div class="perf-label">FID</div>
          <div class="perf-num">{{ perf.overall.fid || 0 }}<span class="perf-unit">ms</span></div>
          <el-tag :type="fidScore.color" size="small">{{ fidScore.label }}</el-tag>
        </div>
        <div class="perf-card">
          <div class="perf-label">CLS</div>
          <div class="perf-num">{{ (perf.overall.cls || 0) / 1000 }}</div>
          <el-tag :type="clsScore.color" size="small">{{ clsScore.label }}</el-tag>
        </div>
        <div class="perf-card">
          <div class="perf-label">TTFB</div>
          <div class="perf-num">{{ perf.overall.ttfb || 0 }}<span class="perf-unit">ms</span></div>
        </div>
        <div class="perf-card">
          <div class="perf-label">样本数</div>
          <div class="perf-num">{{ perf.overall.sampleCount || 0 }}</div>
        </div>
      </div>
    </section>

    <!-- 最近错误摘要 -->
    <section class="panel">
      <header class="panel-h">最近错误(代码运行时) <small>最近 10 条</small></header>
      <el-table :data="errors.recent || []" stripe size="small" empty-text="无错误 :)">
        <el-table-column prop="type" label="类型" width="140">
          <template #default="{ row }">
            <el-tag :type="row.type === 'UnhandledRejection' ? 'danger' : 'warning'" size="small">{{ row.type }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="route" label="页面" width="160" />
        <el-table-column prop="message" label="消息" min-width="200" show-overflow-tooltip />
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTs(row.occurAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.kpi-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 16px; }
.kpi-card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; }
.kpi-label { font-size: 12px; color: var(--c-text-3); margin-bottom: 8px; font-weight: 500; }
.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.kpi-cell { display: flex; flex-direction: column; align-items: center; }
.kpi-num { font-size: 22px; font-weight: 600; color: var(--c-text-1); }
.kpi-cap { font-size: 11px; color: var(--c-text-3); margin-top: 2px; }
.kpi-cell.alert .kpi-num { color: #ef4444; }
.kpi-foot { display: flex; gap: 16px; margin-top: 8px; font-size: 11px; color: var(--c-text-3); }

.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; margin-bottom: 16px; }
.panel-h { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.panel-h small { font-weight: normal; color: var(--c-text-3); margin-left: 8px; font-size: 12px; }

.trend { padding: 4px 0; }
.trend-svg { width: 100%; height: 180px; }
.empty { text-align: center; color: var(--c-text-3); padding: 24px 0; font-size: 13px; }

.client-cols { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.client-col { padding: 4px; }
.client-title { font-size: 12px; color: var(--c-text-3); font-weight: 500; margin-bottom: 8px; }
.dist-row { display: grid; grid-template-columns: 110px 1fr 50px; gap: 6px; align-items: center; margin-bottom: 4px; font-size: 11px; }
.dist-label { color: var(--c-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dist-bg { background: #f1f5f9; height: 8px; border-radius: 4px; overflow: hidden; }
.dist-fg { height: 100%; transition: width .3s; }
.dist-val { color: var(--c-text-3); text-align: right; }

.perf-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.perf-card { padding: 12px; background: #f8fafc; border-radius: var(--r-s); text-align: center; }
.perf-label { font-size: 12px; color: var(--c-text-3); margin-bottom: 6px; }
.perf-num { font-size: 22px; font-weight: 600; color: var(--c-text-1); }
.perf-unit { font-size: 12px; font-weight: normal; color: var(--c-text-3); margin-left: 2px; }

@media (max-width: 900px) {
  .kpi-grid { grid-template-columns: 1fr; }
  .client-cols { grid-template-columns: 1fr; }
  .perf-grid { grid-template-columns: repeat(3, 1fr); }
}
</style>