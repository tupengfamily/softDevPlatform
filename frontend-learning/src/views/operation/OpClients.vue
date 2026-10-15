<script setup>
/**
 * 客户端画像
 *
 * <p>OS / Browser / Device 分布(基于 tracker SDK 上报的 userAgent 解析结果)
 */
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import { getClientMetrics } from '../../api/operation'

const loading = ref(false)
let timer = null
const cm = ref({ os: [], browser: [], device: [] })

async function reload() {
  loading.value = true
  try { cm.value = (await getClientMetrics()).data || {} }
  catch (e) {} finally { loading.value = false }
}
onMounted(() => { reload(); timer = setInterval(reload, 5 * 60 * 1000) })
onBeforeUnmount(() => { if (timer) clearInterval(timer) })

function distBars(items, colorMap) {
  const total = items.reduce((s, x) => s + (x.pv || 0), 0) || 1
  return items.map(x => ({
    name: x.name, pv: x.pv || 0, uv: x.uv || 0,
    pct: Math.round(((x.pv || 0) * 1000 / total)) / 10,
    color: colorMap[x.name] || '#64748b'
  }))
}
const osBars = computed(() => distBars(cm.value.os || [], {
  'Windows 11': '#3b82f6', 'Windows 10': '#60a5fa',
  'macOS': '#8b5cf6', 'Linux': '#10b981', 'iOS': '#f59e0b', 'Android': '#ef4444'
}))
const browserBars = computed(() => distBars(cm.value.browser || [], {
  'Chrome': '#3b82f6', 'Edge': '#06b6d4', 'Safari': '#8b5cf6',
  'Firefox': '#f59e0b', 'Opera': '#ef4444'
}))
const deviceBars = computed(() => distBars(cm.value.device || [], {
  'Desktop': '#3b82f6', 'Mobile': '#10b981', 'Tablet': '#f59e0b'
}))
</script>

<template>
  <div>
    <section class="panel">
      <header class="panel-h">客户端画像 <small>最近 7 天</small></header>
      <div class="grid">
        <div class="client-col">
          <div class="client-title">操作系统</div>
          <div v-for="b in osBars" :key="b.name" class="dist-row">
            <span class="dist-label">{{ b.name }}</span>
            <div class="dist-bg"><div class="dist-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
            <span class="dist-val">{{ b.pv }} / {{ b.uv }}</span>
          </div>
          <div v-if="osBars.length === 0" class="empty">无数据</div>
        </div>
        <div class="client-col">
          <div class="client-title">浏览器</div>
          <div v-for="b in browserBars" :key="b.name" class="dist-row">
            <span class="dist-label">{{ b.name }}</span>
            <div class="dist-bg"><div class="dist-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
            <span class="dist-val">{{ b.pv }} / {{ b.uv }}</span>
          </div>
          <div v-if="browserBars.length === 0" class="empty">无数据</div>
        </div>
        <div class="client-col">
          <div class="client-title">设备类型</div>
          <div v-for="b in deviceBars" :key="b.name" class="dist-row">
            <span class="dist-label">{{ b.name }}</span>
            <div class="dist-bg"><div class="dist-fg" :style="{ width: b.pct + '%', background: b.color }"></div></div>
            <span class="dist-val">{{ b.pv }}</span>
          </div>
          <div v-if="deviceBars.length === 0" class="empty">无数据</div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped>
.panel { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 20px; }
.panel-h { font-size: 15px; font-weight: 600; margin-bottom: 12px; }
.panel-h small { font-weight: normal; color: var(--c-text-3); margin-left: 8px; font-size: 12px; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
.client-col { padding: 4px; }
.client-title { font-size: 13px; color: var(--c-text-1); font-weight: 600; margin-bottom: 8px; }
.dist-row { display: grid; grid-template-columns: 110px 1fr 70px; gap: 6px; align-items: center; margin-bottom: 4px; font-size: 11px; }
.dist-label { color: var(--c-text-2); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dist-bg { background: #f1f5f9; height: 8px; border-radius: 4px; overflow: hidden; }
.dist-fg { height: 100%; transition: width .3s; }
.dist-val { color: var(--c-text-3); text-align: right; font-variant-numeric: tabular-nums; }
.empty { text-align: center; color: var(--c-text-3); padding: 24px 0; font-size: 13px; }
@media (max-width: 900px) { .grid { grid-template-columns: 1fr; } }
</style>