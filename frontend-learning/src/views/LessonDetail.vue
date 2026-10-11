<script setup>
// 课时学习页: 正文 + 代码练习 + SQL 控制台 + 导航
// 设计稿风格:三栏(章节侧栏 / 正文 / 进度侧栏),代码区与 SQL 控制台直接内嵌正文
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getCurriculum, getLesson, markLearned, unmarkLearned } from '../api/learning'
import { runJava } from '../api/sandbox'
import { lessonCrumbTitle } from '../store/crumb'
import CodeEditor from '../components/CodeEditor.vue'
import SqlConsole from '../components/SqlConsole.vue'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const lesson = ref(null)
const curriculum = ref([])
const expandedChapters = ref([])
const centerRef = ref(null)

const difficultyMap = { 1: { text: '简单', type: 'success' }, 2: { text: '中等', type: 'warning' }, 3: { text: '较难', type: 'danger' } }

// ============== 课程树与选中 ==============
const loadCurriculum = async () => {
  try {
    const res = await getCurriculum()
    curriculum.value = res.data || []
  } catch (e) {}
}

// ============== 课时正文 + Markdown 解析 ==============
// 轻量 Markdown: "## " 标题 / "- " 列表 / ``` 代码围栏 / `行内代码` / 其余段落
const blocks = computed(() => {
  const text = lesson.value?.content || ''
  const result = []
  let list = null
  let code = null
  for (const raw of text.split('\n')) {
    const line = raw.trimEnd()
    if (line.trim().startsWith('```')) {
      if (list) { result.push(list); list = null }
      if (code) { result.push({ type: 'code', text: code.join('\n') }); code = null }
      else code = []
      continue
    }
    if (code) { code.push(raw); continue }
    if (line.startsWith('## ')) {
      if (list) { result.push(list); list = null }
      result.push({ type: 'h', text: line.slice(3) })
    } else if (line.startsWith('- ')) {
      if (!list) list = { type: 'ul', items: [] }
      list.items.push(line.slice(2))
    } else if (line.trim()) {
      if (list) { result.push(list); list = null }
      result.push({ type: 'p', text: line })
    }
  }
  if (code) result.push({ type: 'code', text: code.join('\n') })
  if (list) result.push(list)
  return result
})

// 行内 `code` 拆成片段,供模板渲染 <code>
const fmt = (t) => {
  const parts = []
  const re = /`([^`]+)`/g
  let last = 0
  let m
  while ((m = re.exec(t))) {
    if (m.index > last) parts.push({ code: false, s: t.slice(last, m.index) })
    parts.push({ code: true, s: m[1] })
    last = m.index + m[0].length
  }
  if (last < t.length) parts.push({ code: false, s: t.slice(last) })
  return parts.length ? parts : [{ code: false, s: t }]
}

const loadLesson = async () => {
  // 局部更新: 保留旧正文直到新数据到达,只在首次加载时显示整块 loading(避免点击章节时页面闪动)
  const firstLoad = !lesson.value
  if (firstLoad) loading.value = true
  try {
    const res = await getLesson(route.params.id)
    lesson.value = res.data
    // 面包屑动态标题: 首页 / 学习中心 / <课时标题>
    lessonCrumbTitle.value = lesson.value.title
    if (lesson.value.chapterId) {
      const cid = String(lesson.value.chapterId)
      if (!expandedChapters.value.includes(cid)) expandedChapters.value.push(cid)
    }
    javaCode.value = lesson.value.codeExample || javaCode.value
    // 切换课时后内容区回到顶部(平滑滚动,不整页闪动)
    if (!firstLoad) centerRef.value?.scrollTo({ top: 0, behavior: 'smooth' })
  } catch (e) {} finally {
    loading.value = false
  }
}

// ============== 进度 ==============
const toggling = ref(false)
const toggleLearned = async () => {
  if (!lesson.value || toggling.value) return
  toggling.value = true
  try {
    if (lesson.value.learned) {
      await unmarkLearned(lesson.value.id)
      lesson.value.learned = false
      ElMessage.info('已取消完成标记')
    } else {
      await markLearned(lesson.value.id)
      lesson.value.learned = true
      ElMessage.success('恭喜完成本课时!')
    }
  } finally {
    toggling.value = false
  }
}

const completeAndNext = async () => {
  if (!lesson.value) return
  if (!lesson.value.learned) await markLearned(lesson.value.id)
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

watch(() => route.params.id, () => { if (route.name === 'LessonDetail') loadLesson() })

onMounted(async () => {
  await loadCurriculum()
  await loadLesson()
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

        <template v-for="(block, i) in blocks" :key="i">
          <h3 v-if="block.type === 'h'" class="md-h">
            <template v-for="(p, j) in fmt(block.text)" :key="j"><code v-if="p.code" class="md-ic">{{ p.s }}</code><template v-else>{{ p.s }}</template></template>
          </h3>
          <p v-else-if="block.type === 'p'" class="md-p">
            <template v-for="(p, j) in fmt(block.text)" :key="j"><code v-if="p.code" class="md-ic">{{ p.s }}</code><template v-else>{{ p.s }}</template></template>
          </p>
          <ul v-else-if="block.type === 'ul'" class="md-ul">
            <li v-for="(item, j) in block.items" :key="j">
              <template v-for="(p, k) in fmt(item)" :key="k"><code v-if="p.code" class="md-ic">{{ p.s }}</code><template v-else>{{ p.s }}</template></template>
            </li>
          </ul>
          <pre v-else-if="block.type === 'code'" class="md-code"><code>{{ block.text }}</code></pre>
        </template>

        <!-- 代码练习卡 -->
        <div class="practice">
          <div class="phead">
            <h4>动手做 · Java 沙箱 <small>单文件代码 · 5 秒超时</small></h4>
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

        <!-- SQL 练习卡:仅"数据库与 JDBC"阶段(stageNo=5)的课时展示,避免其他课时出现无关面板 -->
        <div v-if="lesson?.stageNo === 5" class="practice">
          <div class="phead">
            <h4>动手做 · SQL 沙箱 <small>教学库 sandbox.db · 仅 DML</small></h4>
          </div>
          <div style="padding:14px"><SqlConsole /></div>
        </div>
      </article>
      <el-empty v-else-if="!loading" description="课时不存在或已下线">
        <el-button type="primary" @click="backToList">返回学习中心</el-button>
      </el-empty>
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
.layout { display:grid; grid-template-columns:280px 1fr 280px; min-height:calc(100vh - 60px) }

/* 左 */
.lside { background:#fff; border-right:1px solid var(--c-border); overflow:auto; height:calc(100vh - 60px); position:sticky; top:60px }
.lhead { padding:18px 20px 18px 28px; border-bottom:1px solid var(--c-border) }
.crumb { font-size:var(--fs-xs); color:var(--c-text-3); margin-bottom:6px; display:flex; align-items:center; gap:4px }
.lhead-title { margin:0; font-size:var(--fs-md); font-weight:600 }
.parts { padding:8px 0 }
/* 一级标题与左侧主菜单之间留出间距,层级更清晰 */
.part-h { padding:14px 20px 8px 28px; font-size:var(--fs-xs); color:var(--c-text-3); letter-spacing:.5px; text-transform:uppercase; font-weight:600 }
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
.md-h { font-size:var(--fs-lg); margin:30px 0 12px; padding-left:12px; border-left:3px solid var(--c-primary); color:var(--c-text-1) }
.md-p { font-size:var(--fs-md); line-height:1.85; margin:0 0 14px; color:var(--c-text-1) }
.md-ul { padding-left:22px; margin:0 0 14px }
.md-ul li { font-size:var(--fs-md); line-height:1.85; margin-bottom:6px; color:var(--c-text-1) }
.md-ul li::marker { color:var(--c-primary) }
.md-ic { font-family:"SFMono-Regular",Consolas,Menlo,monospace; font-size:.88em; background:#f1f4f9; color:#b93a86; padding:1px 6px; border-radius:4px }
.md-code { margin:0 0 16px; padding:16px 18px; background:#0f172a; color:#e2e8f0; border-radius:var(--r-s); overflow-x:auto; font-family:"SFMono-Regular",Consolas,Menlo,monospace; font-size:var(--fs-sm); line-height:1.7; tab-size:4 }
.md-code code { font-family:inherit; background:none; color:inherit; padding:0 }

/* 练习卡 */
.practice { margin:30px 0; background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); overflow:hidden; box-shadow:0 2px 6px rgba(20,30,60,.06); scroll-margin-top:80px }
.phead { display:flex; align-items:center; justify-content:space-between; padding:14px 18px; background:linear-gradient(180deg,#fafbff,#fff); border-bottom:1px solid var(--c-border) }
.phead h4 { margin:0; font-size:var(--fs-md) }
.phead small { color:var(--c-text-3); font-weight:400; margin-left:8px; font-size:var(--fs-xs) }
.phead .meta-mini { font-size:var(--fs-xs); color:var(--c-text-3); font-family:'JetBrains Mono',monospace }

.editor-area { padding:14px }
.result-area { border-top:1px solid var(--c-border); padding:14px 18px; font-family:'JetBrains Mono',Menlo,monospace; font-size:var(--fs-sm) }
.result-area.ok { background:#f6fcf8; border-top-color:var(--c-success) }
.result-area.ce, .result-area.re { background:#fef2f2; border-top-color:#ef4444 }
.result-area.to { background:#fff7e6; border-top-color:#f59e0b }
.r-head { margin-bottom:10px; font-size:var(--fs-base); display:flex; align-items:center; gap:4px }
.r-head .ok { color:#0e8a4f; font-weight:500 }
.r-head .ce, .r-head .re { color:#c12828; font-weight:500 }
.r-head .to { color:#b45309; font-weight:500 }
.stdout, .stderr { margin:8px 0 0; white-space:pre-wrap; color:var(--c-text-1) }
.stderr { color:#991b1b; font-size:var(--fs-xs) }

/* 右 */
.rside { background:#fff; border-left:1px solid var(--c-border); padding:18px 18px; overflow:auto; height:calc(100vh - 60px); position:sticky; top:60px }
.next-up { background:linear-gradient(135deg,#2563eb,#4f46e5); color:#fff; border-radius:var(--r-m); padding:14px; margin-bottom:14px }
.next-up small { font-size:var(--fs-xs); opacity:.85; display:block; margin-bottom:6px }
.next-up b { font-size:var(--fs-base); display:block; margin-bottom:8px }
.next-up .next-btn { color:#fff; border-color:rgba(255,255,255,.4); background:rgba(255,255,255,.1); width:100% }
.action-card { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:14px; margin-bottom:14px }
.nav-row { display:flex; justify-content:space-between; align-items:center; padding:8px 4px; border-radius:var(--r-s); cursor:pointer; font-size:var(--fs-sm); color:var(--c-text-1) }
.nav-row:hover { background:#f5f7fa }
.nav-row.disabled { cursor:not-allowed; opacity:.5 }
.nav-row .v { color:var(--c-text-3); font-size:var(--fs-xs) }
.nav-row > span:first-child { display:inline-flex; align-items:center; gap:4px }
.code-path-card { background:#fff; border:1px solid var(--c-border); border-radius:var(--r-m); padding:14px }
.card-title { font-size:var(--fs-sm); font-weight:600; margin-bottom:8px; color:var(--c-text-1); display:flex; align-items:center; gap:4px }
.code-path { background:var(--c-primary-bg); color:var(--c-primary); padding:8px 10px; border-radius:var(--r-s); font-size:var(--fs-xs); word-break:break-all; font-family:'JetBrains Mono',monospace }
.tip { color:var(--c-text-3); font-size:var(--fs-xs); margin:8px 0 0; line-height:1.6 }
</style>