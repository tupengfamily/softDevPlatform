<template>
  <div class="gh-page">
    <header class="gh-head">
      <a class="back-link" @click="gotoCenter">
        <span class="back-icon">←</span>{{ $t('gameHistory.back') }}
      </a>
      <h2 class="title">{{ $t('gameHistory.title') }}</h2>
      <p class="hint">{{ $t('gameHistory.hint') }}</p>
    </header>

    <section class="gh-toolbar">
      <el-select v-model="code" size="default" class="code-select" @change="loadStats">
        <el-option v-for="g in GAME_OPTIONS" :key="g.code" :value="g.code" :label="g.label" />
      </el-select>
      <div class="summary">
        <span class="sum-item">
          <span class="lab">{{ $t('gameHistory.total') }}</span>
          <strong>{{ total }}</strong>
        </span>
        <span class="sum-item">
          <span class="lab">{{ $t('gameHistory.bestScore') }}</span>
          <strong>{{ bestScore }}</strong>
        </span>
        <span class="sum-item">
          <span class="lab">{{ $t('gameHistory.winRate') }}</span>
          <strong>{{ winRate }}</strong>
        </span>
      </div>
      <el-button @click="loadStats" :loading="loading" plain>{{ $t('gameHistory.refresh') }}</el-button>
    </section>

    <section class="gh-table-card">
      <div v-if="loading" class="loading">{{ $t('common.loading') }}</div>
      <div v-else-if="!entries.length" class="empty">
        <div class="emoji">📭</div>
        <p>{{ $t('gameHistory.empty', { game: currentLabel }) }}</p>
      </div>
      <el-table v-else :data="entries" stripe size="default" class="gh-table" max-height="640">
        <!-- 🔴 2026-10-26：农场对局展开归因明细（extra_json 里 20+ 字段）
             🔴 2026-10-26 修正：窄屏下**不能**用行内展开。
             el-table 的固定宽度列合计 840px，窄屏容器只有 281px，
             横向滚动条在 Element Plus 里是 `visible:false`（宽 0），
             玩家根本发现不了右边还有 607px 内容 —— 归因面板被静默裁掉。
             所以窄屏改用 Drawer（见下方），桌面端才走行内展开。 -->
        <el-table-column v-if="isFarm && !isNarrow" type="expand">
          <template #default="{ row }">
            <FarmAttributionPanel :extra="row.extraParsed" />
          </template>
        </el-table-column>
        <el-table-column v-if="isFarm && isNarrow" width="70" align="center">
          <template #default="{ row }">
            <el-button link type="primary" @click="openAttribution(row)">
              {{ $t('gameHistory.farmDetailBtn') }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column type="index" :label="$t('gameHistory.colNo')" width="60" />
        <el-table-column :label="$t('gameHistory.colTime')" min-width="170">
          <template #default="{ row }">
            <span class="time">{{ fmtTime(row.createTime) }}</span>
            <span class="rel">{{ fmtRelative(row.createTime) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="$t('gameHistory.colScore')" width="100">
          <template #default="{ row }">
            <strong class="score">{{ formatScore(row.score) }}</strong>
          </template>
        </el-table-column>
        <el-table-column :label="$t('gameHistory.colDuration')" width="90">
          <template #default="{ row }">{{ formatDuration(row.playSeconds) }}</template>
        </el-table-column>
        <el-table-column :label="$t('gameHistory.colMoves')" width="80">
          <template #default="{ row }">{{ row.moves }}</template>
        </el-table-column>
        <el-table-column :label="$t('gameHistory.colResult')" width="100">
          <template #default="{ row }">
            <el-tag v-if="getResultTag(row)" :type="getResultTag(row).type" size="small">{{ getResultTag(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column :label="$t('gameHistory.colExtra')" min-width="240">
          <template #default="{ row }">
            <span class="extra-text">{{ formatExtra(row.extra) }}</span>
          </template>
        </el-table-column>
      </el-table>      <div v-if="entries.length === 100" class="cap-note">
        <i class="el-icon-info"></i> {{ $t('gameHistory.cappedAt', { n: 100 }) }}
      </div>
    </section>

    <!-- 🔴 窄屏归因明细抽屉：行内展开在窄屏会被表格横向滚动区静默裁掉，
         抽屉不受表格列宽约束，能完整展示 20+ 归因字段。 -->
    <el-drawer
      v-model="attrDrawer"
      :title="$t('gameHistory.farmDetailTitle')"
      direction="rtl"
      size="92%"
      class="gh-attr-drawer"
    >
      <FarmAttributionPanel :extra="attrRow ? attrRow.extraParsed : null" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { getGameStats } from '../../api/game'
import FarmAttributionPanel from '../../components/farm/FarmAttributionPanel.vue'
import { cropLabelOf } from '../../utils/farmLabels'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()
const gotoCenter = () => router.push('/games')

const GAME_OPTIONS = [
  { code: '2048',       label: t('nav.game2048') },
  { code: 'gomoku',     label: t('gomoku.title') },
  { code: 'guess',      label: t('guess.title') },
  { code: 'sudoku',     label: t('sudoku.title') },
  { code: 'one-stroke', label: t('oneStroke.title') },
  { code: 'farm',       label: t('farm.title') }
]

const code = ref(route.query.code || '2048')
const loading = ref(false)
const entries = ref([])
const total = ref(0)
const bestScore = ref(0)
const currentLabel = computed(() => GAME_OPTIONS.find(g => g.code === code.value)?.label || code.value)

/** 🔴 农场分类才有归因明细（extra_json 是 FarmAttribution 的字段契约）。 */
const isFarm = computed(() => code.value === 'farm')

/**
 * 🔴 窄屏判定（≤700px，与本文件既有断点一致）。
 *
 * <p>用 {@code matchMedia} 而不是 {@code window.innerWidth}：
 * 前者能在视口变化时自动触发监听（{@code addEventListener('change')}
 * 在所有现代浏览器都支持），后者只能靠 resize 事件轮询。
 */
const isNarrow = ref(false)
let narrowMql = null
function syncNarrow () {
  isNarrow.value = window.matchMedia('(max-width: 700px)').matches
}
onMounted(() => {
  narrowMql = window.matchMedia('(max-width: 700px)')
  syncNarrow()
  narrowMql.addEventListener('change', syncNarrow)
  loadStats()
})
onBeforeUnmount(() => {
  if (narrowMql) narrowMql.removeEventListener('change', syncNarrow)
})

/** 窄屏抽屉：当前展示的归因明细行。 */
const attrDrawer = ref(false)
const attrRow = ref(null)
function openAttribution (row) {
  attrRow.value = row
  attrDrawer.value = true
}

const winRate = computed(() => {
  if (!entries.value.length) return t('gameHistory.dash')
  const wins = entries.value.filter(e => {
    if (code.value === 'gomoku' || code.value === 'guess') {
      const extra = parseExtra(e.extra)
      return extra?.result === 'win'
    }
    if (code.value === 'sudoku') {
      const extra = parseExtra(e.extra)
      return extra?.result === 'solved'
    }
    return false // 2048 / one-stroke 不算胜负
  }).length
  if (code.value === '2048' || code.value === 'one-stroke') return t('gameHistory.dash')
  return Math.round((wins / entries.value.length) * 100) + '%'
})

watch(() => route.query.code, (c) => {
  if (c && c !== code.value) { code.value = c; loadStats() }
})

async function loadStats () {
  loading.value = true
  try {
    const res = await getGameStats(code.value, 100)
    const d = res.data || {}
    entries.value = (d.recent || []).map(normalize)
    total.value = d.playedCount || 0
    bestScore.value = d.bestScore || 0
  } catch (e) {
    entries.value = []
    total.value = 0
    bestScore.value = 0
  } finally {
    loading.value = false
  }
}

function normalize (e) {
  return { ...e, extraParsed: parseExtra(e.extra) }
}

function parseExtra (s) {
  if (!s) return null
  try { return JSON.parse(s) } catch { return null }
}

function formatScore (s) {
  if (code.value === 'gomoku') {
    if (s > 0) return t('gameHistory.scoreWin')
    if (s < 0) return t('gameHistory.scoreLose')
    return t('gameHistory.scoreDraw')
  }
  if (code.value === 'one-stroke') {
    return `${s} ${t('gameHistory.pts')}`
  }
  return s
}

function formatDuration (sec) {
  if (!sec) return '0s'
  const m = Math.floor(sec / 60); const s = sec % 60
  return m > 0 ? `${m}m${s}s` : `${s}s`
}

function getResultTag (row) {
  if (code.value === 'sudoku') {
    const r = row.extraParsed?.result
    if (r === 'solved') return { type: 'success', label: t('gameHistory.resultSolved') }
    if (r === 'abandoned') return { type: 'info', label: t('gameHistory.resultAbandoned') }
    return null
  }
  if (code.value === 'guess' || code.value === 'gomoku') {
    const r = row.extraParsed?.result
    if (r === 'win') return { type: 'success', label: t('gameHistory.resultWin') }
    if (r === 'lose') return { type: 'danger', label: t('gameHistory.resultLose') }
    if (r === 'draw') return { type: 'info', label: t('gameHistory.resultDraw') }
    return null
  }
  if (code.value === '2048') {
    if (row.finished === 2) return { type: 'success', label: t('gameHistory.resultReach2048') }
    if (row.finished === 1) return { type: 'danger', label: t('gameHistory.resultDead') }
    if (row.finished === 3) return { type: 'info', label: t('gameHistory.resultAbandoned') }
    return null
  }
  // 🔴 农场：绝收 / 农残超标 / 产量封顶 —— 三种都要标出来，
  // 因为它们是**玩家能改变的结果**，而分数本身看不出区别。
  if (code.value === 'farm') {
    const e = row.extraParsed || {}
    if (e.withered) return { type: 'danger', label: t('gameHistory.farmResult.failed') }
    if (e.phiViolated) return { type: 'warning', label: t('gameHistory.farmResult.phi') }
    if (e.yieldCapped) return { type: 'info', label: t('gameHistory.farmResult.capped') }
    return null
  }
  return null
}

function formatExtra (raw) {
  const e = parseExtra(raw)
  if (!e) return ''
  const parts = []
  if (code.value === 'sudoku') {
    if (e.difficulty) parts.push(t('gameHistory.extDifficulty', { v: t('sudoku.diff.' + e.difficulty) }))
    if (e.mistakes != null) parts.push(t('gameHistory.extMistakes', { n: e.mistakes }))
    if (e.hintsUsed != null) parts.push(t('gameHistory.extHints', { n: e.hintsUsed }))
  } else if (code.value === 'guess') {
    if (e.length) parts.push(t('gameHistory.extLength', { n: e.length }))
    if (e.attempts != null) parts.push(t('gameHistory.extAttempts', { n: e.attempts }))
  } else if (code.value === 'gomoku') {
    if (e.mode) parts.push(t('gameHistory.extMode', { v: t('gomoku.mode.' + e.mode) }))
    if (e.aiLevel) parts.push(t('gameHistory.extAi', { v: t('gomoku.diff.' + e.aiLevel) }))
  } else if (code.value === 'one-stroke') {
    if (e.difficulty) parts.push(t('gameHistory.extDifficulty', { v: t('oneStroke.diff.' + e.difficulty) }))
    if (e.levelTitle) parts.push(t('gameHistory.extLevel', { v: e.levelTitle }))
    if (e.hintsUsed != null) parts.push(t('gameHistory.extHints', { n: e.hintsUsed }))
  } else if (code.value === '2048') {
    if (e.maxStreak) parts.push(t('gameHistory.extStreak', { n: e.maxStreak }))
  } else if (code.value === 'farm') {
    // 🔴 农场摘要：只放**一眼能看出问题**的三项（作物 / 亩产 / 主要减产原因）。
    //    完整归因链在展开行里 —— 表格列宽有限，塞不下 20+ 字段。
    // 🔴 2026-10-27：作物名走共享映射（老数据只有中文名 → 英文界面会露中文）。
    const cropText = cropLabelOf(e, String(locale.value).startsWith('en'))
    if (cropText) parts.push(cropText)
    // 🔴 2026-10-27：优先显示**实收**（yieldFinal），没有才退回 yieldRaw。
    //
    // 为什么改：yieldRaw 是引擎原始**潜力**（只含温度/积温损伤），
    // 而摘要里的「亩产」在玩家眼里就是「我这一季收了多少斤」——
    // 显示潜力会让人以为收成比实际好一大截（管理到位的田差距可达 40%）。
    //
    // 历史数据（2026-10-27 之前）没有 yieldFinal → 退回 yieldRaw，行为不变。
    const y = e.yieldFinal != null ? e.yieldFinal : e.yieldRaw
    if (y != null) {
      parts.push(t('gameHistory.farmYield', { y: Number(y).toFixed(1) }))
    }
    const cause = farmMainCause(e)
    if (cause) parts.push(cause)
  }
  return parts.join(' · ')
}

/**
 * 找出这一季**最主要的减产原因**（只取一个，避免摘要变成长清单）。
 *
 * 🔴 口径：按「四要素受损 + 病虫害 + 灾害 + 缺苗」的严重度比，
 *    而不是按字段顺序取第一个非零 —— 顺序取会漏掉真正的元凶
 *    （比如花器只伤 2%，但病虫害伤了 30%，顺序取会显示花器）。
 */
function farmMainCause(e) {
  const cands = []
  if (e.withered) {
    cands.push({ v: 1, text: t('gameHistory.farmCause.failed') })
  }
  if (e.germination != null && e.germination < 0.85) {
    cands.push({ v: 1 - e.germination, text: t('gameHistory.farmCause.germination') })
  }
  if (e.pestLoss > 0) {
    cands.push({ v: e.pestLoss, text: t('gameHistory.farmCause.pest') })
  }
  if (e.disasterLoss > 0) {
    cands.push({ v: e.disasterLoss, text: t('gameHistory.farmCause.disaster') })
  }
  const dmg = ['dmgPanicle', 'dmgFloret', 'dmgFertility', 'dmgWeight']
  for (const k of dmg) {
    const v = e[k]
    if (typeof v === 'number' && v > 0.005) {
      cands.push({ v, text: t('gameHistory.farmCause.element') })
    }
  }
  if (!cands.length) return ''
  cands.sort((a, b) => b.v - a.v)
  return cands[0].text
}

function fmtTime (s) {
  if (!s) return t('gameHistory.dash')
  // 后端返回 ISO 字符串或时间戳
  const d = typeof s === 'string' ? new Date(s) : new Date(s)
  if (Number.isNaN(d.getTime())) return String(s)
  const p = (x) => String(x).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

function fmtRelative (s) {
  if (!s) return ''
  const d = typeof s === 'string' ? new Date(s) : new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return t('gameHistory.justNow')
  if (min < 60) return t('gameHistory.minutesAgo', { n: min })
  const h = Math.floor(min / 60)
  if (h < 24) return t('gameHistory.hoursAgo', { n: h })
  const day = Math.floor(h / 24)
  if (day < 30) return t('gameHistory.daysAgo', { n: day })
  return fmtTime(s).slice(0, 10)
}
</script>

<style scoped>
.gh-page { padding: 24px 32px 40px; }
.gh-head { margin-bottom: 18px; }
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--fs-sm); color: var(--c-text-2); cursor: pointer;
}
.back-link:hover { color: var(--c-primary); }
.title { margin: 8px 0 4px; font-size: 26px; font-weight: 700; color: var(--c-text-1); }
.title::before { content: '📊 '; }
.gh-head .hint { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }

.gh-toolbar {
  display: flex; align-items: center; gap: 14px; flex-wrap: wrap;
  margin-bottom: 14px; padding: 12px 14px;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.gh-toolbar .code-select { width: 160px; }
.gh-toolbar .summary { display: flex; gap: 18px; }
.gh-toolbar .summary .sum-item {
  display: flex; flex-direction: column; align-items: center; min-width: 70px;
}
.gh-toolbar .summary .lab { font-size: var(--fs-xs); color: var(--c-text-3); margin-bottom: 2px; }
.gh-toolbar .summary strong { font-size: 18px; font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }

.gh-table-card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05); padding: 14px;
  min-height: 200px;
}
.gh-table { font-size: var(--fs-sm); }
.gh-table .time { display: block; font-weight: 600; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
.gh-table .rel { display: block; font-size: var(--fs-xs); color: var(--c-text-3); }
.gh-table .score { font-size: 16px; color: var(--c-primary); font-variant-numeric: tabular-nums; }
.gh-table .extra-text { color: var(--c-text-2); }

/* 🔴 展开行：Element Plus 的 expand 单元格默认没有内边距，
   归因面板会贴到边框上。这里给一层浅底 + 内边距，让它读起来是「子区块」。 */
.gh-table :deep(.el-table__expanded-cell) {
  padding: 0 !important;
  background: #fafbfc;
}
.gh-table :deep(.el-table__expanded-cell > .cell) {
  padding: 10px 14px 4px 46px;
}
@media (max-width: 700px) {
  .gh-table :deep(.el-table__expanded-cell > .cell) {
    padding-left: 14px;
  }
}

/* 🔴 窄屏归因抽屉：抽屉 body 默认 padding 偏小，面板贴边；
   四要素表在窄屏需要允许换行，否则「机理」列会把表格撑宽。 */
:deep(.gh-attr-drawer .el-drawer__body) {
  padding: 12px 14px 24px;
  overflow-x: hidden;
}
:deep(.gh-attr-drawer .fa-table) {
  table-layout: fixed;
}
:deep(.gh-attr-drawer .fa-table th:nth-child(1)),
:deep(.gh-attr-drawer .fa-table td:nth-child(1)) { width: 22%; }
:deep(.gh-attr-drawer .fa-table th:nth-child(2)),
:deep(.gh-attr-drawer .fa-table td:nth-child(2)) { width: 20%; }
:deep(.gh-attr-drawer .fa-table td:nth-child(3)) {
  word-break: break-word;
  line-height: 1.5;
}

.loading, .empty { text-align: center; padding: 50px 20px; color: var(--c-text-3); }
.empty .emoji { font-size: 48px; margin-bottom: 8px; }
.empty p { margin: 4px 0; }

.cap-note {
  margin-top: 10px; padding: 8px 12px;
  background: #f8fafc; border-radius: 6px;
  font-size: var(--fs-xs); color: var(--c-text-3);
}

@media (max-width: 700px) {
  .gh-toolbar { gap: 10px; }
  .gh-toolbar .code-select { width: 100%; }
  .gh-toolbar .summary { width: 100%; justify-content: space-around; }
}
</style>