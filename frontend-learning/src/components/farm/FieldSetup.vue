<template>
  <div class="fs-page">
    <!-- ================= 左：选地 ================= -->
    <section class="fs-left">
      <header class="fs-head">
        <div class="fs-title">
          <h2>{{ $t('farm.setup.title') }}</h2>
          <p class="muted">{{ $t('farm.setup.subtitle') }}</p>
        </div>
        <div class="fs-steps">
          <span class="step on">1 {{ $t('farm.setup.stepCrop') }}</span>
          <span class="step on">2 {{ $t('farm.setup.stepZone') }}</span>
          <span class="step on">3 {{ $t('farm.setup.stepSow') }}</span>
          <span class="step">4 {{ $t('farm.setup.stepConfirm') }}</span>
        </div>
      </header>

      <div v-if="loading" class="fs-loading">
        <el-skeleton :rows="8" animated />
      </div>

      <div v-else class="crop-row">
        <div class="crop-head">
          <h3>{{ $t('farm.setup.stepCrop') }}</h3>
          <span class="muted">{{ cropHint }}</span>
        </div>
        <div v-for="g in cropGroups" :key="g.key" class="crop-group">
          <div class="cg-title">
            <span>{{ g.label }}</span>
            <i>{{ g.items.length }}</i>
          </div>
          <div class="crop-grid">
            <button
              v-for="c in g.items"
              :key="c.id"
              class="crop-card"
              :class="{ active: c.id === cropId }"
              @click="pickCrop(c)"
            >
              <span class="cc-icon">{{ cropIcon(c.visual) }}</span>
              <span class="cc-main">
                <b>{{ c.shortName }}</b>
                <i>{{ c.category }}</i>
              </span>
              <span class="cc-meta mono">{{ $t('farm.setup.nNeedShort', { n: c.nNeed }) }}</span>
            </button>
          </div>
        </div>
      </div>

      <div v-if="!loading" class="zone-grid">
        <button
          v-for="z in zones"
          :key="z.id"
          class="zone-card"
          :class="[`lv-${z.level}`, { active: z.id === zoneId }]"
          @click="pickZone(z)"
        >
          <div class="zc-top">
            <div class="zc-name">
              <b>{{ z.name }}</b>
              <span class="zc-site">{{ z.site }}</span>
            </div>
            <span class="lv-badge" :class="`b-${z.level}`">{{ z.levelName }}</span>
          </div>

          <div class="zc-rate">
            <div class="rate-row">
              <span class="rate-lab">{{ $t('farm.setup.passRate') }}</span>
              <span class="rate-val mono">{{ Math.round(z.pPass * 100) }}%</span>
            </div>
            <div class="rate-bar"><i :style="{ width: Math.max(3, z.pPass * 100) + '%' }"></i></div>
          </div>

          <div class="zc-kv">
            <div>
              <dt>{{ $t('farm.setup.medianYield') }}</dt>
              <dd class="mono">{{ z.yieldMedian.toFixed(0) }} <i>kg/{{ $t('farm.mu') }}</i></dd>
            </div>
            <div>
              <dt>{{ $t('farm.setup.annualAat') }}</dt>
              <dd class="mono">{{ Math.round(z.aatP50) }} <i>℃·d</i></dd>
            </div>
            <div>
              <dt>{{ $t('farm.setup.frostFree') }}</dt>
              <dd class="mono">{{ z.frostFreeP50 }} <i>{{ $t('farm.day') }}</i></dd>
            </div>
          </div>

          <p class="zc-hint">{{ z.seasonHint }}</p>

          <div class="zc-foot">
            <span class="zc-factor" :class="{ strong: z.level >= 2 }">
              {{ $t('farm.setup.mainPressure') }}：{{ z.primaryFactorName }}
            </span>
            <span class="zc-sow mono">{{ z.sowDate.slice(5) }} {{ $t('farm.setup.sow') }}</span>
          </div>
        </button>
      </div>

      <p class="fs-note muted">{{ $t('farm.setup.zoneNote') }}</p>
    </section>

    <!-- ================= 右：报告 ================= -->
    <aside class="fs-right">
      <template v-if="report">
        <div class="rp-head">
          <div class="rp-level">
            <span class="rp-badge" :class="`b-${report.level}`">L{{ report.level }}</span>
            <div>
              <h3>{{ report.levelName }}</h3>
              <p class="muted">{{ report.zoneName }} · {{ report.site }}</p>
            </div>
          </div>
          <p class="rp-summary">{{ report.summary }}</p>
        </div>

        <div class="rp-scroll">
          <!-- 播期 -->
          <section class="rp-card">
            <div class="rp-card-h">
              <h4>{{ $t('farm.setup.sowDate') }}</h4>
              <span class="muted">{{ $t('farm.setup.sowHint') }}</span>
            </div>
            <div class="sow-row">
              <el-date-picker
                v-model="sowDate"
                type="date"
                value-format="YYYY-MM-DD"
                :clearable="false"
                size="default"
                style="width: 148px"
              />
              <div class="sow-presets">
                <el-button size="small" @click="shiftSow(-20)">−20</el-button>
                <el-button size="small" @click="shiftSow(-10)">−10</el-button>
                <el-button size="small" @click="shiftSow(10)">+10</el-button>
                <el-button size="small" @click="shiftSow(20)">+20</el-button>
              </div>
            </div>
            <p v-if="sowWarn" class="sow-warn">{{ sowWarn }}</p>
          </section>

          <!-- 关键数字 -->
          <section class="rp-metrics">
            <div class="m">
              <span class="m-lab">{{ $t('farm.setup.passRate') }}</span>
              <span class="m-val mono">{{ Math.round(report.pPass * 100) }}%</span>
              <span class="m-sub">{{ report.samples }} {{ $t('farm.year') }}</span>
            </div>
            <div class="m">
              <span class="m-lab">{{ $t('farm.setup.medianYield') }}</span>
              <span class="m-val mono">{{ report.yieldMedian.toFixed(0) }}</span>
              <span class="m-sub">kg/{{ $t('farm.mu') }}</span>
            </div>
            <div class="m">
              <span class="m-lab">{{ $t('farm.setup.worstYear') }}</span>
              <span class="m-val mono">{{ report.yieldWorstPct.toFixed(0) }}%</span>
              <span class="m-sub">{{ $t('farm.setup.vsRef') }}</span>
            </div>
            <div class="m">
              <span class="m-lab">{{ $t('farm.setup.sowCost') }}</span>
              <span class="m-val mono">{{ report.sowCost.toFixed(0) }}</span>
              <span class="m-sub">{{ $t('farm.yuan') }}/{{ $t('farm.mu') }}</span>
            </div>
          </section>

          <!-- 限制因子 -->
          <section class="rp-card">
            <div class="rp-card-h">
              <h4>{{ $t('farm.setup.factors') }}</h4>
              <span class="muted">{{ $t('farm.setup.factorHint') }}</span>
            </div>
            <ul class="fx-list">
              <li v-for="f in activeFactors" :key="f.code" :class="{ primary: f.code === report.primaryFactor }">
                <span class="fx-code">{{ f.code }}</span>
                <span class="fx-name">{{ f.name }}</span>
                <div class="fx-bar"><i :style="{ width: (f.level / 5) * 100 + '%' }"></i></div>
                <span class="fx-lv mono">{{ f.level }}/5</span>
              </li>
            </ul>
            <p v-if="!activeFactors.length" class="fx-none">{{ $t('farm.setup.noFactor') }}</p>
          </section>

          <!-- 缺口说明 -->
          <section v-if="report.gaps.length" class="rp-card">
            <div class="rp-card-h">
              <h4>{{ $t('farm.setup.gaps') }}</h4>
            </div>
            <ul class="gap-list">
              <li v-for="(g, i) in report.gaps" :key="i">
                <span class="gap-tag">{{ g.factor }}</span>
                <p>{{ gapText(g) }}</p>
                <span class="gap-num mono">
                  {{ fmt(g.actual) }} / {{ fmt(g.required) }} {{ g.unit }}
                </span>
              </li>
            </ul>
          </section>

          <!-- 失败模式 -->
          <section v-if="report.failureMode" class="rp-card rp-fail">
            <div class="rp-card-h">
              <h4>⚠️ {{ $t('farm.setup.risk') }}</h4>
              <span class="fail-name">{{ report.failureName }}</span>
            </div>
            <p class="fail-text">{{ report.failureText }}</p>
          </section>

          <!-- 建田 -->
          <section class="rp-card rp-confirm">
            <div class="rp-card-h">
              <h4>{{ $t('farm.setup.stepConfirm') }}</h4>
            </div>
            <el-input v-model="fieldName" :placeholder="defaultName" maxlength="16" show-word-limit />
            <el-button type="primary" class="confirm-btn" :disabled="busy" @click="confirm">
              {{ $t('farm.setup.confirm') }}
            </el-button>
            <p class="rp-foot muted">{{ report.dataSource }}</p>
          </section>
        </div>
      </template>
      <div v-else class="fs-loading"><el-skeleton :rows="10" animated /></div>
    </aside>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listFarmCrops, listFarmZones, getFarmSuitability, createFarmField } from '../../api/farm'
import { useRoute } from 'vue-router'

const props = defineProps({
  initialZone: { type: String, default: '' },
  initialCrop: { type: String, default: '' },
  hasField: { type: Boolean, default: false },
  /** -1 表示新建一块地（后端自动分配槽位）；≥0 表示在该槽位上重建 */
  slot: { type: Number, default: -1 },
  /** 归属农场；给了它就不用再选农业区（区县决定气候） */
  farmId: { type: Number, default: null }
})
const emit = defineEmits(['created'])
const { t, locale } = useI18n()
const route = useRoute()

const crops = ref([])
const zones = ref([])
const cropId = ref(props.initialCrop || 'rice_mid')
const zoneId = ref('jiangnan')
const sowDate = ref('')
const report = ref(null)
const fieldName = ref('')
const loading = ref(true)
const busy = ref(false)
const sowWarn = ref('')

const defaultName = computed(() => {
  const z = zones.value.find(x => x.id === zoneId.value)
  const c = crops.value.find(x => x.id === cropId.value)
  return z ? `${z.name}${c ? c.shortName : ''}田` : ''
})

/**
 * 作物按类别分组。后端 category 形如「蔬菜·果菜」，取中点前的部分做分组键，
 * 保持后端返回顺序（粮食 → 蔬菜 → 水果 → 花卉），不在前端再排一遍。
 */
const CROP_GROUPS = ['粮食', '蔬菜', '水果', '花卉']
const cropGroups = computed(() => {
  const out = []
  for (const key of CROP_GROUPS) {
    const items = crops.value.filter((c) => String(c.category || '').startsWith(key))
    if (items.length) out.push({ key, label: key, items })
  }
  // 兜底：category 为空或不在预期前缀里的作物归到「其它」，避免整块不显示
  const known = out.flatMap((g) => g.items)
  const rest = crops.value.filter((c) => !known.includes(c))
  if (rest.length) out.push({ key: 'other', label: '其它', items: rest })
  return out
})

const activeFactors = computed(() =>
  (report.value?.factors || []).filter(f => f.level > 0).sort((a, b) => b.level - a.level)
)

onMounted(async () => {
  try {
    const cres = await listFarmCrops()
    crops.value = cres?.data || []
    await loadZones()
  } catch (e) {
    ElMessage.error(t('farm.setup.loadFail'))
  } finally {
    loading.value = false
  }
})

/** 重新拉该作物的四区评级，并把播期重置为该区推荐值 */
async function loadZones() {
  const res = await listFarmZones(cropId.value)
  zones.value = res?.data || []
  const cur = zones.value.find(z => z.id === props.initialZone)
    || zones.value.find(z => z.id === zoneId.value)
    || zones.value[0]
  if (!cur) return
  zoneId.value = cur.id
  sowDate.value = cur.sowDate
}

const cropHint = computed(() => {
  const c = crops.value.find(x => x.id === cropId.value)
  return c ? c.desc : ''
})

function cropIcon(visual) {
  return ({
    rice: '🌾', wheat: '🌾', corn: '🌽', soybean: '🫘',
    leafy: '🥬', vine: '🥒', bush: '🍅', flower: '🌼'
  })[visual] || '🌱'
}

async function pickCrop(c) {
  if (c.id === cropId.value) return
  cropId.value = c.id
  fieldName.value = ''
  await loadZones()
}

let timer = null
watch([zoneId, sowDate], () => {
  if (!zoneId.value || !sowDate.value) return
  sowWarn.value = ''
  clearTimeout(timer)
  timer = setTimeout(loadReport, 300)
}, { immediate: false })

async function loadReport() {
  try {
    const res = await getFarmSuitability(zoneId.value, sowDate.value, cropId.value)
    report.value = res?.data || null
    checkWindow()
  } catch (e) {
    ElMessage.error(t('farm.setup.loadFail'))
  }
}

/** 各作物的适宜播期窗口（只提示，不阻止——S23 §2.1：适区不是许可） */
const SOW_WINDOW = {
  rice_mid: ['04-20', '06-20'],
  wheat_winter: ['09-20', '11-10'],
  corn_spring: ['03-20', '06-25'],
  soybean: ['05-20', '06-25'],
  // 蔬菜
  cabbage: ['07-15', '09-15'],
  tomato: ['02-15', '04-25'],
  cucumber: ['02-25', '04-30'],
  chili: ['03-01', '05-05'],
  // 水果
  strawberry: ['08-15', '10-15'],
  watermelon: ['03-05', '05-10'],
  melon: ['03-10', '05-15'],
  // 花卉
  chrysanthemum: ['05-25', '07-15'],
  sunflower: ['03-15', '05-15'],
  tulip: ['09-20', '11-30']
}

function checkWindow() {
  const md = sowDate.value.slice(5)
  const w = SOW_WINDOW[cropId.value] || SOW_WINDOW.rice_mid
  const inWindow = md >= w[0] && md <= w[1]
  const zone = zoneId.value
  if (cropId.value === 'rice_mid' && zone === 'dongbei' && (md < '04-25' || md > '05-25')) {
    sowWarn.value = t('farm.setup.warnDongbei')
  } else if (!inWindow) {
    sowWarn.value = t('farm.setup.warnWindowCrop', { from: w[0], to: w[1] })
  }
}

function pickZone(z) {
  zoneId.value = z.id
  sowDate.value = z.sowDate
  fieldName.value = ''
}

function shiftSow(days) {
  const d = new Date(sowDate.value + 'T00:00:00')
  d.setDate(d.getDate() + days)
  sowDate.value = d.toISOString().slice(0, 10)
}

function fmt(v) {
  return Math.abs(v) >= 100 ? Math.round(v) : Math.round(v * 10) / 10
}

/** 缺口文案后端只出中文；英文环境退化为「数值 + 单位」 */
function gapText(g) {
  if (locale.value === 'en') {
    return `${g.metric}: ${fmt(g.actual)} / ${fmt(g.required)} ${g.unit}`
  }
  return g.text
}

async function confirm() {
  if (props.hasField) {
    try {
      await ElMessageBox.confirm(
        t('farm.setup.replaceWarn'),
        t('farm.setup.replaceTitle'),
        { type: 'warning', confirmButtonText: t('farm.setup.confirm'), cancelButtonText: t('common.cancel') }
      )
    } catch (e) {
      return
    }
  }
  busy.value = true
  try {
    const r = await createFarmField({
      zoneId: zoneId.value,
      cropId: cropId.value,
      sowDate: sowDate.value,
      fieldName: fieldName.value || defaultName.value,
      ...(props.farmId ? { farmId: props.farmId } : {})
    }, props.slot)
    ElMessage.success(t('farm.setup.created', { name: r?.data?.fieldName || defaultName.value }))
    emit('created')
  } catch (e) {
    ElMessage.error(e?.message || t('farm.setup.createFail'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.crop-group { margin-bottom: 14px; }
.cg-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 8px;
  font-size: 11.5px;
  letter-spacing: 1px;
  color: var(--c-text-3, #8b9784);
}
.cg-title span { font-weight: 600; }
.cg-title i {
  font-style: normal;
  font-size: 10px;
  padding: 0 6px;
  border-radius: 8px;
  background: var(--c-fill-2, #eef2ea);
  color: var(--c-text-3, #8b9784);
}

.fs-page {
  display: grid;
  grid-template-columns: minmax(0, 1fr) clamp(420px, 32vw, 500px);
  height: 100%;
  min-height: 0;
  background: #f6f8fb;
  overflow: hidden;
}

/* ---------------- 左 ---------------- */
.fs-left {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 24px 28px 18px;
  overflow-y: auto;
}
.fs-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.fs-title h2 {
  margin: 0 0 4px;
  font-size: 21px;
  font-weight: 700;
  color: #0f172a;
  letter-spacing: 0.2px;
}
.fs-title p { margin: 0; font-size: 12.5px; }
.fs-steps { display: flex; gap: 6px; flex: none; padding-top: 2px; }
.fs-steps .step {
  font-size: 11px;
  color: #94a3b8;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 999px;
  padding: 3px 10px;
  white-space: nowrap;
}
.fs-steps .step.on { color: #1d6fb8; border-color: #cfe4f6; background: #f2f9ff; }

.zone-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
  margin-top: 14px;
}
.zone-card {
  display: block;
  text-align: left;
  font: inherit;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 16px;
  padding: 16px 16px 12px;
  transition: border-color 0.16s, box-shadow 0.16s, transform 0.16s;
  min-width: 0;
}
.zone-card:hover { border-color: #c9d8e8; box-shadow: 0 8px 24px rgba(15, 23, 42, 0.07); transform: translateY(-1px); }
.zone-card.active {
  border-color: #2f7fd0;
  box-shadow: 0 0 0 3px rgba(47, 127, 208, 0.12), 0 10px 26px rgba(15, 23, 42, 0.08);
}

.zc-top { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.zc-name b { font-size: 16px; font-weight: 700; color: #0f172a; }
.zc-site { display: block; font-size: 11px; color: #94a3b8; margin-top: 1px; }
.lv-badge {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  border-radius: 6px;
  padding: 3px 8px;
  white-space: nowrap;
  background: #eef7e7;
  color: #2f7a2a;
}
.lv-badge.b-1 { background: #eef7e7; color: #3a8a33; }
.lv-badge.b-2 { background: #fff7e6; color: #b45309; }
.lv-badge.b-3 { background: #fff1e6; color: #c2410c; }
.lv-badge.b-4 { background: #fdeeea; color: #b91c1c; }
.lv-badge.b-5 { background: #f3eefe; color: #7c3aed; }

.zc-rate { margin: 12px 0 10px; }
.rate-row { display: flex; align-items: baseline; justify-content: space-between; }
.rate-lab { font-size: 11px; color: #8a94a6; }
.rate-val { font-size: 15px; font-weight: 650; color: #0f172a; }
.rate-bar { height: 6px; border-radius: 999px; background: #eef1f5; margin-top: 5px; overflow: hidden; }
.rate-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #6fbf5a, #3f8f2e);
  transition: width 0.35s;
}
.lv-2 .rate-bar i { background: linear-gradient(90deg, #f0b45a, #d98b2b); }
.lv-3 .rate-bar i, .lv-4 .rate-bar i { background: linear-gradient(90deg, #ef8f6a, #d9534f); }
.lv-5 .rate-bar i { background: linear-gradient(90deg, #b0a0d8, #7c3aed); }

.zc-kv { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.zc-kv dt { font-size: 10px; color: #98a1b0; white-space: nowrap; }
.zc-kv dd { margin: 1px 0 0; font-size: 13px; font-weight: 600; color: #1f2937; }
.zc-kv dd i { font-style: normal; font-size: 9.5px; font-weight: 400; color: #a3abb9; }

.zc-hint {
  margin: 10px 0 0;
  font-size: 10.5px;
  line-height: 1.55;
  color: #8a94a6;
  min-height: 32px;
}
.zc-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed #eef1f6;
  font-size: 10.5px;
}
.zc-factor { color: #8a94a6; }
.zc-factor.strong { color: #c2410c; font-weight: 600; }
.zc-sow { color: #a3abb9; }
.fs-note { margin: 14px 0 0; font-size: 11px; line-height: 1.6; }

.fs-loading { padding: 24px; }

/* 作物选择 */
.crop-row { margin-top: 16px; }
.crop-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 8px; }
.crop-head h3 { margin: 0; font-size: 13px; font-weight: 650; color: #0f172a; }
.crop-head .muted { font-size: 11px; }
.crop-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.crop-card {
  display: flex;
  align-items: center;
  gap: 8px;
  font: inherit;
  text-align: left;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 12px;
  padding: 9px 10px;
  min-width: 0;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.crop-card:hover { border-color: #c9d8e8; }
.crop-card.active {
  border-color: #2f7fd0;
  box-shadow: 0 0 0 3px rgba(47, 127, 208, 0.12);
}
.cc-icon { font-size: 18px; line-height: 1; flex: none; }
.cc-main { display: flex; flex-direction: column; min-width: 0; }
.cc-main b { font-size: 13px; color: #0f172a; white-space: nowrap; }
.cc-main i { font-style: normal; font-size: 9.5px; color: #98a1b0; white-space: nowrap; }
.cc-meta { margin-left: auto; font-size: 9.5px; color: #b0b8c4; white-space: nowrap; }

/* ---------------- 右 ---------------- */
.fs-right {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  background: #fff;
  border-left: 1px solid rgba(15, 23, 42, 0.07);
  overflow: hidden;
}
.rp-head {
  flex: none;
  padding: 18px 20px 14px;
  border-bottom: 1px solid #eef1f6;
  background: linear-gradient(180deg, #fbfdff, #fff);
}
.rp-level { display: flex; align-items: center; gap: 12px; }
.rp-badge {
  flex: none;
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: grid;
  place-items: center;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(140deg, #7acb63, #3f8f2e);
}
.rp-badge.b-1 { background: linear-gradient(140deg, #7acb63, #3f8f2e); }
.rp-badge.b-2 { background: linear-gradient(140deg, #f5c169, #d18a2a); }
.rp-badge.b-3 { background: linear-gradient(140deg, #f0a07a, #d2603c); }
.rp-badge.b-4 { background: linear-gradient(140deg, #ef8a80, #c0392b); }
.rp-badge.b-5 { background: linear-gradient(140deg, #a98fd6, #6d3fc0); }
.rp-level h3 { margin: 0; font-size: 17px; font-weight: 700; color: #0f172a; }
.rp-level p { margin: 1px 0 0; font-size: 11.5px; }
.rp-summary {
  margin: 12px 0 0;
  font-size: 12.5px;
  line-height: 1.75;
  color: #334155;
}

.rp-scroll {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 16px 24px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.rp-card {
  background: #fff;
  border: 1px solid #eef1f6;
  border-radius: 13px;
  padding: 12px 14px;
}
.rp-card-h {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 8px;
}
.rp-card-h h4 { margin: 0; font-size: 13px; font-weight: 650; color: #0f172a; }
.rp-card-h .muted { font-size: 10.5px; }

.sow-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sow-presets { display: flex; gap: 4px; }
.sow-presets .el-button { margin: 0; }
.sow-warn {
  margin: 8px 0 0;
  font-size: 11px;
  line-height: 1.55;
  color: #b45309;
  background: #fff8ed;
  border: 1px solid #f7e3c6;
  border-radius: 8px;
  padding: 6px 9px;
}

.rp-metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 1px;
  background: #eef1f6;
  border: 1px solid #eef1f6;
  border-radius: 13px;
  overflow: hidden;
}
.rp-metrics .m { background: #fff; padding: 9px 8px; display: flex; flex-direction: column; gap: 1px; }
.m-lab { font-size: 10px; color: #98a1b0; white-space: nowrap; }
.m-val { font-size: 15px; font-weight: 650; color: #0f172a; line-height: 1.2; }
.m-sub { font-size: 10px; color: #a3abb9; white-space: nowrap; }

.fx-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 7px; }
.fx-list li { display: flex; align-items: center; gap: 8px; font-size: 11.5px; }
.fx-code {
  flex: none;
  width: 22px;
  font-size: 10px;
  font-weight: 700;
  color: #64748b;
  background: #f1f4f8;
  border-radius: 4px;
  text-align: center;
  padding: 1px 0;
}
.fx-list li.primary .fx-code { background: #fdeeea; color: #c2410c; }
.fx-name { flex: none; width: 74px; color: #334155; }
.fx-list li.primary .fx-name { font-weight: 650; color: #b23b38; }
.fx-bar { flex: 1; height: 5px; border-radius: 999px; background: #eef1f5; overflow: hidden; }
.fx-bar i { display: block; height: 100%; background: linear-gradient(90deg, #f0b45a, #d9534f); }
.fx-lv { flex: none; font-size: 10px; color: #98a1b0; }
.fx-none { margin: 2px 0; font-size: 11.5px; color: #2f7a2a; }

.gap-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 9px; }
.gap-list li { font-size: 11.5px; line-height: 1.6; color: #334155; }
.gap-tag {
  display: inline-block;
  font-size: 9.5px;
  font-weight: 700;
  color: #b45309;
  background: #fff4e6;
  border-radius: 4px;
  padding: 0 5px;
  margin-right: 6px;
}
.gap-list p { display: inline; margin: 0; }
.gap-num { display: block; margin-top: 2px; font-size: 10.5px; color: #a3abb9; }

.rp-fail { background: #fffaf7; border-color: #f7e0d3; }
.fail-name { font-size: 11px; font-weight: 600; color: #c2410c; }
.fail-text { margin: 0; font-size: 11.5px; line-height: 1.7; color: #7c4a3a; }

.rp-confirm { display: flex; flex-direction: column; gap: 9px; }
.confirm-btn { width: 100%; }
.rp-foot { margin: 0; font-size: 10px; line-height: 1.5; }

@media (max-width: 1180px) {
  .fs-page { grid-template-columns: minmax(0, 1fr) 420px; }
  .fs-left { padding: 18px; }
  .rp-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>
