<template>
  <el-drawer
    :model-value="modelValue"
    :title="$t('farm.input.title')"
    direction="rtl"
    size="540px"
    class="input-drawer"
    @update:model-value="$emit('update:modelValue', $event)"
  >
    <div class="in-wrap">
      <p class="in-intro">{{ $t('farm.input.intro') }}</p>

      <div class="in-tabs">
        <button
          v-for="t in tabs"
          :key="t.key"
          class="in-tab"
          :class="{ on: tab === t.key }"
          @click="tab = t.key"
        >
          {{ t.label }}<i v-if="t.count">{{ t.count }}</i>
        </button>
      </div>

      <div class="in-list">
        <article v-for="it in visible" :key="it.id" class="in-item" :class="{ off: !it.inWindow }">
          <header class="ii-head">
            <div class="ii-name">
              <b>{{ it.name }}</b>
              <span class="ii-cat">{{ categoryLabel(it) }}</span>
            </div>
            <span class="ii-power" :class="powerClass(it)">
              {{ $t('farm.input.power') }} {{ Math.round(it.power * 100) }}%
            </span>
          </header>

          <p class="ii-target">
            {{ it.kind === 'FERT' ? $t('farm.input.element') : $t('farm.input.target') }}：
            <b>{{ it.kind === 'FERT' ? elementLabel(it) : it.targetNames.join(' / ') }}</b>
          </p>

          <div class="ii-tags">
            <span class="tag" :class="it.inWindow ? 'ok' : 'warn'">
              {{ it.inWindow ? $t('farm.input.inWindow') : $t('farm.input.outWindow') }}
            </span>
            <span class="tag mono">DVS {{ it.doseWindow }}</span>
            <span v-if="it.kind === 'PEST'" class="tag">{{ $t('farm.input.phi', { d: it.phiDays }) }}</span>
            <span v-if="it.kind === 'PEST'" class="tag">{{ $t('farm.input.protect', { d: it.protectDays }) }}</span>
            <!-- 除草剂没有 PHI（草不进人），但有"见效延迟"和"药效期"（需求 O） -->
            <span v-if="it.kind === 'HERB'" class="tag">
              {{ $t('farm.input.herbDelay', { d: HERB_DELAY_DAYS }) }}
            </span>
            <span v-if="it.kind === 'HERB'" class="tag">
              {{ $t('farm.input.protect', { d: it.protectDays }) }}
            </span>
            <span v-if="it.usedTimes" class="tag used">{{ $t('farm.input.used', { n: it.usedTimes }) }}</span>
            <span v-if="it.resistanceRisk < 0.999" class="tag risk">
              {{ $t('farm.input.resistance', { p: Math.round((1 - it.resistanceRisk) * 100) }) }}
            </span>
            <span v-if="it.phiRisk" class="tag danger">{{ $t('farm.input.phiRisk') }}</span>
          </div>

          <p v-if="it.kind === 'HERB'" class="ii-note herb">
            {{ $t('farm.input.herbDelayNote', { d: HERB_DELAY_DAYS }) }}
          </p>
          <p class="ii-note">{{ it.note }}</p>

          <div class="ii-apply">
            <el-input-number
              v-model="doses[it.id]"
              :min="0"
              :max="it.doseMax * 2"
              :step="stepOf(it)"
              :precision="it.unit === 'kg' ? 1 : 0"
              size="small"
              controls-position="right"
              class="dose-input"
            />
            <span class="unit">{{ it.unit }}/{{ $t('farm.mu') }}</span>
            <span class="cost mono">≈ {{ costOf(it).toFixed(1) }} {{ $t('farm.yuan') }}</span>
            <el-button
              type="primary"
              size="small"
              :disabled="busy || it.phiRisk"
              @click="apply(it)"
            >
              {{ $t('farm.input.apply') }}
            </el-button>
          </div>
          <p class="ii-rec">
            {{ $t('farm.input.recommend', { d: it.doseOpt, u: it.unit, min: it.doseMin, max: it.doseMax }) }}
          </p>
        </article>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyFarmInput } from '../../api/farm'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  inputs: { type: Array, default: () => [] },
  busy: { type: Boolean, default: false },
  /** 作用到哪块地 */
  slot: { type: Number, default: 0 }
})
const emit = defineEmits(['update:modelValue', 'applied'])
const { t } = useI18n()

const tab = ref('ALL')
const doses = ref({})
const localBusy = ref(false)

/**
 * 除草剂见效延迟（天）。
 * ⚠️ 必须与后端 `WeedEngine.CHEM_DELAY` 保持一致 —— 后端延迟 2 天才降压，
 * 前端如果写 0 天，玩家会以为喷完马上见效，白等两天才发现草还在。
 */
const HERB_DELAY_DAYS = 2

const tabs = computed(() => [
  { key: 'ALL', label: t('farm.input.tabAll'), count: props.inputs.length },
  { key: 'FERT', label: t('farm.input.tabFert'), count: props.inputs.filter(i => i.kind === 'FERT').length },
  { key: 'PEST', label: t('farm.input.tabPest'), count: props.inputs.filter(i => i.kind === 'PEST').length },
  // 除草剂独立成一类（需求 O）：它跟农药不是一回事，不能混在"农药"里让玩家误点
  { key: 'HERB', label: t('farm.input.tabHerb'), count: props.inputs.filter(i => i.kind === 'HERB').length }
])

const visible = computed(() =>
  props.inputs
    .filter(i => tab.value === 'ALL' || i.kind === tab.value)
    .map(i => ({ ...i, doseWindow: windowText(i) }))
)

// 默认用量 = 推荐量
watch(
  () => props.inputs,
  (list) => {
    for (const it of list) {
      if (doses.value[it.id] === undefined) doses.value[it.id] = it.doseOpt
    }
  },
  { immediate: true, deep: false }
)

function windowText(it) {
  return `${Number(it.dvsLo).toFixed(2)}–${Number(it.dvsHi).toFixed(2)}`
}

function stepOf(it) {
  if (it.unit === 'kg') return it.doseOpt >= 10 ? 1 : 0.1
  return it.doseOpt >= 40 ? 5 : 2
}

function costOf(it) {
  return (doses.value[it.id] || 0) * it.price
}

function powerClass(it) {
  if (!it.inWindow) return 'low'
  if (it.power >= 0.9) return 'high'
  return 'mid'
}

function categoryLabel(it) {
  return t('farm.input.cat.' + it.category)
}

function elementLabel(it) {
  return `${it.element || ''} ${Math.round(it.nContent * 100)}%`
}

async function apply(it) {
  const dose = doses.value[it.id]
  if (!dose || dose <= 0) {
    ElMessage.warning(t('farm.input.doseRequired'))
    return
  }
  if (!it.inWindow) {
    try {
      await ElMessageBox.confirm(t('farm.input.confirmOut', { name: it.name }), t('farm.input.apply'), {
        type: 'warning',
        confirmButtonText: t('farm.input.apply'),
        cancelButtonText: t('common.cancel')
      })
    } catch (e) {
      return
    }
  }
  localBusy.value = true
  try {
    const res = await applyFarmInput(props.slot, it.id, dose)
    const d = res?.data || {}
    const warn = (d.warnings || []).join('；')
    if (warn) {
      ElMessage.warning({ message: warn, duration: 5200, showClose: true })
    } else {
      // 🔴 2026-10-27 资金流口径：钱在**商店买货时**就付掉了，施用只扣库存。
      //
      // 所以这里**不能**说「实付 X 元」—— 施用那一刻钱包根本没动，
      // 说了就是骗玩家（「我明明在商店已经付过了，怎么又扣一次」）。
      //
      // d.cost 的语义是「这批货的**成本摊销**」（用量 × 单价），
      // 用来让玩家理解「这一喷折合多少钱的肥/药」，属于农艺信息，不是账单。
      ElMessage.success({
        message: t('farm.input.appliedPaid', {
          name: it.name,
          cost: d.cost,
          balance: d.balance
        }),
        duration: 3200
      })
    }
    emit('applied')
  } catch (e) {
    ElMessage.error(e?.message || t('farm.input.fail'))
  } finally {
    localBusy.value = false
  }
}
</script>

<style scoped>
.in-wrap { display: flex; flex-direction: column; gap: 10px; height: 100%; }
.in-intro {
  margin: 0;
  font-size: 12px;
  line-height: 1.65;
  color: #64748b;
  background: #f6f9fd;
  border: 1px solid #e6eefa;
  border-radius: 10px;
  padding: 9px 11px;
}
.in-tabs { display: flex; gap: 6px; }
.in-tab {
  font: inherit;
  font-size: 12px;
  color: #64748b;
  background: #fff;
  border: 1px solid #e6ebf2;
  border-radius: 999px;
  padding: 4px 12px;
  cursor: pointer;
}
.in-tab.on { color: #1d6fb8; border-color: #cfe4f6; background: #f2f9ff; }
.in-tab i {
  font-style: normal;
  font-size: 10px;
  margin-left: 4px;
  color: #94a3b8;
}

.in-list { flex: 1; min-height: 0; overflow-y: auto; display: flex; flex-direction: column; gap: 9px; padding-bottom: 16px; }
.in-item {
  border: 1px solid #eef1f6;
  border-radius: 12px;
  padding: 11px 12px;
  background: #fff;
}
.in-item.off { background: #fffdfa; border-color: #f6ebda; }
.ii-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ii-name b { font-size: 13.5px; color: #0f172a; }
.ii-cat { margin-left: 6px; font-size: 10px; color: #7b8798; }
.ii-power {
  flex: none;
  font-size: 11px;
  font-weight: 600;
  border-radius: 6px;
  padding: 2px 8px;
  font-variant-numeric: tabular-nums;
}
.ii-power.high { background: #eaf7e3; color: #2f7a2a; }
.ii-power.mid { background: #fff7e6; color: #b45309; }
.ii-power.low { background: #fdeeea; color: #b91c1c; }

.ii-target { margin: 5px 0 0; font-size: 11.5px; color: #475569; }
.ii-target b { color: #0f172a; }
.ii-tags { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.tag {
  font-size: 10px;
  color: #64748b;
  background: #f1f4f8;
  border-radius: 4px;
  padding: 1px 6px;
}
.tag.ok { background: #eef7e7; color: #2f7a2a; }
.tag.warn { background: #fff4e6; color: #b45309; }
.tag.used { background: #eef4ff; color: #2563eb; }
.tag.risk { background: #fff1e6; color: #c2410c; }
.tag.danger { background: #fdeeea; color: #b91c1c; font-weight: 600; }

.ii-note { margin: 7px 0 0; font-size: 10.5px; line-height: 1.55; color: #94a3b8; }
.ii-note.herb { color: #b45309; background: #fff8ec; border-radius: 6px; padding: 4px 7px; }
.ii-apply { display: flex; align-items: center; gap: 7px; margin-top: 9px; }
.dose-input { width: 104px; }
.unit { font-size: 10.5px; color: #94a3b8; }
.cost { margin-left: auto; font-size: 11.5px; color: #b45309; }
.ii-rec { margin: 5px 0 0; font-size: 10px; color: #b0b8c4; }
</style>
