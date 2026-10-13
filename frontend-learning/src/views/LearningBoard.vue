<script setup>
// 学习看板 — 管理员与普通用户看到的维度不同:
// - 管理员: 全部用户的学习进度 + 学习偏好分布/偏好语言,可搜索
// - 普通用户: 当前语言(头像旁选择框记忆的语言)的个人学习进度
// - 语言维度完全跟随头部"学习语言"选择框,页内不再提供语言选择(节省显示空间)
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getBoard } from '../api/learning'
import { useSubjectStore } from '../store/subject'

const router = useRouter()
const subjectStore = useSubjectStore()
const { t } = useI18n()

const loading = ref(false)
const board = ref(null)
const keyword = ref('')
const subjectId = ref(null)     // 跟随头部"学习语言"选择(存储偏好)

const isAdmin = computed(() => board.value?.scope === 'ALL')
const myRow = computed(() => (board.value?.rows || []).find(r => r.self) || null)
const subjectStats = computed(() => board.value?.subjects || [])
const preferenceStats = computed(() =>
  (board.value?.preferenceStats || []).filter(p => p.userCount > 0))
const subjectName = computed(() => {
  if (subjectId.value == null) return t('learningBoard.subject.all')
  return subjectStats.value.find(s => s.subjectId === subjectId.value)?.subjectName || t('learningBoard.subject.all')
})

// 搜索过滤(仅管理员视图使用)
const rows = computed(() => {
  const list = board.value?.rows || []
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list
  return list.filter(r =>
    (r.username || '').toLowerCase().includes(kw) ||
    (r.email || '').toLowerCase().includes(kw))
})

const load = async () => {
  loading.value = true
  try {
    const res = await getBoard(subjectId.value)
    board.value = res.data
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}
watch(subjectId, load)
// 头部语言选择框切换 -> 看板维度同步
watch(() => subjectStore.preferredSubjectId, (sid) => {
  const next = sid ?? null
  if (next !== subjectId.value) subjectId.value = next
})

const barColor = (p) => (p >= 80 ? '#16a34a' : p >= 40 ? '#2563eb' : '#f59e0b')
const rankClass = (i) => (i === 0 ? 'gold' : i === 1 ? 'silver' : i === 2 ? 'bronze' : '')
const displayRole = (roles) => {
  const r = (roles || '').split(',').filter(Boolean)
  if (r.includes('ADMIN')) return t('learningBoard.role.admin')
  return t('learningBoard.role.user')
}

onMounted(async () => {
  // 默认维度 = 用户正在学习的语言(偏好),让"看板"与学习内容保持同一语言
  if (!subjectStore.loaded) await subjectStore.load()
  if (subjectStore.preferredSubjectId) subjectId.value = subjectStore.preferredSubjectId
  await load()
})
</script>

<template>
  <div class="page" v-loading="loading">

    <!-- ① 管理员: 全员看板 -->
    <template v-if="isAdmin">
      <div class="hero admin">
        <div>
          <h1>{{ $t('learningBoard.hero.title') }} <small>{{ $t('learningBoard.hero.adminSub', { subject: subjectName }) }}</small></h1>
          <p>{{ $t('learningBoard.hero.adminDesc') }}</p>
        </div>
        <el-input
          v-model="keyword"
          :placeholder="$t('learningBoard.search.placeholder')"
          clearable
          class="search"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
      </div>

      <div class="stats">
        <div class="stat">
          <div class="label">{{ $t('learningBoard.kpi.totalUsers') }}</div>
          <div class="num">{{ board?.totalUsers || 0 }}<small>{{ $t('learningBoard.kpi.unitPeople') }}</small></div>
        </div>
        <div class="stat">
          <div class="label">{{ $t('learningBoard.kpi.activeUsers') }}</div>
          <div class="num">{{ board?.activeUsers || 0 }}<small>{{ $t('learningBoard.kpi.unitPeople') }}</small></div>
        </div>
        <div class="stat">
          <div class="label">{{ $t('learningBoard.kpi.avgPercent') }}</div>
          <div class="num">{{ board?.avgPercent || 0 }}<small>%</small></div>
          <div class="bar"><i :style="{ width: (board?.avgPercent || 0) + '%', background: barColor(board?.avgPercent || 0) }"></i></div>
        </div>
        <div class="stat">
          <div class="label">{{ $t('learningBoard.kpi.totalLessons') }}</div>
          <div class="num">{{ board?.totalLessons || 0 }}<small>{{ $t('learningBoard.kpi.unitLesson') }}</small></div>
        </div>
      </div>

      <!-- 各科目(语言)平均完成度对比(纯展示,语言选择在头部) -->
      <div class="subject-stats" v-if="subjectStats.length">
        <div
          v-for="s in subjectStats"
          :key="s.subjectId"
          class="sub-card"
          :class="{ active: subjectId === s.subjectId }"
        >
          <div class="sub-name">{{ s.subjectName }}</div>
          <div class="sub-pct">{{ s.avgPercent }}<small>%</small></div>
          <div class="bar"><i :style="{ width: s.avgPercent + '%', background: barColor(s.avgPercent) }"></i></div>
          <div class="sub-meta">{{ $t('learningBoard.subject.meta', { n: s.totalLessons }) }}</div>
        </div>
      </div>

      <!-- 学习偏好分布:学员们正在学习的语言 -->
      <div class="pref-bar" v-if="preferenceStats.length">
        <span class="pref-title">{{ $t('learningBoard.pref.title') }}</span>
        <el-tag
          v-for="p in preferenceStats"
          :key="p.subjectId"
          size="small"
          effect="plain"
          round
          class="pref-tag"
        >
          {{ $t('learningBoard.pref.item', { subject: p.subjectName, n: p.userCount }) }}
        </el-tag>
        <span class="pref-hint">{{ $t('learningBoard.pref.hint') }}</span>
      </div>

      <div class="card">
        <div class="card-head">
          <h3>{{ $t('learningBoard.table.title') }} <small class="hint-inline">{{ subjectName }}</small></h3>
          <span class="hint">{{ $t('learningBoard.table.hint', { n: rows.length, p: board?.maxPercent || 0 }) }}</span>
        </div>
        <table class="board-table">
          <thead>
            <tr>
              <th style="width:56px">{{ $t('learningBoard.table.rank') }}</th>
              <th>{{ $t('learningBoard.table.student') }}</th>
              <th style="width:110px">{{ $t('learningBoard.table.role') }}</th>
              <th style="width:110px">{{ $t('learningBoard.table.preference') }}</th>
              <th style="width:230px">{{ $t('learningBoard.table.progress') }}</th>
              <th style="width:110px">{{ $t('learningBoard.table.learned') }}</th>
              <th style="width:150px">{{ $t('learningBoard.table.lastLearned') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(r, i) in rows" :key="r.userId" :class="{ me: r.self }">
              <td>
                <span class="rank" :class="rankClass(i)">{{ i + 1 }}</span>
              </td>
              <td>
                <div class="who">
                  <span class="avatar" :style="{ background: r.roles?.includes('ADMIN') ? '#c22b2b' : '#15803d' }">
                    {{ (r.username || '?').charAt(0).toUpperCase() }}
                  </span>
                  <div class="who-info">
                    <b>{{ r.username }}<em v-if="r.self">{{ $t('learningBoard.table.me') }}</em></b>
                    <small>{{ r.email || '—' }}</small>
                  </div>
                </div>
              </td>
              <td>
                <el-tag size="small" :type="r.roles?.includes('ADMIN') ? 'danger' : 'success'" effect="light" round>
                  {{ displayRole(r.roles) }}
                </el-tag>
              </td>
              <td class="muted">
                <el-tag v-if="r.preferredSubject" size="small" effect="plain" round>{{ r.preferredSubject }}</el-tag>
                <template v-else>{{ $t('learningBoard.table.notSet') }}</template>
              </td>
              <td>
                <div class="progress-cell">
                  <div class="bar"><i :style="{ width: r.percent + '%', background: barColor(r.percent) }"></i></div>
                  <span class="pct">{{ r.percent }}%</span>
                </div>
              </td>
              <td class="mono">{{ r.learnedLessons }} / {{ r.totalLessons }}</td>
              <td class="muted">{{ r.lastLearnedAt || $t('learningBoard.notStarted') }}</td>
            </tr>
            <tr v-if="!rows.length">
              <td colspan="7" class="empty">{{ $t('learningBoard.table.noMatch') }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>

    <!-- ② 普通用户: 当前语言的个人学习进度 -->
    <template v-else>
      <div class="hero">
        <div>
          <h1>{{ $t('learningBoard.self.title') }} <small>{{ subjectName }}</small></h1>
          <p>{{ $t('learningBoard.self.desc', { subject: subjectName }) }}</p>
        </div>
        <el-button type="primary" @click="router.push('/learning')">{{ $t('learningBoard.self.goLearn') }} <el-icon><ArrowRight /></el-icon></el-button>
      </div>

      <div class="self-cards">
        <div class="self-main">
          <div class="ring" :style="{ '--p': (myRow?.percent || 0) + '%', '--c': barColor(myRow?.percent || 0) }">
            <div class="ring-inner">
              <b>{{ myRow?.percent || 0 }}<small>%</small></b>
              <span>{{ $t('learningBoard.self.percent') }}</span>
            </div>
          </div>
          <div class="self-meta">
            <div class="row"><span>{{ $t('learningBoard.self.currentLang') }}</span><b>{{ subjectName }}</b></div>
            <div class="row"><span>{{ $t('learningBoard.self.learned') }}</span><b>{{ myRow?.learnedLessons || 0 }} / {{ myRow?.totalLessons || 0 }}</b></div>
            <div class="row"><span>{{ $t('learningBoard.self.lastLearned') }}</span><b>{{ myRow?.lastLearnedAt || $t('learningBoard.notStarted') }}</b></div>
            <el-button type="primary" plain style="margin-top:14px" @click="router.push('/learning')">
              {{ $t('learningBoard.self.continue') }}
            </el-button>
          </div>
        </div>

        <div class="tip-card">
          <h4>{{ $t('learningBoard.tips.title') }}</h4>
          <ul>
            <li v-if="!myRow?.learnedLessons">{{ $t('learningBoard.tips.start') }}</li>
            <li v-else-if="(myRow?.percent || 0) < 30">{{ $t('learningBoard.tips.pace') }}</li>
            <li v-else-if="(myRow?.percent || 0) < 70">{{ $t('learningBoard.tips.practice') }}</li>
            <li v-else>{{ $t('learningBoard.tips.challenge') }}</li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px 40px; }

/* 各科目平均完成度卡片(纯展示,当前语言高亮) */
.subject-stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 12px; margin-bottom: 16px; }
.sub-card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 14px 16px; transition: all .15s; box-shadow: 0 2px 6px rgba(20, 30, 60, .05);
}
.sub-card.active { border-color: var(--c-primary); box-shadow: 0 8px 18px rgba(37, 99, 235, .15); }
.sub-name { color: #64748b; font-size: var(--fs-xs); margin-bottom: 4px; }
.sub-pct { font-size: var(--fs-xl); font-weight: 700; color: #1f2937; line-height: 1.2; }
.sub-pct small { font-size: var(--fs-xs); color: #64748b; margin-left: 2px; }
.sub-card .bar { margin-top: 8px; height: 5px; border-radius: var(--r-full); background: var(--c-border); overflow: hidden; }
.sub-card .bar i { display: block; height: 100%; border-radius: var(--r-full); transition: width .4s ease; }
.sub-meta { margin-top: 6px; color: #64748b; font-size: var(--fs-xs); }
.hint-inline { color: #64748b; font-size: var(--fs-xs); font-weight: 400; margin-left: 6px; }

/* 学习偏好分布 */
.pref-bar {
  display: flex; align-items: center; gap: 8px; flex-wrap: wrap;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 11px 16px; margin-bottom: 16px; box-shadow: 0 2px 6px rgba(20, 30, 60, .05);
}
.pref-title { font-size: var(--fs-xs); color: #64748b; font-weight: 600; margin-right: 4px; }
.pref-tag { flex: none; }
.pref-hint { margin-left: auto; color: #64748b; font-size: var(--fs-xs); }

.hero {
  display: flex; align-items: flex-end; justify-content: space-between; gap: 20px;
  background: linear-gradient(120deg, #eef4ff 0%, #f6f1ff 60%, #fff 100%);
  border: 1px solid #e8edf9; border-radius: var(--r-l); padding: 22px 26px; margin-bottom: 18px;
}
.hero.admin { background: linear-gradient(120deg, #fff1f1 0%, #fdf4ff 55%, #fff 100%); border-color: #f6e2e2; }
.hero h1 { margin: 0 0 6px; font-size: var(--fs-xl); }
.hero h1 small { font-size: var(--fs-xs); color: #64748b; font-weight: 400; margin-left: 8px; }
.hero p { margin: 0; color: #6b7280; font-size: var(--fs-base); }
.search { width: 260px; flex: none; }

.stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; margin-bottom: 18px; }
.stat { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 16px 18px; box-shadow: 0 2px 6px rgba(20, 30, 60, .05); }
.stat .label { color: #64748b; font-size: var(--fs-xs); margin-bottom: 6px; }
.stat .num { font-size: var(--fs-xl); font-weight: 700; color: #1f2937; line-height: 1.2; }
.stat .num small { font-size: var(--fs-xs); color: #64748b; font-weight: 400; margin-left: 3px; }
.bar { height: 6px; border-radius: var(--r-full); background: var(--c-border); overflow: hidden; }
.stat .bar { margin-top: 10px; }
.bar i { display: block; height: 100%; border-radius: var(--r-full); transition: width .4s ease; }

.card { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); box-shadow: 0 2px 6px rgba(20, 30, 60, .05); overflow: hidden; }
.card-head { display: flex; align-items: center; justify-content: space-between; padding: 15px 20px; border-bottom: 1px solid #f1f3f8; }
.card-head h3 { margin: 0; font-size: var(--fs-md); }
.card-head .hint { color: #64748b; font-size: var(--fs-xs); }

.board-table { width: 100%; border-collapse: collapse; font-size: var(--fs-base); }
.board-table th {
  text-align: left; padding: 11px 16px; background: #f8fafd; color: #6b7280;
  font-weight: 500; font-size: var(--fs-xs); border-bottom: 1px solid var(--c-border);
}
.board-table td { padding: 12px 16px; border-bottom: 1px solid #f4f6fa; vertical-align: middle; }
.board-table tr.me { background: #f5f9ff; }
.board-table tr.me:hover { background: #eef5ff; }
.board-table tbody tr:hover { background: #fafbfe; }

.rank { display: inline-flex; width: 24px; height: 24px; align-items: center; justify-content: center; border-radius: 50%; background: #f1f4f9; color: #6b7280; font-size: var(--fs-xs); font-weight: 600; }
.rank.gold { background: linear-gradient(135deg, #b45309, #92400e); color: #fff; }
.rank.silver { background: linear-gradient(135deg, #64748b, #556275); color: #fff; }
.rank.bronze { background: linear-gradient(135deg, #9a6b42, #7d5632); color: #fff; }

.who { display: flex; align-items: center; gap: 10px; }
.avatar { width: 30px; height: 30px; border-radius: 50%; color: #fff; display: inline-flex; align-items: center; justify-content: center; font-size: var(--fs-sm); font-weight: 600; flex: none; }
.who-info { display: flex; flex-direction: column; line-height: 1.3; min-width: 0; }
.who-info b { font-size: var(--fs-base); }
.who-info em { font-style: normal; font-size: var(--fs-xs); color: #2563eb; background: #e8f0ff; border-radius: var(--r-full); padding: 0 6px; margin-left: 6px; }
.who-info small { color: #6b7280; font-size: var(--fs-xs); }

.progress-cell { display: flex; align-items: center; gap: 10px; }
.progress-cell .bar { flex: 1; }
.progress-cell .pct { width: 38px; text-align: right; font-size: var(--fs-xs); color: #374151; }
.mono { font-family: 'JetBrains Mono', Menlo, monospace; font-size: var(--fs-xs); color: #374151; }
.muted { color: #6b7280; font-size: var(--fs-xs); }
.empty { text-align: center; color: #6b7280; padding: 34px 0; }

/* 普通用户视图 */
.self-cards { display: grid; grid-template-columns: 1.35fr 1fr; gap: 16px; }
.self-main {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-l); padding: 26px;
  display: flex; align-items: center; gap: 30px; box-shadow: 0 2px 6px rgba(20, 30, 60, .05);
}
.ring {
  --p: 0%; --c: #2563eb;
  width: 148px; height: 148px; border-radius: 50%; flex: none;
  background: conic-gradient(var(--c) var(--p), var(--c-border) 0);
  display: flex; align-items: center; justify-content: center;
}
.ring-inner {
  width: 116px; height: 116px; border-radius: 50%; background: #fff;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.ring-inner b { font-size: var(--fs-xl); color: #1f2937; line-height: 1; }
.ring-inner b small { font-size: var(--fs-sm); color: #64748b; }
.ring-inner span { font-size: var(--fs-xs); color: #64748b; margin-top: 4px; }
.self-meta { flex: 1; }
.self-meta .row { display: flex; justify-content: space-between; padding: 9px 0; border-bottom: 1px dashed var(--c-border); font-size: var(--fs-base); }
.self-meta .row span { color: #64748b; }
.self-meta .row b { color: #1f2937; font-weight: 600; }

.tip-card { background: linear-gradient(135deg, #f0f6ff, #faf5ff); border: 1px solid #e8edf9; border-radius: var(--r-l); padding: 22px 24px; }
.tip-card h4 { margin: 0 0 12px; font-size: var(--fs-md); }
.tip-card ul { margin: 0; padding-left: 18px; color: #4b5563; font-size: var(--fs-base); line-height: 2; }

/* ============ 移动端(<=768px) ============ */
@media (max-width: 768px) {
  .hero { flex-direction: column; align-items: stretch; gap: 12px; padding: 16px; }
  .search { width: 100%; }
  .subject-stats { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .stats { grid-template-columns: repeat(2, 1fr); gap: 10px; }
  .self-cards { grid-template-columns: 1fr; }
  .self-main { flex-direction: column; text-align: center; padding: 18px; gap: 16px; }
  .self-meta .row { font-size: var(--fs-sm); }
  /* 排行榜表格横向滚动 */
  .card { overflow-x: auto; }
  .board-table { min-width: 620px; }
}
@media (max-width: 400px) {
  .subject-stats { grid-template-columns: 1fr 1fr; }
  .ring { width: 124px; height: 124px; }
  .ring-inner { width: 96px; height: 96px; }
}
</style>
