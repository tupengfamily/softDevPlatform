<script setup>
/**
 * 五子棋(15x15)
 * <p>
 * 三种模式:
 * - 单机双人(本机轮流, 进度存 localStorage)
 * - 人机对战(本地 AI pickMove, 三档难度)
 * - 联机对战(短轮询 HTTP, 服务端权威)
 *
 * 进度策略:
 * - 单机: 棋盘存 localStorage(15x15=225 字符足够轻量, 不进服务端 t_game_progress)
 * - 联机: 无存档(每个房间独立, 退出即丢)
 * <p>
 * UI: SVG 绘制棋盘 + 木纹背景 + 落子动画 + 最后手红圈 + 五连线高亮
 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { BLACK, EMPTY, SIZE, WHITE, checkRenju, cloneBoard, findWinLine, makeBoard, opposite, pickMove } from '../../utils/gomoku'
import {
  createGomokuRoom, getGomokuState, joinGomokuRoom, leaveGomokuRoom, listGomokuRooms, placeGomokuMove
} from '../../api/gomoku'
import { confirmOngoingGame, hasOngoingGame } from '../../utils/gameConfirm'

const { t } = useI18n()
const router = useRouter()
const gotoCenter = () => router.push('/games')

// ==================== 状态 ====================
const SIZE_PX = 540        // SVG 画布像素尺寸
const PADDING = 24         // 边距, 让最外圈棋子居中
const CELL = (SIZE_PX - PADDING * 2) / (SIZE - 1)

const mode = ref('pvp')             // pvp / pve / online
const difficulty = ref(1)          // 0=简单 / 1=普通 / 2=困难
const playerColor = ref(BLACK)     // 我执黑还是白
const allowRenju = ref(false)      // 禁手开关(默认关; 启用后黑棋禁三三/四四/长连, 不影响白棋)

const board = ref(makeBoard())
const currentColor = ref(BLACK)
const winnerColor = ref(0)
const winLine = ref(null)
const history = ref([])            // 每步 {r, c, color}
const lastMove = ref(null)
const over = ref(false)
const startedAt = ref(0)
const elapsedSec = ref(0)
const placing = ref(false)         // 防止双击/AI 计算期间重复点

// 联机模式专属
const onlineRoomCode = ref('')
const onlineRole = ref(0)          // 我执了后, myColor
const onlineError = ref('')
const pollHandle = ref(null)
const lastVersion = ref(0)
const waitingForPeer = ref(false) // 联机等待对方加入

const showRules = ref(false)
const showOnlinePanel = ref(false)
const showOnlineLobby = ref(false)
const onlineRooms = ref([])

let clockTimer = null

// ==================== 工具 ====================
const fmtDuration = (sec) => {
  const m = Math.floor((sec || 0) / 60)
  const s = (sec || 0) % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

// 检查是否落在棋盘坐标内
const isOnBoard = (r, c) => r >= 0 && r < SIZE && c >= 0 && c < SIZE

// ==================== 棋盘渲染 ====================
const stones = computed(() => {
  const out = []
  for (let r = 0; r < SIZE; r++) {
    for (let c = 0; c < SIZE; c++) {
      const color = board.value[r][c]
      if (color === EMPTY) continue
      const isLast = lastMove.value && lastMove.value[0] === r && lastMove.value[1] === c
      out.push({ key: `${r}-${c}`, r, c, color, isLast })
    }
  }
  return out
})

const stars = computed(() => {
  // 经典天元 + 4 个角的星位(15x15 棋盘上)
  const pts = [[3, 3], [3, 11], [11, 3], [11, 11], [7, 7]]
  return pts.filter(([r, c]) => board.value[r][c] === EMPTY)
})

const lines = computed(() => {
  // 五连线高亮
  if (!winLine.value) return []
  return winLine.value.map(([r, c]) => ({
    r, c,
    x1: PADDING + c * CELL, y1: PADDING + r * CELL
  }))
})

const stonePos = (r, c) => ({
  cx: PADDING + c * CELL,
  cy: PADDING + r * CELL,
  r: CELL * 0.42
})

// ==================== 单机模式 ====================
function startSingle (m, side = BLACK) {
  mode.value = m
  playerColor.value = side
  reset()
  if (m === 'pve' && side === WHITE) {
    // 让电脑先下(玩家执白)
    aiMove()
  }
  // 切换「我执白/黑」时若已有非空进度, 棋盘要重置(否则会出现双方颜色不一致的脏局面)
  startClock()
}

// 玩家切颜色时立即开新局(避免双方颜色混乱)
const onPlayerColorChange = () => {
  if (mode.value === 'pve' || mode.value === 'pvp') {
    // 仅在非空进度时重启; 空棋盘直接继续(避免每次下拉都重置)
    if (history.value.length > 0) {
      startSingle(mode.value, playerColor.value)
    }
  }
}

function startClock () {
  if (clockTimer) clearInterval(clockTimer)
  startedAt.value = Date.now() - elapsedSec.value * 1000
  clockTimer = setInterval(() => {
    if (over.value) return
    elapsedSec.value = Math.max(0, Math.floor((Date.now() - startedAt.value) / 1000))
  }, 1000)
}

function reset () {
  board.value = makeBoard()
  currentColor.value = BLACK
  winnerColor.value = 0
  winLine.value = null
  history.value = []
  lastMove.value = null
  over.value = false
  elapsedSec.value = 0
  placing.value = false
}

function placeAt (r, c) {
  if (over.value || placing.value) return
  if (!isOnBoard(r, c)) return
  if (board.value[r][c] !== EMPTY) return
  if (mode.value !== 'online' && currentColor.value !== playerColor.value) return
  if (mode.value === 'online' && !canMoveOnline.value) return

  // 禁手检查: 仅在单人/人机模式下启用(玩家执黑时检查)
  if (allowRenju.value && currentColor.value === BLACK
      && (mode.value === 'pvp' || mode.value === 'pve')) {
    // 假设落子, 检查是否触发禁手
    const sim = cloneBoard(board.value)
    sim[r][c] = BLACK
    const violations = checkRenju(sim, r, c)
    if (violations) {
      ElMessage.warning(t('gomoku.renjuForbidden', { kind: violations.join(' / ') }))
      return
    }
  }

  doPlace(r, c, currentColor.value)
}

function doPlace (r, c, color) {
  placing.value = true
  board.value[r][c] = color
  lastMove.value = [r, c]
  history.value.push({ r, c, color, t: Date.now() })

  const win = findWinLine(board.value, r, c)
  if (win) {
    winLine.value = win
    winnerColor.value = color
    over.value = true
    onGameOver()
    persist()
    placing.value = false
    return
  }
  // 平局
  if (board.value.flat().every((v) => v !== EMPTY)) {
    over.value = true
    onGameOver()
    persist()
    placing.value = false
    return
  }
  currentColor.value = opposite(color)
  // 双人对战: 当前执子方就是我的执方(双方轮流, 一人下完换另一人)
  if (mode.value === 'pvp') playerColor.value = currentColor.value

  if (mode.value === 'pve' && currentColor.value !== playerColor.value) {
    // 玩家刚落,等 200ms 让落子动画播完再让 AI 下
    persist()
    setTimeout(aiMove, 220)
  } else {
    persist()
  }
  placing.value = false
}

function aiMove () {
  if (over.value) return
  const [r, c] = pickMove(board.value, currentColor.value, difficulty.value, 800)
  if (r == null) return
  doPlace(r, c, currentColor.value)
}

function undo () {
  if (mode.value === 'online') return
  if (over.value) return
  if (history.value.length === 0) return
  // 人机: 撤销要一次撤两步(玩家 + AI)
  const steps = mode.value === 'pve' ? 2 : 1
  if (history.value.length < steps) return
  for (let i = 0; i < steps; i++) {
    const last = history.value.pop()
    board.value[last.r][last.c] = EMPTY
  }
  lastMove.value = history.value.length ? [history.value[history.value.length - 1].r, history.value[history.value.length - 1].c] : null
  currentColor.value = history.value.length ? opposite(history.value[history.value.length - 1].color) : BLACK
  winLine.value = null
  winnerColor.value = 0
  persist()
}

function onGameOver () {
  if (mode.value === 'pve') {
    if (winnerColor.value === playerColor.value) ElMessage.success(t('game.win'))
    else if (winnerColor.value === 0) ElMessage.info(t('gomoku.draw'))
    else ElMessage.warning(t('gomoku.lose'))
  } else if (mode.value === 'pvp') {
    if (winnerColor.value !== 0) ElMessage.success(t('gomoku.playerWin', { n: winnerColor.value === BLACK ? 1 : 2 }))
  }
  // 落库对局记录(对局模式/胜负/总步数); 网络模式不记(房主/对手是动态用户, 落个人记录意义不大)
  if (mode.value !== 'online') recordGame()
}

const GAME_START_TS = ref(0)
watch(mode, () => { GAME_START_TS.value = Date.now() }, { immediate: true })

async function recordGame () {
  // score: 胜=1 平=0 负=-1, 用于 leaderboard 不区分, 个人历史可一眼看胜负
  let result = 'draw', score = 0
  if (winnerColor.value === 0) { result = 'draw'; score = 0 }
  else if (mode.value === 'pvp') {
    // pvp: 没有"我", 胜者(黑白)任意, 双方都记为 win 视角不便; 按 1 玩家算胜 / 2 玩家算胜分别落
    result = 'win'; score = 1
  } else if (mode.value === 'pve') {
    if (winnerColor.value === playerColor.value) { result = 'win'; score = 1 }
    else { result = 'lose'; score = -1 }
  }
  const playSeconds = Math.floor((Date.now() - GAME_START_TS.value) / 1000)
  try {
    const res = await saveGameScore('gomoku', {
      score,
      maxTile: 0,
      moves: history.value.length,
      playSeconds,
      finished: winnerColor.value === 0 ? 5 : 4,
      extra: JSON.stringify({
        mode: mode.value,
        result,
        winnerColor: winnerColor.value,
        myColor: playerColor.value
      })
    })
    if (res.data?.isNewBest) ElMessage.success(t('game.newBest'))
  } catch (e) { /* 静默 */ }
}

// ==================== 进度持久化(单机) ====================
const STORAGE_KEY = 'gomokuProgress'

function persist () {
  if (mode.value === 'online') return
  try {
    const data = {
      mode: mode.value,
      difficulty: difficulty.value,
      playerColor: playerColor.value,
      board: board.value,
      currentColor: currentColor.value,
      winnerColor: winnerColor.value,
      winLine: winLine.value,
      moves: history.value.map((h) => ({ r: h.r, c: h.c, color: h.color })),
      elapsedSec: elapsedSec.value
    }
    localStorage.setItem(STORAGE_KEY, JSON.stringify(data))
  } catch (e) { /* localStorage 满/隐私模式忽略 */ }
}

function loadProgress () {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return false
    const d = JSON.parse(raw)
    if (!d || !d.board || d.board.length !== SIZE) return false
    // 棋盘 + 形状 + 当前应该落子方一致性校验(防止脏数据)
    let hasAny = false
    let count = 0
    for (let r = 0; r < SIZE; r++) for (let c = 0; c < SIZE; c++) {
      const v = d.board[r][c]
      if (v !== EMPTY) { hasAny = true; if (v === BLACK || v === WHITE) count++ }
    }
    if (!hasAny) return false
    board.value = d.board
    currentColor.value = d.currentColor
    winnerColor.value = d.winnerColor || 0
    winLine.value = d.winLine || null
    over.value = d.winnerColor > 0 || (typeof d.over === 'boolean' ? d.over : false)
    history.value = (d.moves || []).map((m) => ({ ...m, t: Date.now() }))
    elapsedSec.value = d.elapsedSec || 0
    mode.value = d.mode || 'pvp'
    difficulty.value = d.difficulty || 1
    playerColor.value = d.playerColor || BLACK
    return true
  } catch (e) {
    return false
  }
}

function clearProgress () {
  localStorage.removeItem(STORAGE_KEY)
  reset()
}

// ==================== 联机模式 ====================
async function startOnline () {
  mode.value = 'online'
  onlineError.value = ''
  reset()
  // 玩家执黑(默认) — 联机由服务端决定 currentColor
  playerColor.value = BLACK
  waitingForPeer.value = true
  // 不弹加入对话框: 创房后「等待对手」遮罩自带房间码分享提示, 对话框只用于「加入」场景
  try {
    const res = await createGomokuRoom({ boardSize: 15 })
    onlineRoomCode.value = res.data.roomCode
    startPolling()
  } catch (e) { /* 已统一弹错 */ }
}

async function joinOnline (code) {
  mode.value = 'online'
  onlineError.value = ''
  onlineRoomCode.value = code.trim().toUpperCase()
  reset()
  waitingForPeer.value = true
  showOnlinePanel.value = true
  try {
    const res = await joinGomokuRoom(onlineRoomCode.value)
    applyServerState(res.data)
    startPolling()
  } catch (e) {
    onlineError.value = e.message || t('gomoku.joinFailed')
    showOnlinePanel.value = true
  }
}

async function loadLobby () {
  showOnlineLobby.value = true
  try {
    const res = await listGomokuRooms()
    onlineRooms.value = res.data || []
  } catch (e) { /* 已统一弹错 */ }
}

function applyServerState (s) {
  if (!s) return
  onlineRoomCode.value = s.roomCode
  onlineRole.value = s.myColor || 0
  playerColor.value = s.myColor || BLACK
  if (s.board) {
    // 字符串 "0/1/2" 还原为整数
    board.value = s.board.map((row) => row.map((v) => parseInt(v, 10)))
  }
  currentColor.value = s.currentColor
  winnerColor.value = s.winnerColor
  winLine.value = s.winLine || null
  // 找最后一手: 跳过空位的最后一个 1/2
  let lm = null
  for (let r = SIZE - 1; r >= 0 && !lm; r--) {
    for (let c = SIZE - 1; c >= 0; c--) {
      if (board.value[r][c] !== EMPTY) { lm = [r, c]; break }
    }
  }
  lastMove.value = lm
  // 状态
  if (s.status === 2) {
    over.value = true
    waitingForPeer.value = false
    if (winnerColor.value !== 0) {
      ElMessage.success(s.myColor === winnerColor.value ? t('game.win') : t('gomoku.lose'))
    } else {
      ElMessage.info(t('gomoku.draw'))
    }
  } else if (s.status === 1 && s.myColor && s.currentColor === s.myColor) {
    waitingForPeer.value = false
  } else if (s.status === 1) {
    waitingForPeer.value = false
  } else {
    waitingForPeer.value = true
  }
  lastVersion.value = s.moveCount || 0
}

const canMoveOnline = computed(() => {
  return mode.value === 'online'
    && !over.value
    && onlineRole.value > 0
    && currentColor.value === onlineRole.value
})

function startPolling () {
  if (pollHandle.value) clearInterval(pollHandle.value)
  pollHandle.value = setInterval(async () => {
    if (mode.value !== 'online' || !onlineRoomCode.value) return
    try {
      const res = await getGomokuState(onlineRoomCode.value, lastVersion.value)
      const s = res.data
      if (!s.unchanged) {
        applyServerState(s)
      } else {
        // 即便 unchanged, 也看状态变化
        if (s.status === 2 && !over.value) applyServerState(s)
        else if (s.currentColor !== currentColor.value) currentColor.value = s.currentColor
        else if (s.status !== (over.value ? 2 : (waitingForPeer.value ? 0 : 1))) applyServerState(s)
      }
    } catch (e) { /* silent: 轮询错误不打扰 */ }
  }, 1200)
}

function stopPolling () {
  if (pollHandle.value) clearInterval(pollHandle.value)
  pollHandle.value = null
}

async function onlinePlaceAt (r, c) {
  if (!canMoveOnline.value) return
  if (!isOnBoard(r, c) || board.value[r][c] !== EMPTY) return
  placing.value = true
  try {
    const res = await placeGomokuMove(onlineRoomCode.value, r, c)
    applyServerState(res.data)
  } catch (e) {
    ElMessage.warning(e.message || t('gomoku.moveFailed'))
  } finally {
    placing.value = false
  }
}

// 单机点击逻辑; 联机走 onlinePlaceAt
const onCellClick = (r, c) => {
  if (mode.value === 'online') {
    onlinePlaceAt(r, c)
  } else {
    placeAt(r, c)
  }
}

async function leaveOnline () {
  if (mode.value === 'online' && onlineRoomCode.value) {
    try { await leaveGomokuRoom(onlineRoomCode.value) } catch (e) { /* silent */ }
  }
  stopPolling()
  mode.value = 'pvp'
  onlineRoomCode.value = ''
  onlineRole.value = 0
  onlineError.value = ''
  waitingForPeer.value = false
  reset()
}

// ==================== 事件处理 ====================
const myColor = computed(() => {
  if (mode.value === 'online') return onlineRole.value
  return playerColor.value
})
const myTurn = computed(() => {
  if (over.value) return false
  if (mode.value === 'online') return canMoveOnline.value
  return currentColor.value === playerColor.value
})

watch(() => mode.value, (m) => {
  stopPolling()
  if (m !== 'online') {
    onlineRoomCode.value = ''
    onlineRole.value = 0
    waitingForPeer.value = false
    showOnlinePanel.value = false
  }
})

// ==================== 生命周期 ====================
onMounted(() => {
  // 默认进入单机双人模式(最常用入口), 不要在挂载时自动 startOnline(用户点联机按钮才进)
  const restored = loadProgress()
  if (!restored) {
    mode.value = 'pvp'
    playerColor.value = BLACK
    reset()
  }
  startClock()
})

onBeforeUnmount(() => {
  if (clockTimer) clearInterval(clockTimer)
  stopPolling()
  // 离开页面(非联机): 落盘最新进度
  if (mode.value !== 'online') persist()
  // 联机: 主动离开(避免房间被孤立, 房主离开会自动关房间)
  if (mode.value === 'online' && onlineRoomCode.value) {
    // 用 sendBeacon 或 fire-and-forget 即可; 这里直接调用同步接口
    leaveGomokuRoom(onlineRoomCode.value).catch(() => {})
  }
})

// ==================== 工具: 行内模式切换 ====================
const setMode = (m) => {
  // 联机模式: 不自动创房, 只进入"等待创房/加入"状态, 用户主动点创建或输入房码
  if (m === 'online') {
    mode.value = 'online'
    reset()
    onlineRoomCode.value = ''
    onlineError.value = ''
    stopPolling()
  } else {
    // 单机模式: 直接开新局(确保进度干净)
    if (mode.value === 'online' && onlineRoomCode.value) {
      leaveGomokuRoom(onlineRoomCode.value).catch(() => {})
    }
    startSingle(m, BLACK)
  }
}
const newGame = () => {
  if (mode.value === 'online') {
    leaveOnline()
    return
  }
  clearProgress()
}

// 包裹一层「进行中检测」: 单机模式下, 若已有进行中的局面, 弹确认(继续/重开/取消)
const requestNewGame = async () => {
  // 联机: 直接退出当前房间(不弹确认, 离开房间是用户主动行为)
  if (mode.value === 'online') {
    leaveOnline()
    return
  }
  // 单机: 有进行中的局才弹
  if (hasOngoingGame({ board: board.value, score: history.value.length, moves: history.value.length, over: over.value })) {
    const detail = t('game.confirmOngoing.detailGomoku', { moves: history.value.length })
    const choice = await confirmOngoingGame({
      title: t('game.confirmOngoing.title'),
      message: t('game.confirmOngoing.messageGomoku'),
      detail
    })
    if (choice !== 'restart') return
  }
  clearProgress()
}

// 复制房间码到剪贴板(支持无 clipboard API 的 fallback)
const copyRoomCode = async () => {
  const code = onlineRoomCode.value
  if (!code) return
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(code)
      ElMessage.success(t('gomoku.copied'))
    } else {
      // Fallback: 选中文本
      const ta = document.createElement('textarea')
      ta.value = code
      ta.style.position = 'fixed'; ta.style.opacity = '0'
      document.body.appendChild(ta)
      ta.select()
      document.execCommand('copy')
      document.body.removeChild(ta)
      ElMessage.success(t('gomoku.copied'))
    }
  } catch (e) {
    ElMessage.warning(t('gomoku.copyFailed'))
  }
}
</script>

<template>
  <div class="gmk" v-loading="false">
    <!-- 顶部: 返回 + 标题 + 计时 -->
    <header class="gmk-head">
      <div class="gmk-title">
        <button class="back-link" @click="gotoCenter">
          <el-icon><ArrowLeft /></el-icon>{{ $t('game.backToCenter') }}
        </button>
        <h2>{{ $t('gomoku.title') }}</h2>
        <p class="hint">{{ $t('gomoku.hint') }}</p>
      </div>
      <div class="gmk-status">
        <div class="status-pill" :class="myTurn ? 'my' : 'wait'">
          <i class="dot" :class="{ pulse: myTurn }"></i>
          <span v-if="over">{{ winnerColor === 0 ? $t('gomoku.draw') : (winnerColor === myColor ? $t('game.win') : $t('gomoku.lose')) }}</span>
          <span v-else-if="myTurn">{{ $t('gomoku.yourTurn') }}</span>
          <span v-else>{{ $t('gomoku.peerTurn') }}</span>
        </div>
        <div class="timer">{{ fmtDuration(elapsedSec) }}</div>
      </div>
    </header>

    <!-- 模式切换 + 工具栏 -->
    <section class="gmk-toolbar">
      <el-radio-group v-model="mode" @change="setMode">
        <el-radio-button value="pvp">{{ $t('gomoku.mode.pvp') }}</el-radio-button>
        <el-radio-button value="pve">{{ $t('gomoku.mode.pve') }}</el-radio-button>
        <el-radio-button value="online">{{ $t('gomoku.mode.online') }}</el-radio-button>
      </el-radio-group>

      <template v-if="mode === 'pve'">
        <el-select v-model="difficulty" size="small" class="diff-select">
          <el-option :value="0" :label="$t('gomoku.diff.easy')" />
          <el-option :value="1" :label="$t('gomoku.diff.normal')" />
          <el-option :value="2" :label="$t('gomoku.diff.hard')" />
        </el-select>
        <el-select v-model="playerColor" size="small" class="diff-select" @change="onPlayerColorChange">
          <el-option :value="1" :label="$t('gomoku.black')" />
          <el-option :value="2" :label="$t('gomoku.white')" />
        </el-select>
        <el-checkbox v-model="allowRenju" :title="$t('gomoku.renjuTitle')">
          {{ $t('gomoku.renju') }}
        </el-checkbox>
      </template>

      <template v-else-if="mode === 'pvp'">
        <el-select v-model="playerColor" size="small" class="diff-select" @change="onPlayerColorChange">
          <el-option :value="1" :label="$t('gomoku.firstBlack')" />
          <el-option :value="2" :label="$t('gomoku.firstWhite')" />
        </el-select>
        <el-checkbox v-model="allowRenju" :title="$t('gomoku.renjuTitle')">
          {{ $t('gomoku.renju') }}
        </el-checkbox>
      </template>

      <el-button @click="undo" :disabled="over || mode === 'online' || history.length === 0">
        <el-icon><RefreshLeft /></el-icon>{{ $t('gomoku.undo') }}
      </el-button>
      <el-button @click="requestNewGame" type="primary">{{ $t('gomoku.newGame') }}</el-button>
      <el-button @click="showRules = true" plain>
        <el-icon><Document /></el-icon>{{ $t('gomoku.rules') }}
      </el-button>
      <el-button v-if="mode === 'online' && onlineRoomCode" @click="loadLobby" plain>
        <el-icon><Search /></el-icon>{{ $t('gomoku.lobby') }}
      </el-button>
    </section>

    <div class="gmk-body">
      <!-- 棋盘卡片 -->
      <section class="board-card">
        <div class="board-wrap">
          <svg :viewBox="`0 0 ${SIZE_PX} ${SIZE_PX}`" class="board" :class="{ shake: placing }">
            <!-- 木纹背景 -->
            <defs>
              <linearGradient id="gmk-bg" x1="0" y1="0" x2="1" y2="1">
                <stop offset="0%" stop-color="#e8c89c" />
                <stop offset="100%" stop-color="#d8a86c" />
              </linearGradient>
              <!-- 棋子渐变(径向, 顶部受光形成立体感) -->
              <radialGradient id="stoneBlack" cx="35%" cy="30%" r="75%">
                <stop offset="0%" stop-color="#5a5a5a" />
                <stop offset="60%" stop-color="#1a1a1a" />
                <stop offset="100%" stop-color="#000000" />
              </radialGradient>
              <radialGradient id="stoneWhite" cx="35%" cy="30%" r="75%">
                <stop offset="0%" stop-color="#ffffff" />
                <stop offset="60%" stop-color="#e8e8e8" />
                <stop offset="100%" stop-color="#b8b8b8" />
              </radialGradient>
            </defs>
            <rect x="0" y="0" :width="SIZE_PX" :height="SIZE_PX" :rx="10" fill="url(#gmk-bg)" />
            <!-- 网格线 -->
            <g class="grid">
              <line v-for="i in SIZE" :key="'h'+i" :x1="PADDING" :x2="SIZE_PX - PADDING" :y1="PADDING + (i-1) * CELL" :y2="PADDING + (i-1) * CELL" />
              <line v-for="i in SIZE" :key="'v'+i" :y1="PADDING" :y2="SIZE_PX - PADDING" :x1="PADDING + (i-1) * CELL" :x2="PADDING + (i-1) * CELL" />
            </g>
            <!-- 星位(天元 + 4 角) -->
            <g class="stars">
              <circle v-for="(p, i) in stars" :key="'s'+i" :cx="PADDING + p[1] * CELL" :cy="PADDING + p[0] * CELL" r="3" fill="#5a3a1a" />
            </g>

            <!-- 点击命中区 -->
            <g class="cells">
              <rect
                v-for="i in (SIZE * SIZE)"
                :key="'c'+i"
                :x="PADDING + ((i - 1) % SIZE) * CELL - CELL/2"
                :y="PADDING + Math.floor((i - 1) / SIZE) * CELL - CELL/2"
                :width="CELL" :height="CELL"
                fill="transparent"
                class="hit"
                :class="{ my: mode === 'online' ? canMoveOnline : (currentColor === playerColor && !over) }"
                @click="onCellClick(Math.floor((i-1)/SIZE), (i-1) % SIZE)"
              />
            </g>

            <!-- 棋子 -->
            <g class="stones">
              <g v-for="s in stones" :key="s.key" :class="{ fresh: s.isLast }">
                <!-- 阴影 -->
                <circle
                  :cx="stonePos(s.r, s.c).cx"
                  :cy="stonePos(s.r, s.c).cy + 2"
                  :r="stonePos(s.r, s.c).r * 0.96"
                  :fill="s.color === BLACK ? 'rgba(0,0,0,0.4)' : 'rgba(0,0,0,0.25)'"
                />
                <!-- 主体: 用 url(#id) 而非内联表达式, Vue 表达式在 SVG url() 里不展开 -->
                <circle
                  :cx="stonePos(s.r, s.c).cx"
                  :cy="stonePos(s.r, s.c).cy"
                  :r="stonePos(s.r, s.c).r"
                  :fill="s.color === BLACK ? 'url(#stoneBlack)' : 'url(#stoneWhite)'"
                />
                <!-- 最后一手红圈 -->
                <circle
                  v-if="s.isLast"
                  :cx="stonePos(s.r, s.c).cx"
                  :cy="stonePos(s.r, s.c).cy"
                  :r="stonePos(s.r, s.c).r * 0.45"
                  fill="none"
                  stroke="#ef4444"
                  stroke-width="2"
                />
              </g>
            </g>

            <!-- 五连线高亮 -->
            <line
              v-for="(p, i) in lines"
              :key="'w'+i"
              :x1="p.x1" :y1="p.y1"
              :x2="lines[i+1]?.x1 ?? p.x1" :y2="lines[i+1]?.y1 ?? p.y1"
              stroke="#ef4444" stroke-width="3" stroke-linecap="round"
              class="win-line"
            />
          </svg>

          <!-- 落子/回合提示浮层 -->
          <div v-if="mode === 'online' && waitingForPeer && !onlineRoomCode" class="overlay">
            <div class="overlay-card">
              <h3>{{ $t('gomoku.onlineModeHint') }}</h3>
              <p class="muted">{{ $t('gomoku.onlineModeHintDesc') }}</p>
            </div>
          </div>
          <div v-if="mode === 'online' && onlineRoomCode && waitingForPeer" class="overlay">
            <div class="overlay-card">
              <el-icon class="is-loading"><Loading /></el-icon>
              <h3>{{ $t('gomoku.waiting') }}</h3>
              <p class="room-code-label">{{ $t('gomoku.roomCode') }}</p>
              <p class="room-code">{{ onlineRoomCode }}</p>
              <el-button size="default" plain @click="copyRoomCode">
                <el-icon><CopyDocument /></el-icon>{{ $t('gomoku.copyCode') }}
              </el-button>
              <p class="muted">{{ $t('gomoku.shareTip') }}</p>
            </div>
          </div>
          <div v-if="over" class="overlay">
            <div class="overlay-card">
              <h3 v-if="winnerColor !== 0">🎉 {{ winnerColor === myColor ? $t('game.win') : $t('gomoku.lose') }}</h3>
              <h3 v-else>{{ $t('gomoku.draw') }}</h3>
              <el-button type="primary" @click="newGame">{{ $t('gomoku.playAgain') }}</el-button>
            </div>
          </div>
        </div>
      </section>

      <!-- 侧栏: 模式信息 / 联机面板 / 大厅 -->
      <aside class="side">
<!-- 联机: 创建/加入 -->
      <div v-if="mode === 'online'" class="panel">
        <h4>{{ $t('gomoku.onlineTitle') }}</h4>
        <div v-if="onlineRoomCode" class="kv">
          <span>{{ $t('gomoku.roomCode') }}</span>
          <b class="code">{{ onlineRoomCode }}</b>
        </div>
        <div v-if="onlineRoomCode" class="kv">
          <span>{{ $t('gomoku.youAre') }}</span>
          <b :class="myColor === 1 ? 'black' : 'white'">
            {{ myColor === 1 ? $t('gomoku.black') : (myColor === 2 ? $t('gomoku.white') : '-') }}
          </b>
        </div>
        <el-button v-if="!onlineRoomCode && !onlineError" type="primary" class="create-btn" @click="startOnline">
          <el-icon><Plus /></el-icon>{{ $t('gomoku.create') }}
        </el-button>
        <el-button v-else-if="onlineError" type="primary" class="create-btn" @click="startOnline">
          {{ $t('gomoku.retry') }}
        </el-button>
        <div v-if="onlineRoomCode" class="copy-row">
          <el-button size="small" plain @click="copyRoomCode">
            <el-icon><CopyDocument /></el-icon>{{ $t('gomoku.copyCode') }}
          </el-button>
        </div>
        <el-button type="danger" plain class="leave-btn" @click="leaveOnline">{{ $t('gomoku.leave') }}</el-button>
      </div>

        <!-- 单机: 模式说明 -->
        <div v-else-if="mode === 'pvp'" class="panel">
          <h4>{{ $t('gomoku.mode.pvp') }}</h4>
          <p class="muted">{{ $t('gomoku.pvpTip') }}</p>
          <el-button @click="() => { reset(); persist(); }" plain class="reset-btn">{{ $t('gomoku.resetBoard') }}</el-button>
        </div>

        <div v-else-if="mode === 'pve'" class="panel">
          <h4>{{ $t('gomoku.mode.pve') }}</h4>
          <p class="muted">{{ $t('gomoku.pveTip') }}</p>
          <div class="kv">
            <span>{{ $t('gomoku.diff') }}</span>
            <b>{{ $t(['gomoku.diff.easy','gomoku.diff.normal','gomoku.diff.hard'][difficulty]) }}</b>
          </div>
          <div class="kv">
            <span>{{ $t('gomoku.youAre') }}</span>
            <b :class="myColor === 1 ? 'black' : 'white'">{{ $t('gomoku.black') }}</b>
          </div>
          <el-button @click="() => startSingle('pve', BLACK)" plain class="reset-btn">{{ $t('gomoku.startNew') }}</el-button>
        </div>

        <!-- 操作提示 -->
        <div class="panel tip">
          <h4>{{ $t('gomoku.tipsTitle') }}</h4>
          <ul>
            <li>{{ $t('gomoku.tipFive') }}</li>
            <li>{{ $t('gomoku.tipOpen') }}</li>
            <li>{{ $t('gomoku.tipCenter') }}</li>
          </ul>
        </div>
      </aside>
    </div>

    <!-- 加入房间对话框(扫码 / 输入房码) -->
    <el-dialog v-model="showOnlinePanel" :title="$t('gomoku.joinTitle')" width="420px">
      <div v-if="mode !== 'online'" class="join-pane">
        <el-button @click="startOnline" type="primary">{{ $t('gomoku.create') }}</el-button>
        <el-divider>{{ $t('gomoku.or') }}</el-divider>
        <el-input v-model="onlineRoomCode" :placeholder="$t('gomoku.inputCode')" maxlength="8" style="text-transform: uppercase;" />
        <el-button type="primary" @click="joinOnline(onlineRoomCode)" :disabled="!onlineRoomCode">{{ $t('gomoku.join') }}</el-button>
        <p v-if="onlineError" class="err">{{ onlineError }}</p>
      </div>
      <template v-else>
        <p>{{ $t('gomoku.shareTip') }}</p>
      </template>
      <template #footer>
        <el-button @click="showOnlinePanel = false">{{ $t('common.cancel') }}</el-button>
      </template>
    </el-dialog>

    <!-- 大厅: 等待中的房间 -->
    <el-dialog v-model="showOnlineLobby" :title="$t('gomoku.lobbyTitle')" width="420px">
      <div v-if="!onlineRooms.length" class="empty">{{ $t('gomoku.noRooms') }}</div>
      <ul v-else class="lobby-list">
        <li v-for="r in onlineRooms" :key="r.roomCode" class="lobby-row">
          <div>
            <b class="lobby-code">{{ r.roomCode }}</b>
            <div class="lobby-sub">{{ $t('gomoku.boardSize') }} {{ r.boardSize }} · {{ $t('gomoku.moveCount', { n: r.moveCount }) }}</div>
          </div>
          <el-button size="small" type="primary" @click="joinOnline(r.roomCode); showOnlineLobby = false;">{{ $t('gomoku.join') }}</el-button>
        </li>
      </ul>
      <template #footer>
        <el-button @click="showOnlineLobby = false">{{ $t('common.cancel') }}</el-button>
      </template>
    </el-dialog>

    <!-- 规则说明 -->
    <el-dialog v-model="showRules" :title="$t('gomoku.rules')" width="600px">
      <div class="rules">
        <h4>{{ $t('gomoku.rulesBasic') }}</h4>
        <ul>
          <li>{{ $t('gomoku.rules.b1') }}</li>
          <li>{{ $t('gomoku.rules.b2') }}</li>
          <li>{{ $t('gomoku.rules.b3') }}</li>
          <li>{{ $t('gomoku.rules.b4') }}</li>
          <li>{{ $t('gomoku.rules.b5') }}</li>
        </ul>
        <h4>{{ $t('gomoku.rulesAi') }}</h4>
        <ul>
          <li>{{ $t('gomoku.rules.a1') }}</li>
          <li>{{ $t('gomoku.rules.a2') }}</li>
        </ul>
        <h4>{{ $t('gomoku.rulesOnline') }}</h4>
        <ul>
          <li>{{ $t('gomoku.rules.o1') }}</li>
          <li>{{ $t('gomoku.rules.o2') }}</li>
          <li>{{ $t('gomoku.rules.o3') }}</li>
          <li>{{ $t('gomoku.rules.o4') }}</li>
        </ul>
        <h4>{{ $t('gomoku.rulesRenju') }}</h4>
        <ul>
          <li>{{ $t('gomoku.rules.r1') }}</li>
          <li>{{ $t('gomoku.rules.r2') }}</li>
          <li>{{ $t('gomoku.rules.r3') }}</li>
        </ul>
        <p class="rules-foot">{{ $t('gomoku.rulesFoot') }}</p>
      </div>
      <template #footer>
        <el-button type="primary" @click="showRules = false">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.gmk { padding: 20px 24px 40px; max-width: 1040px; margin: 0 auto; }

/* ============ 顶部 ============ */
.gmk-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 16px; flex-wrap: wrap; margin-bottom: 14px;
}
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 0; margin-bottom: 8px;
  border: none; background: none; cursor: pointer;
  font-size: var(--fs-xs); color: var(--c-text-3);
  transition: color .15s;
}
.back-link:hover { color: var(--c-primary); }
.gmk-title h2 { margin: 0 0 4px; font-size: 26px; font-weight: 700; color: var(--c-text-1); }
.gmk-title .hint { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }
.gmk-status { display: flex; align-items: center; gap: 10px; }
.status-pill {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 7px 14px; border-radius: 999px;
  background: #fff; border: 1px solid var(--c-border);
  font-size: var(--fs-sm); font-weight: 600;
}
.status-pill.my { background: linear-gradient(135deg, #fef9c3, #fde68a); border-color: #facc15; color: #92400e; }
.status-pill.wait { color: var(--c-text-3); }
.status-pill .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--c-text-3); }
.status-pill.my .dot { background: #22c55e; }
.status-pill .dot.pulse { animation: pulseDot 1.4s ease-in-out infinite; }
@keyframes pulseDot {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(.65); opacity: .55; }
}
.timer { font-variant-numeric: tabular-nums; color: var(--c-text-2); font-size: var(--fs-sm); }

/* ============ 工具栏 ============ */
.gmk-toolbar {
  display: flex; gap: 8px; flex-wrap: wrap; align-items: center;
  margin-bottom: 16px; padding: 8px 12px;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
}
.gmk-toolbar :deep(.el-radio-group) .el-radio-button__inner { padding: 7px 14px; }
.gmk-toolbar .diff-btn { padding: 0; }
.gmk-toolbar .diff-btn :deep(.el-button__inner) { padding: 0 12px; }
.gmk-toolbar .el-button { padding: 7px 12px; }

/* ============ 主体 ============ */
.gmk-body { display: flex; gap: 18px; align-items: flex-start; }
.board-card {
  flex: 1 1 auto; min-width: 0; max-width: 580px;
  background: #fff; border: 1px solid var(--c-border); border-radius: 16px;
  padding: 12px; box-shadow: 0 2px 10px rgba(15, 23, 42, .05);
}
.board-wrap { position: relative; }
.board { width: 100%; height: auto; display: block; border-radius: 10px; user-select: none; }
.board.shake { animation: boardShake .12s ease; }
@keyframes boardShake {
  0%, 100% { transform: translateX(0); }
  50% { transform: translateX(2px); }
}
.grid line { stroke: #5a3a1a; stroke-width: 1; }
.stones > g { transition: opacity .12s; }
.stones > g.fresh { animation: stonePlace .22s ease-out; }
@keyframes stonePlace {
  0% { opacity: 0; transform: scale(.6); transform-origin: center; }
  100% { opacity: 1; transform: scale(1); }
}
.win-line { animation: winDraw .35s ease-out; }
@keyframes winDraw { from { stroke-dasharray: 80; stroke-dashoffset: 80; } to { stroke-dasharray: 80; stroke-dashoffset: 0; } }
.cells .hit { cursor: default; transition: background .12s; }
.cells .hit.my { cursor: pointer; }
.cells .hit.my:hover { fill: rgba(0, 0, 0, .04); }

.overlay {
  position: absolute; inset: 0; z-index: 5;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255, 255, 255, .7); border-radius: 10px;
  backdrop-filter: blur(2px);
}
.overlay-card {
  text-align: center; padding: 22px 28px;
  background: #fff; border-radius: 14px;
  box-shadow: 0 8px 24px rgba(15, 23, 42, .18);
}
.overlay-card h3 { margin: 0 0 12px; font-size: 22px; }
.overlay-card .code {
  display: inline-block; padding: 4px 12px; margin: 4px 0;
  background: #1f2937; color: #facc15; border-radius: 6px;
  font-family: ui-monospace, Menlo, monospace; font-weight: 700; letter-spacing: 1px;
}
.overlay-card .room-code-label { font-size: var(--fs-xs); color: var(--c-text-3); margin: 8px 0 4px; }
.overlay-card .room-code {
  display: block; padding: 10px 16px; margin: 4px 0 12px;
  background: #1f2937; color: #facc15; border-radius: 8px;
  font-family: ui-monospace, Menlo, monospace; font-weight: 800; letter-spacing: 3px;
  font-size: 26px; user-select: all;
}
.overlay-card p { margin: 6px 0; font-size: var(--fs-sm); color: var(--c-text-2); }
.overlay-card .muted { color: var(--c-text-3); font-size: var(--fs-xs); }

/* ============ 侧栏 ============ */
.side { flex: 0 0 240px; display: flex; flex-direction: column; gap: 12px; }
.panel {
  background: #fff; border: 1px solid var(--c-border);
  border-radius: var(--r-m); padding: 14px 16px;
}
.panel h4 { margin: 0 0 10px; font-size: var(--fs-md); color: var(--c-text-1); }
.panel p { margin: 0 0 10px; font-size: var(--fs-sm); color: var(--c-text-2); line-height: 1.5; }
.panel .muted { color: var(--c-text-3); }
.panel .kv {
  display: flex; justify-content: space-between; align-items: center;
  padding: 6px 0; border-bottom: 1px dashed var(--c-border);
  font-size: var(--fs-sm);
}
.panel .kv:last-of-type { border-bottom: none; }
.panel .kv b { font-weight: 600; }
.panel .kv b.black { color: #1f2937; }
.panel .kv b.white { color: #6b7280; }
.panel .kv b.code {
  font-family: ui-monospace, Menlo, monospace; letter-spacing: 1px;
  background: #f5f5f5; padding: 2px 8px; border-radius: 4px;
}
.panel ul { margin: 0; padding-left: 18px; font-size: var(--fs-sm); color: var(--c-text-2); }
.panel ul li { margin: 4px 0; line-height: 1.45; }
.panel .reset-btn { width: 100%; margin-top: 4px; }
.panel .leave-btn { width: 100%; margin-top: 8px; }
.panel .create-btn { width: 100%; }
.panel .copy-row { display: flex; justify-content: flex-end; margin-top: 6px; }
.gmk-toolbar .diff-select { width: 110px; }
.gmk-toolbar :deep(.el-checkbox) { margin-left: 4px; }
.gmk-toolbar :deep(.el-checkbox__label) { font-size: var(--fs-xs); }

/* ============ 对话框 ============ */
.join-pane { display: flex; flex-direction: column; gap: 12px; }
.join-pane .err { color: #ef4444; font-size: var(--fs-xs); margin: 0; }
.lobby-list { list-style: none; margin: 0; padding: 0; max-height: 320px; overflow: auto; }
.lobby-row {
  display: flex; justify-content: space-between; align-items: center;
  padding: 10px 12px; border-radius: 8px;
}
.lobby-row:nth-child(odd) { background: #fafbfc; }
.lobby-code {
  font-family: ui-monospace, Menlo, monospace; letter-spacing: 1px;
  background: #1f2937; color: #facc15; padding: 2px 8px; border-radius: 4px;
}
.lobby-sub { font-size: var(--fs-xs); color: var(--c-text-3); margin-top: 2px; }
.empty { padding: 32px 0; text-align: center; color: var(--c-text-3); }

/* ============ 规则 ============ */
.rules h4 { margin: 14px 0 6px; font-size: var(--fs-md); color: var(--c-text-1); }
.rules h4:first-child { margin-top: 0; }
.rules ul { margin: 0; padding-left: 20px; }
.rules li { margin: 4px 0; font-size: var(--fs-sm); color: var(--c-text-2); line-height: 1.55; }
.rules-foot { margin-top: 12px; padding: 10px 12px; border-radius: 6px; background: var(--c-primary-bg, #ecf5ff); color: var(--c-primary); font-size: var(--fs-xs); }

/* ============ 响应式 ============ */
@media (max-width: 880px) {
  .gmk-body { flex-direction: column; }
  .board-card { max-width: none; width: 100%; }
  .side { flex: 1 1 auto; width: 100%; }
}
@media (max-width: 480px) {
  .gmk { padding: 12px 10px 28px; }
  .gmk-title h2 { font-size: 22px; }
  .status-pill { padding: 6px 10px; font-size: var(--fs-xs); }
}
</style>