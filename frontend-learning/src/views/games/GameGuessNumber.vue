<template>
  <div class="gn-page">
    <!-- 顶部返回 + 标题 -->
    <header class="gn-head">
      <a class="back-link" @click="gotoCenter">
        <el-icon><ArrowLeft /></el-icon>{{ $t('guess.back') }}
      </a>
      <h2 class="title">{{ $t('guess.title') }}</h2>
      <p class="hint">{{ $t('guess.hint') }}</p>
    </header>

    <!-- 状态卡: 当前难度 / 剩余次数 / 已用次数 -->
    <section class="gn-status">
      <div class="stat">
        <span class="lab">{{ $t('guess.difficulty') }}</span>
        <strong class="val">{{ $t(diffConfig.labelKey) }}</strong>
      </div>
      <div class="stat">
        <span class="lab">{{ $t('guess.remaining') }}</span>
        <strong class="val" :class="{ danger: remaining <= 2 }">{{ remaining }}</strong>
      </div>
      <div class="stat">
        <span class="lab">{{ $t('guess.attempts') }}</span>
        <strong class="val">{{ history.length }}</strong>
      </div>
    </section>

    <!-- 工具栏: 难度切换 + 新对局 + 规则说明 -->
    <section class="gn-toolbar">
      <el-select v-model="difficulty" size="default" class="diff-select" :disabled="over" @change="onDifficultyChange">
        <el-option v-for="d in DIFFICULTY" :key="d.code" :value="d.code" :label="$t(d.labelKey)" />
      </el-select>
      <el-button @click="requestNewGame" type="primary">
        <el-icon><Refresh /></el-icon>{{ $t('guess.newGame') }}
      </el-button>
      <el-button @click="showRules = true" plain>
        <el-icon><Document /></el-icon>{{ $t('guess.rules') }}
      </el-button>
    </section>

    <!-- 输入区: N 位数字格子 + 提交按钮 -->
    <section class="gn-input">
      <div class="digit-row">
        <input
          v-for="i in length"
          :key="i"
          :ref="el => setSlotRef(el, i - 1)"
          v-model="digits[i - 1]"
          class="digit-slot"
          :class="{ filled: digits[i - 1], win: winFlash[i - 1] }"
          type="text"
          inputmode="numeric"
          maxlength="1"
          :disabled="over"
          @input="onDigitInput(i - 1, $event)"
          @keydown="onDigitKey($event, i - 1)"
          @paste="onDigitPaste($event)"
        />
      </div>
      <div class="input-actions">
        <el-button @click="clearInput" :disabled="over || digits.every(d => !d)">
          <el-icon><Delete /></el-icon>{{ $t('guess.clear') }}
        </el-button>
        <el-button type="success" @click="submitGuess" :disabled="!canSubmit">
          <el-icon><Promotion /></el-icon>{{ $t('guess.submit') }}
        </el-button>
      </div>
      <p v-if="inputError" class="input-error">{{ inputError }}</p>
    </section>

    <!-- 历史记录列表 -->
    <section class="gn-history">
      <h3>{{ $t('guess.history') }}</h3>
      <div v-if="!history.length" class="empty">{{ $t('guess.noHistory') }}</div>
      <ul v-else class="hist-list">
        <li v-for="(h, idx) in historyWithIdx" :key="h.id" :class="['hist-item', { win: h.A === length }]">
          <span class="num">#{{ idx + 1 }}</span>
          <span class="guess">{{ h.guess }}</span>
          <span class="result">
            <b class="a">{{ h.A }}</b><span class="x">A</span>
            <b class="b">{{ h.B }}</b><span class="x">B</span>
          </span>
        </li>
      </ul>
    </section>

    <!-- 胜利遮罩 -->
    <div v-if="win" class="overlay win">
      <div class="overlay-card">
        <div class="big">🎉</div>
        <h3>{{ $t('guess.winTitle') }}</h3>
        <p>{{ $t('guess.winDesc', { attempts: history.length, length }) }}</p>
        <p class="ans">{{ $t('guess.answerWas') }} <b>{{ answer }}</b></p>
        <div class="overlay-btns">
          <el-button type="primary" @click="requestNewGame">{{ $t('guess.playAgain') }}</el-button>
        </div>
      </div>
    </div>

    <!-- 失败遮罩 -->
    <div v-else-if="lose" class="overlay lose">
      <div class="overlay-card">
        <div class="big">😢</div>
        <h3>{{ $t('guess.loseTitle') }}</h3>
        <p>{{ $t('guess.loseDesc', { maxAttempts }) }}</p>
        <p class="ans">{{ $t('guess.answerWas') }} <b>{{ answer }}</b></p>
        <div class="overlay-btns">
          <el-button type="primary" @click="requestNewGame">{{ $t('guess.tryAgain') }}</el-button>
        </div>
      </div>
    </div>

    <!-- 规则说明弹窗 -->
    <el-dialog v-model="showRules" :title="$t('guess.rules')" width="560px">
      <div class="rules">
        <h4>{{ $t('guess.rulesGoal') }}</h4>
        <p>{{ $t('guess.rulesGoalDesc') }}</p>
        <h4>{{ $t('guess.rulesJudge') }}</h4>
        <p>{{ $t('guess.rulesJudgeDesc') }}</p>
        <ul>
          <li><b class="a">A</b> — {{ $t('guess.rulesA') }}</li>
          <li><b class="b">B</b> — {{ $t('guess.rulesB') }}</li>
        </ul>
        <h4>{{ $t('guess.rulesLevels') }}</h4>
        <ul>
          <li>{{ $t('guess.rulesLevelEasy') }}</li>
          <li>{{ $t('guess.rulesLevelNormal') }}</li>
          <li>{{ $t('guess.rulesLevelHard') }}</li>
        </ul>
        <h4>{{ $t('guess.rulesTips') }}</h4>
        <ul>
          <li>{{ $t('guess.rulesTip1') }}</li>
          <li>{{ $t('guess.rulesTip2') }}</li>
          <li>{{ $t('guess.rulesTip3') }}</li>
        </ul>
        <p class="rules-foot">{{ $t('guess.rulesFoot') }}</p>
      </div>
      <template #footer>
        <el-button type="primary" @click="showRules = false">{{ $t('common.confirm') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import {
  ArrowLeft, Delete, Document, Promotion, Refresh
} from '@element-plus/icons-vue'
import { DIFFICULTY, generateAnswer, judge, validateGuess } from '../../utils/guessNumber'
import { saveGameScore } from '../../api/game'
import { confirmOngoingGame, hasOngoingGame } from '../../utils/gameConfirm'

const { t } = useI18n()
const router = useRouter()
const gotoCenter = () => router.push('/games')

// ==================== 状态 ====================
const difficulty = ref('easy')
const digits = ref([]) // 当前输入位
const slotRefs = ref([])
const history = ref([]) // [{ guess, A, B, ts }]
const answer = ref('') // 不持久化, 内存持有
const over = ref(false)
const win = ref(false)
const lose = ref(false)
const showRules = ref(false)
const inputError = ref('')
const winFlash = ref([]) // 一次性胜利闪烁

const diffConfig = computed(() => DIFFICULTY.find(d => d.code === difficulty.value) || DIFFICULTY[0])
const length = computed(() => diffConfig.value.length)
const maxAttempts = computed(() => diffConfig.value.maxAttempts)
const remaining = computed(() => Math.max(0, maxAttempts.value - history.value.length))
const canSubmit = computed(() => !over.value && digits.value.every(d => d !== ''))
const historyWithIdx = computed(() => history.value.slice().reverse())

function setSlotRef (el, idx) {
  if (el) slotRefs.value[idx] = el
}

// ==================== 持久化 ====================
const PROGRESS_KEY = 'guessProgress'

function loadProgress () {
  try {
    const raw = localStorage.getItem(PROGRESS_KEY)
    if (!raw) return false
    const p = JSON.parse(raw)
    if (!p || !p.answer) return false
    // 校验一致性
    if (p.difficulty && DIFFICULTY.find(d => d.code === p.difficulty)) difficulty.value = p.difficulty
    digits.value = new Array(length.value).fill('')
    history.value = Array.isArray(p.history) ? p.history : []
    answer.value = p.answer
    // 终局状态恢复
    const won = history.value.some(h => h.A === length.value)
    const exhausted = history.value.length >= maxAttempts.value
    if (won) {
      over.value = true
      win.value = true
    } else if (exhausted) {
      over.value = true
      lose.value = true
    } else {
      over.value = false
      win.value = false
      lose.value = false
    }
    return true
  } catch (e) {
    return false
  }
}

function persist () {
  if (over.value && win.value) {
    // 胜利局删 progress
    clearProgress()
    return
  }
  const p = { difficulty: difficulty.value, answer: answer.value, history: history.value, ts: Date.now() }
  try {
    localStorage.setItem(PROGRESS_KEY, JSON.stringify(p))
  } catch (e) { /* 容量不足忽略 */ }
}

function clearProgress () {
  try { localStorage.removeItem(PROGRESS_KEY) } catch (e) { /* ignore */ }
}

// ==================== 输入处理 ====================
function onDigitInput (idx, ev) {
  // 只允许 0-9 单字符
  let v = ev.target.value
  if (v.length > 1) v = v.slice(-1)
  v = v.replace(/\D/g, '')
  digits.value[idx] = v || ''
  inputError.value = ''
  // 自动跳到下一格
  if (v && idx < length.value - 1) {
    slotRefs.value[idx + 1]?.focus()
  }
}

function onDigitKey (ev, idx) {
  if (ev.key === 'Backspace' && !digits.value[idx] && idx > 0) {
    // 当前格空 + 退格 → 退到上一格并清空
    digits.value[idx - 1] = ''
    slotRefs.value[idx - 1]?.focus()
    ev.preventDefault()
  } else if (ev.key === 'Enter') {
    submitGuess()
  } else if (ev.key === 'ArrowLeft' && idx > 0) {
    slotRefs.value[idx - 1]?.focus()
    ev.preventDefault()
  } else if (ev.key === 'ArrowRight' && idx < length.value - 1) {
    slotRefs.value[idx + 1]?.focus()
    ev.preventDefault()
  }
}

function onDigitPaste (ev) {
  const text = (ev.clipboardData?.getData('text') || '').replace(/\D/g, '').slice(0, length.value)
  if (text) {
    const arr = new Array(length.value).fill('')
    for (let i = 0; i < text.length; i++) arr[i] = text[i]
    digits.value = arr
    ev.preventDefault()
    inputError.value = ''
    slotRefs.value[Math.min(text.length, length.value - 1)]?.focus()
  }
}

function clearInput () {
  digits.value = new Array(length.value).fill('')
  inputError.value = ''
  slotRefs.value[0]?.focus()
}

// ==================== 提交 ====================
async function submitGuess () {
  if (!canSubmit.value) return
  const guess = digits.value.join('')
  const err = validateGuess(guess, length.value)
  if (err) {
    inputError.value = err
    return
  }
  inputError.value = ''
  const result = judge(guess, answer.value)
  history.value.push({ guess, A: result.A, B: result.B, ts: Date.now() })

  if (result.A === length.value) {
    // 胜利
    win.value = true
    over.value = true
    flashWin()
    await recordResult('win')
    ElMessage.success(t('guess.winMsg', { attempts: history.value.length }))
    clearProgress()
  } else if (history.value.length >= maxAttempts.value) {
    // 失败
    lose.value = true
    over.value = true
    await recordResult('lose')
    ElMessage.warning(t('guess.loseMsg'))
    clearProgress()
  } else {
    // 继续
    persist()
    // 清空输入, 焦点回到第一格
    digits.value = new Array(length.value).fill('')
    slotRefs.value[0]?.focus()
  }
}

function flashWin () {
  winFlash.value = new Array(length.value).fill(false)
  // 闪烁动画: 输入位变绿
  for (let i = 0; i < length.value; i++) {
    setTimeout(() => {
      winFlash.value[i] = true
      setTimeout(() => { winFlash.value[i] = false }, 600)
    }, i * 100)
  }
}

// ==================== 战绩上报 ====================
async function recordResult (result) {
  const diffCode = { easy: 0, normal: 1, hard: 2 }
  try {
    await saveGameScore({
      gameCode: 'guess',
      score: result === 'win' ? Math.max(100, 1000 - (history.value.length - 1) * 80) : 0,
      maxTile: diffCode[difficulty.value] ?? 0,
      moves: history.value.length,
      playSeconds: 0,
      finished: 1
    })
  } catch (e) { /* 静默 */ }
}

// ==================== 新对局 ====================
function newGameImmediate () {
  digits.value = new Array(length.value).fill('')
  history.value = []
  answer.value = generateAnswer(length.value)
  over.value = false
  win.value = false
  lose.value = false
  inputError.value = ''
  winFlash.value = new Array(length.value).fill(false)
  clearProgress()
  // 首次开局也存档, 这样退出再进能继续(尚未猜的也算进行中)
  persist()
  // 延迟到 nextTick 聚焦第一格
  setTimeout(() => slotRefs.value[0]?.focus(), 80)
}

async function requestNewGame () {
  // 终局状态: 直接重开, 不弹
  if (over.value) {
    newGameImmediate()
    return
  }
  // 没有任何历史记录: 直接开
  if (!history.value.length) {
    newGameImmediate()
    return
  }
  // 检测进行中
  const state = {
    board: history.value.map(h => [h.guess]),
    score: history.value.some(h => h.A === length.value) ? 1 : 0,
    moves: history.value.length,
    over: over.value
  }
  if (!hasOngoingGame(state)) {
    newGameImmediate()
    return
  }
  const choice = await confirmOngoingGame({
    title: t('guess.confirmOngoingTitle'),
    message: t('guess.confirmOngoingMsg'),
    detail: t('guess.confirmOngoingDetail', { attempts: history.value.length, max: maxAttempts.value })
  })
  if (choice === 'restart') newGameImmediate()
}

// ==================== 难度切换 ====================
function onDifficultyChange () {
  newGameImmediate()
}

// ==================== 生命周期 ====================
onMounted(() => {
  // 第一次进入, 恢复旧进度或开新局
  const restored = loadProgress()
  if (!restored) {
    newGameImmediate()
  }
})

onBeforeUnmount(() => {
  // 离开时若有未结束局面, 持久化(answer 在内存里)
  if (!over.value && history.value.length && answer.value) {
    persist()
  }
})

// 监听 length 变化时清掉 ref 数组
watch(length, () => {
  digits.value = new Array(length.value).fill('')
  winFlash.value = new Array(length.value).fill(false)
})
</script>

<style scoped>
.gn-page { padding: 20px 24px 40px; max-width: 760px; margin: 0 auto; }

/* ============ 顶部 ============ */
.gn-head { margin-bottom: 18px; }
.back-link {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: var(--fs-sm); color: var(--c-text-2); cursor: pointer;
  transition: color .15s;
}
.back-link:hover { color: var(--c-primary); }
.gn-title { margin: 8px 0 4px; font-size: 26px; font-weight: 700; color: var(--c-text-1); letter-spacing: .5px; }
.gn-title::before { content: '🔢 '; }
.gn-head .hint { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }

/* ============ 状态卡 ============ */
.gn-status {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; margin-bottom: 14px;
}
.gn-status .stat {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 10px 14px; text-align: center;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.gn-status .lab { display: block; font-size: var(--fs-xs); color: var(--c-text-3); margin-bottom: 2px; }
.gn-status .val { font-size: 20px; font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
.gn-status .val.danger { color: #ef4444; }

/* ============ 工具栏 ============ */
.gn-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 18px; flex-wrap: wrap; }
.gn-toolbar .diff-select { width: 140px; }

/* ============ 输入 ============ */
.gn-input {
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
  padding: 20px 18px 14px; margin-bottom: 16px;
  box-shadow: 0 1px 3px rgba(15, 23, 42, .05);
}
.digit-row { display: flex; justify-content: center; gap: 10px; flex-wrap: wrap; }
.digit-slot {
  width: 52px; height: 60px; text-align: center;
  font-size: 28px; font-weight: 700; font-family: ui-monospace, Menlo, monospace;
  color: var(--c-text-1);
  border: 2px solid var(--c-border); border-radius: 8px;
  background: #f9fafb;
  transition: border-color .15s, background .15s, transform .15s;
  outline: none;
}
.digit-slot:focus { border-color: var(--c-primary); background: #fff; box-shadow: 0 0 0 3px rgba(37, 99, 235, .12); }
.digit-slot:disabled { opacity: .6; cursor: not-allowed; }
.digit-slot.filled { background: #fff; border-color: var(--c-primary); }
.digit-slot.win {
  background: #dcfce7; border-color: #16a34a; color: #15803d;
  animation: pulse .5s ease;
}
@keyframes pulse {
  0% { transform: scale(1); } 50% { transform: scale(1.15); } 100% { transform: scale(1); }
}
.input-actions { display: flex; gap: 8px; justify-content: center; margin-top: 14px; }
.input-error { margin: 10px 0 0; text-align: center; color: #ef4444; font-size: var(--fs-sm); }

/* ============ 历史 ============ */
.gn-history { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); padding: 14px 16px; box-shadow: 0 1px 3px rgba(15, 23, 42, .05); }
.gn-history h3 { margin: 0 0 10px; font-size: 15px; color: var(--c-text-2); font-weight: 600; }
.empty { color: var(--c-text-3); font-size: var(--fs-sm); padding: 12px 0; text-align: center; }
.hist-list { list-style: none; margin: 0; padding: 0; max-height: 360px; overflow-y: auto; }
.hist-item {
  display: grid; grid-template-columns: 36px 1fr auto; align-items: center; gap: 12px;
  padding: 8px 10px; border-radius: 6px; transition: background .15s;
}
.hist-item + .hist-item { margin-top: 4px; }
.hist-item:hover { background: #f3f4f6; }
.hist-item.win { background: linear-gradient(90deg, #dcfce7, transparent); border-left: 3px solid #16a34a; }
.hist-item .num { font-size: var(--fs-xs); color: var(--c-text-3); font-variant-numeric: tabular-nums; }
.hist-item .guess { font-family: ui-monospace, Menlo, monospace; font-size: 18px; font-weight: 700; color: var(--c-text-1); letter-spacing: 4px; }
.hist-item .result { font-size: var(--fs-sm); }
.hist-item .result b { font-size: 18px; font-weight: 700; font-variant-numeric: tabular-nums; }
.hist-item .result .a { color: #16a34a; }
.hist-item .result .b { color: #f59e0b; }
.hist-item .result .x { color: var(--c-text-3); font-weight: 600; margin: 0 4px 0 2px; }
.hist-item .result b + .x { margin-left: 4px; }

/* ============ 胜利/失败遮罩 ============ */
.overlay {
  position: fixed; inset: 0; background: rgba(15, 23, 42, .55);
  display: flex; align-items: center; justify-content: center; z-index: 1000;
  animation: fade-in .25s ease;
}
@keyframes fade-in { from { opacity: 0; } to { opacity: 1; } }
.overlay-card {
  background: #fff; border-radius: 16px; padding: 28px 32px; max-width: 420px; width: calc(100% - 40px);
  text-align: center; box-shadow: 0 20px 60px rgba(0, 0, 0, .25);
}
.overlay-card .big { font-size: 56px; margin-bottom: 6px; }
.overlay-card h3 { margin: 0 0 8px; font-size: 22px; color: var(--c-text-1); }
.overlay-card p { margin: 4px 0; font-size: var(--fs-sm); color: var(--c-text-2); }
.overlay-card .ans { font-size: var(--fs-base); color: var(--c-text-2); }
.overlay-card .ans b { font-family: ui-monospace, Menlo, monospace; font-size: 18px; color: var(--c-text-1); letter-spacing: 4px; padding: 4px 12px; background: #f3f4f6; border-radius: 6px; margin-left: 6px; }
.overlay-btns { margin-top: 18px; display: flex; justify-content: center; gap: 10px; }
.overlay.win .big::after { content: ''; }
.overlay.lose .big { filter: grayscale(.4); }

/* ============ 规则弹窗 ============ */
.rules h4 { margin: 14px 0 6px; font-size: 14px; font-weight: 700; color: var(--c-text-1); }
.rules p { margin: 4px 0; font-size: var(--fs-sm); color: var(--c-text-2); }
.rules ul { margin: 6px 0; padding-left: 20px; }
.rules li { font-size: var(--fs-sm); color: var(--c-text-2); margin: 4px 0; }
.rules .a { color: #16a34a; font-weight: 700; }
.rules .b { color: #f59e0b; font-weight: 700; }
.rules-foot { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--c-border); font-size: var(--fs-xs); color: var(--c-text-3); }

/* ============ 响应式 ============ */
@media (max-width: 480px) {
  .gn-page { padding: 12px 12px 28px; }
  .gn-title { font-size: 22px; }
  .gn-status { grid-template-columns: 1fr; gap: 8px; }
  .gn-status .stat { padding: 8px 12px; text-align: left; display: flex; justify-content: space-between; align-items: baseline; }
  .digit-slot { width: 42px; height: 50px; font-size: 22px; }
  .hist-item { grid-template-columns: 28px 1fr auto; gap: 8px; padding: 6px 8px; }
  .hist-item .guess { font-size: 16px; letter-spacing: 3px; }
  .hist-item .result b { font-size: 16px; }
}
</style>