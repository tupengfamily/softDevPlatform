<template>
  <div class="sp-mask" @click.self="$emit('close')">
    <div class="sp-panel">
      <header class="sp-head">
        <div class="sp-title">
          <h2>{{ t('farm.shop.title') }}</h2>
          <p>
            {{ t('farm.shop.balance') }} <b class="val">¥{{ balance }}</b> ·
            {{ t('farm.shop.income') }} <b class="income">¥{{ totalIncome }}</b> ·
            {{ t('farm.shop.expense') }} <b class="expense">¥{{ totalExpense }}</b>
          </p>
        </div>
        <div class="sp-actions">
          <span class="sp-date">{{ worldDate }}</span>
          <button class="sp-close" @click="$emit('close')">×</button>
        </div>
      </header>

      <!-- Tab：种子 / 农具 / 农资 / 水资源 -->
      <nav class="sp-tabs">
        <button
          v-for="tb in tabs"
          :key="tb.key"
          :class="{ on: tab === tb.key }"
          @click="tab = tb.key"
        >
          {{ tb.icon }} {{ tb.label }}
          <i v-if="counts[tb.key]">{{ counts[tb.key] }}</i>
        </button>
      </nav>

      <div v-if="loading" class="sp-loading">{{ t('farm.shop.loading') }}</div>

      <div v-else class="sp-body">
        <!-- 空货架 -->
        <div v-if="!visibleItems.length" class="sp-none">
          <p>{{ t('farm.shop.empty') }}</p>
          <span>{{ t('farm.shop.emptyHint') }}</span>
        </div>

        <template v-else>
          <!-- 🔴 分类提示：每个 Tab 讲清楚这一类的玩法语义 -->
          <section class="sp-tip">
            <b>{{ tipTitle }}</b>
            <span>{{ tipText }}</span>
          </section>

          <div class="sp-list">
            <article
              v-for="it in visibleItems"
              :key="it.id"
              class="sp-card"
              :class="{ owned: it.alreadyOwned, poor: !it.affordable && !it.alreadyOwned }"
            >
              <header class="sc-head">
                <div class="sc-name">
                  <span class="sc-emoji">{{ emojiOf(it) }}</span>
                  <div>
                    <b>{{ it.name }}</b>
                    <small v-if="it.cropName">· {{ it.cropName }}</small>
                  </div>
                </div>
                <div class="sc-price">
                  <b>¥{{ it.price }}</b>
                  <small v-if="it.unit">/ {{ it.unit }}</small>
                </div>
              </header>

              <p class="sc-note">{{ it.note }}</p>

              <footer class="sc-foot">
                <div class="sc-meta">
                  <!-- 种子：可播多少亩 -->
                  <span v-if="it.kind === 'SEED'">{{ t('farm.shop.coversMu', { mu: it.coversMu }) }}</span>
                  <!-- 农具：效率加成 -->
                  <span v-else-if="it.kind === 'TOOL'">
                    {{ t('farm.shop.efficiencyUp', { pct: Math.round((it.efficiency || 0) * 100) }) }}
                  </span>
                  <!-- 水：一份含多少次 -->
                  <span v-else-if="it.kind === 'WATER'">
                    {{ t('farm.shop.packSize', { n: it.packSize }) }}
                  </span>
                  <!-- 农资：还能施几遍 -->
                  <span v-else>{{ t('farm.shop.dosesLeft', { n: it.doses || 0 }) }}</span>

                  <!-- 🔴 2026-10-28：种子的「已有 N」单位是**亩**不是「包」——
                       库存量记的是能播多少亩（1 包 = N 亩）。 -->
                  <span class="sc-owned" :class="{ zero: !it.owned }">
                    {{ t('farm.shop.ownedN', { n: it.owned || 0 }) }}
                    <em v-if="it.kind === 'SEED'" class="sc-unit">{{ it.stockUnit || '亩' }}</em>
                  </span>
                </div>

                <div class="sc-buy">
                  <template v-if="it.alreadyOwned">
                    <span class="sc-owned-tag">{{ t('farm.shop.owned') }}</span>
                  </template>
                  <template v-else>
                    <el-input-number
                      :model-value="qtyOf(it.id)"
                      @update:model-value="(v) => setQty(it.id, v)"
                      :min="1"
                      :max="99"
                      :step="1"
                      size="small"
                      controls-position="right"
                      class="sc-qty"
                    />
                    <button
                      class="sc-btn"
                      :disabled="!it.affordable || busy"
                      @click="buy(it)"
                    >
                      {{ it.affordable ? t('farm.shop.buy') : t('farm.shop.noMoney') }}
                    </button>
                  </template>
                </div>
              </footer>
            </article>
          </div>
        </template>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { getFarmShop, buyFarmShopItem } from '../../api/farm'

const emit = defineEmits(['close', 'bought'])
const { t } = useI18n()

const loading = ref(true)
const busy = ref(false)
const tab = ref('SEED')

const data = reactive({
  items: [],
  balance: 0,
  totalIncome: 0,
  totalExpense: 0,
  assetValue: 0,
  counts: {},
  worldDate: ''
})

/** 每件商品的购买数量（按 id 存）。 */
const qtyMap = reactive({})

const tabs = computed(() => [
  { key: 'SEED', icon: '🌱', label: t('farm.shop.tabSeed') },
  { key: 'TOOL', icon: '🚜', label: t('farm.shop.tabTool') },
  { key: 'INPUT', icon: '🧪', label: t('farm.shop.tabInput') },
  { key: 'WATER', icon: '💧', label: t('farm.shop.tabWater') }
])

const counts = computed(() => {
  const c = data.counts || {}
  return {
    SEED: c.SEED || 0,
    TOOL: c.TOOL || 0,
    INPUT: (c.FERT || 0) + (c.PEST || 0) + (c.HERB || 0),
    WATER: c.WATER || 0
  }
})

/**
 * 「INPUT」是前端聚合 Tab（化肥/农药/除草剂三类），
 * 后端仍按 FERT/PEST/HERB 分开返回，这里按 kind 过滤。
 */
const visibleItems = computed(() => {
  const list = data.items || []
  if (tab.value === 'INPUT') {
    return list.filter((it) => ['FERT', 'PEST', 'HERB'].includes(it.kind))
  }
  return list.filter((it) => it.kind === tab.value)
})

const balance = computed(() => data.balance || 0)
const totalIncome = computed(() => data.totalIncome || 0)
const totalExpense = computed(() => data.totalExpense || 0)
const worldDate = computed(() => data.worldDate || '')

const tipTitle = computed(() => ({
  SEED: t('farm.shop.tipSeed'),
  TOOL: t('farm.shop.tipTool'),
  INPUT: t('farm.shop.tipInput'),
  WATER: t('farm.shop.tipWater')
}[tab.value] || ''))

const tipText = computed(() => ({
  SEED: t('farm.shop.tipSeedText'),
  TOOL: t('farm.shop.tipToolText'),
  INPUT: t('farm.shop.tipInputText'),
  WATER: t('farm.shop.tipWaterText')
}[tab.value] || ''))

function emojiOf(it) {
  if (it.kind === 'SEED') return '🌱'
  if (it.kind === 'TOOL') return '🚜'
  if (it.kind === 'WATER') return '💧'
  if (it.kind === 'FERT') return '🧪'
  if (it.kind === 'HERB') return '🌿'
  return '🐛'
}

function qtyOf(id) {
  if (qtyMap[id] === undefined) qtyMap[id] = 1
  return qtyMap[id]
}

/**
 * 写入购买数量。
 *
 * <p>⚠️ 不能用 `v-model="qtyOf(it.id)"` —— v-model 需要**可写表达式**，
 * 函数返回值不是可写的，Vue 会直接抛编译错误。
 * 必须用 `:model-value` + `@update:model-value` 手动回写。
 */
function setQty(id, v) {
  qtyMap[id] = v
}

async function load() {
  loading.value = true
  try {
    const res = await getFarmShop()
    // 🔴 响应拦截器 return data 返回的是后端统一包装 {code,message,data}
    const d = res?.data || {}
    Object.assign(data, {
      items: d.items || [],
      balance: d.balance || 0,
      totalIncome: d.totalIncome || 0,
      totalExpense: d.totalExpense || 0,
      assetValue: d.assetValue || 0,
      counts: d.counts || {},
      worldDate: d.worldDate || ''
    })
  } catch (e) {
    ElMessage.error(t('farm.shop.loadFail'))
  } finally {
    loading.value = false
  }
}

async function buy(it) {
  busy.value = true
  try {
    const qty = qtyOf(it.id) || 1
    const res = await buyFarmShopItem({ itemId: it.id, qty })
    const d = res?.data || {}
    ElMessage.success(d.note || t('farm.shop.buyDone', { name: it.name, qty }))
    await load()
    emit('bought', d)
  } catch (e) {
    ElMessage.error(e?.message || t('farm.shop.buyFail'))
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sp-mask {
  position: fixed;
  inset: 0;
  background: rgba(8, 14, 10, 0.62);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2600;
  padding: 20px;
}

.sp-panel {
  width: min(880px, 100%);
  max-height: min(86vh, 820px);
  display: flex;
  flex-direction: column;
  background: linear-gradient(160deg, #16241c 0%, #0f1a14 100%);
  border: 1px solid rgba(126, 200, 140, 0.22);
  border-radius: 16px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.55);
  overflow: hidden;
  color: #d9e8dc;
}

.sp-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 16px 18px 12px;
  border-bottom: 1px solid rgba(126, 200, 140, 0.14);
}

.sp-title h2 {
  margin: 0;
  font-size: 17px;
  color: #eaf5ec;
}

.sp-title p {
  margin: 6px 0 0;
  font-size: 12.5px;
  color: #8fae98;
}

.sp-title .val { color: #ffd479; }
.sp-title .income { color: #7ee0a0; }
.sp-title .expense { color: #f0908a; }

.sp-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.sp-date {
  font-size: 11.5px;
  color: #7d9b86;
}

.sp-close {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  border: 1px solid rgba(126, 200, 140, 0.24);
  background: rgba(255, 255, 255, 0.04);
  color: #b9d3bf;
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
}

.sp-close:hover { background: rgba(126, 200, 140, 0.16); }

.sp-tabs {
  display: flex;
  gap: 6px;
  padding: 10px 18px 0;
  flex-wrap: wrap;
}

.sp-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(126, 200, 140, 0.18);
  background: rgba(255, 255, 255, 0.03);
  color: #9dbca6;
  font-size: 12.5px;
  cursor: pointer;
  transition: all 0.15s;
}

.sp-tabs button.on {
  background: rgba(126, 200, 140, 0.18);
  border-color: rgba(126, 200, 140, 0.5);
  color: #e6f5e9;
}

.sp-tabs button i {
  font-style: normal;
  font-size: 10.5px;
  padding: 0 5px;
  border-radius: 999px;
  background: rgba(126, 200, 140, 0.24);
  color: #cfe8d5;
}

.sp-body {
  flex: 1;
  overflow-y: auto;
  padding: 12px 18px 18px;
}

.sp-loading,
.sp-none {
  padding: 34px 12px;
  text-align: center;
  color: #8fae98;
  font-size: 13px;
}

.sp-none p { margin: 0 0 6px; color: #cfdfd3; }

.sp-tip {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 12px;
  margin-bottom: 12px;
  border-radius: 10px;
  background: rgba(126, 200, 140, 0.07);
  border-left: 3px solid rgba(126, 200, 140, 0.5);
}

.sp-tip b { font-size: 12.5px; color: #d8ecd9; }
.sp-tip span { font-size: 11.5px; color: #90b098; line-height: 1.55; }

.sp-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
}

.sp-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 12px;
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.035);
  border: 1px solid rgba(126, 200, 140, 0.13);
  transition: border-color 0.15s;
}

.sp-card:hover { border-color: rgba(126, 200, 140, 0.34); }
.sp-card.owned { border-color: rgba(126, 220, 160, 0.42); }
.sp-card.poor { opacity: 0.62; }

.sc-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
}

.sc-name {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.sc-emoji { font-size: 17px; }

.sc-name b {
  font-size: 13px;
  color: #e8f4ea;
  display: block;
  line-height: 1.35;
}

.sc-name small {
  font-size: 11px;
  color: #8fae98;
}

.sc-price {
  text-align: right;
  white-space: nowrap;
}

.sc-price b { font-size: 14px; color: #ffd479; }
.sc-price small { font-size: 10.5px; color: #8fae98; margin-left: 2px; }

.sc-note {
  margin: 0;
  font-size: 11.5px;
  line-height: 1.6;
  color: #90ae98;
  flex: 1;
}

.sc-foot {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
}

.sc-meta {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 11px;
  color: #a7c4ad;
}

.sc-owned { color: #7ee0a0; }
/* 种子库存单位是「亩」，跟着「已有 N」显示 */
.sc-unit { font-style: normal; font-size: 11px; opacity: 0.8; margin-left: 1px; }
.sc-owned.zero { color: #7d9b86; }

.sc-buy {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sc-qty { width: 96px; }

.sc-btn {
  padding: 5px 12px;
  border-radius: 8px;
  border: 1px solid rgba(126, 200, 140, 0.4);
  background: rgba(126, 200, 140, 0.16);
  color: #dff3e2;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  transition: all 0.15s;
}

.sc-btn:hover:not(:disabled) { background: rgba(126, 200, 140, 0.3); }
.sc-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
  border-color: rgba(160, 160, 160, 0.3);
  background: rgba(255, 255, 255, 0.04);
}

.sc-owned-tag {
  font-size: 11.5px;
  padding: 4px 10px;
  border-radius: 8px;
  background: rgba(126, 220, 160, 0.18);
  color: #8fe6ac;
  border: 1px solid rgba(126, 220, 160, 0.34);
}

@media (max-width: 768px) {
  .sp-mask { padding: 0; align-items: stretch; }
  .sp-panel { border-radius: 0; max-height: 100vh; height: 100%; }
  .sp-list { grid-template-columns: 1fr; }
  .sp-head { padding: 12px 14px 10px; }
  .sp-tabs { padding: 8px 14px 0; }
  .sp-body { padding: 10px 14px 16px; }
}
</style>
