<script setup>
// 课时学习页: 正文 + 代码练习 + SQL 控制台 + 导航
// 设计稿风格:三栏(章节侧栏 / 正文 / 进度侧栏),代码区与 SQL 控制台直接内嵌正文
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getCurriculum, getLesson, markLearned, unmarkLearned } from '../api/learning'
import { runJava, runPython, runNode } from '../api/sandbox'
import { lessonCrumbTitle } from '../store/crumb'
import { useUserStore } from '../store/user'
import { renderMarkdown } from '../utils/markdown'
import CodeEditor from '../components/CodeEditor.vue'
import SqlConsole from '../components/SqlConsole.vue'
import SupplementTree from '../components/SupplementTree.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 游客可浏览课时;标记完成等个人操作需登录
const isGuest = computed(() => !userStore.isLogin)
const promptLogin = () => {
  ElMessageBox.confirm('登录后即可标记学习完成、记录学习进度', '该功能需要登录', {
    confirmButtonText: '去登录',
    cancelButtonText: '暂不登录',
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

const difficultyMap = { 1: { text: '简单', type: 'success' }, 2: { text: '中等', type: 'warning' }, 3: { text: '较难', type: 'danger' } }

// ============== 课程树与选中(限定当前课时的语言) ==============
const curriculumSubjectId = ref(undefined)   // undefined=未加载
const loadCurriculum = async (subjectId) => {
  try {
    const res = await getCurriculum(subjectId)
    curriculum.value = res.data || []
    curriculumSubjectId.value = subjectId ?? null
  } catch (e) {}
}

// ============== 课时正文(开源 marked 渲染,支持基本 Markdown 语法) ==============
const renderedContent = computed(() => renderMarkdown(lesson.value?.content || ''))

const loadLesson = async () => {
  // 局部更新: 保留旧正文直到新数据到达,只在首次加载时显示整块 loading(避免点击章节时页面闪动)
  const firstLoad = !lesson.value
  if (firstLoad) loading.value = true
  try {
    const res = await getLesson(route.params.id)
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
    javaCode.value = lesson.value.codeExample || javaCode.value
    // SQL 类型示例灌入 SQL 控制台(而非 Java 编辑器)
    if (lesson.value.codeType === 'sql') sqlCode.value = lesson.value.codeExample || ''
    // Python 类型示例灌入 Python 编辑器
    if (lesson.value.codeType === 'python') pyCode.value = lesson.value.codeExample || ''
    // Node.js 类型示例灌入 Node 编辑器
    if (lesson.value.codeType === 'nodejs') nodeCode.value = lesson.value.codeExample || ''
    // 切换课时后内容区回到顶部(平滑滚动,不整页闪动)
    if (!firstLoad) centerRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) {} finally {
    loading.value = false
  }
}

// ============== 进度 ==============
// 同步左侧目录的完成图标与章节进度(修复"标记完成后图标不更新")
const syncLearned = (lessonId, learned) => {
  for (const stage of curriculum.value) {
    for (const ch of stage.chapters) {
      const ls = ch.lessons.find(l => l.id === lessonId)
      if (ls) {
        ls.learned = learned
        ch.learnedLessons = ch.lessons.filter(l => l.learned).length
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
      ElMessage.info('已取消完成标记')
    } else {
      await markLearned(lesson.value.id)
      lesson.value.learned = true
      syncLearned(lesson.value.id, true)
      ElMessage.success('恭喜完成本课时!')
    }
  } finally {
    toggling.value = false
  }
}

const completeAndNext = async () => {
  if (!lesson.value) return
  if (isGuest.value) return promptLogin()   // 游客点击 → 引导登录
  if (!lesson.value.learned) {
    await markLearned(lesson.value.id)
    lesson.value.learned = true
    syncLearned(lesson.value.id, true)
  }
  if (lesson.value.nextLessonId) {
    router.push(`/learning/lesson/${lesson.value.nextLessonId}`)
    ElMessage.success('已记录, 进入下一课')
  } else {
    ElMessage.success('已记录。你已学完全部课程!')
  }
}

const goPrev = () => lesson.value?.prevLessonId && router.push(`/learning/lesson/${lesson.value.prevLessonId}`)
const goNext = () => lesson.value?.nextLessonId && router.push(`/learning/lesson/${lesson.value.nextLessonId}`)
const backToList = () => router.push('/learning')

const openLesson = (id) => router.push(`/learning/lesson/${id}`)

// 键盘可达:课时条目 / 导航行支持 Enter 与空格触发
const onKeyActivate = (e, fn) => {
  if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fn() }
}

// ============== Java 沙箱 ==============
const javaCode = ref(`public class Main {
    public static void main(String[] args) {
        System.out.println("Hello, Sandbox!");
    }
}`)
const sqlCode = ref('')
const javaMarkers = ref([])
const javaResult = ref(null)
const javaRunning = ref(false)

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
  javaCode.value = lesson.value?.codeExample || javaCode.value
  javaMarkers.value = []
  javaResult.value = null
}

// ============== Python 沙箱 ==============
const pyCode = ref("print('Hello, Python!')")
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
  pyCode.value = lesson.value?.codeExample || pyCode.value
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
  nodeCode.value = lesson.value?.codeExample || nodeCode.value
  nodeMarkers.value = []
  nodeResult.value = null
}

watch(() => route.params.id, () => { if (route.name === 'LessonDetail') loadLesson() })

onMounted(async () => {
  await loadLesson()   // 内部按课时语言加载目录
})
</script>

<template>
  <div class="layout">
    <!-- 左：章节侧栏 -->
    <aside class="lside">
      <div class="lhead">
        <div class="crumb"><el-icon><Reading /></el-icon> 学习中心</div>
        <div class="lhead-title" v-if="lesson">{{ lesson.stageName }}</div>
        <div class="lhead-title" v-else>课程目录</div>
      </div>
      <div class="parts">
        <template v-for="stage in curriculum" :key="stage.stageNo">
          <div class="part-h">{{ stage.stageName }}</div>
          <el-collapse v-model="expandedChapters">
            <el-collapse-item
              v-for="ch in stage.chapters"
              :key="ch.id"
              :name="String(ch.id)"
              :title="`第${ch.chapterNo}章 ${ch.title}`"
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
            <span class="meta-item"><el-icon><Timer /></el-icon> {{ lesson.durationMinutes }} 分钟</span>
            <el-tag v-if="lesson.learned" type="success" size="small">已学完</el-tag>
          </div>
        </div>

        <p v-if="lesson.summary" class="summary">{{ lesson.summary }}</p>

        <!-- 正文: 开源 marked 渲染的 Markdown(支持标题/列表/表格/代码块/行内代码/链接等基本语法) -->
        <div class="md-body" v-html="renderedContent"></div>

        <!-- 代码练习卡(sql/python 类型课时不展示 Java 沙箱,避免语言错配) -->
        <div v-if="!lesson?.codeType || lesson.codeType === 'java'" class="practice">
          <div class="phead">
            <h3>动手做 · Java 沙箱 <small>单文件代码 · 5 秒超时</small></h3>
            <div class="meta-mini">{{ javaResult ? `${javaResult.elapsedMs}ms` : '尚未运行' }}</div>
          </div>
          <div class="editor-area">
            <CodeEditor v-model="javaCode" :markers="javaMarkers" @run="runJavaCode" @reset="resetJavaCode" />
          </div>
          <div v-if="javaResult" class="result-area" :class="javaResult.status.toLowerCase()">
            <div class="r-head">
              <span v-if="javaResult.status === 'OK'" class="ok"><el-icon><CircleCheckFilled /></el-icon> 运行成功 · {{ javaResult.exitCode }} · {{ javaResult.elapsedMs }}ms</span>
              <span v-else-if="javaResult.status === 'COMPILE_ERROR'" class="ce"><el-icon><WarningFilled /></el-icon> 编译错误 · {{ javaResult.errors?.length || 0 }} 处</span>
              <span v-else-if="javaResult.status === 'RUNTIME_ERROR'" class="re"><el-icon><WarningFilled /></el-icon> 运行时异常</span>
              <span v-else-if="javaResult.status === 'TIMEOUT'" class="to"><el-icon><Timer /></el-icon> 执行超时(&gt;5s)</span>
              <span v-else>{{ javaResult.status }}</span>
            </div>
            <pre v-if="javaResult.stdout" class="stdout">{{ javaResult.stdout }}</pre>
            <pre v-if="javaResult.stderr" class="stderr">{{ javaResult.stderr }}</pre>
          </div>
        </div>

        <!-- Python 练习卡: python 类型示例的课时展示 -->
        <div v-if="lesson?.codeType === 'python'" class="practice">
          <div class="phead">
            <h3>动手做 · Python 沙箱 <small>单文件代码 · 5 秒超时</small></h3>
            <div class="meta-mini">{{ pyResult ? `${pyResult.elapsedMs}ms` : '尚未运行' }}</div>
          </div>
          <div class="editor-area">
            <CodeEditor v-model="pyCode" language="python" :markers="pyMarkers" @run="runPythonCode" @reset="resetPythonCode" />
          </div>
          <div v-if="pyResult" class="result-area" :class="pyResult.status.toLowerCase()">
            <div class="r-head">
              <span v-if="pyResult.status === 'OK'" class="ok"><el-icon><CircleCheckFilled /></el-icon> 运行成功 · {{ pyResult.exitCode }} · {{ pyResult.elapsedMs }}ms</span>
              <span v-else-if="pyResult.status === 'COMPILE_ERROR'" class="ce"><el-icon><WarningFilled /></el-icon> 语法错误 · {{ pyResult.errors?.length || 0 }} 处</span>
              <span v-else-if="pyResult.status === 'RUNTIME_ERROR'" class="re"><el-icon><WarningFilled /></el-icon> 运行时异常</span>
              <span v-else-if="pyResult.status === 'TIMEOUT'" class="to"><el-icon><Timer /></el-icon> 执行超时(&gt;5s)</span>
              <span v-else>{{ pyResult.status }}</span>
            </div>
            <pre v-if="pyResult.stdout" class="stdout">{{ pyResult.stdout }}</pre>
            <pre v-if="pyResult.stderr" class="stderr">{{ pyResult.stderr }}</pre>
          </div>
        </div>

        <!-- Node.js 练习卡: nodejs 类型示例的课时展示 -->
        <div v-if="lesson?.codeType === 'nodejs'" class="practice">
          <div class="phead">
            <h3>动手做 · Node.js 沙箱 <small>单文件代码 · 5 秒超时</small></h3>
            <div class="meta-mini">{{ nodeResult ? `${nodeResult.elapsedMs}ms` : '尚未运行' }}</div>
          </div>
          <div class="editor-area">
            <CodeEditor v-model="nodeCode" language="javascript" :markers="nodeMarkers" @run="runNodeCode" @reset="resetNodeCode" />
          </div>
          <div v-if="nodeResult" class="result-area" :class="nodeResult.status.toLowerCase()">
            <div class="r-head">
              <span v-if="nodeResult.status === 'OK'" class="ok"><el-icon><CircleCheckFilled /></el-icon> 运行成功 · {{ nodeResult.exitCode }} · {{ nodeResult.elapsedMs }}ms</span>
              <span v-else-if="nodeResult.status === 'COMPILE_ERROR'" class="ce"><el-icon><WarningFilled /></el-icon> 语法错误 · {{ nodeResult.errors?.length || 0 }} 处</span>
              <span v-else-if="nodeResult.status === 'RUNTIME_ERROR'" class="re"><el-icon><WarningFilled /></el-icon> 运行时异常</span>
              <span v-else-if="nodeResult.status === 'TIMEOUT'" class="to"><el-icon><Timer /></el-icon> 执行超时(&gt;5s)</span>
              <span v-else>{{ nodeResult.status }}</span>
            </div>
            <pre v-if="nodeResult.stdout" class="stdout">{{ nodeResult.stdout }}</pre>
            <pre v-if="nodeResult.stderr" class="stderr">{{ nodeResult.stderr }}</pre>
          </div>
        </div>

        <!-- SQL 练习卡:数据库阶段(stageNo=5)或 sql 类型示例的课时展示 -->
        <div v-if="lesson?.stageNo === 5 || lesson?.codeType === 'sql'" class="practice">
          <div class="phead">
            <h3>动手做 · SQL 沙箱 <small>教学库 sandbox.db · 仅 DML</small></h3>
          </div>
          <div style="padding:14px"><SqlConsole v-model="sqlCode" /></div>
        </div>
      </article>
      <el-empty v-else-if="!loading" description="课时不存在或已下线">
        <el-button type="primary" @click="backToList">返回学习中心</el-button>
      </el-empty>

      <!-- 本章补充内容: 用户自建子节点(记录/补充课程缺失,富文本+Markdown) -->
      <SupplementTree
        v-if="lesson"
        class="sup-block"
        :chapter-id="lesson.chapterId"
        title="本章补充内容"
        root-label="添加补充内容"
      />
    </main>

    <!-- 右：导航与笔记 -->
    <aside class="rside">
      <div class="next-up" v-if="lesson?.nextLessonId">
        <small>下一课时</small>
        <b>前往下一课</b>
        <el-button size="small" plain class="next-btn" @click="completeAndNext">
          学完并下一课
        </el-button>
      </div>

      <div class="action-card">
        <el-button :type="lesson?.learned ? 'success' : 'primary'" :plain="lesson?.learned" :loading="toggling" @click="toggleLearned" style="width:100%;margin-bottom:8px">
          {{ lesson?.learned ? '取消完成' : '标记为已学' }}
        </el-button>
        <div v-if="isGuest" class="guest-tip">当前为游客访问,登录后可记录学习进度</div>
        <div class="nav-row" :class="{ disabled: !lesson?.prevLessonId }" role="button" tabindex="0"
             :aria-disabled="!lesson?.prevLessonId" @click="goPrev" @keydown="onKeyActivate($event, goPrev)">
          <span><el-icon><ArrowLeft /></el-icon> 上一课</span>
          <span class="v">{{ lesson?.prevLessonId ? '上一课时' : '已是第一课' }}</span>
        </div>
        <div class="nav-row" :class="{ disabled: !lesson?.nextLessonId }" role="button" tabindex="0"
             :aria-disabled="!lesson?.nextLessonId" @click="goNext" @keydown="onKeyActivate($event, goNext)">
          <span>下一课 <el-icon><ArrowRight /></el-icon></span>
          <span class="v">{{ lesson?.nextLessonId ? '下一课时' : '已是最后一课' }}</span>
        </div>
        <div class="nav-row" role="button" tabindex="0" @click="backToList" @keydown="onKeyActivate($event, backToList)">
          <span><el-icon><Back /></el-icon> 返回目录</span>
          <span class="v">课程总览</span>
        </div>
      </div>

      <div v-if="lesson?.codePath" class="code-path-card">
        <div class="card-title"><el-icon><FolderOpened /></el-icon> 对应练习代码</div>
        <div class="code-path">{{ lesson.codePath }}</div>
        <p class="tip">学完本课后, 建议到根模块找到对应示例亲手练习。</p>
      </div>
    </aside>
  </div>
</template>

<style scoped>
.layout { display:grid; grid-template-columns:280px 1fr 280px; min-height:calc(100vh - 52px) }

/* 左 */
.lside { background:#fff; border-right:1px solid var(--c-border); overflow:auto; height:calc(100vh - 52px); position:sticky; top:52px }
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

/* 中 */
.center { overflow:auto; background:#f7f8fb }
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

/* 右 */
.rside { background:#fff; border-left:1px solid var(--c-border); padding:12px 14px; overflow:auto; height:calc(100vh - 52px); position:sticky; top:52px }
.next-up { background:linear-gradient(135deg,#2563eb,#4f46e5); color:#fff; border-radius:var(--r-m); padding:12px; margin-bottom:10px }
.next-up small { font-size:var(--fs-xs); opacity:.85; display:block; margin-bottom:5px }
.next-up b { font-size:var(--fs-base); display:block; margin-bottom:8px }
.next-up .next-btn { color:#fff; border-color:rgba(255,255,255,.4); background:rgba(255,255,255,.1); width:100% }
.action-card { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:12px; margin-bottom:12px }
.guest-tip { margin:-2px 0 8px; font-size:var(--fs-xs); color:var(--c-text-3); text-align:center; line-height:1.5 }
.nav-row { display:flex; justify-content:space-between; align-items:center; padding:8px 4px; border-radius:var(--r-s); cursor:pointer; font-size:var(--fs-sm); color:var(--c-text-1) }
.nav-row:hover { background:#f5f7fa }
.nav-row.disabled { cursor:not-allowed; opacity:.5 }
.nav-row .v { color:var(--c-text-3); font-size:var(--fs-xs) }
.nav-row > span:first-child { display:inline-flex; align-items:center; gap:4px }
.code-path-card { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:14px }
.card-title { font-size:var(--fs-sm); font-weight:600; margin-bottom:8px; color:var(--c-text-1); display:flex; align-items:center; gap:4px }
.code-path { background:var(--c-primary-bg); color:var(--c-primary-hover); padding:8px 10px; border-radius:var(--r-s); font-size:var(--fs-xs); word-break:break-all; font-family:var(--font-mono) }
.tip { color:var(--c-text-3); font-size:var(--fs-xs); margin:8px 0 0; line-height:1.6 }
.sup-block { margin-top:18px }
</style>