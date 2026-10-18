<script setup>
/**
 * 仪表盘(重构)
 * <p>
 * 单接口拉全 + Caffeine 60s 缓存;反映 5 款游戏 + 学习平台 + 反馈的真实活跃度
 * 主指标 4 张:游戏 / 学习 / 反馈 / 待办;趋势 4 张 + 最近活动 2 张
 */
import { ref, onMounted, computed } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getDashboardAll } from '../api/dashboard'

const router = useRouter()
const data = ref({})
const loading = ref(false)

// 主指标卡(按业务模块排,不再以 admin 中心)
const summary = computed(() => data.value.summary || {})
const gamePlays = computed(() => data.value.gamePlays || [])
const learning = computed(() => data.value.learning || [])
const feedbackTrend = computed(() => data.value.feedbackTrend || [])
const registrations = computed(() => data.value.registrations || [])
const operations = computed(() => data.value.operations || [])
const statusDist = computed(() => data.value.statusDist || [])
const recentFeedback = computed(() => data.value.recentFeedback || [])
const recentGames = computed(() => data.value.recentGames || [])

const gMax = computed(() => Math.max(1, ...gamePlays.value.map(x => x.count)))
const lMax = computed(() => Math.max(1, ...learning.value.map(x => x.count)))
const fbMax = computed(() => Math.max(1, ...feedbackTrend.value.map(x => x.count)))
const opMax = computed(() => Math.max(1, ...operations.value.map(x => x.count)))

const formatShortDate = (iso) => iso.slice(5) // MM-DD
const statusLabel = (s) => ({ 0: '禁用', 1: '启用', 2: '只读' }[s] || `状态${s}`)
const statusType = (s) => ({ 0: 'danger', 1: 'success', 2: 'info' }[s] || '')

const feedbackTypeLabel = (t) => ({ BUG: 'Bug', SUGGESTION: '建议', RATING: '评分' }[t] || t)
const feedbackTypeType = (t) => ({ BUG: 'danger', SUGGESTION: 'warning', RATING: '' }[t] || 'info')

const gameLabel = (c) => ({ '2048': '2048', gomoku: '五子棋', guess: '猜数字', sudoku: '数独', 'one-stroke': '一笔画完' }[c] || c)
const formatGameTime = (iso) => {
  if (!iso) return '-'
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return iso
  const pad = (x) => String(x).padStart(2, '0')
  return `${d.getMonth() + 1}/${d.getDate()} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function gotoFeedback() { router.push('/feedback') }
function gotoGame(code) {
  router.push(`/games/${code === 'one-stroke' ? 'one-stroke' : code}`)
}

onMounted(async () => {
  loading.value = true
  try {
    const res = await getDashboardAll()
    data.value = res.data || {}
  } catch (e) {
    ElMessage.error('加载仪表盘失败')
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('dashboard.title') }}</div>
      <div class="page-subtitle">Spring Boot 3 + Vue 3 + Element Plus + SQLite + JWT</div>
    </div>

    <!-- 顶部 4 张主指标卡 — 按业务模块而非按 admin 维度 -->
    <el-row :gutter="16" v-loading="loading">
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-blue">
          <div class="stat-label">{{ $t('dashboard.stat.gamePlays') }}</div>
          <div class="stat-num">{{ summary.totalGamePlays ?? 0 }}</div>
          <div class="stat-sub">
            <span>{{ $t('dashboard.stat.gameUsers') }} {{ summary.gameUsers ?? 0 }}</span>
            <span>{{ $t('dashboard.stat.activeGames') }} {{ summary.activeGames ?? 0 }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-green">
          <div class="stat-label">{{ $t('dashboard.stat.learning') }}</div>
          <div class="stat-num">{{ summary.totalLearned ?? 0 }}</div>
          <div class="stat-sub">
            <span>{{ summary.activeSubjects ?? 0 }} {{ $t('dashboard.stat.subjects') }}</span>
            <span>{{ summary.totalLessons ?? 0 }} {{ $t('dashboard.stat.lessons') }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-orange">
          <div class="stat-label">{{ $t('dashboard.stat.feedback') }}</div>
          <div class="stat-num">{{ summary.totalFeedback ?? 0 }}</div>
          <div class="stat-sub">
            <el-tag v-if="(summary.pendingFeedback || 0) > 0" type="danger" size="small">{{ summary.pendingFeedback }} {{ $t('dashboard.stat.pending') }}</el-tag>
            <span v-else>{{ $t('dashboard.stat.allHandled') }}</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-purple">
          <div class="stat-label">{{ $t('dashboard.stat.ops') }}</div>
          <div class="stat-num">{{ summary.todayOps ?? 0 }}</div>
          <div class="stat-sub">
            <span>{{ $t('dashboard.stat.total') }} {{ summary.totalOps ?? 0 }}</span>
            <span>{{ summary.totalUsers ?? 0 }} {{ $t('dashboard.stat.users') }}</span>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 4 个趋势图:对局 / 学习 / 反馈 / 操作 -->
    <el-row :gutter="16" style="margin-top:16px;">
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.chart.gamePlays') }}</strong></template>
          <div class="chart">
            <div v-for="item in gamePlays" :key="item.date" class="chart-col">
              <div class="chart-value">{{ item.count }}</div>
              <div class="chart-bar bar-blue" :style="{ height: (item.count / gMax * 100) + '%' }"></div>
              <div class="chart-label">{{ formatShortDate(item.date) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.chart.learning') }}</strong></template>
          <div class="chart">
            <div v-for="item in learning" :key="item.date" class="chart-col">
              <div class="chart-value">{{ item.count }}</div>
              <div class="chart-bar bar-green" :style="{ height: (item.count / lMax * 100) + '%' }"></div>
              <div class="chart-label">{{ formatShortDate(item.date) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px;">
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.chart.feedbackTrend') }}</strong></template>
          <div class="chart">
            <div v-for="item in feedbackTrend" :key="item.date" class="chart-col">
              <div class="chart-value">{{ item.count }}</div>
              <div class="chart-bar bar-orange" :style="{ height: (item.count / fbMax * 100) + '%' }"></div>
              <div class="chart-label">{{ formatShortDate(item.date) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.chart.operations') }}</strong></template>
          <div class="chart">
            <div v-for="item in operations" :key="item.date" class="chart-col">
              <div class="chart-value">{{ item.count }}</div>
              <div class="chart-bar bar-purple" :style="{ height: (item.count / opMax * 100) + '%' }"></div>
              <div class="chart-label">{{ formatShortDate(item.date) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 最近活动 + 用户分布 -->
    <el-row :gutter="16" style="margin-top:16px;">
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header>
              <div class="card-header">
                <strong>{{ $t('dashboard.recent.recentFeedback') }}</strong>
                <el-button link type="primary" @click="gotoFeedback">{{ $t('dashboard.recent.viewAll') }}</el-button>
              </div>
            </template>
          <el-empty v-if="!recentFeedback.length" :description="$t('dashboard.recent.emptyFeedback')" :image-size="60" />
          <ul v-else class="recent-list">
            <li v-for="r in recentFeedback" :key="r.id" class="recent-item">
              <el-tag :type="feedbackTypeType(r.type)" size="small">{{ feedbackTypeLabel(r.type) }}</el-tag>
              <span class="recent-title">{{ r.title || r.userName }}</span>
              <span class="recent-meta">{{ r.userName }} · {{ formatGameTime(r.createTime) }}</span>
            </li>
          </ul>
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.recent.recentGames') }}</strong></template>
          <el-empty v-if="!recentGames.length" :description="$t('dashboard.recent.emptyGames')" :image-size="60" />
          <ul v-else class="recent-list">
            <li v-for="g in recentGames" :key="g.id" class="recent-item recent-game-item">
              <el-tag size="small" @click="gotoGame(g.gameCode)" class="game-tag">
                  {{ gameLabel(g.gameCode) }}
                </el-tag>
              <span class="recent-title">{{ $t('dashboard.recent.score') }} <strong>{{ g.score }}</strong></span>
              <span class="recent-meta">{{ formatGameTime(g.createTime) }} · {{ g.playSeconds }}s</span>
            </li>
          </ul>
        </el-card>
      </el-col>
    </el-row>

    <!-- 用户状态分布 + 注册/操作统计(汇总) -->
    <el-row :gutter="16" style="margin-top:16px;">
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.statusDist.title') }}</strong></template>
          <div class="status-list">
            <div v-for="r in statusDist" :key="r.status" class="status-row">
              <el-tag :type="statusType(r.status)">{{ statusLabel(r.status) }}</el-tag>
              <span class="status-count">{{ r.count }} {{ $t('dashboard.statusDist.users') }}</span>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header><strong>{{ $t('dashboard.summary.title') }}</strong></template>
          <ul class="summary-list">
            <li><span>{{ $t('dashboard.stat.totalUsers') }}</span><strong>{{ summary.totalUsers ?? 0 }}</strong></li>
            <li><span>{{ $t('dashboard.stat.activeUsers') }}</span><strong>{{ summary.activeUsers ?? 0 }}</strong></li>
            <li><span>{{ $t('dashboard.stat.disabledUsers') }}</span><strong>{{ summary.disabledUsers ?? 0 }}</strong></li>
            <li><span>{{ $t('dashboard.stat.readonlyUsers') }}</span><strong>{{ summary.readonlyUsers ?? 0 }}</strong></li>
            <li><span>{{ $t('dashboard.stat.totalRoles') }}</span><strong>{{ summary.totalRoles ?? 0 }}</strong></li>
            <li><span>{{ $t('dashboard.stat.totalPermissions') }}</span><strong>{{ summary.totalPermissions ?? 0 }}</strong></li>
          </ul>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.stat-card { text-align: center; }
.stat-card .stat-label { font-size: 13px; color: #6b7280; }
.stat-card .stat-num { font-size: 30px; font-weight: 700; color: #303133; padding: 6px 0; }
.stat-card .stat-sub {
  display: flex; gap: 8px; justify-content: center; align-items: center;
  font-size: var(--fs-xs); color: var(--c-text-3);
  border-top: 1px dashed var(--c-border); padding-top: 6px; margin-top: 4px;
}
.stat-blue   { border-top: 3px solid #2563eb; }
.stat-green  { border-top: 3px solid #67c23a; }
.stat-orange { border-top: 3px solid #e6a23c; }
.stat-purple { border-top: 3px solid #8e44ad; }

.chart {
  display: flex; align-items: flex-end; justify-content: space-around;
  height: 180px; padding: 16px 0;
}
.chart-col {
  display: flex; flex-direction: column; align-items: center; justify-content: flex-end;
  flex: 1; height: 100%; position: relative;
}
.chart-value { font-size: 12px; color: #606266; margin-bottom: 6px; font-variant-numeric: tabular-nums; }
.chart-bar {
  width: 70%; max-width: 36px; min-height: 2px;
  background: linear-gradient(180deg, #2563eb, #6692f1);
  border-radius: var(--r-s) var(--r-s) 0 0;
  transition: height 0.4s;
}
.chart-bar.bar-blue   { background: linear-gradient(180deg, #2563eb, #6692f1); }
.chart-bar.bar-green  { background: linear-gradient(180deg, #67c23a, #95d475); }
.chart-bar.bar-orange { background: linear-gradient(180deg, #e6a23c, #eebe77); }
.chart-bar.bar-purple { background: linear-gradient(180deg, #8e44ad, #b07cc6); }
.chart-label { font-size: 12px; color: #6b7280; margin-top: 6px; }

/* 最近活动列表 */
.recent-list { list-style: none; margin: 0; padding: 0; max-height: 280px; overflow-y: auto; }
.recent-item {
  display: flex; align-items: center; gap: 10px;
  padding: 8px 0; border-bottom: 1px dashed var(--c-border);
  font-size: var(--fs-sm);
}
.recent-item:last-child { border-bottom: none; }
.recent-title {
  flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  color: var(--c-text-1);
}
.recent-meta { font-size: var(--fs-xs); color: var(--c-text-3); white-space: nowrap; }
.recent-game-item .game-tag { cursor: pointer; min-width: 60px; }

/* 用户状态分布 */
.status-list { display: flex; flex-direction: column; gap: 12px; padding: 8px 0; }
.status-row { display: flex; align-items: center; gap: 12px; }
.status-count { font-size: var(--fs-sm); color: var(--c-text-2); font-variant-numeric: tabular-nums; }

/* 汇总列表 */
.summary-list {
  list-style: none; margin: 0; padding: 0;
  display: grid; grid-template-columns: 1fr 1fr; gap: 10px 24px;
}
.summary-list li {
  display: flex; justify-content: space-between; align-items: baseline;
  padding: 6px 0; border-bottom: 1px dashed var(--c-border);
  font-size: var(--fs-sm); color: var(--c-text-2);
}
.summary-list li strong {
  font-size: 18px; color: var(--c-text-1); font-variant-numeric: tabular-nums;
}

.card-header {
  display: flex; justify-content: space-between; align-items: center;
}
</style>