<template>
  <div class="fq-mask" @click.self="$emit('close')">
    <div class="fq-panel">
      <!-- ================= 头部 ================= -->
      <header class="fq-head">
        <div class="fq-title">
          <h2>{{ t('farm.quality.title') }}</h2>
          <p>{{ t('farm.quality.subtitle') }}</p>
        </div>
        <button class="fq-close" @click="$emit('close')">×</button>
      </header>

      <!-- ================= 当前状态 ================= -->
      <section class="fq-status">
        <div class="fq-stat">
          <b>{{ current.label }}</b>
          <span>{{ t('farm.quality.currentLevel') }}</span>
        </div>
        <div class="fq-stat">
          <b :class="fpsClass">{{ fps > 0 ? fps : '—' }}</b>
          <span>{{ t('farm.quality.measuredFps') }}</span>
        </div>
        <div class="fq-stat">
          <b>{{ Q.pixelRatioMax }}×</b>
          <span>{{ t('farm.quality.pixelRatio') }}</span>
        </div>
        <div class="fq-stat">
          <b>{{ Q.shadowEnabled ? Q.shadowMapSize : '—' }}</b>
          <span>{{ t('farm.quality.shadow') }}</span>
        </div>
      </section>

      <!-- ================= 档位选择 ================= -->
      <section class="fq-list">
        <button
          v-for="lv in levels"
          :key="lv.id"
          class="fq-item"
          :class="{ on: setting === lv.id }"
          @click="pick(lv.id)"
        >
          <div class="fq-item-main">
            <b>{{ t('farm.quality.level.' + lv.id) }}</b>
            <span class="fq-item-desc">{{ t('farm.quality.desc.' + lv.id) }}</span>
          </div>
          <div class="fq-item-meta">
            <span class="fq-badge" :class="'fq-b-' + lv.id">{{ t('farm.quality.hint.' + lv.id) }}</span>
            <i v-if="setting === lv.id" class="fq-check">✓</i>
          </div>
        </button>
      </section>

      <!-- ================= 自动检测说明 ================= -->
      <footer class="fq-foot">
        <p class="fq-note">
          <b>{{ t('farm.quality.autoTitle') }}</b>
          {{ t('farm.quality.autoDesc', { detected: t('farm.quality.level.' + detected) }) }}
        </p>
        <p class="fq-note fq-note-dim">
          {{ t('farm.quality.autoDowngradeNote') }}
        </p>
      </footer>
    </div>
  </div>
</template>

<script setup>
/**
 * 画质设置面板（2026-10-26 画质分级）
 *
 * 🔴 **本面板只做两件事**：
 *   1. 展示当前档位与实测帧率（让用户能判断档位是否合适，而不是凭感觉猜）
 *   2. 切换档位 → 调 quality.js 的 setQuality()
 *
 * **所有参数矩阵都在 scene/quality.js**，本组件不硬编码任何画质数字。
 * 这样「改画质参数」只有一个地方要改（符合本项目「同型代码只改一处」铁律）。
 */
import { computed, onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  QUALITY_LEVELS, QUALITY_IDS, AUTO_QUALITY,
  getQuality, setQuality, detectQualityLevel, initQuality
} from './scene/quality.js'

const emit = defineEmits(['close', 'changed'])
const { t } = useI18n()

const levels = QUALITY_IDS.map((id) => QUALITY_LEVELS[id])

const setting = ref(AUTO_QUALITY)
const Q = ref(QUALITY_LEVELS.standard)
const detected = ref('standard')
const fps = ref(0)

/**
 * 实测帧率。
 *
 * 🔴 **来源是 3D 场景实测上报**，不是估算 —— 面板打开时场景可能还没起来，
 *   所以要允许父组件通过 prop 传进来。
 */
const props = defineProps({
  /** 3D 场景上报的实测帧率（0 = 还没测到） */
  fps: { type: Number, default: 0 }
})

const fpsClass = computed(() => {
  if (props.fps <= 0) return 'fq-fps-na'
  if (props.fps >= 50) return 'fq-fps-good'
  if (props.fps >= 30) return 'fq-fps-mid'
  return 'fq-fps-low'
})

onMounted(() => {
  initQuality()
  refresh()
  detected.value = detectQualityLevel()
})

function refresh() {
  const q = getQuality()
  setting.value = q.auto ? AUTO_QUALITY : q.id
  Q.value = q.params
  detected.value = q.detected
}

/**
 * 切换档位。
 *
 * ⚠️ **只调 setQuality，不自己操作场景** —— 场景组件订阅了 quality.js 的
 *   变更事件，自己会决定「热更新」还是「重建」。
 *   这样「切换画质」的行为只有一处实现。
 */
function pick(id) {
  const r = setQuality(id)
  if (!r.ok) return
  refresh()
  emit('changed', { id, setting: setting.value })
}
</script>

<style scoped>
/* 🔴 z-index 必须 > .wm-page 的 60（中国地图浮层），
   否则地图会盖住画质面板 —— 与「地图浮层盖住成就/商店面板」同型缺陷。 */
.fq-mask {
  position: fixed;
  inset: 0;
  z-index: 62;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 22px;
  background: rgba(8, 14, 10, 0.62);
  backdrop-filter: blur(3px);
}
.fq-panel {
  width: min(520px, 100%);
  max-height: 100%;
  overflow-y: auto;
  border-radius: 14px;
  background: #fbfdf9;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
}

/* ---------- 头部 ---------- */
.fq-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid #e6ece4;
  background: linear-gradient(180deg, #f6faf3, #fbfdf9);
}
.fq-title h2 { margin: 0 0 4px; font-size: 19px; font-weight: 700; color: #16241a; }
.fq-title p { margin: 0; font-size: 12.5px; color: #6b7a6d; }
.fq-close {
  width: 28px; height: 28px; flex: none;
  border: none; border-radius: 8px;
  background: #eef2ec; color: #5a6a5b; font-size: 18px; line-height: 1; cursor: pointer;
}
.fq-close:hover { background: #e2e8df; }

/* ---------- 状态条 ---------- */
.fq-status {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  padding: 14px 20px;
  border-bottom: 1px solid #eef2ec;
}
.fq-stat {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 3px;
  padding: 9px 4px;
  border-radius: 9px;
  background: #f4f8f1;
}
.fq-stat b { font-size: 16px; font-weight: 700; color: #1d3323; }
.fq-stat span { font-size: 11px; color: #6b7a6d; text-align: center; }
.fq-fps-good { color: #2f7d32 !important; }
.fq-fps-mid { color: #b8860b !important; }
.fq-fps-low { color: #c0392b !important; }
.fq-fps-na { color: #9aa79b !important; }

/* ---------- 档位列表 ---------- */
.fq-list { padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; }
.fq-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  width: 100%;
  padding: 11px 13px;
  border: 1px solid #e3eae0;
  border-radius: 10px;
  background: #fff;
  cursor: pointer;
  text-align: left;
  transition: border-color 0.15s, background 0.15s;
}
.fq-item:hover { border-color: #b9cfae; background: #f8fbf5; }
.fq-item.on { border-color: #6fa25c; background: #f0f7ea; }
.fq-item-main { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.fq-item-main b { font-size: 14px; font-weight: 600; color: #1d3323; }
.fq-item-desc { font-size: 11.5px; color: #6b7a6d; line-height: 1.45; }
.fq-item-meta { display: flex; align-items: center; gap: 8px; flex: none; }
.fq-badge {
  font-size: 10.5px; padding: 2px 7px; border-radius: 9px;
  background: #eef2ec; color: #5a6a5b; white-space: nowrap;
}
.fq-b-low { background: #e8f0f6; color: #3d6b8c; }
.fq-b-standard { background: #eef2ec; color: #5a6a5b; }
.fq-b-high { background: #e5f2e0; color: #3f7a35; }
.fq-b-ultra { background: #f6ead8; color: #9a6a22; }
.fq-check { color: #4d8f3f; font-style: normal; font-size: 15px; font-weight: 700; }

/* ---------- 底部说明 ---------- */
.fq-foot { padding: 4px 20px 16px; border-top: 1px solid #eef2ec; }
.fq-note {
  margin: 10px 0 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: #6b7a6d;
}
.fq-note b { color: #3d4f41; margin-right: 6px; }
.fq-note-dim { color: #93a094; font-size: 11px; }

/* ---------- 窄屏 ---------- */
@media (max-width: 480px) {
  .fq-mask { padding: 12px; }
  .fq-status { grid-template-columns: repeat(2, 1fr); }
  .fq-item { flex-direction: column; align-items: flex-start; gap: 8px; }
  .fq-item-meta { width: 100%; justify-content: space-between; }
}
</style>
