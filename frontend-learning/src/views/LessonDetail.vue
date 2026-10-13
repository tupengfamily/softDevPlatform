<script setup>
// 课时学习页: 正文 + 代码练习 + SQL 控制台 + 导航
// 设计稿风格:三栏(章节侧栏 / 正文 / 进度侧栏),代码区与 SQL 控制台直接内嵌正文
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCurriculum, getLesson, markLearned, unmarkLearned } from '../api/learning'
import { runJava, runPython, runNode } from '../api/sandbox'
import { submitFeedback } from '../api/feedback'
import { lessonCrumbTitle } from '../store/crumb'
import { useUserStore } from '../store/user'
import { useSubjectStore } from '../store/subject'
import { useDictStore } from '../store/dict'
import { renderMarkdown } from '../utils/markdown'
import CodeEditor from '../components/CodeEditor.vue'
import SqlConsole from '../components/SqlConsole.vue'
import SupplementTree from '../components/SupplementTree.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const { t } = useI18n()

// 游客可浏览课时;标记完成等个人操作需登录
const isGuest = computed(() => !userStore.isLogin)
const promptLogin = () => {
  ElMessageBox.confirm(t('lesson.login.message'), t('lesson.login.title'), {
    confirmButtonText: t('lesson.login.confirm'),
    cancelButtonText: t('lesson.login.cancel'),
    type: 'info'
  }).then(() => {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
  }).catch(() => { /* 暂不登录 */ })
}

const loading = ref(false)
const lesson = ref(null)
const curriculum = ref([])
const expandedChapters = ref([])
const centerRef = ref(null)

// ============== 移动端: 目录抽屉 ==============
// <=768px 时左栏目录收进 off-canvas 抽屉,由浮动"目录"按钮唤出
const isMobile = ref(false)
const tocOpen = ref(false)
let mq = null
const onMq = (e) => {
  isMobile.value = e.matches
  if (!e.matches) tocOpen.value = false
}
const toggleToc = () => { tocOpen.value = !tocOpen.value }

// ============== 右侧悬浮工具栏(图标竖排,可折叠) 与 回到顶部 ==============
const RSIDE_KEY = 'lessonRsideCollapsed'
const rsideCollapsed = ref(localStorage.getItem(RSIDE_KEY) === '1')
const toggleRside = () => {
  rsideCollapsed.value = !rsideCollapsed.value
  localStorage.setItem(RSIDE_KEY, rsideCollapsed.value ? '1' : '0')
}
const showTop = ref(false)
const onCenterScroll = () => {
  showTop.value = (centerRef.value?.scrollTop || 0) > 300
}
const scrollToTop = () => centerRef.value?.scrollTo({ top: 0, behavior: 'smooth' })

// 添加补充内容:按钮在工具栏,弹窗复用 SupplementTree 内部对话框
const supRef = ref(null)
const addSupplement = () => {
  if (isGuest.value) { promptLogin(); return }
  supRef.value?.openCreate(null)
}

// ============== 反馈问题/建议(自动携带来源页面,便于管理员定位) ==============
const fbVisible = ref(false)
const fbSubmitting = ref(false)
const fbForm = ref({ type: 'BUG', title: '', content: '', contact: '' })
// 来源上下文: 页面名称 | 课时标题 | 路由路径
const fbSource = computed(() =>
  `${t('lesson.feedback.sourcePage')} · ${lesson.value?.title || ''} · ${route.fullPath}`
)
const openFeedback = () => {
  if (isGuest.value) { promptLogin(); return }
  fbVisible.value = true
}
const submitFb = async () => {
  const f = fbForm.value
  if (!f.title.trim()) { ElMessage.warning(f.type === 'BUG' ? t('lesson.feedback.needBugTitle') : t('lesson.feedback.needSuggestTitle')); return }
  if (!f.content.trim()) { ElMessage.warning(f.type === 'BUG' ? t('lesson.feedback.needBugContent') : t('lesson.feedback.needSuggestContent')); return }
  fbSubmitting.value = true
  try {
    await submitFeedback({ ...f, source: fbSource.value })
    ElMessage.success(t('lesson.feedback.submitted'))
    fbVisible.value = false
    fbForm.value = { type: 'BUG', title: '', content: '', contact: '' }
  } catch (e) { /* 已统一弹错 */ } finally {
    fbSubmitting.value = false
  }
}

// 复制练习代码路径(工具栏"对应练习代码"按钮)
const copyCodePath = async () => {
  const p = lesson.value?.codePath
  if (!p) return
  try {
    await navigator.clipboard.writeText(p)
    ElMessage.success(t('lesson.codePath.copied', { path: p }))
  } catch (e) {
    ElMessage.info(t('lesson.codePath.info', { path: p }))
  }
}

// 难度文案来自数据字典 lesson_difficulty,tag 颜色固定在代码
// 包 computed: 切换界面语言时按 dictStore 的双语字典值即时重算
const dictStore = useDictStore()
const difficultyMap = computed(() => ({
  1: { text: dictStore.label('lesson_difficulty', 1, t('lesson.difficulty.easy')), type: 'success' },
  2: { text: dictStore.label('lesson_difficulty', 2, t('lesson.difficulty.medium')), type: 'warning' },
  3: { text: dictStore.label('lesson_difficulty', 3, t('lesson.difficulty.hard')), type: 'danger' }
}))

// ============== 课程树与选中(限定当前课时的语言) ==============
const curriculumSubjectId = ref(undefined)   // undefined=未加载
// 请求序号: 快速切换课时/语言时旧响应可能后到,只认最后一次请求的响应
let curriculumSeq = 0
const loadCurriculum = async (subjectId) => {
  const seq = ++curriculumSeq
  try {
    const res = await getCurriculum(subjectId)
    if (seq !== curriculumSeq) return
    curriculum.value = res.data || []
    curriculumSubjectId.value = subjectId ?? null
  } catch (e) {}
}

// ============== 课时正文(开源 marked 渲染,支持基本 Markdown 语法) ==============
const renderedContent = computed(() => renderMarkdown(lesson.value?.content || ''))

// 请求序号: 快速切换课时(watch 触发)只认最后一次请求的响应,防止旧响应覆盖新数据
let reqSeq = 0
const loadLesson = async () => {
  // 局部更新: 保留旧正文直到新数据到达,只在首次加载时显示整块 loading(避免点击章节时页面闪动)
  const firstLoad = !lesson.value
  const seq = ++reqSeq
  if (firstLoad) loading.value = true
  try {
    const res = await getLesson(route.params.id)
    if (seq !== reqSeq) return   // 已有更新的请求在途/完成,丢弃本次(旧)响应
    lesson.value = res.data
    // 面包屑动态标题: 首页 / 学习中心 / <课时标题>
    lessonCrumbTitle.value = lesson.value.title
    // 目录只展示当前课时所属语言(多语言课程),语言变化时重新加载
    if (curriculumSubjectId.value !== (lesson.value.subjectId ?? null)) {
      loadCurriculum(lesson.value.subjectId)
    }
    if (lesson.value.chapterId) {
      const cid = String(lesson.value.chapterId)
      if (!expandedChapters.value.includes(cid)) expandedChapters.value.push(cid)
    }
    // 切换课时后重置沙箱(代码灌入本课示例、清空上一课的运行结果与错误标记)
    resetSandboxes()
    // 切换课时后内容区回到顶部(平滑滚动,不整页闪动)
    if (!firstLoad) centerRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) {} finally {
    if (seq === reqSeq) loading.value = false   // 旧请求收尾不关 loading(新请求在途)
  }
}

// ============== 进度 ==============
// 同步左侧目录的完成图标与章节进度(修复"标记完成后图标不更新")
const syncLearned = (lessonId, learned) => {
  for (const stage of curriculum.value) {
    for (const ch of (stage.chapters || [])) {
      const ls = (ch.lessons || []).find(l => l.id === lessonId)
      if (ls) {
        ls.learned = learned
        ch.learnedLessons = (ch.lessons || []).filter(l => l.learned).length
      }
    }
  }
}

const toggling = ref(false)
const toggleLearned = async () => {
  if (!lesson.value || toggling.value) return
  if (isGuest.value) return promptLogin()   // 游客点击 → 引导登录
  toggling.value = true
  try {
    if (lesson.value.learned) {
      await unmarkLearned(lesson.value.id)
      lesson.value.learned = false
      syncLearned(lesson.value.id, false)
      ElMessage.info(t('lesson.unmarked'))
    } else {
      await markLearned(lesson.value.id)
      lesson.value.learned = true
      syncLearned(lesson.value.id, true)
      ElMessage.success(t('lesson.marked'))
    }
  } catch (e) { /* 拦截器已提示 */ } finally {
    toggling.value = false
  }
}

const completeAndNext = async () => {
  if (!lesson.value || toggling.value) return
  if (isGuest.value) return promptLogin()   // 游客点击 → 引导登录
  toggling.value = true
  try {
    if (!lesson.value.learned) {
      await markLearned(lesson.value.id)
      lesson.value.learned = true
      syncLearned(lesson.value.id, true)
    }
    if (lesson.value.nextLessonId) {
      router.push(`/learning/lesson/${lesson.value.nextLessonId}`)
      ElMessage.success(t('lesson.doneNext'))
    } else {
      ElMessage.success(t('lesson.doneAll'))
    }
  } catch (e) { /* 拦截器已提示 */ } finally {
    toggling.value = false
  }
}

const goPrev = () => lesson.value?.prevLessonId && router.push(`/learning/lesson/${lesson.value.prevLessonId}`)
const goNext = () => lesson.value?.nextLessonId && router.push(`/learning/lesson/${lesson.value.nextLessonId}`)
const backToList = () => router.push('/learning')

const openLesson = (id) => { tocOpen.value = false; router.push(`/learning/lesson/${id}`) }

// 键盘可达:课时条目 / 导航行支持 Enter 与空格触发
const onKeyActivate = (e, fn) => {
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fn() }
}

// ============== 通用示例代码卡(非沙箱语言) ==============
const SANDBOX_TYPES = ['java', 'python', 'nodejs', 'sql']
const showExample = computed(() =>
  !!lesson.value?.codeExample && !SANDBOX_TYPES.includes(lesson.value?.codeType || 'java'))
const exampleCopied = ref(false)
const copyExample = async () => {
  try {
    await navigator.clipboard.writeText(lesson.value?.codeExample || '')
    exampleCopied.value = true
    setTimeout(() => (exampleCopied.value = false), 1500)
  } catch { /* 剪贴板不可用时静默 */ }
}

// ============== Java 沙箱 ==============
const DEFAULT_JAVA = `public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Sandbox!");
    }
}`
const DEFAULT_PY = "print('Hello, Python!')"
const DEFAULT_NODE = "console.log('Hello, Node.js!')"
const javaCode = ref(DEFAULT_JAVA)
const sqlCode = ref('')
const javaMarkers = ref([])
const javaResult = ref(null)
const javaRunning = ref(false)

// 切换课时后重置各沙箱状态(代码/错误标记/运行结果),避免上一课的运行结果残留到新课时
const resetSandboxes = () => {
  const l = lesson.value
  javaCode.value = l?.codeExample && (!l.codeType || l.codeType === 'java') ? l.codeExample : DEFAULT_JAVA
  sqlCode.value = l?.codeType === 'sql' ? (l.codeExample || '') : ''
  javaMarkers.value = []
  javaResult.value = null
  pyCode.value = l?.codeType === 'python' ? (l.codeExample || DEFAULT_PY) : DEFAULT_PY
  pyMarkers.value = []
  pyResult.value = null
  nodeCode.value = l?.codeType === 'nodejs' ? (l.codeExample || DEFAULT_NODE) : DEFAULT_NODE
  nodeMarkers.value = []
  nodeResult.value = null
}

const runJavaCode = async () => {
  if (javaRunning.value) return
  javaRunning.value = true
  javaResult.value = null
  javaMarkers.value = []
  try {
    const res = await runJava(javaCode.value)
    javaResult.value = res.data
    if (javaResult.value.status === 'COMPILE_ERROR') {
      javaMarkers.value = (javaResult.value.errors || []).map(e => ({
        line: e.line, column: e.column, message: e.message, severity: 'error'
      }))
    }
  } catch (e) { /* 已统一弹错 */ } finally {
    javaRunning.value = false
  }
}

const resetJavaCode = () => {
  const l = lesson.value
  javaCode.value = l?.codeExample && (!l.codeType || l.codeType === 'java') ? l.codeExample : DEFAULT_JAVA
  javaMarkers.value = []
  javaResult.value = null
}

// ============== Python 沙箱 ==============
const pyCode = ref(DEFAULT_PY)
const pyMarkers = ref([])
const pyResult = ref(null)
const pyRunning = ref(false)

const runPythonCode = async () => {
  if (pyRunning.value) return
  pyRunning.value = true
  pyResult.value = null
  pyMarkers.value = []
  try {
    const res = await runPython(pyCode.value)
    pyResult.value = res.data
    if (pyResult.value.status === 'COMPILE_ERROR') {
      pyMarkers.value = (pyResult.value.errors || []).map(e => ({
        line: e.line, column: e.column, message: e.message, severity: 'error'
      }))
    }
  } catch (e) { /* 已统一弹错 */ } finally {
    pyRunning.value = false
  }
}

const resetPythonCode = () => {
  const l = lesson.value
  pyCode.value = l?.codeType === 'python' ? (l.codeExample || DEFAULT_PY) : DEFAULT_PY
  pyMarkers.value = []
  pyResult.value = null
}

// ============== Node.js 沙箱 ==============
const nodeCode = ref("console.log('Hello, Node.js!')")
const nodeMarkers = ref([])
const nodeResult = ref(null)
const nodeRunning = ref(false)

const runNodeCode = async () => {
  if (nodeRunning.value) return
  nodeRunning.value = true
  nodeResult.value = null
  nodeMarkers.value = []
  try {
    const res = await runNode(nodeCode.value)
    nodeResult.value = res.data
    if (nodeResult.value.status === 'COMPILE_ERROR') {
      nodeMarkers.value = (nodeResult.value.errors || []).map(e => ({
        line: e.line, column: e.column, message: e.message, severity: 'error'
      }))
    }
  } catch (e) { /* 已统一弹错 */ } finally {
    nodeRunning.value = false
  }
}

const resetNodeCode = () => {
  const l = lesson.value
  nodeCode.value = l?.codeType === 'nodejs' ? (l.codeExample || DEFAULT_NODE) : DEFAULT_NODE
  nodeMarkers.value = []
  nodeResult.value = null
}

watch(() => route.params.id, () => { if (route.name === 'LessonDetail') loadLesson() })

// ============== 切换学习语言(头部选择框)联动 ==============
// 用户在课时页手动切换语言后,学习内容须跟随新语言(仅响应用户动作,不响应偏好加载回填):
// 尽量定位到新语言中"同章同节"的课时,没有对应课时则进入该语言第一课
const subjectStore = useSubjectStore()
watch(() => subjectStore.switchTick, async () => {
  const sid = subjectStore.preferredSubjectId
  if (!sid || !lesson.value || sid === (lesson.value.subjectId ?? null)) return
  try {
    const res = await getCurriculum(sid)
    const stages = res.data || []
    let target = null
    for (const st of stages) {
      for (const ch of st.chapters) {
        if (ch.chapterNo === lesson.value.chapterNo) {
          target = ch.lessons.find(l => l.lessonNo === lesson.value.lessonNo) || ch.lessons[0] || null
        }
        if (target) break
      }
      if (target) break
    }
    if (!target) {
      // 该语言没有同章同节:取全语言第一个课时
      outer: for (const st of stages) {
        for (const ch of st.chapters) {
          for (const l of ch.lessons) { target = l; break outer }
        }
      }
    }
    if (target) {
      ElMessage.info(t('lesson.langSwitched', { title: target.title }))
      router.push(`/learning/lesson/${target.id}`)
    } else {
      ElMessage.warning(t('lesson.langEmpty'))
      router.push('/learning')
    }
  } catch (e) { /* 已统一弹错 */ }
})

onMounted(async () => {
  mq = window.matchMedia('(max-width: 768px)')
  isMobile.value = mq.matches
  mq.addEventListener('change', onMq)
  await loadLesson()   // 内部按课时语言加载目录
  centerRef.value?.addEventListener('scroll', onCenterScroll, { passive: true })
})

onBeforeUnmount(() => {
  mq?.removeEventListener('change', onMq)
  centerRef.value?.removeEventListener('scroll', onCenterScroll)
})
</script>

<template>
  <div class="layout" :class="{ 'is-mobile': isMobile, 'toc-open': tocOpen }">
    <!-- 移动端目录抽屉遮罩 -->
    <transition name="fade">
      <div v-if="isMobile && tocOpen" class="toc-mask" @click="tocOpen = false"></div>
    </transition>

    <!-- 左：章节侧栏 -->
    <aside class="lside">
      <div class="lhead">
        <div class="crumb"><el-icon><Reading /></el-icon> {{ $t('lesson.sidebar.crumb') }}</div>
        <div class="lhead-title" v-if="lesson">{{ lesson.stageName }}</div>
        <div class="lhead-title" v-else>{{ $t('lesson.sidebar.catalog') }}</div>
      </div>
      <div class="parts">
        <template v-for="stage in curriculum" :key="stage.stageNo">
          <div class="part-h">{{ stage.stageName }}</div>
          <el-collapse v-model="expandedChapters">
            <el-collapse-item
              v-for="ch in stage.chapters"
              :key="ch.id"
              :name="String(ch.id)"
              :title="$t('lesson.sidebar.chapter', { no: ch.chapterNo, title: ch.title })"
            >
              <div
                v-for="ls in ch.lessons"
                :key="ls.id"
                class="lesson-item"
                role="button"
                tabindex="0"
                :class="{ active: lesson?.id === ls.id, done: ls.learned }"
                :aria-label="`${ch.chapterNo}.${ls.lessonNo} ${ls.title}`"
                :aria-current="lesson?.id === ls.id ? 'true' : undefined"
                @click="openLesson(ls.id)"
                @keydown="onKeyActivate($event, () => openLesson(ls.id))"
              >
                <el-icon v-if="ls.learned" class="ic done"><CircleCheckFilled /></el-icon>
                <el-icon v-else class="ic"><CircleCheck /></el-icon>
                <span class="t">{{ ch.chapterNo }}.{{ ls.lessonNo }} {{ ls.title }}</span>
              </div>
            </el-collapse-item>
          </el-collapse>
        </template>
      </div>
    </aside>

    <!-- 中：正文 + 练习 -->
    <main ref="centerRef" class="center" v-loading="loading">
      <article v-if="lesson" :key="lesson.id" class="content">
        <div class="lesson-h">
          <h1>{{ lesson.title }}</h1>
          <div class="meta-row">
            <el-tag size="small" effect="plain">{{ lesson.stageName }}</el-tag>
            <el-tag size="small" :type="difficultyMap[lesson.difficulty]?.type" effect="plain">
              {{ difficultyMap[lesson.difficulty]?.text }}
            </el-tag>
            <span class="meta-item"><el-icon><Timer /></el-icon> {{ $t('lesson.meta.minutes', { n: lesson.durationMinutes }) }}</span>
            <el-tag v-if="lesson.learned" type="success" size="small">{{ $t('lesson.meta.learned') }}</el-tag>
          </div>
        </div>

        <p v-if="lesson.summary" class="summary">{{ lesson.summary }}</p>

        <!-- 正文: 开源 marked 渲染的 Markdown(支持标题/列表/表格/代码块/行内代码/链接等基本语法) -->
        <div class="md-body" v-html="renderedContent"></div>

        <!-- 通用示例代码卡:html/css/js/vue 等非沙箱语言,仅展示代码(带复制) -->
        <div v-if="showExample" class="practice example">
          <div class="phead">
            <h3>{{ $t('lesson.practice.exampleTitle') }} <small>{{ lesson.codeType }}</small></h3>
            <el-button size="small" text @click="copyExample">
              <el-icon><CopyDocument /></el-icon> {{ exampleCopied ? $t('lesson.practice.copied') : $t('lesson.practice.copy') }}
            </el-button>
          </div>
          <pre class="example-code"><code>{{ lesson.codeExample }}</code></pre>
        </div>

        <!-- 代码练习卡(sql/python 类型课时不展示 Java 沙箱,避免语言错配) -->
        <div v-if="!lesson?.codeType || lesson.codeType === 'java'" class="practice">
          <div class="phead">
            <h3>{{ $t('lesson.practice.javaTitle') }} <small>{{ $t('lesson.practice.sandboxSub') }}</small></h3>
            <div class="meta-mini">{{ javaResult ? `${javaResult.elapsedMs}ms` : $t('lesson.practice.notRun') }}</div>
          </div>
          <div class="editor-area">
            <CodeEditor v-model="javaCode" :markers="javaMarkers" @run="runJavaCode" @reset="resetJavaCode" />
          </div>
          <div v-if="javaResult" class="result-area" :class="javaResult?.status?.toLowerCase() || 'normal'">
            <div class="r-head">
              <span v-if="javaResult.status === 'OK'" class="ok"><el-icon><CircleCheckFilled /></el-icon> {{ $t('lesson.result.ok', { exit: javaResult.exitCode, ms: javaResult.elapsedMs }) }}</span>
              <span v-else-if="javaResult.status === 'COMPILE_ERROR'" class="ce"><el-icon><WarningFilled /></el-icon> {{ $t('lesson.result.compileError', { n: javaResult.errors?.length || 0 }) }}</span>
              <span v-else-if="javaResult.status === 'RUNTIME_ERROR'" class="re"><el-icon><WarningFilled /></el-icon> {{ $t('lesson.result.runtimeError') }}</span>
              <span v-else-if="javaResult.status === 'TIMEOUT'" class="to"><el-icon><Timer /></el-icon> {{ $t('lesson.result.timeout') }}</span>
              <span v-else>{{ javaResult.status }}</span>
            </div>
            <pre v-if="javaResult.stdout" class="stdout">{{ javaResult.stdout }}</pre>
            <pre v-if="javaResult.stderr" class="stderr">{{ javaResult.stderr }}</pre>
          </div>
        </div>

        <!-- Python 练习卡: python 类型示例的课时展示 -->
        <div v-if="lesson?.codeType === 'python'" class="practice">
          <div class="phead">
            <h3>{{ $t('lesson.practice.pythonTitle') }} <small>{{ $t('lesson.practice.sandboxSub') }}</small></h3>
            <div class="meta-mini">{{ pyResult ? `${pyResult.elapsedMs}ms` : $t('lesson.practice.notRun') }}</div>
          </div>
          <div class="editor-area">
            <CodeEditor v-model="pyCode" language="python" :markers="pyMarkers" @run="runPythonCode" @reset="resetPythonCode" />
          </div>
          <div v-if="pyResult" class="result-area" :class="pyResult?.status?.toLowerCase() || 'normal'">
            <div class="r-head">
              <span v-if="pyResult.status === 'OK'" class="ok"><el-icon><CircleCheckFilled /></el-icon> {{ $t('lesson.result.ok', { exit: pyResult.exitCode, ms: pyResult.elapsedMs }) }}</span>
              <span v-else-if="pyResult.status === 'COMPILE_ERROR'" class="ce"><el-icon><WarningFilled /></el-icon> {{ $t('lesson.result.syntaxError', { n: pyResult.errors?.length || 0 }) }}</span>
              <span v-else-if="pyResult.status === 'RUNTIME_ERROR'" class="re"><el-icon><WarningFilled /></el-icon> {{ $t('lesson.result.runtimeError') }}</span>
              <span v-else-if="pyResult.status === 'TIMEOUT'" class="to"><el-icon><Timer /></el-icon> {{ $t('lesson.result.timeout') }}</span>
              <span v-else>{{ pyResult.status }}</span>
            </div>
            <pre v-if="pyResult.stdout" class="stdout">{{ pyResult.stdout }}</pre>
            <pre v-if="pyResult.stderr" class="stderr">{{ pyResult.stderr }}</pre>
          </div>
        </div>

        <!-- Node.js 练习卡: nodejs 类型示例的课时展示 -->
        <div v-if="lesson?.codeType === 'nodejs'" class="practice">
          <div class="phead">
            <h3>{{ $t('lesson.practice.nodeTitle') }} <small>{{ $t('lesson.practice.sandboxSub') }}</small></h3>
            <div class="meta-mini">{{ nodeResult ? `${nodeResult.elapsedMs}ms` : $t('lesson.practice.notRun') }}</div>
          </div>
          <div class="editor-area">
            <CodeEditor v-model="nodeCode" language="javascript" :markers="nodeMarkers" @run="runNodeCode" @reset="resetNodeCode" />
          </div>
          <div v-if="nodeResult" class="result-area" :class="nodeResult?.status?.toLowerCase() || 'normal'">
            <div class="r-head">
              <span v-if="nodeResult.status === 'OK'" class="ok"><el-icon><CircleCheckFilled /></el-icon> {{ $t('lesson.result.ok', { exit: nodeResult.exitCode, ms: nodeResult.elapsedMs }) }}</span>
              <span v-else-if="nodeResult.status === 'COMPILE_ERROR'" class="ce"><el-icon><WarningFilled /></el-icon> {{ $t('lesson.result.syntaxError', { n: nodeResult.errors?.length || 0 }) }}</span>
              <span v-else-if="nodeResult.status === 'RUNTIME_ERROR'" class="re"><el-icon><WarningFilled /></el-icon> {{ $t('lesson.result.runtimeError') }}</span>
              <span v-else-if="nodeResult.status === 'TIMEOUT'" class="to"><el-icon><Timer /></el-icon> {{ $t('lesson.result.timeout') }}</span>
              <span v-else>{{ nodeResult.status }}</span>
            </div>
            <pre v-if="nodeResult.stdout" class="stdout">{{ nodeResult.stdout }}</pre>
            <pre v-if="nodeResult.stderr" class="stderr">{{ nodeResult.stderr }}</pre>
          </div>
        </div>

        <!-- SQL 练习卡:sql 类型示例,或 Java 课程数据库阶段(stageNo=5)的课时展示 -->
        <div v-if="lesson?.codeType === 'sql' || (lesson?.stageNo === 5 && (!lesson?.codeType || lesson.codeType === 'java'))" class="practice">
          <div class="phead">
            <h3>{{ $t('lesson.practice.sqlTitle') }} <small>{{ $t('lesson.practice.sqlSub') }}</small></h3>
          </div>
          <div style="padding:14px"><SqlConsole :key="lesson.id" v-model="sqlCode" /></div>
        </div>
      </article>
      <el-empty v-else-if="!loading" :description="$t('lesson.empty.desc')">
        <el-button type="primary" @click="backToList">{{ $t('lesson.empty.back') }}</el-button>
      </el-empty>

      <!-- 本章补充内容: 用户自建子节点(记录/补充课程缺失,富文本+Markdown) -->
      <!-- 添加按钮已移至右侧悬浮工具栏;没有任何补充内容时整块隐藏 -->
      <SupplementTree
        v-if="lesson"
        ref="supRef"
        class="sup-block"
        :subject-id="lesson.subjectId"
        :chapter-id="lesson.chapterId"
        :title="$t('lesson.supplement.title')"
        :root-label="$t('lesson.supplement.rootLabel')"
        hide-root-button
        hide-when-empty
      />
    </main>

    <!-- 右：悬浮工具栏(图标竖排,固定右侧,分组分区,悬停显示功能说明) -->
    <aside class="rdock" :class="{ collapsed: rsideCollapsed }">
      <template v-if="!rsideCollapsed">
        <!-- 学习进度 -->
        <el-tooltip placement="left" :show-after="150" :content="lesson?.learned ? $t('lesson.dock.unmarkTip') : $t('lesson.dock.markTip')">
          <span class="dock-slot">
            <button class="dock-btn" :class="{ active: lesson?.learned }" :disabled="toggling"
                    :aria-label="lesson?.learned ? $t('lesson.dock.unmark') : $t('lesson.dock.mark')" @click="toggleLearned">
              <el-icon><CircleCheckFilled v-if="lesson?.learned" /><CircleCheck v-else /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip placement="left" :show-after="150" :content="$t('lesson.dock.completeNextTip')">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.completeNext')"
                    :disabled="!lesson?.nextLessonId" @click="completeAndNext">
              <el-icon><DArrowRight /></el-icon>
            </button>
          </span>
        </el-tooltip>

        <span class="dock-sep" aria-hidden="true" />

        <!-- 课程导航 -->
        <el-tooltip placement="left" :show-after="150" :content="$t('lesson.dock.prevTip')">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.prev')"
                    :disabled="!lesson?.prevLessonId" @click="goPrev">
              <el-icon><ArrowLeft /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip placement="left" :show-after="150" :content="$t('lesson.dock.nextTip')">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.next')"
                    :disabled="!lesson?.nextLessonId" @click="goNext">
              <el-icon><ArrowRight /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip placement="left" :show-after="150" :content="$t('lesson.dock.backTip')">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.back')" @click="backToList">
              <el-icon><Back /></el-icon>
            </button>
          </span>
        </el-tooltip>

        <span class="dock-sep" aria-hidden="true" />

        <!-- 内容与协作 -->
        <el-tooltip placement="left" :show-after="150" :content="$t('lesson.dock.addSupTip')">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.addSup')" @click="addSupplement">
              <el-icon><DocumentAdd /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip v-if="lesson?.codePath" placement="left" :show-after="150"
                    :content="$t('lesson.dock.copyPathTip', { path: lesson.codePath })">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.copyPath')" @click="copyCodePath">
              <el-icon><FolderOpened /></el-icon>
            </button>
          </span>
        </el-tooltip>
        <el-tooltip placement="left" :show-after="150" :content="$t('lesson.dock.feedbackTip')">
          <span class="dock-slot">
            <button class="dock-btn" :aria-label="$t('lesson.dock.feedback')" @click="openFeedback">
              <el-icon><ChatDotSquare /></el-icon>
            </button>
          </span>
        </el-tooltip>

        <span class="dock-sep" aria-hidden="true" />
      </template>
      <el-tooltip placement="left" :show-after="150" :content="rsideCollapsed ? $t('lesson.dock.expand') : $t('lesson.dock.collapse')">
        <span class="dock-slot">
          <button class="dock-btn toggle" :aria-label="rsideCollapsed ? $t('lesson.dock.expand') : $t('lesson.dock.collapse')" @click="toggleRside">
            <el-icon><ArrowLeft v-if="rsideCollapsed" /><ArrowRight v-else /></el-icon>
          </button>
        </span>
      </el-tooltip>
    </aside>

    <!-- 反馈问题/建议(携带来源页面) -->
    <el-dialog v-model="fbVisible" :title="$t('lesson.feedback.dialogTitle')" width="520px" destroy-on-close>
      <el-form label-width="82px">
        <el-form-item :label="$t('lesson.feedback.typeLabel')">
          <el-radio-group v-model="fbForm.type">
            <el-radio-button value="BUG">{{ $t('lesson.feedback.typeBug') }}</el-radio-button>
            <el-radio-button value="SUGGESTION">{{ $t('lesson.feedback.typeSuggestion') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="fbForm.type === 'BUG' ? $t('lesson.feedback.bugTitle') : $t('lesson.feedback.suggestTitle')">
          <el-input v-model="fbForm.title" maxlength="100"
                    :placeholder="fbForm.type === 'BUG' ? $t('lesson.feedback.bugTitlePh') : $t('lesson.feedback.suggestTitlePh')" />
        </el-form-item>
        <el-form-item :label="fbForm.type === 'BUG' ? $t('lesson.feedback.bugDesc') : $t('lesson.feedback.suggestDesc')">
          <el-input v-model="fbForm.content" type="textarea" :rows="4" maxlength="2000"
                    :placeholder="fbForm.type === 'BUG' ? $t('lesson.feedback.bugDescPh') : $t('lesson.feedback.suggestDescPh')" />
        </el-form-item>
        <el-form-item :label="$t('lesson.feedback.contact')">
          <el-input v-model="fbForm.contact" maxlength="100" :placeholder="$t('lesson.feedback.contactPh')" />
        </el-form-item>
        <el-form-item :label="$t('lesson.feedback.source')">
          <el-input :model-value="fbSource" readonly />
          <small class="fb-tip">{{ $t('lesson.feedback.sourceTip') }}</small>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="fbVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="fbSubmitting" @click="submitFb">{{ $t('lesson.feedback.submit') }}</el-button>
      </template>
    </el-dialog>

    <!-- 回到顶部(跟随正文滚动区域) -->
    <!-- 移动端浮动"目录"按钮: 唤出章节目录抽屉 -->
    <button
      v-if="isMobile"
      class="toc-fab"
      :class="{ open: tocOpen }"
      :aria-label="tocOpen ? $t('lesson.toc.close') : $t('lesson.toc.open')"
      @click="toggleToc"
    >
      <el-icon><Close v-if="tocOpen" /><Notebook v-else /></el-icon>
      <span v-if="!tocOpen">{{ $t('lesson.toc.label') }}</span>
    </button>

    <transition name="fade">
      <button v-if="showTop" class="to-top" :aria-label="$t('lesson.toTop')" :title="$t('lesson.toTop')" @click="scrollToTop">
        <el-icon><ArrowUp /></el-icon>
      </button>
    </transition>
  </div>
</template>

<style scoped>
/* 双栏 + 右侧悬浮导航:内容区占满剩余宽度,导航面板悬浮固定在右侧 */
.layout { display:grid; grid-template-columns:280px 1fr; height:calc(100vh - 52px); overflow:hidden }

/* 左:目录栏 — 独立滚动,不随正文滚动;顶部紧贴头部,不留缝 */
.lside { background:#fff; border-right:1px solid var(--c-border); overflow-y:auto; height:100% }
.lhead { padding:12px 20px 10px 28px; border-bottom:1px solid var(--c-border) }
.crumb { font-size:var(--fs-xs); color:var(--c-text-3); margin-bottom:4px; display:flex; align-items:center; gap:4px }
.lhead-title { margin:0; font-size:var(--fs-md); font-weight:600 }
.parts { padding:2px 0 }
/* 一级标题与左侧主菜单之间留出间距,层级更清晰 */
.part-h { padding:8px 20px 4px 28px; font-size:var(--fs-xs); color:var(--c-text-3); letter-spacing:.5px; text-transform:uppercase; font-weight:600 }
/* 章节折叠头压扁(Element Plus 默认 min-height:48px 会撑高,需一并覆盖)
   注意:不能定死 height+line-height,长标题换行会被裁切/溢出叠到下一节点——
   用 min-height 保底 + 自然行高,单行仍为 38px,多行自动撑高 */
.parts :deep(.el-collapse-item__header) {
  height:auto; min-height:38px; padding:8px 20px 8px 28px; line-height:1.5;
  font-size:var(--fs-sm); font-weight:500; color:var(--c-text-1)
}
.parts :deep(.el-collapse-item__wrap) { border-bottom:none }
.parts :deep(.el-collapse-item__content) { padding-bottom:0 }
.lesson-item { display:flex; align-items:center; gap:10px; padding:9px 20px 9px 28px; cursor:pointer; border-left:2px solid transparent; font-size:var(--fs-sm) }
.lesson-item:hover { background:#f7f8fb }
.lesson-item.active { background:var(--c-primary-bg); border-left-color:var(--c-primary) }
.lesson-item .ic { color:#64748b; font-size:var(--fs-sm) }
.lesson-item .ic.done { color:var(--c-success) }
.lesson-item .t { color:var(--c-text-1) }

/* 中:正文栏 — 独立滚动,滚动条只作用于正文 */
.center { overflow-y:auto; height:100%; background:#f7f8fb }
.content { max-width:920px; margin:0 auto; padding:32px 48px; animation:lessonIn .22s ease }
@keyframes lessonIn { from { opacity:0; transform:translateY(6px) } to { opacity:1; transform:none } }
.lesson-h h1 { font-size:var(--fs-xl); margin:0 0 12px; line-height:1.3 }
.meta-row { display:flex; gap:8px; align-items:center; margin-bottom:24px; flex-wrap:wrap }
.meta-item { font-size:var(--fs-sm); color:var(--c-text-3); display:inline-flex; align-items:center; gap:4px }
.summary { background:var(--c-primary-bg); border-left:3px solid var(--c-primary); padding:14px 18px; border-radius:var(--r-s); color:var(--c-text-2); font-size:var(--fs-md); margin-bottom:28px }
/* Markdown 正文(marked 渲染,支持基本语法) */
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3), .md-body :deep(h4) {
  font-size:var(--fs-lg); margin:30px 0 12px; padding-left:12px; border-left:3px solid var(--c-primary); color:var(--c-text-1)
}
.md-body :deep(h1) { font-size:var(--fs-xl) }
.md-body :deep(h4) { font-size:var(--fs-md) }
.md-body :deep(p) { font-size:var(--fs-md); line-height:1.85; margin:0 0 14px; color:var(--c-text-1) }
.md-body :deep(ul), .md-body :deep(ol) { padding-left:22px; margin:0 0 14px }
.md-body :deep(li) { font-size:var(--fs-md); line-height:1.85; margin-bottom:6px; color:var(--c-text-1) }
.md-body :deep(li::marker) { color:var(--c-primary) }
.md-body :deep(code) {
  font-family:"SFMono-Regular",Consolas,Menlo,monospace; font-size:.88em;
  background:#f1f4f9; color:#b93a86; padding:1px 6px; border-radius:4px
}
.md-body :deep(pre) {
  margin:0 0 16px; padding:16px 18px; background:#0f172a; color:#e2e8f0;
  border-radius:var(--r-s); overflow-x:auto; font-family:"SFMono-Regular",Consolas,Menlo,monospace;
  font-size:var(--fs-sm); line-height:1.7; tab-size:4
}
.md-body :deep(pre code) { background:none; color:inherit; padding:0; font-size:inherit }
.md-body :deep(blockquote) {
  margin:8px 0 16px; padding:10px 16px; border-left:3px solid var(--c-primary);
  background:var(--c-primary-bg); color:var(--c-text-2); border-radius:0 var(--r-s) var(--r-s) 0
}
.md-body :deep(blockquote p) { margin:0 }
.md-body :deep(a) { color:var(--c-primary); text-decoration:underline }
.md-body :deep(hr) { border:none; border-top:1px solid var(--c-border); margin:20px 0 }
.md-body :deep(table) { border-collapse:collapse; margin:0 0 16px; width:100%; font-size:var(--fs-sm) }
.md-body :deep(th), .md-body :deep(td) { border:1px solid var(--c-border); padding:8px 12px; text-align:left }
.md-body :deep(th) { background:#f8fafd; color:var(--c-text-2); font-weight:600 }
.md-body :deep(img) { max-width:100%; border-radius:var(--r-s) }

/* 练习卡 */
.practice { margin:30px 0; background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); overflow:hidden; box-shadow:0 2px 6px rgba(20,30,60,.06); scroll-margin-top:80px }
.example-code { margin:0; padding:18px 20px; background:#0f172a; color:#e2e8f0; overflow-x:auto; font-family:var(--font-mono, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace); font-size:var(--fs-sm); line-height:1.7; tab-size:2 }
.example-code code { background:none; padding:0; color:inherit; font-size:inherit }
.phead { display:flex; align-items:center; justify-content:space-between; padding:14px 18px; background:linear-gradient(180deg,#fafbff,#fff); border-bottom:1px solid var(--c-border) }
.phead h3 { margin:0; font-size:var(--fs-md) }
.phead small { color:var(--c-text-3); font-weight:400; margin-left:8px; font-size:var(--fs-xs) }
.phead .meta-mini { font-size:var(--fs-xs); color:var(--c-text-3); font-family:'JetBrains Mono',monospace }

.editor-area { padding:14px }
.result-area { border-top:1px solid var(--c-border); padding:14px 18px; font-family:'JetBrains Mono',Menlo,monospace; font-size:var(--fs-sm) }
.result-area.ok { background:#f6fcf8; border-top-color:var(--c-success) }
.result-area.ce, .result-area.re { background:#fef2f2; border-top-color:#ef4444 }
.result-area.to { background:#fff7e6; border-top-color:#f59e0b }
.r-head { margin-bottom:10px; font-size:var(--fs-base); display:flex; align-items:center; gap:4px }
.r-head .ok { color:#146c33; font-weight:500 }
.r-head .ce, .r-head .re { color:#c12828; font-weight:500 }
.r-head .to { color:#b45309; font-weight:500 }
.stdout, .stderr { margin:8px 0 0; white-space:pre-wrap; color:var(--c-text-1) }
.stderr { color:#991b1b; font-size:var(--fs-xs) }

/* 右:悬浮工具栏 — 图标竖排,垂直居中固定右侧,按功能分组(进度/导航/协作),悬停显示功能说明 */
.rdock {
  position:fixed; right:16px; top:50%; transform:translateY(-50%); width:46px; z-index:30;
  display:flex; flex-direction:column; align-items:center; gap:2px;
  background:rgba(255,255,255,.96); backdrop-filter:blur(8px);
  border:1px solid var(--c-border); border-radius:12px;
  padding:6px 5px; box-shadow:0 8px 24px rgba(20,30,60,.12), 0 1px 3px rgba(20,30,60,.06);
  transition:width .18s ease
}
.rdock.collapsed {
  width:36px; padding:0; border-radius:50%; height:36px; justify-content:center
}
/* tooltip 触发插槽: 包住 button,保证 disabled 时悬停也能出提示 */
.dock-slot { display:flex; border-radius:9px }
/* 分组分隔线(进度 / 导航 / 协作 三组) */
.dock-sep { width:20px; height:1px; background:var(--c-border); margin:4px 0; flex:none }
.dock-btn {
  width:34px; height:34px; padding:0; flex:none;
  display:flex; align-items:center; justify-content:center;
  border:none; border-radius:9px; background:transparent;
  color:var(--c-text-2); cursor:pointer; font-size:15px;
  transition:background .18s ease, color .18s ease, transform .18s ease, box-shadow .18s ease
}
/* hover: 明显的悬浮反馈 — 填充主题色 + 图标放大 + 轻投影 */
.dock-btn:hover:not(:disabled) {
  background:var(--c-primary); color:#fff;
  transform:translateY(-2px) scale(1.08);
  box-shadow:0 6px 14px rgba(64,158,255,.35)
}
.dock-btn:active:not(:disabled) { transform:translateY(0) scale(.95); box-shadow:none }
.dock-btn:disabled { opacity:.32; cursor:not-allowed }
.dock-btn.active { color:var(--c-success) }
.dock-btn.active:hover:not(:disabled) {
  background:var(--c-success); color:#fff;
  box-shadow:0 6px 14px rgba(34,197,94,.35)
}
.rdock.collapsed .dock-btn { width:100%; height:100%; border-radius:50% }

.fb-tip { color:var(--c-text-3); font-size:var(--fs-xs); line-height:1.5 }

.sup-block { margin-top:18px }

/* 回到顶部按钮(跟随正文滚动区域显示) */
.to-top {
  position:fixed; right:24px; bottom:24px; width:40px; height:40px; z-index:30;
  display:flex; align-items:center; justify-content:center;
  border:1px solid var(--c-border); border-radius:50%; background:#fff; color:var(--c-text-2);
  box-shadow:0 6px 16px rgba(20,30,60,.14); cursor:pointer; font-size:18px
}
.to-top:hover {
  color:#fff; background:var(--c-primary); border-color:var(--c-primary);
  transform:translateY(-2px); box-shadow:0 8px 18px rgba(64,158,255,.35)
}
.to-top { transition:background .18s ease, color .18s ease, transform .18s ease, box-shadow .18s ease }
.fade-enter-active, .fade-leave-active { transition:opacity .18s ease }

/* ============ 移动端(<=768px): 目录收进抽屉,正文全宽,工具栏贴右下 ============ */
.toc-mask {
  position:fixed; inset:0; z-index:39;
  background:rgba(15,23,42,.45); backdrop-filter:blur(2px)
}
.toc-fab {
  position:fixed; left:14px; bottom:18px; z-index:41;
  display:inline-flex; align-items:center; gap:5px;
  height:42px; padding:0 16px;
  border:1px solid var(--c-border); border-radius:var(--r-full); background:#fff; color:var(--c-text-1);
  box-shadow:0 8px 20px rgba(20,30,60,.18); cursor:pointer; font-size:var(--fs-sm); font-weight:500
}
.toc-fab.open { background:var(--c-primary); color:#fff; border-color:var(--c-primary) }
@media (max-width: 768px) {
  .layout { display:block; height:calc(100vh - 52px); overflow-y:auto }
  .lside {
    position:fixed; left:0; top:52px; bottom:0; width:min(300px, 82vw); z-index:40;
    transform:translateX(-100%); transition:transform .22s cubic-bezier(.4,0,.2,1);
    box-shadow:8px 0 24px rgba(15,23,42,.18)
  }
  .toc-open .lside { transform:translateX(0) }
  .center { height:auto; overflow:visible }
  .content { max-width:none; padding:20px 16px 90px }
  .lesson-h h1 { font-size:var(--fs-lg) }
  .phead { flex-wrap:wrap; gap:6px }
  .phead small { display:none }
  .editor-area { padding:10px }
  .result-area { padding:12px }
  /* 右侧工具栏: 竖排贴右缘,不遮正文 */
  .rdock { right:8px; padding:4px 3px }
  .dock-btn { width:30px; height:30px }
  .to-top { right:14px; bottom:70px }
  /* 正文表格横向滚动,不撑破布局 */
  .md-body :deep(table) { display:block; overflow-x:auto; white-space:nowrap }
  .md-body :deep(pre) { font-size:var(--fs-xs) }
}
@media (max-width: 400px) {
  .content { padding:16px 12px 90px }
  .rdock { transform:translateY(-50%) scale(.92); transform-origin:right center }
}
.fade-enter-from, .fade-leave-to { opacity:0 }
</style>