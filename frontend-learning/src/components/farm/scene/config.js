/**
 * 农场 3D 场景的静态配置。
 *
 * 这里只放"数据"——任何函数都不应该写在这，方便美术/数值调整时一眼找到。
 */

/**
 * 贴图根路径与版本。换素材必须递增 TEX_VER，否则浏览器缓存会吃到旧图。
 *
 * ⚠️ 2026-10-24：v4 → v5。
 *
 *   22 种作物专属贴图（`plant_<cropId>_*.png`）还没全部裁好，加载会先 404
 *   再退到通用族图。**浏览器会缓存 404 响应** —— 素材补齐后不换版本号，
 *   玩家仍会一直吃那个 404，一直看到降级的族图。换版本号强制重新请求。
 */
export const TEX_BASE = '/farm/tex/web/'
export const TEX_VER = 'v5'

/** 主田尺寸（米）与中心 Z */
export const PADDY_W = 16.6
export const PADDY_D = 22.6
export const FIELD_CZ = -4.5

/** 相机初始球坐标 */
export const CAMERA = { yaw: 0, pitch: 0.215, base: 11.8, zoom: 1, yawAuto: 0 }
export const LOOK_AT = { y: 0.95 }

/**
 * 四季外观。数字为 three 颜色，字符串为 CSS 颜色（canvas 合成用）。
 *
 * ⚠️ **天空色阶与雾色必须色相连续**（2026-10-22 血泪教训）：
 *
 *   相机 fov=48°、俯角 12.3°，**实际只覆盖 0~15° 仰角** —— 天空几乎整个
 *   可见区都是"低空段"。如果 skyLow 与 fog 色相差太远（秋天的 skyLow 是冷蓝、
 *   fog 是暖米色，蓝通道差 +22），地面被暖雾染白、天空却是冷蓝，
 *   在地平线（树线）处就割裂出一条**青蓝色横带**，远看像水面。
 *
 *   约束：**fog ≈ 地平线色**（sky.js 里地平线色直接由 fog 派生），
 *   **skyLow 与 fog 的蓝通道差控制在 ±8 以内**，让低空到地平线自然过渡。
 *   天空真正变蓝要等到 skyMid/skyTop —— 但那在可见区之外，只影响环境反射。
 *
 * ⚠️ **fogFar 必须够远（≈230~240）**：
 *   远山三层在 z=-186/-203/-217，距相机约 197/215/228m。
 *   fogFar 若只有 166~172，这三层雾浓度全是 100% → 远山完全消失。
 *   fogFar=240 时雾浓度分别是 78% / 87% / 94%，层次正好（越远越淡）。
 *   同时树带（-95~-162）落在 31%~66% 雾，不会被冲白。
 */
export const SEASON = {
  spring: {
    fog: 0xe4eee0, fogNear: 56, fogFar: 244,
    sun: 0xfff6e0, sunI: 1.45, hemiSky: 0xdcefff, hemiGround: 0x74895a, hemiI: 1.05,
    // ⚠️ **曝光值统一在 1.30~1.42**（2026-10-23 画质升级实测）。
    //   原来 0.96~1.02 太暗 —— 实测主田地面亮度只有 42~65，
    //   土壤纹理和作物层次全糊在一起。
    //   曝光**只影响地面不影响天空**（天空 toneMapped:false），
    //   所以调高是安全的，不会把天空洗白。
    exposure: 1.32, water: 0x9fc9bd,
    skyTop: '#2b6fb0', skyMid: '#79b5e0', skyLow: '#c3dfe8', skyHorizon: '#dbeef9',
    haze: 'rgba(228,241,247,0.88)',
    ridgeTint: '#e8f2e4',
    soilTint: 0xf0f2e6, grassTint: 0xe8f6d8, plantTint: 0xf2faec
  },
  summer: {
    // ⚠️ **雾色必须偏暖**（2026-10-23 画质升级，实测取样）。
    //
    //   原来 fog=0xbdd9e8 是**青蓝色**（189,217,232），fogNear=46。
    //   实测画面取样：中景田 RGB=(163,169,180)、主田中段 (140,153,161)
    //   —— **蓝通道最高**，整片农田被染成灰蓝，一点都不像真实农田。
    //
    //   物理上也不对：晴天农田上方的空气是**被地面反照染暖**的，
    //   越靠近地面越暖（这是真实照片里"远山偏暖、天空偏蓝"的原因）。
    //   所以雾色应该取「地平线天空色」与「地面反照暖色」的混合，
    //   而不是纯青蓝。
    //
    //   现在改为 0xd6e2e0（淡青灰，暖化后）—— 仍然保持"空气感"，
    //   但不会把暖褐色的田面染成灰蓝。
    fog: 0xd9e6e0, fogNear: 58, fogFar: 250,
    sun: 0xfff8e6, sunI: 1.6, hemiSky: 0xd2ecff, hemiGround: 0x6f8a52, hemiI: 1.15,
    exposure: 1.30, water: 0x9dc9cc,
    skyTop: '#1a5fae', skyMid: '#5fa9dd', skyLow: '#aed2e4', skyHorizon: '#d6ebf8',
    haze: 'rgba(221,237,248,0.86)',
    ridgeTint: '#eef6f2',
    soilTint: 0xffffff, grassTint: 0xffffff, plantTint: 0xffffff
  },
  autumn: {
    fog: 0xe8ddc6, fogNear: 56, fogFar: 242,
    sun: 0xffe6b4, sunI: 1.5, hemiSky: 0xffeed2, hemiGround: 0x6d5c38, hemiI: 1.08,
    exposure: 1.30, water: 0xa8b99c,
    skyTop: '#2a68a4', skyMid: '#85b3d4', skyLow: '#dcd9cd', skyHorizon: '#dee6e2',
    haze: 'rgba(236,225,202,0.86)',
    // 秋天山体偏暖褐：与暖米色雾同色系，同时比天空暗一档保住轮廓
    ridgeTint: '#e8dcc4',
    soilTint: 0xf6ead2, grassTint: 0xf3e2bd, plantTint: 0xffeec8
  },
  winter: {
    fog: 0xe2e9ee, fogNear: 54, fogFar: 238,
    // ⚠️ **冬天必须降低 hemiI，而不是提高 sunI**（2026-10-23 画质升级实测）。
    //
    //   原来 hemiI=1.35 > sunI=1.1 —— **环境光比直射光还强**，
    //   于是阴影区被天空光完全填平：实测阴影开/关的像素差
    //   只有 maxDiff=55（正常应该 120+），影子"有形无实"。
    //
    //   物理上冬天的直射阳光确实弱（空气路径长、大气散射多），
    //   但**天空散射也弱**（太阳低 → 天空照度同样下降）。
    //   正确比例是"直射稍弱、环境更弱"，影子才有分量。
    //
    //   现在 hemiI 1.35 → 0.72，sunI 1.1 → 1.25（相对提一点直射），
    //   冬天读起来是"斜射的冷白光 + 清晰的投影"。
    //
    //   exposure 1.02 → 1.40：环境光调低后总光通量下降，
    //   用曝光补回来，保证"影子更清楚"的同时画面不会整体发闷。
    //
    //   ⚠️ **曝光只影响地面，不影响天空**（实测：ex 1.12/1.30/1.40
    //   天空四行亮度恒为 187/201/206/228）——
    //   因为天空材质设了 toneMapped:false（见 FarmScene3D 的 buildSky）。
    //   所以曝光可以放心调高，不会把天空洗白。
    sun: 0xeaf2ff, sunI: 1.25, hemiSky: 0xe6f0fa, hemiGround: 0xa2adb4, hemiI: 0.72,
    exposure: 1.40, water: 0xb9c9ce,
    skyTop: '#527aa2', skyMid: '#9dbad2', skyLow: '#dde3e6', skyHorizon: '#e0e9f0',
    haze: 'rgba(232,239,244,0.92)',
    // 冬天山体冷灰（雪覆山）
    ridgeTint: '#d4dde4',
    soilTint: 0xd2dadb, grassTint: 0xc9d4d3, plantTint: 0xc6d2d2
  }
}

/**
 * 物候阶段 → 精灵形态。
 *   tex   : young（营养生长期丛）/ ripe（带穗或挂果成株）
 *   h     : 植株世界高度（米）
 *   vTop  : 贴图纵向截取上界（<1 表示截掉顶部穗部/果实，模拟抽穗前、膨果前）
 *
 * 🔴 **2026-10-26 重构：段数与后端物候严格对齐**。
 *
 *   原实现所有 22 种作物都用**固定 6 段**模板，且前 3 段共用同一张 `young` 贴图，
 *   只靠 `h` 缩放区分 —— 结果是「苗 / 分蘖 / 拔节」三段在画面上几乎一样，
 *   用户看到的阶段与真实物候对不上。
 *
 *   现在每一段对应**该作物自己的一个物候阶段**，段数与后端
 *   `CropConfig.phenology()` 完全一致：
 *     · 水稻 7 段 · 冬小麦 7 段 · 玉米 6 段 · 其余 19 种各 5 段
 *
 *   🔴 **段数必须与后端一致**（后端 `PhenologyIndexTest.stageCountSnapshot` 守着），
 *   否则前端第 N 段会画成后端第 N+1 段的形态。
 *
 *   形态表达的三条规则：
 *     1. `h` 按**物候进程**递增：营养生长前期最矮 → 生殖生长中期最高 → 成熟略回落
 *        （穗已饱满、叶片开始衰老，真实农田里成熟株常比盛期矮 1~4%）。
 *     2. `vTop < 1` 表示**该阶段穗/果实还没长出来**，截掉贴图顶部。
 *        禾谷在抽穗期之前截顶；瓜果类在膨果期之前截顶；块根类在膨大期之前截顶。
 *     3. `tex` 在**营养生长 → 生殖生长**的界线上切换 young → ripe。
 *
 *   ⚠️ `rice`/`wheat`/`corn`/`soybean` 是后端 visual 的既有取值（存量
 *      `t_farm_season.visual` 里就是这几个），键名不能动。
 */
export const STAGE_PLAN = {
  // ---- 水稻（rice_mid）：7 段 ----
  // 秧苗 → 返青分蘖 → 够苗晒田 → 拔节孕穗 → 抽穗开花 → 灌浆 → 黄熟
  rice: [
    { tex: 'young', h: 0.34, vTop: 1.0 },   // 秧苗期：插秧后的矮丛
    { tex: 'young', h: 0.56, vTop: 1.0 },   // 返青分蘖始：开始分蘖，丛幅变宽
    { tex: 'young', h: 0.68, vTop: 1.0 },   // 够苗晒田期：群体够了，高度趋稳
    { tex: 'young', h: 0.92, vTop: 1.0 },   // 拔节孕穗期：节间伸长，穗还没抽
    { tex: 'ripe',  h: 1.02, vTop: 0.90 },  // 抽穗开花期：穗抽出（截顶 → 穗显形）
    { tex: 'ripe',  h: 1.08, vTop: 1.0 },   // 灌浆期：灌浆饱满，株高到位
    { tex: 'ripe',  h: 1.04, vTop: 1.0 }    // 黄熟期：穗黄叶衰，略矮于灌浆期
  ],

  // ---- 冬小麦（wheat_winter）：7 段 ----
  // 出苗 → 分蘖 → 越冬 → 返青拔节 → 抽穗扬花 → 灌浆 → 成熟
  wheat: [
    { tex: 'young', h: 0.26, vTop: 1.0 },   // 出苗期：三叶一心的小苗
    { tex: 'young', h: 0.48, vTop: 1.0 },   // 分蘖期：分蘖数增加，丛变宽
    { tex: 'young', h: 0.56, vTop: 1.0 },   // 越冬期：停止生长，株高基本不再增
    { tex: 'young', h: 0.86, vTop: 1.0 },   // 返青拔节：节间伸长，穗未抽
    { tex: 'ripe',  h: 0.98, vTop: 0.88 },  // 抽穗扬花：穗抽出（截顶 → 穗显形）
    { tex: 'ripe',  h: 1.02, vTop: 1.0 },   // 灌浆期：籽粒充实
    { tex: 'ripe',  h: 0.98, vTop: 1.0 }    // 成熟期：麦黄穗沉，略矮
  ],

  // ---- 玉米（corn_spring）：6 段 ----
  // 出苗 → 拔节 → 大喇叭口 → 抽雄吐丝 → 灌浆 → 成熟
  corn: [
    { tex: 'young', h: 0.62, vTop: 1.0 },   // 出苗期：芽鞘顶土
    { tex: 'young', h: 1.30, vTop: 1.0 },   // 拔节期：节间快速伸长
    { tex: 'young', h: 1.92, vTop: 1.0 },   // 大喇叭口期：叶片最宽，株高接近极限
    { tex: 'ripe',  h: 2.24, vTop: 0.86 },  // 抽雄吐丝：雄穗抽出（截顶 → 穗显形）
    { tex: 'ripe',  h: 2.40, vTop: 1.0 },   // 灌浆期：籽粒饱满，全株最高
    { tex: 'ripe',  h: 2.32, vTop: 1.0 }    // 成熟期：苞叶干枯，株高略降
  ],

  // ---- 大豆（soybean）：5 段 ----
  // 出苗 → 分枝 → 开花 → 结荚鼓粒 → 成熟
  soybean: [
    { tex: 'young', h: 0.22, vTop: 1.0 },   // 出苗期：子叶展开
    { tex: 'young', h: 0.38, vTop: 1.0 },   // 分枝期：分枝增加
    { tex: 'young', h: 0.50, vTop: 1.0 },   // 开花期：花期（豆科花很小，不截顶）
    { tex: 'ripe',  h: 0.54, vTop: 0.90 },  // 结荚鼓粒：荚果显形（截顶）
    { tex: 'ripe',  h: 0.55, vTop: 1.0 }    // 成熟期：叶落荚黄
  ],

  // ---- 谷子（millet）：5 段 ----
  // 播种出苗 → 分蘖拔节 → 抽穗扬花 → 灌浆 → 成熟收获
  millet: [
    { tex: 'young', h: 0.30, vTop: 1.0 },   // 播种出苗
    { tex: 'young', h: 0.74, vTop: 1.0 },   // 分蘖拔节：蹲苗后拔高
    { tex: 'ripe',  h: 0.96, vTop: 0.88 },  // 抽穗扬花：谷穗抽出（截顶）
    { tex: 'ripe',  h: 1.02, vTop: 1.0 },   // 灌浆
    { tex: 'ripe',  h: 1.00, vTop: 1.0 }    // 成熟收获：穗垂叶黄
  ],

  // ---- 马铃薯（potato）：5 段 ----
  // 催芽播种 → 出苗现蕾 → 块茎形成 → 块茎膨大 → 成熟收获
  // ⚠️ 马铃薯收的是地下块茎，地上株型变化不大；高度差异主要来自茎叶伸展。
  potato: [
    { tex: 'young', h: 0.14, vTop: 1.0 },   // 催芽播种：刚出芽
    { tex: 'young', h: 0.27, vTop: 1.0 },   // 出苗现蕾：茎叶伸展
    { tex: 'young', h: 0.42, vTop: 1.0 },   // 块茎形成：地上仍以营养生长为主
    { tex: 'ripe',  h: 0.52, vTop: 0.93 },  // 块茎膨大（截顶表示块茎已显）
    { tex: 'ripe',  h: 0.54, vTop: 1.0 }    // 成熟收获：地上部分开始衰老
  ],

  // ---- 大白菜（cabbage）：5 段 ----
  // 发芽出苗 → 幼苗期 → 莲座期 → 结球期 → 成熟可收
  cabbage: [
    { tex: 'young', h: 0.10, vTop: 1.0 },   // 发芽出苗：子叶刚展
    { tex: 'young', h: 0.20, vTop: 1.0 },   // 幼苗期：叶片数增加
    { tex: 'young', h: 0.33, vTop: 1.0 },   // 莲座期：外叶铺开成莲座
    { tex: 'ripe',  h: 0.40, vTop: 0.94 },  // 结球期：叶球抱紧（截顶）
    { tex: 'ripe',  h: 0.42, vTop: 1.0 }    // 成熟可收：球紧实
  ],

  // ---- 番茄（tomato）：5 段 ----
  // 发芽出苗 → 幼苗期 → 开花坐果 → 膨果期 → 成熟采收
  tomato: [
    { tex: 'young', h: 0.18, vTop: 1.0 },   // 发芽出苗
    { tex: 'young', h: 0.42, vTop: 1.0 },   // 幼苗期：茎伸长
    { tex: 'young', h: 0.70, vTop: 1.0 },   // 开花坐果：花序出现，果实刚坐住
    { tex: 'ripe',  h: 0.86, vTop: 0.94 },  // 膨果期：果实膨大（截顶）
    { tex: 'ripe',  h: 0.90, vTop: 1.0 }    // 成熟采收：果红
  ],

  // ---- 辣椒（chili）：5 段 ----
  // 发芽出苗 → 幼苗期 → 开花坐果 → 果实膨大 → 成熟采收
  chili: [
    { tex: 'young', h: 0.16, vTop: 1.0 },   // 发芽出苗
    { tex: 'young', h: 0.38, vTop: 1.0 },   // 幼苗期
    { tex: 'young', h: 0.64, vTop: 1.0 },   // 开花坐果
    { tex: 'ripe',  h: 0.79, vTop: 0.94 },  // 果实膨大（截顶）
    { tex: 'ripe',  h: 0.83, vTop: 1.0 }    // 成熟采收：果红
  ],

  // ---- 茄子（eggplant）：5 段 ----
  // 发芽出苗 → 幼苗期 → 开花坐果 → 果实膨大 → 成熟采收
  eggplant: [
    { tex: 'young', h: 0.18, vTop: 1.0 },   // 发芽出苗
    { tex: 'young', h: 0.42, vTop: 1.0 },   // 幼苗期
    { tex: 'young', h: 0.72, vTop: 1.0 },   // 开花坐果
    { tex: 'ripe',  h: 0.89, vTop: 0.94 },  // 果实膨大（截顶）
    { tex: 'ripe',  h: 0.93, vTop: 1.0 }    // 成熟采收
  ],

  // ---- 豆角（yardlong_bean）：5 段 ----
  // 播种出苗 → 伸蔓期 → 开花结荚 → 盛荚期 → 采收期
  yardlong_bean: [
    { tex: 'young', h: 0.16, vTop: 1.0 },   // 播种出苗
    { tex: 'young', h: 0.52, vTop: 1.0 },   // 伸蔓期：攀爬上架
    { tex: 'young', h: 0.96, vTop: 1.0 },   // 开花结荚
    { tex: 'ripe',  h: 1.20, vTop: 0.93 },  // 盛荚期：荚果垂挂（截顶）
    { tex: 'ripe',  h: 1.26, vTop: 1.0 }    // 采收期
  ],

  // ---- 萝卜（radish）：5 段 ----
  // 播种出苗 → 幼苗期 → 莲座期 → 肉质根膨大 → 成熟采收
  radish: [
    { tex: 'young', h: 0.10, vTop: 1.0 },   // 播种出苗
    { tex: 'young', h: 0.19, vTop: 1.0 },   // 幼苗期
    { tex: 'young', h: 0.30, vTop: 1.0 },   // 莲座期：叶丛铺开
    { tex: 'ripe',  h: 0.35, vTop: 0.94 },  // 肉质根膨大（截顶表示根已显）
    { tex: 'ripe',  h: 0.37, vTop: 1.0 }    // 成熟采收
  ],

  // ---- 草莓（strawberry）：5 段 ----
  // 缓苗越冬 → 返青现蕾 → 开花坐果 → 果实膨大 → 采收期
  strawberry: [
    { tex: 'young', h: 0.07, vTop: 1.0 },   // 缓苗越冬：极矮丛生
    { tex: 'young', h: 0.11, vTop: 1.0 },   // 返青现蕾
    { tex: 'young', h: 0.16, vTop: 1.0 },   // 开花坐果
    { tex: 'ripe',  h: 0.20, vTop: 0.95 },  // 果实膨大（截顶）
    { tex: 'ripe',  h: 0.21, vTop: 1.0 }    // 采收期：果红下垂
  ],

  // ---- 西瓜（watermelon）：5 段 ----
  // 发芽出苗 → 幼苗期 → 伸蔓开花 → 膨瓜期 → 成熟采收
  watermelon: [
    { tex: 'young', h: 0.13, vTop: 1.0 },   // 发芽出苗
    { tex: 'young', h: 0.26, vTop: 1.0 },   // 幼苗期
    { tex: 'young', h: 0.42, vTop: 1.0 },   // 伸蔓开花：藤蔓铺开
    { tex: 'ripe',  h: 0.51, vTop: 0.93 },  // 膨瓜期：瓜果膨大（截顶）
    { tex: 'ripe',  h: 0.54, vTop: 1.0 }    // 成熟采收
  ],

  // ---- 甜瓜（melon）：5 段 ----
  // 发芽出苗 → 幼苗期 → 伸蔓开花 → 膨瓜期 → 成熟采收
  melon: [
    { tex: 'young', h: 0.14, vTop: 1.0 },   // 发芽出苗
    { tex: 'young', h: 0.26, vTop: 1.0 },   // 幼苗期
    { tex: 'young', h: 0.40, vTop: 1.0 },   // 伸蔓开花
    { tex: 'ripe',  h: 0.48, vTop: 0.93 },  // 膨瓜期（截顶）
    { tex: 'ripe',  h: 0.51, vTop: 1.0 }    // 成熟采收
  ],

  // ---- 葡萄（grape）：5 段 ----
  // 定植休眠 → 萌芽展叶 → 开花坐果 → 果实膨大 → 成熟采收
  grape: [
    { tex: 'young', h: 0.30, vTop: 1.0 },   // 定植休眠：只有枝干
    { tex: 'young', h: 0.66, vTop: 1.0 },   // 萌芽展叶：新梢伸长
    { tex: 'young', h: 1.05, vTop: 1.0 },   // 开花坐果
    { tex: 'ripe',  h: 1.30, vTop: 0.93 },  // 果实膨大（截顶）
    { tex: 'ripe',  h: 1.38, vTop: 1.0 }    // 成熟采收
  ],

  // ---- 梨（pear）：5 段 ----
  // 定植休眠 → 萌芽展叶 → 开花坐果 → 果实膨大 → 成熟采收
  pear: [
    { tex: 'young', h: 0.42, vTop: 1.0 },   // 定植休眠：只有枝干
    { tex: 'young', h: 0.86, vTop: 1.0 },   // 萌芽展叶
    { tex: 'young', h: 1.36, vTop: 1.0 },   // 开花坐果：花序显形
    { tex: 'ripe',  h: 1.68, vTop: 0.94 },  // 果实膨大（截顶）
    { tex: 'ripe',  h: 1.78, vTop: 1.0 }    // 成熟采收
  ],

  // ---- 菊花（chrysanthemum）：5 段 ----
  // 定植缓苗 → 摘心分枝 → 现蕾期 → 开花期 → 切花采收
  chrysanthemum: [
    { tex: 'young', h: 0.16, vTop: 1.0 },   // 定植缓苗
    { tex: 'young', h: 0.33, vTop: 1.0 },   // 摘心分枝：分枝后冠幅扩大
    { tex: 'young', h: 0.55, vTop: 1.0 },   // 现蕾期：花芽形成
    { tex: 'ripe',  h: 0.67, vTop: 0.94 },  // 开花期：花头展开（截顶）
    { tex: 'ripe',  h: 0.71, vTop: 1.0 }    // 切花采收
  ],

  // ---- 向日葵（sunflower）：5 段 ----
  // 发芽出苗 → 苗期 → 现蕾开花 → 灌浆期 → 成熟收获
  sunflower: [
    { tex: 'young', h: 0.18, vTop: 1.0 },   // 发芽出苗
    { tex: 'young', h: 0.78, vTop: 1.0 },   // 苗期：茎秆快速伸长
    { tex: 'young', h: 1.55, vTop: 1.0 },   // 现蕾开花：花盘形成
    { tex: 'ripe',  h: 1.95, vTop: 0.90 },  // 灌浆期：籽粒充实（截顶）
    { tex: 'ripe',  h: 2.08, vTop: 1.0 }    // 成熟收获：全株最高
  ],

  // ---- 郁金香（tulip）：5 段 ----
  // 定植越冬 → 返青抽叶 → 现蕾期 → 开花期 → 切花采收
  tulip: [
    { tex: 'young', h: 0.14, vTop: 1.0 },   // 定植越冬：只有叶片
    { tex: 'young', h: 0.27, vTop: 1.0 },   // 返青抽叶
    { tex: 'young', h: 0.43, vTop: 1.0 },   // 现蕾期：花苞挺立
    { tex: 'ripe',  h: 0.51, vTop: 0.93 },  // 开花期：花冠展开（截顶）
    { tex: 'ripe',  h: 0.54, vTop: 1.0 }    // 切花采收
  ],

  // ---- 月季（rose）：5 段 ----
  // 定植缓苗 → 摘心整形 → 现蕾期 → 开花期 → 切花采收
  rose: [
    { tex: 'young', h: 0.17, vTop: 1.0 },   // 定植缓苗
    { tex: 'young', h: 0.38, vTop: 1.0 },   // 摘心整形：分枝后冠幅扩大
    { tex: 'young', h: 0.64, vTop: 1.0 },   // 现蕾期：花苞形成
    { tex: 'ripe',  h: 0.79, vTop: 0.93 },  // 开花期：花朵绽放（截顶）
    { tex: 'ripe',  h: 0.83, vTop: 1.0 }    // 切花采收
  ]
}

/**
 * 后端物候段中文名 → i18n key。
 *
 * 🔴 **2026-10-26 新增**：后端下发 `stageNames`（每种作物自己的段名），
 *   前端时间轴要逐段显示名字，就必须把中文段名映射到可翻译的 key。
 *
 * <p>58 个中文段名归并到 42 个规范 key —— 归并规则：
 *   · 同义合并：「灌浆」=「灌浆期」、「成熟期」=「成熟采收」=「成熟收获」=「成熟可收」
 *   · 语义细分保留：「幼苗期」与「苗期」同义，但「开花期」「开花坐果」「开花结荚」
 *     是三个不同阶段（泛指开花 / 花后坐果 / 结荚），保留独立 key。
 *
 * <p>⚠️ **这个表必须覆盖后端全部段名**。
 *   后端 `HortConfigs` / `RiceConfig` / `WheatConfig` / `CornConfig` / `SoybeanConfig`
 *   里新增一个段名而这里没加 → 时间轴会显示原始中文（英文界面漏中文）。
 */
export const PHENO_STAGE_KEY = {
  '灌浆': 'filling',  '灌浆期': 'filling',  '成熟期': 'mature',  '成熟采收': 'mature',  '成熟收获': 'mature',  '成熟可收': 'mature',  '采收期': 'harvest',  '切花采收': 'cutflower',  '幼苗期': 'seedling',  '苗期': 'seedling',  '开花期': 'flower',  '开花坐果': 'flowerset',  '开花结荚': 'flowerbean',  '现蕾期': 'bud',  '现蕾开花': 'budflower',  '果实膨大': 'fruitgrow',  '膨果期': 'fruitgrow',  '膨瓜期': 'fruitgrow',  '莲座期': 'rosette',  '播种出苗': 'sowemerge',  '催芽播种': 'sowemerge',  '定植休眠': 'dormant',  '定植越冬': 'dormant',  '缓苗越冬': 'dormant',  '越冬期': 'overwinter',  '出苗期': 'emerge',  '发芽出苗': 'emerge',  '出苗现蕾': 'emergebud',  '分蘖期': 'tiller',  '分蘖拔节': 'tillerboot',  '分枝期': 'branch',  '返青分蘖始': 'greenup',  '返青拔节': 'greenup',  '返青抽叶': 'greenup',  '返青现蕾': 'greenupbud',  '伸蔓期': 'vinespread',  '伸蔓开花': 'vineflower',  '抽蔓开花': 'vineflower',  '块茎形成': 'tuberform',  '块茎膨大': 'tubergrow',  '肉质根膨大': 'rootgrow',  '结球期': 'headform',  '结瓜期': 'fruitform',  '盛荚期': 'beanfull',  '结荚鼓粒': 'beanfill',  '够苗晒田期': 'dryfield',  '拔节期': 'jointing',  '拔节孕穗期': 'jointing',  '大喇叭口期': 'bellmouth',  '抽穗开花期': 'headingflower',  '抽穗扬花': 'headingflower',  '抽雄吐丝': 'tassel',  '黄熟期': 'yellow',  '定植缓苗': 'settle',  '秧苗期': 'nursery',  '萌芽展叶': 'sproutleaf',  '摘心分枝': 'pinchbranch',  '摘心整形': 'pinchshape',}

/**
 * 取某作物某阶段的 i18n key（未登记时返回 null —— 调用方应回退到原始中文名）。
 */
export function phenoStageKeyOf(name) {
  return PHENO_STAGE_KEY[name] || null
}

/**
 * cut-out 后内容宽高比（scripts/farm-assets/prep.py 实测输出）。
 *
 * ⚠️ 2026-10-24：`rice`/`wheat`/`corn`/`soybean` 4 项是**实测值**，保持原样。
 * 其余 18 种园艺作物是**按形态估的合理值**（禾谷偏窄、灌木偏方、藤本偏窄、
 * 高大乔木窄、匍匐作物偏宽），等专属素材裁好后必须实测校正。
 */
/**
 * cut-out 后内容宽高比 —— **全部为实测值**。
 *
 * 🔴 **2026-10-24：22 种作物专属素材到位后，这批值由
 *   `scripts/farm-assets/prep_crops.py` 实测写入（`sprites.json` 为权威来源）。
 *
 * <p><b>为什么必须是实测而不是估值</b>：前端十字面片按宽高比决定几何比例，
 * 估值偏差会让整株作物**比例失真** —— 贴图本身没变形，是几何被拉伸。
 * 典型例子：郁金香成熟株实测 0.268（花头窄、茎高），
 * 估值 0.56 差了一倍多，贴上去会变成一朵被拍扁的花。
 */
export const SPRITE_ASPECT = {
  plant_bush_ripe: 0.634,
  plant_bush_young: 0.607,
  plant_cabbage_ripe: 0.784,
  plant_cabbage_young: 1.036,
  plant_chili_ripe: 0.708,
  plant_chili_young: 0.509,
  plant_chrysanthemum_ripe: 0.77,
  plant_chrysanthemum_young: 1.152,
  plant_corn_ripe: 0.505,
  plant_corn_spring_ripe: 0.505,
  plant_corn_spring_young: 0.732,
  plant_corn_young: 0.732,
  plant_cucumber_ripe: 0.997,
  plant_cucumber_young: 0.766,
  plant_eggplant_ripe: 0.643,
  plant_eggplant_young: 0.997,
  plant_flower_ripe: 0.77,
  plant_flower_young: 1.152,
  plant_grape_ripe: 0.703,
  plant_grape_young: 0.493,
  plant_grass_tuft: 0.736,
  plant_leafy_ripe: 0.784,
  plant_leafy_young: 1.036,
  plant_melon_ripe: 0.827,
  plant_melon_young: 0.762,
  plant_millet_ripe: 0.501,
  plant_millet_young: 0.641,
  plant_pear_ripe: 0.77,
  plant_pear_young: 0.478,
  plant_potato_ripe: 0.818,
  plant_potato_young: 1.547,
  plant_radish_ripe: 0.753,
  plant_radish_young: 0.374,
  plant_rice_mid_ripe: 0.645,
  plant_rice_mid_young: 0.91,
  plant_rice_ripe: 0.645,
  plant_rice_young: 0.91,
  plant_rose_ripe: 0.754,
  plant_rose_young: 0.658,
  plant_soybean_ripe: 0.665,
  plant_soybean_young: 0.924,
  plant_strawberry_ripe: 0.745,
  plant_strawberry_young: 0.827,
  plant_sunflower_ripe: 0.637,
  plant_sunflower_young: 0.958,
  plant_tomato_ripe: 0.634,
  plant_tomato_young: 0.607,
  plant_tree: 0.665,
  plant_tulip_ripe: 0.264,
  plant_tulip_young: 0.595,
  plant_vine_ripe: 0.997,
  plant_vine_young: 0.766,
  plant_watermelon_ripe: 0.9,
  plant_watermelon_young: 0.934,
  plant_wheat_ripe: 0.482,
  plant_wheat_winter_ripe: 0.482,
  plant_wheat_winter_young: 0.701,
  plant_wheat_young: 0.701,
  plant_yardlong_bean_ripe: 0.642,
  plant_yardlong_bean_young: 0.503,
}

/**
 * 种植密度：列数 × 行数 × 间距（米）。
 *
 * ⚠️ 2026-10-24：从 8 个通用形态族扩到 **22 种作物专属密度**。
 *
 * 折算依据：每种作物的**实际定植密度**（行株距）→ 换算成主田
 * （16.6m × 22.6m）里的行列数与丛距。
 *
 * 🔴 `rice`/`wheat`/`corn`/`soybean` 4 项保持原样。
 */
export const DENSITY = {
  // ---- 粮食（实测调好的，保持原样）----
  rice: { cols: 27, rows: 35, step: 0.58 },
  wheat: { cols: 38, rows: 52, step: 0.40 },
  corn: { cols: 20, rows: 34, step: 0.78 },
  soybean: { cols: 26, rows: 34, step: 0.60 },

  // ---- 粮食·新增：谷子 ----
  // 禾谷密植，比小麦略稀（谷子茎粗、分蘖少），但仍是禾本科的密植口径
  millet: { cols: 40, rows: 54, step: 0.38 },

  // ---- 园艺作物（按各自定植密度折算）----
  // 马铃薯：垄作，穴距 30cm 但要通风透光 → 折算成较大株距
  potato: { cols: 26, rows: 34, step: 0.72 },
  // 大白菜：行株距 40×25cm
  cabbage: { cols: 34, rows: 44, step: 0.50 },
  // 萝卜：撒播/条播后间苗，密植
  radish: { cols: 36, rows: 48, step: 0.46 },
  // 番茄：搭架 50×35cm
  tomato: { cols: 28, rows: 36, step: 0.60 },
  // 辣椒：同番茄，株型略小故略密
  chili: { cols: 28, rows: 36, step: 0.58 },
  // 茄子：60×45cm，株型比番茄大 → 更稀
  eggplant: { cols: 24, rows: 32, step: 0.72 },
  // 豆角：架豆 60×30cm，藤本要通风
  yardlong_bean: { cols: 22, rows: 30, step: 0.72 },
  // 黄瓜：70×40cm
  cucumber: { cols: 22, rows: 28, step: 0.72 },
  // 西瓜：大行距 200×50cm，全场最稀的瓜类
  watermelon: { cols: 16, rows: 20, step: 1.10 },
  // 甜瓜：120×45cm，比西瓜耐密植
  melon: { cols: 20, rows: 26, step: 0.92 },
  // 葡萄：架式 150×80cm
  grape: { cols: 18, rows: 22, step: 0.95 },
  // 梨：乔木 250×200cm，全场最稀
  pear: { cols: 12, rows: 15, step: 1.45 },
  // 草莓：20×15cm，最密
  strawberry: { cols: 40, rows: 52, step: 0.36 },
  // 菊花：25×25cm 切花密植
  chrysanthemum: { cols: 34, rows: 44, step: 0.48 },
  // 向日葵：60×40cm
  sunflower: { cols: 24, rows: 32, step: 0.70 },
  // 郁金香：15×10cm 球根，最密
  tulip: { cols: 42, rows: 54, step: 0.34 },
  // 月季：50×40cm 灌木
  rose: { cols: 26, rows: 34, step: 0.66 }
}

/**
 * 22 种作物的 visual 标识（= 后端 `CropConfig.visual`）。
 *
 * ⚠️ 2026-10-24：园艺作物的 visual 从「通用形态族」变成**作物专属 id**。
 * 4 个粮食作物保持原值（存量 t_farm_season.visual 里就是这几个）。
 */
export const CROPS = [
  // 粮食（4）
  'rice', 'wheat', 'corn', 'soybean',
  // 粮食·新增（1）
  'millet',
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
 * ⚠️ 2026-10-24：存量数据库 `t_farm_season.visual` 里存的是旧值
 * `leafy` / `vine` / `bush` / `flower`。后端不做数据库迁移（风险大），
 * 所以前端必须认这些旧值，否则整片田渲染不出来。
 *
 * ⚠️ **这是一个有损降级**：存量田块刷新后会**变成代表作物**的形态 ——
 * 所有旧 `bush` 田都长成番茄、所有旧 `vine` 田都长成黄瓜。
 * 这是可接受的：比整片报错好得多，而且只要玩家重开一季、
 * 后端写入新的作物专属 visual，形态就自然修正了。
 */
const LEGACY_VISUAL = {
  leafy: 'cabbage',
  vine: 'cucumber',
  bush: 'tomato',
  flower: 'chrysanthemum'
}

/**
 * 作物 id → 通用族贴图（**仅在专属贴图缺失时降级用**）。
 *
 * 按形态挑最接近的一套：禾谷→稻、块茎→叶菜、乔木→灌木、
 * 藤本→瓜、茄果→茄果、花卉→花卉。
 *
 * ⚠️ 这是**过渡期**的妥协：22 种作物各有各的形态，但 3D 素材目前只有
 * 4 个粮食 + 4 个通用族。等专属贴图补齐后，这些映射会被逐条删掉。
 */
export const FALLBACK_TEXTURE = {
  millet: 'rice',
  potato: 'leafy',
  pear: 'bush',
  cabbage: 'leafy',
  radish: 'leafy',
  tomato: 'bush',
  chili: 'bush',
  eggplant: 'bush',
  strawberry: 'bush',
  yardlong_bean: 'vine',
  cucumber: 'vine',
  watermelon: 'vine',
  melon: 'vine',
  grape: 'vine',
  chrysanthemum: 'flower',
  sunflower: 'flower',
  tulip: 'flower',
  rose: 'flower'
}

/**
 * 该作物专属贴图缺失时，退到哪一套通用族贴图。
 *
 * @param {string} cropId 22 种作物之一（已 normalize）
 * @returns {string|null} 通用族名；null 表示该作物没有降级方案
 */
export function fallbackTextureOf(cropId) {
  return FALLBACK_TEXTURE[cropId] || null
}

/**
 * 后端 visual 字段 → 场景作物键（= 实际要加载的贴图名）。
 *
 * 解析顺序：
 * 1. 22 种作物专属 id（crops.js / STAGE_PLAN / SPRITE_ASPECT 里都有）；
 * 2. **存量旧值**（leafy/vine/bush/flower）→ 兜底到代表作物；
 * 3. 兜底 'rice'。
 *
 * ⚠️ 存量旧值是有损降级，见 {@link LEGACY_VISUAL} 的说明。
 */
export function normalizeCrop(crop) {
  if (!crop) return 'rice'
  // 1. 22 种作物专属 id
  if (CROPS.includes(crop)) return crop
  // 2. 存量旧形态族 → 代表作物（有损降级，但比整片报错好）
  if (LEGACY_VISUAL[crop]) return LEGACY_VISUAL[crop]
  // 3. 兜底
  return 'rice'
}

/** 该 visual 对应的形态族（用于颜色、密度等按族取值的场景）。 */
export function cropFamily(crop) {
  if (CROPS.includes(crop)) return crop
  if (LEGACY_VISUAL[crop]) return LEGACY_VISUAL[crop]
  return 'rice'
}

/**
 * 取该 visual 的种植密度。
 *
 * 密度是**按作物**定的（梨 1.45m、豆角 0.72m），22 种作物各有一条。
 * 存量旧值走 {@link normalizeCrop} 的代表作物那条。
 */
export function densityOf(crop) {
  if (DENSITY[crop]) return DENSITY[crop]
  const norm = normalizeCrop(crop)
  return DENSITY[norm] || DENSITY.rice
}

/**
 * 作物 → 邻田田面色（`neighborTint` 用）。
 *
 * 俯瞰视角下不同作物的田块必须一眼分得开，所以每个作物给三档：
 *
 *   [0] 成熟色（黄熟/灌浆/成熟期）
 *   [1] 生长色（旺长期，默认档）
 *   [2] 苗期色（出苗/播种/返青期）
 *
 * 🔴 `rice`/`wheat`/`corn`/`soybean` 4 项是**原值**，只是重新排列成上面的顺序。
 */
export const CROP_TINT = {
  // ---- 粮食：成熟转金黄 ----
  rice: [0xd8bd52, 0x4f8f2c, 0x86a24c],
  wheat: [0xe8c451, 0x4a8a2c, 0x7d9d45],
  corn: [0xb9a342, 0x3f8a22, 0x8a9c3c],
  soybean: [0xc0a63c, 0x3e8526, 0x86a03f],
  millet: [0xd9bb4e, 0x5f8a34, 0x8fa548],

  // ---- 蔬菜：茄果转红、瓜类转深绿、叶菜转黄绿 ----
  cabbage: [0xa8c65c, 0x5f9c3a, 0x86bc52],
  radish: [0x9cbe5a, 0x57933a, 0x7fb04e],
  tomato: [0xc2543c, 0x3f8a30, 0x76a840],
  chili: [0xc0402f, 0x3a8430, 0x6da340],
  eggplant: [0x9c4a58, 0x367c2c, 0x689a3c],
  yardlong_bean: [0x93b455, 0x3d8a36, 0x74a848],
  cucumber: [0x8ab35e, 0x37913f, 0x62a955],

  // ---- 水果 ----
  strawberry: [0xc74a55, 0x3f8a34, 0x74a244],
  watermelon: [0x7fae58, 0x35853a, 0x5d9e4e],
  melon: [0x9cba60, 0x3c8b3f, 0x6aa254],
  grape: [0x7d6f9c, 0x357c34, 0x5f9846],
  pear: [0xa8b856, 0x3d8038, 0x6a9a4c],

  // ---- 花卉：颜色最跳 ----
  // ⚠️ 菊花用紫（与地图 CROP_COLOR 一致），不能用金黄 —— 冬小麦已经是金黄
  chrysanthemum: [0xb07ad0, 0x6b8f3c, 0x86a842],
  sunflower: [0xe0b83a, 0x4a8c30, 0x86a83c],
  tulip: [0xd05a7a, 0x4b8f38, 0x7cae46],
  rose: [0xcc5570, 0x468a34, 0x76a842],

  // ---- 块茎 ----
  potato: [0xa8b96a, 0x5b8a3c, 0x86a852],

  // ---- 存量旧形态族（保留旧色，存量数据仍可能出现）----
  // ⚠️ 中间档（生长色）必须与 FarmWorldMap.vue 的 CROP_COLOR 旧族色一致，
  //   否则地图上是紫色、3D 场景里是金色，同一块田两个颜色。
  leafy: [0xa8c65c, 0x7fc45c, 0x86bc52],
  vine: [0x8ab35e, 0x3f9e72, 0x62a955],
  bush: [0xc2543c, 0xc8563f, 0x76a840],
  flower: [0xc9a83f, 0xa06bbf, 0x7fb04c]
}

/**
 * 作物渲染精细度参数（2026-10-24 画质升级）。
 *
 * ⚠️ **为什么 alphaTest 要按作物分档**：
 *
 *   `alphaTest` 是"低于这个透明度就丢弃像素"的硬阈值。阈值太高 → 叶片边缘
 *   被啃掉一圈锯齿状的缺口（真实照片的柔边直接变成硬边）；
 *   阈值太低 → 半透明的柔边像素被保留，但它们参与深度写入，
 *   在 1200 个实例密集排列时会互相遮挡，产生"脏边"和排序错误。
 *
 *   22 种作物的抠图质量不一样：
 *   · ImageGen 生成的图边缘锐利（硬边为主）→ 阈值可以高一点（0.45~0.5）；
 *   · 真实照片（大白菜/番茄/黄瓜/菊花）边缘是柔和的半透明羽化
 *     → 阈值必须低（0.22~0.3），否则羽化边全被切掉。
 *
 *   配合 `alphaToCoverage`（WebGL2 MSAA 下）把剩下的半透明像素
 *   变成"部分覆盖的样本"，边缘就是抗锯齿的软边而不是硬缺口。
 */
export const ALPHA_CUTOFF = {
  // 真实照片（柔和羽化边）—— 阈值必须低，否则羽化边被啃成锯齿
  cabbage: 0.26, tomato: 0.28, cucumber: 0.28, chrysanthemum: 0.24,
  // AI 生成（锐边为主）—— 阈值可以高，避免半透明像素造成排序脏边
  chili: 0.42, eggplant: 0.42, yardlong_bean: 0.40, radish: 0.40,
  strawberry: 0.38, watermelon: 0.42, melon: 0.42, grape: 0.40, pear: 0.40,
  sunflower: 0.44, tulip: 0.46, rose: 0.40,
  millet: 0.46, potato: 0.46,
  // 粮食（复用旧 AI 图）
  rice: 0.46, wheat: 0.44, corn: 0.42, soybean: 0.42
}

/**
 * 十字面片的顶点细分数。
 *
 * ⚠️ **为什么横向比纵向少**：
 *   球面化法线的变化主要发生在**横向**（从中心到边缘卷起），
 *   纵向只需要够密以支持风动的弯曲梯度。3×4 在视觉与开销上最平衡。
 */
export const QUAD_SEGMENTS = { x: 3, y: 4 }

/**
 * 作物**姿态**：立姿（竖直十字面片）还是趴姿（贴地面片）。
 *
 * ⚠️ **2026-10-24 新增 —— 姿态必须与素材的拍摄角度一致**。
 *
 *   用户反馈"甜瓜有点纸片的样子"，根因不是贴图质量，而是
 *   **几何与素材的角度矛盾**：
 *
 *   · 甜瓜/西瓜/黄瓜的成熟素材是**俯视图**（从正上方拍的瓜蔓叶毯）
 *   · 十字面片是**竖直**的（90° 相交的两片竖直平面）
 *   → 俯视的瓜蔓被"立"起来显示，画面上就是一张立起来的海报
 *
 *   真实农田里匍匐作物**本来就趴在地上**。所以正确解法不是硬掰角度，
 *   而是给这类作物换**贴地面片**（buildGroundQuad）：
 *   几何与素材角度一致 → 瓜的圆形轮廓自然、叶毯平铺、顶光均匀。
 *
 *   姿态取值：
 *     'upright' —— 竖直十字面片（禾本科、果树、茄果类…）
 *     'ground'  —— 贴地面片（俯视素材：匍匐瓜类、叶丛类…）
 *
 *   ⚠️ **幼苗期与成熟期可以不同姿态**：瓜苗是竖着长的（upright），
 *     但成株铺满地面（ground）。所以这里按阶段给，而不是按作物给。
 *
 * 🔴🔴 **每一项都必须对着源图逐张核对过**（2026-10-24 全量复核 44 张）。
 *
 *   判据：**前景 bbox 的宽高比** + 肉眼确认根/花球的位置：
 *     · 宽 > 高 且根/土球不在画面底部 → 俯视（ground）
 *     · 高 > 宽 且根在底部 → 侧视（upright）
 *
 *   踩过的坑（都是"想当然"没看图导致的）：
 *     ❌ potato 幼苗是**俯视**（根在右下角，苗平躺）→ 之前误设 upright
 *     ❌ potato 成株是**侧视**（根在正下方）→ 之前误设 ground，渲染出来是"倒挂"
 *     ❌ cabbage 成株是**侧视**（白菜球正侧面，根在下方）→ 之前误设 ground
 *     ❌ radish 两个阶段都是**侧视**（萝卜球正侧，根须在下）→ 之前误设 ground
 *     ❌ chrysanthemum 两个阶段都是**俯视**（花球/花丛从上看）→ 之前漏设
 *
 *   素材目录：`frontend-learning/public/farm/tex/hort/src/<crop>/{young,ripe}.png`
 */
export const CROP_POSTURE = {
  // 匍匐瓜类：苗期直立（藤蔓竖着爬），成株铺地（叶毯）
  melon: { young: 'upright', ripe: 'ground' },
  watermelon: { young: 'upright', ripe: 'ground' },
  cucumber: { young: 'upright', ripe: 'ground' },
  // 叶丛类
  cabbage: { young: 'ground', ripe: 'upright' },   // 幼苗俯拍叶球，成株侧视
  potato: { young: 'ground', ripe: 'upright' },    // 幼苗俯拍（根在右下），成株侧视
  radish: { young: 'upright', ripe: 'upright' },   // 两阶段都侧视（根须在下）
  strawberry: { young: 'upright', ripe: 'upright' }, // 两阶段都侧视
  // 花丛类：花球/花丛俯拍
  chrysanthemum: { young: 'ground', ripe: 'ground' }
}

/**
 * 取某作物某阶段的姿态。
 * @param {string} cropId 作物 id
 * @param {string} tex    'young' | 'ripe'
 * @returns {'upright'|'ground'}
 */
export function postureOf(cropId, tex) {
  const p = CROP_POSTURE[cropId]
  if (p && p[tex]) return p[tex]
  return 'upright'
}

/** 取某作物的 alphaTest 阈值（未登记则用通用值）。 */
export function alphaCutoffOf(crop) {
  if (ALPHA_CUTOFF[crop] != null) return ALPHA_CUTOFF[crop]
  const norm = normalizeCrop(crop)
  return ALPHA_CUTOFF[norm] != null ? ALPHA_CUTOFF[norm] : 0.4
}
