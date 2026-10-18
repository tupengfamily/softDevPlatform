<template>
  <div class="sd-page">
    <header class="sd-head">
      <a class="back-link" @click="gotoCenter">
        <span class="back-icon">←</span>{{ $t('sudoku.back') }}
      </a>
      <h2 class="title">{{ $t('sudoku.title') }}</h2>
      <p class="hint">{{ $t('sudoku.hint') }}</p>
    </header>

    <section class="sd-status">
      <div class="stat"><span class="lab">{{ $t('sudoku.difficulty') }}</span><strong class="val">{{ $t('sudoku.diff.' + difficulty) }}</strong></div>
      <div class="stat"><span class="lab">{{ $t('sudoku.timer') }}</span><strong class="val">{{ fmtTime(elapsedSec) }}</strong></div>
      <div class="stat" :class="{ danger: mistakes.length > 0 }"><span class="lab">{{ $t('sudoku.mistakes') }}</span><strong class="val">{{ mistakes.length }}</strong></div>
      <div class="stat"><span class="lab">{{ $t('sudoku.hints') }}</span><strong class="val">{{ hintsLeft }}</strong></div>
    </section>

    <section class="sd-toolbar">
      <el-select v-model="difficulty" size="default" class="diff-select" :disabled="over" @change="onDifficultyChange">
        <el-option v-for="d in DIFF_LIST" :key="d.code" :value="d.code" :label="$t(d.labelKey)" />
      </el-select>
      <el-button @click="requestNewGame" type="primary">{{ $t('sudoku.newGame') }}</el-button>
      <el-button @click="useHint" :disabled="over || hintsLeft <= 0 || !selected || givenBoard[selected.r][selected.c]">{{ $t('sudoku.hint') }}</el-button>
      <el-button @click="undo" :disabled="over || history.length === 0">{{ $t('sudoku.undo') }}</el-button>
      <el-button @click="eraseSelected" :disabled="over || !selected || givenBoard[selected.r][selected.c]">{{ $t('sudoku.erase') }}</el-button>
      <el-button @click="showRules = true" plain>{{ $t('sudoku.rules') }}</el-button>
    </section>

    <section class="sd-board-area">
      <div class="board-card">
        <div class="board" :style="{ '--cs': cellSize + 'px' }">
          <div v-for="(row, r) in board" :key="r" class="row">
            <div
              v-for="(v, c) in row"
              :key="c"
              class="cell"
              :class="cellClass(r, c)"
              @click="onCellClick(r, c)"
            >
              <span v-if="v" class="num" :class="{ given: givenBoard[r][c] !== 0 }">{{ v }}</span>
              <div v-else class="notes auto">
                <span v-for="d in maskToDigits(candidates[r][c])" :key="d" class="note">{{ d }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>

      <div class="num-pad">
        <button
          v-for="n in 9"
          :key="n"
          class="num-btn"
          :class="{ disabled: !canInput() }"
          :disabled="over || !canInput()"
          @click="onNumClick(n)"
        >
          <span class="big">{{ n }}</span>
        </button>
      </div>
    </section>

    <el-dialog
      v-model="showRules"
      :title="$t('sudoku.rules')"
      width="min(560px, 92vw)"
      :close-on-click-modal="true"
      class="sd-rules-dialog"
    >
      <div class="rules-body">
        <h4>{{ $t('sudoku.rulesGoal') }}</h4>
        <p>{{ $t('sudoku.rulesGoalDesc') }}</p>

        <h4>{{ $t('sudoku.rulesHow') }}</h4>
        <ol>
          <li>{{ $t('sudoku.rulesR1') }}</li>
          <li>{{ $t('sudoku.rulesR2') }}</li>
          <li>{{ $t('sudoku.rulesR3') }}</li>
        </ol>

        <h4>{{ $t('sudoku.rulesTips') }}</h4>
        <ul>
          <li>{{ $t('sudoku.rulesTip1') }}</li>
          <li>{{ $t('sudoku.rulesTip2') }}</li>
          <li>{{ $t('sudoku.rulesTip3') }}</li>
        </ul>

        <h4>{{ $t('sudoku.rulesLevels') }}</h4>
        <ul class="rules-levels">
          <li>{{ $t('sudoku.rulesLevelEasy') }}</li>
          <li>{{ $t('sudoku.rulesLevelNormal') }}</li>
          <li>{{ $t('sudoku.rulesLevelHard') }}</li>
        </ul>

        <p class="rules-foot">{{ $t('sudoku.rulesFoot') }}</p>
      </div>
    </el-dialog>

    <el-dialog
      v-model="over"
      :title="$t('sudoku.winTitle')"
      width="min(420px, 92vw)"
      :show-close="false"
      :close-on-click-modal="false"
      align-center
      class="sd-win-dialog"
    >
      <div class="win-body">
        <div class="win-emoji">🎉</div>
        <p>{{ $t('sudoku.winDesc', { time: fmtTime(elapsedSec), mistakes: mistakes.length }) }}</p>
      </div>
      <template #footer>
        <el-button type="primary" @click="requestNewGame">{{ $t('sudoku.playAgain') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { saveGameScore } from '../../api/game'
import { ArrowLeft } from '@element-plus/icons-vue'
import { DIFFICULTY, SIZE, checkMistakes, computeCandidates, generatePuzzle, isSolved, maskToDigits } from '../../utils/sudoku'

const { t } = useI18n()
const router = useRouter()
const gotoCenter = () => router.push('/games')

const DIFF_LIST = [
  { code: 'easy', labelKey: 'sudoku.diff.easy' },
  { code: 'normal', labelKey: 'sudoku.diff.normal' },
  { code: 'hard', labelKey: 'sudoku.diff.hard' }
]
const difficulty = ref('normal')
const diffConfig = computed(() => DIFFICULTY[difficulty.value])
const givenBoard = ref([])
const solution = ref([])
const board = ref([])
const candidates = ref([])
const userNotes = reactive({})
const autoNotes = ref([])
const selected = ref(null)
const highlight = ref(null)
const noteMode = ref(false)
const over = ref(false)
const showRules = ref(false)
const mistakes = ref([])
const history = ref([])
const elapsedSec = ref(0)
let timerHandle = null
const hintsLeft = ref(0)
const cellSize = ref(48)

function isSameBox (r1, c1, r2, c2) {
  return Math.floor(r1 / 3) === Math.floor(r2 / 3) &&
         Math.floor(c1 / 3) === Math.floor(c2 / 3)
}
function noteKey (r, c) { return r + ',' + c }
function getNote (r, c) { return userNotes[noteKey(r, c)] || 0 }
function setNote (r, c, mask) { userNotes[noteKey(r, c)] = mask }
function cellClass (r, c) {
  const sel = selected.value
  return {
    given: givenBoard.value[r][c] !== 0,
    selected: sel && sel.r === r && sel.c === c,
    peerRow: sel && sel.r === r,
    peerCol: sel && sel.c === c,
    peerBox: sel && isSameBox(sel.r, sel.c, r, c),
    sameNum: sel && board.value[r][c] !== 0 && board.value[r][c] === board.value[sel.r][sel.c],
    mistake: mistakes.value.some(m => m[0] === r && m[1] === c),
    hl: highlight.value && highlight.value.r === r && highlight.value.c === c,
    thickR: c === 2 || c === 5,
    thickB: r === 2 || r === 5
  }
}
function canInput () {
  if (!selected.value) return false
  const { r, c } = selected.value
  return givenBoard.value[r][c] === 0
}
function fmtTime (s) {
  const m = Math.floor(s / 60); const sec = s % 60
  return String(m).padStart(2, '0') + ':' + String(sec).padStart(2, '0')
}
function refreshCandidates () {
  candidates.value = computeCandidates(board.value)
  autoNotes.value = candidates.value.map(row => row.slice())
}

function newGameImmediate () {
  for (const k in userNotes) delete userNotes[k]
  const { puzzle, solution: sol } = generatePuzzle(difficulty.value)
  givenBoard.value = puzzle
  solution.value = sol
  board.value = puzzle.map(r => r.slice())
  candidates.value = computeCandidates(board.value)
  autoNotes.value = candidates.value.map(row => row.slice())
  selected.value = null
  highlight.value = null
  noteMode.value = false
  over.value = false
  mistakes.value = []
  history.value = []
  elapsedSec.value = 0
  hintsLeft.value = (diffConfig.value && diffConfig.value.maxHints) || 5
  startTimer()
  persist()
}

function countMoves () {
  let c = 0
  for (let i = 0; i < SIZE; i++) for (let j = 0; j < SIZE; j++) {
    if (board.value[i][j] !== givenBoard.value[i][j]) c++
  }
  for (const k in userNotes) if (userNotes[k]) c++
  return c
}

function onDifficultyChange () { newGameImmediate() }
function requestNewGame () { newGameImmediate() }
function onCellClick (r, c) {
  if (over.value) return
  if (selected.value && selected.value.r === r && selected.value.c === c) {
    selected.value = null
    return
  }
  selected.value = { r, c }
}

function onNumClick (n) {
  if (!canInput()) return
  const sel = selected.value
  const { r, c } = sel
  history.value.push({ r, c, prev: board.value[r][c], prevNotes: getNote(r, c) })
  board.value[r][c] = n
  setNote(r, c, 0)
  refreshCandidates()
  checkState()
  persist()
}

function eraseSelected () {
  if (!canInput()) return
  const sel = selected.value
  const { r, c } = sel
  if (board.value[r][c] === 0 && getNote(r, c) === 0) return
  history.value.push({ r, c, prev: board.value[r][c], prevNotes: getNote(r, c) })
  board.value[r][c] = 0
  setNote(r, c, 0)
  refreshCandidates()
  checkState()
  persist()
}

function undo () {
  if (!history.value.length) return
  const op = history.value.pop()
  board.value[op.r][op.c] = op.prev
  setNote(op.r, op.c, op.prevNotes)
  refreshCandidates()
  checkState()
  persist()
}

function useHint () {
  if (!canInput() || hintsLeft.value <= 0) return
  const sel = selected.value
  const { r, c } = sel
  const ans = solution.value[r][c]
  history.value.push({ r, c, prev: board.value[r][c], prevNotes: getNote(r, c) })
  board.value[r][c] = ans
  setNote(r, c, 0)
  refreshCandidates()
  hintsLeft.value--
  highlight.value = { r, c }
  setTimeout(() => { if (highlight.value && highlight.value.r === r && highlight.value.c === c) highlight.value = null }, 1200)
  checkState()
  persist()
}

function checkState () {
  mistakes.value = checkMistakes(board.value, solution.value)
  if (isSolved(board.value, solution.value)) {
    over.value = true
    stopTimer()
    clearProgress()
    recordWin()
  }
}

/** 数独通关落库: 分数 = 81 - 错误 - 提示, 反映表现 */
async function recordWin () {
  const score = Math.max(0, 81 - mistakes.value.length - hintsLeft.value)
  try {
    const res = await saveGameScore('sudoku', {
      score,
      maxTile: 0,
      moves: countMoves(),
      playSeconds: elapsedSec.value,
      finished: 4,
      extra: JSON.stringify({
        difficulty: difficulty.value,
        mistakes: mistakes.value.length,
        hintsUsed: DIFFICULTY[difficulty.value].maxHints - hintsLeft.value,
        result: 'solved'
      })
    })
    if (res.data?.isNewBest) ElMessage.success(t('game.newBest'))
  } catch (e) { /* 静默 */ }
}

const PROGRESS_KEY = 'sudokuProgress'
function persist () {
  if (over.value) { clearProgress(); return }
  try {
    localStorage.setItem(PROGRESS_KEY, JSON.stringify({
      difficulty: difficulty.value,
      given: givenBoard.value,
      solution: solution.value,
      board: board.value,
      notes: { ...userNotes },
      elapsed: elapsedSec.value,
      hintsLeft: hintsLeft.value,
      ts: Date.now()
    }))
  } catch (e) { /* ignore */ }
}
function clearProgress () { try { localStorage.removeItem(PROGRESS_KEY) } catch (e) { /* ignore */ } }

function loadProgress () {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY)
    if (!raw) return false
    const p = JSON.parse(raw)
    if (!p || !p.given || !p.solution) return false
    difficulty.value = p.difficulty || 'normal'
    givenBoard.value = p.given
    solution.value = p.solution
    board.value = p.board
    candidates.value = computeCandidates(board.value)
    autoNotes.value = candidates.value.map(row => row.slice())
    if (p.notes) {
      for (const k in userNotes) delete userNotes[k]
      Object.assign(userNotes, p.notes)
    }
    elapsedSec.value = p.elapsed || 0
    hintsLeft.value = (p.hintsLeft != null) ? p.hintsLeft : ((diffConfig.value && diffConfig.value.maxHints) || 5)
    mistakes.value = checkMistakes(board.value, solution.value)
    over.value = isSolved(board.value, solution.value)
    selected.value = null
    highlight.value = null
    startTimer()
    return true
  } catch (e) { return false }
}

function startTimer () {
  stopTimer()
  timerHandle = setInterval(() => {
    if (!over.value) {
      elapsedSec.value++
      if (elapsedSec.value % 10 === 0) persist()
    }
  }, 1000)
}
function stopTimer () { if (timerHandle) { clearInterval(timerHandle); timerHandle = null } }

function onKeyDown (ev) {
  if (over.value) return
  if (ev.key === 'Escape') { selected.value = null; return }
  if (/^[1-9]$/.test(ev.key) && selected.value) onNumClick(parseInt(ev.key, 10))
  else if (ev.key === 'Backspace' || ev.key === 'Delete' || ev.key === '0') eraseSelected()
  else if (ev.key === 'u' || ev.key === 'U') undo()
}

onMounted(() => {
  if (!loadProgress()) newGameImmediate()
  window.addEventListener('keydown', onKeyDown)
  const updateSize = () => {
    cellSize.value = window.innerWidth >= 1100 ? 52 : (window.innerWidth >= 700 ? 50 : 44)
  }
  updateSize()
  window.addEventListener('resize', updateSize)
})

onBeforeUnmount(() => {
  stopTimer()
  window.removeEventListener('keydown', onKeyDown)
})
</script>

<style scoped>
.sd-page { padding: 20px 24px 40px; max-width: 1100px; margin: 0 auto; }
.sd-head { margin-bottom: 18px; }
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--fs-sm); color: var(--c-text-2); cursor: pointer;
}
.back-link:hover { color: var(--c-primary); }
.title { margin: 8px 0 4px; font-size: 26px; font-weight: 700; color: var(--c-text-1); letter-spacing: .5px; }
.title::before { content: '🔢 '; }
.sd-head .hint { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }
.sd-status { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 14px; }
.sd-status .stat {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 10px 14px; text-align: center;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.sd-status .lab { display: block; font-size: var(--fs-xs); color: var(--c-text-3); margin-bottom: 2px; }
.sd-status .val { font-size: 20px; font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
.sd-status .danger .val { color: #ef4444; }
.sd-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.sd-toolbar .diff-select { width: 140px; }
.sd-board-area { display: flex; flex-direction: column; align-items: center; gap: 16px; }
.board-card {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 10px; box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
  display: flex; justify-content: center;
}
.board {
  display: grid; grid-template-rows: repeat(9, var(--cs, 50px));
  background: var(--c-text-3); gap: 1px;
  width: min(100%, 510px);
}
.row { display: grid; grid-template-columns: repeat(9, var(--cs, 50px)); gap: 1px; }
.cell {
  background: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 22px; font-weight: 600; cursor: pointer;
  position: relative; user-select: none;
  transition: background .12s;
}
.cell .num { font-family: ui-monospace, Menlo, monospace; color: var(--c-text-1); }
.cell .num.given { font-weight: 700; }
.cell:not(.given) .num { color: var(--c-primary); font-weight: 600; }
.cell.peerRow, .cell.peerCol, .cell.peerBox { background: #f1f5f9; }
.cell.selected { background: #dbeafe !important; box-shadow: inset 0 0 0 2px var(--c-primary); z-index: 2; }
.cell.sameNum { background: #e0e7ff; }
.cell.mistake .num { color: #ef4444 !important; }
.cell.thickR { border-right: 2px solid var(--c-text-3); }
.cell.thickB { border-bottom: 2px solid var(--c-text-3); }
.notes {
  display: grid; grid-template-columns: repeat(3, 1fr); grid-template-rows: repeat(3, 1fr);
  width: 100%; height: 100%; padding: 2px;
  font-family: ui-monospace, Menlo, monospace;
}
.notes .note {
  display: flex; align-items: center; justify-content: center;
  font-size: 11px; font-weight: 600; color: #475569;
}
.num-pad {
  display: grid; grid-template-columns: repeat(9, 1fr); gap: 6px;
  width: 100%; max-width: 480px;
}
.num-btn {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 12px 0;
  min-height: 44px;          /* 高于 44px 触摸目标(HIG/WCAG) */
  cursor: pointer; transition: background .12s, border-color .12s;
  font-family: ui-monospace, Menlo, monospace;
  font-size: 22px; font-weight: 700; color: var(--c-primary);
  -webkit-tap-highlight-color: transparent;
}
.num-btn:hover:not(:disabled) { background: #f8fafc; border-color: var(--c-primary); }
.num-btn:disabled { opacity: .4; cursor: not-allowed; }

/* ===== 规则说明弹窗 ===== */
.rules-body { font-size: var(--fs-sm); color: var(--c-text-2); line-height: 1.7; }
.rules-body h4 {
  margin: 14px 0 6px;
  font-size: var(--fs-sm);
  color: var(--c-text-1);
  font-weight: 700;
}
.rules-body h4:first-child { margin-top: 0; }
.rules-body p { margin: 4px 0; }
.rules-body ol, .rules-body ul { margin: 6px 0; padding-left: 22px; }
.rules-body li { margin: 2px 0; }
.rules-body .rules-levels { list-style: none; padding-left: 0; }
.rules-body .rules-levels li {
  padding: 6px 10px; border-radius: 6px;
  background: var(--c-fill-2, #f1f5f9); margin: 4px 0;
}
.rules-body .rules-foot {
  margin-top: 14px; padding-top: 10px;
  border-top: 1px dashed var(--c-border);
  color: var(--c-text-3); font-size: var(--fs-xs);
}

/* ===== 胜利弹窗 ===== */
.win-body { text-align: center; padding: 10px 0 4px; }
.win-emoji { font-size: 56px; line-height: 1; margin-bottom: 12px; }
.win-body p { margin: 0; font-size: var(--fs-md); color: var(--c-text-1); font-weight: 600; }

@media (max-width: 700px) {
  .sd-status { grid-template-columns: repeat(2, 1fr); }
  .cell .num { font-size: 18px; }
  .num-btn { font-size: 18px; }
}
</style>
