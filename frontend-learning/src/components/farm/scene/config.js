/**
 * 农场 3D 场景的静态配置。
 *
 * 这里只放"数据"——任何函数都不应该写在这，方便美术/数值调整时一眼找到。
 */

/** 贴图根路径与版本。换素材必须递增 TEX_VER，否则浏览器缓存会吃到旧图。 */
export const TEX_BASE = '/farm/tex/web/'
export const TEX_VER = 'v4'

/** 主田尺寸（米）与中心 Z */
export const PADDY_W = 16.6
export const PADDY_D = 22.6
export const FIELD_CZ = -4.5

/** 相机初始球坐标 */
export const CAMERA = { yaw: 0, pitch: 0.215, base: 11.8, zoom: 1, yawAuto: 0 }
export const LOOK_AT = { y: 0.95 }

/** 四季外观。数字为 three 颜色，字符串为 CSS 颜色（canvas 合成用）。 */
export const SEASON = {
  spring: {
    fog: 0xd7e8dc, fogNear: 44, fogFar: 168,
    sun: 0xfff6e0, sunI: 1.45, hemiSky: 0xdcefff, hemiGround: 0x74895a, hemiI: 1.05,
    exposure: 0.99, water: 0x9fc9bd,
    skyTop: '#2b6fb0', skyMid: '#79b5e0', skyLow: '#a4cfe9', skyHorizon: '#dbeef9',
    haze: 'rgba(228,241,247,0.88)',
    soilTint: 0xf0f2e6, grassTint: 0xe8f6d8, plantTint: 0xf2faec
  },
  summer: {
    fog: 0xbdd9e8, fogNear: 46, fogFar: 172,
    sun: 0xfff8e6, sunI: 1.6, hemiSky: 0xd2ecff, hemiGround: 0x6f8a52, hemiI: 1.15,
    exposure: 0.96, water: 0x9dc9cc,
    skyTop: '#1a5fae', skyMid: '#5fa9dd', skyLow: '#93c3e6', skyHorizon: '#d6ebf8',
    haze: 'rgba(221,237,248,0.86)',
    soilTint: 0xffffff, grassTint: 0xffffff, plantTint: 0xffffff
  },
  autumn: {
    fog: 0xe6d9c0, fogNear: 44, fogFar: 166,
    sun: 0xffe6b4, sunI: 1.5, hemiSky: 0xffeed2, hemiGround: 0x6d5c38, hemiI: 1.08,
    exposure: 0.98, water: 0xa8b99c,
    skyTop: '#2a68a4', skyMid: '#85b3d4', skyLow: '#a6c3d6', skyHorizon: '#dee6e2',
    haze: 'rgba(236,225,202,0.86)',
    soilTint: 0xf6ead2, grassTint: 0xf3e2bd, plantTint: 0xffeec8
  },
  winter: {
    fog: 0xdfe8ef, fogNear: 40, fogFar: 155,
    sun: 0xeaf2ff, sunI: 1.1, hemiSky: 0xe6f0fa, hemiGround: 0xa2adb4, hemiI: 1.35,
    exposure: 1.02, water: 0xb9c9ce,
    skyTop: '#527aa2', skyMid: '#9dbad2', skyLow: '#b2c9da', skyHorizon: '#e0e9f0',
    haze: 'rgba(232,239,244,0.92)',
    soilTint: 0xd2dadb, grassTint: 0xc9d4d3, plantTint: 0xc6d2d2
  }
}

/**
 * 物候阶段 → 精灵形态。
 *   tex   : young（营养生长期丛）/ ripe（带穗成株）
 *   h     : 植株世界高度（米）
 *   vTop  : 贴图纵向截取上界（<1 表示截掉顶部穗部，模拟抽穗前）
 */
export const STAGE_PLAN = {
  rice: [
    { tex: 'young', h: 0.38, vTop: 1.0 },
    { tex: 'young', h: 0.58, vTop: 1.0 },
    { tex: 'young', h: 0.84, vTop: 1.0 },
    { tex: 'ripe', h: 1.02, vTop: 0.90 },
    { tex: 'ripe', h: 1.08, vTop: 1.0 },
    { tex: 'ripe', h: 1.04, vTop: 1.0 }
  ],
  wheat: [
    { tex: 'young', h: 0.34, vTop: 1.0 },
    { tex: 'young', h: 0.56, vTop: 1.0 },
    { tex: 'young', h: 0.84, vTop: 1.0 },
    { tex: 'ripe', h: 0.98, vTop: 0.88 },
    { tex: 'ripe', h: 1.02, vTop: 1.0 },
    { tex: 'ripe', h: 0.98, vTop: 1.0 }
  ],
  corn: [
    { tex: 'young', h: 0.72, vTop: 1.0 },
    { tex: 'young', h: 1.34, vTop: 1.0 },
    { tex: 'young', h: 1.96, vTop: 1.0 },
    { tex: 'ripe', h: 2.28, vTop: 0.86 },
    { tex: 'ripe', h: 2.40, vTop: 1.0 },
    { tex: 'ripe', h: 2.30, vTop: 1.0 }
  ],
  soybean: [
    { tex: 'young', h: 0.26, vTop: 1.0 },
    { tex: 'young', h: 0.40, vTop: 1.0 },
    { tex: 'young', h: 0.52, vTop: 1.0 },
    { tex: 'ripe', h: 0.54, vTop: 0.90 },
    { tex: 'ripe', h: 0.58, vTop: 1.0 },
    { tex: 'ripe', h: 0.55, vTop: 1.0 }
  ],

  // ---- 园艺作物 ----
  // 叶菜：莲座期到结球，高度只到膝盖
  leafy: [
    { tex: 'young', h: 0.16, vTop: 1.0 },
    { tex: 'young', h: 0.24, vTop: 1.0 },
    { tex: 'young', h: 0.32, vTop: 1.0 },
    { tex: 'young', h: 0.38, vTop: 1.0 },
    { tex: 'ripe', h: 0.42, vTop: 1.0 },
    { tex: 'ripe', h: 0.40, vTop: 1.0 }
  ],
  // 瓜类：匍匐藤，横向铺开而不是往上长
  vine: [
    { tex: 'young', h: 0.18, vTop: 1.0 },
    { tex: 'young', h: 0.28, vTop: 1.0 },
    { tex: 'young', h: 0.38, vTop: 1.0 },
    { tex: 'ripe', h: 0.46, vTop: 0.92 },
    { tex: 'ripe', h: 0.50, vTop: 1.0 },
    { tex: 'ripe', h: 0.48, vTop: 1.0 }
  ],
  // 茄果类：搭架后一人多高
  bush: [
    { tex: 'young', h: 0.22, vTop: 1.0 },
    { tex: 'young', h: 0.42, vTop: 1.0 },
    { tex: 'young', h: 0.66, vTop: 1.0 },
    { tex: 'ripe', h: 0.84, vTop: 0.94 },
    { tex: 'ripe', h: 0.92, vTop: 1.0 },
    { tex: 'ripe', h: 0.88, vTop: 1.0 }
  ],
  // 花卉：叶丛到开花，花头在最上面
  flower: [
    { tex: 'young', h: 0.18, vTop: 1.0 },
    { tex: 'young', h: 0.32, vTop: 1.0 },
    { tex: 'young', h: 0.50, vTop: 1.0 },
    { tex: 'ripe', h: 0.64, vTop: 0.94 },
    { tex: 'ripe', h: 0.72, vTop: 1.0 },
    { tex: 'ripe', h: 0.68, vTop: 1.0 }
  ]
}

/** cut-out 后内容宽高比（scripts/farm-assets/prep.py 实测输出） */
export const SPRITE_ASPECT = {
  plant_rice_young: 0.921,
  plant_rice_ripe: 0.751,
  plant_wheat_young: 0.692,
  plant_wheat_ripe: 0.588,
  plant_corn_young: 0.757,
  plant_corn_ripe: 0.619,
  plant_soybean_young: 0.915,
  plant_soybean_ripe: 0.819,
  plant_leafy_young: 0.666,
  plant_leafy_ripe: 0.666,
  plant_vine_young: 0.764,
  plant_vine_ripe: 0.666,
  plant_bush_young: 0.666,
  plant_bush_ripe: 0.666,
  plant_flower_young: 0.666,
  plant_flower_ripe: 0.77,
  plant_tree: 0.636,
  plant_grass_tuft: 0.707
}

/** 种植密度：列数 × 行数 × 间距（米） */
export const DENSITY = {
  rice: { cols: 27, rows: 35, step: 0.58 },
  wheat: { cols: 38, rows: 52, step: 0.40 },
  corn: { cols: 20, rows: 34, step: 0.78 },
  soybean: { cols: 26, rows: 34, step: 0.60 },

  // ---- 园艺作物（株距按各作物实际定植密度折算） ----
  // 叶菜：25cm 株距
  leafy: { cols: 30, rows: 40, step: 0.55 },
  // 瓜类：50cm 株距、1.5m 行距，密度最低
  vine: { cols: 18, rows: 22, step: 1.00 },
  // 茄果类：35cm 株距
  bush: { cols: 27, rows: 36, step: 0.62 },
  // 切花：25cm 密植
  flower: { cols: 33, rows: 44, step: 0.50 }
}

export const CROPS = ['rice', 'wheat', 'corn', 'soybean', 'leafy', 'vine', 'bush', 'flower']

/** 后端 visual 字段 → 场景作物键 */
export function normalizeCrop(crop) {
  return CROPS.includes(crop) ? crop : 'rice'
}
