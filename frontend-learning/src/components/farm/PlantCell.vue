<template>
  <div class="plant-cell" :data-stage="stage" :class="cellClass">
    <div class="perspective-wrap">
      <!-- 田面: 浅褐色土壤 (在 .plotgrid 内会被隐藏, 由父级田面统一提供) -->
      <div class="ground" :class="groundClass">
        <div class="ground-shadow"></div>
        <div v-if="waterlogged" class="ground-water"></div>
        <div v-if="dry" class="ground-cracks">
          <span v-for="n in 4" :key="n" :style="crackStyle(n)"></span>
        </div>
        <div v-if="frost" class="ground-frost"></div>
      </div>

      <!-- 作物: 丛生水稻 (一丛 3-8 根茎, 细长下垂叶, 松散穗) -->
      <svg class="plant" :viewBox="`0 0 ${VB.w} ${VB.h}`" preserveAspectRatio="xMidYMax meet" aria-hidden="true">
        <defs>
          <linearGradient :id="`leafG-${stage}-${uid}`" x1="0" y1="0" x2="0.6" y2="1">
            <stop offset="0%" :stop-color="leafLight" />
            <stop offset="100%" :stop-color="leafDark" />
          </linearGradient>
          <linearGradient :id="`panG-${stage}-${uid}`" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" :stop-color="panicleLight" />
            <stop offset="100%" :stop-color="panicleDark" />
          </linearGradient>
        </defs>

        <!-- 每根茎: 先画茎, 再画叶, 最后画穗 (从后往前) -->
        <g v-for="(s, i) in stems" :key="'s'+i" :transform="`translate(${VB.w/2},${VB.h})`">
          <!-- 茎秆 (略带弧度) -->
          <path
            :d="`M 0 0 Q ${s.bendX} ${-s.h*0.55} ${s.tipX} ${-s.h}`"
            :stroke="stemColor"
            :stroke-width="s.w"
            fill="none"
            stroke-linecap="round"
          />
          <!-- 叶片: 2-3 片, 细长下垂 -->
          <path
            v-for="(lf, li) in s.leaves"
            :key="'l'+li"
            :d="lf.d"
            :fill="`url(#leafG-${stage}-${uid})`"
            :stroke="leafDark"
            stroke-width="0.25"
            stroke-opacity="0.5"
          />
          <!-- 穗: 抽穗后才出现, 松散圆锥形 -->
          <g v-if="stage >= 3" :transform="`translate(${s.tipX},${-s.h}) rotate(${s.panTilt})`">
            <!-- 穗轴 -->
            <path :d="`M 0 0 L 0 ${-s.panLen}`" :stroke="panicleDark" stroke-width="0.6" />
            <!-- 小穗: 沿穗轴交错分布成松散锥形 -->
            <ellipse
              v-for="(g, gi) in s.grains"
              :key="'g'+gi"
              :cx="g.x"
              :cy="g.y"
              :rx="g.rx"
              :ry="g.ry"
              :fill="g.fill"
              :transform="`rotate(${g.rot} ${g.x} ${g.y})`"
            />
          </g>
        </g>

        <!-- 状态覆盖: 热害/霜冻 直接画在植株上 -->
        <g v-if="frost" opacity="0.5">
          <circle v-for="n in 5" :key="'f'+n" :cx="10 + n * 8" :cy="20 + (n % 3) * 6" r="1.2" fill="#dceaf5" />
        </g>
      </svg>

      <!-- 状态 overlay -->
      <div v-if="heatWave" class="overlay heat">
        <span>〰</span><span>〰</span><span>〰</span>
      </div>
      <div v-if="frost" class="overlay frost">❄</div>
      <div v-if="rain && !waterlogged" class="overlay rain-drops">
        <span v-for="n in 3" :key="n">💧</span>
      </div>
      <div v-else-if="waterlogged" class="overlay flood">💧💧</div>
      <div v-if="dry" class="overlay dry">▒</div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  stage: { type: Number, required: true },
  heatWave: { type: Boolean, default: false },
  frost: { type: Boolean, default: false },
  rain: { type: Boolean, default: false },
  waterlogged: { type: Boolean, default: false },
  dry: { type: Boolean, default: false }
})

const VB = { w: 60, h: 100 }
const uid = Math.floor(Math.random() * 1e6)

// ---- 颜色 ----
const stemColor = computed(() => {
  const c = ['#7ab648', '#6da23f', '#7a9e35', '#a8b04a', '#b8a840', '#c9a030']
  return c[props.stage] || c[2]
})
const leafLight = computed(() => {
  // 0秧苗→1分蘖→2拔节→3抽穗 绿；4灌浆 绿中带黄；5黄熟 金
  const c = ['#a8dc78', '#8ecc58', '#72b43e', '#8cbc4a', '#a8c848', '#e2b846']
  return c[props.stage] || c[2]
})
const leafDark = computed(() => {
  const c = ['#5a8a30', '#4d7a28', '#38661a', '#4a7a24', '#6e8420', '#9a7214']
  return c[props.stage] || c[2]
})
const panicleLight = computed(() => {
  const c = ['#c8d090', '#c8d090', '#b8c070', '#c8d878', '#ddd060', '#e8c050']
  return c[props.stage] || '#c8d090'
})
const panicleDark = computed(() => {
  const c = ['#889850', '#889850', '#788838', '#98a848', '#a88830', '#a87818']
  return c[props.stage] || '#889850'
})

/**
 * 丛生水稻配置: 每 stage 的丛数/高度/展开度
 * 真实水稻: 分蘖后一丛 5-8 根, 株高 80-100cm, 剑叶直立, 穗下垂
 */
const STEM_CFG = [
  { n: 4, h: 18, spread: 10, pan: 0 },    // 0 秧苗: 4 片小叶
  { n: 6, h: 40, spread: 16, pan: 0 },    // 1 分蘖: 6 根, 展开
  { n: 7, h: 68, spread: 18, pan: 0 },    // 2 拔节: 7 根, 高
  { n: 7, h: 82, spread: 18, pan: 10 },   // 3 抽穗: 穗抽出 (绿)
  { n: 7, h: 84, spread: 18, pan: 26 },   // 4 灌浆: 穗饱满下垂
  { n: 7, h: 82, spread: 18, pan: 34 }    // 5 黄熟: 穗深垂
]

const stems = computed(() => {
  const cfg = STEM_CFG[props.stage] || STEM_CFG[2]
  const arr = []
  for (let i = 0; i < cfg.n; i++) {
    // 位置: -1..1 均匀分布
    const t = cfg.n === 1 ? 0 : (i / (cfg.n - 1) - 0.5) * 2
    const h = cfg.h * (0.82 + ((i * 7) % 5) * 0.045)   // 每根高度微差
    const tipX = t * cfg.spread * 0.5
    const bendX = t * cfg.spread * 0.18

    // 叶片: 每根茎 2-3 片, 分左右交替, 细长下垂
    const leaves = []
    const leafCount = props.stage === 0 ? 1 : 3
    for (let li = 0; li < leafCount; li++) {
      const side = li % 2 === 0 ? 1 : -1
      const atH = h * (0.35 + li * 0.22)          // 叶着生高度
      const len = h * (0.28 - li * 0.03)          // 叶长
      const droop = len * 0.55                     // 下垂幅度
      const x1 = side * len * 0.62
      const y1 = -atH - len * 0.35
      const x2 = side * len
      const y2 = -atH + droop
      // 叶片: 从茎出发, 上拱后下垂, 收窄成尖
      leaves.push({
        d: `M ${side * 0.6} ${-atH} Q ${x1} ${y1} ${x2} ${y2} Q ${x1 * 0.9} ${y2 - droop * 0.3} ${side * 0.4} ${-atH + 0.6} Z`
      })
    }

    // 穗: 松散圆锥, 多个小椭圆沿穗轴分布
    const panLen = cfg.pan > 0 ? 10 + cfg.pan * 0.25 : 0
    const grains = []
    if (panLen > 0) {
      const N = 9
      for (let gi = 0; gi < N; gi++) {
        const frac = gi / (N - 1)                 // 0..1 从穗基到穗尖
        const y = -frac * panLen
        const spread = Math.sin(frac * Math.PI) * 2.6   // 中间宽两头窄
        const side = gi % 2 === 0 ? 1 : -1
        const x = side * spread * (0.4 + ((gi * 3) % 5) * 0.12)
        grains.push({
          x, y,
          rx: 1.5 + (1 - frac) * 0.6,
          ry: 2.2 + (1 - frac) * 0.8,
          rot: side * (10 + frac * 12),
          fill: gi % 3 === 0 ? panicleLight.value : panicleDark.value
        })
      }
    }

    arr.push({
      h, tipX, bendX,
      w: 0.7 + (i % 3) * 0.15,
      leaves,
      panLen,
      panTilt: t * 12 + cfg.pan,
      grains
    })
  }
  return arr
})

const cellClass = computed(() => ({
  'has-heat': props.heatWave,
  'has-frost': props.frost,
  'has-rain': props.rain,
  'has-flood': props.waterlogged,
  'has-dry': props.dry
}))

const groundClass = computed(() => ({
  'is-wet': props.rain || props.waterlogged,
  'is-dry': props.dry,
  'is-frost': props.frost
}))

function crackStyle(n) {
  const left = (n * 23) % 100
  const top = (n * 17) % 100
  const rot = (n * 45) % 90 - 45
  const w = 8 + (n % 3) * 3
  return { left: left + '%', top: top + '%', width: w + 'px', transform: `rotate(${rot}deg)` }
}
</script>

<style scoped>
.plant-cell {
  position: relative;
  width: 100%;
  border-radius: 4px;
  overflow: visible;
  background: transparent;
  perspective: 400px;
  perspective-origin: 50% 100%;
}

.perspective-wrap {
  position: absolute;
  inset: 0;
  transform-style: preserve-3d;
  transition: transform 0.4s ease;
}

/* 田面: 默认浅褐, 状态叠加 (在 .plotgrid 内被隐藏) */
.ground {
  position: absolute;
  inset: 0;
  border-radius: 4px;
  overflow: hidden;
  background:
    radial-gradient(ellipse at 50% 100%, rgba(80, 50, 20, 0.4) 0%, transparent 60%),
    linear-gradient(180deg, #b89770 0%, #8c6a3e 80%, #5a3e1a 100%);
}
.ground-shadow {
  position: absolute;
  bottom: 0; left: 0; right: 0;
  height: 25%;
  background: linear-gradient(180deg, transparent 0%, rgba(0, 0, 0, 0.25) 100%);
  pointer-events: none;
}
.ground.is-wet {
  background:
    radial-gradient(ellipse at 50% 80%, rgba(180, 200, 220, 0.6) 0%, transparent 50%),
    linear-gradient(180deg, #6a5028 0%, #4a3a18 100%);
}
.ground.is-dry {
  background:
    repeating-linear-gradient(45deg, transparent 0 5px, rgba(80, 50, 20, 0.25) 5px 6px),
    linear-gradient(180deg, #c89858 0%, #a07030 100%);
}
.ground.is-frost {
  background:
    linear-gradient(180deg, rgba(220, 230, 240, 0.55) 0%, rgba(220, 230, 240, 0.2) 60%),
    linear-gradient(180deg, #a08868 0%, #6a4828 100%);
}
.ground-water {
  position: absolute;
  bottom: 0; left: 10%; right: 10%;
  height: 30%;
  background: linear-gradient(180deg, rgba(120, 160, 200, 0.4) 0%, rgba(80, 120, 160, 0.7) 100%);
  border-radius: 50% 50% 0 0 / 30% 30% 0 0;
  pointer-events: none;
}
.ground-cracks {
  position: absolute;
  inset: 0;
  pointer-events: none;
}
.ground-cracks span {
  position: absolute;
  height: 1px;
  background: rgba(60, 30, 10, 0.6);
}
.ground-frost {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(circle at 20% 30%, rgba(255, 255, 255, 0.4) 0%, transparent 8%),
    radial-gradient(circle at 70% 60%, rgba(255, 255, 255, 0.4) 0%, transparent 8%),
    radial-gradient(circle at 50% 80%, rgba(255, 255, 255, 0.4) 0%, transparent 8%);
  pointer-events: none;
}

.plant {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  overflow: visible;
  filter: drop-shadow(1px 2px 2px rgba(0, 0, 0, 0.18));
}

.overlay {
  position: absolute;
  font-size: 10px;
  line-height: 1;
  pointer-events: none;
  text-shadow: 0 1px 2px rgba(0, 0, 0, 0.4);
}
.overlay.heat {
  top: 6%; left: 0; right: 0;
  text-align: center;
  color: #ff6b35;
  font-size: 13px;
  letter-spacing: 1px;
  display: flex;
  justify-content: space-around;
  animation: shimmer 1.5s ease-in-out infinite;
}
.overlay.frost {
  top: 8%; right: 8%;
  font-size: 15px;
  color: #d0e4f5;
}
.overlay.rain-drops {
  top: 0; right: 6%;
  display: flex;
  flex-direction: column;
  gap: 1px;
  font-size: 8px;
  color: #4a8acc;
  opacity: 0.8;
}
.overlay.flood {
  bottom: 8%; left: 12%;
  font-size: 11px;
  color: #2a5d8a;
}
.overlay.dry {
  bottom: 4%; right: 8%;
  color: #c89858;
  font-size: 15px;
  font-weight: 700;
  opacity: 0.7;
}

@keyframes shimmer {
  0%, 100% { opacity: 0.6; transform: translateY(0); }
  50%      { opacity: 1;   transform: translateY(-2px); }
}
</style>
