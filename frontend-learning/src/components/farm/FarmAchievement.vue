<template>
  <div class="ac-mask" @click.self="$emit('close')">
    <div class="ac-panel">
      <!-- ================= 头部 ================= -->
      <header class="ac-head">
        <div class="ac-title">
          <h2>{{ t('farm.ach.title') }}</h2>
          <p>
            {{ t('farm.ach.unlocked', { n: summary.unlockedCount || 0, total: summary.totalCount || 0 }) }}
            · {{ t('farm.ach.albumProgress', { n: summary.cropsCollected || 0, total: summary.cropsTotal || 0 }) }}
          </p>
        </div>
        <div class="ac-actions">
          <span v-if="summary.longestStreak" class="ac-chip streak">
            🔗 {{ t('farm.ach.streakNow', { n: summary.longestStreak }) }}
          </span>
          <button class="ac-close" @click="$emit('close')">×</button>
        </div>
      </header>

      <!-- ================= 概览卡 ================= -->
      <section v-if="summary.totalCount" class="ac-overview">
        <div class="ac-ring">
          <svg viewBox="0 0 72 72" class="ring-svg">
            <circle cx="36" cy="36" r="30" class="ring-bg" />
            <circle
              cx="36" cy="36" r="30" class="ring-fg"
              :stroke-dasharray="`${(summary.percent || 0) * 1.884} 188.4`"
            />
          </svg>
          <span class="ring-txt">{{ summary.percent || 0 }}<i>%</i></span>
        </div>
        <div class="ac-stats">
          <div class="ac-stat">
            <b>{{ summary.harvestedCount || 0 }}</b>
            <span>{{ t('farm.ach.statHarvests') }}</span>
          </div>
          <div class="ac-stat">
            <b>{{ summary.bestYieldRatio || 0 }}</b>
            <span>{{ t('farm.ach.statBestRatio') }}</span>
          </div>
          <div class="ac-stat">
            <b>¥{{ summary.totalNet || 0 }}</b>
            <span>{{ t('farm.ach.statNet') }}</span>
          </div>
          <div class="ac-stat">
            <b>{{ summary.firstHarvestDate || '—' }}</b>
            <span>{{ t('farm.ach.statFirstHarvest') }}</span>
          </div>
        </div>
      </section>

      <div v-if="loading" class="ac-loading">{{ t('common.loading') }}</div>
      <div v-else-if="error" class="ac-error">{{ error }}</div>

      <div v-else class="ac-body">
        <!-- ============ 成就分类 ============ -->
        <section v-for="cat in categories" :key="cat.code" class="ac-sec">
          <header class="ac-sec-head">
            <b>{{ t(cat.labelKey) }}</b>
            <span>{{ t('farm.ach.catProgress', { n: cat.done, total: cat.total }) }}</span>
          </header>
          <div class="ac-grid">
            <article
              v-for="a in cat.items"
              :key="a.code"
              class="ac-item"
              :class="{ done: a.unlocked }"
            >
              <div class="ac-item-head">
                <span class="ac-mark">{{ a.unlocked ? '✓' : '○' }}</span>
                <b class="ac-name">{{ t(a.nameKey) }}</b>
                <span v-if="a.unlocked" class="ac-badge">{{ t('farm.ach.done') }}</span>
              </div>
              <p class="ac-desc">{{ t(a.descKey) }}</p>
              <!-- 🔴 flag 型（开关型成就）没有「进度」概念，画条进度条是误导 -->
              <div v-if="a.unit !== 'flag'" class="ac-bar">
                <i :style="{ width: a.percent + '%' }"></i>
              </div>
              <span class="ac-prog">{{ progressText(a) }}</span>
            </article>
          </div>
        </section>

        <!-- ============ 作物图鉴 ============ -->
        <section class="ac-sec">
          <header class="ac-sec-head">
            <b>{{ t('farm.ach.albumTitle') }}</b>
            <span>{{ t('farm.ach.albumHint') }}</span>
          </header>
          <div class="ac-album">
            <div
              v-for="c in album"
              :key="c.cropId"
              class="ac-card"
              :class="{ got: c.collected }"
            >
              <div class="ac-card-top">
                <b>{{ cropName(c) }}</b>
                <em>{{ cropCategory(c) }}</em>
              </div>
              <template v-if="c.collected">
                <span class="ac-card-yield">
                  {{ t('farm.ach.bestYield', { kg: c.bestYield }) }}
                </span>
                <span class="ac-card-ratio" :class="ratioClass(c.bestRatio)">
                  {{ t('farm.ach.ratio', { r: c.bestRatio }) }}
                </span>
              </template>
              <span v-else class="ac-card-lock">{{ t('farm.ach.notCollected') }}</span>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getFarmAchievements } from '../../api/farm'
import { useLocaleStore } from '../../store/locale'
import { CROP_EN } from '../../utils/farmLabels'

const emit = defineEmits(['close'])
const { t } = useI18n()
const localeStore = useLocaleStore()

const loading = ref(true)
const error = ref('')
const summary = reactive({})
const achievements = ref([])
const album = ref([])

/**
 * 🔴 成就类别 → 分组顺序。
 *
 * 后端只给 `category` 字符串（枚举名），顺序与展示名由前端定 ——
 * 这样加一个类别不用改后端，也不用改 i18n 的语言包结构。
 */
const CATEGORY_META = [
  { code: 'BEGINNER', labelKey: 'farm.ach.catBeginner' },
  { code: 'SKILL', labelKey: 'farm.ach.catSkill' },
  { code: 'PERSISTENCE', labelKey: 'farm.ach.catPersistence' },
  { code: 'COLLECTION', labelKey: 'farm.ach.catCollection' },
  { code: 'ECONOMY', labelKey: 'farm.ach.catEconomy' }
]

const categories = computed(() =>
  CATEGORY_META.map((c) => {
    const items = achievements.value.filter((a) => a.category === c.code)
    return {
      code: c.code,
      labelKey: c.labelKey,
      items,
      total: items.length,
      done: items.filter((a) => a.unlocked).length
    }
  }).filter((c) => c.total > 0)
)

/**
 * 进度文案。
 *
 * 🔴 单位不同，格式必须分开：
 * - `flag` 是**开关型**（语义是「有没有过」），只显示达成/未达成，不显示进度条分母；
 * - `ratio` 是**比值**（0.60 = 实收达到参照产量 60%），要显示成百分比；
 * - `cny` 是金额；
 * - 其余是计数。
 */
function progressText(a) {
  const p = a.progress ?? 0
  if (a.unit === 'flag') {
    return a.unlocked ? t('farm.ach.done') : t('farm.ach.progFlag')
  }
  if (a.unit === 'ratio') {
    return t('farm.ach.progRatio', { p: Math.round(p * 100), target: Math.round(a.target * 100) })
  }
  if (a.unit === 'cny') {
    return t('farm.ach.progCny', { p: Math.round(p), target: Math.round(a.target) })
  }
  return t('farm.ach.progCount', { p: Math.round(p), target: Math.round(a.target) })
}

/**
 * 作物显示名。
 *
 * 🔴 后端 {@code album[].name} 与 {@code album[].category} 都是中文
 * （{@code CropCatalog.shortName()} / 原始分类串），英文界面下会露中文 ——
 * 所以前端按 cropId 自己映射。
 * 映射缺项时退回后端名字（至少不是空白）。
 *
 * 🔴 2026-10-27：{@code CROP_EN} 已收进 {@code utils/farmLabels.js}
 *    （原先归因面板 / 游戏历史 / 本文件各有一份副本，必然分叉）。
 */

/**
 * 作物分类 → 英文。
 *
 * 🔴 后端分类串是中文（「蔬菜·果菜」这种两级串），直接透出在英文界面里很扎眼。
 * 这里按**二级**（果菜 / 叶菜 / 根菜 / 荚菜 …）映射，够用且不必维护两级全表。
 */
const CATEGORY_EN = {
  '粮食·禾谷': 'Cereals', '粮食·豆科': 'Legumes', '粮食·块茎': 'Tubers',
  '蔬菜·叶菜': 'Leafy greens', '蔬菜·果菜': 'Fruiting veg', '蔬菜·根菜': 'Root veg',
  '蔬菜·荚菜': 'Pod veg',
  '水果·浆果': 'Berries', '水果·瓜类': 'Melons', '水果·藤果': 'Vine fruit',
  '水果·仁果': 'Pome fruit',
  '花卉·切花': 'Cut flowers', '花卉·油料': 'Oil crop', '花卉·球根': 'Bulb flowers',
  '花卉·观赏': 'Ornamental'
}

const isEn = computed(() => String(localeStore.locale || 'zh-CN').toLowerCase().startsWith('en'))

function cropName(c) {
  if (isEn.value && CROP_EN[c.cropId]) return CROP_EN[c.cropId]
  return c.name || c.cropId
}

function cropCategory(c) {
  if (isEn.value && CATEGORY_EN[c.category]) return CATEGORY_EN[c.category]
  return c.category || ''
}

/** 比值配色：≥0.85 优秀 / ≥0.60 达标 / 低于达标。 */
function ratioClass(ratio) {
  const r = Number(ratio) || 0
  if (r >= 0.85) return 'r-great'
  if (r >= 0.60) return 'r-good'
  return 'r-low'
}

onMounted(async () => {
  try {
    const res = await getFarmAchievements()
    // 🔴 拦截器 return 的是后端包装 {code,message,data} —— 必须取 res.data
    const d = res.data || {}
    Object.assign(summary, d.summary || {})
    achievements.value = d.achievements || []
    album.value = d.album || []
  } catch (e) {
    error.value = (e && e.message) || t('common.loadFailed')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.ac-mask {
  position: fixed;
  inset: 0;
  /* 🔴 z-index 必须 > .wm-page 的 60（中国地图浮层）。
     否则地图会整个盖住成就面板 —— 与「地图浮层盖住仓库/商店面板」同型缺陷。
     这里取 61，同时调用方仍会在打开前关掉地图（双保险）。 */
  z-index: 61;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 22px;
  background: rgba(8, 14, 10, 0.62);
  backdrop-filter: blur(3px);
}
.ac-panel {
  display: flex;
  flex-direction: column;
  width: min(1020px, 100%);
  max-height: 100%;
  border-radius: 14px;
  background: #fbfdf9;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
  overflow: hidden;
}

/* ---------- 头部 ---------- */
.ac-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid #e6ece4;
  background: linear-gradient(180deg, #f6faf3, #fbfdf9);
}
.ac-title h2 { margin: 0 0 4px; font-size: 19px; font-weight: 700; color: #16241a; }
.ac-title p { margin: 0; font-size: 12.5px; color: #6b7a6d; }
.ac-actions { display: flex; align-items: center; gap: 10px; flex: none; }
.ac-chip {
  font-size: 12px; color: #6b7a6d;
  background: #eef2ec; border-radius: 10px; padding: 3px 9px;
}
.ac-chip.streak { color: #2f7d32; background: #e2f2df; font-weight: 600; }
.ac-close {
  width: 28px; height: 28px; border: none; border-radius: 8px;
  background: #eef2ec; color: #5a6a5b; font-size: 18px; line-height: 1; cursor: pointer;
}
.ac-close:hover { background: #e2e8df; }

/* ---------- 概览 ---------- */
.ac-overview {
  display: flex;
  align-items: center;
  gap: 22px;
  padding: 14px 20px;
  border-bottom: 1px solid #eef2ec;
  background: #fbfdf9;
}
.ac-ring { position: relative; width: 72px; height: 72px; flex: none; }
.ring-svg { width: 72px; height: 72px; transform: rotate(-90deg); }
.ring-bg { fill: none; stroke: #e8eee6; stroke-width: 7; }
.ring-fg {
  fill: none; stroke: #3f8f43; stroke-width: 7; stroke-linecap: round;
  transition: stroke-dasharray 0.5s ease;
}
.ring-txt {
  position: absolute; inset: 0;
  display: flex; align-items: center; justify-content: center;
  font-size: 17px; font-weight: 700; color: #24382a;
}
.ring-txt i { font-style: normal; font-size: 11px; margin-left: 1px; }
.ac-stats {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
  flex: 1;
  min-width: 0;
}
.ac-stat {
  display: flex; flex-direction: column; gap: 2px;
  padding: 8px 10px;
  background: #f4f8f1; border: 1px solid #e3eae0; border-radius: 9px;
  min-width: 0;
}
.ac-stat b {
  font-size: 16px; color: #24382a;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ac-stat span { font-size: 11px; color: #8b9a8c; }

.ac-loading { padding: 40px; text-align: center; color: #8b9a8c; font-size: 13px; }
.ac-error { padding: 40px; text-align: center; color: #b42318; font-size: 13px; }
.ac-body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 20px 20px; }

/* ---------- 分区 ---------- */
.ac-sec { margin-bottom: 22px; }
.ac-sec:last-child { margin-bottom: 0; }
.ac-sec-head {
  display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap;
  margin-bottom: 9px;
}
.ac-sec-head b { font-size: 13.5px; color: #2a3b2d; }
.ac-sec-head span { font-size: 11.5px; color: #97a598; }

/* ---------- 成就卡 ---------- */
.ac-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(240px, 1fr));
  gap: 10px;
}
.ac-item {
  padding: 11px 13px;
  background: #f7faf5;
  border: 1px solid #e6ece4;
  border-radius: 10px;
}
.ac-item.done { background: #f0f8ee; border-color: #cfe6c9; }
.ac-item-head { display: flex; align-items: center; gap: 7px; }
.ac-mark { font-size: 13px; color: #a9b6a5; }
.ac-item.done .ac-mark { color: #3f8f43; font-weight: 700; }
.ac-name {
  flex: 1; min-width: 0;
  font-size: 13px; color: #2a3b2d;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.ac-badge {
  flex: none;
  font-size: 10px; color: #2f7d32;
  background: #dcf0d8; border-radius: 7px; padding: 1px 6px;
}
.ac-desc {
  margin: 5px 0 8px;
  font-size: 11.5px; color: #7d8c7e; line-height: 1.55;
}
.ac-bar {
  height: 5px; border-radius: 3px;
  background: #e8eee6; overflow: hidden;
}
.ac-bar i {
  display: block; height: 100%;
  background: linear-gradient(90deg, #6bb26f, #3f8f43);
  transition: width 0.4s ease;
}
.ac-prog {
  display: block; margin-top: 5px;
  font-size: 11px; color: #8b9a8c;
}

/* ---------- 图鉴 ---------- */
.ac-album {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(168px, 1fr));
  gap: 9px;
}
.ac-card {
  padding: 9px 11px;
  background: #f7faf5;
  border: 1px solid #e6ece4;
  border-radius: 9px;
  min-width: 0;
}
.ac-card.got { background: #f0f8ee; border-color: #cfe6c9; }
.ac-card-top {
  display: flex; align-items: baseline; gap: 6px;
  margin-bottom: 4px;
}
/* 作物名允许折成两行 ——「Chinese Cabbage」「Chrysanthemum」在 168px 卡里单行必然截断，
   而作物名是图鉴的主体信息，截断了就没意义了（分类标签才是次要信息，可省略）。 */
.ac-card-top b {
  flex: 1; min-width: 0;
  font-size: 12.5px; color: #2a3b2d; line-height: 1.35;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
/* 分类标签放行末 —— 作物名是主体，分类是次要信息，
   分类用 nowrap + 省略号，让作物名拿到尽可能多的宽度 */
.ac-card-top em {
  flex: 0 1 auto; min-width: 0;
  font-style: normal; font-size: 10px; color: #9aa89a;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  max-width: 46%;
}
.ac-card-yield { display: block; font-size: 11px; color: #6b7a6d; }
.ac-card-ratio { display: block; font-size: 11px; font-weight: 600; }
.ac-card-ratio.r-great { color: #2f7d32; }
.ac-card-ratio.r-good { color: #b3761f; }
.ac-card-ratio.r-low { color: #97a598; }
.ac-card-lock { display: block; font-size: 11px; color: #a9b6a5; }

/* ---------- 窄屏 ---------- */
@media (max-width: 768px) {
  .ac-mask { padding: 0; align-items: stretch; }
  .ac-panel { width: 100%; max-height: 100vh; border-radius: 0; }
  .ac-overview { flex-direction: column; align-items: stretch; gap: 12px; }
  .ac-ring { align-self: center; }
  .ac-stats { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .ac-grid { grid-template-columns: minmax(0, 1fr); }
  .ac-album { grid-template-columns: repeat(auto-fill, minmax(146px, 1fr)); }
  .ac-head { padding: 12px 14px 10px; }
  .ac-body { padding: 12px 14px 18px; }
}
</style>
