<script setup>
/**
 * 2048 小游戏
 * <p>
 * 玩法: 方向键 / WASD / 触摸滑动 移动方块, 相同数字合并翻倍, 目标是拼出 2048。
 * <p>
 * 数据联动(后端 /api/games/2048):
 * - 每次有效移动后**防抖 800ms 自动保存进度**(board/score/moves/用时), 下次进入自动恢复;
 * - 死局或主动结束后 POST 一局得分 → 计入「最近十局」与最高分;
 * - 排行榜按每人历史最高分聚合, 当前用户高亮。
 * <p>
 * 实现选择: 不用 tile 对象池做位移动画(复杂度高收益低), 改为「格子 + 数值」渲染,
 * 用 pop(新生成) / merged(刚合并) 两个瞬时 class 给出动画反馈, 逻辑更稳。
 */
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { getGameStats, saveGameScore, saveGameProgress, clearGameProgress, getLeaderboard } from '../../api/game'
import { confirmOngoingGame, hasOngoingGame } from '../../utils/gameConfirm'

const { t } = useI18n()
const router = useRouter()
const gotoCenter = () => router.push('/games')

/** 用户点击「新游戏」或点卡片进入「New」分支: 若有进行中的局, 弹确认; 否则直接重开 */
const requestNewGame = async () => {
  if (hasOngoingGame({ board: board.value, score: score.value, moves: moves.value, over: over.value, won: won.value })) {
    const choice = await confirmOngoingGame({
      title: t('game.confirmOngoing.title'),
      message: t('game.confirmOngoing.message'),
      detail: t('game.confirmOngoing.detail2048', { score: score.value, moves: moves.value })
    })
    if (choice !== 'restart') return
  }
  await newGame()
}

const CODE = '2048'
const SIZE = 4
/** 进度自动保存防抖间隔 */
const SAVE_DEBOUNCE = 800
/** 触摸滑动判定阈值(px) */
const SWIPE_MIN = 24

// ==================== 状态 ====================
const board = ref(emptyBoard())
const score = ref(0)
const moves = ref(0)
const over = ref(false)
const won = ref(false)
const keepPlaying = ref(false)
const elapsedSec = ref(0)

// 统计 / 排行榜
const stats = ref({ bestScore: 0, playedCount: 0, recent: [], progress: null })
const leaderboard = ref([])
const tab = ref('recent')
const loading = ref(true)

// 动画标记(渲染后清空, 用 class 触发 CSS 动画)
const spawnCell = ref(null)       // [r, c] 新生成的格子
const mergedCells = ref([])       // [[r, c], ...] 这一步发生合并的格子

// 撤销栈(只留最近 20 步, 每步存快照)
const history = ref([])

// 加分飘字: 每次合并后在分数卡上飘一个 +N
const gain = ref(0)
const gainKey = ref(0)
let gainTimer = null
const showGain = (n) => {
  if (n <= 0) return
  gain.value = n
  gainKey.value++
  if (gainTimer) clearTimeout(gainTimer)
  gainTimer = setTimeout(() => { gain.value = 0 }, 950)
}

let startAt = 0            // 本局开始时间戳
let clockTimer = null      // 计时器
let progressTimer = null   // 进度保存防抖
let animTimer = null       // 动画 class 清除

// ==================== 工具 ====================
function emptyBoard() {
  return Array.from({ length: SIZE }, () => Array(SIZE).fill(0))
}

const bestScore = computed(() => Math.max(stats.value.bestScore || 0, score.value))
const canUndo = computed(() => history.value.length > 0 && !over.value)

const maxTile = () => {
  let m = 0
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) m = Math.max(m, board.value[r][c])
  return m
}

const emptyCells = () => {
  const out = []
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) if (!board.value[r][c]) out.push([r, c])
  return out
}

/** 在随机空格生成 2(90%) 或 4(10%) */
const spawn = () => {
  const cells = emptyCells()
  if (!cells.length) return false
  const [r, c] = cells[Math.floor(Math.random() * cells.length)]
  board.value[r][c] = Math.random() < 0.9 ? 2 : 4
  spawnCell.value = [r, c]
  return true
}

/** 还有可用移动? (有空格 或 有相邻相同) */
const hasMoves = () => {
  for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) {
    if (!board.value[r][c]) return true
    if (c + 1 < SIZE && board.value[r][c] === board.value[r][c + 1]) return true
    if (r + 1 < SIZE && board.value[r][c] === board.value[r + 1][c]) return true
  }
  return false
}

const fmtDuration = (sec) => {
  const m = Math.floor((sec || 0) / 60)
  const s = (sec || 0) % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

const fmtTime = (s) => {
  if (!s) return '-'
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return '-'
  const p = (n) => String(n).padStart(2, '0')
  return `${p(d.getMonth() + 1)}/${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

const tileClass = (v) => (v <= 2048 ? `t${v}` : 'tbig')

/** 格子绝对定位: 用百分比 + 偏移, 棋盘缩放时自动适配 */
const cellStyle = (r, c) => ({
  left: `calc(${(c * 100) / SIZE}% + ${4 + c * 2}px)`,
  top: `calc(${(r * 100) / SIZE}% + ${4 + r * 2}px)`,
  width: `calc(${100 / SIZE}% - 8px)`,
  height: `calc(${100 / SIZE}% - 8px)`
})

const cells = computed(() => {
  const out = []
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      const sp = spawnCell.value
      out.push({
        k: r * SIZE + c,
        r, c,
        v: board.value[r][c],
        pop: !!sp && sp[0] === r && sp[1] === c,
        merged: mergedCells.value.some(([mr, mc]) => mr === r && mc === c)
      })
    }
  }
  return out
})

const flashAnim = () => {
  if (animTimer) clearTimeout(animTimer)
  animTimer = setTimeout(() => { spawnCell.value = null; mergedCells.value = [] }, 260)
}

// ==================== 核心: 移动 ====================
/**
 * 把一行向左压缩 + 合并
 * @returns {{row:number[], gained:number, mergedAt:number[]}} mergedAt 是合并结果在行内的索引
 */
function slideRow(row) {
  const nums = row.filter((v) => v)
  const out = []
  const mergedAt = []
  let gained = 0
  for (let i = 0; i < nums.length; i++) {
    if (i + 1 < nums.length && nums[i] === nums[i + 1]) {
      const merged = nums[i] * 2
      out.push(merged)
      mergedAt.push(out.length - 1)
      gained += merged
      i++ // 跳过被合并掉的那个
    } else {
      out.push(nums[i])
    }
  }
  while (out.length < SIZE) out.push(0)
  return { row: out, gained, mergedAt }
}

/** 执行一次移动; 无变化时返回 false(不生成新块、不存进度) */
function move(dir) {
  if (over.value || (won.value && !keepPlaying.value)) return false
  if (!board.value.length) return false

  const snapshot = JSON.parse(JSON.stringify(board.value))
  const next = emptyBoard()
  let gained = 0
  const merges = []

  for (let i = 0; i < SIZE; i++) {
    let line
    if (dir === 'left') line = snapshot[i].slice()
    else if (dir === 'right') line = snapshot[i].slice().reverse()
    else if (dir === 'up') line = snapshot.map((row) => row[i])
    else line = snapshot.map((row) => row[i]).reverse()

    const res = slideRow(line)
    gained += res.gained

    // 合并位置映射回绝对坐标, 供合并动画用
    for (const pos of res.mergedAt) {
      if (dir === 'left') merges.push([i, pos])
      else if (dir === 'right') merges.push([i, SIZE - 1 - pos])
      else if (dir === 'up') merges.push([pos, i])
      else merges.push([SIZE - 1 - pos, i])
    }

    if (dir === 'left') next[i] = res.row
    else if (dir === 'right') next[i] = res.row.slice().reverse()
    else if (dir === 'up') res.row.forEach((v, r) => { next[r][i] = v })
    else res.row.slice().reverse().forEach((v, r) => { next[r][i] = v })
  }

  if (JSON.stringify(next) === JSON.stringify(snapshot)) return false // 该方向无变化

  // 记录撤销快照
  history.value.push({ board: snapshot, score: score.value, moves: moves.value })
  if (history.value.length > 20) history.value.shift()

  board.value = next
  score.value += gained
  moves.value++
  if (gained > 0) showGain(gained)
  mergedCells.value = merges
  spawnCell.value = null
  flashAnim()

  // 达标 2048(只提示一次)
  if (!won.value && maxTile() >= 2048) won.value = true

  spawn()

  if (!hasMoves()) {
    over.value = true
    finishGame(1) // 1 = 无路可走
  } else {
    pushProgress()
  }
  return true
}

function undo() {
  const last = history.value.pop()
  if (!last) return
  board.value = JSON.parse(JSON.stringify(last.board))
  score.value = last.score
  moves.value = last.moves
  over.value = false
  spawnCell.value = null
  mergedCells.value = []
  pushProgress()
}

// ==================== 进度 / 成绩 ====================
function pushProgress() {
  if (progressTimer) clearTimeout(progressTimer)
  progressTimer = setTimeout(() => {
    saveGameProgress(CODE, {
      board: JSON.stringify(board.value),
      score: score.value,
      maxTile: maxTile(),
      moves: moves.value,
      playSeconds: elapsedSec.value,
      finished: over.value ? 1 : 0
    }).catch(() => { /* silent: 自动保存失败不打扰, 下次移动会重试 */ })
  }, SAVE_DEBOUNCE)
}

/** 一局结束 → 落库并刷新统计/排行榜 */
async function finishGame(reason) {
  if (progressTimer) { clearTimeout(progressTimer); progressTimer = null }
  if (!score.value) return // 0 分局不记录, 避免历史被污染
  try {
    const res = await saveGameScore(CODE, {
      score: score.value,
      maxTile: maxTile(),
      moves: moves.value,
      playSeconds: elapsedSec.value,
      finished: reason
    })
    if (res.data?.isNewBest) ElMessage.success(t('game.newBest'))
    await loadStats()
    await loadLeaderboard()
  } catch (e) { /* 统一弹错 */ }
}

async function endAfterWin() {
  keepPlaying.value = false
  over.value = true
  await finishGame(2) // 2 = 达成 2048 后主动结束
}

async function newGame() {
  // 局中重开: 有分就先记一笔(3 = 主动新开)
  if (score.value > 0 && !over.value) await finishGame(3)

  board.value = emptyBoard()
  score.value = 0
  moves.value = 0
  over.value = false
  won.value = false
  keepPlaying.value = false
  history.value = []
  mergedCells.value = []
  spawnCell.value = null
  elapsedSec.value = 0

  spawn()
  spawn()
  flashAnim()

  startAt = Date.now()
  clearGameProgress(CODE).catch(() => {})
  pushProgress()
}

// ==================== 加载 ====================
async function loadStats() {
  try {
    const res = await getGameStats(CODE)
    stats.value = res.data || { bestScore: 0, playedCount: 0, recent: [], progress: null }
  } catch (e) { /* 统一弹错 */ }
}

async function loadLeaderboard() {
  try {
    const res = await getLeaderboard(CODE, 20)
    leaderboard.value = res.data || []
  } catch (e) { /* 统一弹错 */ }
}

/** 进入页面: 恢复进度 or 开新局 */
async function bootstrap() {
  loading.value = true
  await Promise.all([loadStats(), loadLeaderboard()])
  loading.value = false

  const p = stats.value.progress
  let restored = false
  // 只有「未结束 + 有实际进展」的存档才恢复: 0 分 0 步只是刚开局就离开, 恢复没意义还会误报"已恢复"
  if (p && p.board && !p.finished && ((p.score || 0) > 0 || (p.moves || 0) > 0)) {
    try {
      const parsed = JSON.parse(p.board)
      // 校验形状, 防止脏数据把页面搞崩
      if (Array.isArray(parsed) && parsed.length === SIZE && parsed.every((r) => Array.isArray(r) && r.length === SIZE)) {
        board.value = parsed
        score.value = p.score || 0
        moves.value = p.moves || 0
        elapsedSec.value = p.playSeconds || 0
        startAt = Date.now() - elapsedSec.value * 1000
        restored = true
      }
    } catch (e) { /* 解析失败 → 走新局 */ }
  }

  if (!restored) {
    board.value = emptyBoard()
    score.value = 0
    moves.value = 0
    elapsedSec.value = 0
    spawn()
    spawn()
    flashAnim()
    startAt = Date.now()
    pushProgress()
  } else {
    ElMessage.success(t('game.restored'))
  }

  // 计时器
  clockTimer = setInterval(() => {
    if (over.value) return
    elapsedSec.value = Math.max(0, Math.floor((Date.now() - startAt) / 1000))
  }, 1000)
}

// ==================== 输入 ====================
const KEY_MAP = {
  ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down',
  a: 'left', d: 'right', w: 'up', s: 'down',
  A: 'left', D: 'right', W: 'up', S: 'down'
}

const onKeydown = (e) => {
  // 输入框/可编辑区域里不劫持方向键
  const tag = (e.target?.tagName || '').toLowerCase()
  if (tag === 'input' || tag === 'textarea' || e.target?.isContentEditable) return
  const dir = KEY_MAP[e.key]
  if (!dir) return
  e.preventDefault() // 阻止方向键滚动页面
  move(dir)
}

// 触摸滑动
let touchStart = null
const onTouchStart = (e) => {
  const tp = e.touches?.[0]
  if (!tp) return
  touchStart = { x: tp.clientX, y: tp.clientY }
}
const onTouchEnd = (e) => {
  if (!touchStart) return
  const tp = e.changedTouches?.[0]
  if (!tp) return
  const dx = tp.clientX - touchStart.x
  const dy = tp.clientY - touchStart.y
  touchStart = null
  if (Math.abs(dx) < SWIPE_MIN && Math.abs(dy) < SWIPE_MIN) return
  if (Math.abs(dx) > Math.abs(dy)) move(dx > 0 ? 'right' : 'left')
  else move(dy > 0 ? 'down' : 'up')
}

onMounted(() => {
  bootstrap()
  window.addEventListener('keydown', onKeydown)
})

onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  if (clockTimer) clearInterval(clockTimer)
  // 离开页面前把进度落一次(不等防抖)
  if (!over.value && board.value.flat().some((v) => v)) {
    saveGameProgress(CODE, {
      board: JSON.stringify(board.value),
      score: score.value,
      maxTile: maxTile(),
      moves: moves.value,
      playSeconds: elapsedSec.value,
      finished: 0
    }).catch(() => {})
  }
  if (progressTimer) clearTimeout(progressTimer)
  if (animTimer) clearTimeout(animTimer)
  if (gainTimer) clearTimeout(gainTimer)
})
</script>

<template>
  <div class="g2048" v-loading="loading">
    <!-- 页头: 返回 + 标题 + 分数卡 -->
    <header class="g-head">
      <div class="g-title">
        <button class="back-link" @click="gotoCenter">
          <el-icon><ArrowLeft /></el-icon>{{ $t('game.backToCenter') }}
        </button>
        <h2>2048</h2>
        <p class="hint">{{ $t('game.hint') }}</p>
      </div>
      <div class="g-scores">
        <div class="sbox">
          <span class="label">{{ $t('game.score') }}</span>
          <strong class="val">{{ score }}</strong>
          <transition name="gain">
            <span v-if="gain" :key="gainKey" class="gain">+{{ gain }}</span>
          </transition>
        </div>
        <div class="sbox best">
          <span class="label">{{ $t('game.best') }}</span>
          <strong class="val">{{ bestScore }}</strong>
        </div>
      </div>
    </header>

    <div class="g-body">
      <!-- 棋盘卡片: 工具栏 + 棋盘 + 操作提示 -->
      <section class="board-card">
        <div class="board-toolbar">
          <el-button type="primary" @click="requestNewGame">{{ $t('game.newGame') }}</el-button>
          <el-button :disabled="!canUndo" @click="undo">{{ $t('game.undo') }}</el-button>
          <span class="meta"><b>{{ $t('game.moves', { n: moves }) }}</b><i class="sep">·</i><b>{{ fmtDuration(elapsedSec) }}</b></span>
        </div>

        <div class="board-stage">
        <div
          class="board"
          @touchstart.passive="onTouchStart"
          @touchend.passive="onTouchEnd"
        >
          <!-- 背景格 -->
          <div class="bg-grid">
            <div v-for="i in 16" :key="`bg-${i}`" class="bg-cell"></div>
          </div>

          <!-- 方块层 -->
          <div class="tile-layer">
            <div
              v-for="cell in cells"
              :key="cell.k"
              class="slot"
              :style="cellStyle(cell.r, cell.c)"
            >
              <div
                v-if="cell.v"
                class="tile"
                :class="[tileClass(cell.v), { pop: cell.pop, merged: cell.merged }]"
              >{{ cell.v }}</div>
            </div>
          </div>

          <!-- 死局遮罩 -->
          <div v-if="over" class="overlay">
            <p class="o-title">{{ $t('game.over') }}</p>
            <p class="o-sub">{{ $t('game.finalScore', { s: score }) }}</p>
            <el-button type="primary" @click="newGame">{{ $t('game.playAgain') }}</el-button>
          </div>
          <!-- 胜利遮罩(可继续挑战) -->
          <div v-else-if="won && !keepPlaying" class="overlay win">
            <p class="o-title">🎉 {{ $t('game.win') }}</p>
            <div class="o-btns">
              <el-button @click="keepPlaying = true">{{ $t('game.keepGoing') }}</el-button>
              <el-button type="primary" @click="endAfterWin">{{ $t('game.endAndSave') }}</el-button>
            </div>
          </div>
        </div>
        </div><!-- /board-stage -->

        <p class="touch-tip">{{ $t('game.controls') }}</p>
      </section>

      <!-- 右侧数据面板 -->
      <aside class="side">
        <el-tabs v-model="tab" class="side-tabs">
          <el-tab-pane :label="$t('game.recent')" name="recent">
            <div v-if="!stats.recent || !stats.recent.length" class="empty">{{ $t('game.noRecord') }}</div>
            <ul v-else class="rec-list">
              <li v-for="(r, i) in stats.recent" :key="r.id" class="rec">
                <span class="idx">{{ i + 1 }}</span>
                <span class="sc">{{ r.score }}</span>
                <span class="tag">{{ r.maxTile }}</span>
                <span class="tm">{{ fmtTime(r.createTime) }}</span>
              </li>
            </ul>
            <div class="rec-foot">{{ $t('game.playedCount', { n: stats.playedCount || 0 }) }}</div>
          </el-tab-pane>

          <el-tab-pane :label="$t('game.leaderboard')" name="board">
            <div v-if="!leaderboard.length" class="empty">{{ $t('game.noRecord') }}</div>
            <ul v-else class="lb-list">
              <li v-for="r in leaderboard" :key="r.userId" class="lb" :class="{ me: r.isMe }">
                <span class="rank" :class="`r${r.rank <= 3 ? r.rank : 'n'}`">{{ r.rank }}</span>
                <span class="who" :title="r.username">{{ r.displayName }}</span>
                <span class="sc">{{ r.bestScore }}</span>
              </li>
            </ul>
          </el-tab-pane>
        </el-tabs>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.g2048 { padding: 20px 24px 40px; max-width: 1040px; margin: 0 auto; }

/* ============ 顶部 ============ */
.g-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 16px; flex-wrap: wrap; margin-bottom: 16px;
}
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 0; margin-bottom: 8px;
  border: none; background: none; cursor: pointer;
  font-size: var(--fs-xs); color: var(--c-text-3);
  transition: color .15s;
}
.back-link:hover { color: var(--c-primary); }
.g-title h2 { margin: 0 0 4px; font-size: 28px; font-weight: 700; color: var(--c-text-1); letter-spacing: .5px; }
.g-title .hint { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }
.g-scores { display: flex; gap: 10px; }
.sbox {
  position: relative;
  min-width: 96px; padding: 8px 16px; text-align: center;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.sbox.best { background: linear-gradient(135deg, #fff7e6, #ffeccc); border-color: #f5d9a8; }
.sbox .label { display: block; font-size: var(--fs-xs); color: var(--c-text-3); margin-bottom: 2px; }
.sbox .val { font-size: 22px; font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
/* 加分飘字 */
.gain {
  position: absolute; right: 12px; top: 6px;
  font-size: 15px; font-weight: 700; color: #22c55e;
  pointer-events: none; font-variant-numeric: tabular-nums;
}
.gain-enter-active { animation: gainUp .9s ease-out; }
.gain-leave-active { transition: opacity .18s; }
.gain-leave-to { opacity: 0; }
@keyframes gainUp {
  0% { opacity: 0; transform: translateY(6px) scale(.9); }
  25% { opacity: 1; transform: translateY(-2px) scale(1.05); }
  100% { opacity: 0; transform: translateY(-20px) scale(1); }
}

/* ============ 主体 ============ */
.g-body { display: flex; gap: 18px; align-items: flex-start; }
.board-card {
  flex: 1 1 auto; min-width: 0;
  background: #fff; border: 1px solid var(--c-border); border-radius: 16px;
  padding: 14px 16px 12px;
  box-shadow: 0 2px 10px rgba(15, 23, 42, .05);
}
.board-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.board-toolbar .meta {
  margin-left: auto; display: inline-flex; align-items: center; gap: 6px;
  font-size: var(--fs-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums;
}
.board-toolbar .meta b { font-weight: 600; color: var(--c-text-2); }
.board-toolbar .meta .sep { font-style: normal; color: var(--c-border-strong, #cbd5e1); }
.board-stage { display: flex; justify-content: center; }

/* 棋盘: 正方形, 内边距 + 8px 间隙, 方块用绝对定位百分比对齐 */
.board {
  position: relative;
  width: 100%; max-width: 470px;   /* 卡片内居中, 不无限撑大 */
  aspect-ratio: 1 / 1;
  background: #bbada0; border-radius: 12px; padding: 4px;
  touch-action: none; user-select: none;
  box-shadow: 0 6px 20px rgba(187, 173, 160, .35);
}
.bg-grid {
  position: absolute; inset: 4px;
  display: grid; grid-template-columns: repeat(4, 1fr); grid-template-rows: repeat(4, 1fr);
  gap: 8px;
}
.bg-cell { background: rgba(238, 228, 218, .35); border-radius: 6px; }
.tile-layer { position: absolute; inset: 0; }

.slot { position: absolute; }
.tile {
  width: 100%; height: 100%;
  display: flex; align-items: center; justify-content: center;
  border-radius: 6px; font-weight: 700; color: #776e65;
  font-variant-numeric: tabular-nums;
  font-size: clamp(18px, 4.4vw, 34px);
  transition: background .12s ease, color .12s ease;
}
.tile.merged { animation: mergePop .24s cubic-bezier(.2, .9, .3, 1.4); }
.tile.pop { animation: spawnPop .2s cubic-bezier(.2, .9, .3, 1.4); }
@keyframes mergePop {
  0% { transform: scale(1); }
  55% { transform: scale(1.16); }
  100% { transform: scale(1); }
}
@keyframes spawnPop {
  0% { transform: scale(.2); opacity: .3; }
  100% { transform: scale(1); opacity: 1; }
}

/* 数值配色(经典 2048 色板) */
.tile.t2 { background: #eee4da; }
.tile.t4 { background: #ede0c8; }
.tile.t8 { background: #f2b179; color: #fff; }
.tile.t16 { background: #f59563; color: #fff; }
.tile.t32 { background: #f67c5f; color: #fff; }
.tile.t64 { background: #f65e3b; color: #fff; }
.tile.t128 { background: #edcf72; color: #fff; font-size: clamp(17px, 3.9vw, 30px); }
.tile.t256 { background: #edcc61; color: #fff; font-size: clamp(17px, 3.9vw, 30px); }
.tile.t512 { background: #edc850; color: #fff; font-size: clamp(17px, 3.9vw, 30px); }
.tile.t1024 { background: #edc53f; color: #fff; font-size: clamp(15px, 3.3vw, 26px); }
.tile.t2048 { background: #edc22e; color: #fff; font-size: clamp(15px, 3.3vw, 26px);
  box-shadow: 0 0 0 2px rgba(255, 255, 255, .5), 0 0 18px rgba(237, 194, 46, .8); }
.tile.tbig { background: #3c3a32; color: #fff; font-size: clamp(14px, 3vw, 24px); }

/* 遮罩 */
.overlay {
  position: absolute; inset: 0; z-index: 5;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px;
  background: rgba(238, 228, 218, .78); border-radius: 12px;
  backdrop-filter: blur(2px);
}
.overlay.win { background: rgba(237, 194, 46, .82); }
.o-title { margin: 0; font-size: 26px; font-weight: 700; color: #776e65; }
.overlay.win .o-title { color: #fff; text-shadow: 0 2px 8px rgba(0, 0, 0, .2); }
.o-sub { margin: 0 0 8px; font-size: var(--fs-md); color: #8f7a66; }
.o-btns { display: flex; gap: 10px; }

.touch-tip { margin: 10px 0 0; font-size: var(--fs-xs); color: var(--c-text-3); text-align: center; }

/* ============ 右侧面板 ============ */
.side {
  flex: 0 0 300px; background: #fff; border: 1px solid var(--c-border);
  border-radius: var(--r-m); padding: 4px 12px 12px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.side-tabs :deep(.el-tabs__header) { margin-bottom: 8px; }
.empty { padding: 28px 0; text-align: center; color: var(--c-text-3); font-size: var(--fs-sm); }

.rec-list, .lb-list { list-style: none; margin: 0; padding: 0; max-height: 420px; overflow: auto; }
.rec, .lb {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 6px; border-radius: 6px; font-size: var(--fs-sm);
}
.rec:nth-child(odd), .lb:nth-child(odd) { background: #fafbfc; }
.rec .idx { width: 18px; color: var(--c-text-3); font-size: var(--fs-xs); }
.rec .sc { font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
.rec .tag {
  padding: 1px 6px; border-radius: 4px; font-size: var(--fs-xs);
  background: #eef2f8; color: var(--c-text-2);
}
.rec .tm { margin-left: auto; color: var(--c-text-3); font-size: var(--fs-xs); }

.lb .rank {
  width: 20px; text-align: center; font-size: var(--fs-xs); font-weight: 700;
  color: var(--c-text-3);
}
.lb .rank.r1 { color: #d4a017; }
.lb .rank.r2 { color: #9aa5b1; }
.lb .rank.r3 { color: #c67b4a; }
.lb .who { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--c-text-1); }
.lb .sc { font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
.lb.me { background: var(--c-primary-bg, #ecf5ff); }
.lb.me .who { color: var(--c-primary); font-weight: 600; }

.rec-foot {
  margin-top: 8px; padding-top: 8px; border-top: 1px solid var(--c-border);
  font-size: var(--fs-xs); color: var(--c-text-3); text-align: center;
}

/* ============ 响应式 ============ */
@media (max-width: 900px) {
  .g-body { flex-direction: column; }
  .board-card { width: 100%; }
  .side { flex: 1 1 auto; width: 100%; }
}
@media (max-width: 480px) {
  .g2048 { padding: 12px 12px 28px; }
  .g-title h2 { font-size: 22px; }
  .sbox { min-width: 76px; padding: 6px 10px; }
  .sbox .val { font-size: 18px; }
  .board-card { padding: 10px 10px 8px; border-radius: 12px; }
  .board { border-radius: 9px; max-width: none; }
  .tile { border-radius: 5px; }
  .board-toolbar { gap: 8px; }
  .board-toolbar .meta { margin-left: 0; }
}
</style>
