/**
 * 农场**跨语言名称映射**的唯一实现。
 *
 * <p>🔴 2026-10-27 抽出来的原因：同一批映射表在两个地方各写了一份 ——
 * 归因明细面板（{@code FarmAttributionPanel.vue}）与游戏历史摘要
 * （{@code GameHistory.vue}）。按项目铁律「同型代码只改一处 → 必须抽唯一实现」，
 * 这里把三张表 + 四个查表函数收成单一出口。
 *
 * <h2>这些表解决什么问题</h2>
 * <p>后端写进 {@code t_game_score.extra_json} 的字段有**中英混杂**的历史包袱：
 * <ul>
 *   <li><b>作物</b>：新数据带 {@code cropId}（稳定标识），老数据（2026-10-26 之前）
 *       只有中文名 {@code crop}（如「水稻·中稻/一季稻（籼）」，早期版本用 {@code ·} 分隔）。</li>
 *   <li><b>病虫害</b>：新数据带 {@code pests[].id}，老数据是 {@code "稻飞虱:0.711"}
 *       这种「名称:严重度」拼接串，<b>没有 id</b>。</li>
 *   <li><b>灾害</b>：新数据带 {@code disasters[].type}，老数据是 {@code "暴雨山洪:0.08"}，
 *       <b>没有 type</b>。</li>
 * </ul>
 * 没有 id 就无法映射到英文名 → <b>英文界面露中文</b>。
 *
 * <h2>🔴 查不到就查不到</h2>
 * <p>三个反查函数在查不到时返回 {@code null}/{@code ''}，
 * <b>不猜、不兜底</b>。例如「蚜虫」有 4 个不同的 id（{@code flower_aphid} /
 * {@code wheat_aphid} / …），老数据丢了 id 就无法区分 ——
 * 此时随便兜底成某一个，等于<b>编造数据</b>。
 *
 * <h2>🔴 同名多 id 的处理</h2>
 * <p>反查表按<b>后端注册顺序</b>取第一个匹配（见各表注释）。
 * 老数据本就丢了 id，选一个<b>合理且不误导</b>的即可（英文名本来就近似）。
 *
 * <p>🔴 与 {@code FarmAchievement.vue} 的 {@code CROP_EN} 同源同内容 ——
 * 作物名是「游戏内所有界面共用」的名称，不是某一个面板专用文案，
 * 所以它属于这里而不是某个组件的局部常量。
 */

/* ================================================================== *
 * 1. 作物
 * ================================================================== */

/**
 * 作物英文名表（22 种，与后端 {@code CropCatalog} 的 id 一一对应）。
 *
 * <p>🔴 <b>为什么不是从 i18n 消息表取</b>：i18n 里的 key 是扁平字符串
 * （{@code farm.attr.xxx}），作物名不是「归因面板专用文案」，
 * 而是「游戏内所有界面共用」的名称 —— 放进某个面板会变成第二份来源。
 *
 * <p>🔴 <b>缺项时退回后端中文名</b>，至少不是空白。
 */
export const CROP_EN = {
  rice_mid: 'Rice',
  wheat_winter: 'Winter Wheat',
  corn_spring: 'Maize',
  soybean: 'Soybean',
  millet: 'Millet',
  potato: 'Potato',
  cabbage: 'Chinese Cabbage',
  tomato: 'Tomato',
  cucumber: 'Cucumber',
  chili: 'Chili Pepper',
  eggplant: 'Eggplant',
  yardlong_bean: 'Yardlong Bean',
  radish: 'Radish',
  strawberry: 'Strawberry',
  watermelon: 'Watermelon',
  melon: 'Melon',
  grape: 'Grape',
  pear: 'Pear',
  chrysanthemum: 'Chrysanthemum',
  sunflower: 'Sunflower',
  tulip: 'Tulip',
  rose: 'Rose'
}

/**
 * 中文名 → 作物 id 的**反查表**（22 种，与后端 {@code CropCatalog} 一一对应）。
 *
 * <p>🔴 <b>为什么需要它</b>：2026-10-26 之前写入的成绩（{@code extra_json}）
 * <b>没有 {@code cropId} 字段</b>，只有中文名 {@code crop}。
 * 没有 id 就连带出两个 bug：
 * <ol>
 *   <li><b>单位判错</b>：水稻的 {@code thousandGrainWeight} = 27.88（千粒重），
 *       但没有 id 就走「非禾谷」分支 → 显示成「单果重 27.8 g」（错了 1000 倍口径）。</li>
 *   <li><b>英文界面露中文</b>：只能退回后端中文名。</li>
 * </ol>
 *
 * <p>名字取后端 {@code CropCatalog} 的全名与简称，外加历史遗留的旧名
 * （{@code 水稻·中稻/一季稻（籼）} —— 早期版本用 {@code ·} 分隔）。
 */
export const CROP_ID_BY_NAME = {
  '水稻（中稻/一季稻）': 'rice_mid',
  '水稻': 'rice_mid',
  '水稻·中稻/一季稻（籼）': 'rice_mid',
  '冬小麦（半冬性）': 'wheat_winter',
  '冬小麦': 'wheat_winter',
  '玉米（春/夏播）': 'corn_spring',
  '玉米': 'corn_spring',
  '大豆（夏播）': 'soybean',
  '大豆': 'soybean',
  '谷子（春播）': 'millet',
  '谷子': 'millet',
  '马铃薯（春播）': 'potato',
  '马铃薯': 'potato',
  '大白菜（秋播）': 'cabbage',
  '大白菜': 'cabbage',
  '番茄（春/秋茬）': 'tomato',
  '番茄': 'tomato',
  '黄瓜（春茬）': 'cucumber',
  '黄瓜': 'cucumber',
  '辣椒（春茬鲜椒）': 'chili',
  '辣椒': 'chili',
  '茄子（春茬）': 'eggplant',
  '茄子': 'eggplant',
  '豆角（春茬）': 'yardlong_bean',
  '豆角': 'yardlong_bean',
  '萝卜（秋播）': 'radish',
  '萝卜': 'radish',
  '草莓（秋植春收）': 'strawberry',
  '草莓': 'strawberry',
  '西瓜（春茬）': 'watermelon',
  '西瓜': 'watermelon',
  '甜瓜（春茬厚皮）': 'melon',
  '甜瓜': 'melon',
  '葡萄（休眠→春梢）': 'grape',
  '葡萄': 'grape',
  '梨（秋播）': 'pear',
  '梨': 'pear',
  '菊花（切花）': 'chrysanthemum',
  '菊花': 'chrysanthemum',
  '向日葵（籽用）': 'sunflower',
  '向日葵': 'sunflower',
  '郁金香（秋植球根）': 'tulip',
  '郁金香': 'tulip',
  '月季（切花）': 'rose',
  '月季': 'rose'
}

/** 禾谷类（千粒重）vs 园艺类（单果重）—— 与 GameFarm.vue 的 isGrainCrop 同一口径。 */
export const GRAIN_CROPS = [
  'rice_mid', 'wheat_winter', 'corn_spring', 'soybean', 'millet'
]

/**
 * 从 {@code extra_json} 里解析作物 id。
 *
 * @param {object} e extra_json 解析后的对象
 * @returns {string} 作物 id；查不到返回 {@code ''}
 */
export function cropIdOf (e) {
  if (!e) return ''
  if (e.cropId) return e.cropId
  const name = e.crop
  if (!name) return ''
  return CROP_ID_BY_NAME[name] || ''
}

/**
 * 作物显示名。
 *
 * <p>🔴 口径：英文界面优先用 {@code cropId} 映射的英文名；
 * 映射不到就退回后端中文名（宁可中文也不要空白）。
 *
 * @param {object} e extra_json 解析后的对象
 * @param {boolean} isEn 当前是否英文界面
 * @returns {string}
 */
export function cropLabelOf (e, isEn) {
  if (!e) return ''
  const id = cropIdOf(e)
  if (isEn && id && CROP_EN[id]) return CROP_EN[id]
  return e.crop || (id && CROP_EN[id]) || ''
}

/* ================================================================== *
 * 1.5 农业区
 * ================================================================== */

/**
 * 农业区 id → 双语名（**只有后端真实存在的 4 个区**）。
 *
 * <p>🔴 2026-10-28：口径对齐后端 {@code ClimateModel.ZONES} ——
 * 它只注册了 4 个区：江南 / 华南 / 黄淮海 / 东北。
 *
 * <p>⚠️ FieldSetup.vue 原来硬编码了 <b>8 个</b>区（含西南 / 西北 / 青藏 / 高原），
 * 那 4 个是<b>幻觉区</b> —— 后端根本不存在，永远匹配不上，
 * 只会在兜底分支里把 id 原样吐出来。已删除。
 *
 * <p>⚠️ 区名是<b>跨界面共用</b>的名称（选地面板、归因面板都要用），
 * 属于「游戏内所有界面共用」而非某一面板专用文案 —— 所以放这里，
 * 而不是塞进某个组件的局部常量。
 */
export const ZONE_NAME = {
  jiangnan: { zh: '江南', en: 'Jiangnan' },
  huanan: { zh: '华南', en: 'South China' },
  huanghuaihai: { zh: '黄淮海', en: 'Huang-Huai-Hai' },
  dongbei: { zh: '东北', en: 'Northeast' }
}

/**
 * 农业区 id → 显示名（**唯一出口**）。
 *
 * <p>🔴 2026-10-28：原来有<b>两份</b> {@code zoneNameOf()}：
 * <ul>
 *   <li>FieldSetup.vue —— 硬编码 8 个中文名，<b>英文界面漏中文「江南」</b>；</li>
 *   <li>FarmAttributionPanel.vue —— 走 i18n（{@code farm.zoneName.*}），
 *       但 i18n 只覆盖 4 个区，且查不到时把 id 原样吐出来。</li>
 * </ul>
 * 两者<b>数据源不同、行为不同</b> —— 同一屏上「江南」和「jiangnan」可能同时出现。
 *
 * <p><b>解析顺序</b>：
 * <ol>
 *   <li>i18n 的 {@code farm.zoneName.<id>} —— <b>随界面语言变化</b>，
 *       英文界面走这条拿英文名；</li>
 *   <li>内置双语表 {@link ZONE_NAME} —— 没传 t 时的按语言兜底；</li>
 *   <li>后端下发的区名（{@code zones[].name}，中文）；</li>
 *   <li>兜底：id 原样返回（<b>不猜、不编造</b>）。</li>
 * </ol>
 *
 * ⚠️ i18n <b>必须在后端名之前</b>：后端下发的 {@code name} 永远是中文，
 * 若它优先，英文界面照样会露「江南」—— 那正是本次要修的 bug。
 *
 * @param {string} id 农业区 id
 * @param {function} [t] i18n 的 t 函数（可选；不传则只用内置表）
 * @param {string} [fallbackName] 后端下发的区名
 * @param {boolean} [isEn=false] 当前是否英文界面（只影响内置表兜底）
 * @returns {string}
 */
export function zoneNameOf (id, t, fallbackName, isEn = false) {
  if (!id) return ''
  if (typeof t === 'function') {
    const key = 'farm.zoneName.' + id
    const v = t(key)
    if (v && v !== key) return v
  }
  const z = ZONE_NAME[id]
  if (z) return isEn ? z.en : z.zh
  if (fallbackName) return fallbackName
  return id
}

/* ================================================================== *
 * 2. 病虫害
 * ================================================================== */

/**
 * 病虫害中文名 → id 的**反查表**（38 种，与后端 {@code PestEngine} 一一对应）。
 *
 * <p>🔴 老数据的 {@code pests} 是 {@code "稻飞虱:0.711"} 这种
 * 「名称:严重度」拼接串，<b>没有 id</b> —— 英文界面无法映射，只能露中文。
 *
 * <p>🔴 <b>同名多 id</b>：「蚜虫」有 {@code flower_aphid} / {@code wheat_aphid} 等，
 * 「霜霉病」有 {@code leafy_downy} / {@code cuc_downy}，「白粉病」有 3 个，
 * 「灰霉病」有 2 个。这里按<b>后端注册顺序</b>取第一个匹配。
 */
export const PEST_ID_BY_NAME = {
  '稻瘟病': 'blast',
  '纹枯病': 'sheath_blight',
  '白叶枯病': 'bacterial_blight',
  '二化螟': 'stem_borer',
  '稻飞虱': 'planthopper',
  '稻纵卷叶螟': 'leaf_roller',
  '小麦赤霉病': 'wheat_scab',
  '条锈病': 'stripe_rust',
  '白粉病': 'powdery_mildew',
  '麦蚜': 'wheat_aphid',
  '玉米大斑病': 'northern_leaf_blight',
  '玉米螟': 'corn_borer',
  '草地贪夜蛾': 'fall_armyworm',
  '根腐病': 'root_rot',
  '大豆蚜': 'soybean_aphid',
  '胞囊线虫': 'cyst_nematode',
  '霜霉病': 'leafy_downy',
  '软腐病': 'soft_rot',
  '菜青虫': 'cabbage_worm',
  '瓜蚜': 'melon_aphid',
  '早疫病': 'early_blight',
  '灰霉病': 'gray_mold',
  '红蜘蛛': 'spider_mite',
  '蚜虫': 'flower_aphid',
  '谷子瘟病': 'millet_blast',
  '谷子白粉病': 'millet_powdery',
  '谷子螟': 'millet_moth',
  '马铃薯晚疫病': 'potato_late_blight',
  '马铃薯早疫病': 'potato_early_blight',
  '马铃薯环腐病': 'potato_ringrot',
  '马铃薯蚜虫': 'potato_aphid',
  '梨黑星病': 'pear_scab',
  '梨锈病': 'pear_rust',
  '梨介壳虫': 'pear_scale'
}

/**
 * 解析一条病虫害记录（兼容新老两种格式）。
 *
 * <p>新格式：{@code {id, name, type, severity}}；
 * 老格式：{@code "稻飞虱:0.711"}（名称 + 冒号 + 严重度，无 id）。
 *
 * @param {object|string} p
 * @returns {{id: string, name: string, type: string, severity: number}}
 */
export function parsePestHit (p) {
  if (p && typeof p === 'object') {
    return {
      id: p.id || '',
      name: p.name || '',
      type: p.type || '',
      severity: Number(p.severity) || 0
    }
  }
  const text = String(p == null ? '' : p)
  const i = text.lastIndexOf(':')
  if (i > 0) {
    const name = text.slice(0, i)
    return {
      // 🔴 老格式没有 id —— 按中文名反查补上，否则英文界面露中文。
      id: PEST_ID_BY_NAME[name] || '',
      name,
      type: '',
      severity: Number(text.slice(i + 1)) || 0
    }
  }
  return { id: PEST_ID_BY_NAME[text] || '', name: text, type: '', severity: 0 }
}

/* ================================================================== *
 * 3. 灾害
 * ================================================================== */

/**
 * 病虫害/灾害的显示名（**统一出口**）。
 *
 * <p>🔴 2026-10-28：原来有<b>两份</b>几乎相同的回退模板：
 * <ul>
 *   <li>GameFarm.vue 的 {@code pestName(p)} —— 回退 {@code p.name}；</li>
 *   <li>FarmAttributionPanel.vue 的 {@code pestLabel(p)} —— 回退
 *       {@code p.name || p.id}（多了个 id 兜底），{@code disasterLabel(d)} 同理。</li>
 * </ul>
 * 两份的差异只是「回退到 name 还是 name||id」—— 语义相同，<b>必然分叉</b>。
 *
 * <p><b>解析顺序</b>：
 * <ol>
 *   <li>i18n 的 {@code farm.pestName.<id>} / {@code farm.disasterName.<type>} —— 随语言变化；</li>
 *   <li>后端下发的中文名（{@code name}）；</li>
 *   <li>id / type 原样（<b>不猜、不编造</b>）。</li>
 * </ol>
 *
 * @param {object} p 病虫害记录（需含 id / name）
 * @param {function} t i18n 的 t 函数
 * @returns {string}
 */
export function pestLabelOf (p, t) {
  if (!p) return ''
  if (!p.id) return p.name || ''
  if (typeof t === 'function') {
    const key = 'farm.pestName.' + p.id
    const v = t(key)
    if (v && v !== key) return v
  }
  return p.name || p.id
}

/**
 * 灾害的显示名（**统一出口**，见 {@link pestLabelOf}）。
 *
 * @param {object} d 灾害记录（需含 type / name）
 * @param {function} t i18n 的 t 函数
 * @returns {string}
 */
export function disasterLabelOf (d, t) {
  if (!d) return ''
  if (!d.type) return d.name || ''
  if (typeof t === 'function') {
    const key = 'farm.disasterName.' + d.type
    const v = t(key)
    if (v && v !== key) return v
  }
  return d.name || d.type
}

/**
 * 灾害中文名 → 类型标识的反查表（4 种，与后端 {@code DisasterEngine} 一致）。
 *
 * <p>与 pest 同理：老数据的 {@code disasters} 是 {@code "暴雨山洪:0.08"}，没有 type。
 */
export const DISASTER_TYPE_BY_NAME = {
  '暴雨山洪': 'FLASH_FLOOD',
  '连阴雨': 'CONTINUOUS_RAIN',
  '低温冻害': 'FREEZE',
  '持续高温': 'HEATWAVE'
}

/**
 * 解析一条灾害记录（兼容新老两种格式）。
 *
 * <p>新格式：{@code {type, name, date, intensity, loss, note}}；
 * 老格式：{@code "暴雨山洪:0.08"}（名称 + 冒号 + 损失，无 type）。
 *
 * @param {object|string} d
 * @returns {{type: string, name: string, date: string, loss: number}}
 */
export function parseDisasterHit (d) {
  if (d && typeof d === 'object') {
    return {
      type: d.type || '',
      name: d.name || '',
      date: d.date || '',
      loss: Number(d.loss) || 0
    }
  }
  const text = String(d == null ? '' : d)
  const i = text.lastIndexOf(':')
  if (i > 0) {
    const name = text.slice(0, i)
    return {
      // 🔴 老格式没有 type —— 按中文名反查补上，否则英文界面露中文。
      type: DISASTER_TYPE_BY_NAME[name] || '',
      name,
      date: '',
      loss: Number(text.slice(i + 1)) || 0
    }
  }
  return { type: DISASTER_TYPE_BY_NAME[text] || '', name: text, date: '', loss: 0 }
}

/* ================================================================== *
 * 4. 收割判定
 * ================================================================== */

/**
 * 🔴 2026-10-27：**「这块地能不能收割」的判定口径**（前端唯一出口）。
 *
 * <p><b>为什么要有这个函数</b>：此前有 <b>4 个组件</b>各自写死
 * {@code dvs >= 0.92}（FarmBoard / FarmWorldMap / FarmOverviewPanel /
 * GameFarm 的 fallback SVG）。0.92 是<b>水稻口径</b>，而各作物的收割阈值
 * 本来就不同（番茄 0.82 · 白菜 0.85 · 瓜类 0.88 · 大豆 0.90 · 玉米 0.95），
 * 于是：
 * <ul>
 *   <li>番茄到 0.85 就该收了，UI 却把「收割」按钮<b>禁用</b>；</li>
 *   <li>玉米没熟（0.93）UI 却<b>高亮成可收</b>，玩家收了一茬不熟的玉米。</li>
 * </ul>
 *
 * <p><b>判定顺序</b>（很重要）：
 * <ol>
 *   <li>后端给了 {@code harvestable} → <b>直接用</b>（后端是唯一权威）；</li>
 *   <li>后端给了 {@code harvestDvs} → 用它比 {@code dvs}（兼容只给阈值不给结论的接口）；</li>
 *   <li>两者都没有 → 退回 0.92（老数据 / 老接口的最后兜底）。</li>
 * </ol>
 * ⚠️ 兜底值只是「老接口没这个字段时别崩」，不是新逻辑的正常路径 ——
 * 新逻辑永远走后端给的 {@code harvestable}。
 *
 * @param {object} p 地块对象（后端 {@code /farm/plots} 列表元素）
 * @returns {boolean}
 */
export function isHarvestable (p) {
  if (!p) return false
  if (typeof p.harvestable === 'boolean') return p.harvestable
  const threshold = typeof p.harvestDvs === 'number' ? p.harvestDvs : 0.92
  return Number(p.dvs || 0) >= threshold
}
