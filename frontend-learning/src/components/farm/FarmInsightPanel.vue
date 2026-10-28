<template>
  <div class="fi-panel">
    <!-- 无解释数据（待播种地块 / 空农场） -->
    <p v-if="!list.length" class="fi-empty muted">{{ $t('farm.insight.empty') }}</p>

    <article
      v-for="ins in list"
      :key="ins.conceptId"
      class="fi-item"
      :class="'sev-' + sevBand(ins.severity)"
    >
      <!-- 概念标题 + 严重度徽标 -->
      <header class="fi-head">
        <h4>{{ conceptTitle(ins) }}</h4>
        <span class="fi-sev" :class="'sev-' + sevBand(ins.severity)">
          {{ $t('farm.insight.sev.' + sevBand(ins.severity)) }}
        </span>
      </header>

      <!-- ① 现象 -->
      <div class="fi-row">
        <span class="fi-label">{{ $t('farm.insight.observation') }}</span>
        <p class="fi-text" v-html="rich(ins.observation)"></p>
      </div>

      <!-- ② 量 -->
      <div class="fi-row">
        <span class="fi-label">{{ $t('farm.insight.quantity') }}</span>
        <p class="fi-text fi-quantity mono" v-html="rich(ins.quantity)"></p>
      </div>

      <!-- ③ 原理 -->
      <div class="fi-row">
        <span class="fi-label">{{ $t('farm.insight.principle') }}</span>
        <p class="fi-text" v-html="rich(ins.principle)"></p>
      </div>

      <!-- ④ 建议 -->
      <div class="fi-row fi-advice-row">
        <span class="fi-label">{{ $t('farm.insight.advice') }}</span>
        <p class="fi-text fi-advice" v-html="rich(ins.advice)"></p>
      </div>

      <!-- 量化指标（进度条） -->
      <div v-if="barsOf(ins).length" class="fi-bars">
        <div v-for="b in barsOf(ins)" :key="b.key" class="fi-bar-row">
          <span class="fi-bar-label">{{ b.label }}</span>
          <span class="fi-bar-track">
            <span
              class="fi-bar-fill"
              :class="b.tone"
              :style="{ width: b.pct + '%' }"
            ></span>
          </span>
          <span class="fi-bar-value mono">{{ b.text }}</span>
        </div>
      </div>
    </article>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { sanitizeHtml } from '../../utils/markdown'

/**
 * 农场科学解释面板（2026-10-27 P1）。
 *
 * <p>🔴 <b>数据源是 {@code FarmView.insights}</b> —— 后端
 * {@code FarmInsightService} 算好的结构化解释（conceptId + 四段文案 + metrics）。
 * 前端<b>不自己算任何科学量</b>：算就是第二份口径，必然漂移。
 *
 * <p>🔴 <b>四段式结构</b>（对应「农业科学模块」的四层定位）：
 * <ol>
 *   <li><b>现象</b>：我观察到什么（后端中文原文）；</li>
 *   <li><b>量</b>：具体数字（后端算好的字符串，含单位）；</li>
 *   <li><b>原理</b>：背后的科学；</li>
 *   <li><b>建议</b>：下一步做什么。</li>
 * </ol>
 * 玩家能迁移的是<b>原理</b>，不是数字 —— 所以四段必须都在，不能只给结论。
 *
 * <p>🔴 <b>跨语言纪律</b>：后端是中文唯一权威，但每段都带稳定标识
 * （{@code conceptId} / {@code metrics} 的键）。前端按稳定标识查 i18n，
 * <b>查不到就退回后端中文原文</b>（宁可中文也不要空白）。
 * 这与 {@code FarmAttributionPanel} 是同一套模式。
 *
 * <p>🔴 <b>「缺数据」不编造</b>：后端拿不到引擎数据的概念<b>整条不下发</b>，
 * 所以这里没有「缺数据」的渲染分支 —— 列表为空就是「本季没有可解释的概念」。
 */

const { t } = useI18n()

const props = defineProps({
  /** FarmView.insights 数组（后端下发，可能为空） */
  insights: { type: Array, default: () => [] }
})

const list = computed(() => (Array.isArray(props.insights) ? props.insights : []))

/**
 * 文本 → 安全 HTML。
 *
 * <p>🔴 <b>为什么需要它</b>：后端原理段会用 {@code <b>} 标出关键词
 * （如「饱和」「定数时间」）。用 {@code {{ }}} 文本插值会把标签<b>当字面量显示</b>
 * —— 玩家看到的是「贡献是<b>饱和</b>的」而不是加粗的「饱和」。
 *
 * <p>🔴 <b>为什么直接用 v-html 是安全的</b>：文本来自后端
 * {@code FarmInsightService}（服务端唯一权威，不是用户输入），
 * 且统一过 {@link sanitizeHtml}（DOMPurify 白名单）——
 * <b>复用的是项目里唯一的消毒实现</b>，不新写第二份。
 *
 * <p>🔴 <b>纪律</b>：任何 v-html 前都必须先过 sanitizeHtml；
 * 这条规则在 {@code Layout.vue} 已有同样约定（{@code tHtml}）。
 */
function rich (text) {
  return sanitizeHtml(String(text ?? ''))
}

/**
 * 概念标题：优先按 {@code conceptId} 查 i18n，查不到退回后端标题。
 *
 * <p>🔴 {@code conceptId} 是跨语言稳定标识（AAT / NITROGEN / YIELD_ELEMENTS / WATER）。
 */
function conceptTitle (ins) {
  const key = 'farm.insight.concept.' + ins.conceptId
  const v = t(key)
  return v === key ? (ins.title || ins.conceptId) : v
}

/**
 * 严重度分档（0..5 → 4 档）。
 *
 * <p>🔴 与后端 {@code RiskItem.level} 同一刻度（0..5），但 UI 上 6 档太碎，
 * 归成 4 档：0=正常 / 1=注意 / 2=警告 / 3+=严重。
 * <b>不改后端数值</b>，只做展示分档 —— 两端口径不能各搞一套。
 */
function sevBand (severity) {
  const s = Number(severity) || 0
  if (s <= 0) return 'ok'
  if (s === 1) return 'info'
  if (s === 2) return 'warn'
  return 'bad'
}

/**
 * 量化指标 → 进度条。
 *
 * <p>🔴 <b>每个概念的指标表写在这里</b>（唯一一处），不散落到模板里。
 * 键名与后端 {@code metrics} 的键一一对应（稳定标识，不随文案变）。
 *
 * <p>🔴 <b>没有进度条的指标就不画</b>：画一根 0% 的空条等于说「这项是 0」，
 * 而「没有这项数据」与「这项是 0」是两回事（本项目反复踩过的坑）。
 *
 * <p>🔴 <b>前端不自己算比值</b>：后端 {@code FarmInsightService} 已经把
 * 「地力多少算好」「春化完成度多少」这些口径算好并下发了
 * {@code sfiRatio / soilCoefRatio / vernalizationRatio / heatForceRatio / phiShortRatio}。
 * 前端若自己拼分母（例如「地力 60 分算好」），就是<b>在前端复制一份农学口径</b>，
 * 迟早与后端分叉，而且分叉后<b>不会报错</b>，只会悄悄给出错误结论。
 */
const METRIC_BARS = {
  AAT: [
    { key: 'ratio', label: 'farm.insight.metric.aatProgress', pct: (m) => clampPct(m.ratio * 100), text: (m) => fmtPct(m.ratio * 100) }
  ],
  NITROGEN: [
    { key: 'ratio', label: 'farm.insight.metric.nRatio', pct: (m) => clampPct(m.ratio * 100), text: (m) => fmtPct(m.ratio * 100) },
    { key: 'nSatisfaction', label: 'farm.insight.metric.nSat', pct: (m) => clampPct(m.nSatisfaction * 100), text: (m) => fmtPct(m.nSatisfaction * 100) }
  ],
  YIELD_ELEMENTS: [
    { key: 'lossPanicle', label: 'farm.insight.metric.lossPanicle', pct: (m) => clampPct(m.lossPanicle * 100), text: (m) => fmtLoss(m.lossPanicle) },
    { key: 'lossFloret', label: 'farm.insight.metric.lossFloret', pct: (m) => clampPct(m.lossFloret * 100), text: (m) => fmtLoss(m.lossFloret) },
    { key: 'lossFertility', label: 'farm.insight.metric.lossFertility', pct: (m) => clampPct(m.lossFertility * 100), text: (m) => fmtLoss(m.lossFertility) },
    { key: 'lossWeight', label: 'farm.insight.metric.lossWeight', pct: (m) => clampPct(m.lossWeight * 100), text: (m) => fmtLoss(m.lossWeight) }
  ],
  WATER: [
    { key: 'droughtLoss', label: 'farm.insight.metric.droughtLoss', pct: (m) => clampPct(m.droughtLoss * 100), text: (m) => fmtLoss(m.droughtLoss) },
    { key: 'waterlogLoss', label: 'farm.insight.metric.waterlogLoss', pct: (m) => clampPct(m.waterlogLoss * 100), text: (m) => fmtLoss(m.waterlogLoss) }
  ],
  // 🔴 地力与轮作：两个归一化量都来自后端（sfi/100、(coef−0.60)/0.40）。
  //    「连作季数」是计数不是比值 —— 不画进度条（画了就得在前端定「几季算多」的阈值）。
  SOIL_ROTATION: [
    { key: 'sfiRatio', label: 'farm.insight.metric.sfi', pct: (m) => clampPct(m.sfiRatio * 100), text: (m) => fmtNum(m.sfi) },
    { key: 'soilCoefRatio', label: 'farm.insight.metric.soilCoef', pct: (m) => clampPct(m.soilCoefRatio * 100), text: (m) => fmtNum(m.soilCoef) }
  ],
  // 🔴 热害：worstHeatLoss 是损失类（越大越糟）；heatForceRatio 是「高温强迫量占需求积温的比例」——
  //    它越大代表发育被加速得越多（提前成熟），但**过热本身是风险**，
  //    所以按「风险类」着色（越大越红），而不是按「进度类」着色。
  HEAT_STRESS: [
    { key: 'worstHeatLoss', label: 'farm.insight.metric.worstHeatLoss', pct: (m) => clampPct(m.worstHeatLoss * 100), text: (m) => fmtLoss(m.worstHeatLoss) },
    { key: 'heatForceRatio', label: 'farm.insight.metric.heatForceRatio', pct: (m) => clampPct(m.heatForceRatio * 100), text: (m) => fmtPct(m.heatForceRatio * 100) }
  ],
  // 🔴 春化：vernalizationRatio = 已积累/需求（进度类，越接近满越好）。
  //    「冻死」是布尔事实，不画成进度条 —— 0/1 的条没有进度语义。
  VERNALIZATION: [
    { key: 'vernalizationRatio', label: 'farm.insight.metric.vernalization', pct: (m) => clampPct(m.vernalizationRatio * 100), text: (m) => fmtPct(m.vernalizationRatio * 100) }
  ],
  // 🔴 PHI：phiShortRatio = 缺口天数/该药要求间隔天数（越大越糟）。
  //    priceFactor 是折价系数（0.85），是结果不是进度，不画条。
  PHI: [
    { key: 'phiShortRatio', label: 'farm.insight.metric.phiShort', pct: (m) => clampPct(m.phiShortRatio * 100), text: (m) => fmtPct(m.phiShortRatio * 100) }
  ]
}

/**
 * 指标的方向（决定进度条颜色）。
 *
 * <p>🔴 <b>为什么不能靠键名猜方向</b>：
 * 早期实现用「键名含 loss 就越大越糟」判定，{@code worstHeatLoss} 恰好被正确识别，
 * 但 {@code heatForceRatio} 若也按同一规则会被当成「进度类」着色 ——
 * 而它其实是<b>风险量</b>（高温强迫得越多越危险）。
 * 方向必须逐个指标显式声明，不能靠命名猜。
 *
 * <ul>
 *   <li>{@code 'gain'} —— 进度类：越大越好（绿）；</li>
 *   <li>{@code 'loss'} —— 损失类：越大越糟（红）；</li>
 *   <li>{@code 'risk'} —— 风险类：越大越糟（红），语义是「偏离正常」而非「已损失」。</li>
 * </ul>
 */
const METRIC_DIRECTION = {
  aatProgress: 'gain',
  nRatio: 'gain',
  nSat: 'gain',
  lossPanicle: 'loss',
  lossFloret: 'loss',
  lossFertility: 'loss',
  lossWeight: 'loss',
  droughtLoss: 'loss',
  waterlogLoss: 'loss',
  sfi: 'gain',
  soilCoef: 'gain',
  worstHeatLoss: 'loss',
  heatForceRatio: 'risk',
  vernalization: 'gain',
  phiShort: 'loss'
}

function barsOf (ins) {
  const spec = METRIC_BARS[ins.conceptId]
  if (!spec || !ins.metrics) return []
  const m = ins.metrics
  const out = []
  for (const s of spec) {
    const raw = m[s.key]
    const v = Number(raw)
    if (!Number.isFinite(v)) continue
    const pct = s.pct(m)
    const label = t(s.label)
    const dir = METRIC_DIRECTION[s.key] || 'gain'
    out.push({
      key: s.key,
      label: label === s.label ? s.key : label,
      pct,
      text: s.text(m),
      // 损失类 / 风险类：值越大越糟 → 红；进度类：越接近满越好 → 绿
      tone: dir === 'gain'
        ? (pct >= 90 ? 'ok' : pct >= 60 ? 'info' : 'warn')
        : (pct >= 30 ? 'bad' : pct >= 10 ? 'warn' : 'ok')
    })
  }
  return out
}

function clampPct (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return 0
  return Math.max(0, Math.min(100, n))
}

function fmtPct (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return n.toFixed(0) + '%'
}

function fmtLoss (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  if (n <= 0.0005) return '0.0%'
  return '−' + (n * 100).toFixed(1) + '%'
}

/**
 * 保留一位小数的原始数值（用于「地力 76.0 分 / 系数 0.9」这类带量纲的展示）。
 *
 * <p>🔴 进度条画的是**归一化后的比例**，但**文字要显示原始量** ——
 * 显示「76%」会让玩家以为地力是 76%，而地力指数的定义域是 0–100 分，
 * 量纲完全不同（这与「积温进度 61%」是两回事）。
 */
function fmtNum (v) {
  const n = Number(v)
  if (!Number.isFinite(n)) return '—'
  return n.toFixed(1)
}
</script>

<style scoped>
.fi-panel {
  font-size: var(--fs-sm);
  color: var(--c-text-1);
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
}
.fi-empty {
  margin: 0;
  padding: 6px 0;
  font-size: var(--fs-xs);
  color: var(--c-text-3);
}

.fi-item {
  border-left: 3px solid var(--c-border);
  padding: 8px 0 10px 10px;
  margin-bottom: 12px;
  border-radius: 0 6px 6px 0;
}
.fi-item:last-child { margin-bottom: 0; }
.fi-item.sev-ok { border-left-color: #1e8449; }
.fi-item.sev-info { border-left-color: #2e86c1; }
.fi-item.sev-warn { border-left-color: #b9770e; }
.fi-item.sev-bad { border-left-color: #c0392b; }

.fi-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
}
.fi-head h4 {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
}
.fi-sev {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  color: #fff;
  white-space: nowrap;
}
.fi-sev.sev-ok { background: #1e8449; }
.fi-sev.sev-info { background: #2e86c1; }
.fi-sev.sev-warn { background: #b9770e; }
.fi-sev.sev-bad { background: #c0392b; }

.fi-row {
  display: flex;
  gap: 8px;
  align-items: flex-start;
  margin-bottom: 4px;
}
.fi-label {
  flex: 0 0 auto;
  font-size: 11px;
  color: var(--c-text-3);
  background: var(--c-bg-2, #f5f6f8);
  border-radius: 3px;
  padding: 1px 6px;
  margin-top: 2px;
  white-space: nowrap;
}
.fi-text {
  margin: 0;
  flex: 1;
  min-width: 0;
  font-size: var(--fs-xs);
  line-height: 1.65;
  color: var(--c-text-2);
}
.fi-quantity {
  color: var(--c-text-1);
  font-weight: 600;
}
.fi-advice {
  color: var(--c-text-1);
}

.fi-bars {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.fi-bar-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
}
.fi-bar-label {
  flex: 0 0 88px;
  color: var(--c-text-3);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fi-bar-track {
  flex: 1;
  height: 5px;
  background: var(--c-border);
  border-radius: 999px;
  overflow: hidden;
  min-width: 0;
}
.fi-bar-fill {
  display: block;
  height: 100%;
  border-radius: 999px;
}
.fi-bar-fill.ok { background: #1e8449; }
.fi-bar-fill.info { background: #2e86c1; }
.fi-bar-fill.warn { background: #b9770e; }
.fi-bar-fill.bad { background: #c0392b; }
.fi-bar-value {
  flex: 0 0 auto;
  color: var(--c-text-2);
  min-width: 48px;
  text-align: right;
}
.muted { color: var(--c-text-3); }
.mono { font-variant-numeric: tabular-nums; }

/* 🔴 窄屏（375px）：标签与文本换行排布，避免长中文串把卡片撑破 */
@media (max-width: 480px) {
  .fi-row {
    flex-direction: column;
    gap: 2px;
  }
  .fi-label {
    align-self: flex-start;
  }
  .fi-bar-label {
    flex: 0 0 76px;
  }
}
</style>
