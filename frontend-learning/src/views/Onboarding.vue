<script setup>
// 新手指引 3 步向导: 目标 → 水平 → 语言
// 用户首次登录(needsOnboarding=true)由 Layout 拦截自动跳转 /onboarding
// 也可从 ProfileSettings 手动"重做新手指引"进入
import { computed, ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useUserStore } from '../store/user'
import { finishOnboarding, skipOnboarding, getOnboardingMe, getRecommend as getOnboardingRecommend } from '../api/onboarding'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const step = ref(1)  // 1=目标, 2=水平, 3=语言
const submitting = ref(false)

// 3 步表单
const form = ref({
  goal: '',         // JOB/INTEREST/EXAM/PROJECT
  level: '',        // BEGINNER/HAS_OTHER/HAS_JS/INTERMEDIATE
  preferredSubjectId: null
})

// 候选语言(从 API 拿)
const subjects = ref([])
const recommend = ref(null)   // 完成后服务端返回的推荐路径

const goalOptions = [
  { value: 'JOB',      icon: 'Briefcase',    titleKey: 'onboarding.goal.job',      descKey: 'onboarding.goal.jobDesc' },
  { value: 'INTEREST', icon: 'Star',         titleKey: 'onboarding.goal.interest', descKey: 'onboarding.goal.interestDesc' },
  { value: 'EXAM',     icon: 'EditPen',      titleKey: 'onboarding.goal.exam',     descKey: 'onboarding.goal.examDesc' },
  { value: 'PROJECT',  icon: 'FolderOpened', titleKey: 'onboarding.goal.project',  descKey: 'onboarding.goal.projectDesc' },
]

const levelOptions = [
  { value: 'BEGINNER',     titleKey: 'onboarding.level.beginner',     descKey: 'onboarding.level.beginnerDesc' },
  { value: 'HAS_OTHER',    titleKey: 'onboarding.level.hasOther',    descKey: 'onboarding.level.hasOtherDesc' },
  { value: 'HAS_JS',       titleKey: 'onboarding.level.hasJs',       descKey: 'onboarding.level.hasJsDesc' },
  { value: 'INTERMEDIATE', titleKey: 'onboarding.level.intermediate', descKey: 'onboarding.level.intermediateDesc' },
]

const canNext = computed(() => {
  if (step.value === 1) return !!form.value.goal
  if (step.value === 2) return !!form.value.level
  if (step.value === 3) return !!form.value.preferredSubjectId
  return false
})

const isDone = ref(false)

const loadSubjects = async () => {
  // 直接调 learning subjects 接口
  const { getSubjects } = await import('../api/learning')
  const r = await getSubjects()
  subjects.value = r.data || []
  // 默认选中第一个 enabled 的
  if (!form.value.preferredSubjectId && subjects.value.length) {
    const first = subjects.value.find(s => s.enabled) || subjects.value[0]
    form.value.preferredSubjectId = first.id
  }
}

const next = () => {
  if (!canNext.value) {
    ElMessage.warning(t('onboarding.required'))
    return
  }
  if (step.value < 3) {
    step.value++
  } else {
    submit()
  }
}

const prev = () => {
  if (step.value > 1) step.value--
}

const submit = async () => {
  submitting.value = true
  try {
    const r = await finishOnboarding(form.value)
    recommend.value = r.data
    isDone.value = true
  } catch (e) {
    // 拦截器已弹错
  } finally {
    submitting.value = false
  }
}

const skip = async () => {
  try {
    await skipOnboarding()
    goHome()
  } catch (e) { /* ignore */ }
}

const goHome = () => {
  // 走 redirect 参数或 /learning
  const redirect = route.query.redirect || '/learning'
  router.push(redirect)
}

const enterRecommend = () => {
  const subj = recommend.value?.recommendedSubject
  if (subj?.id) {
    router.push(`/learning?subject=${subj.id}`)
  } else {
    goHome()
  }
}

const enterLesson = (l) => {
  router.push(`/learning/lesson/${l.lessonId}`)
}

onMounted(async () => {
  if (!userStore.isLogin) {
    router.push('/login?redirect=/onboarding')
    return
  }
  // 已完成则直接跳走
  try {
    const r = await getOnboardingMe()
    const data = r.data
    if (data && !data.needsOnboarding) {
      goHome()
      return
    }
    // 回填已有数据
    if (data.goal) form.value.goal = data.goal
    if (data.level) form.value.level = data.level
    if (data.preferredSubjectId) form.value.preferredSubjectId = data.preferredSubjectId
  } catch (e) { /* ignore */ }
  loadSubjects()
})
</script>

<template>
  <div class="onboarding">
    <div class="ob-card">
      <!-- 跳过按钮 (步骤 1-3 显示, 完成态隐藏) -->
      <el-button v-if="!isDone" class="ob-skip" link @click="skip">{{ t('onboarding.skip') }}</el-button>

      <!-- 步骤指示 -->
      <div v-if="!isDone" class="ob-steps">
        <div v-for="i in 3" :key="i" class="ob-step" :class="{ active: step === i, done: step > i }">
          <span class="dot">{{ i }}</span>
          <span class="label">
            {{ i === 1 ? t('onboarding.step1') : i === 2 ? t('onboarding.step2') : t('onboarding.step3') }}
          </span>
        </div>
      </div>

      <!-- 完成态 -->
      <div v-if="isDone" class="ob-done">
        <el-icon class="ob-done-icon"><CircleCheckFilled /></el-icon>
        <h2>{{ t('onboarding.doneTitle') }}</h2>
        <p v-if="recommend?.hint" class="ob-hint">{{ recommend.hint }}</p>

        <div v-if="recommend?.recommendedSubject" class="ob-recommend-subject">
          <el-tag size="large" effect="plain">{{ t('onboarding.recommendSubject') }}</el-tag>
          <h3>{{ recommend.recommendedSubject.name }}</h3>
        </div>

        <ul v-if="recommend?.recommendedLessons?.length" class="ob-recommend-list">
          <li v-for="(l, idx) in recommend.recommendedLessons" :key="l.lessonId" class="ob-recommend-item" @click="enterLesson(l)">
            <div class="seq">{{ idx + 1 }}</div>
            <div class="meta">
              <div class="title">{{ l.lessonTitle }}</div>
              <div class="sub">
                <span>{{ l.chapterTitle }}</span>
                <span class="dot-sep">·</span>
                <span>{{ l.durationMinutes }} min</span>
              </div>
            </div>
            <el-icon><ArrowRight /></el-icon>
          </li>
        </ul>

        <div class="ob-done-actions">
          <el-button @click="enterRecommend" type="primary" size="large">
            {{ t('onboarding.viewAllCourses') }}
          </el-button>
          <el-button @click="goHome" size="large">{{ t('onboarding.skipToHome') }}</el-button>
        </div>
      </div>

      <!-- 步骤 1: 目标 -->
      <div v-else-if="step === 1" class="ob-step-content">
        <h1>{{ t('onboarding.goalTitle') }}</h1>
        <p class="ob-subtitle">{{ t('onboarding.goalSubtitle') }}</p>
        <div class="ob-grid">
          <div
            v-for="g in goalOptions" :key="g.value"
            class="ob-card-opt" :class="{ active: form.goal === g.value }"
            @click="form.goal = g.value"
            tabindex="0"
            @keyup.enter="form.goal = g.value"
          >
            <el-icon class="ic"><component :is="g.icon" /></el-icon>
            <h3>{{ t(g.titleKey) }}</h3>
            <p>{{ t(g.descKey) }}</p>
          </div>
        </div>
      </div>

      <!-- 步骤 2: 水平 -->
      <div v-else-if="step === 2" class="ob-step-content">
        <h1>{{ t('onboarding.levelTitle') }}</h1>
        <p class="ob-subtitle">{{ t('onboarding.levelSubtitle') }}</p>
        <div class="ob-grid ob-grid-2col">
          <div
            v-for="lv in levelOptions" :key="lv.value"
            class="ob-card-opt" :class="{ active: form.level === lv.value }"
            @click="form.level = lv.value"
            tabindex="0"
            @keyup.enter="form.level = lv.value"
          >
            <h3>{{ t(lv.titleKey) }}</h3>
            <p>{{ t(lv.descKey) }}</p>
          </div>
        </div>
      </div>

      <!-- 步骤 3: 语言 -->
      <div v-else-if="step === 3" class="ob-step-content">
        <h1>{{ t('onboarding.langTitle') }}</h1>
        <p class="ob-subtitle">{{ t('onboarding.langSubtitle') }}</p>
        <div class="ob-grid ob-grid-3col">
          <div
            v-for="s in subjects" :key="s.id"
            class="ob-card-opt" :class="{ active: form.preferredSubjectId === s.id, disabled: !s.enabled }"
            @click="s.enabled && (form.preferredSubjectId = s.id)"
          >
            <h3>{{ s.name }}</h3>
            <p class="ob-meta">{{ s.totalLessons }} {{ t('onboarding.lessons') }}</p>
            <p v-if="!s.enabled" class="ob-disabled">{{ t('onboarding.disabled') }}</p>
          </div>
        </div>
      </div>

      <!-- 底部按钮 -->
      <div v-if="!isDone" class="ob-actions">
        <el-button v-if="step > 1" @click="prev" size="large">{{ t('onboarding.prev') }}</el-button>
        <div v-else></div>
        <el-button type="primary" :loading="submitting" :disabled="!canNext" @click="next" size="large">
          {{ step < 3 ? t('onboarding.next') : t('onboarding.finish') }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.onboarding {
  min-height: 100vh;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, var(--c-primary-bg) 0%, #fff 60%);
  padding: 24px;
}
.ob-card {
  position: relative;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 8px 32px rgba(0,0,0,.08);
  padding: 40px 48px;
  width: 100%;
  max-width: 880px;
  min-height: 480px;
}
.ob-skip { position: absolute; top: 20px; right: 24px; }

/* 步骤指示 */
.ob-steps {
  display: flex; justify-content: center; gap: 24px;
  margin-bottom: 32px;
}
.ob-step { display: flex; align-items: center; gap: 8px; color: var(--c-text-3); font-size: var(--fs-sm); }
.ob-step .dot {
  width: 28px; height: 28px; border-radius: 50%;
  background: #f0f0f0; color: var(--c-text-3);
  display: inline-flex; align-items: center; justify-content: center; font-weight: 600;
}
.ob-step.active .dot { background: var(--c-primary); color: #fff; }
.ob-step.done .dot { background: var(--c-primary); color: #fff; }
.ob-step.active, .ob-step.done { color: var(--c-text-1); font-weight: 500; }

/* 步骤内容 */
.ob-step-content h1 {
  font-size: 24px; font-weight: 600; color: var(--c-text-1);
  margin: 0 0 8px; text-align: center;
}
.ob-subtitle {
  text-align: center; color: var(--c-text-2); font-size: var(--fs-base);
  margin: 0 0 32px;
}

/* 卡片选项 */
.ob-grid {
  display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px;
  margin-bottom: 32px;
}
.ob-grid-2col { grid-template-columns: repeat(2, 1fr); }
.ob-grid-3col { grid-template-columns: repeat(3, 1fr); }
@media (max-width: 720px) {
  .ob-grid, .ob-grid-2col { grid-template-columns: 1fr; }
  .ob-grid-3col { grid-template-columns: repeat(2, 1fr); }
}
.ob-card-opt {
  border: 2px solid var(--c-border);
  border-radius: 12px;
  padding: 20px;
  cursor: pointer;
  transition: all .2s;
  background: #fff;
  text-align: left;
}
.ob-card-opt:hover { border-color: var(--c-primary); transform: translateY(-2px); }
.ob-card-opt.active { border-color: var(--c-primary); background: var(--c-primary-bg); }
.ob-card-opt.disabled { opacity: 0.4; cursor: not-allowed; }
.ob-card-opt .ic { font-size: 28px; color: var(--c-primary); margin-bottom: 8px; }
.ob-card-opt h3 { margin: 0 0 6px; font-size: var(--fs-base); font-weight: 600; color: var(--c-text-1); }
.ob-card-opt p { margin: 0; font-size: var(--fs-sm); color: var(--c-text-2); line-height: 1.5; }
.ob-card-opt .ob-meta { color: var(--c-text-3); font-size: var(--fs-xs); margin-top: 4px; }
.ob-card-opt .ob-disabled { color: var(--c-danger); font-size: var(--fs-xs); margin-top: 4px; }

/* 底部按钮 */
.ob-actions { display: flex; justify-content: space-between; align-items: center; }

/* 完成态 */
.ob-done { text-align: center; }
.ob-done-icon { font-size: 64px; color: var(--c-primary); }
.ob-done h2 { margin: 16px 0 8px; font-size: 22px; color: var(--c-text-1); }
.ob-hint { color: var(--c-text-2); font-size: var(--fs-sm); line-height: 1.6; max-width: 600px; margin: 0 auto 24px; }
.ob-recommend-subject { margin-bottom: 24px; }
.ob-recommend-subject h3 { margin: 8px 0 0; color: var(--c-text-1); }
.ob-recommend-list {
  list-style: none; padding: 0; margin: 0 0 32px;
  display: flex; flex-direction: column; gap: 8px;
  text-align: left;
}
.ob-recommend-item {
  display: flex; align-items: center; gap: 16px;
  padding: 12px 16px; border: 1px solid var(--c-border); border-radius: 8px;
  cursor: pointer; transition: all .2s;
}
.ob-recommend-item:hover { border-color: var(--c-primary); background: var(--c-primary-bg); }
.ob-recommend-item .seq {
  width: 28px; height: 28px; border-radius: 50%;
  background: var(--c-primary); color: #fff;
  display: inline-flex; align-items: center; justify-content: center; font-weight: 600;
  flex: none;
}
.ob-recommend-item .meta { flex: 1; min-width: 0; }
.ob-recommend-item .title { font-size: var(--fs-base); font-weight: 500; color: var(--c-text-1); }
.ob-recommend-item .sub { font-size: var(--fs-xs); color: var(--c-text-3); margin-top: 2px; }
.ob-recommend-item .dot-sep { margin: 0 6px; }
.ob-done-actions { display: flex; justify-content: center; gap: 12px; }
</style>
