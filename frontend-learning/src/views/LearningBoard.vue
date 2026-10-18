<script setup>
// 学习看板 — 重设计(更直观,更现代)
// 管理员视角: KPI 大卡 + 学科横向对比 + 偏好分布 + 实时活动 + 学员榜 + 鼓励
// 普通用户视角: 个人进度环 + 里程碑 + 鼓励
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ArrowRight, Document, Medal, Reading, Sunny } from '@element-plus/icons-vue'
import { getBoard, getRecentProgress } from '../api/learning'
import { useSubjectStore } from '../store/subject'

const router = useRouter()
const subjectStore = useSubjectStore()
const { t } = useI18n()

const loading = ref(false)
const board = ref(null)
const recentEvents = ref([])
const keyword = ref('')
const subjectId = ref(null)

const isAdmin = computed(() => board.value?.scope === 'ALL')
const myRow = computed(() => (board.value?.rows || []).find(r => r.self) || null)
const subjectStats = computed(() => (board.value?.subjects || []).slice().sort((a, b) => b.avgPercent - a.avgPercent))
const preferenceStats = computed(() => (board.value?.preferenceStats || []).filter(p => p.userCount > 0))

const rows = computed(() => {
  const list = board.value?.rows || []
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(r =>
    (r.username || '').toLowerCase().includes(kw) ||
    (r.email || '').toLowerCase().includes(kw))
})

const subjectName = computed(() => {
  if (subjectId.value == null) return t('learningBoard.subject.all')
  return subjectStats.value.find(s => s.subjectId === subjectId.value)?.subjectName || t('learningBoard.subject.all')
})

// 进度条颜色梯度(< 30 橙, 30-70 蓝, >= 70 绿)
const progressColor = (p) => (p >= 70 ? '#16a34a' : p >= 30 ? '#2563eb' : '#f59e0b')

// 排行前 3 名特殊徽章
const rankClass = (i) => (i === 0 ? 'gold' : i === 1 ? 'silver' : i === 2 ? 'bronze' : '')
const rankLabel = (i) => (i === 0 ? '🥇' : i === 1 ? '🥈' : i === 2 ? '🥉' : `#${i + 1}`)

// 头像首字母
const avatar = (name) => (name || '?').charAt(0).toUpperCase()
const avatarColor = (roles) => (roles?.includes('ADMIN') ? '#c22b2b' : '#15803d')

// 鼓励文案按当前完成度智能切换
const encouragement = computed(() => {
  const p = myRow.value?.percent || 0
  if (p === 0) return { icon: Sunny, type: 'start', key: 'learningBoard.tips.start' }
  if (p < 30) return { icon: Reading, type: 'pace', key: 'learningBoard.tips.pace' }
  if (p < 70) return { icon: Medal, type: 'practice', key: 'learningBoard.tips.practice' }
  return { icon: Medal, type: 'challenge', key: 'learningBoard.tips.challenge' }
})

// 里程碑(三个卡片):已学 / 待学 / 总时长
const milestones = computed(() => {
  const r = myRow.value
  if (!r) return []
  const learned = r.learnedLessons || 0
  const total = r.totalLessons || 0
  const remaining = Math.max(0, total - learned)
  return [
    { key: 'learningBoard.self.learned', value: learned, suffix: t('learningBoard.self.lessons'), color: 'green', icon: '✓' },
    { key: 'learningBoard.self.remaining', value: remaining, suffix: t('learningBoard.self.lessons'), color: 'blue', icon: '⋯' },
    { key: 'learningBoard.self.lastLearned', value: r.lastLearnedAt || t('learningBoard.notStarted'), suffix: '', color: 'orange', icon: '⏱' }
  ]
})

const displayRole = (roles) => {
  const r = (roles || '').split(',').filter(Boolean)
  if (r.includes('ADMIN')) return t('learningBoard.role.admin')
  return t('learningBoard.role.user')
}

// 相对时间(短)
const fmtAgo = (iso) => {
  if (!iso) return '—'
  const d = typeof iso === 'string' ? new Date(iso) : iso
  if (Number.isNaN(d.getTime())) return String(iso)
  const diff = Date.now() - d.getTime()
  const min = Math.floor(diff / 60000)
  if (min < 1) return t('gameHistory.justNow')
  if (min < 60) return t('gameHistory.minutesAgo', { n: min })
  const h = Math.floor(min / 60)
  if (h < 24) return t('gameHistory.hoursAgo', { n: h })
  const day = Math.floor(h / 24)
  if (day < 30) return t('gameHistory.daysAgo', { n: day })
  return d.toLocaleDateString()
}

const load = async () => {
  loading.value = true
  try {
    const res = await getBoard(subjectId.value)
    board.value = res.data
    if (isAdmin.value) {
      const rr = await getRecentProgress(8)
      recentEvents.value = rr.data || []
    }
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}
watch(subjectId, load)
watch(() => subjectStore.preferredSubjectId, (sid) => {
  const next = sid ?? null
  if (next !== subjectId.value) subjectId.value = next
})

onMounted(async () => {
  if (!subjectStore.loaded) await subjectStore.load()
  if (subjectStore.preferredSubjectId) subjectId.value = subjectStore.preferredSubjectId
  await load()
})
</script>

<template>
  <div class="lb-page" v-loading="loading">

    <!-- ==================== ① 管理员视角:全员看板 ==================== -->
    <template v-if="isAdmin">
      <!-- Hero / 头部:简洁标题 + 搜索框(原来太花) -->
      <header class="lb-hero">
        <div class="lb-hero-left">
          <h1>
            <span class="hero-emoji">📊</span>
            {{ $t('learningBoard.hero.title') }}
            <small class="hero-sub">{{ $t('learningBoard.hero.adminSub', { subject: subjectName }) }}</small>
          </h1>
          <p class="hero-desc">{{ $t('learningBoard.hero.adminDesc') }}</p>
        </div>
        <div class="lb-hero-right">
          <el-input
            v-model="keyword"
            :placeholder="$t('learningBoard.search.placeholder')"
            clearable
            size="large"
            class="search-box"
          >
            <template #prefix><el-icon><Document /></el-icon></template>
          </el-input>
        </div>
      </header>

      <!-- KPI 大卡: 4 张差异化设计 -->
      <section class="kpi-row">
        <div class="kpi-card kpi-blue">
          <div class="kpi-icon"><el-icon><Reading /></el-icon></div>
          <div class="kpi-body">
            <div class="kpi-label">{{ $t('learningBoard.kpi.totalUsers') }}</div>
            <div class="kpi-num">{{ board?.totalUsers || 0 }}</div>
            <div class="kpi-sub">
              <span class="trend-up">{{ $t('learningBoard.kpi.activeUsers') }} {{ board?.activeUsers || 0 }} {{ $t('learningBoard.kpi.unitPeople') }}</span>
            </div>
          </div>
        </div>
        <div class="kpi-card kpi-green">
          <div class="kpi-icon"><el-icon><Medal /></el-icon></div>
          <div class="kpi-body">
            <div class="kpi-label">{{ $t('learningBoard.kpi.avgPercent') }}</div>
            <div class="kpi-num">{{ board?.avgPercent || 0 }}<small>%</small></div>
            <div class="kpi-sub">
              <div class="mini-bar"><i :style="{ width: (board?.avgPercent || 0) + '%', background: progressColor(board?.avgPercent || 0) }"></i></div>
            </div>
          </div>
        </div>
        <div class="kpi-card kpi-orange">
          <div class="kpi-icon"><el-icon><Document /></el-icon></div>
          <div class="kpi-body">
            <div class="kpi-label">{{ $t('learningBoard.kpi.totalLessons') }}</div>
            <div class="kpi-num">{{ board?.totalLessons || 0 }}</div>
            <div class="kpi-sub">{{ $t('learningBoard.kpi.unitLesson') }}</div>
          </div>
        </div>
        <div class="kpi-card kpi-purple">
          <div class="kpi-icon"><el-icon><Sunny /></el-icon></div>
          <div class="kpi-body">
            <div class="kpi-label">{{ $t('learningBoard.kpi.topPercent') }}</div>
            <div class="kpi-num">{{ board?.maxPercent || 0 }}<small>%</small></div>
            <div class="kpi-sub">{{ $t('learningBoard.kpi.topHint') }}</div>
          </div>
        </div>
      </section>

      <!-- 学科完成度:横向条形图(更直观对比) -->
      <section v-if="subjectStats.length" class="section subject-section">
        <h2 class="section-title">
          <span class="title-emoji">📚</span>
          {{ $t('learningBoard.subject.title') }}
          <small class="title-sub">{{ $t('learningBoard.subject.subtitle', { n: subjectStats.length }) }}</small>
        </h2>
        <div class="subject-bar-list">
          <div
            v-for="(s, i) in subjectStats"
            :key="s.subjectId"
            class="subject-row"
            :class="{ active: subjectId === s.subjectId }"
          >
            <span class="rank-num">{{ i + 1 }}</span>
            <span class="subject-name">{{ s.subjectName }}</span>
            <div class="bar-track">
              <div
                class="bar-fill"
                :style="{
                  width: s.avgPercent + '%',
                  background: progressColor(s.avgPercent)
                }"
              ></div>
            </div>
            <span class="percent" :style="{ color: progressColor(s.avgPercent) }">{{ s.avgPercent }}%</span>
            <span class="meta muted">{{ s.totalLessons }} {{ $t('learningBoard.kpi.unitLesson') }}</span>
          </div>
        </div>
      </section>

      <!-- 偏好分布 + 实时活动: 双栏 -->
      <section class="row-2col">
        <!-- 学习偏好分布:堆叠 bar + 标签 -->
        <div v-if="preferenceStats.length" class="section pref-section">
          <h2 class="section-title">
            <span class="title-emoji">🎯</span>
            {{ $t('learningBoard.pref.title') }}
          </h2>
          <div class="pref-stack">
            <div class="pref-bar-track">
              <div
                v-for="p in preferenceStats"
                :key="p.subjectId"
                class="pref-segment"
                :style="{
                  width: (p.userCount / (board?.totalUsers || 1) * 100) + '%',
                  background: ['#2563eb', '#16a34a', '#f59e0b', '#8e44ad', '#ec4899', '#06b6d4'][preferenceStats.indexOf(p) % 6]
                }"
                :title="`${p.subjectName}: ${p.userCount} 人`"
              ></div>
            </div>
            <ul class="pref-legend">
              <li v-for="(p, i) in preferenceStats" :key="p.subjectId">
                <span class="dot" :style="{ background: ['#2563eb', '#16a34a', '#f59e0b', '#8e44ad', '#ec4899', '#06b6d4'][i % 6] }"></span>
                <span class="dot-name">{{ p.subjectName }}</span>
                <strong class="dot-num">{{ p.userCount }}</strong>
              </li>
            </ul>
          </div>
        </div>

        <!-- 实时活动流(管理员专属) -->
        <div v-if="recentEvents.length" class="section activity-section">
          <h2 class="section-title">
            <span class="title-emoji">⚡</span>
            {{ $t('learningBoard.activity.title') }}
          </h2>
          <ul class="activity-list">
            <li v-for="e in recentEvents" :key="e.lessonId + '-' + e.userId + '-' + e.learnedAt" class="activity-item">
              <span class="activity-avatar" :style="{ background: avatarColor(e.username === 'admin' ? 'ADMIN' : '') }">
                {{ avatar(e.username) }}
              </span>
              <div class="activity-content">
                <div class="activity-line">
                  <strong>{{ e.username }}</strong>
                  <span class="muted">{{ $t('learningBoard.activity.completed') }}</span>
                  <em>{{ e.lessonTitle }}</em>
                </div>
                <div class="activity-meta">
                  <el-tag size="small" effect="plain" round>{{ e.subjectName }}</el-tag>
                  <span class="muted">{{ $t('learningBoard.activity.chapter') }} {{ e.chapterNo }}</span>
                  <span class="muted">· {{ fmtAgo(e.learnedAt) }}</span>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </section>

      <!-- 学员榜: 前 3 名高亮 -->
      <section class="section rank-section">
        <div class="section-head">
          <h2 class="section-title">
            <span class="title-emoji">🏆</span>
            {{ $t('learningBoard.table.title') }}
            <small class="title-sub">{{ $t('learningBoard.table.headSub', { n: rows.length }) }}</small>
          </h2>
          <span class="head-hint">{{ $t('learningBoard.table.hint', { p: board?.maxPercent || 0 }) }}</span>
        </div>

        <div v-if="!rows.length" class="empty-state">
          <span class="empty-emoji">🔍</span>
          <p>{{ $t('learningBoard.table.noMatch') }}</p>
        </div>
        <table v-else class="rank-table">
          <thead>
            <tr>
              <th class="col-rank">{{ $t('learningBoard.table.rank') }}</th>
              <th>{{ $t('learningBoard.table.student') }}</th>
              <th class="col-progress">{{ $t('learningBoard.table.progress') }}</th>
              <th class="col-stat">{{ $t('learningBoard.table.learned') }}</th>
              <th class="col-time">{{ $t('learningBoard.table.lastLearned') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in rows" :key="r.userId" :class="{ me: r.self, podium: i < 3 }">
              <td class="col-rank">
                <span class="rank-badge" :class="rankClass(i)">
                  <template v-if="i < 3">{{ rankLabel(i) }}</template>
                  <template v-else>{{ i + 1 }}</template>
                </span>
              </td>
              <td>
                <div class="who">
                  <span class="who-avatar" :style="{ background: avatarColor(r.roles) }">{{ avatar(r.username) }}</span>
                  <div class="who-info">
                    <b>{{ r.username }}<em v-if="r.self" class="me-tag">{{ $t('learningBoard.table.me') }}</em></b>
                    <small class="muted">
                      <el-tag size="small" :type="r.roles?.includes('ADMIN') ? 'danger' : 'success'" effect="plain" round class="role-tag">
                        {{ displayRole(r.roles) }}
                      </el-tag>
                      <template v-if="r.preferredSubject">
                        <span class="muted"> · </span>
                        <span class="pref-tag">{{ r.preferredSubject }}</span>
                      </template>
                    </small>
                  </div>
                </div>
              </td>
              <td class="col-progress">
                <div class="progress-cell">
                  <div class="bar-track"><div class="bar-fill" :style="{ width: r.percent + '%', background: progressColor(r.percent) }"></div></div>
                  <strong class="percent" :style="{ color: progressColor(r.percent) }">{{ r.percent }}%</strong>
                </div>
              </td>
              <td class="col-stat">
                <strong>{{ r.learnedLessons }}</strong><small class="muted"> / {{ r.totalLessons }}</small>
              </td>
              <td class="col-time muted">{{ r.lastLearnedAt ? fmtAgo(r.lastLearnedAt) : $t('learningBoard.notStarted') }}</td>
            </tr>
          </tbody>
        </table>
      </section>
    </template>

    <!-- ==================== ② 普通用户视角: 个人进度 ==================== -->
    <template v-else>
      <!-- Hero: 个人化欢迎 + 引导 -->
      <header class="lb-hero self-hero">
        <div class="lb-hero-left">
          <h1>
            <span class="hero-emoji">🎓</span>
            {{ $t('learningBoard.self.title') }}
            <small class="hero-sub">{{ subjectName }}</small>
          </h1>
          <p class="hero-desc">{{ $t('learningBoard.self.desc', { subject: subjectName }) }}</p>
        </div>
      </header>

      <!-- 主进度环 + 鼓励: 大卡片视觉冲击 -->
      <section class="self-main-card">
        <div class="ring-wrap">
          <svg class="ring-svg" viewBox="0 0 160 160">
            <circle cx="80" cy="80" r="68" fill="none" stroke="#eef0f6" stroke-width="14" />
            <circle
              cx="80" cy="80" r="68" fill="none"
              :stroke="progressColor(myRow?.percent || 0)"
              stroke-width="14"
              stroke-linecap="round"
              :stroke-dasharray="`${(myRow?.percent || 0) * 4.27} 427`"
              transform="rotate(-90 80 80)"
            />
          </svg>
          <div class="ring-content">
            <div class="ring-pct" :style="{ color: progressColor(myRow?.percent || 0) }">
              {{ myRow?.percent || 0 }}<small>%</small>
            </div>
            <div class="ring-label">{{ $t('learningBoard.self.percent') }}</div>
          </div>
        </div>

        <div class="self-content">
          <component
            :is="encouragement.icon"
            class="enc-icon"
            :style="{ color: progressColor(myRow?.percent || 0) }"
          />
          <p class="enc-text">{{ $t(encouragement.key) }}</p>
          <el-button type="primary" round size="large" @click="router.push('/learning')">
            {{ $t('learningBoard.self.continue') }} <el-icon class="el-icon--right"><ArrowRight /></el-icon>
          </el-button>
        </div>
      </section>

      <!-- 三张里程碑: 已学 / 待学 / 最近 -->
      <section class="milestone-row">
        <div v-for="(m, i) in milestones" :key="i" class="milestone-card" :class="'ms-' + m.color">
          <div class="ms-icon">{{ m.icon }}</div>
          <div class="ms-label">{{ $t(m.key) }}</div>
          <div class="ms-value" :class="{ large: typeof m.value === 'number' && m.value >= 100 }">
            {{ m.value }}<small v-if="m.suffix && typeof m.value === 'number'"> {{ m.suffix }}</small>
          </div>
        </div>
      </section>

      <!-- 个人最近活动 -->
      <section v-if="recentEvents.length" class="section my-activity">
        <h2 class="section-title">
          <span class="title-emoji">📝</span>
          {{ $t('learningBoard.activity.myTitle') }}
        </h2>
        <ul class="activity-list">
          <li v-for="e in recentEvents.slice(0, 5)" :key="e.lessonId + '-my-' + e.learnedAt" class="activity-item">
            <span class="activity-avatar" :style="{ background: avatarColor('USER') }">{{ avatar(e.username) }}</span>
            <div class="activity-content">
              <div class="activity-line">
                <strong>{{ e.lessonTitle }}</strong>
              </div>
              <div class="activity-meta">
                <el-tag size="small" effect="plain" round>{{ e.subjectName }}</el-tag>
                <span class="muted">{{ $t('learningBoard.activity.chapter') }} {{ e.chapterNo }}</span>
                <span class="muted">· {{ fmtAgo(e.learnedAt) }}</span>
              </div>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>
/* ============ 基础布局(与 LearningCenter / Dashboard 一致:无 max-width,撑满容器) ============ */
.lb-page { padding: 24px 32px 40px; }

/* ============ Hero (管理员/普通用户通用) ============ */
.lb-hero {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 20px; margin-bottom: 20px;
}
.lb-hero h1 {
  margin: 0; font-size: 24px; font-weight: 700; color: var(--c-text-1);
  display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap;
}
.hero-emoji { font-size: 28px; }
.hero-sub {
  font-size: 13px; color: var(--c-text-3); font-weight: 400;
  background: var(--c-fill-2, #f1f5f9); padding: 2px 10px; border-radius: 99px;
}
.hero-desc { margin: 8px 0 0; color: var(--c-text-3); font-size: 14px; }
.lb-hero-right { width: 320px; flex: none; }
.search-box :deep(.el-input__wrapper) { border-radius: 12px; }

/* ============ KPI 大卡 ============ */
.kpi-row {
  display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px;
  margin-bottom: 24px;
}
.kpi-card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l, 14px);
  padding: 18px 20px; display: flex; gap: 14px; align-items: flex-start;
  box-shadow: 0 2px 8px rgba(20, 30, 60, .04);
  transition: transform .2s, box-shadow .2s;
}
.kpi-card:hover { transform: translateY(-2px); box-shadow: 0 8px 20px rgba(20, 30, 60, .08); }
.kpi-icon {
  width: 44px; height: 44px; border-radius: 12px;
  display: flex; align-items: center; justify-content: center; flex: none;
  font-size: 22px; color: #fff;
}
.kpi-blue   { border-top-color: #2563eb; } .kpi-blue .kpi-icon   { background: linear-gradient(135deg, #2563eb, #3b82f6); }
.kpi-green  { border-top-color: #16a34a; } .kpi-green .kpi-icon  { background: linear-gradient(135deg, #16a34a, #22c55e); }
.kpi-orange { border-top-color: #f59e0b; } .kpi-orange .kpi-icon { background: linear-gradient(135deg, #f59e0b, #fb923c); }
.kpi-purple { border-top-color: #8e44ad; } .kpi-purple .kpi-icon { background: linear-gradient(135deg, #8e44ad, #a855f7); }

.kpi-body { flex: 1; min-width: 0; }
.kpi-label { color: var(--c-text-3); font-size: 12px; margin-bottom: 4px; }
.kpi-num { font-size: 32px; font-weight: 800; color: var(--c-text-1); line-height: 1.1; font-variant-numeric: tabular-nums; }
.kpi-num small { font-size: 16px; color: var(--c-text-3); margin-left: 2px; font-weight: 600; }
.kpi-sub { margin-top: 6px; font-size: 12px; color: var(--c-text-3); display: flex; align-items: center; gap: 6px; }
.trend-up { color: #16a34a; font-weight: 600; }

/* ============ Section 通用 ============ */
.section { margin-bottom: 24px; }
.section-title {
  font-size: 16px; font-weight: 700; color: var(--c-text-1);
  margin: 0 0 14px; display: flex; align-items: baseline; gap: 8px; flex-wrap: wrap;
}
.title-emoji { font-size: 18px; }
.title-sub { font-size: 12px; color: var(--c-text-3); font-weight: 400; margin-left: 4px; }

/* ============ 学科完成度 横向条形图 ============ */
.subject-section, .pref-section, .activity-section {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l);
  padding: 18px 20px; box-shadow: 0 2px 8px rgba(20, 30, 60, .04);
}
.subject-bar-list { display: flex; flex-direction: column; gap: 10px; }
.subject-row {
  display: grid;
  grid-template-columns: 28px 100px 1fr 60px 90px;
  gap: 12px; align-items: center;
  padding: 10px 12px; border-radius: 10px;
  transition: background .15s;
}
.subject-row:hover { background: var(--c-fill-2, #f8fafc); }
.subject-row.active { background: linear-gradient(90deg, rgba(37, 99, 235, .08), transparent); border-left: 3px solid #2563eb; padding-left: 9px; }
.rank-num { color: var(--c-text-3); font-weight: 700; font-size: 13px; text-align: center; }
.subject-name { color: var(--c-text-1); font-weight: 600; font-size: 14px; }
.bar-track {
  height: 10px; background: var(--c-fill-2, #f1f5f9); border-radius: 99px; overflow: hidden;
}
.bar-fill {
  height: 100%; border-radius: 99px; transition: width .5s ease;
  box-shadow: 0 1px 4px rgba(0, 0, 0, .08) inset;
}
.percent { font-weight: 700; font-size: 15px; text-align: right; font-variant-numeric: tabular-nums; }
.meta { font-size: 12px; text-align: right; }
.muted { color: var(--c-text-3); }

/* ============ 偏好分布 堆叠 bar ============ */
.row-2col { display: grid; grid-template-columns: 1fr 1.4fr; gap: 16px; }
.pref-section { display: flex; flex-direction: column; }
.pref-stack { display: flex; flex-direction: column; gap: 14px; }
.pref-bar-track {
  display: flex; height: 28px; border-radius: 99px; overflow: hidden;
  background: var(--c-fill-2, #f1f5f9);
}
.pref-segment { height: 100%; transition: width .5s; }
.pref-legend { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; }
.pref-legend li { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.pref-legend .dot { width: 10px; height: 10px; border-radius: 50%; flex: none; }
.pref-legend .dot-name { flex: 1; color: var(--c-text-2); }
.pref-legend .dot-num { color: var(--c-text-1); font-variant-numeric: tabular-nums; }

/* ============ 实时活动流 ============ */
.activity-list { list-style: none; padding: 0; margin: 0; max-height: 320px; overflow-y: auto; }
.activity-item {
  display: flex; gap: 12px; padding: 10px 0;
  border-bottom: 1px dashed var(--c-border);
}
.activity-item:last-child { border-bottom: none; }
.activity-avatar {
  width: 32px; height: 32px; border-radius: 50%; flex: none;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 600; font-size: 13px;
}
.activity-content { flex: 1; min-width: 0; }
.activity-line { font-size: 13px; line-height: 1.4; }
.activity-line em { font-style: normal; font-weight: 600; color: var(--c-text-1); }
.activity-line .muted { margin: 0 4px; }
.activity-meta { display: flex; align-items: center; gap: 8px; margin-top: 4px; font-size: 11px; }

/* ============ 学员榜 ============ */
.rank-section { padding: 0; }
.section-head {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 14px;
  padding: 0 4px;
}
.head-hint { font-size: 12px; color: var(--c-text-3); }
.rank-table {
  width: 100%; border-collapse: collapse;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l);
  overflow: hidden; box-shadow: 0 2px 8px rgba(20, 30, 60, .04);
}
.rank-table th {
  text-align: left; padding: 12px 16px; background: linear-gradient(180deg, #f8fafd, #f4f6fa);
  color: var(--c-text-3); font-weight: 600; font-size: 12px;
  border-bottom: 1px solid var(--c-border);
}
.rank-table td { padding: 14px 16px; border-bottom: 1px solid #f4f6fa; vertical-align: middle; }
.rank-table tr:last-child td { border-bottom: none; }
.rank-table tbody tr { transition: background .15s; }
.rank-table tbody tr:hover { background: #fafbfe; }
.rank-table tr.me { background: #f0f7ff; }
.rank-table tr.me:hover { background: #e3effa; }
.rank-table tr.podium { font-weight: 500; }

.col-rank { width: 70px; text-align: center; }
.col-progress { width: 240px; }
.col-stat { width: 90px; }
.col-time { width: 130px; }

.rank-badge {
  display: inline-flex; min-width: 32px; height: 32px;
  align-items: center; justify-content: center;
  border-radius: 50%; font-weight: 700; font-size: 18px;
}
.rank-badge.gold { background: linear-gradient(135deg, #fbbf24, #f59e0b); color: #fff; box-shadow: 0 4px 8px rgba(245, 158, 11, .3); }
.rank-badge.silver { background: linear-gradient(135deg, #cbd5e1, #94a3b8); color: #fff; box-shadow: 0 4px 8px rgba(148, 163, 184, .3); }
.rank-badge.bronze { background: linear-gradient(135deg, #d97706, #b45309); color: #fff; box-shadow: 0 4px 8px rgba(180, 83, 9, .3); }

.who { display: flex; align-items: center; gap: 10px; }
.who-avatar {
  width: 36px; height: 36px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-weight: 600; font-size: 14px; flex: none;
}
.who-info { display: flex; flex-direction: column; gap: 3px; line-height: 1.2; }
.who-info b { font-size: 14px; color: var(--c-text-1); display: flex; align-items: center; gap: 6px; }
.me-tag { font-style: normal; font-size: 10px; background: #2563eb; color: #fff; padding: 1px 6px; border-radius: 99px; }
.who-info small { font-size: 11px; color: var(--c-text-3); display: flex; align-items: center; gap: 4px; flex-wrap: wrap; }
.role-tag { font-size: 10px !important; height: 18px !important; padding: 0 6px !important; }
.pref-tag { font-size: 11px; }

.progress-cell { display: flex; align-items: center; gap: 10px; }
.progress-cell .bar-track { flex: 1; height: 8px; }
.progress-cell .percent { width: 44px; font-size: 13px; }

.empty-state {
  text-align: center; padding: 48px 20px;
  background: #fff; border: 1px dashed var(--c-border); border-radius: var(--r-l);
  color: var(--c-text-3);
}
.empty-emoji { font-size: 40px; display: block; margin-bottom: 8px; }

/* ============ 普通用户视图 ============ */
.self-hero { background: linear-gradient(120deg, #f0f6ff, #faf5ff 60%, #fff); padding: 18px 22px; border-radius: var(--r-l); border: 1px solid #e8edf9; margin-bottom: 20px; }

.self-main-card {
  display: grid; grid-template-columns: auto 1fr; gap: 32px; align-items: center;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l);
  padding: 28px 36px; box-shadow: 0 4px 16px rgba(20, 30, 60, .05);
  margin-bottom: 20px;
}
.ring-wrap { position: relative; width: 160px; height: 160px; flex: none; }
.ring-svg { width: 100%; height: 100%; transform: rotate(0); }
.ring-content {
  position: absolute; inset: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.ring-pct { font-size: 44px; font-weight: 800; line-height: 1; font-variant-numeric: tabular-nums; }
.ring-pct small { font-size: 20px; margin-left: 2px; opacity: .6; }
.ring-label { color: var(--c-text-3); font-size: 13px; margin-top: 4px; }

.self-content { display: flex; flex-direction: column; align-items: flex-start; gap: 12px; }
.enc-icon { font-size: 36px; }
.enc-text { font-size: 16px; color: var(--c-text-1); margin: 0; line-height: 1.6; max-width: 480px; }

/* 里程碑 3 卡 */
.milestone-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; margin-bottom: 24px; }
.milestone-card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l);
  padding: 18px 22px;
  display: flex; flex-direction: column; gap: 4px;
  box-shadow: 0 2px 6px rgba(20, 30, 60, .04);
  border-top: 3px solid var(--c-border);
  transition: transform .15s;
}
.milestone-card:hover { transform: translateY(-2px); }
.ms-green  { border-top-color: #16a34a; } .ms-green .ms-icon  { color: #16a34a; }
.ms-blue   { border-top-color: #2563eb; } .ms-blue .ms-icon   { color: #2563eb; }
.ms-orange { border-top-color: #f59e0b; } .ms-orange .ms-icon { color: #f59e0b; }
.ms-icon { font-size: 22px; line-height: 1; }
.ms-label { color: var(--c-text-3); font-size: 12px; }
.ms-value { font-size: 24px; font-weight: 700; color: var(--c-text-1); line-height: 1.2; }
.ms-value.large { color: var(--c-primary, #2563eb); }
.ms-value small { font-size: 13px; color: var(--c-text-3); font-weight: 400; }

.my-activity {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l);
  padding: 18px 20px; box-shadow: 0 2px 6px rgba(20, 30, 60, .04);
}

/* ============ 移动端适配 ============ */
@media (max-width: 1024px) {
  .row-2col { grid-template-columns: 1fr; }
  .kpi-row { grid-template-columns: repeat(2, 1fr); }
}
@media (max-width: 768px) {
  .lb-page { padding: 16px 14px 36px; }
  .lb-hero { flex-direction: column; align-items: stretch; }
  .lb-hero-right { width: 100%; }
  .kpi-row { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .kpi-card { padding: 14px; gap: 10px; }
  .kpi-num { font-size: 24px; }
  .kpi-icon { width: 36px; height: 36px; font-size: 18px; }
  .self-main-card { grid-template-columns: 1fr; text-align: center; padding: 22px; }
  .self-content { align-items: center; }
  .ring-wrap { margin: 0 auto; }
  .milestone-row { grid-template-columns: 1fr; gap: 10px; }
  .rank-table { font-size: 13px; min-width: 580px; }
  .rank-section { overflow-x: auto; }
  .subject-row { grid-template-columns: 24px 80px 1fr 50px; font-size: 12px; }
  .subject-row .meta { display: none; }
}
</style>