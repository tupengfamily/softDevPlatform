<template>
  <div class="fa-panel">
    <!-- 绝收 / 失败 -->
    <div v-if="failed" class="fa-fail">
      <div class="ff-head">
        <span class="ff-badge">{{ $t('farm.attr.failedBadge') }}</span>
        <b>{{ $t('farm.attr.failureType.' + (failType || 'UNKNOWN')) }}</b>
      </div>
      <p v-if="failNote" class="ff-note">{{ failNote }}</p>
      <div class="ff-kv">
        <span>{{ $t('farm.attr.days') }} <b class="mono">{{ days }}</b></span>
        <span>{{ $t('farm.attr.dvs') }} <b class="mono">{{ fmt(dvs, 3) }}</b></span>
        <span v-if="germination != null">
          {{ $t('farm.attr.germination') }}
          <b class="mono" :class="{ bad: germination < 0.85 }">
            {{ Math.round(germination * 100) }}%
          </b>
        </span>
      </div>
    </div>

    <!-- 作物与播期 -->
    <div class="fa-block">
      <div class="fb-head-line">
        <b class="crop-name">{{ cropLabel }}</b>
        <span class="mono muted">{{ sowDate }} → {{ harvestDate }}</span>
      </div>
      <div v-if="days != null && !failed" class="fa-kv-grid">
        <span>{{ $t('farm.attr.days') }} <b class="mono">{{ days }}</b></span>
        <span>{{ $t('farm.attr.dvs') }} <b class="mono">{{ fmt(dvs, 3) }}</b></span>
        <span>
          {{ $t('farm.attr.germination') }}
          <b class="mono" :class="{ bad: germination != null && germination < 0.85 }">
            {{ germination != null ? Math.round(germination * 100) + '%' : $t('farm.attr.unrecorded') }}
          </b>
        </span>
      </div>
    </div>

    <!-- 产量绝对值（extra 里确实有的：实收产量 / 结实率 / 粒重） -->
    <div v-if="!failed" class="fa-block">
      <div class="fb-head-line"><b>{{ $t('farm.attr.yieldAbsolute') }}</b></div>
      <div class="fa-kv-grid">
        <span>
          {{ $t('farm.attr.yieldFinal') }}
          <b class="mono">
            <template v-if="yieldFinal != null">{{ yieldFinal.toFixed(1) }} {{ $t('farm.unit.perMu') }}</template>
            <template v-else>{{ $t('farm.attr.unrecorded') }}</template>
          </b>
        </span>
        <span>
          {{ $t('farm.attr.fertility') }}
          <b class="mono">{{ fmt(fertility, 3) }}</b>
        </span>
        <span>
          {{ isGrain ? $t('farm.col.tgw') : $t('farm.col.unitWeight') }}
          <b class="mono">
            <template v-if="grainWeight != null">{{ grainWeight.toFixed(isGrain ? 2 : 1) }} g</template>
            <template v-else>{{ $t('farm.attr.unrecorded') }}</template>
          </b>
        </span>
      </div>
    </div>

    <!-- 产量四要素 -->
    <div v-if="!failed" class="fa-block">
      <div class="fb-head-line">
        <b>{{ $t('farm.attr.yieldElements') }}</b>
        <span class="mono muted">
          <template v-if="yieldRaw != null">
            {{ $t('farm.attr.yieldRaw') }} {{ yieldRaw.toFixed(1) }} {{ $t('farm.unit.perMu') }}
          </template>
          <template v-else>
            {{ $t('farm.attr.yieldRaw') }} {{ $t('farm.attr.unrecorded') }}
          </template>
          <template v-if="yieldCapped">
            · <span class="warn">{{ $t('farm.attr.capped', { cap: fmt(yieldCap, 0) }) }}</span>
          </template>
        </span>
      </div>
      <table class="fa-table">
        <thead>
          <tr>
            <th>{{ $t('farm.col.item') }}</th>
            <th>{{ $t('farm.col.loss') }}</th>
            <th>{{ $t('farm.attr.mechanism') }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in elementRows" :key="r.key">
            <td>{{ r.label }}</td>
            <td class="mono" :class="{ bad: r.dmg != null && r.dmg > 0.005 }">
              <template v-if="r.dmg == null">{{ $t('farm.attr.unrecorded') }}</template>
              <template v-else-if="r.dmg > 0.001">−{{ (r.dmg * 100).toFixed(1) }}%</template>
              <template v-else>0.0%</template>
            </td>
            <td class="muted">{{ r.why }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- 病虫害 -->
    <div v-if="pests.length" class="fa-block">
      <div class="fb-head-line">
        <b>{{ $t('farm.attr.pests') }}</b>
        <span class="mono muted">
          <template v-if="pestLoss != null">
            {{ $t('farm.attr.pestLoss') }} {{ (pestLoss * 100).toFixed(1) }}%
          </template>
          <template v-else>{{ $t('farm.attr.unrecorded') }}</template>
        </span>
      </div>
      <ul class="fa-list">
        <li v-for="(p, i) in pests" :key="p.id || i">
          <span class="pi-name">{{ pestLabel(p) }}</span>
          <span class="pi-sev mono">{{ fmt(p.severity * 100, 1) }}%</span>
        </li>
      </ul>
    </div>

    <!-- 灾害 -->
    <div v-if="disasters.length" class="fa-block">
      <div class="fb-head-line">
        <b>{{ $t('farm.attr.disasters') }}</b>
        <span class="mono muted">
          <template v-if="disasterLoss != null">
            {{ $t('farm.attr.disasterLoss') }} {{ (disasterLoss * 100).toFixed(1) }}%
          </template>
          <template v-else>{{ $t('farm.attr.unrecorded') }}</template>
        </span>
      </div>
      <ul class="fa-list">
        <li v-for="(d, i) in disasters" :key="i">
          <span class="pi-name">{{ disasterLabel(d) }}</span>
          <span class="mono muted">{{ d.date }}</span>
          <span class="pi-sev mono bad">{{ fmt(d.loss * 100, 1) }}%</span>
        </li>
      </ul>
    </div>

    <!-- 投入产出 -->
    <div v-if="hasEcon" class="fa-block">
      <div class="fb-head-line"><b>{{ $t('farm.attr.economics') }}</b></div>
      <div class="fa-kv-grid">
        <span>{{ $t('farm.attr.inputCost') }} <b class="mono">{{ withUnit(inputCost, 1, '¥') }}</b></span>
        <span>{{ $t('farm.attr.revenue') }} <b class="mono">{{ withUnit(revenue, 1, '¥') }}</b></span>
        <span>
          {{ $t('farm.attr.net') }}
          <b class="mono" :class="net < 0 ? 'bad' : 'good'">{{ withUnit(net, 1, '¥') }}</b>
        </span>
        <span v-if="nApplied != null">
          {{ $t('farm.attr.nApplied') }} <b class="mono">{{ withUnit(nApplied, 1, $t('farm.unit.perMu')) }}</b>
        </span>
      </div>
    </div>

    <!-- 合规提示 -->
    <div v-if="phiViolated || overdose > 0" class="fa-block fa-warn-block">
      <div class="fb-head-line"><b>{{ $t('farm.attr.compliance') }}</b></div>
      <p v-if="phiViolated" class="fa-warn">
        {{ $t('farm.attr.phiViolated', { n: phiViolations }) }}
        <span v-if="phiItem" class="mono">（{{ phiItem }}）</span>
      </p>
      <p v-if="overdose > 0" class="fa-warn">
        {{ $t('farm.attr.overdose', { n: overdose }) }}
      </p>
    </div>

    <!-- 天气来源 -->
    <p v-if="weatherSource" class="fa-weather muted">{{ weatherSource }}</p>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  GRAIN_CROPS,
  cropIdOf,
  cropLabelOf,
  disasterLabelOf,
  parseDisasterHit,
  parsePestHit,
  pestLabelOf,
  zoneNameOf as zoneNameOfRaw
} from '../../utils/farmLabels'

/**
 * 农场收割归因面板（2026-10-26）。
 *
 * <p>🔴 <b>数据源是 {@code t_game_score.extra_json}</b> —— 后端收割时写入的
 * 20+ 归因字段（见 {@code FarmAttribution}）。此前这些字段<b>躺在库里没人看</b>：
 * {@code GameHistory.vue} 里 grep {@code extra|crop|revenue|net} 零命中。
 *
 * <p>🔴 <b>为什么文案在这里而不是后端</b>：后端下发的 {@code crop} / {@code pests[].name}
 * 都是中文（后端唯一权威），英文界面会露中文。前端按稳定的 id 覆盖：
 * <ul>
 *   <li>作物：{@code cropId} → 英文名（{@code CROP_EN}）</li>
 *   <li>病虫害：{@code pests[].id} → {@code farm.pestName.*}（与 GameFarm 同源）</li>
 *   <li>灾害：{@code disasters[].type} → {@code farm.disasterName.*}</li>
 * </ul>
 * 缺项时退回后端中文名（至少不是空白）。
 *
 * <p>🔴 2026-10-27：<b>三张反查表 + 查表函数已抽到 {@code utils/farmLabels.js}</b> ——
 *    归因面板与游戏历史摘要共用同一份实现（此前是两处各写一份，必然分叉）。
 *
 * <p>🔴 <b>口径铁律</b>：{@code yieldRaw} 是<b>引擎原始产量</b>（未封顶），
 * {@code yieldCap} 是<b>产量上限</b> —— 两者不是一回事，展示时不能混用。
 */

const { t, locale } = useI18n()

const props = defineProps({
  /** extra_json 解析后的对象（可为 null） */
  extra: { type: Object, default: null }
})

/*
 * 🔴 反查表与查表函数统一来自 utils/farmLabels.js（唯一实现）。
 * 下面保留的 import 仅为兼容本文件内的历史引用点。
 */


const e = computed(() => props.extra || {})

const isEn = computed(() => String(locale.value).startsWith('en'))

/**
 * 🔴 <b>「缺数据」与「数值是零」必须区分开</b>。
 *
 * <p>2026-10-26 之前写入的成绩（{@code t_game_score.extra_json}）只有 9~30 个字段，
 * 缺 {@code dmgPanicle} / {@code days} / {@code dvs} / {@code germination} 等。
 * 如果直接写 {@code e.dmgPanicle ?? 0}，老数据会显示成「0.0%」——
 * <b>把「这一季没记录穗数受损」说成「穗数一点没受损」</b>，这是编造。
 *
 * <p>规则：字段<b>不存在</b> → 显示「未记录」；字段<b>存在且为 0</b> → 显示「0.0%」。
 */
function has(field) {
  return Object.prototype.hasOwnProperty.call(e.value, field)
}

/** 数值字段：存在才返回数值，不存在返回 null（由调用方决定怎么显示）。 */
function num(field) {
  if (!has(field)) return null
  const v = e.value[field]
  if (v == null) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

const failed = computed(() => e.value.withered === true || e.value.yield === 0)

/**
 * 作物 id：优先用后端写的 {@code cropId}；老数据没有就按中文名反查。
 *
 * 🔴 反查不到就是空串（不猜）—— 空串会让 {@code isGrain} 走「非禾谷」分支，
 *    此时「单果重」是<b>默认解读</b>而非断言，比显示错数字诚实。
 *
 * 🔴 2026-10-27：实现已收进 {@code utils/farmLabels.js}（与 GameHistory 共用）。
 */
const cropId = computed(() => cropIdOf(e.value))

const cropLabel = computed(() => cropLabelOf(e.value, isEn.value))

const isGrain = computed(() => GRAIN_CROPS.includes(cropId.value))

const days = computed(() => num('days'))
const dvs = computed(() => num('dvs'))
const sowDate = computed(() => e.value.sowDate || '—')
const harvestDate = computed(() => e.value.harvestDate || '—')
const germination = computed(() => num('germination'))
const failType = computed(() => e.value.failureType || null)
const failNote = computed(() => e.value.failureNote || '')

const yieldRaw = computed(() => num('yieldRaw'))
const yieldCap = computed(() => num('yieldCap'))
const yieldCapped = computed(() => e.value.yieldCapped === true)
/**
 * 🔴 2026-10-27：最终实收产量（kg/亩）。
 *
 * <p>{@code yieldRaw} 是<b>引擎原始潜力</b>（只含温度/积温损伤），
 * {@code yieldFinal} 才是叠加病虫害/灾害/土壤/出苗/杂草/工序遗漏/农具后的
 * <b>真实入库量</b>。历史数据（2026-10-27 之前）没有这个字段 → null → 显示「未记录」。
 */
const yieldFinal = computed(() => num('yieldFinal'))
const fertility = computed(() => num('fertility'))
const grainWeight = computed(() => num('thousandGrainWeight'))

const pestLoss = computed(() => num('pestLoss'))
const disasterLoss = computed(() => num('disasterLoss'))
/**
 * 病虫害列表（<b>兼容新旧两种格式</b>）。
 *
 * 🔴 <b>旧格式是字符串数组</b>：2026-10-26 之前后端写的是
 *    {@code extra.pests = ["蚜虫:0.961", ...]}（`name + ":" + severity` 拼接）。
 *    如果直接按结构化对象读，元素全是 undefined → 渲染出「—%」的空行。
 *    所以这里做一次兼容解析：字符串按最后一个冒号切出「名称 / 严重度」。
 *
 * 🔴 <b>新格式是对象数组</b>：{@code {id, name, type, severity}}，
 *    带跨语言稳定的 {@code id}（英文界面靠它映射）。
 */
const pests = computed(() => {
  const raw = e.value.pests
  if (!Array.isArray(raw)) return []
  // 🔴 2026-10-27：解析逻辑已收进 utils/farmLabels.js（与 GameHistory 共用）。
  return raw.map((p) => parsePestHit(p)).filter(Boolean)
})

/**
 * 灾害列表（<b>兼容新旧两种格式</b>）。
 *
 * 🔴 旧格式：{@code ["暴雨山洪:0.12", ...]}；新格式：
 *    {@code {type, name, date, intensity, loss, note}}。
 */
const disasters = computed(() => {
  const raw = e.value.disasters
  if (!Array.isArray(raw)) return []
  // 🔴 2026-10-27：解析逻辑已收进 utils/farmLabels.js（与 GameHistory 共用）。
  return raw.map((d) => parseDisasterHit(d)).filter(Boolean)
})

const inputCost = computed(() => num('inputCost'))
const revenue = computed(() => num('revenue'))
const net = computed(() => num('net'))
const nApplied = computed(() => num('nApplied'))

const phiViolated = computed(() => e.value.phiViolated === true)
const phiViolations = computed(() => e.value.phiViolated === true ? (e.value.phiViolations ?? 0) : 0)
const phiItem = computed(() => e.value.phiItem || '')
const overdose = computed(() => num('overdose') ?? 0)

/**
 * 天气来源标签。
 *
 * 🔴 <b>优先用结构化的 {@code weatherSourceData}</b>（2026-10-26 后端新增），
 *    按 {@code kind / year / mockedSegments} 拼当前语言的标签；
 *    老数据没有这个字段，退回中文原文 {@code weatherSource}（会露中文，但至少不是空白）。
 */
const weatherLabel = computed(() => {
  const d = e.value.weatherSourceData
  if (d && d.kind) {
    const n = Number(d.mockedSegments) || 0
    if (d.kind === 'REPLAY') {
      const base = t('farm.attr.weather.replay', { y: d.year })
      return n > 0 ? t('farm.attr.weather.mocked', { n, base }) : base
    }
    if (d.kind === 'CLIMATE') {
      const base = t('farm.attr.weather.climate', { z: zoneNameOf(d.zoneId) })
      return n > 0 ? t('farm.attr.weather.mocked', { n, base }) : base
    }
  }
  return e.value.weatherSource || ''
})

/** 分区 id → 可读名（统一出口，见 utils/farmLabels.js）。 */
function zoneNameOf (id) {
  return zoneNameOfRaw(id, t, '', isEn.value)
}

const weatherSource = computed(() => weatherLabel.value)

/**
 * 「投入产出」区块是否要显示。
 *
 * 🔴 老成绩（id 122~128）根本没记投入产出 —— 整块显示「0.0 ¥」等于
 * 告诉玩家「这一季没花钱也没赚钱」，而事实是<b>没记录</b>。所以整块隐藏。
 */
const hasEcon = computed(
  () => has('inputCost') || has('revenue') || has('net') || has('nApplied')
)

/**
 * 产量四要素行。
 *
 * 🔴 <b>口径铁律一：这里只列「受损率」，不列「当前值」</b>。
 *    {@code extra_json} 里只有四个 {@code dmg*} 字段（受损率）与
 *    {@code fertility} / {@code thousandGrainWeight}（两个绝对值），
 *    <b>没有「当前穗数 / 当前单果重」</b> —— 那属于「游戏进行中」的实时视图
 *    （{@code FarmView.YieldView}），历史归因表拿不到。
 *    缺数据就不显示，绝不编造。
 *
 * 🔴 <b>口径铁律二：「未记录」与「0.0%」是两回事</b>。
 *    老成绩（2026-10-26 之前）没写 {@code dmgPanicle}，
 *    如果按 {@code ?? 0} 处理就会显示「0.0%」—— 把「没记录」说成「零受损」。
 *    所以 {@code dmg} 保留 {@code null} 表示「未记录」，由模板区分渲染。
 *
 *    「来自」列讲的是<b>机制</b>（这个要素在什么阶段、因为什么受损），
 *    这是「农业科学模块」最有教学价值的部分：玩家要能迁移的是原理，不是数字。
 */
const elementRows = computed(() => {
  const g = isGrain.value
  return [
    {
      key: 'panicle',
      label: t(g ? 'farm.col.panicles' : 'farm.col.plants'),
      dmg: num('dmgPanicle'),
      why: t(g ? 'farm.attr.why.panicleGrain' : 'farm.attr.why.panicleVeg')
    },
    {
      key: 'floret',
      label: t(g ? 'farm.col.grains' : 'farm.col.fruits'),
      dmg: num('dmgFloret'),
      why: t('farm.attr.why.floret')
    },
    {
      key: 'fertility',
      label: t('farm.attr.fertility'),
      dmg: num('dmgFertility'),
      why: t('farm.attr.why.fertility')
    },
    {
      key: 'weight',
      label: t(g ? 'farm.col.tgw' : 'farm.col.unitWeight'),
      dmg: num('dmgWeight'),
      why: t(g ? 'farm.attr.why.weightGrain' : 'farm.attr.why.weightVeg')
    }
  ]
})

/**
 * 病虫害名：优先按 id 映射（跨语言稳定），缺项退回后端中文名。
 *
 * 🔴 旧格式没有 id（只有拼接字符串解析出来的 name），此时直接用 name。
 */
function pestLabel(p) {
  return pestLabelOf(p, t)
}

/** 灾害名：按 type 映射，缺项退回后端中文名。 */
function disasterLabel(d) {
  return disasterLabelOf(d, t)
}

/**
 * 数值格式化：<b>null（未记录）显示「未记录」，不是 0</b>。
 *
 * 🔴 这里的 null 有两种来源：字段不存在（老成绩没这个字段）、
 *    字段存在但值为 null（如 {@code germination} 在绝收路径可能没有）。
 *    两者都表示「没记录」，不能显示成 0。
 */
function fmt(v, digits) {
  if (v == null) return t('farm.attr.unrecorded')
  const n = Number(v)
  if (!Number.isFinite(n)) return t('farm.attr.unrecorded')
  return n.toFixed(digits)
}

/**
 * 带单位的数值格式化：<b>null 时只显示「未记录」，不跟单位</b>。
 *
 * 🔴 之前写的是 {@code fmt(v, 1) + ' ¥'} → 未记录时会显示「未记录 ¥」，
 *    读起来像「未记录多少元」。缺数据就不带单位。
 */
function withUnit(v, digits, unit) {
  if (v == null) return t('farm.attr.unrecorded')
  const n = Number(v)
  if (!Number.isFinite(n)) return t('farm.attr.unrecorded')
  return n.toFixed(digits) + ' ' + unit
}
</script>

<style scoped>
.fa-panel {
  font-size: var(--fs-sm);
  color: var(--c-text-1);
  padding: 4px 0 2px;
  /* 🔴 窄屏下表格单元格会把面板撑到 888px（列宽合计），
     这里必须允许收缩，否则在抽屉里也会横向溢出。 */
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
}
.fa-block {
  margin-bottom: 12px;
}
.fb-head-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 6px;
  padding-bottom: 4px;
  border-bottom: 1px solid var(--c-border);
}
.fb-head-line b { font-size: 13px; }
.muted { color: var(--c-text-3); font-size: var(--fs-xs); }
.crop-name { font-size: 14px; }
.mono { font-variant-numeric: tabular-nums; }
.bad { color: #c0392b; }
.good { color: #1e8449; }
.warn { color: #b9770e; }

.fa-kv-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  font-size: var(--fs-xs);
  color: var(--c-text-2);
}
.fa-kv-grid b { color: var(--c-text-1); }

.fa-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-xs);
}
.fa-table th {
  text-align: left;
  font-weight: 600;
  color: var(--c-text-3);
  padding: 3px 6px;
  border-bottom: 1px solid var(--c-border);
}
.fa-table td {
  padding: 3px 6px;
  border-bottom: 1px dashed var(--c-border);
  color: var(--c-text-2);
}

.fa-list { list-style: none; margin: 0; padding: 0; }
.fa-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 3px 0;
  border-bottom: 1px dashed var(--c-border);
  font-size: var(--fs-xs);
}
.pi-name { flex: 1; color: var(--c-text-1); }
.pi-sev { color: #b9770e; }

.fa-warn-block {
  background: #fff8e6;
  border: 1px solid #f3d19a;
  border-radius: 6px;
  padding: 8px 10px;
}
.fa-warn {
  margin: 2px 0;
  font-size: var(--fs-xs);
  color: #8a5a00;
}

.fa-fail {
  background: #fdecea;
  border: 1px solid #f5c6c0;
  border-radius: 6px;
  padding: 8px 10px;
  margin-bottom: 12px;
}
.ff-head { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.ff-badge {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 999px;
  background: #c0392b;
  color: #fff;
  font-size: 11px;
}
.ff-note { margin: 4px 0; font-size: var(--fs-xs); color: var(--c-text-2); }
.ff-kv { display: flex; gap: 16px; flex-wrap: wrap; font-size: var(--fs-xs); color: var(--c-text-2); }
.ff-kv b { color: var(--c-text-1); }

.fa-weather {
  margin: 0;
  padding-top: 6px;
  border-top: 1px dashed var(--c-border);
  font-size: 11px;
}
</style>
