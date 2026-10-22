<template>
  <aside class="ov">
    <div class="ov-head">
      <div class="oh-top">
        <h3>{{ scope.title }}</h3>
        <span v-if="scope.badge" class="oh-badge">{{ scope.badge }}</span>
      </div>
      <p class="oh-sub">{{ subtitle }}</p>
      <!-- 范围切换：全部农场 / 跟随镜头 -->
      <div class="ov-scope">
        <button
          class="os-btn"
          :class="{ on: scopeMode === 'all' }"
          @click="$emit('scope-mode', 'all')"
        >全部农场</button>
        <button
          class="os-btn"
          :class="{ on: scopeMode === 'auto' }"
          @click="$emit('scope-mode', 'auto')"
        >跟随镜头</button>
      </div>
    </div>

    <div class="ov-scroll">
      <!-- 空态：还没建过农场 -->
      <div v-if="!farms.length && !(scope.type === 'all' && unassigned.length)" class="ov-empty">
        <div class="oe-icon">◈</div>
        <p>{{ scope.type === 'all' ? '还没有农场' : '这个范围里还没有农场' }}</p>
        <span>
          {{ scope.type === 'all'
            ? '在地图上点一个区县，就能在那里建农场；一个区县一个农场，最多 20 个。'
            : '点地图上的区县可以在这里建农场。' }}
        </span>
        <span v-if="unassigned.length && scope.type !== 'all'" class="oe-more">
          另有 {{ unassigned.length }} 块田尚未归属任何农场，返回全国视图可以看到它们。
        </span>
      </div>

      <template v-else>
        <!-- 总览数字 -->
        <div class="ov-stats">
          <div class="st">
            <b>{{ farms.length }}</b>
            <span>农场</span>
          </div>
          <div class="st">
            <b>{{ totals.plots }}</b>
            <span>地块</span>
          </div>
          <div class="st">
            <b class="grow">{{ totals.growing }}</b>
            <span>生长中</span>
          </div>
          <div class="st">
            <b :class="{ ready: totals.ready > 0 }">{{ totals.ready }}</b>
            <span>可收割</span>
          </div>
        </div>

        <!-- 作物分布 -->
        <div v-if="cropRows.length" class="ov-sec">
          <div class="os-t">
            作物分布
            <span class="os-note">{{ cropRows.length }} 种</span>
          </div>
          <div class="crop-chips">
            <span v-for="c in cropRows" :key="c.name" class="cc-pill">
              <i :style="{ background: c.color }"></i>
              <span class="cp-name">{{ c.name }}</span>
              <b class="mono">{{ c.count }}</b>
            </span>
          </div>
        </div>

        <!-- 省视图：本省各区县的主栽分布（玩家自己的作物之外，看看这片地适合什么） -->
        <div v-if="regionCrops.length" class="ov-sec">
          <div class="os-t">
            本省区县主栽
            <span class="os-note">{{ regionCrops.reduce((a, c) => a + c.count, 0) }} 个区县</span>
          </div>
          <div class="crop-chips">
            <span v-for="c in regionCrops" :key="c.key" class="cc-pill">
              <i :style="{ background: c.color }"></i>
              <span class="cp-name">{{ c.name }}</span>
              <b class="mono">{{ c.count }}</b>
            </span>
          </div>
        </div>

        <!-- 农场列表：卡片形式，单击定位到地图，双击进详情 -->
        <div class="ov-sec">
          <div class="os-t">
            农场
            <span class="os-note">{{ farms.length }} 个</span>
          </div>
          <div class="farm-cards">
            <div
              v-for="f in farms"
              :key="f.farmId"
              class="fc"
              :class="{ on: activeFarmId === f.farmId }"
              role="button"
              tabindex="0"
              @click="onCardClick(f)"
              @dblclick.stop="onCardDblClick(f)"
              @keydown.enter="onCardClick(f)"
              @keydown.space.prevent="onCardClick(f)"
            >
              <div class="fc-row">
                <span class="fc-name">{{ f.name }}</span>
                <span v-if="f.readyCount" class="fc-flag">可收 {{ f.readyCount }}</span>
                <span class="fc-n mono">{{ f.plotCount }} 块</span>
              </div>
              <div class="fc-loc">{{ f.location || '—' }}</div>
              <div class="fc-crops">
                <span v-for="c in cropTags(f)" :key="c.text" class="fc-crop">
                  <i :style="{ background: c.color }"></i>{{ c.text }}
                </span>
                <span v-if="!cropTags(f).length" class="fc-crop-none">未种植</span>
              </div>
              <div class="fc-bar">
                <i
                  v-for="p in f.plots || []"
                  :key="p.slot"
                  :class="'pc-' + dotClass(p)"
                  :title="plotTip(p)"
                ></i>
                <i v-if="!(f.plots || []).length" class="pc-empty wide"></i>
              </div>
              <!-- 触屏没有双击：给一个显式的入口 -->
              <div class="fc-acts">
                <button class="fc-locate" @click.stop="onCardClick(f)">定位</button>
                <button class="fc-open" @click.stop="onCardDblClick(f)">进入 →</button>
              </div>
            </div>
          </div>
        </div>

        <!-- 早期建的地块没有归属农场：单独列出来，别让它们在概览里消失 -->
        <div v-if="unassigned.length && scope.type !== 'all'" class="ua-note">
          另有 {{ unassigned.length }} 块田尚未归属任何农场，返回全国视图可以看到它们。
        </div>

        <div v-if="unassigned.length && scope.type === 'all'" class="ov-sec">
          <div class="os-t">
            未归属农场
            <span class="os-note">{{ unassigned.length }} 块</span>
          </div>
          <div class="ua-list">
            <button
              v-for="p in unassigned"
              :key="'u' + p.slot"
              class="ua-row"
              @click="$emit('enter-plot', p)"
            >
              <i class="ua-dot" :class="'pc-' + dotClass(p)"></i>
              <span class="ua-name">{{ p.fieldName || ('第 ' + (p.slot + 1) + ' 号田') }}</span>
              <span class="ua-crop">{{ cropShort(p.cropName) || '空地' }}</span>
              <span class="ua-stage">{{ p.chosen ? p.stage : '待建田' }}</span>
            </button>
          </div>
          <p class="ua-tip">这些田是在地图农场之前开的，进入后可以继续耕种。</p>
        </div>

        <p class="ov-hint">
          单击卡片在地图上定位到该农场；双击卡片（或点「进入」）直接进 3D 农场场景。
        </p>
      </template>
    </div>
  </aside>
</template>

<script setup>
import { computed, ref, onBeforeUnmount } from 'vue'

const props = defineProps({
  /** { type: 'all' | 'province' | 'district', title, badge, adcode } */
  scope: { type: Object, required: true },
  /** board().farms —— 已按 scope 过滤 */
  farms: { type: Array, default: () => [] },
  /** board().unassigned —— 还没挂到农场的老地块 */
  unassigned: { type: Array, default: () => [] },
  /** 省视图：本省各区县主栽作物聚合 [{ name, color, count }] */
  regionCrops: { type: Array, default: () => [] },
  activeFarmId: { type: Number, default: null },
  /** 面板范围模式：'all' 全部农场 | 'auto' 跟随镜头 */
  scopeMode: { type: String, default: 'auto' }
})

const emit = defineEmits(['select', 'enter', 'enter-plot', 'scope-mode'])

/**
 * 双击与单击的时序仲裁。
 *
 * <p>浏览器的双击会先派发两次 click 再派发 dblclick。如果 click 里直接
 * 定位、dblclick 里进详情，玩家双击时会被「定位 → 定位 → 进详情」打乱，
 * 镜头先跳一次再跳一次，闪得厉害。
 *
 * <p>标准解法：click 延迟一个短窗口（DBL_WINDOW）再执行；期间若收到
 * dblclick 就取消这次延迟的 click。单击延迟只有 ~200ms，人眼感知不到，
 * 但足以把双击和单击区分开。
 */
const DBL_WINDOW = 220
let clickTimer = null
let pendingFarm = null

function onCardClick(f) {
  // 已经排好了一次待执行的点击（可能是双击的前半段）→ 覆盖掉
  if (clickTimer) {
    clearTimeout(clickTimer)
    clickTimer = null
  }
  pendingFarm = f
  clickTimer = setTimeout(() => {
    clickTimer = null
    const target = pendingFarm
    pendingFarm = null
    if (target) emit('select', target)
  }, DBL_WINDOW)
}

/**
 * 双击：直接进 3D 农场场景（不经过地图定位）。
 *
 * <p>🔴 2026-10-22 需求 O：原来叫「详情」，进去只看到右侧面板里的地块列表。
 * 玩家想看的是「我的作物长什么样」——所以现在直接落到 3D 场景里。
 */
function onCardDblClick(f) {
  if (clickTimer) {
    clearTimeout(clickTimer)
    clickTimer = null
  }
  pendingFarm = null
  emit('enter', f)
}

onBeforeUnmount(() => {
  if (clickTimer) clearTimeout(clickTimer)
  clickTimer = null
  pendingFarm = null
})

/**
 * 卡片的作物标签：取该农场正在种的作物（去重、限 3 个）。
 *
 * <p>后端 /board 的 `crops` 是作物名数组（可能带括号注释），
 * 地块上的 `cropName` 才是带形态名的完整名，这里以地块为准。
 */
function cropTags(f) {
  const out = []
  const seen = new Set()
  for (const p of f.plots || []) {
    if (!p.chosen || !p.cropName) continue
    const text = cropShort(p.cropName)
    if (!text || seen.has(text)) continue
    seen.add(text)
    out.push({ text, color: CROP_COLOR[p.visual] || '#8ba07c' })
    if (out.length >= 3) break
  }
  return out
}

const CROP_COLOR = {
  rice: '#5aa654',
  wheat: '#c9a441',
  corn: '#cf8536',
  soybean: '#87944a',
  leafy: '#7fc45c',
  vine: '#3f9e72',
  bush: '#c8563f',
  flower: '#a06bbf'
}

const subtitle = computed(() => {
  const n = props.farms.length
  if (!n) return props.scope.type === 'all' ? '地图上还没有你的点位' : '这个范围暂无农场'
  const provs = new Set(props.farms.map((f) => f.provinceName)).size
  if (props.scope.type === 'all') {
    return `${provs} 个省级区划 · ${n} 个农场 · ${totals.value.plots} 块地`
  }
  return `${n} 个农场 · ${totals.value.plots} 块地`
})

const totals = computed(() => {
  let plots = 0
  let chosen = 0
  let growing = 0
  let ready = 0
  const all = []
  for (const f of props.farms) all.push(...(f.plots || []))
  if (props.scope.type === 'all') all.push(...props.unassigned)
  for (const p of all) {
    plots++
    if (!p.chosen) continue
    chosen++
    const d = p.dvs || 0
    if (d >= 0.92) ready++
    else if (d > 0) growing++
  }
  return { plots, chosen, growing, ready }
})

/** 作物分布：按地块数降序，颜色取作物形态色 */
const cropRows = computed(() => {
  const map = new Map()
  const all = []
  for (const f of props.farms) all.push(...(f.plots || []))
  if (props.scope.type === 'all') all.push(...props.unassigned)
  for (const p of all) {
    if (!p.chosen || !p.cropName) continue
    const key = String(p.cropName).split(/[（(]/)[0]
    const cur = map.get(key)
    if (cur) cur.count++
    else map.set(key, { name: key, count: 1, color: CROP_COLOR[p.visual] || '#8ba07c' })
  }
  return [...map.values()].sort((a, b) => b.count - a.count)
})

/** 地块小方块的状态分档：空 / 未出苗 / 生长 / 可收 */
function dotClass(p) {
  if (!p.chosen) return 'idle'
  const d = p.dvs || 0
  if (d >= 0.92) return 'ready'
  if (d > 0) return 'grow'
  return 'sown'
}

function cropShort(name) {
  return name ? String(name).split(/[（(]/)[0] : ''
}

function plotTip(p) {
  const stage = p.stage || '待建田'
  return `${p.fieldName || '第 ' + (p.slot + 1) + ' 号田'} · ${p.cropName ? String(p.cropName).split(/[（(]/)[0] : '空地'} · ${stage}`
}
</script>

<style scoped>
.ov {
  display: flex;
  flex-direction: column;
  min-height: 0;
  flex: 1;
}

.ov-head {
  padding: 15px 17px 12px;
  border-bottom: 1px solid rgba(150, 190, 140, 0.14);
  flex: 0 0 auto;
}
.oh-top { display: flex; align-items: center; gap: 8px; }
.oh-top h3 { margin: 0; font-size: 16px; letter-spacing: 0.3px; }
.oh-badge {
  padding: 1px 8px;
  border-radius: 8px;
  font-size: 10.5px;
  background: rgba(120, 190, 96, 0.18);
  color: #a8d78e;
}
.oh-sub { margin: 5px 0 0; font-size: 11.5px; color: rgba(206, 228, 196, 0.52); }

/* 范围切换：全部农场 / 跟随镜头 */
.ov-scope {
  display: flex;
  gap: 5px;
  margin-top: 10px;
  padding: 3px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
}
.os-btn {
  flex: 1;
  padding: 4px 0;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: rgba(206, 228, 196, 0.5);
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.14s, color 0.14s;
}
.os-btn:hover { color: rgba(224, 240, 216, 0.82); }
.os-btn.on {
  background: rgba(122, 190, 96, 0.22);
  color: #a8d78e;
  font-weight: 600;
}

.ov-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 14px 15px 18px;
}
.ov-scroll::-webkit-scrollbar { width: 5px; }
.ov-scroll::-webkit-scrollbar-thumb {
  background: rgba(150, 190, 140, 0.24);
  border-radius: 3px;
}

/* ---- 空态 ---- */
.ov-empty { padding: 42px 22px; text-align: center; color: rgba(206, 228, 196, 0.5); }
.oe-icon { font-size: 28px; opacity: 0.35; margin-bottom: 8px; }
.ov-empty p { margin: 0 0 7px; font-size: 13px; color: rgba(224, 240, 216, 0.8); }
.ov-empty span { font-size: 11.5px; line-height: 1.75; }
.oe-more { display: block; margin-top: 12px; color: rgba(200, 224, 190, 0.36); font-size: 10.5px; }

/* ---- 统计 ---- */
.ov-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 7px;
  margin-bottom: 16px;
}
.st {
  padding: 9px 4px 8px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.045);
  text-align: center;
}
.st b {
  display: block;
  font-size: 19px;
  line-height: 1.15;
  color: #eaf4e5;
  font-variant-numeric: tabular-nums;
}
.st b.grow { color: #a8d78e; }
.st b.ready { color: #ffd86b; }
.st span { font-size: 10.5px; color: rgba(200, 224, 190, 0.5); }

/* ---- 分区 ---- */
.ov-sec { margin-bottom: 17px; }
.os-t {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 11px;
  letter-spacing: 1px;
  color: rgba(200, 224, 190, 0.48);
  margin-bottom: 9px;
}
.os-note { letter-spacing: 0; font-size: 10.5px; color: rgba(200, 224, 190, 0.35); }

/* ---- 作物分布 ---- */
.crop-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.cc-pill {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 9px 4px 7px;
  border-radius: 9px;
  background: rgba(255, 255, 255, 0.055);
  font-size: 11.5px;
}
.cc-pill i { width: 8px; height: 8px; border-radius: 2px; flex: 0 0 auto; }
.cp-name { color: rgba(224, 240, 216, 0.86); }
.cc-pill b { font-size: 11px; color: rgba(200, 224, 190, 0.55); font-weight: 500; }

/* ---- 农场卡片 ---- */
.farm-cards { display: flex; flex-direction: column; gap: 8px; }
.fc {
  width: 100%;
  text-align: left;
  padding: 10px 11px;
  border-radius: 9px;
  border: 1px solid rgba(255, 255, 255, 0.06);
  background: rgba(255, 255, 255, 0.04);
  color: inherit;
  cursor: pointer;
  transition: background 0.14s, border-color 0.14s;
}
.fc:hover { background: rgba(255, 255, 255, 0.085); border-color: rgba(160, 200, 150, 0.28); }
.fc:focus-visible {
  outline: 2px solid rgba(168, 215, 142, 0.7);
  outline-offset: 1px;
}
.fc.on { border-color: rgba(255, 216, 107, 0.5); background: rgba(255, 216, 107, 0.09); }
.fc-row { display: flex; align-items: center; gap: 7px; }
.fc-name {
  flex: 1;
  min-width: 0;
  font-size: 13px;
  color: #eaf4e5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.fc-flag {
  padding: 1px 6px;
  border-radius: 7px;
  font-size: 10px;
  background: rgba(255, 200, 70, 0.2);
  color: #ffd86b;
  flex: 0 0 auto;
}
.fc-n { font-size: 10.5px; color: rgba(200, 224, 190, 0.5); flex: 0 0 auto; }
.fc-loc {
  margin-top: 4px;
  font-size: 10.5px;
  color: rgba(200, 224, 190, 0.45);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 作物标签：一眼看出这个农场在种什么 */
.fc-crops {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.fc-crop {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 7px 1px 5px;
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.06);
  font-size: 10.5px;
  color: rgba(224, 240, 216, 0.8);
  white-space: nowrap;
}
.fc-crop i { width: 6px; height: 6px; border-radius: 2px; flex: 0 0 auto; }
.fc-crop-none { padding: 1px 7px; font-size: 10.5px; color: rgba(200, 224, 190, 0.3); }
.fc-bar { display: flex; gap: 3px; margin-top: 8px; flex-wrap: wrap; }
.fc-bar i {
  width: 9px;
  height: 9px;
  border-radius: 2px;
  display: inline-block;
  background: rgba(255, 255, 255, 0.12);
}
.fc-bar i.wide { width: 26px; }
.pc-idle { background: rgba(255, 255, 255, 0.1) !important; }
.pc-sown { background: rgba(110, 160, 210, 0.75) !important; }
.pc-grow { background: rgba(122, 190, 96, 0.9) !important; }
.pc-ready { background: #ffd24a !important; }

/* 卡片底部操作条：触屏没有双击，靠它兜底 */
.fc-acts {
  display: flex;
  gap: 6px;
  margin-top: 9px;
  padding-top: 8px;
  border-top: 1px solid rgba(255, 255, 255, 0.07);
}
.fc-acts button {
  flex: 1;
  padding: 4px 0;
  border: 0;
  border-radius: 6px;
  font-size: 11px;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.14s, color 0.14s;
}
.fc-locate {
  background: rgba(255, 255, 255, 0.07);
  color: rgba(224, 240, 216, 0.78);
}
.fc-locate:hover { background: rgba(255, 255, 255, 0.13); }
.fc-open {
  background: rgba(122, 190, 96, 0.18);
  color: #a8d78e;
  font-weight: 600;
}
.fc-open:hover { background: rgba(122, 190, 96, 0.3); }

/* ---- 未归属地块 ---- */
.ua-list { display: flex; flex-direction: column; gap: 2px; }
.ua-row {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 7px 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: inherit;
  text-align: left;
  cursor: pointer;
}
.ua-row:hover { background: rgba(255, 255, 255, 0.06); }
.ua-dot { width: 8px; height: 8px; border-radius: 2px; flex: 0 0 auto; }
.ua-name { flex: 1; min-width: 0; font-size: 11.5px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ua-crop { font-size: 10.5px; color: rgba(168, 214, 143, 0.85); flex: 0 0 auto; }
.ua-stage { font-size: 10.5px; color: rgba(200, 224, 190, 0.45); flex: 0 0 auto; }
.ua-note {
  margin: 0 0 14px;
  padding: 8px 10px;
  border-radius: 7px;
  background: rgba(255, 255, 255, 0.04);
  font-size: 10.5px;
  line-height: 1.7;
  color: rgba(200, 224, 190, 0.45);
}
.ua-tip { margin: 7px 2px 0; font-size: 10px; line-height: 1.6; color: rgba(200, 224, 190, 0.34); }

.ov-hint {
  margin: 2px 0 0;
  font-size: 10.5px;
  line-height: 1.7;
  color: rgba(200, 224, 190, 0.38);
}
.mono { font-variant-numeric: tabular-nums; }
</style>
