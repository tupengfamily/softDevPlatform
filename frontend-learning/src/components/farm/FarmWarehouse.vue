<template>
  <div class="wh-mask" @click.self="$emit('close')">
    <div class="wh-panel">
      <header class="wh-head">
        <div class="wh-title">
          <h2>{{ t('farm.warehouse.title') }}</h2>
          <p>
            <template v-if="tab === 'crop'">
              {{ t('farm.warehouse.totalKg') }} <b>{{ totalKg }}</b> kg ·
              {{ t('farm.warehouse.value') }} <b class="val">¥{{ totalValue }}</b> ·
              {{ t('farm.warehouse.income') }} <b class="income">¥{{ totalIncome }}</b>
              <span v-if="expiringCount" class="wh-warn">· {{ expiringCount }} {{ t('farm.warehouse.expiring') }}</span>
              <span v-if="rottenCount" class="wh-bad">· {{ rottenCount }} {{ t('farm.warehouse.rotten') }}</span>
            </template>
            <template v-else>
              {{ t('farm.warehouse.stockKinds') }} <b>{{ stockKinds }}</b> {{ t('farm.warehouse.kinds') }} ·
              {{ t('farm.warehouse.stockValue') }} <b class="val">¥{{ stockValue }}</b> ·
              {{ t('farm.warehouse.stockSpent') }} <b class="income">¥{{ stockSpent }}</b>
            </template>
          </p>
        </div>
        <div class="wh-actions">
          <span class="wh-date">{{ worldDate }}</span>
          <button class="wh-close" @click="$emit('close')">×</button>
        </div>
      </header>

      <!-- Tab：作物 / 农资 -->
      <nav class="wh-tabs">
        <button :class="{ on: tab === 'crop' }" @click="tab = 'crop'">
          🌾 {{ t('farm.warehouse.tabCrop') }}
          <i v-if="items.length">{{ items.length }}</i>
        </button>
        <button :class="{ on: tab === 'input' }" @click="tab = 'input'">
          🧪 {{ t('farm.warehouse.tabInput') }}
          <i v-if="stockItems.length">{{ stockItems.length }}</i>
        </button>
      </nav>

      <div v-if="loading" class="wh-loading">{{ t('farm.warehouse.loading') }}</div>

      <div v-else class="wh-body">
        <!-- ============ 作物区 ============ -->
        <template v-if="tab === 'crop'">
          <!-- 空仓 -->
          <div v-if="!allItems.length && !soldHistory.length" class="wh-none">
            <p>{{ t('farm.warehouse.empty') }}</p>
            <span>{{ t('farm.warehouse.emptyHint') }}</span>
          </div>

          <template v-else>
            <!-- 🔴 S25 三维筛选：农场 / 作物 / 地域 -->
            <section v-if="hasFacets" class="wh-sec">
              <header class="wh-sec-head">
                <b>{{ t('farm.warehouse.filter') }}</b>
                <span>{{ t('farm.warehouse.filterHint') }}</span>
              </header>
              <div class="wh-filters">
                <div class="wf-group">
                  <label>{{ t('farm.warehouse.byFarm') }}</label>
                  <div class="wf-chips">
                    <button
                      class="wf-chip"
                      :class="{ on: filters.farm === null }"
                      @click="filters.farm = null"
                    >{{ t('farm.warehouse.allFarms') }}</button>
                    <button
                      v-for="f in facets.farms"
                      :key="'farm-' + (f.id ?? 'none')"
                      class="wf-chip"
                      :class="{ on: filters.farm === f.id }"
                      @click="filters.farm = f.id"
                    >
                      {{ f.name }}<i>{{ f.count }}</i>
                    </button>
                  </div>
                </div>
                <div class="wf-group">
                  <label>{{ t('farm.warehouse.byCrop') }}</label>
                  <div class="wf-chips">
                    <button
                      class="wf-chip"
                      :class="{ on: filters.crop === null }"
                      @click="filters.crop = null"
                    >{{ t('farm.warehouse.allCrops') }}</button>
                    <button
                      v-for="c in facets.crops"
                      :key="'crop-' + c.id"
                      class="wf-chip"
                      :class="{ on: filters.crop === c.id }"
                      @click="filters.crop = c.id"
                    >
                      {{ c.name }}<i>{{ c.quantity }}kg</i>
                    </button>
                  </div>
                </div>
                <div class="wf-group">
                  <label>{{ t('farm.warehouse.byZone') }}</label>
                  <div class="wf-chips">
                    <button
                      class="wf-chip"
                      :class="{ on: filters.zone === null }"
                      @click="filters.zone = null"
                    >{{ t('farm.warehouse.allZones') }}</button>
                    <button
                      v-for="z in facets.zones"
                      :key="'zone-' + z.id"
                      class="wf-chip"
                      :class="{ on: filters.zone === z.id }"
                      @click="filters.zone = z.id"
                    >
                      {{ z.name }}<i>{{ z.quantity }}kg</i>
                    </button>
                  </div>
                </div>
                <button v-if="filterActive" class="wf-clear" @click="resetFilters">
                  {{ t('farm.warehouse.clearFilter') }}
                </button>
              </div>
              <p v-if="filterActive" class="wf-summary">
                {{ t('farm.warehouse.filtered', { n: items.length, total: allItems.length }) }}
              </p>
            </section>

            <!-- 存货清单 -->
            <section v-if="items.length" class="wh-sec">
              <header class="wh-sec-head">
                <b>{{ t('farm.warehouse.list') }}</b>
                <span>{{ t('farm.warehouse.listHint') }}</span>
              </header>

              <div class="wh-table">
                <div class="wh-row wh-th">
                  <span class="c-crop">{{ t('farm.warehouse.crop') }}</span>
                  <span class="c-farm">{{ t('farm.warehouse.farm') }}</span>
                  <span class="c-qty">{{ t('farm.warehouse.qty') }}</span>
                  <span class="c-grade">{{ t('farm.warehouse.grade') }}</span>
                  <span class="c-life">{{ t('farm.warehouse.life') }}</span>
                  <span class="c-price">{{ t('farm.warehouse.unitPrice') }}</span>
                  <span class="c-total">{{ t('farm.warehouse.total') }}</span>
                  <span class="c-op">{{ t('farm.warehouse.op') }}</span>
                </div>

                <div
                  v-for="it in items"
                  :key="it.id"
                  class="wh-row"
                  :class="{ expiring: it.expiring, rotten: !it.sellable }"
                >
                  <span class="c-crop">
                    <b>{{ it.cropName }}</b>
                    <em class="zone">{{ it.zoneName }}</em>
                    <i v-if="it.supplyNote" class="supply" :title="it.supplyNote">{{ t('farm.warehouse.pressured') }}</i>
                    <i v-if="it.residuePenalty" class="residue" :title="t('farm.warehouse.residueTip')">
                      {{ t('farm.warehouse.residue') }}
                    </i>
                  </span>
                  <span class="c-farm">{{ farmName(it.farmId) }}</span>
                  <span class="c-qty">{{ it.quantity }} kg</span>
                  <span class="c-grade">
                    <i class="g-tag" :class="'g-' + it.grade">{{ it.gradeName }}</i>
                  </span>
                  <span class="c-life">
                    <template v-if="it.sellable">
                      <b :class="{ warn: it.expiring }">{{ it.daysLeft }}</b> {{ t('farm.warehouse.daysLeft') }}
                      <em class="aged">{{ t('farm.warehouse.aged', { aged: it.agedDays, total: it.storageDays }) }}</em>
                    </template>
                    <b v-else class="dead">{{ t('farm.warehouse.spoiled') }}</b>
                  </span>
                  <span class="c-price">
                    ¥{{ it.unitPrice }}
                    <em class="per">元/kg</em>
                  </span>
                  <span class="c-total">¥{{ it.amount }}</span>
                  <span class="c-op">
                    <button
                      class="wh-sell"
                      :disabled="!it.sellable || busy"
                      @click="sell(it.cropId, it.quantity)"
                    >{{ t('farm.warehouse.sell') }}</button>
                  </span>
                </div>
              </div>
            </section>

            <!-- 无匹配 -->
            <div v-else-if="allItems.length" class="wh-none">
              <p>{{ t('farm.warehouse.noMatch') }}</p>
              <span>{{ t('farm.warehouse.noMatchHint') }}</span>
            </div>

            <!-- 合计 -->
            <div v-if="items.length" class="wh-sum">
              <span>{{ t('farm.warehouse.sellableTotal') }} <b>¥{{ totalValue }}</b></span>
              <button class="wh-sell-all" :disabled="!sellableCount || busy" @click="sell(null, null)">
                {{ t('farm.warehouse.sellAll') }}
              </button>
            </div>

            <!-- 已变质待清理 -->
            <div v-if="rottenCount" class="wh-rotten-tip">
              {{ t('farm.warehouse.rottenTip', { kg: rottenKg, n: rottenCount }) }}
            </div>

            <!-- 仓储期规则 -->
            <section v-if="storageRules.length" class="wh-sec">
              <header class="wh-sec-head">
                <b>{{ t('farm.warehouse.rules') }}</b>
                <span>{{ t('farm.warehouse.rulesHint') }}</span>
              </header>
              <div class="wh-rules">
                <div v-for="r in storageRules" :key="r.cropId" class="wh-rule">
                  <b>{{ r.cropName }}</b>
                  <span class="rd">{{ t('farm.warehouse.storageDays', { d: r.storageDays }) }}</span>
                  <em v-if="r.perishable" class="perishable">{{ t('farm.warehouse.perishable') }}</em>
                  <span class="bp">{{ t('farm.warehouse.basePrice', { p: r.basePrice }) }}</span>
                </div>
              </div>
            </section>

            <!-- 变卖记录 -->
            <section v-if="soldHistory.length" class="wh-sec">
              <header class="wh-sec-head">
                <b>{{ t('farm.warehouse.history') }}</b>
                <span>{{ t('farm.warehouse.historyCount', { n: soldHistory.length }) }}</span>
              </header>
              <div class="wh-history">
                <div v-for="s in soldHistory" :key="s.id" class="wh-hrow">
                  <span class="h-crop">{{ s.cropName }}</span>
                  <span class="h-qty">{{ s.quantity }} kg</span>
                  <span class="h-price">¥{{ s.soldPrice }}/kg</span>
                  <span class="h-amt">¥{{ s.soldAmount }}</span>
                  <span class="h-date">{{ s.soldDate }}</span>
                </div>
              </div>
            </section>
          </template>
        </template>

        <!-- ============ 农资区 ============ -->
        <template v-else>
          <div v-if="!stockItems.length" class="wh-none">
            <p>{{ t('farm.warehouse.stockEmpty') }}</p>
            <span>{{ t('farm.warehouse.stockEmptyHint') }}</span>
          </div>

          <template v-else>
            <!-- 农资筛选 -->
            <section class="wh-sec">
              <header class="wh-sec-head">
                <b>{{ t('farm.warehouse.stockList') }}</b>
                <span>{{ t('farm.warehouse.stockListHint') }}</span>
              </header>
              <div class="wh-filters">
                <div class="wf-group">
                  <label>{{ t('farm.warehouse.byKind') }}</label>
                  <div class="wf-chips">
                    <button class="wf-chip" :class="{ on: kind === '' }" @click="kind = ''">
                      {{ t('farm.warehouse.allKinds') }}
                    </button>
                    <button class="wf-chip" :class="{ on: kind === 'FERT' }" @click="kind = 'FERT'">
                      {{ t('farm.warehouse.kindFert') }}
                    </button>
                    <button class="wf-chip" :class="{ on: kind === 'PEST' }" @click="kind = 'PEST'">
                      {{ t('farm.warehouse.kindPest') }}
                    </button>
                    <button class="wf-chip" :class="{ on: kind === 'HERB' }" @click="kind = 'HERB'">
                      {{ t('farm.warehouse.kindHerb') }}
                    </button>
                  </div>
                </div>
              </div>
            </section>

            <!-- 农资清单 -->
            <section class="wh-sec">
              <div class="wh-table">
                <div class="wh-row wh-th stock-th">
                  <span class="c-crop">{{ t('farm.warehouse.item') }}</span>
                  <span class="c-qty">{{ t('farm.warehouse.stockQty') }}</span>
                  <span class="c-grade">{{ t('farm.warehouse.doses') }}</span>
                  <span class="c-life">{{ t('farm.warehouse.total') }}</span>
                  <span class="c-op">{{ t('farm.warehouse.op') }}</span>
                </div>

                <div v-for="it in stockItems" :key="it.id" class="wh-row">
                  <span class="c-crop">
                    <b>{{ it.itemName }}</b>
                    <em class="zone" :class="'k-' + it.kind">{{ kindLabel(it.kind) }}</em>
                  </span>
                  <span class="c-qty">{{ it.quantity }} {{ it.unit }}</span>
                  <span class="c-grade">
                    <!-- 🔴 2026-10-28：种子不是「按剂量施用」，「还能施几遍」对它无意义。
                         改显示「可播 N 亩」——这才是玩家真正要看的（还能种几块地）。 -->
                    <b v-if="it.kind === 'SEED'" class="doses">
                      {{ t('farm.warehouse.coversMu', { mu: it.coversMu || 0 }) }}
                    </b>
                    <b v-else class="doses">{{ it.dosesLeft }} {{ t('farm.warehouse.doseTimes') }}</b>
                  </span>
                  <span class="c-life">
                    <b>¥{{ it.value }}</b>
                    <!-- 🔴 单价单位用「售卖单位」（¥/包、¥/份），不是库存单位（亩、次）——
                         两者不同，混用会显示成「单价 ¥28/亩」（实际是 ¥28/包，可播 5 亩）。 -->
                    <em class="aged">{{ t('farm.warehouse.unitPriceOf', { p: it.pricePerUnit, u: it.sellUnit || it.unit }) }}</em>
                  </span>
                  <span class="c-op">
                    <button class="wh-sell buy" :disabled="busy" @click="openBuy(it)">
                      {{ t('farm.warehouse.buy') }}
                    </button>
                  </span>
                </div>
              </div>
            </section>

            <p class="wh-note">{{ t('farm.warehouse.stockNote') }}</p>
          </template>
        </template>
      </div>

      <!-- 购买农资弹窗 -->
      <div v-if="buyTarget" class="wh-buy-mask" @click.self="buyTarget = null">
        <div class="wh-buy">
          <h3>{{ buyTarget.itemName }}</h3>
          <p class="bw-hint">{{ t('farm.warehouse.buyHint', { p: buyTarget.pricePerUnit, u: buyTarget.sellUnit || buyTarget.unit }) }}</p>
          <div class="bw-row">
            <el-input-number
              v-model="buyQty"
              :min="buyTarget.unit === 'kg' ? 0.1 : 1"
              :max="99999"
              :step="buyTarget.unit === 'kg' ? 0.1 : 10"
              :precision="buyTarget.unit === 'kg' ? 1 : 0"
              controls-position="right"
            />
            <!-- 🔴 2026-10-28：数量单位一律用「售卖单位」（水=份、种子=包）——
                 库存单位（次、亩）与售卖单位不同，混用会把价格显示错 N 倍。 -->
            <span class="bw-unit">{{ buyTarget.sellUnit || buyTarget.unit }}</span>
            <span class="bw-cost">{{ t('farm.warehouse.total') }} ¥{{ buyCost }}</span>
          </div>
          <p v-if="buyTarget.note" class="bw-note">{{ buyTarget.note }}</p>
          <div class="bw-ops">
            <button class="bw-cancel" @click="buyTarget = null">{{ t('common.cancel') }}</button>
            <button class="bw-ok" :disabled="busy" @click="doBuy">{{ t('farm.warehouse.buy') }}</button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { getFarmWarehouse, sellFarmWarehouse, buyFarmStock } from '../../api/farm'

const emit = defineEmits(['close', 'sold'])
const props = defineProps({
  busy: { type: Boolean, default: false }
})
const { t } = useI18n()

const loading = ref(true)
const busy = ref(false)
const tab = ref('crop')
/** 农资类别筛选（'' = 全部） */
const kind = ref('')
/** 购买弹窗的目标农资 */
const buyTarget = ref(null)
const buyQty = ref(1)

const data = reactive({
  worldDate: '',
  items: [],
  totalValue: 0,
  totalKg: 0,
  expiringCount: 0,
  rottenCount: 0,
  rottenKg: 0,
  totalIncome: 0,
  soldHistory: [],
  storageRules: [],
  facets: { farms: [], crops: [], zones: [] },
  inputs: { items: [], totalValue: 0, kindCount: 0, totalCost: 0 }
})

/** 🔴 S25 三维筛选：农场 / 作物 / 地域 */
const filters = reactive({
  farm: null,
  crop: null,
  zone: null
})

const allItems = computed(() => data.items || [])
const facets = computed(() => data.facets || { farms: [], crops: [], zones: [] })
const hasFacets = computed(
  () => (facets.value.farms?.length || 0) + (facets.value.crops?.length || 0) + (facets.value.zones?.length || 0) > 0
)

/**
 * 筛选后的存货清单。
 *
 * <p>三个维度是**与**关系（农场 AND 作物 AND 地域），
 * 同一个筛选器可以同时命中「江南农场的番茄」+「产自华南的番茄」——
 * 这正是玩家想表达的「我要看江南那块地里的、但其实是华南运来的番茄」。
 */
const items = computed(() => {
  const f = filters
  return allItems.value.filter((it) => {
    if (f.farm !== null) {
      // 未归属农场用 null 表示；注意 undefined（老数据无 farm_id）也算未归属
      const itFarm = it.farmId ?? null
      if (itFarm !== f.farm) return false
    }
    if (f.crop !== null && it.cropId !== f.crop) return false
    if (f.zone !== null && it.zoneId !== f.zone) return false
    return true
  })
})

const filterActive = computed(
  () => filters.farm !== null || filters.crop !== null || filters.zone !== null
)

function resetFilters() {
  filters.farm = null
  filters.crop = null
  filters.zone = null
}

const totalValue = computed(() =>
  items.value.reduce((s, it) => s + (it.sellable ? it.amount : 0), 0)
)
const totalKg = computed(() => items.value.reduce((s, it) => s + it.quantity, 0))
const expiringCount = computed(() => items.value.filter((it) => it.expiring).length)
const rottenCount = computed(() => items.value.filter((it) => !it.sellable).length)
const rottenKg = computed(() =>
  items.value.filter((it) => !it.sellable).reduce((s, it) => s + it.quantity, 0)
)
const totalIncome = computed(() => data.totalIncome || 0)
const soldHistory = computed(() => data.soldHistory || [])
const storageRules = computed(() => data.storageRules || [])
const sellableCount = computed(() => items.value.filter((i) => i.sellable).length)

const stockItems = computed(() => {
  const list = data.inputs?.items || []
  if (!kind.value) return list
  return list.filter((it) => it.kind === kind.value)
})
const stockKinds = computed(() => data.inputs?.kindCount || 0)
const stockValue = computed(() => data.inputs?.totalValue || 0)
const stockSpent = computed(() => data.inputs?.totalCost || 0)

const buyCost = computed(() => {
  if (!buyTarget.value) return 0
  // 🔴 2026-10-28：数量单位已统一为「售卖单位」，直接单价 × 数量即可。
  // （原先种子按「亩」计价会贵 N 倍 —— ¥28/包被当成 ¥28/亩。）
  return Math.round((buyTarget.value.pricePerUnit || 0) * (buyQty.value || 0) * 100) / 100
})

/** 农场 id → 名称。 */
function farmName(farmId) {
  if (farmId === null || farmId === undefined) return t('farm.warehouse.unassigned')
  const f = facets.value.farms?.find((x) => x.id === farmId)
  return f ? f.name : t('farm.warehouse.unassigned')
}

function kindLabel(k) {
  return {
    FERT: t('farm.warehouse.kindFert'),
    PEST: t('farm.warehouse.kindPest'),
    HERB: t('farm.warehouse.kindHerb')
  }[k] || k
}

async function load() {
  loading.value = true
  try {
    const res = await getFarmWarehouse()
    // 🔴 响应拦截器 return data 返回的是后端统一包装 {code,message,data}
    const d = res?.data || {}
    Object.assign(data, {
      worldDate: d.worldDate || '',
      items: d.items || [],
      totalValue: d.totalValue || 0,
      totalKg: d.totalKg || 0,
      expiringCount: d.expiringCount || 0,
      rottenCount: d.rottenCount || 0,
      rottenKg: d.rottenKg || 0,
      totalIncome: d.totalIncome || 0,
      soldHistory: d.soldHistory || [],
      storageRules: d.storageRules || [],
      facets: d.facets || { farms: [], crops: [], zones: [] },
      inputs: d.inputs || { items: [], totalValue: 0, kindCount: 0, totalCost: 0 }
    })
  } catch (e) {
    ElMessage.error(t('farm.warehouse.loadFail'))
  } finally {
    loading.value = false
  }
}

/**
 * 变卖。
 * @param {string|null} cropId 只卖这个作物；null = 全部
 * @param {number|null} kg  卖多少；null = 该作物全部
 */
async function sell(cropId, kg) {
  busy.value = true
  try {
    const res = await sellFarmWarehouse(cropId ? { cropId, kg } : {})
    const d = res?.data || {}
    const expired = d.expiredDiscarded || 0
    if (d.soldKg > 0) {
      if (expired > 0) {
        ElMessage.warning(
          t('farm.warehouse.sellDoneWithDiscard', { kg: d.soldKg, income: d.income, n: expired })
        )
      } else {
        ElMessage.success(t('farm.warehouse.sellDone', { kg: d.soldKg, income: d.income }))
      }
    } else if (expired > 0) {
      // 全是变质货：清掉了但没卖成钱
      ElMessage.warning(t('farm.warehouse.sellAllDiscarded', { n: expired }))
    } else {
      ElMessage.info(d.message || t('farm.warehouse.sellNothing'))
    }
    await load()
    emit('sold', d)
  } catch (e) {
    ElMessage.error(e?.message || t('farm.warehouse.sellFail'))
  } finally {
    busy.value = false
  }
}

/**
 * 打开购买弹窗，默认量 = **够施 10 遍**所需的数量。
 *
 * <p>🔴 **不能用 `dosesLeft` 算**：`dosesLeft` 是「当前库存还能施几遍」
 * （= quantity / doseOpt），把它乘回 doseOpt 得到的还是现有库存，
 * 而「还能施几遍」和「每遍用量」是两个不同量纲，混用会算出 9999 这种
 * 荒谬的默认购买量。默认购买量必须由 `doseOpt`（每遍用量）直接推。
 *
 * <p>🔴 2026-10-28：种子不走「每遍用量」这条路（它不是按剂量施用的）。
 * 种子的默认量按「够种 10 亩」算，再换算成包数（1 包 = packMu 亩）。
 *
 * <p>🔴 <b>数量单位一律是「售卖单位」</b>（水=份、种子=包、化肥=kg、农药=g/ml）。
 * 后端 {@code /warehouse/stocks/buy} 的 qty 已统一为售卖单位
 * （原先是入库单位，两处语义不一致 —— 已修）。
 */
function openBuy(it) {
  buyTarget.value = it
  if (it.kind === 'SEED') {
    const packMu = it.packMu || 1
    // 够种 10 亩 → 需要几包（向上取整）
    buyQty.value = Math.max(1, Math.ceil(10 / packMu))
    return
  }
  if (it.kind === 'WATER') {
    // 水按「份」卖（1 份 = packSize 次灌溉）—— 默认买 2 份
    buyQty.value = 2
    return
  }
  const per = it.doseOpt || 0
  const unitIsKg = it.unit === 'kg'
  // 施 10 遍的量；向上取整到 kg→0.1 精度，g/ml→整数
  const raw = per * 10
  const qty = unitIsKg ? Math.max(0.1, Math.ceil(raw * 10) / 10) : Math.max(1, Math.ceil(raw))
  buyQty.value = qty
}

async function doBuy() {
  if (!buyTarget.value) return
  busy.value = true
  try {
    const res = await buyFarmStock({
      itemId: buyTarget.value.itemId,
      qty: buyQty.value
    })
    ElMessage.success(t('farm.warehouse.buyDone', {
      name: buyTarget.value.itemName,
      qty: buyQty.value,
      u: buyTarget.value.unit
    }))
    buyTarget.value = null
    await load()
  } catch (e) {
    ElMessage.error(e?.message || t('farm.warehouse.buyFail'))
  } finally {
    busy.value = false
  }
}

onMounted(load)
defineExpose({ load })
</script>

<style scoped>
.wh-mask {
  position: fixed;
  inset: 0;
  z-index: 58;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 22px;
  background: rgba(8, 14, 10, 0.62);
  backdrop-filter: blur(3px);
}
.wh-panel {
  display: flex;
  flex-direction: column;
  width: min(1080px, 100%);
  max-height: 100%;
  border-radius: 14px;
  background: #fbfdf9;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
  overflow: hidden;
}

/* ---------- 头部 ---------- */
.wh-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 16px 20px 12px;
  border-bottom: 1px solid #e6ece4;
  background: linear-gradient(180deg, #f6faf3, #fbfdf9);
}
.wh-title h2 { margin: 0 0 4px; font-size: 19px; font-weight: 700; color: #16241a; }
.wh-title p { margin: 0; font-size: 12.5px; color: #6b7a6d; }
.wh-title b { color: #24382a; }
.wh-title b.val { color: #2f7d32; font-size: 14px; }
.wh-title b.income { color: #b3761f; }
.wh-warn { color: #c2410c; font-weight: 600; }
.wh-bad { color: #991b1b; font-weight: 600; }
.wh-actions { display: flex; align-items: center; gap: 12px; flex: none; }
.wh-date { font-size: 12px; color: #8b9a8c; }
.wh-close {
  width: 28px; height: 28px; border: none; border-radius: 8px;
  background: #eef2ec; color: #5a6a5b; font-size: 18px; line-height: 1; cursor: pointer;
}
.wh-close:hover { background: #e2e8df; }

/* ---------- Tab ---------- */
.wh-tabs {
  display: flex;
  gap: 2px;
  padding: 0 20px;
  border-bottom: 1px solid #e6ece4;
  background: #fbfdf9;
}
.wh-tabs button {
  display: flex; align-items: center; gap: 5px;
  padding: 9px 14px;
  border: none; border-bottom: 2px solid transparent;
  background: transparent; color: #6b7a6d;
  font-size: 13px; font-weight: 600; cursor: pointer;
}
.wh-tabs button.on { color: #2f7d32; border-bottom-color: #3f8f43; }
.wh-tabs button i {
  font-style: normal; font-size: 10.5px;
  background: #eef2ec; color: #6b7a6d;
  border-radius: 8px; padding: 1px 6px;
}
.wh-tabs button.on i { background: #e2f2df; color: #2f7d32; }

.wh-loading { padding: 40px; text-align: center; color: #8b9a8c; font-size: 13px; }
.wh-none { padding: 40px 24px; text-align: center; }
.wh-none p { margin: 0 0 8px; font-size: 15px; color: #3d4d40; }
.wh-none span { font-size: 12.5px; color: #8b9a8c; line-height: 1.7; }

.wh-body { flex: 1; min-height: 0; overflow-y: auto; padding: 14px 20px 20px; }

/* ---------- 分区 ---------- */
.wh-sec { margin-bottom: 22px; }
.wh-sec:last-child { margin-bottom: 0; }
.wh-sec-head {
  display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap;
  margin-bottom: 9px;
}
.wh-sec-head b { font-size: 13.5px; color: #2a3b2d; }
.wh-sec-head span { font-size: 11.5px; color: #97a598; }

/* ---------- 筛选器 ---------- */
.wh-filters {
  display: flex; flex-direction: column; gap: 8px;
  padding: 10px 12px;
  background: #f4f8f1; border: 1px solid #e3eae0; border-radius: 9px;
}
.wf-group { display: flex; align-items: flex-start; gap: 9px; }
.wf-group label {
  flex: 0 0 52px; padding-top: 4px;
  font-size: 11.5px; font-weight: 600; color: #7d8b7e;
}
.wf-chips { display: flex; flex-wrap: wrap; gap: 5px; flex: 1; }
.wf-chip {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 3px 9px;
  border: 1px solid #dbe4d6; border-radius: 12px;
  background: #fff; color: #5a6a5b;
  font-size: 11.5px; cursor: pointer;
}
.wf-chip:hover { border-color: #a9c7a4; }
.wf-chip.on {
  background: #3f8f43; border-color: #3f8f43; color: #fff; font-weight: 600;
}
.wf-chip i {
  font-style: normal; font-size: 10px; opacity: 0.75;
  font-variant-numeric: tabular-nums;
}
.wf-clear {
  align-self: flex-start;
  padding: 3px 10px; border: 1px solid #dbe4d6; border-radius: 12px;
  background: #fff; color: #991b1b; font-size: 11.5px; cursor: pointer;
}
.wf-clear:hover { background: #fef2f2; }
.wf-summary {
  margin: 7px 0 0; font-size: 11.5px; color: #2f7d32; font-weight: 600;
}

/* ---------- 表格 ---------- */
.wh-table {
  border: 1px solid #e3eae0;
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.wh-row {
  display: grid;
  grid-template-columns: minmax(150px, 1.5fr) 88px 88px 84px 130px 96px 100px 76px;
  align-items: center;
  gap: 8px;
  padding: 9px 12px;
  border-bottom: 1px solid #f0f4ee;
  font-size: 12.5px;
  color: #334436;
}
.wh-row:last-child { border-bottom: none; }
.wh-th {
  background: #f4f8f1;
  font-size: 11px;
  font-weight: 600;
  color: #7d8b7e;
  letter-spacing: 0.3px;
}
.wh-row.expiring { background: #fffaf3; }
.wh-row.rotten { background: #fdf6f6; opacity: 0.85; }
/* 农资表少一列（没有品质/仓储期） */
.wh-row.stock-th, .wh-row:has(.doses) {
  grid-template-columns: minmax(150px, 1.5fr) 96px 92px 108px 76px;
}

.c-crop { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.c-crop b { color: #24382a; font-weight: 600; }
.c-crop em.zone {
  font-style: normal; font-size: 10.5px; color: #93a094;
  border: 1px solid #e0e7dc; border-radius: 4px; padding: 0 4px;
}
.c-crop em.zone.k-FERT { color: #2f7d32; border-color: #cbe3cb; background: #f2faf1; }
.c-crop em.zone.k-PEST { color: #b45309; border-color: #f5d9a8; background: #fffaef; }
.c-crop em.zone.k-HERB { color: #6366f1; border-color: #d5d7fb; background: #f6f7ff; }
.c-crop i.supply {
  font-style: normal; font-size: 10px; color: #b45309; font-weight: 600;
  background: #fef3c7; border-radius: 4px; padding: 1px 5px;
}
.c-crop i.residue {
  font-style: normal; font-size: 10px; color: #991b1b; font-weight: 600;
  background: #fee2e2; border-radius: 4px; padding: 1px 5px;
}
.c-farm { font-size: 11.5px; color: #7d8b7e; }
.c-qty { color: #4a5a4c; }
.c-grade .g-tag {
  display: inline-block; font-style: normal; font-size: 11px; font-weight: 600;
  border-radius: 5px; padding: 2px 7px;
}
.g-FRESH { background: #e7f4e8; color: #2f7d32; }
.g-DECAYING { background: #fef3c7; color: #b45309; }
.g-ROTTEN { background: #fee2e2; color: #991b1b; }
.c-grade b.doses { color: #2f7d32; font-size: 12.5px; }

.c-life b { color: #334436; }
.c-life b.warn { color: #c2410c; }
.c-life b.dead { color: #991b1b; }
.c-life em.aged {
  display: block; font-style: normal; font-size: 10px; color: #9aa79b; margin-top: 1px;
}
.c-price { color: #334436; font-weight: 600; }
.c-price em.per { font-style: normal; font-size: 10px; color: #9aa79b; font-weight: 400; }
.c-total { color: #2f7d32; font-weight: 700; }

.wh-sell {
  padding: 4px 12px; border: 1px solid #b8d4b4; border-radius: 6px;
  background: #f0f8ee; color: #2f7d32; font-size: 12px; cursor: pointer;
}
.wh-sell:hover:not(:disabled) { background: #e2f2df; }
.wh-sell:disabled { opacity: 0.4; cursor: not-allowed; }
.wh-sell.buy { border-color: #c7b98f; background: #fdf8ec; color: #8a6d1f; }
.wh-sell.buy:hover:not(:disabled) { background: #f8efd6; }

.wh-note {
  margin: 10px 0 0; font-size: 11.5px; color: #97a598; line-height: 1.7;
}

/* ---------- 合计 ---------- */
.wh-sum {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  margin-top: 9px; padding: 9px 12px;
  background: #f4f8f1; border-radius: 8px; font-size: 12.5px; color: #5a6a5b;
}
.wh-sum b { color: #2f7d32; font-size: 14px; }
.wh-sell-all {
  padding: 6px 16px; border: none; border-radius: 7px;
  background: #3f8f43; color: #fff; font-size: 12.5px; font-weight: 600; cursor: pointer;
}
.wh-sell-all:hover:not(:disabled) { background: #367c3a; }
.wh-sell-all:disabled { opacity: 0.4; cursor: not-allowed; }

/* ---------- 已变质提示 ---------- */
.wh-rotten-tip {
  margin-top: 9px; padding: 8px 12px;
  background: #fef2f2; border: 1px solid #fecaca; border-radius: 8px;
  color: #991b1b; font-size: 12px; line-height: 1.6;
}

/* ---------- 仓储期规则 ---------- */
.wh-rules { display: flex; flex-wrap: wrap; gap: 8px; }
.wh-rule {
  display: flex; align-items: center; gap: 7px;
  padding: 6px 10px; border: 1px solid #e3eae0; border-radius: 7px;
  background: #fff; font-size: 11.5px; color: #5a6a5b;
}
.wh-rule b { color: #334436; font-size: 12px; }
.wh-rule .rd { color: #2f7d32; font-weight: 600; }
.wh-rule em.perishable {
  font-style: normal; font-size: 10px; color: #c2410c;
  background: #ffedd5; border-radius: 4px; padding: 1px 5px;
}
.wh-rule .bp { color: #9aa79b; font-size: 10.5px; }

/* ---------- 变卖历史 ---------- */
.wh-history { border: 1px solid #e3eae0; border-radius: 10px; overflow: hidden; background: #fff; }
.wh-hrow {
  display: grid;
  grid-template-columns: 1fr 90px 100px 100px 100px;
  gap: 8px; align-items: center;
  padding: 7px 12px; border-bottom: 1px solid #f0f4ee;
  font-size: 12px; color: #4a5a4c;
}
.wh-hrow:last-child { border-bottom: none; }
.h-crop { font-weight: 600; color: #334436; }
.h-amt { color: #2f7d32; font-weight: 700; }
.h-date { color: #9aa79b; font-size: 11px; }

/* ---------- 购买弹窗 ---------- */
.wh-buy-mask {
  position: absolute; inset: 0; z-index: 10;
  display: flex; align-items: center; justify-content: center;
  background: rgba(8, 14, 10, 0.45);
}
.wh-buy {
  width: min(360px, calc(100% - 32px));
  padding: 18px 20px;
  border-radius: 12px;
  background: #fbfdf9;
  box-shadow: 0 18px 44px rgba(0, 0, 0, 0.3);
}
.wh-buy h3 { margin: 0 0 6px; font-size: 15px; color: #16241a; }
.bw-hint { margin: 0 0 12px; font-size: 12px; color: #6b7a6d; }
.bw-row { display: flex; align-items: center; gap: 8px; }
.bw-unit { font-size: 12.5px; color: #6b7a6d; }
.bw-cost { margin-left: auto; font-size: 13px; font-weight: 700; color: #b3761f; }
.bw-note {
  margin: 10px 0 0; font-size: 11.5px; color: #8b9a8c; line-height: 1.6;
}
.bw-ops { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
.bw-cancel, .bw-ok {
  padding: 6px 16px; border-radius: 7px; font-size: 12.5px; font-weight: 600; cursor: pointer;
}
.bw-cancel { border: 1px solid #dbe4d6; background: #fff; color: #5a6a5b; }
.bw-ok { border: none; background: #3f8f43; color: #fff; }
.bw-ok:disabled { opacity: 0.4; cursor: not-allowed; }

/* ---------- 响应式 ---------- */
@media (max-width: 1080px) {
  .wh-row {
    grid-template-columns: minmax(110px, 1.4fr) 74px 76px 74px 96px 86px 88px;
  }
  .wh-row .c-op { grid-column: 1 / -1; justify-content: flex-end; }
  .wh-th .c-op { display: none; }
}
@media (max-width: 900px) {
  /* 8 列放不下：农场列并入作物格里 */
  .wh-row { grid-template-columns: minmax(120px, 1.5fr) 74px 78px 100px 88px 88px; }
  .wh-th .c-farm { display: none; }
  .wh-row .c-farm {
    grid-column: 1; padding-left: 0;
    font-size: 10.5px; margin-top: -2px;
  }
  .wh-row .c-farm::before { content: "🏡 "; }
}
@media (max-width: 768px) {
  .wh-mask { padding: 0; }
  .wh-panel { width: 100%; height: 100%; max-height: 100%; border-radius: 0; }
  .wh-head { padding: 12px 14px 10px; }
  .wh-title h2 { font-size: 17px; }
  .wh-tabs { padding: 0 14px; }
  .wh-body { padding: 12px 14px 18px; }
  /* 窄屏改卡片式：网格太窄会挤成两行 */
  .wh-table { border: none; background: transparent; overflow: visible; }
  .wh-th { display: none; }
  .wh-row, .wh-row.stock-th {
    grid-template-columns: 1fr 1fr;
    gap: 5px 10px;
    padding: 10px 12px; margin-bottom: 8px;
    border: 1px solid #e3eae0; border-radius: 9px; background: #fff;
  }
  .wh-row .c-crop { grid-column: 1 / -1; }
  .wh-row .c-farm { grid-column: 1 / -1; }
  .wh-row .c-op { grid-column: 1 / -1; justify-content: flex-start; }
  .c-life em.aged { display: inline; margin-left: 4px; }
  .wh-hrow { grid-template-columns: 1fr 1fr; gap: 4px 10px; }
  .wh-hrow .h-date { grid-column: 1 / -1; }
  .wf-group { flex-direction: column; gap: 4px; }
  .wf-group label { flex: none; padding-top: 0; }
}
@media (max-width: 400px) {
  .wh-sum { flex-direction: column; align-items: stretch; }
  .wh-sell-all { width: 100%; }
}
</style>
