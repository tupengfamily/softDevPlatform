<template>
  <div class="os-page">
    <header class="os-head">
      <a class="back-link" @click="gotoCenter">
        <span class="back-icon">←</span>{{ $t('oneStroke.back') }}
      </a>
      <h2 class="title">{{ $t('oneStroke.title') }}</h2>
      <p class="hint">{{ $t('oneStroke.hint') }}</p>
    </header>

    <section class="os-status">
      <div class="stat"><span class="lab">{{ $t('oneStroke.level') }}</span><strong class="val">{{ currentLevel.id }} / {{ TOTAL_LEVELS }}</strong></div>
      <div class="stat"><span class="lab">{{ $t('oneStroke.progress') }}</span><strong class="val">{{ usedEdgeCount }} / {{ currentLevel.edges.length }}</strong></div>
      <div class="stat"><span class="lab">{{ $t('oneStroke.timer') }}</span><strong class="val">{{ fmtTime(elapsedSec) }}</strong></div>
      <div class="stat"><span class="lab">{{ $t('oneStroke.hints') }}</span><strong class="val">{{ hintsLeft }}</strong></div>
    </section>

    <section class="os-toolbar">
      <el-select v-model="difficulty" size="default" class="diff-select" @change="onDifficultyChange">
        <el-option v-for="d in DIFF_LIST" :key="d.code" :value="d.code" :label="d.label" />
      </el-select>
      <el-button :disabled="!hasPrev" @click="gotoPrev">{{ $t('oneStroke.prev') }}</el-button>
      <el-button :disabled="!hasNext" @click="gotoNext">{{ $t('oneStroke.next') }}</el-button>
      <el-button @click="resetLevel" type="primary">{{ $t('oneStroke.reset') }}</el-button>
      <el-button @click="undo" :disabled="walked.length === 0 || over">{{ $t('oneStroke.undo') }}</el-button>
      <el-button @click="useHint" :disabled="over || hintsLeft <= 0 || !hasWalkable()">{{ $t('oneStroke.hint') }}</el-button>
      <el-button @click="showRules = true" plain>{{ $t('oneStroke.rules') }}</el-button>
    </section>

    <section class="os-board-area">
      <div class="board-card">
        <svg
          class="board"
          :viewBox="`0 0 ${VIEW.width} ${VIEW.height}`"
          preserveAspectRatio="xMidYMid meet"
          @click="onBgClick"
        >
          <!-- 边: 未走灰色 / 已走蓝色 -->
          <g class="edges">
            <line
              v-for="e in currentLevel.edges"
              :key="e.id"
              :x1="nodeById(e.a).x"
              :y1="nodeById(e.a).y"
              :x2="nodeById(e.b).x"
              :y2="nodeById(e.b).y"
              class="edge"
              :class="edgeClass(e)"
            />
          </g>
          <!-- 节点 -->
          <g class="nodes">
            <g
              v-for="n in currentLevel.nodes"
              :key="n.id"
              class="node"
              :class="nodeClass(n)"
              :transform="`translate(${n.x}, ${n.y})`"
              @click.stop="onNodeClick(n)"
            >
              <circle r="22" class="node-bg" />
              <text class="node-label" text-anchor="middle" dominant-baseline="central">{{ n.id }}</text>
            </g>
          </g>
        </svg>
      </div>
      <div class="status-line">
        <span class="hint-text">
          <template v-if="!walking && !over">{{ $t('oneStroke.chooseStart') }}</template>
          <template v-else-if="over">{{ $t('oneStroke.winHint') }}</template>
          <template v-else>{{ $t('oneStroke.walkingAt', { node: currentNodeId }) }}</template>
        </span>
      </div>
    </section>

    <el-dialog
      v-model="showRules"
      :title="$t('oneStroke.rules')"
      width="min(560px, 92vw)"
      :close-on-click-modal="true"
      class="os-rules-dialog"
    >
      <div class="rules-body">
        <h4>{{ $t('oneStroke.rulesGoal') }}</h4>
        <p>{{ $t('oneStroke.rulesGoalDesc') }}</p>

        <h4>{{ $t('oneStroke.rulesHow') }}</h4>
        <ol>
          <li>{{ $t('oneStroke.rulesR1') }}</li>
          <li>{{ $t('oneStroke.rulesR2') }}</li>
          <li>{{ $t('oneStroke.rulesR3') }}</li>
        </ol>

        <h4>{{ $t('oneStroke.rulesMath') }}</h4>
        <p>{{ $t('oneStroke.rulesMathDesc') }}</p>

        <h4>{{ $t('oneStroke.rulesLevels') }}</h4>
        <ul class="rules-levels">
          <li>{{ $t('oneStroke.rulesLevelEasy') }}</li>
          <li>{{ $t('oneStroke.rulesLevelNormal') }}</li>
          <li>{{ $t('oneStroke.rulesLevelHard') }}</li>
        </ul>
      </div>
    </el-dialog>

    <el-dialog
      v-model="over"
      :title="$t('oneStroke.winTitle')"
      width="min(420px, 92vw)"
      :show-close="false"
      :close-on-click-modal="false"
      align-center
      class="os-win-dialog"
    >
      <div class="win-body">
        <div class="win-emoji">🎉</div>
        <p>{{ $t('oneStroke.winDesc', { level: currentLevel.id, time: fmtTime(elapsedSec) }) }}</p>
      </div>
      <template #footer>
        <el-button @click="resetLevel">{{ $t('oneStroke.reset') }}</el-button>
        <el-button v-if="hasNext" type="primary" @click="gotoNext">{{ $t('oneStroke.next') }}</el-button>
        <el-button v-else type="primary" @click="gotoCenter">{{ $t('oneStroke.backToCenter') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { saveGameScore } from '../../api/game'
import { DIFFICULTY, LEVELS, TOTAL_LEVELS, VIEW, analyze, findEulerPath, getLevels } from '../../utils/oneStroke'

const { t } = useI18n()
const router = useRouter()
const gotoCenter = () => router.push('/games')

const DIFF_LIST = [
  { code: 'easy',   label: t('oneStroke.diff.easy') },
  { code: 'normal', label: t('oneStroke.diff.normal') },
  { code: 'hard',   label: t('oneStroke.diff.hard') }
]

const difficulty = ref('easy')
const levelId = ref(1)
const walked = ref([])         // Array<{ nodeId, edgeId, prevId }>
const hintPath = ref([])       // 用于高亮提示路径的节点序列
const hintsLeft = ref(DIFFICULTY.easy.maxHints)
const elapsedSec = ref(0)
const over = ref(false)
const showRules = ref(false)
const walking = computed(() => walked.value.length > 0 && !over.value)
const currentNodeId = computed(() => walked.value.length ? walked.value[walked.value.length - 1].nodeId : null)
const usedEdgeCount = computed(() => walked.value.length ? walked.value.length : 0)

const allLevels = LEVELS
const currentLevel = computed(() => allLevels.find(l => l.id === levelId.value) || allLevels[0])
const hasPrev = computed(() => levelId.value > 1)
const hasNext = computed(() => levelId.value < TOTAL_LEVELS)

const availableLevels = computed(() => getLevels(difficulty.value))
const levelOptions = computed(() => {
  // 限制关卡列表: 按当前难度, 但用户可手动切
  return availableLevels.value.map(l => ({ id: l.id, label: `${l.id}. ${l.title || t('oneStroke.levelGeneric')}` }))
})

function nodeById (id) {
  return currentLevel.value.nodes.find(n => n.id === id) || { x: 0, y: 0 }
}

function fmtTime (s) {
  const m = Math.floor(s / 60); const sec = s % 60
  return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
}

function edgeClass (e) {
  const used = walked.value.some(w => w.edgeId === e.id)
  const hinted = hintPath.value.length > 1 && isEdgeInPath(e.id, hintPath.value)
  return { used, hinted, walkable: !used && canWalkEdge(e) }
}

function isEdgeInPath (edgeId, path) {
  for (let i = 0; i < path.length - 1; i++) {
    const a = Math.min(path[i], path[i + 1])
    const b = Math.max(path[i], path[i + 1])
    if (('e' + a + '-' + b) === edgeId) return true
  }
  return false
}

function nodeClass (n) {
  const isCurrent = n.id === currentNodeId.value
  const isStart = !walking.value && !over.value
  const isHint = hintPath.value.includes(n.id)
  const canClick = isStart || isCurrent || canWalkToNode(n)
  return { current: isCurrent, hint: isHint, 'can-click': canClick }
}

function canWalkEdge (e) {
  if (walked.value.some(w => w.edgeId === e.id)) return false
  if (!walking.value) return true  // 起步前所有边都可走
  // 走步中: 必须是当前节点的相邻边
  const cur = currentNodeId.value
  return e.a === cur || e.b === cur
}

function canWalkToNode (n) {
  if (over.value) return false
  if (!walking.value) return true  // 起步
  // 当前节点出发可到的未走邻居
  const cur = currentNodeId.value
  if (n.id === cur) return true  // 撤回: 等于当前点 (无意义, 但允许再点也不出错)
  const edge = currentLevel.value.edges.find(e =>
    !walked.value.some(w => w.edgeId === e.id) &&
    ((e.a === cur && e.b === n.id) || (e.b === cur && e.a === n.id))
  )
  return !!edge
}

function hasWalkable () {
  if (!walking.value) return true
  const cur = currentNodeId.value
  return currentLevel.value.edges.some(e =>
    !walked.value.some(w => w.edgeId === e.id) && (e.a === cur || e.b === cur)
  )
}

function onNodeClick (n) {
  if (over.value) return
  hintPath.value = []
  if (!walking.value) {
    // 起点: 任意节点都可
    walked.value = [{ nodeId: n.id, edgeId: null, prevId: null }]
    return
  }
  const cur = currentNodeId.value
  if (n.id === cur) return  // 点同一个节点, 无变化
  // 找一条未走的相邻边
  const edge = currentLevel.value.edges.find(e =>
    !walked.value.some(w => w.edgeId === e.id) &&
    ((e.a === cur && e.b === n.id) || (e.b === cur && e.a === n.id))
  )
  if (!edge) return  // 不相邻或边已用
  walked.value.push({ nodeId: n.id, edgeId: edge.id, prevId: cur })
  // 胜利判定
  if (walked.value.length === currentLevel.value.edges.length + 1) {
    over.value = true
    stopTimer()
    recordWin()
  }
}

/** 通关落库: 100 分一关 + 关卡号 + 提示次数 */
async function recordWin () {
  try {
    const res = await saveGameScore('one-stroke', {
      score: 100,
      maxTile: 0,
      moves: walked.value.length,
      playSeconds: elapsedSec.value,
      finished: 4,
      extra: JSON.stringify({
        difficulty: difficulty.value,
        levelId: currentLevel.value.id,
        levelTitle: currentLevel.value.title,
        hintsUsed: DIFFICULTY[difficulty.value].maxHints - hintsLeft.value
      })
    })
    if (res.data?.isNewBest) ElMessage.success(t('game.newBest'))
  } catch (e) { /* 静默 */ }
}

function onBgClick () {
  // 点击空白处: 清掉提示高亮
  if (hintPath.value.length) hintPath.value = []
}

function undo () {
  if (!walked.value.length) return
  hintPath.value = []
  walked.value.pop()
  if (over.value) over.value = false
}

function resetLevel () {
  walked.value = []
  hintPath.value = []
  over.value = false
  elapsedSec.value = 0
  hintsLeft.value = DIFFICULTY[difficulty.value].maxHints
  startTimer()
}

function useHint () {
  if (over.value || hintsLeft.value <= 0) return
  // 算一条从当前节点出发的欧拉路径(若还没开始, 用 pickStart)
  const start = currentNodeId.value || currentLevel.value.nodes[0].id
  const path = findEulerPath(currentLevel.value.nodes, currentLevel.value.edges, start)
  if (!path) return
  hintPath.value = path
  hintsLeft.value--
}

function gotoPrev () {
  if (!hasPrev.value) return
  levelId.value -= 1
  resetLevel()
}
function gotoNext () {
  if (!hasNext.value) return
  levelId.value += 1
  resetLevel()
}
function onDifficultyChange (code) {
  const lv = availableLevels.value
  if (lv.length) levelId.value = lv[0].id
  resetLevel()
}

// ==================== 计时器 ====================
let timerHandle = null
function startTimer () {
  stopTimer()
  timerHandle = setInterval(() => {
    if (!over.value) elapsedSec.value++
  }, 1000)
}
function stopTimer () { if (timerHandle) { clearInterval(timerHandle); timerHandle = null } }

// ==================== 难度切换时同步 hintsLeft ====================
watch(difficulty, (code) => {
  hintsLeft.value = DIFFICULTY[code].maxHints
})

// ==================== 生命周期 ====================
onMounted(() => {
  hintsLeft.value = DIFFICULTY[difficulty.value].maxHints
  startTimer()
  // 开发期诊断: 关卡若不可解, 后台 warn (生产构建会被移除)
  if (import.meta.env.DEV) {
    const info = analyze(currentLevel.value.nodes, currentLevel.value.edges)
    if (!info.solvable) console.warn('[oneStroke] current level is unsolvable!')
  }
})
onBeforeUnmount(() => {
  stopTimer()
})
</script>

<style scoped>
.os-page { padding: 20px 24px 40px; max-width: 1100px; margin: 0 auto; }
.os-head { margin-bottom: 18px; }
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--fs-sm); color: var(--c-text-2); cursor: pointer;
}
.back-link:hover { color: var(--c-primary); }
.title { margin: 8px 0 4px; font-size: 26px; font-weight: 700; color: var(--c-text-1); letter-spacing: .5px; }
.title::before { content: '✏️ '; }
.os-head .hint { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }

.os-status { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px; }
.os-status .stat {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 10px 14px; text-align: center;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.os-status .lab { display: block; font-size: var(--fs-xs); color: var(--c-text-3); margin-bottom: 2px; }
.os-status .val { font-size: 20px; font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }

.os-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.os-toolbar .diff-select { width: 140px; }

.os-board-area { display: flex; flex-direction: column; align-items: center; gap: 14px; }
.board-card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 10px; box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
  width: 100%; max-width: 720px;
}
.board { width: 100%; height: auto; display: block; touch-action: manipulation; }

.edge {
  stroke: #cbd5e1;
  stroke-width: 4;
  stroke-linecap: round;
  transition: stroke .15s, stroke-width .15s;
}
.edge.used {
  stroke: var(--c-primary, #2563eb);
  stroke-width: 6;
}
.edge.hinted {
  stroke: #f59e0b;
  stroke-width: 6;
  stroke-dasharray: 8 4;
  animation: dash-march 0.8s linear infinite;
}
@keyframes dash-march {
  to { stroke-dashoffset: -12; }
}

.node { cursor: pointer; transition: transform .12s; }
.node-bg {
  fill: #f1f5f9;
  stroke: #94a3b8;
  stroke-width: 2.5;
  transition: fill .12s, stroke .12s;
}
.node-label {
  font-family: ui-monospace, Menlo, monospace;
  font-size: 18px;
  font-weight: 700;
  fill: #475569;
  user-select: none;
  pointer-events: none;
}
.node.current .node-bg {
  fill: var(--c-primary, #2563eb);
  stroke: var(--c-primary, #2563eb);
}
.node.current .node-label { fill: #fff; }
.node.hint .node-bg {
  fill: #fff7ed;
  stroke: #f59e0b;
  stroke-width: 3;
}
.node.hint .node-label { fill: #f59e0b; }
.node:not(.can-click) { opacity: 0.55; cursor: not-allowed; }
.node:hover.can-click .node-bg { stroke: var(--c-primary, #2563eb); stroke-width: 3; }
.node:active.can-click .node-bg { fill: #dbeafe; }

.status-line {
  font-size: var(--fs-sm);
  color: var(--c-text-3);
  text-align: center;
}
.hint-text { display: inline-flex; align-items: center; gap: 4px; }

/* 规则弹窗 */
.rules-body { font-size: var(--fs-sm); color: var(--c-text-2); line-height: 1.7; }
.rules-body h4 { margin: 14px 0 6px; font-size: var(--fs-sm); color: var(--c-text-1); font-weight: 700; }
.rules-body h4:first-child { margin-top: 0; }
.rules-body p { margin: 4px 0; }
.rules-body ol, .rules-body ul { margin: 6px 0; padding-left: 22px; }
.rules-body li { margin: 2px 0; }
.rules-body .rules-levels { list-style: none; padding-left: 0; }
.rules-body .rules-levels li {
  padding: 6px 10px; border-radius: 6px;
  background: #f1f5f9; margin: 4px 0;
}

/* 胜利弹窗 */
.win-body { text-align: center; padding: 10px 0 4px; }
.win-emoji { font-size: 56px; line-height: 1; margin-bottom: 12px; }
.win-body p { margin: 0; font-size: var(--fs-md); color: var(--c-text-1); font-weight: 600; }

@media (max-width: 700px) {
  .os-status { grid-template-columns: repeat(2, 1fr); }
  .board-card { padding: 6px; }
}
</style>