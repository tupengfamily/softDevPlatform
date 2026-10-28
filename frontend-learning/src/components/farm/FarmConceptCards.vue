<template>
  <div class="fc-panel">
    <!-- 加载中 / 出错 -->
    <div v-if="loading" class="fc-hint muted">{{ $t('common.loading') }}</div>
    <div v-else-if="error" class="fc-hint error">{{ error }}</div>

    <!-- 🔴 空态：课程种子没同步 → 不渲染「0%」进度条（那等于说「一项都没学」） -->
    <p v-else-if="!cards.length" class="fc-hint muted">{{ $t('farm.concept.empty') }}</p>

    <div v-else class="fc-grid">
      <article
        v-for="c in cards"
        :key="c.conceptId"
        class="fc-card"
        :class="{ unlocked: c.unlocked, locked: !c.unlocked }"
      >
        <header class="fc-head">
          <span class="fc-no">{{ c.chapterNo }}</span>
          <b class="fc-title">{{ titleOf(c) }}</b>
          <span v-if="c.unlocked" class="fc-badge">{{ $t('farm.concept.unlocked') }}</span>
        </header>

        <!-- 引擎指标：这一概念在引擎里由哪些量决定（教学锚点） -->
        <div class="fc-metric mono">{{ c.engineMetric }}</div>

        <!-- 进度条：分母 = 该概念自己的课时数 -->
        <div class="fc-bar">
          <i :style="{ width: (c.learnedPercent || 0) + '%' }"></i>
        </div>

        <div class="fc-meta">
          <span>{{ $t('farm.concept.progress', { learned: c.learnedLessons || 0, total: c.totalLessons || 0 }) }}</span>
          <span class="mono">{{ c.learnedPercent || 0 }}%</span>
        </div>

        <!-- 入口：直达该概念下一节该学的课时（下一节 id 由后端算，前端不自己挑） -->
        <button v-if="c.nextLessonId" class="fc-go" @click="$emit('open', c)">
          {{ c.unlocked ? $t('farm.concept.read') : $t('farm.concept.learn') }}
        </button>
      </article>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getFarmConcepts } from '../../api/farm'

/**
 * 农业科学概念卡（2026-10-28 P2）。
 *
 * <p>🔴 <b>这是「实操即课时」的视觉落点</b>：8 张卡对应 8 个农业科学概念，
 * 每张卡显示「这个概念学完了没、学了几节」。
 *
 * <p>🔴 <b>数据源是后端 {@code GET /api/games/farm/concepts}</b> ——
 * 前端不自己判断「解锁」也不自己数课时。
 * 理由：解锁口径必须与学习看板一致（课时全部学完），
 * 如果前端自己按「做过某动作」判断，就会出现「农场页说已解锁、
 * 学习看板说还有 2 节没学」这种自相矛盾 —— 本项目反复踩过的分叉坑。
 *
 * <p>🔴 <b>「缺数据」≠「零值」</b>：后端 {@code hasLessons=false}
 * 表示课程种子没同步到，这时<b>不画进度条</b>（画一根 0% 的条等于说「这项是 0」）。
 *
 * <p>🔴 <b>跨语言纪律</b>：概念标题优先按 {@code conceptId} 查 i18n，
 * 查不到退回后端中文原文（宁可中文也不要空白）。
 */
const { t } = useI18n()

const props = defineProps({
  /** 预加载的数据（可选）。不传则组件自己拉。 */
  cards: { type: Array, default: null }
})

const emit = defineEmits(['open'])

const loading = ref(false)
const error = ref('')
const loaded = ref([])

const cards = computed(() => {
  if (Array.isArray(props.cards)) return props.cards
  return loaded.value
})

onMounted(async () => {
  // 父组件已经给了数据就不重复请求
  if (Array.isArray(props.cards)) return
  loading.value = true
  try {
    const res = await getFarmConcepts()
    loaded.value = res.data || []
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
})

/** 概念标题：i18n 优先，查不到退回后端标题。 */
function titleOf (c) {
  const key = 'farm.concept.name.' + c.conceptId
  const v = t(key)
  return v === key ? (c.title || c.conceptId) : v
}
</script>

<style scoped>
.fc-panel { min-width: 0; max-width: 100%; box-sizing: border-box; }
.fc-hint { margin: 0; font-size: var(--fs-xs); color: var(--c-text-3); }
.fc-hint.error { color: #c0392b; }

.fc-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(210px, 1fr));
  gap: 10px;
}

.fc-card {
  border: 1px solid var(--c-border);
  border-radius: var(--r-m);
  padding: 12px 14px;
  background: #fff;
  transition: all .15s;
}
.fc-card.unlocked {
  border-color: #86efac;
  background: linear-gradient(180deg, #f6fcf8, #fff);
}
.fc-card.locked { opacity: .9; }

.fc-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 6px;
}
.fc-no {
  flex: 0 0 auto;
  min-width: 20px;
  height: 20px;
  border-radius: 4px;
  background: var(--c-primary-bg, #eff6ff);
  color: #1d4ed8;
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
}
.fc-title {
  flex: 1;
  font-size: var(--fs-sm);
  font-weight: 600;
  color: var(--c-text-1);
}
.fc-badge {
  flex-shrink: 0;
  font-size: 10px;
  padding: 1px 7px;
  border-radius: 999px;
  background: #dcfce7;
  color: #146c33;
  font-weight: 600;
}

.fc-metric {
  font-size: 10px;
  color: var(--c-text-3);
  margin-bottom: 8px;
  word-break: break-all;
}

.fc-bar {
  height: 5px;
  background: var(--c-border);
  border-radius: 999px;
  overflow: hidden;
}
.fc-bar i {
  display: block;
  height: 100%;
  background: var(--c-success, #22c55e);
  border-radius: 999px;
  transition: width .3s;
}

.fc-meta {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  margin-top: 6px;
  font-size: 11px;
  color: var(--c-text-3);
}

.fc-go {
  margin-top: 10px;
  width: 100%;
  padding: 5px 10px;
  border-radius: var(--r-s);
  border: 1px solid var(--c-border-strong);
  background: #fff;
  color: var(--c-primary);
  font-size: var(--fs-xs);
  cursor: pointer;
}
.fc-go:hover { background: var(--c-primary-bg, #eff6ff); }

.muted { color: var(--c-text-3); }
.mono { font-variant-numeric: tabular-nums; }
</style>
