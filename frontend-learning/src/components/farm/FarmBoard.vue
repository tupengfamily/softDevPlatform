<template>
  <div class="fb-mask" @click.self="$emit('close')">
    <div class="fb-panel">
      <header class="fb-head">
        <div class="fb-title">
          <h2>农场看板</h2>
          <p>
            共 <b>{{ summary.farmCount }}</b> 个农场 ·
            <b>{{ summary.plotCount }}</b> 块地 ·
            生长中 <b class="grow">{{ summary.growingCount }}</b> ·
            可收割 <b class="ready">{{ summary.readyCount }}</b>
          </p>
        </div>
        <div class="fb-actions">
          <span class="fb-today">{{ board.today }}</span>
          <button class="fb-close" @click="$emit('close')">×</button>
        </div>
      </header>

      <div v-if="loading" class="fb-loading">正在汇总地块状态…</div>

      <div v-else class="fb-body">
        <div v-if="!farms.length && !unassigned.length" class="fb-none">
          <p>还没有农场。</p>
          <span>打开「中国地图」，在区县上选点建立你的第一个农场。</span>
        </div>

        <!-- 农场分组 -->
        <section v-for="f in farms" :key="f.farmId" class="fb-farm">
          <header class="ff-head" @click="toggle(f.farmId)">
            <span class="ff-caret" :class="{ open: isOpen(f.farmId) }">▸</span>
            <div class="ff-main">
              <b>{{ f.name }}</b>
              <span class="ff-loc">{{ f.location }}</span>
            </div>
            <span class="ff-tag zone">{{ f.zoneName }}</span>
            <span class="ff-tag">{{ f.plotCount }} 块地</span>
            <span v-if="f.readyCount" class="ff-tag ready">可收 {{ f.readyCount }}</span>
            <span class="ff-crops">{{ (f.crops || []).join(' · ') || '—' }}</span>
            <button class="ff-enter" @click.stop="enterFarm(f)">进入农场</button>
          </header>

          <div v-show="isOpen(f.farmId)" class="ff-body">
            <div v-if="!f.plots.length" class="ff-empty">
              这个农场还没有地块。进入农场后开垦第一块地。
            </div>

            <div v-else class="fb-grid">
              <div class="fb-row fb-sub">
                <span class="c-idx">#</span>
                <span class="c-name">地块</span>
                <span class="c-crop">作物</span>
                <span class="c-stage">阶段</span>
                <span class="c-dvs">DVS</span>
                <span class="c-season">季</span>
                <span class="c-ops">操作</span>
              </div>
              <div
                v-for="p in f.plots"
                :key="p.slot"
                class="fb-row"
                :class="{ empty: !p.chosen, ready: p.chosen && isHarvestable(p) }"
              >
                <span class="c-idx">
                  <i class="idx-dot" :class="dotClass(p)"></i>{{ p.slot + 1 }}
                </span>
                <span class="c-name">
                  <button class="lnk" @click="$emit('select', p.slot)">
                    {{ p.fieldName || ('第 ' + (p.slot + 1) + ' 号田') }}
                  </button>
                </span>
                <span class="c-crop">{{ shortCrop(p.cropName) }}</span>
                <span class="c-stage">
                  <span v-if="!p.chosen && p.awaitingSowing" class="tag tag-await">待播种</span>
                  <span v-else-if="!p.chosen" class="tag tag-empty">待开垦</span>
                  <span v-else class="tag" :class="stageTag(p)">{{ p.stage }}</span>
                </span>
                <span class="c-dvs mono">{{ p.chosen ? p.dvs.toFixed(2) : '—' }}</span>
                <span class="c-season">{{ p.chosen ? p.seasons : (p.seasons || '—') }}</span>
                <span class="c-ops">
                  <template v-if="p.chosen">
                    <button class="mini" :disabled="busy" @click="$emit('action', p.slot, 'IRRIGATE')">
                      浇水
                    </button>
                    <button
                      class="mini primary"
                      :disabled="busy || !isHarvestable(p)"
                      @click="$emit('harvest', p.slot)"
                    >收割</button>
                  </template>
                  <button class="mini ghost" @click="$emit('select', p.slot)">
                    {{ p.chosen ? '进入' : (p.awaitingSowing ? '选种' : '开垦') }}
                  </button>
                </span>
              </div>
            </div>
          </div>
        </section>

        <!-- 未归属农场的历史地块 -->
        <section v-if="unassigned.length" class="fb-farm legacy">
          <header class="ff-head" @click="toggle('legacy')">
            <span class="ff-caret" :class="{ open: isOpen('legacy') }">▸</span>
            <div class="ff-main">
              <b>未归属农场的地块</b>
              <span class="ff-loc">v1 时期建的田，还没有地理归属</span>
            </div>
            <span class="ff-tag">{{ unassigned.length }} 块地</span>
          </header>
          <div v-show="isOpen('legacy')" class="ff-body">
            <div class="fb-grid">
              <div class="fb-row fb-sub">
                <span class="c-idx">#</span>
                <span class="c-name">地块</span>
                <span class="c-crop">作物</span>
                <span class="c-stage">阶段</span>
                <span class="c-dvs">DVS</span>
                <span class="c-season">季</span>
                <span class="c-ops">操作</span>
              </div>
              <div
                v-for="p in unassigned"
                :key="p.slot"
                class="fb-row"
                :class="{ empty: !p.chosen, ready: p.chosen && isHarvestable(p) }"
              >
                <span class="c-idx">
                  <i class="idx-dot" :class="dotClass(p)"></i>{{ p.slot + 1 }}
                </span>
                <span class="c-name">
                  <button class="lnk" @click="$emit('select', p.slot)">
                    {{ p.fieldName || ('第 ' + (p.slot + 1) + ' 号田') }}
                  </button>
                </span>
                <span class="c-crop">{{ shortCrop(p.cropName) }}</span>
                <span class="c-stage">
                  <span v-if="!p.chosen && p.awaitingSowing" class="tag tag-await">待播种</span>
                  <span v-else-if="!p.chosen" class="tag tag-empty">待开垦</span>
                  <span v-else class="tag" :class="stageTag(p)">{{ p.stage }}</span>
                </span>
                <span class="c-dvs mono">{{ p.chosen ? p.dvs.toFixed(2) : '—' }}</span>
                <span class="c-season">{{ p.chosen ? p.seasons : (p.seasons || '—') }}</span>
                <span class="c-ops">
                  <button class="mini ghost" @click="$emit('select', p.slot)">
                    {{ p.chosen ? '进入' : (p.awaitingSowing ? '选种' : '开垦') }}
                  </button>
                </span>
              </div>
            </div>
          </div>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { getFarmBoard } from '../../api/farm'
import { isHarvestable } from '../../utils/farmLabels'
// 🔴 2026-10-28：作物常量与派生函数收进 utils/farmCrops.js（唯一实现）。
import { shortCrop as shortCropRaw, plotDotState } from '../../utils/farmCrops'

defineProps({
  busy: { type: Boolean, default: false }
})
const emit = defineEmits(['close', 'select', 'action', 'harvest'])

const loading = ref(true)
const board = reactive({ farms: [], unassigned: [], summary: {}, today: '' })
const farms = ref([])
const unassigned = ref([])
const summary = ref({ farmCount: 0, plotCount: 0, growingCount: 0, readyCount: 0 })
const openSet = reactive(new Set())

function isOpen(key) {
  return openSet.has(key)
}
function toggle(key) {
  if (openSet.has(key)) openSet.delete(key)
  else openSet.add(key)
}

/**
 * 作物短名（去括号注释）。
 *
 * <p>🔴 2026-10-28：实现收进 {@code utils/farmCrops.js} 的 {@code shortCrop}
 * （原来有 5 份拷贝，兜底值各不相同：{@code ''} / {@code '—'} / {@code '空地'}）。
 */
function shortCrop(name) {
  return shortCropRaw(name, '—')
}

/**
 * 地块状态分档 → CSS 类名。
 *
 * <p>🔴 2026-10-28：判定收进 {@code utils/farmCrops.js} 的 {@code plotDotState}
 * （与 FarmWorldMap / FarmOverviewPanel 同一套口径），这里只做类名映射。
 *
 * <p>⚠️ 原来这个函数<b>少了 {@code sown} 档</b>（已播种未出苗）：
 * 地图上显示蓝色小点，看板上却是灰色 —— 同一块地两个界面两个颜色。
 * 现在补上 {@code d-sown}（样式见下方 CSS）。
 */
function dotClass(p) {
  const s = plotDotState(p)
  if (s === 'idle') return 'd-empty'
  if (s === 'ready') return 'd-ready'
  if (s === 'grow') return 'd-grow'
  return 'd-sown'
}

function stageTag(p) {
  const s = String(p.stage || '')
  if (isHarvestable(p)) return 'tag-ripe'
  if (/苗|播种|出苗|返青|分蘖/.test(s)) return 'tag-young'
  return 'tag-mid'
}

function enterFarm(f) {
  const plots = f.plots || []
  const target = plots.find((p) => p.chosen) || plots[0]
  if (!target) {
    ElMessage.info('这个农场还没有地块')
    return
  }
  emit('select', target.slot)
}

async function load() {
  loading.value = true
  try {
    const res = await getFarmBoard()
    const d = res?.data || {}
    board.farms = d.farms || []
    board.unassigned = d.unassigned || []
    board.summary = d.summary || {}
    board.today = d.today || ''
    farms.value = board.farms
    unassigned.value = board.unassigned
    summary.value = {
      farmCount: board.summary.farmCount || 0,
      plotCount: board.summary.plotCount || 0,
      growingCount: board.summary.growingCount || 0,
      readyCount: board.summary.readyCount || 0
    }
    // 首次打开全部展开：农场数量通常不多，折叠反而多点一次
    if (!openSet.size) {
      for (const f of farms.value) openSet.add(f.farmId)
      if (unassigned.value.length) openSet.add('legacy')
    }
  } catch (e) {
    ElMessage.error(e?.message || '看板加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
defineExpose({ reload: load })
</script>

<style scoped>
.fb-mask {
  position: fixed;
  inset: 0;
  z-index: 55;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 22px;
  background: rgba(8, 14, 10, 0.62);
  backdrop-filter: blur(3px);
}
.fb-panel {
  display: flex;
  flex-direction: column;
  width: min(1240px, 100%);
  max-height: 100%;
  border-radius: 14px;
  background: #fbfdf9;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.35);
  overflow: hidden;
}

.fb-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  padding: 16px 20px 13px;
  border-bottom: 1px solid #e6ece1;
  background: linear-gradient(180deg, #f5f9f1, #fbfdf9);
}
.fb-title h2 { margin: 0 0 4px; font-size: 17px; color: #22301e; }
.fb-title p { margin: 0; font-size: 12px; color: #77836f; }
.fb-title b { color: #2f6b3d; font-variant-numeric: tabular-nums; }
.fb-title b.grow { color: #4c8a3a; }
.fb-title b.ready { color: #b8860b; }
.fb-actions { display: flex; align-items: center; gap: 12px; }
.fb-today { font-size: 11.5px; color: #9aa593; font-variant-numeric: tabular-nums; }
.fb-close {
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: 50%;
  background: #eef2ea;
  color: #68755f;
  font-size: 17px;
  line-height: 1;
  cursor: pointer;
}
.fb-close:hover { background: #e2e9dc; }

.fb-loading { padding: 40px; text-align: center; font-size: 13px; color: #88947f; }
.fb-body { flex: 1; overflow-y: auto; padding: 14px 18px 22px; }
.fb-body::-webkit-scrollbar { width: 7px; }
.fb-body::-webkit-scrollbar-thumb { background: #d6e0cf; border-radius: 4px; }

.fb-none { padding: 46px 20px; text-align: center; color: #8b9784; }
.fb-none p { margin: 0 0 8px; font-size: 14px; color: #4a5a44; }
.fb-none span { font-size: 12px; }

/* ---- 农场分组 ---- */
.fb-farm {
  margin-bottom: 12px;
  border: 1px solid #e3eade;
  border-radius: 10px;
  overflow: hidden;
  background: #fff;
}
.fb-farm.legacy { border-style: dashed; background: #fcfcfa; }

.ff-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 14px;
  background: #f7f9f4;
  border-bottom: 1px solid transparent;
  cursor: pointer;
  user-select: none;
}
.fb-farm .ff-head:hover { background: #f2f7ec; }
.ff-caret {
  font-size: 11px;
  color: #8b9784;
  transition: transform 0.16s;
  display: inline-block;
}
.ff-caret.open { transform: rotate(90deg); }
.ff-main { display: flex; flex-direction: column; min-width: 0; }
.ff-main b { font-size: 13.5px; color: #26331f; }
.ff-loc { font-size: 11px; color: #8b9784; margin-top: 1px; }
.ff-tag {
  padding: 2px 9px;
  border-radius: 9px;
  font-size: 11px;
  background: #eef2ea;
  color: #5f6c58;
  white-space: nowrap;
}
.ff-tag.zone { background: #e6f0de; color: #41722f; }
.ff-tag.ready { background: #fbf0d4; color: #8a6512; }
.ff-crops {
  margin-left: auto;
  font-size: 11.5px;
  color: #8b9784;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ff-enter {
  flex: 0 0 auto;
  height: 26px;
  padding: 0 12px;
  border: 1px solid #cfe0c4;
  border-radius: 13px;
  background: #fff;
  color: #3c6b2c;
  font-size: 11.5px;
  cursor: pointer;
}
.ff-enter:hover { background: #eff7ea; }

.ff-body { padding: 6px 8px 10px; }
.ff-empty { padding: 14px 12px; font-size: 12px; color: #94a08c; }

/* ---- 地块表 ---- */
.fb-grid { display: flex; flex-direction: column; font-size: 13px; }
.fb-row {
  display: grid;
  grid-template-columns: 52px minmax(150px, 340px) 130px 130px 82px 52px 236px;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border-bottom: 1px solid #f0f4ec;
  color: #2a3427;
}
.fb-row:last-child { border-bottom: 0; }
.fb-row.fb-sub {
  font-size: 11.5px;
  font-weight: 600;
  color: #8b9784;
  background: #fcfdfa;
  border-bottom: 1px solid #eef2ea;
}
.fb-row.empty { color: #97a291; }
.fb-row.ready { background: #fdf9ea; }
.fb-row:not(.fb-sub):hover { background: #f4f9ef; }
.c-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.c-ops { display: flex; gap: 5px; white-space: nowrap; }
.mono { font-variant-numeric: tabular-nums; }

.idx-dot {
  display: inline-block;
  width: 7px;
  height: 7px;
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: 1px;
}
.d-empty { background: #c3cbc0; }
/* 🔴 2026-10-28：补上「已播种未出苗」档（原来缺这一档，
   地图上是蓝点、看板上是灰点，同一块地两个界面两个颜色）。
   颜色与 FarmWorldMap 的 .pc-sown 保持一致（rgba(110,160,210,0.8)）。 */
.d-sown { background: rgba(110, 160, 210, 0.8); }
.d-grow { background: #63a34a; }
.d-ready { background: #d8a520; }

.lnk {
  border: 0;
  background: none;
  padding: 0;
  font: inherit;
  color: #2f6b3d;
  cursor: pointer;
  text-decoration: underline dotted;
}
.tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 9px;
  font-size: 11px;
  background: #eef2ea;
  color: #5d6a58;
}
.tag-young { background: #e8f2e0; color: #4c7a37; }
.tag-mid { background: #e4f0e6; color: #2f6b46; }
.tag-ripe { background: #fbf0d4; color: #8a6512; }
.tag-empty { background: #f0f1ee; color: #98a293; }
/* 待播种：等玩家决定下一季种什么（2026-10-22） */
.tag-await { background: #fdf3d9; color: #9a7414; }

.mini {
  height: 24px;
  padding: 0 10px;
  border: 1px solid #dde6d7;
  border-radius: 12px;
  background: #fff;
  color: #57634f;
  font-size: 11.5px;
  cursor: pointer;
}
.mini:hover:not(:disabled) { background: #f2f8ec; border-color: #c9dcbe; }
.mini.primary { border-color: #cfe0c4; color: #3c6b2c; background: #f3faea; }
.mini.ghost { border-color: transparent; color: #7d8a75; }
.mini:disabled { opacity: 0.45; cursor: not-allowed; }
</style>
