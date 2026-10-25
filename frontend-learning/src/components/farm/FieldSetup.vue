<template>
  <div class="fs-page">
    <!-- ================= 左：选地 ================= -->
    <section class="fs-left">
      <header class="fs-head">
        <div class="fs-title">
          <!-- 🔴 2026-10-23：取消按钮。空农场点「进入农场」会直接进这里，
               没有退路就等于把玩家困住了。 -->
          <button class="fs-back" @click="onCancel">← {{ $t('common.back') }}</button>
          <h2>{{ $t('farm.setup.title') }}</h2>
          <p class="muted">{{ $t('farm.setup.subtitle') }}</p>
          <!-- 🔴 2026-10-23：显示「正在给哪个农场开地」。
               无地块的农场也要能说清自己是谁，否则玩家建完地不知道地建在哪。 -->
          <p v-if="props.farmName" class="fs-farm">
            <span class="ff-ico">🚜</span>
            <span class="ff-name">{{ props.farmName }}</span>
            <span v-if="props.farmZone" class="ff-zone">{{ zoneNameOf(props.farmZone) }}</span>
          </p>
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
            <div class="zc-badges">
              <span v-if="z.isBestZone" class="best-badge">★ {{ $t('farm.setup.bestZone') }}</span>
              <span class="lv-badge" :class="`b-${z.level}`">{{ z.levelName }}</span>
            </div>
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

          <!-- 区域风险角标：相对最佳区差几级 -->
          <div v-if="!z.isBestZone && zoneGap(z) > 0" class="zc-risk">
            <span class="zc-risk-tag">{{ $t('farm.setup.notBestZone') }}</span>
            <span class="zc-risk-txt">
              {{ $t('farm.setup.zoneGap', { n: zoneGap(z) }) }}
            </span>
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
                :disabled-date="disabledSowDate"
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
            <!-- 推荐播期 / 适期窗口（后端权威数据，前端不再硬编码） -->
            <div v-if="risk" class="sow-window">
              <!--
                🔴 2026-10-23：这里显示的是**距今最近**的推荐播期（后端保证 ≥ 今天）。
                之前后端返回的是「同期锚点」——世界日期 2027-11-21 时它可能是
                2027-09-20（已经过去），点「回到推荐播期」会把日期设到过去。
                🔴 再修正：不再是「下一季」——若本期窗口尾巴还没过（如大白菜
                江南 9/24 才收尾），后端会给出「今天」，因为今天就能种。
              -->
              <div class="sw-row">
                <span class="sw-lab">{{ $t('farm.setup.nearestSow') }}</span>
                <span class="sw-val mono">{{ risk.recommendedSow }}</span>
              </div>
              <!--
                适期窗口 = **距今最近**的那段适合日子（起点永远是今天以后）。
                不能用「本期窗口」——它可能整个落在过去，而日期选择器
                禁用了今天之前，展示一个选不了的窗口只会让人困惑。
              -->
              <div class="sw-row">
                <span class="sw-lab">{{ $t('farm.setup.sowWindow') }}</span>
                <span class="sw-val mono" :class="{ out: !risk.inWindow }">
                  {{ risk.nearestWindowFrom }} ~ {{ risk.nearestWindowTo }}
                </span>
              </div>
              <!--
                偏移 = 你选的播期与**本期适期**的差（同期比较口径）。
                这是风险评估的核心量，不能改成相对下一季推荐 ——
                那样会掩盖「这一季已经晚了 62 天」这个事实。
              -->
              <div v-if="risk.offsetDays !== 0" class="sw-row">
                <span class="sw-lab">{{ $t('farm.setup.sowOffset') }}</span>
                <span class="sw-val mono" :class="{ out: !risk.inWindow }">
                  {{ risk.offsetDays > 0 ? '+' : '' }}{{ risk.offsetDays }} {{ $t('farm.day') }}
                </span>
              </div>
              <el-button
                v-if="risk.offsetDays !== 0"
                size="small"
                class="sw-fix"
                @click="gotoRecommended"
              >
                {{ $t('farm.setup.gotoRecommended') }}
              </el-button>
            </div>
          </section>

          <!-- 风险提示（区域 / 时机 / 结构） -->
          <section v-if="risk && riskItems.length" class="rp-card rp-risk">
            <div class="rp-card-h">
              <h4>{{ $t('farm.setup.riskTitle') }}</h4>
              <span class="risk-badge" :class="RISK_CLASS[risk.riskLevel]">
                {{ risk.riskLevelName }}
              </span>
            </div>
            <p class="risk-summary">{{ risk.summary }}</p>
            <ul class="risk-list">
              <li v-for="(it, i) in riskItems" :key="i" :class="RISK_CLASS[it.level]">
                <div class="ri-head">
                  <span class="ri-tag">{{ it.code }}</span>
                  <span class="ri-title">{{ it.title }}</span>
                </div>
                <p class="ri-detail">{{ it.detail }}</p>
                <p v-if="it.advice" class="ri-advice">
                  <span class="ri-advice-tag">{{ $t('farm.setup.advice') }}</span>
                  {{ it.advice }}
                </p>
              </li>
            </ul>
            <p class="rp-foot muted">{{ $t('farm.setup.riskNote') }}</p>
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
import {
  listFarmCrops,
  listFarmZones,
  getFarmSuitability,
  getFarmPlantingRisk,
  createFarmField
} from '../../api/farm'
import { useRoute } from 'vue-router'

const props = defineProps({
  initialZone: { type: String, default: '' },
  initialCrop: { type: String, default: '' },
  hasField: { type: Boolean, default: false },
  /** -1 表示新建一块地（后端自动分配槽位）；≥0 表示在该槽位上重建 */
  slot: { type: Number, default: -1 },
  /**
   * 归属农场；给了它就不用再选农业区（区县决定气候）
   *
   * <p>🔴 2026-10-23：给了 {@code farmId} 时**必须同时给 {@code farmZone}**。
   * 无地块的农场没有 {@code state.plot.zoneId}，若不锁定农业区，
   * 选地面板会退回默认的「江南」区 —— 在东北的农场里按江南的播期和气候算风险，
   * 评估结果整个是错的（后端仍会以农场实际 zone 建田，于是前后端两套区）。
   */
  farmId: { type: Number, default: null },
  /** 农场名 —— 面板顶部要显示「正在给哪个农场开地」 */
  farmName: { type: String, default: '' },
  /** 农场所在农业区（锁定后不再让玩家选） */
  farmZone: { type: String, default: '' },
  /**
   * 世界日期（YYYY-MM-DD）——「不能选今天之前」的基准。
   *
   * <p>🔴 **必须用世界日期，不能用 `new Date()`**：演示模式下用户可推进时间轴，
   * 世界日期被推进过（可能已经是 2027 年）。用现实日期做基准
   * 会把「合法但在过去」的世界日期全判成非法，或者反过来放过一堆历史日期。
   * 父组件从 `state.worldDate` 传进来。
   */
  today: { type: String, default: '' }
})
const emit = defineEmits(['created', 'cancel', 'need-shop'])
const { t, locale } = useI18n()
const route = useRoute()

const crops = ref([])
const zones = ref([])
const cropId = ref(props.initialCrop || 'rice_mid')
const zoneId = ref('jiangnan')
const sowDate = ref('')
const report = ref(null)
/** 风险提示表（后端 /planting-risk） */
const risk = ref(null)
const fieldName = ref('')
const loading = ref(true)
const busy = ref(false)

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

/**
 * 风险条目按「严重度倒序」排。
 *
 * <p>后端已经按 AREA / TIMING / STRUCTURE 的语义给好了顺序，
 * 这里再按 level 排一次只是保险——真正决定视觉优先级的是它。
 */
const riskItems = computed(() =>
  [...(risk.value?.items || [])].sort((a, b) => b.level - a.level)
)

/** 风险等级对应的语义类名（前端配色用）。 */
const RISK_CLASS = ['r0', 'r1', 'r2', 'r3', 'r4', 'r5']

/** 区域卡上的风险角标等级：该区相对最佳区差几级（0 = 就是最佳区）。 */
function zoneGap(z) {
  if (z.isBestZone) return 0
  const best = zones.value.find(x => x.isBestZone)
  if (!best) return 0
  return Math.max(0, z.level - best.level)
}

/**
 * 高风险二次确认的正文：把风险条目压成几句人话。
 *
 * <p>不放数字堆砌——二次确认的目的是让玩家知道「会发生什么」，不是让他做算术。
 */
const riskConfirmText = computed(() => {
  if (!risk.value) return ''
  const lines = riskItems.value
    .filter(it => it.level >= 3)
    .map(it => `· ${it.title}：${it.detail}`)
  if (!lines.length) return ''
  return [
    t('farm.setup.riskConfirmLead', {
      crop: risk.value.cropName,
      zone: risk.value.zoneName,
      level: risk.value.riskLevelName
    }),
    ...lines,
    t('farm.setup.riskConfirmFoot')
  ].join('\n')
})

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

/**
 * 重新拉该作物的四区评级，并把播期重置为该区推荐值。
 *
 * <p>🔴 2026-10-23：若父组件锁定了农场（{@code farmZone}），
 * 只保留那一个区 —— 四区评级对玩家没有意义（不能选），
 * 而且拉着别的区会让人以为「还能换区」。
 * 后端 {@code /zones} 一律返回四区，过滤在前端做（省一次接口改动）。
 */
async function loadZones() {
  const res = await listFarmZones(cropId.value)
  const all = res?.data || []
  const locked = props.farmZone
  zones.value = locked ? all.filter((z) => z.id === locked) : all
  const cur = locked
    ? zones.value[0]
    : zones.value.find((z) => z.id === props.initialZone)
      || zones.value.find((z) => z.id === zoneId.value)
      || zones.value[0]
  if (!cur) return
  zoneId.value = cur.id
  // 🔴 2026-10-23：后端 `/zones` 的 `sowDate` 现在保证是「≥ 今天」的最近推荐播期
  // （后端用 `CropCatalog.nearestWindow` 算），所以这里不再需要 clampToToday 兜底。
  // 留着它反而会在极端情况下把真正的推荐日压成「今天」，丢掉信息。
  sowDate.value = cur.sowDate
}

/**
 * 农业区的中文名（用于顶部农场合标识）。
 *
 * <p>后端 {@code /zones} 只返回当前作物的四区，父组件锁定的区
 * 未必在这个作物里，所以这里内置一份全区名映射兜底。
 */
const ZONE_NAMES = {
  jiangnan: '江南',
  huanan: '华南',
  huanghuaihai: '黄淮海',
  dongbei: '东北',
  xinan: '西南',
  xibei: '西北',
  qinghai: '青藏',
  gaoxin: '高原'
}
function zoneNameOf(id) {
  if (!id) return ''
  const hit = zones.value.find((z) => z.id === id)
  if (hit?.name) return hit.name
  return ZONE_NAMES[id] || id
}

/**
 * 取消选地（2026-10-23）。
 *
 * <p>空农场点「进入农场」会直接进这个面板 —— 必须给一条退路，
 * 否则玩家想回去看看地图却出不去。
 */
function onCancel() {
  emit('cancel')
}

const cropHint = computed(() => {
  const c = crops.value.find(x => x.id === cropId.value)
  return c ? c.desc : ''
})

/**
 * 「今天」的世界日期（YYYY-MM-DD）。
 *
 * <p>父组件没传（独立使用时）退化为本地日期，保证组件不会因为缺参数就崩。
 */
const todayStr = computed(() => {
  if (props.today) return props.today
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
})

/**
 * 日期选择器禁用今天之前的日期。
 *
 * <p>Element Plus 的 {@code disabledDate} 收 Date 对象；比较时按
 * 「本地时区的零点」与今天的零点比，避免时分秒造成的边界误判
 * （比如今天 00:30 时，选今天会被误禁）。
 */
function disabledSowDate(d) {
  if (!d) return false
  const pick = new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime()
  const today = new Date()
  const base = new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime()
  const min = todayStr.value
    ? new Date(`${todayStr.value}T00:00:00`).getTime()
    : base
  return pick < min
}

/**
 * 把日期推到不早于今天。
 *
 * <p>🔴 2026-10-23：已无调用者。
 *
 * <p>它是给「后端返回过期推荐值」打的补丁 —— 把推荐播期压成「今天」，
 * 免得日期选择器显示一个禁用态的值。但那个 bug 的<b>根在后端</b>
 * （用 {@code LocalDate.now()} 现实日期而不是世界日期算推荐值），
 * 后端改成 {@code CropCatalog.nextSow} 后返回的推荐值保证 ≥ 今天，
 * 补丁就不需要了。
 *
 * <p>留着它反而有害：它会把「真正的下一季推荐日」（如 2028-09-20）
 * 错误地压成「今天」（2027-11-21），把信息丢掉。
 */
function clampToToday(dateStr) {
  if (!dateStr || !todayStr.value) return dateStr
  return dateStr < todayStr.value ? todayStr.value : dateStr
}

/**
 * 作物 visual → 图标。
 *
 * ⚠️ 2026-10-24：后端 `visual` 的语义变了 —— 园艺作物的 visual 现在是**作物 id**
 * （cabbage / grape / rose …），不再是 leafy/vine/bush/flower 这类通用族。
 * 存量数据里仍是旧值，两种都要认，所以这里先按作物 id 查、再按族查。
 */
function cropIcon(visual) {
  const byCrop = {
    rice_mid: '🌾', wheat_winter: '🌾', corn_spring: '🌽', soybean: '🫘',
    millet: '🌾',
    cabbage: '🥬', radish: '🥕',
    tomato: '🍅', chili: '🌶️', eggplant: '🍆', strawberry: '🍓',
    cucumber: '🥒', watermelon: '🍉', melon: '🍈', yardlong_bean: '🫛',
    potato: '🥔',
    grape: '🍇', pear: '🍐',
    chrysanthemum: '🌼', sunflower: '🌻', tulip: '🌷', rose: '🌹'
  }
  if (byCrop[visual]) return byCrop[visual]
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
  clearTimeout(timer)
  timer = setTimeout(loadReport, 300)
}, { immediate: false })

async function loadReport() {
  try {
    const res = await getFarmSuitability(zoneId.value, sowDate.value, cropId.value)
    report.value = res?.data || null
  } catch (e) {
    report.value = null
    ElMessage.error(t('farm.setup.loadFail'))
  }
  // 风险表单独拉：它比评级多跑 4 个区的回放（约 200ms），失败不该影响评级显示
  try {
    const rr = await getFarmPlantingRisk(zoneId.value, sowDate.value, cropId.value)
    risk.value = rr?.data || null
  } catch (e) {
    risk.value = null
  }
}

function pickZone(z) {
  zoneId.value = z.id
  // 同 loadZones：后端给的 sowDate 已是「≥ 今天」的最近推荐播期，直接用。
  sowDate.value = z.sowDate
  fieldName.value = ''
}

/**
 * 播期前后挪动。
 *
 * <p>🔴 **不能往前挪到今天之前**（需求 2026-10-22）：「−10 / −20」按钮
 * 无脑减日期会减出过去的日子，与日期选择器的禁用规则打架。
 * 所以往前挪时撞到今天就停住。
 */
function shiftSow(days) {
  const d = new Date(sowDate.value + 'T00:00:00')
  d.setDate(d.getDate() + days)
  let next = d.toISOString().slice(0, 10)
  if (next < todayStr.value) {
    next = todayStr.value
  }
  sowDate.value = next
}

/**
 * 一键回到推荐播期（风险提示里给出的「补救建议」的直接操作）。
 *
 * <p>🔴 2026-10-23：这里**不再**用 {@code clampToToday}。
 *
 * <p>原因：后端 {@code recommendedSow} 现在保证是「≥ 今天」的最近推荐播期
 * （2028-09-20 这种），本身就可直接用。
 * 之前的 {@code clampToToday} 是给「后端返回过期推荐值」打的补丁 ——
 * 它会把推荐值改成「今天」，把「真正的推荐日」这个信息丢了。
 * 补丁的病因（后端口径错）已经在后端修掉，前端这层不需要了，
 * 留着反而会在极端情况下把 2028-09-20 错误地压成 2027-11-21。
 */
function gotoRecommended() {
  if (risk.value?.recommendedSow) {
    sowDate.value = risk.value.recommendedSow
  }
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
  // 🔴 高风险二次确认（S23 §2.1：**只提示不拦**）。
  // 风险等级 ≥3 时把「这一季最可能发生什么」摊开给玩家看，让他在知情的前提下决定。
  if (risk.value && risk.value.riskLevel >= 3) {
    try {
      await ElMessageBox.confirm(
        riskConfirmText.value,
        t('farm.setup.riskConfirmTitle'),
        {
          type: 'warning',
          confirmButtonText: t('farm.setup.riskConfirmOk'),
          cancelButtonText: t('common.cancel'),
          customClass: 'risk-confirm-box'
        }
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
    const d = r?.data || {}
    ElMessage.success(t('farm.setup.created', { name: d.fieldName || defaultName.value }))
    // 🔴 2026-10-23 需求 R：**缺种子提示**。
    //
    // 后端做的是**软校验**：没种子也让你开田（老玩家库存表里没有种子，
    // 一刀切拒绝等于所有老玩家都不能种地），但会把 `needSeed` + `seedHint` 回传。
    // 前端之前完全没读这两个字段 —— 后端算好的提示直接丢掉了，
    // 玩家只看到「已建田」，根本不知道种子还欠着。
    //
    // 这里用一个带「去商店」按钮的确认框补上：提示 + 一键跳商店，
    // 不打断操作（不阻断），但把「欠种子」这件事说清楚。
    if (d.needSeed) {
      const cropName = d.cropName || cropId.value
      try {
        await ElMessageBox.confirm(
          t('farm.setup.needSeedBody', { crop: cropName }),
          t('farm.setup.needSeedTitle'),
          {
            type: 'warning',
            confirmButtonText: t('farm.setup.gotoShop'),
            cancelButtonText: t('common.close'),
            customClass: 'seed-hint-box'
          }
        )
        // 玩家点了「去商店」→ 抛给父组件，由它开商店面板
        emit('need-shop', { cropId: cropId.value, cropName })
      } catch (e) {
        // 玩家点了「关闭」/ 取消 → 不做任何事（地已经建好了）
      }
    }
    // 🔴 2026-10-22：把后端回的 slot / farmId 透出去。
    // 新建地块时槽位是后端分配的，前端若只能"取最大 slot"来跳转，
    // 在农场内（非全局递增）会跳错地块。
    emit('created', { slot: d.slot ?? null, farmId: d.farmId ?? null })
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

/* 🔴 2026-10-23：返回按钮（取消选地） */
.fs-back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin: 0 0 6px;
  padding: 3px 10px;
  border: 1px solid #e6ebf2;
  border-radius: 999px;
  background: #fff;
  color: #64748b;
  font-size: 11.5px;
  cursor: pointer;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}
.fs-back:hover {
  color: #1d6fb8;
  border-color: #cfe4f6;
  background: #f2f9ff;
}

/* 🔴 2026-10-23：农场合标识（正在给哪个农场开地） */
.fs-farm {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 7px !important;
  padding: 4px 11px;
  border-radius: 999px;
  font-size: 12.5px;
  font-weight: 600;
  color: #2f6b34;
  background: rgba(96, 156, 72, 0.14);
  border: 1px solid rgba(96, 156, 72, 0.3);
}
.fs-farm .ff-ico { font-size: 13px; }
.fs-farm .ff-zone {
  font-weight: 500;
  color: #6b7f68;
  padding-left: 6px;
  border-left: 1px solid rgba(96, 156, 72, 0.28);
}
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
.zc-badges { display: flex; flex-direction: column; align-items: flex-end; gap: 4px; flex: none; }
.best-badge {
  font-size: 10.5px;
  font-weight: 700;
  color: #a16207;
  background: #fef9c3;
  border: 1px solid #fde68a;
  border-radius: 6px;
  padding: 2px 7px;
  white-space: nowrap;
}
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

/* 区域风险角标 */
.zc-risk {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px dashed #f1e4d8;
  font-size: 10.5px;
}
.zc-risk-tag {
  flex: none;
  font-size: 9.5px;
  font-weight: 700;
  color: #b45309;
  background: #fff4e6;
  border-radius: 4px;
  padding: 1px 5px;
}
.zc-risk-txt { color: #a3742c; }

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

/* 播期窗口（推荐播期 / 适期区间） */
.sow-window {
  margin-top: 10px;
  padding: 9px 11px;
  background: #f7fafd;
  border: 1px solid #e8eef5;
  border-radius: 9px;
}
.sw-row {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: 11px;
  line-height: 1.9;
}
.sw-lab { color: #8a94a6; }
.sw-val { color: #1f2937; font-weight: 600; }
.sw-val.out { color: #c2410c; font-weight: 700; }
.sw-fix { margin-top: 7px; }

/* 风险提示区 */
.rp-risk { background: #fffdf8; border-color: #f2e6cf; }
.risk-badge {
  flex: none;
  font-size: 11px;
  font-weight: 700;
  border-radius: 6px;
  padding: 3px 9px;
  white-space: nowrap;
}
.risk-badge.r0 { background: #eef7e7; color: #3a8a33; }
.risk-badge.r1 { background: #f0f8e8; color: #5b8a2a; }
.risk-badge.r2 { background: #fff7e6; color: #b45309; }
.risk-badge.r3 { background: #fff1e6; color: #c2410c; }
.risk-badge.r4 { background: #fdeeea; color: #b91c1c; }
.risk-badge.r5 { background: #fbe4e4; color: #a01515; }

.risk-summary {
  margin: 0 0 10px;
  font-size: 11.5px;
  line-height: 1.7;
  color: #475569;
}

.risk-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.risk-list li {
  border-left: 3px solid #e2e8f0;
  padding: 7px 0 7px 10px;
  border-radius: 0 8px 8px 0;
  background: #fff;
}
.risk-list li.r0 { border-left-color: #86c46c; }
.risk-list li.r1 { border-left-color: #a8cf72; }
.risk-list li.r2 { border-left-color: #eab464; }
.risk-list li.r3 { border-left-color: #e88a5c; }
.risk-list li.r4 { border-left-color: #d9615a; }
.risk-list li.r5 { border-left-color: #b42318; }

.ri-head { display: flex; align-items: center; gap: 7px; margin-bottom: 3px; }
.ri-tag {
  flex: none;
  font-size: 9px;
  font-weight: 800;
  letter-spacing: 0.6px;
  color: #64748b;
  background: #f1f4f8;
  border-radius: 4px;
  padding: 1px 5px;
}
.risk-list li.r3 .ri-tag, .risk-list li.r4 .ri-tag, .risk-list li.r5 .ri-tag {
  background: #fdeeea;
  color: #c2410c;
}
.ri-title { font-size: 12px; font-weight: 650; color: #0f172a; }
.ri-detail { margin: 0; font-size: 11.5px; line-height: 1.72; color: #334155; }
.ri-advice {
  margin: 6px 0 0;
  font-size: 11px;
  line-height: 1.65;
  color: #5a6b7d;
  background: #f6f9fc;
  border-radius: 6px;
  padding: 6px 8px;
}
.ri-advice-tag {
  display: inline-block;
  font-size: 9.5px;
  font-weight: 700;
  color: #1d6fb8;
  background: #eaf4fd;
  border-radius: 4px;
  padding: 0 5px;
  margin-right: 5px;
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

/*
 * 🔴 2026-10-22：移动端（≤768px）必须改成**上下堆叠**，不能沿用两栏。
 *
 * <p>原来只有 1180px 一个断点，右侧列写死 min 420px —— 375 视口下
 * grid 撑出 420px 宽的 .fs-right，整个右栏（含风险评估）被推出屏幕右侧，
 * 表现为「风险提示看不见 / 横向溢出」。
 *
 * <p>移动端的顺序：**先看风险，再选地**（风险决定要不要继续），
 * 所以用 grid-template-areas 把右栏排到上面。
 */
@media (max-width: 768px) {
  .fs-page {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: auto minmax(0, 1fr);
    overflow-y: auto;
  }
  .fs-left { padding: 14px 12px 18px; overflow: visible; }
  .fs-right {
    border-left: none;
    border-top: 1px solid rgba(15, 23, 42, 0.07);
    overflow: visible;
  }
  .rp-scroll { overflow: visible; }
  .rp-head { padding: 14px 14px 10px; }
  .rp-card { padding: 10px 12px; }
  .rp-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .fs-title h2 { font-size: 18px; }
  .fs-steps { flex-wrap: wrap; }
  /* 🔴 2026-10-23：标题被步骤条挤成竖排（一字一行）。
     根因：.fs-head 是 flex + space-between，而 .fs-steps 是 flex:none（不收缩），
     4 个步骤 chip 占了整行宽度，把 .fs-title 压到只剩几十 px → 中文自动换行成竖排。
     修法：窄屏改成上下堆叠，标题独占一行。 */
  .fs-head {
    flex-direction: column;
    gap: 10px;
  }
  .fs-title { width: 100%; min-width: 0; }
  .fs-title h2 { white-space: nowrap; }
  .fs-steps { width: 100%; }
}

/* 超窄屏（iPhone SE 一类）：指标压成 1 列，避免文字挤成两行 */
@media (max-width: 400px) {
  .rp-metrics { grid-template-columns: minmax(0, 1fr); }
  .rp-level { flex-wrap: wrap; gap: 8px; }
}
</style>
