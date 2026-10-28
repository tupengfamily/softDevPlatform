/**
 * 农场**作物元数据**的唯一实现（颜色 / 名字 / 形态族 / 地块状态分档）。
 *
 * <p>🔴 2026-10-28 抽出来的原因：同一批作物常量在 <b>4 个文件</b>各写了一份，
 * 而且已经<b>分叉</b>：
 * <ul>
 *   <li>{@code CROP_COLOR} —— FarmWorldMap.vue 与 FarmOverviewPanel.vue 各一份
 *       （28 个键值完全一致，纯拷贝）；</li>
 *   <li>{@code CROP_FAMILY} —— 同上两份（26 键一致）；</li>
 *   <li>{@code LEGACY_VISUAL} —— scene/config.js 一份 +
 *       FarmWorldMap.vue 的 CROP_FAMILY 里又内嵌了一份；</li>
 *   <li>{@code shortCrop()} —— <b>5 份拷贝</b>，而且<b>兜底值各不相同</b>
 *       （{@code ''} / {@code '—'} / {@code '空地'}）。</li>
 * </ul>
 *
 * <h2>🔴 已修的活 bug：兜底色不一致</h2>
 * <p>两个 {@code cropColor()} 的兜底色<b>不一样</b>：
 * <ul>
 *   <li>FarmWorldMap.vue → {@code #33452f}（深土褐）</li>
 *   <li>FarmOverviewPanel.vue → {@code #8ba07c}（灰绿）</li>
 * </ul>
 * <p>后果：<b>同一个 visual 在两个界面显示成两种颜色</b> ——
 * 地图上是一块深褐色的地，概览面板里是灰绿色的。玩家会以为这是两种不同的地。
 *
 * <h2>🔴 为什么地块状态分档也在这里</h2>
 * <p>{@code dotClass} / {@code plotDotClass} 曾经有 3 份，且<b>已经分叉</b>：
 * FarmBoard.vue 的版本<b>少了 {@code sown} 档</b>（只有 空/生长/可收 三档，
 * 少了「已播种未出苗」）。地图上的地会显示蓝色小点，看板上的同一块地却是灰色。
 *
 * <p>这里统一出<b>语义档位</b>（{@code idle} / {@code sown} / {@code grow} / {@code ready}），
 * 各组件再用自己的 CSS 前缀包一层（{@code pc-} / {@code d-}）——
 * <b>判定逻辑只有一份，样式各自保留</b>。
 */

import { isHarvestable } from './farmLabels'

/* ================================================================== *
 * 1. 作物清单
 * ================================================================== */

/**
 * 22 种作物的 visual id。
 *
 * <p>⚠️ 与后端 {@code CropCatalog} 的 {@code visual} 字段一一对应
 * （粮食作物保留 {@code rice/wheat/corn/soybean}，园艺作物用作物 id）。
 */
export const CROP_VISUALS = [
  // 粮食（5）
  'rice', 'wheat', 'corn', 'soybean', 'millet',
  // 蔬菜（7）
  'cabbage', 'tomato', 'cucumber', 'chili', 'eggplant', 'yardlong_bean', 'radish',
  // 水果（5）
  'strawberry', 'watermelon', 'melon', 'grape', 'pear',
  // 花卉（4）
  'chrysanthemum', 'sunflower', 'tulip', 'rose',
  // 块茎（1）
  'potato'
]

/**
 * 旧形态族 → 代表作物（**存量数据兜底**）。
 *
 * <p>⚠️ 存量数据库 {@code t_farm_season.visual} 里存的是旧值
 * {@code leafy / vine / bush / flower}。后端不做数据库迁移（风险大），
 * 所以前端必须认这些旧值，否则整片田渲染不出来。
 *
 * <p>⚠️ **这是一个有损降级**：存量田块刷新后会**变成代表作物**的形态 ——
 * 所有旧 {@code bush} 田都长成番茄、所有旧 {@code vine} 田都长成黄瓜。
 * 这是可接受的：比整片报错好得多，而且只要玩家重开一季、
 * 后端写入新的作物专属 visual，形态就自然修正了。
 */
export const LEGACY_VISUAL = {
  leafy: 'cabbage',
  vine: 'cucumber',
  bush: 'tomato',
  flower: 'chrysanthemum'
}

/**
 * 作物 id → 形态族（**只用于取「旧族的颜色/贴图」**）。
 *
 * <p>⚠️ 2026-10-24 起，园艺作物的 visual 已经是**作物 id**
 * （cabbage / grape / rose …），不再是 leafy/vine/bush/flower 这类通用族。
 * 22 种作物各有专属颜色，所以归族只在「取旧族的颜色」时用得上。
 */
export const CROP_FAMILY = {
  rice_mid: 'rice', wheat_winter: 'wheat', corn_spring: 'corn', soybean: 'soybean',
  millet: 'millet',
  cabbage: 'cabbage', radish: 'radish',
  tomato: 'tomato', chili: 'chili', eggplant: 'eggplant', strawberry: 'strawberry',
  cucumber: 'cucumber', watermelon: 'watermelon', melon: 'melon', yardlong_bean: 'yardlong_bean',
  potato: 'potato',
  grape: 'grape', pear: 'pear',
  chrysanthemum: 'chrysanthemum', sunflower: 'sunflower', tulip: 'tulip', rose: 'rose',
  // 存量旧形态族 → 代表作物（与 LEGACY_VISUAL 保持一致）
  leafy: 'cabbage', vine: 'cucumber', bush: 'tomato', flower: 'chrysanthemum'
}

/* ================================================================== *
 * 2. 颜色
 * ================================================================== */

/**
 * 作物 → 颜色。
 *
 * <p>⚠️ 2026-10-24：从 8 个通用形态族扩到 **22 种作物专属颜色**。
 *
 * <p>约束：**同族作物不能撞色** —— 茄子/番茄/辣椒/草莓都属茄果族，
 * 但它们必须一眼能分开（茄子偏紫、番茄红、辣椒深红、草莓玫红）。
 * 存量旧值（leafy/vine/bush/flower/millet/tuber/tree）**保留原色**，
 * 因为存量数据里仍可能出现这些值。
 */
export const CROP_COLOR = {
  // ---- 粮食（保持原值）----
  rice: '#5aa654',
  wheat: '#c9a441',
  corn: '#cf8536',
  soybean: '#87944a',
  // ---- 粮食·新增：谷子（黄绿，比大豆更黄）----
  millet: '#9aa84e',

  // ---- 蔬菜 ----
  // 大白菜：黄绿偏亮
  cabbage: '#7fc45c',
  // 萝卜：青绿（根菜，叶色偏深）
  radish: '#5fae52',
  // 番茄：正红
  tomato: '#c8563f',
  // 辣椒：深红（比番茄更暗更红）
  chili: '#a83b30',
  // 茄子：紫红（茄果族里唯一偏紫的）
  eggplant: '#8e4a72',
  // 豆角：嫩绿（荚菜，叶色偏亮）
  yardlong_bean: '#4f9e5c',
  // 黄瓜：翠绿（瓜类）
  cucumber: '#3f9e72',

  // ---- 水果 ----
  // 草莓：玫红（浆果，与番茄红要能分开）
  strawberry: '#d05a72',
  // 西瓜：深绿（瓜类，比黄瓜深）
  watermelon: '#2f8259',
  // 甜瓜：浅黄绿（厚皮甜瓜，色偏暖）
  melon: '#7ab469',
  // 葡萄：紫（藤果）
  grape: '#7a5a9e',
  // 梨：橄榄绿（仁果）
  pear: '#6f8f3f',

  // ---- 花卉 ----
  // 菊花：淡紫（切花菊常见白/紫/黄，紫最好认）
  // ⚠️ 不能用金黄 —— 冬小麦已经是 #c9a441，图例里两个作物同色会看错
  chrysanthemum: '#a878c8',
  // 向日葵：亮黄橙
  sunflower: '#e0b83a',
  // 郁金香：粉红
  tulip: '#d4788e',
  // 月季：深玫红
  rose: '#b83f66',

  // ---- 块茎 ----
  // 马铃薯：土黄褐（地上部是绿的，但收的是地下块茎，
  // 用一个偏土的色把「根茎类」和「叶菜类」区分开）
  potato: '#b08d57',

  // ---- 存量旧形态族（保留原色与原名，存量数据仍可能出现）----
  leafy: '#7fc45c',
  vine: '#3f9e72',
  bush: '#c8563f',
  flower: '#a06bbf',
  tuber: '#b08d57',
  tree: '#6f8f3f',

  // ---- 「无作物」兜底色（深土褐）----
  // 🔴 2026-10-28：原来 FarmWorldMap 用 #33452f，FarmOverviewPanel 用 #8ba07c，
  // 同一 visual 在两个界面两种颜色。现在统一为深土褐（地块没有作物时的自然色）。
  none: '#33452f'
}

/**
 * visual → 颜色。
 *
 * <p>解析顺序：作物 id 直接命中 → 旧 id 归族 → 兜底到「无作物」的土褐色。
 *
 * @param {string} visual
 * @returns {string} CSS 颜色值
 */
export function cropColor (visual) {
  if (!visual) return CROP_COLOR.none
  if (CROP_COLOR[visual]) return CROP_COLOR[visual]
  const fam = CROP_FAMILY[visual]
  if (fam && CROP_COLOR[fam]) return CROP_COLOR[fam]
  return CROP_COLOR.none
}

/* ================================================================== *
 * 3. 名字
 * ================================================================== */

/**
 * 作物 → 中文名。
 *
 * <p>⚠️ 同 CROP_COLOR：22 种作物各有名字，旧的 4 族名保留兼容存量数据。
 */
export const CROP_NAME = {
  // ---- 粮食 ----
  rice: '水稻', wheat: '冬小麦', corn: '玉米', soybean: '大豆',
  millet: '谷子',
  // ---- 蔬菜 ----
  cabbage: '大白菜', tomato: '番茄', cucumber: '黄瓜', chili: '辣椒',
  eggplant: '茄子', yardlong_bean: '豆角', radish: '萝卜',
  // ---- 水果 ----
  strawberry: '草莓', watermelon: '西瓜', melon: '甜瓜', grape: '葡萄', pear: '梨',
  // ---- 花卉 ----
  chrysanthemum: '菊花', sunflower: '向日葵', tulip: '郁金香', rose: '月季',
  // ---- 块茎 ----
  potato: '马铃薯',
  // ---- 存量旧形态族（保留原名）----
  leafy: '叶菜', vine: '瓜类', bush: '茄果', flower: '花卉',
  tuber: '块茎', tree: '仁果'
}

/**
 * visual → 中文名。
 *
 * @param {string} visual
 * @returns {string} 查不到时返回原值（不编造）
 */
export function cropName (visual) {
  if (!visual) return '—'
  if (CROP_NAME[visual]) return CROP_NAME[visual]
  const fam = CROP_FAMILY[visual]
  if (fam && CROP_NAME[fam]) return CROP_NAME[fam]
  return visual
}

/**
 * 图例：**只列 22 种新作物**。
 *
 * <p>⚠️ 不能把旧的 leafy/vine/bush/flower 也列进来 —— 那会让图例出现
 * 「叶菜」和「大白菜」两条几乎同色的项，看起来像 bug。
 * 旧值只在数据层保留兼容（颜色/名字表里还在），不进图例。
 */
export const LEGEND_CROPS = [
  'rice', 'wheat', 'corn', 'soybean', 'millet',
  'cabbage', 'tomato', 'cucumber', 'chili', 'eggplant', 'yardlong_bean', 'radish',
  'strawberry', 'watermelon', 'melon', 'grape', 'pear',
  'chrysanthemum', 'sunflower', 'tulip', 'rose',
  'potato'
]

/* ================================================================== *
 * 4. 短名
 * ================================================================== */

/**
 * 作物全名 → 短名（去掉括号里的品种说明）。
 *
 * <p>「水稻（中稻/一季稻）」→「水稻」。
 *
 * <p>🔴 2026-10-28：原来有 <b>5 份拷贝</b>，兜底值还不一样
 * （{@code ''} / {@code '—'} / {@code '空地'}）。现在统一。
 *
 * @param {string} name 作物全名
 * @param {string} [fallback=''] 空值时的兜底文案
 * @returns {string}
 */
export function shortCrop (name, fallback = '') {
  if (!name) return fallback
  return String(name).split(/[（(]/)[0]
}

/* ================================================================== *
 * 5. 地块状态分档
 * ================================================================== */

/**
 * 地块状态档位。
 *
 * <p>- {@code idle} —— 空地（没种）
 * <p>- {@code sown} —— 已播种，未出苗（DVS = 0）
 * <p>- {@code grow} —— 生长中
 * <p>- {@code ready} —— 可收割
 */
export const PLOT_DOT = {
  IDLE: 'idle',
  SOWN: 'sown',
  GROW: 'grow',
  READY: 'ready'
}

/**
 * 地块状态分档（<b>判定逻辑的唯一出口</b>）。
 *
 * <p>🔴 2026-10-28：原来有 3 份（FarmWorldMap / FarmOverviewPanel / FarmBoard），
 * 其中 FarmBoard 那份<b>少了 {@code sown} 档</b> —— 地图上显示蓝色小点，
 * 看板上却是灰色，同一块地两个界面两个颜色。
 *
 * <p>⚠️ <b>收割判定必须走 {@code isHarvestable(p)}</b>
 * （见 {@code utils/farmLabels.js}），那是后端下发阈值的唯一出口。
 * 这里<b>直接复用</b>它，不重写一遍 —— 否则又是一份「同一语义两份实现」。
 *
 * @param {object} p 地块对象
 * @returns {'idle'|'sown'|'grow'|'ready'}
 */
export function plotDotState (p) {
  if (!p || !p.chosen) return PLOT_DOT.IDLE
  if (isHarvestable(p)) return PLOT_DOT.READY
  const d = Number(p.dvs || 0)
  if (d > 0) return PLOT_DOT.GROW
  return PLOT_DOT.SOWN
}
