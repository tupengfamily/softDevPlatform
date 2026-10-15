<script setup>
/**
 * 性能监控
 *
 * <p>Web Vitals: FCP / LCP / FID / CLS / TTFB 平均 + 按页面排行
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { getPerf } from '../../api/operation'

const loading = ref(false)
let timer = null
const perf = ref({ overall: {}, byRoute: [] })

async function reload() {
  loading.value = true
  try { perf.value = (await getPerf()).data || {} }
  catch (e) {} finally { loading.value = false }
}
onMounted(() => { reload(); timer = setInterval(reload, 5 * 60 * 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

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
</script>

<template>
  <div>
    <section class="panel">
      <header class="panel-h">Web Vitals 概览 <small>最近 7 天</small></header>
      <div class="perf-grid">
        <div class="perf-card">
          <div class="perf-label">FCP<small>(首次内容绘制)</small></div>
          <div class="perf-num">{{ perf.overall.fcp || 0 }}<span class="perf-unit">ms</span></div>
          <el-tag :type="fcpScore.color" size="small">{{ fcpScore.label }}</el-tag>
          <div class="perf-hint">标准: &lt;1.8s 好 / 1.8-3s 良 / &gt;3s 差</div>
        </div>
        <div class="perf-card">
          <div class="perf-label">LCP<small>(最大内容绘制)</small></div>
          <div class="perf-num">{{ perf.overall.lcp || 0 }}<span class="perf-unit">ms</span></div>
          <el-tag :type="lcpScore.color" size="small">{{ lcpScore.label }}</el-tag>
          <div class="perf-hint">标准: &lt;2.5s 好 / 2.5-4s 良 / &gt;4s 差</div>
        </div>
        <div class="perf-card">
          <div class="perf-label">FID<small>(首次输入延迟)</small></div>
          <div class="perf-num">{{ perf.overall.fid || 0 }}<span class="perf-unit">ms</span></div>
          <el-tag :type="fidScore.color" size="small">{{ fidScore.label }}</el-tag>
          <div class="perf-hint">标准: &lt;100ms 好 / 100-300ms 良</div>
        </div>
        <div class="perf-card">
          <div class="perf-label">CLS<small>(布局偏移)</small></div>
          <div class="perf-num">{{ (perf.overall.cls || 0) / 1000 }}</div>
          <el-tag :type="clsScore.color" size="small">{{ clsScore.label }}</el-tag>
          <div class="perf-hint">标准: &lt;0.1 好 / 0.1-0.25 良</div>
        </div>
        <div class="perf-card">
          <div class="perf-label">TTFB<small>(首字节)</small></div>
          <div class="perf-num">{{ perf.overall.ttfb || 0 }}<span class="perf-unit">ms</span></div>
        </div>
        <div class="perf-card">
          <div class="perf-label">样本数</div>
          <div class="perf-num">{{ perf.overall.sampleCount || 0 }}</div>
          <div class="perf-hint">最近 7 天上报</div>
        </div>
      </div>
    </section>

    <section class="panel">
      <header class="panel-h">页面性能排行 <small>按 LCP 平均降序</small></header>
      <el-table :data="perf.byRoute || []" stripe size="small" max-height="500" empty-text="暂无数据">
        <el-table-column prop="route" label="路由" min-width="180" />
        <el-table-column prop="url" label="URL" min-width="260" show-overflow-tooltip />
        <el-table-column label="LCP" width="120" sortable :sort-method="(a, b) => a.avgLcp - b.avgLcp">
          <template #default="{ row }">
            {{ row.avgLcp || 0 }} ms
            <el-tag :type="perfScore(row.avgLcp, { good: 700, poor: 2500 }).color" size="small" effect="plain" style="margin-left:6px">
              {{ perfScore(row.avgLcp, { good: 700, poor: 2500 }).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="FCP" width="100">{{ row => (row.avgFcp || 0) + ' ms' }}</el-table-column>
        <el-table-column label="TTFB" width="100">{{ row => (row.avgTtfb || 0) + ' ms' }}</el-table-column>
        <el-table-column label="CLS" width="100">{{ row => ((row.avgCls || 0) / 1000).toFixed(3) }}</el-table-column>
        <el-table-column prop="sampleCount" label="样本" width="80" />
      </el-table>
    </section>
  </div>
</template>

<style scoped>
.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; margin-bottom: 16px; }
.panel-h { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.panel-h small { font-weight: normal; color: var(--c-text-3); margin-left: 8px; font-size: 12px; }
.perf-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 12px; }
.perf-card { padding: 12px; background: #f8fafc; border-radius: var(--r-s); text-align: center; }
.perf-label { font-size: 12px; color: var(--c-text-3); margin-bottom: 6px; }
.perf-label small { color: var(--c-text-3); font-size: 10px; }
.perf-num { font-size: 22px; font-weight: 600; color: var(--c-text-1); }
.perf-unit { font-size: 12px; font-weight: normal; color: var(--c-text-3); margin-left: 2px; }
.perf-hint { font-size: 10px; color: #94a3b8; margin-top: 4px; }
@media (max-width: 900px) { .perf-grid { grid-template-columns: repeat(2, 1fr); } }
</style>