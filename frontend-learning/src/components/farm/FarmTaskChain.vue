<template>
  <div class="ft-panel">
    <div v-if="loading" class="ft-hint muted">{{ $t('common.loading') }}</div>
    <div v-else-if="error" class="ft-hint error">{{ error }}</div>
    <p v-else-if="!tasks.length" class="ft-hint muted">{{ $t('farm.task.empty') }}</p>

    <ol v-else class="ft-list">
      <li
        v-for="(t, i) in tasks"
        :key="t.id"
        class="ft-item"
        :class="{ done: t.done }"
      >
        <span class="ft-mark">{{ t.done ? '✓' : i + 1 }}</span>
        <div class="ft-main">
          <div class="ft-title">{{ t.title }}</div>
          <div v-if="t.done" class="ft-done">{{ $t('farm.task.doneAt') }}</div>
        </div>
      </li>
    </ol>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getFarmTasks } from '../../api/farm'

/**
 * 农场任务链（2026-10-28 P2）。
 *
 * <p>🔴 <b>这是「非阻塞式引导」的载体</b>：任务链只<b>列出</b>下一步做什么，
 * 绝不拦截玩家操作（S23 §2.1 同一条纪律 —— 风险提示只提示不拦截）。
 *
 * <p>🔴 <b>完成状态由后端算</b>：这个组件只渲染 {@code status}，
 * 不做本地判定。本地判定会带来「农场页说还没完成、学习看板说完成了」的矛盾，
 * 而且玩家做完农事后必须刷新页面才知道 —— 那是坏体验。
 *
 * <p>🔴 <b>任务标题是后端中文原文</b>：预置任务是<b>写进数据库</b>的数据，
 * 库里存的就是最终展示文本。英文界面下这里仍是中文 ——
 * 这是「服务端预置任务」的固有代价（要做多语言得按语言各写一条），
 * 已在 {@code FarmTaskChain} 类注释里写明，不在这里假装支持。
 */
const { t } = useI18n()

const props = defineProps({
  /** 预加载的数据（可选）。不传则组件自己拉。 */
  tasks: { type: Array, default: null }
})

const loading = ref(false)
const error = ref('')
const loaded = ref([])

const tasks = computed(() => {
  if (Array.isArray(props.tasks)) return props.tasks
  return loaded.value
})

onMounted(async () => {
  if (Array.isArray(props.tasks)) return
  loading.value = true
  try {
    const res = await getFarmTasks()
    loaded.value = res.data || []
  } catch (e) {
    error.value = e?.message || String(e)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.ft-panel { min-width: 0; max-width: 100%; box-sizing: border-box; }
.ft-hint { margin: 0; font-size: var(--fs-xs); color: var(--c-text-3); }
.ft-hint.error { color: #c0392b; }

.ft-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ft-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 7px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-m);
  background: #fff;
}
.ft-item.done {
  border-color: #bbf7d0;
  background: linear-gradient(90deg, #f6fcf8, #fff);
}

.ft-mark {
  flex: 0 0 auto;
  width: 19px;
  height: 19px;
  border-radius: 50%;
  border: 1px solid var(--c-border-strong);
  color: var(--c-text-2);
  font-size: 11px;
  font-weight: 600;
  display: grid;
  place-items: center;
  margin-top: 1px;
}
.ft-item.done .ft-mark {
  border-color: transparent;
  background: #22c55e;
  color: #fff;
}

.ft-main { flex: 1; min-width: 0; }
.ft-title {
  font-size: var(--fs-xs);
  color: var(--c-text-1);
  line-height: 1.6;
}
.ft-item.done .ft-title {
  color: var(--c-text-3);
  text-decoration: line-through;
}
.ft-done {
  margin-top: 2px;
  font-size: 10px;
  color: #146c33;
}

.muted { color: var(--c-text-3); }
</style>
