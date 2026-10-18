<script setup>
/**
 * 游戏中心
 * <p>
 * 以卡片网格展示「娱乐」板块下已上线的游戏, 每张卡片给到最关键的几项信息:
 * 最高分、总局数、是否有一局正在进行(分数/步数/离开时间), 以及一键「开始 / 继续」。
 * <p>
 * 扩展方式: 往 GAMES 清单里加一项(编码需后端 GameService.SUPPORTED_GAMES 同步放行),
 * 卡片会自动渲染, 无需改模板。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getGamesOverview } from '../../api/game'

const router = useRouter()
const { t } = useI18n()

const GAMES = [
  {
    code: '2048',
    name: '2048',
    route: '/games/2048',
    descKey: 'games.2048.desc',
    color: '#edc22e',
    cover: [
      [2, 4, 0, 8],
      [0, 16, 32, 0],
      [2, 64, 0, 4],
      [0, 0, 128, 0]
    ]
  },
  {
    code: 'gomoku',
    name: '五子棋',
    nameKey: 'gomoku.title',
    route: '/games/gomoku',
    descKey: 'games.gomoku.desc',
    color: '#b08968',
    cover: 'gomoku' // 标记用 SVG 渲染
  },
  {
    code: 'guess',
    name: '猜数字',
    nameKey: 'guess.title',
    route: '/games/guess',
    descKey: 'games.guess.desc',
    color: '#10b981',
    cover: 'guess' // 标记用 SVG 渲染
  }
]

const overview = ref({})
const loading = ref(true)

/** 卡片数据 = 前端清单 + 后端个人数据 */
const cards = computed(() =>
  GAMES.map((g) => {
    const d = overview.value[g.code] || {}
    return {
      ...g,
      // 最高分与游戏内口径一致: 历史最高 与 进行中分数 取大(玩家实时超过纪录时卡片也同步)
      bestScore: Math.max(d.bestScore || 0, d.hasProgress ? (d.progressScore || 0) : 0),
      playedCount: d.playedCount || 0,
      hasProgress: !!d.hasProgress,
      progressScore: d.progressScore,
      progressMoves: d.progressMoves,
      progressUpdatedAt: d.progressUpdatedAt,
      lastPlayedAt: d.lastPlayedAt
    }
  })
)

/** 汇总:玩过的游戏数 / 累计局数 */
const summary = computed(() => {
  let played = 0
  let total = 0
  for (const c of cards.value) {
    if (c.playedCount > 0) played++
    total += c.playedCount
  }
  return { played, total, count: cards.value.length }
})

const fmtRelative = (s) => {
  if (!s) return ''
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  const diff = Date.now() - d.getTime()
  const m = Math.floor(diff / 60000)
  if (m < 1) return t('games.justNow')
  if (m < 60) return t('games.minutesAgo', { n: m })
  const h = Math.floor(m / 60)
  if (h < 24) return t('games.hoursAgo', { n: h })
  const day = Math.floor(h / 24)
  if (day < 30) return t('games.daysAgo', { n: day })
  const p = (x) => String(x).padStart(2, '0')
  return `${p(d.getMonth() + 1)}/${p(d.getDate())}`
}

const open = (card) => router.push(card.route)

const load = async () => {
  loading.value = true
  try {
    const res = await getGamesOverview()
    overview.value = res.data || {}
  } catch (e) { /* 统一弹错 */ } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="game-center" v-loading="loading">
    <!-- 页头 -->
    <header class="gc-head">
      <div class="gc-title">
        <h2>{{ $t('games.title') }}</h2>
        <p class="gc-sub">{{ $t('games.subtitle') }}</p>
      </div>
      <div class="gc-summary">
        <div class="sum-item">
          <span class="n">{{ summary.count }}</span>
          <span class="l">{{ $t('games.statTotal') }}</span>
        </div>
        <div class="sum-item">
          <span class="n">{{ summary.played }}</span>
          <span class="l">{{ $t('games.statPlayed') }}</span>
        </div>
        <div class="sum-item">
          <span class="n">{{ summary.total }}</span>
          <span class="l">{{ $t('games.statRounds') }}</span>
        </div>
      </div>
    </header>

    <!-- 卡片网格 -->
    <div class="gc-grid">
      <article
        v-for="card in cards"
        :key="card.code"
        class="gcard"
        role="button"
        tabindex="0"
        :style="{ '--theme': card.color }"
        @click="open(card)"
        @keydown.enter="open(card)"
      >
        <!-- 封面: 迷你棋盘(2048 风格)或五子棋 SVG -->
        <div class="gcard-cover">
          <div v-if="card.cover === 'gomoku'" class="mini-gomoku">
            <svg viewBox="0 0 100 100" class="mini-gomoku-svg">
              <rect x="6" y="6" width="88" height="88" rx="6" fill="#e8c89c" />
              <g stroke="#5a3a1a" stroke-width="0.6">
                <line v-for="i in 4" :key="'gh'+i" :x1="20" :x2="80" :y1="20 + (i-1)*20" :y2="20 + (i-1)*20" />
                <line v-for="i in 4" :key="'gv'+i" :y1="20" :y2="80" :x1="20 + (i-1)*20" :x2="20 + (i-1)*20" />
              </g>
              <!-- 黑子连五示意 -->
              <circle cx="30" cy="40" r="5.5" fill="#0a0a0a" />
              <circle cx="40" cy="40" r="5.5" fill="#0a0a0a" />
              <circle cx="50" cy="40" r="5.5" fill="#0a0a0a" />
              <circle cx="60" cy="40" r="5.5" fill="#0a0a0a" />
              <circle cx="70" cy="40" r="5.5" fill="#0a0a0a" />
              <circle cx="40" cy="60" r="5.5" fill="#ffffff" stroke="#c8c8c8" stroke-width="0.5" />
              <circle cx="55" cy="65" r="5.5" fill="#ffffff" stroke="#c8c8c8" stroke-width="0.5" />
            </svg>
          </div>
          <div v-else-if="card.cover === 'guess'" class="mini-guess">
            <div class="digits">
              <span class="ds filled">3</span>
              <span class="ds filled">1</span>
              <span class="ds filled">4</span>
              <span class="ds empty"></span>
              <span class="ds filled">7</span>
              <span class="ds empty"></span>
            </div>
            <div class="hint-row">
              <span class="a">2</span><span class="lbl">A</span>
              <span class="b">2</span><span class="lbl">B</span>
            </div>
            <div class="hint-row">
              <span class="a">3</span><span class="lbl">A</span>
              <span class="b">0</span><span class="lbl">B</span>
            </div>
          </div>
          <div v-else class="mini-board">
            <div v-for="(row, r) in card.cover" :key="r" class="mini-row">
              <span
                v-for="(v, c) in row"
                :key="c"
                class="mini-cell"
                :class="v ? `v${v <= 2048 ? v : 'big'}` : 'empty'"
              >{{ v || '' }}</span>
            </div>
          </div>
          <span v-if="card.hasProgress" class="badge live">
            <i class="dot"></i>{{ $t('games.ongoing') }}
          </span>
        </div>

        <!-- 信息区 -->
        <div class="gcard-body">
          <div class="gcard-name">
            <h3>{{ card.name }}</h3>
            <el-icon class="go-arrow"><ArrowRight /></el-icon>
          </div>
          <p class="gcard-desc">{{ $t(card.descKey) }}</p>

          <dl class="gcard-stats">
            <div class="st">
              <dt>{{ $t('games.best') }}</dt>
              <dd class="hi">{{ card.bestScore }}</dd>
            </div>
            <div class="st">
              <dt>{{ $t('games.rounds') }}</dt>
              <dd>{{ card.playedCount }}</dd>
            </div>
            <div class="st">
              <dt>{{ $t('games.lastPlay') }}</dt>
              <dd class="muted">{{ card.lastPlayedAt ? fmtRelative(card.lastPlayedAt) : $t('games.never') }}</dd>
            </div>
          </dl>

          <!-- 进行中进度条 -->
          <div v-if="card.hasProgress" class="progress-line">
            <span class="pl-tag">{{ $t('games.inProgress') }}</span>
            <span class="pl-main">{{ card.progressScore }} {{ $t('games.points') }}</span>
            <span class="pl-sep">·</span>
            <span class="pl-sub">{{ $t('game.moves', { n: card.progressMoves || 0 }) }}</span>
            <span class="pl-time">{{ fmtRelative(card.progressUpdatedAt) }}</span>
          </div>

          <el-button
            class="gcard-btn"
            :type="card.hasProgress ? 'primary' : 'default'"
            @click.stop="open(card)"
          >
            {{ card.hasProgress ? $t('games.resume') : (card.playedCount ? $t('games.playAgain2') : $t('games.start')) }}
          </el-button>
        </div>
      </article>
    </div>
  </div>
</template>

<style scoped>
.game-center { padding: 24px 24px 48px; max-width: 1120px; margin: 0 auto; }

/* ============ 页头 ============ */
.gc-head {
  display: flex; align-items: flex-end; justify-content: space-between;
  gap: 20px; flex-wrap: wrap; margin-bottom: 22px;
}
.gc-title h2 { margin: 0 0 6px; font-size: 26px; font-weight: 700; color: var(--c-text-1); }
.gc-sub { margin: 0; font-size: var(--fs-sm); color: var(--c-text-3); }
.gc-summary { display: flex; gap: 8px; }
.sum-item {
  min-width: 74px; padding: 8px 12px; text-align: center;
  background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m);
}
.sum-item .n { display: block; font-size: 19px; font-weight: 700; color: var(--c-text-1); font-variant-numeric: tabular-nums; }
.sum-item .l { font-size: var(--fs-xs); color: var(--c-text-3); }

/* ============ 卡片网格 ============ */
.gc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 20px;
}

.gcard {
  display: flex; flex-direction: column;
  background: #fff; border: 1px solid var(--c-border);
  border-radius: 14px; overflow: hidden; cursor: pointer;
  transition: transform .18s ease, box-shadow .18s ease, border-color .18s ease;
  outline: none;
}
.gcard:hover, .gcard:focus-visible {
  transform: translateY(-3px);
  border-color: color-mix(in srgb, var(--theme) 55%, var(--c-border));
  box-shadow: 0 10px 28px rgba(15, 23, 42, .12);
}

/* 封面: 用 2048 真实配色画迷你棋盘, 一眼认出是什么游戏 */
.gcard-cover {
  position: relative;
  padding: 18px;
  background: linear-gradient(140deg, #bbada0, #a89a8c);
  display: flex; align-items: center; justify-content: center;
}
.mini-gomoku { width: 100%; max-width: 168px; }
.mini-gomoku-svg { width: 100%; height: auto; display: block; border-radius: 6px; }
.mini-guess {
  width: 100%; max-width: 168px; padding: 8px 4px;
  display: flex; flex-direction: column; align-items: center; gap: 6px;
  background: linear-gradient(140deg, #d1fae5, #a7f3d0);
  border-radius: 6px;
}
.mini-guess .digits { display: flex; gap: 4px; }
.mini-guess .ds {
  width: 16px; height: 22px; line-height: 22px; text-align: center;
  font-size: 13px; font-weight: 700; font-family: ui-monospace, Menlo, monospace;
  color: #065f46; background: #fff; border: 1.5px solid #10b981; border-radius: 3px;
}
.mini-guess .ds.empty { background: rgba(255,255,255,.5); border-style: dashed; }
.mini-guess .hint-row { display: flex; align-items: center; gap: 6px; font-size: 12px; font-family: ui-monospace, Menlo, monospace; }
.mini-guess .hint-row .a { color: #16a34a; font-weight: 700; font-size: 14px; }
.mini-guess .hint-row .b { color: #f59e0b; font-weight: 700; font-size: 14px; }
.mini-guess .hint-row .lbl { color: #6b7280; font-weight: 600; margin-right: 4px; }
.mini-board { display: grid; gap: 5px; width: 100%; max-width: 168px; }
.mini-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 5px; }
.mini-cell {
  aspect-ratio: 1 / 1;
  display: flex; align-items: center; justify-content: center;
  border-radius: 4px; font-size: 11px; font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.mini-cell.empty { background: rgba(238, 228, 218, .35); }
.mini-cell.v2 { background: #eee4da; color: #776e65; }
.mini-cell.v4 { background: #ede0c8; color: #776e65; }
.mini-cell.v8 { background: #f2b179; color: #fff; }
.mini-cell.v16 { background: #f59563; color: #fff; }
.mini-cell.v32 { background: #f67c5f; color: #fff; }
.mini-cell.v64 { background: #f65e3b; color: #fff; }
.mini-cell.v128 { background: #edcf72; color: #fff; font-size: 10px; }
.mini-cell.vbig { background: #3c3a32; color: #fff; font-size: 10px; }

.badge.live {
  position: absolute; top: 12px; right: 12px;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 3px 9px; border-radius: 999px;
  background: rgba(255, 255, 255, .95);
  font-size: 11px; font-weight: 600; color: #16a34a;
  box-shadow: 0 2px 8px rgba(0, 0, 0, .12);
}
.badge.live .dot {
  width: 6px; height: 6px; border-radius: 50%; background: #22c55e;
  animation: pulse 1.6s ease-in-out infinite;
}
@keyframes pulse {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: .45; transform: scale(.8); }
}

/* 信息区 */
.gcard-body { padding: 14px 16px 16px; display: flex; flex-direction: column; flex: 1; }
.gcard-name { display: flex; align-items: center; justify-content: space-between; }
.gcard-name h3 { margin: 0; font-size: 17px; font-weight: 700; color: var(--c-text-1); letter-spacing: .3px; }
.go-arrow { color: var(--c-text-3); font-size: 14px; transition: transform .18s, color .18s; }
.gcard:hover .go-arrow { color: var(--theme); transform: translateX(3px); }
.gcard-desc { margin: 4px 0 12px; font-size: var(--fs-sm); color: var(--c-text-3); line-height: 1.5; }

.gcard-stats {
  display: flex; gap: 18px; margin: 0 0 12px; padding: 10px 0;
  border-top: 1px solid var(--c-border); border-bottom: 1px solid var(--c-border);
}
.gcard-stats .st { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.gcard-stats dt { font-size: var(--fs-xs); color: var(--c-text-3); }
.gcard-stats dd {
  margin: 0; font-size: 15px; font-weight: 600; color: var(--c-text-1);
  font-variant-numeric: tabular-nums;
}
.gcard-stats dd.hi { color: #d99a00; }
.gcard-stats dd.muted { font-weight: 400; font-size: var(--fs-sm); color: var(--c-text-2); }

.progress-line {
  display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
  margin-bottom: 12px; padding: 7px 10px;
  background: var(--c-primary-bg, #ecf5ff); border-radius: 7px;
  font-size: var(--fs-xs); color: var(--c-text-2);
}
.progress-line .pl-tag {
  padding: 1px 6px; border-radius: 4px; background: var(--c-primary, #2563eb);
  color: #fff; font-weight: 600;
}
.progress-line .pl-main { font-weight: 700; color: var(--c-text-1); }
.progress-line .pl-sep { color: var(--c-text-3); }
.progress-line .pl-time { margin-left: auto; color: var(--c-text-3); }

.gcard-btn { margin-top: auto; width: 100%; }

/* ============ 响应式 ============ */
@media (max-width: 768px) {
  .game-center { padding: 16px 14px 36px; }
  .gc-head { align-items: flex-start; }
  .gc-title h2 { font-size: 21px; }
  .gc-summary { width: 100%; }
  .sum-item { flex: 1; min-width: 0; }
  .gc-grid { grid-template-columns: 1fr; gap: 14px; }
}
</style>
