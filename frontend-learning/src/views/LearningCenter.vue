<script setup>
// 学习中心首页(现代主流学习平台风格)
// - 顶部 KPI + 阶段卡片(每阶段显示章/课时/进度)
// - 科目(语言)维度切换: Java / Python / JavaScript ...(管理员可在学习配置中维护)
// - 底部"社区补充章节": 用户自建章节与子节点,补充课程缺失内容
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { getCurriculum, getProgress } from '../api/learning'
import { useSubjectStore } from '../store/subject'
import { useUserStore } from '../store/user'
import SupplementTree from '../components/SupplementTree.vue'

const router = useRouter()
const subjectStore = useSubjectStore()
const userStore = useUserStore()

const loading = ref(false)
const stages = ref([])
const progress = ref(null)

// 游客可浏览课程;进度/完成记录仅登录用户有
const isGuest = computed(() => !userStore.isLogin)
const goLogin = () => router.push({ path: '/login', query: { redirect: '/learning' } })

// 语言维度完全跟随头部"学习语言"选择框(页内不再提供选择,节省显示空间)
const activeSubjectId = computed(() => subjectStore.preferredSubjectId)
const activeSubject = computed(() => subjectStore.preferredSubject)

const stageGradients = [
  'linear-gradient(135deg, #2563eb, #4f46e5)',    // 阶段 1 入门基础
  'linear-gradient(135deg, #4f46e5, #2563eb)',    // 阶段 2 核心基础
  'linear-gradient(135deg, #f59e0b, #ef4444)',    // 阶段 3 进阶特性
  'linear-gradient(135deg, #0ea5e9, #22c55e)',    // 阶段 4 并发编程
  'linear-gradient(135deg, #8b5cf6, #ec4899)',    // 阶段 5 数据库与 JDBC
  'linear-gradient(135deg, #14b8a6, #0ea5e9)',    // 阶段 6 工程化与设计
  'linear-gradient(135deg, #f97316, #f59e0b)',    // 阶段 7 Web 与框架生态
  'linear-gradient(135deg, #94a3b8, #475569)'     // 阶段 8 综合实战
]
const stageGradient = (no) => stageGradients[(no - 1) % stageGradients.length]

const totalChapters = computed(() => stages.value.reduce((s, st) => s + st.chapters.length, 0))
const totalLessons = computed(() => stages.value.reduce((s, st) => s + st.chapters.reduce((n, c) => n + c.totalLessons, 0), 0))
const learnedCount = computed(() => progress.value?.learnedLessons || 0)

const loadData = async () => {
  loading.value = true
  try {
    const sid = activeSubjectId.value
    // 游客只拉课程树(进度是用户维度数据,未登录不请求)
    const [curriculumRes, progressRes] = await Promise.all([
      getCurriculum(sid),
      isGuest.value ? Promise.resolve({ data: null }) : getProgress(sid)
    ])
    stages.value = curriculumRes.data || []
    progress.value = progressRes.data || null
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}

const loadSubjects = async () => {
  // 语言由 store 提供(头部选择框记忆),这里只需确保偏好加载完成后取数
  if (!subjectStore.loaded) await subjectStore.load()
  await loadData()
}

watch(activeSubjectId, loadData)

const openLesson = (id) => { if (id) router.push(`/learning/lesson/${id}`) }

// 章节卡点击:进入该章第一个未学课时(全学完则进最后一课)
const chapterTarget = (ch) => {
  const next = ch.lessons.find(ls => !ls.learned)
  return (next || ch.lessons[ch.lessons.length - 1] || ch.lessons[0])?.id
}

// 键盘可达:章节卡支持 Enter / 空格 触发
const onChapterKey = (e, ch) => {
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openLesson(chapterTarget(ch)) }
}

// 进入当前未学完的第一个章节
const continueLearning = () => {
  for (const st of stages.value) {
    for (const ch of st.chapters) {
      for (const ls of ch.lessons) {
        if (!ls.learned) { openLesson(ls.id); return }
      }
    }
  }
}

// 阶段统计辅助函数(给模板用)
const stageLearned = (st) => st.chapters.reduce((n, c) => n + c.learnedLessons, 0)
const stageTotal = (st) => st.chapters.reduce((n, c) => n + c.totalLessons, 0)
const stagePercent = (st) => {
  const total = stageTotal(st)
  if (!total) return 0
  return Math.round(stageLearned(st) * 100 / total)
}
const stageComplete = (st) => stageTotal(st) > 0 && stageLearned(st) === stageTotal(st)

// 真实可度量 KPI(替代原先的"∞ 沙箱"假进度条)
const learnedChapters = computed(() => stages.value.reduce(
  (n, st) => n + st.chapters.filter(c => c.totalLessons > 0 && c.learnedLessons === c.totalLessons).length, 0))
const remainLessons = computed(() => Math.max(0, totalLessons.value - learnedCount.value))

// 关键: 组件挂载后加载科目与课程数据
onMounted(loadSubjects)
</script>

<template>
  <div class="page" v-loading="loading">

    <!-- 顶部欢迎 + KPI -->
    <div class="hero">
      <div>
        <h1>{{ isGuest ? '欢迎来到学习中心' : '你好, 学习者' }}<template v-if="activeSubject"> · {{ activeSubject.name }}</template></h1>
        <p v-if="isGuest">全部 {{ totalLessons }} 课时 · {{ totalChapters }} 章节 · 游客可自由浏览,登录后记录你的学习进度。</p>
        <p v-else>已学 {{ learnedCount }} / {{ totalLessons }} 课时 · {{ totalChapters }} 章节 · 跟随阶段式课程一路前行。</p>
      </div>
      <div class="btns">
        <button v-if="isGuest" class="btn primary" @click="goLogin">登录后开始学习 <el-icon><ArrowRight /></el-icon></button>
        <button v-else class="btn primary" @click="continueLearning">继续学习 <el-icon><ArrowRight /></el-icon></button>
      </div>
    </div>

    <div class="stats">
      <div class="stat">
        <div class="label">总进度</div>
        <div class="num">{{ progress?.percent || 0 }}<small>%</small></div>
        <div class="bar"><i :style="{ width: (progress?.percent || 0) + '%' }"></i></div>
      </div>
      <div class="stat">
        <div class="label">已学课时</div>
        <div class="num">{{ learnedCount }}<small> / {{ totalLessons }}</small></div>
        <div class="bar"><i :style="{ width: (progress?.percent || 0) + '%' }"></i></div>
      </div>
      <div class="stat">
        <div class="label">已完成章节</div>
        <div class="num">{{ learnedChapters }}<small> / {{ totalChapters }}</small></div>
        <div class="bar"><i :style="{ width: (totalChapters ? learnedChapters / totalChapters * 100 : 0) + '%' }"></i></div>
      </div>
      <div class="stat">
        <div class="label">剩余课时</div>
        <div class="num">{{ remainLessons }}<small> 节</small></div>
        <div class="bar"><i :style="{ width: (progress?.percent || 0) + '%' }"></i></div>
      </div>
    </div>

    <!-- 阶段卡片 -->
    <div class="stages">
      <div v-for="stage in stages" :key="stage.stageNo" class="stage">
        <div class="stage-head">
          <div class="stage-num" :style="{ background: stageGradient(stage.stageNo) }">
            {{ stage.stageNo }}
          </div>
          <div>
            <div class="stage-title">{{ stage.stageName }}</div>
            <div class="stage-sub">
              {{ stage.chapters.length }} 章 ·
              {{ stage.chapters.reduce((n, c) => n + c.totalLessons, 0) }} 课时
            </div>
          </div>
          <div class="stage-meta">
            <template v-if="progress">
              <span class="tag" :class="{ green: stageComplete(stage) }">
                {{ stageLearned(stage) }} / {{ stageTotal(stage) }}
              </span>
              <span class="pct">{{ stagePercent(stage) }}%</span>
            </template>
          </div>
        </div>

        <div class="chapters">
          <div
            v-for="ch in stage.chapters"
            :key="ch.id"
            class="chapter"
            role="button"
            tabindex="0"
            :class="{ done: ch.learnedLessons === ch.totalLessons && ch.totalLessons > 0 }"
            :aria-label="`第${ch.chapterNo}章 ${ch.title}`"
            @click="openLesson(chapterTarget(ch))"
            @keydown="onChapterKey($event, ch)"
          >
            <div class="ch-title">
              <span class="ch-no">第{{ ch.chapterNo }}章</span>
              <span class="ch-name">{{ ch.title }}</span>
              <el-icon v-if="ch.learnedLessons === ch.totalLessons && ch.totalLessons > 0" color="#22c55e" class="ch-done"><CircleCheckFilled /></el-icon>
            </div>
            <div class="ch-meta">
              <span>{{ ch.lessons.length }} 课时</span>
              <span v-if="ch.estimatedHours">· 约 {{ ch.estimatedHours }} 小时</span>
              <span>· {{ ch.learnedLessons }}/{{ ch.totalLessons }} 已学</span>
            </div>
            <div v-if="ch.totalLessons > 0" class="ch-progress">
              <i :style="{ width: (ch.learnedLessons / ch.totalLessons * 100) + '%' }"></i>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 社区补充章节: 用户自建章节与子节点 -->
    <SupplementTree
      v-if="activeSubjectId"
      class="sup-section"
      :subject-id="activeSubjectId"
      title="社区补充章节"
      root-label="添加补充章节"
    />
  </div>
</template>

<style scoped>
.page { padding:24px 32px; max-width:1200px; margin:0 auto }

.sup-section { margin-top:22px }

.hero { display:flex; align-items:center; justify-content:space-between; margin-bottom:24px }
.hero h1 { margin:0 0 4px; font-size:var(--fs-xl); font-weight:600; color:var(--c-text-1) }
.hero p { margin:0; color:var(--c-text-2); font-size:var(--fs-base) }
.btns { display:flex; gap:8px }
.btn { padding:9px 16px; border-radius:var(--r-s); font-size:var(--fs-sm); cursor:pointer; border:1px solid var(--c-border-strong); background:#fff; transition:all .15s }
.btn.primary { background:var(--c-primary); color:#fff; border-color:transparent; box-shadow:0 4px 12px rgba(37,99,235,.25) }
.btn.primary:hover { background:var(--c-primary-hover); transform:translateY(-1px) }

.stats { display:grid; grid-template-columns:repeat(4, 1fr); gap:14px; margin-bottom:24px }
.stat { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:18px; box-shadow:0 2px 6px rgba(20,30,60,.04) }
.stat .label { color:var(--c-text-2); font-size:var(--fs-xs); margin-bottom:6px }
.stat .num { font-size:var(--fs-xl); font-weight:600; color:var(--c-text-1) }
.stat .num small { font-size:var(--fs-sm); font-weight:400; color:var(--c-text-3); margin-left:4px }
.stat .bar { margin-top:10px; height:6px; background:var(--c-border); border-radius:var(--r-full); overflow:hidden }
.stat .bar i { display:block; height:100%; background:linear-gradient(90deg,#2563eb,#4f46e5); border-radius:var(--r-full); transition:width .3s }

.stages { display:flex; flex-direction:column; gap:18px }
.stage { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-l); overflow:hidden; box-shadow:0 2px 6px rgba(20,30,60,.04) }
.stage-head { display:flex; align-items:center; gap:14px; padding:18px 22px; border-bottom:1px solid var(--c-border) }
.stage-num { width:36px; height:36px; border-radius:var(--r-m); display:grid; place-items:center; color:#fff; font-weight:600; font-size:var(--fs-md); flex-shrink:0 }
.stage-title { font-size:var(--fs-md); font-weight:600; color:var(--c-text-1) }
.stage-sub { font-size:var(--fs-xs); color:var(--c-text-3); margin-top:2px }
.stage-meta { margin-left:auto; display:flex; align-items:center; gap:14px; color:var(--c-text-2); font-size:var(--fs-sm) }
.tag { display:inline-flex; align-items:center; gap:6px; padding:4px 12px; border-radius:var(--r-full); font-size:var(--fs-xs); background:var(--c-primary-bg); color:var(--c-primary); font-weight:500 }
.tag.green { background:var(--c-success-bg); color:#0e8a4f }
.pct { font-size:var(--fs-base); font-weight:600; color:var(--c-text-1); min-width:38px; text-align:right }

.chapters { display:grid; grid-template-columns:repeat(2, 1fr); gap:14px; padding:18px 22px }
.chapter { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:16px; cursor:pointer; transition:all .15s }
.chapter:hover { transform:translateY(-2px); box-shadow:0 8px 18px rgba(20,30,60,.08); border-color:var(--c-primary) }
.chapter.done { background:linear-gradient(180deg,#f6fcf8,#fff); border-color:#86efac }
.ch-title { display:flex; align-items:center; gap:8px; font-weight:600; margin-bottom:6px; font-size:var(--fs-base) }
.ch-no { color:var(--c-text-3); font-size:var(--fs-xs); font-weight:500; width:54px; flex-shrink:0 }
.ch-name { flex:1; color:var(--c-text-1) }
.ch-done { flex-shrink:0 }
.ch-meta { display:flex; gap:6px; font-size:var(--fs-xs); color:var(--c-text-3); margin-top:6px; flex-wrap:wrap }
.ch-progress { margin-top:10px; height:5px; background:var(--c-border); border-radius:var(--r-full); overflow:hidden }
.ch-progress i { display:block; height:100%; background:var(--c-success); border-radius:var(--r-full); transition:width .3s }
</style>