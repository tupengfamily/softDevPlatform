package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 园艺作物配置：蔬菜 / 水果 / 花卉 / 块茎 / 果树。
 *
 * <p><b>为什么单独一个类</b>：{@link CropConfig} 是纯参数 record，新增作物不需要新类型；
 * 但这些作物的参数（积温、产量构成、热害档、物候名）差异很大，散在 {@link CropCatalog}
 * 里会让那个文件失控。这里一种作物一个静态工厂方法，参数集中、便于对照标定。
 *
 * <p><b>产量构成的口径迁移</b>（重要）：引擎沿用禾谷类的乘性模型
 * {@code 产量 = 单位数 × 单株果数 × 商品率 × 单果重}。
 * 对蔬菜/花卉，四项分别映射为：
 * <ul>
 *   <li>{@code paniclesPot} → 亩株数（草莓按丛、菊花按支）</li>
 *   <li>{@code floretsPot} → 单株果数 / 花枝数</li>
 *   <li>{@code fertilityBase} → <b>商品率</b>（畸形果、裂果、病害果直接扣这一项）</li>
 *   <li>{@code weightPot} → 单果重（g）</li>
 * </ul>
 * 数学上完全等价，UI 侧按 {@code category} 换成人话标签，不在这里造第二套模型。
 *
 * <p><b>三条硬约束</b>：
 * <ol>
 *   <li>{@code aatRef} 是<b>生物学零度</b>，不是适温。番茄 10℃、叶菜 5℃、西瓜 12℃——
 *       设错会让"积温到没到"整体偏移，所有历史回放都变；</li>
 *   <li>{@code heatProfiles.tStart} 是<b>百叶箱 T_max</b> 口径，与日均温口径不能混；</li>
 *   <li>{@code yieldRef} 是达标线分母，取<b>露地常规栽培</b>的中上水平，
 *       不取设施高产纪录——否则永远到不了 L0。</li>
 * </ol>
 *
 * <p><b>🔴 weightPer 的约定（2026-10-24 补充）</b>：禾谷类用<b>千粒重</b>（1000），
 * 园艺作物用<b>单果重</b>（1）。这个字段存在的原因是产量公式里隐含了「千粒」这个 1000 的除数——
 * 园艺作物直接填单果重会差 1000 倍（白菜 5000kg/亩 会算成 5kg/亩，评级一律 L5）。
 * 新增作物时<b>先问一句「这是千粒还是单果」</b>，填错就是 1000 倍误差。
 *
 * <p>价格与产量均按露地栽培标定；设施栽培（大棚）不在本期范围，相关作物在北方冬季会被判冻死，
 * 这是符合预期的结果，不是 bug。
 */
public final class HortConfigs {

    private HortConfigs() {
    }

    // ==================================================================
    //  蔬菜
    // ==================================================================

    /** 大白菜（秋播）：生育期短、需氮高、产量大，价格低——典型的"薄利多销"作物。 */
    public static CropConfig cabbage() {
        return new CropConfig(
                "cabbage", "大白菜（秋播）",
                5, 25, 1600, 2.4, 1.25,
                2200,   // 亩株数
                1,      // 单株一球
                0.90,   // 商品率
                2500,   // 单球重 g
                6000,   // 产量上限
                List.of(
                        new CropConfig.HeatProfile("莲座期", 0.10, 0.45, 32, 37, 30, 0.5, "PANICLE"),
                        new CropConfig.HeatProfile("结球期", 0.45, 0.85, 32, 37, 30, 1.1, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.85, 1.30, 33, 38, 31, 0.7, "FERTILITY")),
                0.020, 0.010, 0.012, 0.030,
                0.25, 0.15, 0.30, 0.35,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.10),
                        new CropConfig.PhenoStage("幼苗期", 0.10, 0.28),
                        new CropConfig.PhenoStage("莲座期", 0.28, 0.52),
                        new CropConfig.PhenoStage("结球期", 0.52, 0.85),
                        new CropConfig.PhenoStage("成熟可收", 0.85, 1.30)),
                false, 0, 0, 15.0, 5000, "cabbage", 1);
    }

    /** 番茄（春茬/秋茬）：连续坐果，商品率对畸形果与裂果极敏感。 */
    public static CropConfig tomato() {
        return new CropConfig(
                "tomato", "番茄（春/秋茬）",
                // aatReq 取早熟品种（1900）而不是中晚熟 2200：露地栽培在东北只能种早熟，
                // 用晚熟值会让整个东北判成"理论不可行"，与实际生产不符
                10, 28, 1900, 2.6, 1.28,
                1800,   // 亩株数
                20,     // 单株果数
                0.85,   // 商品率
                150,    // 单果重 g
                7000,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 33, 38, 32, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("开花坐果", 0.30, 0.55, 33, 38, 32, 1.4, "FLORET"),
                        new CropConfig.HeatProfile("膨果期", 0.55, 0.85, 34, 39, 33, 1.2, "WEIGHT"),
                        new CropConfig.HeatProfile("采收期", 0.85, 1.30, 34, 39, 33, 0.8, "FERTILITY")),
                0.030, 0.020, 0.025, 0.020,
                0.20, 0.25, 0.35, 0.30,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.10),
                        new CropConfig.PhenoStage("幼苗期", 0.10, 0.28),
                        new CropConfig.PhenoStage("开花坐果", 0.28, 0.55),
                        new CropConfig.PhenoStage("膨果期", 0.55, 0.82),
                        new CropConfig.PhenoStage("成熟采收", 0.82, 1.30)),
                false, 0, 0, 18.0, 4500, "tomato", 1);
    }

    /** 黄瓜（春茬）：连续结瓜，需水需氮都高，霜霉病是头号风险。 */
    public static CropConfig cucumber() {
        return new CropConfig(
                "cucumber", "黄瓜（春茬）",
                10, 28, 1800, 2.5, 1.26,
                3000,   // 亩株数
                15,     // 单株瓜数
                0.90,
                120,    // 单瓜重 g
                6500,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 33, 38, 31, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("抽蔓开花", 0.25, 0.50, 33, 38, 31, 1.2, "FLORET"),
                        new CropConfig.HeatProfile("结瓜期", 0.50, 0.90, 34, 39, 32, 1.3, "WEIGHT"),
                        new CropConfig.HeatProfile("采收期", 0.90, 1.30, 34, 39, 32, 0.8, "FERTILITY")),
                0.030, 0.018, 0.022, 0.022,
                0.20, 0.22, 0.32, 0.30,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.10),
                        new CropConfig.PhenoStage("幼苗期", 0.10, 0.25),
                        new CropConfig.PhenoStage("抽蔓开花", 0.25, 0.50),
                        new CropConfig.PhenoStage("结瓜期", 0.50, 0.88),
                        new CropConfig.PhenoStage("采收期", 0.88, 1.30)),
                false, 0, 0, 16.0, 5000, "cucumber", 1);
    }

    /** 辣椒（春茬鲜椒）：单株果多但果小，日灼与脐腐对商品率影响大。 */
    public static CropConfig chili() {
        return new CropConfig(
                "chili", "辣椒（春茬鲜椒）",
                10, 29, 2400, 2.6, 1.27,
                4000,   // 亩株数
                25,     // 单株果数
                0.85,
                30,     // 单果重 g
                3500,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 34, 39, 32, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("开花坐果", 0.30, 0.55, 34, 39, 32, 1.2, "FLORET"),
                        new CropConfig.HeatProfile("果实膨大", 0.55, 0.85, 35, 40, 33, 1.1, "WEIGHT"),
                        new CropConfig.HeatProfile("红熟采收", 0.85, 1.30, 35, 40, 33, 0.7, "FERTILITY")),
                0.025, 0.016, 0.020, 0.018,
                0.20, 0.20, 0.30, 0.25,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.12),
                        new CropConfig.PhenoStage("幼苗期", 0.12, 0.30),
                        new CropConfig.PhenoStage("开花坐果", 0.30, 0.55),
                        new CropConfig.PhenoStage("果实膨大", 0.55, 0.85),
                        new CropConfig.PhenoStage("成熟采收", 0.85, 1.30)),
                false, 0, 0, 15.0, 2500, "chili", 1);
    }

    // ==================================================================
    //  水果（一年生栽培型）
    // ==================================================================

    /**
     * 草莓（秋植春收）。
     *
     * <p>草莓是多年生，但生产上按一年生栽培（当年定植、当年收、收后翻耕），
     * 所以能用同一套"播种→成熟"模型，不需要多年生引擎。
     * 也正因为要越冬，它带春化需求——与冬小麦走同一条越冬通道。
     */
    public static CropConfig strawberry() {
        return new CropConfig(
                "strawberry", "草莓（秋植春收）",
                5, 25, 1500, 2.2, 1.22,
                8000,   // 亩株数
                14,     // 单株果数
                0.90,
                15,     // 单果重 g
                3000,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.10, 0.35, 30, 35, 29, 0.4, "PANICLE"),
                        new CropConfig.HeatProfile("现蕾开花", 0.45, 0.65, 30, 35, 29, 1.3, "FLORET"),
                        new CropConfig.HeatProfile("果实膨大", 0.65, 0.90, 31, 36, 30, 1.2, "WEIGHT"),
                        new CropConfig.HeatProfile("采收期", 0.90, 1.30, 31, 36, 30, 0.8, "FERTILITY")),
                0.030, 0.020, 0.025, 0.020,
                0.20, 0.22, 0.32, 0.28,
                List.of(
                        new CropConfig.PhenoStage("缓苗越冬", 0.00, 0.35),
                        new CropConfig.PhenoStage("返青现蕾", 0.35, 0.55),
                        new CropConfig.PhenoStage("开花坐果", 0.55, 0.68),
                        new CropConfig.PhenoStage("果实膨大", 0.68, 0.88),
                        new CropConfig.PhenoStage("采收期", 0.88, 1.30)),
                true, -12, 40, 12.0, 1500, "strawberry", 1);
    }

    /** 西瓜（春茬）：单株果少果大，坐果期高温会大量化瓜。 */
    public static CropConfig watermelon() {
        return new CropConfig(
                "watermelon", "西瓜（春茬）",
                12, 30, 2200, 2.6, 1.27,
                700,    // 亩株数
                1.2,    // 单株果数
                0.85,
                5000,   // 单果重 g
                5000,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 35, 40, 33, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("伸蔓开花", 0.25, 0.50, 35, 40, 33, 1.3, "FLORET"),
                        new CropConfig.HeatProfile("膨瓜期", 0.50, 0.88, 36, 41, 34, 1.4, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.88, 1.30, 36, 41, 34, 0.6, "FERTILITY")),
                0.025, 0.015, 0.020, 0.018,
                0.20, 0.18, 0.30, 0.25,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.12),
                        new CropConfig.PhenoStage("幼苗期", 0.12, 0.28),
                        new CropConfig.PhenoStage("伸蔓开花", 0.28, 0.50),
                        new CropConfig.PhenoStage("膨瓜期", 0.50, 0.88),
                        new CropConfig.PhenoStage("成熟采收", 0.88, 1.30)),
                false, 0, 0, 12.0, 3500, "watermelon", 1);
    }

    /** 甜瓜（春茬厚皮）：与西瓜同族但单果小、株数密。 */
    public static CropConfig melon() {
        return new CropConfig(
                "melon", "甜瓜（春茬厚皮）",
                12, 30, 1900, 2.5, 1.26,
                1500,   // 亩株数
                1.5,    // 单株果数
                0.85,
                1200,   // 单果重 g
                4000,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 34, 39, 32, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("伸蔓开花", 0.25, 0.50, 34, 39, 32, 1.3, "FLORET"),
                        new CropConfig.HeatProfile("膨瓜期", 0.50, 0.88, 35, 40, 33, 1.3, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.88, 1.30, 35, 40, 33, 0.6, "FERTILITY")),
                0.025, 0.015, 0.020, 0.018,
                0.20, 0.18, 0.30, 0.25,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.12),
                        new CropConfig.PhenoStage("幼苗期", 0.12, 0.28),
                        new CropConfig.PhenoStage("伸蔓开花", 0.28, 0.50),
                        new CropConfig.PhenoStage("膨瓜期", 0.50, 0.88),
                        new CropConfig.PhenoStage("成熟采收", 0.88, 1.30)),
                false, 0, 0, 11.0, 2500, "melon", 1);
    }

    // ==================================================================
    //  花卉（切花 / 观赏）
    // ==================================================================

    /**
     * 菊花（切花，秋花型）。
     *
     * <p>菊花是短日植物，实际花期由光周期决定；本模型没有光周期通道，
     * 用「积温 + 播期」近似——秋播型在短日季自然开花，误差在 1–2 周，可接受。
     * 产量单位按 kg 当量（切花约 40g/支），与其它作物共用同一套结算。
     */
    public static CropConfig chrysanthemum() {
        return new CropConfig(
                "chrysanthemum", "菊花（切花）",
                // 6 月定植 → 10 月开花只有 ~150 天窗口，2000 积温在江南会卡在成熟前，
                // 1750 对应生产中常见的早花型切菊
                10, 26, 1750, 2.3, 1.22,
                20000,  // 亩株数（切花密植）
                1,      // 单株花枝数
                0.90,   // 商品率（弯颈、畸形花扣这里）
                40,     // 单支重 g
                1000,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.25, 30, 35, 29, 0.4, "PANICLE"),
                        new CropConfig.HeatProfile("摘心分枝", 0.25, 0.50, 30, 35, 29, 0.8, "FLORET"),
                        new CropConfig.HeatProfile("现蕾开花", 0.50, 0.85, 29, 34, 28, 1.5, "FLORET"),
                        new CropConfig.HeatProfile("切花采收", 0.85, 1.30, 29, 34, 28, 0.9, "FERTILITY")),
                0.030, 0.022, 0.026, 0.020,
                0.20, 0.25, 0.35, 0.25,
                List.of(
                        new CropConfig.PhenoStage("定植缓苗", 0.00, 0.20),
                        new CropConfig.PhenoStage("摘心分枝", 0.20, 0.45),
                        new CropConfig.PhenoStage("现蕾期", 0.45, 0.65),
                        new CropConfig.PhenoStage("开花期", 0.65, 0.88),
                        new CropConfig.PhenoStage("切花采收", 0.88, 1.30)),
                false, 0, 0, 14.0, 800, "chrysanthemum", 1);
    }

    /** 向日葵（籽用/观赏兼用）：耐热耐旱，花期高温会显著降低结实率。 */
    public static CropConfig sunflower() {
        return new CropConfig(
                "sunflower", "向日葵（籽用）",
                8, 30, 1800, 2.4, 1.24,
                3500,   // 亩株数
                1,      // 单株一盘
                0.90,
                60,     // 盘粒重 g
                350,
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.25, 35, 40, 33, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("现蕾开花", 0.40, 0.65, 35, 40, 33, 1.5, "FLORET"),
                        new CropConfig.HeatProfile("灌浆期", 0.65, 0.90, 35, 40, 33, 1.2, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.90, 1.30, 35, 40, 33, 0.5, "FERTILITY")),
                0.025, 0.018, 0.020, 0.020,
                0.20, 0.22, 0.30, 0.28,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.15),
                        new CropConfig.PhenoStage("苗期", 0.15, 0.40),
                        new CropConfig.PhenoStage("现蕾开花", 0.40, 0.65),
                        new CropConfig.PhenoStage("灌浆期", 0.65, 0.90),
                        new CropConfig.PhenoStage("成熟收获", 0.90, 1.30)),
                false, 0, 0, 10.0, 250, "sunflower", 1);
    }

    /**
     * 郁金香（秋植球根，春开花）。
     *
     * <p>必须经过低温春化才能抽花，否则只长叶——这条与冬小麦完全同构，
     * 所以直接复用 {@code winterHardy + vernalizationDays} 机制。
     * 球根耐寒（-18℃），是少数能在东北露地越冬的花卉。
     */
    public static CropConfig tulip() {
        return new CropConfig(
                "tulip", "郁金香（秋植球根）",
                5, 22, 1200, 2.0, 1.20,
                25000,  // 亩株数（球）
                1,      // 单株一支
                0.90,
                25,     // 单支重 g
                700,
                List.of(
                        new CropConfig.HeatProfile("越冬期", 0.00, 0.40, 26, 32, 25, 0.2, "PANICLE"),
                        new CropConfig.HeatProfile("抽叶现蕾", 0.40, 0.62, 26, 32, 25, 1.4, "FLORET"),
                        new CropConfig.HeatProfile("开花期", 0.62, 0.86, 25, 30, 24, 1.6, "FLORET"),
                        new CropConfig.HeatProfile("切花采收", 0.86, 1.30, 25, 30, 24, 0.8, "FERTILITY")),
                0.025, 0.020, 0.028, 0.018,
                0.20, 0.25, 0.35, 0.22,
                List.of(
                        new CropConfig.PhenoStage("定植越冬", 0.00, 0.42),
                        new CropConfig.PhenoStage("返青抽叶", 0.42, 0.60),
                        new CropConfig.PhenoStage("现蕾期", 0.60, 0.72),
                        new CropConfig.PhenoStage("开花期", 0.72, 0.88),
                        new CropConfig.PhenoStage("切花采收", 0.88, 1.30)),
                true, -18, 60, 10.0, 500, "tulip", 1);
    }

    // ==================================================================
    //  2026-10-24 新增 8 种
    //
    //  口径约定（与上面 10 种一致）：
    //  · paniclesPot → 亩株数；floretsPot → 单株果数；fertilityBase → 商品率；
    //    weightPot → 单果/单薯/单串/单支重（g）；weightPer = 1。
    //  · 唯一例外是谷子：它是禾谷，weightPot 填千粒重、weightPer = 1000。
    // ==================================================================

    /**
     * 谷子（春播，粟/小米）。
     *
     * <p><b>🔴 这是本批唯一的禾谷类新增作物</b>，所以 {@code weightPer} 必须是
     * <b>1000</b>（千粒重），其余 7 种都是 1。
     *
     * <p><b>千粒重为什么是 8g</b>：小米（粟）脱壳后籽粒千粒重约 8g，
     * 而水稻是 28g、小麦 42g、玉米 300g —— 差 40 倍。
     * 这正是千粒重口径的价值：单看产量数字没有意义，必须配合「多少粒」。
     *
     * <p><b>产量构成</b>：谷子单穗粒数少（谷子穗小而密），所以按
     * 「穗数 × 穗粒数 × 千粒重」折算：亩穗数约 30 万、每穗 60 粒、
     * 千粒重 8g → 30万 × 60 × 8 / 1e6 ≈ 144 kg/亩，与谷子实际亩产 150–200kg 一致。
     *
     * <p><b>耐旱、耐瘠薄，但怕渍</b>：所以需氮不高（6 kg/亩），
     * 但生育前期必须蹲苗——这也是它在黄淮海与东北主栽的原因。
     */
    public static CropConfig millet() {
        return new CropConfig(
                "millet", "谷子（春播）",
                10, 28, 1900, 2.4, 1.24,
                300000,  // 亩穗数（谷子分蘖多、穗密）
                60,      // 单穗粒数
                0.92,    // 商品率（谷粒小，秕粒多，脱粒后折价）
                8.0,     // 千粒重 g（🔴 禾谷口径，不是单粒重）
                350,     // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("苗期蹲苗", 0.00, 0.25, 33, 38, 31, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("抽穗期", 0.45, 0.65, 34, 39, 32, 1.3, "FLORET"),
                        new CropConfig.HeatProfile("灌浆期", 0.65, 0.90, 34, 39, 32, 1.1, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.90, 1.30, 35, 40, 33, 0.5, "FERTILITY")),
                0.015, 0.010, 0.018, 0.012,
                0.20, 0.18, 0.45, 0.35,
                List.of(
                        new CropConfig.PhenoStage("播种出苗", 0.00, 0.15),
                        new CropConfig.PhenoStage("分蘖拔节", 0.15, 0.45),
                        new CropConfig.PhenoStage("抽穗扬花", 0.45, 0.65),
                        new CropConfig.PhenoStage("灌浆", 0.65, 0.90),
                        new CropConfig.PhenoStage("成熟收获", 0.90, 1.30)),
                false, 0, 0, 6.0, 150, "millet", 1000);
    }

    /**
     * 马铃薯（春播，块茎）。
     *
     * <p><b>产量构成的特殊之处</b>：马铃薯收的是<b>地下块茎</b>，
     * 不是地上器官。所以「单株薯数」是核心变量（约 8–12 个/株），
     * 单薯重按商品薯 150g 计。
     * 亩株 4500 穴 × 10 薯 × 150g × 商品率 0.85 ≈ 574 kg/亩，
     * 与春作马铃薯实际亩产 1500–2500kg 的差距来自
     * <b>本作只计「商品薯」且每穴按 1 株计</b>的简化口径 —— 不追设施高产纪录。
     *
     * <p><b>喜微酸、忌碱性土</b>（pH 5.0–6.5），所以形态族是独立的 TUBER，
     * 不是 GRAIN 的通用 5.5–7.0。
     */
    public static CropConfig potato() {
        return new CropConfig(
                "potato", "马铃薯（春播）",
                7, 24, 1700, 2.2, 1.22,
                3200,   // 亩穴数
                4,      // 单穴结薯数
                0.85,   // 商品率（青皮/畸形/烂薯扣这里）
                230,    // 单薯重 g
                2300,   // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.20, 30, 35, 28, 0.4, "PANICLE"),
                        new CropConfig.HeatProfile("块茎形成", 0.30, 0.60, 30, 35, 28, 1.0, "FLORET"),
                        new CropConfig.HeatProfile("块茎膨大", 0.55, 0.88, 31, 36, 29, 1.2, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.88, 1.30, 32, 37, 30, 0.6, "FERTILITY")),
                0.022, 0.014, 0.020, 0.020,
                0.20, 0.20, 0.30, 0.30,
                List.of(
                        new CropConfig.PhenoStage("催芽播种", 0.00, 0.15),
                        new CropConfig.PhenoStage("出苗现蕾", 0.15, 0.35),
                        new CropConfig.PhenoStage("块茎形成", 0.35, 0.60),
                        new CropConfig.PhenoStage("块茎膨大", 0.60, 0.88),
                        new CropConfig.PhenoStage("成熟收获", 0.88, 1.30)),
                false, 0, 0, 12.0, 2000, "potato", 1);
    }

    /**
     * 茄子（春茬，果菜）。
     *
     * <p>与番茄同属茄果类，但<b>更耐热</b>（茄子喜温怕涝）、
     * <b>单果更重</b>（250g vs 番茄 150g）、<b>单株果数更少</b>（8 vs 20）。
     * 商品率对「茄子褐纹病」和「蓟马」特别敏感。
     */
    public static CropConfig eggplant() {
        return new CropConfig(
                "eggplant", "茄子（春茬）",
                12, 29, 2000, 2.6, 1.27,
                2200,   // 亩株数
                8,      // 单株果数
                0.85,   // 商品率
                250,    // 单果重 g
                4500,   // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 33, 38, 32, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("开花坐果", 0.30, 0.55, 34, 39, 33, 1.3, "FLORET"),
                        new CropConfig.HeatProfile("果实膨大", 0.55, 0.85, 35, 40, 34, 1.1, "WEIGHT"),
                        new CropConfig.HeatProfile("采收期", 0.85, 1.30, 35, 40, 34, 0.8, "FERTILITY")),
                0.028, 0.018, 0.024, 0.020,
                0.20, 0.25, 0.35, 0.30,
                List.of(
                        new CropConfig.PhenoStage("发芽出苗", 0.00, 0.12),
                        new CropConfig.PhenoStage("幼苗期", 0.12, 0.30),
                        new CropConfig.PhenoStage("开花坐果", 0.30, 0.55),
                        new CropConfig.PhenoStage("果实膨大", 0.55, 0.85),
                        new CropConfig.PhenoStage("成熟采收", 0.85, 1.30)),
                false, 0, 0, 16.0, 3500, "eggplant", 1);
    }

    /**
     * 豆角（春茬，荚菜）。
     *
     * <p><b>形态上属藤本</b>（要搭架爬蔓），所以形态族是 VINE 而不是 BUSH ——
     * 这直接影响适区评级里的无霜期与 pH 口径，也影响前端贴图。
     *
     * <p>豆角<b>不耐涝也不耐寒</b>，最怕「伏天雨后落叶」，所以需氮中等、
     * 商品率对「锈病 + 豆荚螟」敏感。单荚重 30g 是嫩荚商品标准。
     */
    public static CropConfig yardlongBean() {
        return new CropConfig(
                "yardlong_bean", "豆角（春茬）",
                12, 28, 1800, 2.5, 1.26,
                3200,   // 亩株数（每穴 2 株，按 1 株计则翻倍，这里取折算值）
                25,     // 单株荚数
                0.85,   // 商品率
                30,     // 单荚重 g
                3000,   // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.15, 33, 38, 31, 0.3, "PANICLE"),
                        new CropConfig.HeatProfile("伸蔓开花", 0.25, 0.50, 34, 39, 32, 1.2, "FLORET"),
                        new CropConfig.HeatProfile("结荚期", 0.50, 0.88, 35, 40, 33, 1.3, "WEIGHT"),
                        new CropConfig.HeatProfile("采收期", 0.88, 1.30, 35, 40, 33, 0.8, "FERTILITY")),
                0.026, 0.016, 0.022, 0.018,
                0.20, 0.22, 0.32, 0.28,
                List.of(
                        new CropConfig.PhenoStage("播种出苗", 0.00, 0.15),
                        new CropConfig.PhenoStage("伸蔓期", 0.15, 0.35),
                        new CropConfig.PhenoStage("开花结荚", 0.35, 0.65),
                        new CropConfig.PhenoStage("盛荚期", 0.65, 0.88),
                        new CropConfig.PhenoStage("采收期", 0.88, 1.30)),
                false, 0, 0, 12.0, 2000, "yardlong_bean", 1);
    }

    /**
     * 萝卜（秋播，根菜）。
     *
     * <p><b>与白菜同族（LEAFY）但特性相反</b>：萝卜<b>耐寒</b>（可耐 0℃ 以下短时低温，
     * 反而是低温有利于品质），而白菜怕霜。所以形态族的「无霜期需求」不同 ——
     * 这里靠 LEAFY 族的 85 天与白菜共享，但物候与热害档单独给。
     *
     * <p>根菜<b>怕水**：土壤过湿会烂根，商品率对「糠心/空心」敏感。
     * 单根重 500g 是秋萝卜（白萝卜）的中位水平。
     *
     * <p><b>🔴 2026-10-24 数值校正（产量自洽性）</b>：原「5000 株 × 500g」
     * 折算亩产 2200 kg，而 {@code yieldRef} 写 3000 kg ⇒ 比值 0.73，
     * 达标线 1500 kg 虽然够得到，但裕度太薄（产量稍有损失就跌破）。
     * 萝卜实际是<b>稀植大根</b>：亩栽 5000–6000 株、单根 500–600 g，
     * 亩产 2500–3500 kg。取中位口径校正：
     * <ul>
     *   <li>亩株数 5000 → <b>5500</b>；</li>
     *   <li>单根重 500 → <b>600</b> g（长势好的秋萝卜）。</li>
     * </ul>
     * 改后：5500 × 0.88 × 600 / 1e3 = <b>2904 kg/亩</b>，与 yieldRef 3000 比值 0.97 ✔
     */
    public static CropConfig radish() {
        return new CropConfig(
                "radish", "萝卜（秋播）",
                4, 24, 1300, 2.1, 1.20,
                5500,   // 亩株数
                1,      // 单株一根
                0.88,   // 商品率（糠心/弯曲/开裂扣这里）
                600,    // 单根重 g
                4000,   // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.20, 30, 35, 28, 0.4, "PANICLE"),
                        new CropConfig.HeatProfile("莲座期", 0.20, 0.50, 30, 35, 28, 0.6, "FLORET"),
                        new CropConfig.HeatProfile("肉质根膨大", 0.50, 0.88, 31, 36, 29, 1.1, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟期", 0.88, 1.30, 32, 37, 30, 0.6, "FERTILITY")),
                0.020, 0.010, 0.014, 0.026,
                0.25, 0.15, 0.30, 0.35,
                List.of(
                        new CropConfig.PhenoStage("播种出苗", 0.00, 0.15),
                        new CropConfig.PhenoStage("幼苗期", 0.15, 0.28),
                        new CropConfig.PhenoStage("莲座期", 0.28, 0.50),
                        new CropConfig.PhenoStage("肉质根膨大", 0.50, 0.88),
                        new CropConfig.PhenoStage("成熟采收", 0.88, 1.30)),
                false, 0, 0, 10.0, 3000, "radish", 1);
    }

    /**
     * 葡萄（休眠→春梢，藤果）。
     *
     * <p><b>🔴 为什么葡萄按「一年生栽培」处理</b>：本作引擎是「播种→成熟」一年生，
     * 没有多年生引擎（苹果/柑橘/葡萄需要定植多年、花芽分化、大小年）。
     * 这里借用<b>草莓的同款处理</b>——按一年生栽培建模：
     * 秋季定植、冬季休眠、春季萌芽开花、夏季结实。
     * 这是一个<b>明确的简化</b>，与草莓一致，在游戏里讲得通。
     *
     * <p><b>需要春化</b>（与冬小麦、郁金香同构）：冬季低温不足则春季不萌芽，
     * 所以走 {@code winterHardy + vernalizationDays}。
     *
     * <p>单串重 200g 按单穗计（一穗葡萄约 200g，商品果串 500–800g）。
     *
     * <p><b>🔴 2026-10-24 数值校正（产量自洽性）</b>：原先填「120 株/亩 × 12 穗」，
     * 折算亩产只有 245 kg —— 而 {@code yieldRef} 写的是 1200 kg，
     * 两者差 4.9 倍 ⇒ 达标线（{@code PASS_RATIO=0.5} × 1200 = 600 kg）
     * <b>永远达不到</b> ⇒ pPass 恒为 0、等级永远 L4/L5，
     * 而且失败归因会错误地指向「积温不足」，把玩家往「调播期」上带偏。
     *
     * <p><b>根因不是参照产量填错，而是亩株数与单株负载不匹配</b>：
     * 鲜食葡萄露地栽培是<b>疏植</b>（架式 60–80 株/亩），单株挂果 5–8 kg，
     * 对应亩产 <b>400–600 kg</b> —— 1200 kg 是设施/优培水平，
     * 拿来当「露地常规参照」本身就虚高。真实梨园亩产 1500–2500 kg 是可信的，
     * 但葡萄不是。所以两处一起改：
     * <ul>
     *   <li>亩株数 120 → <b>80</b>（疏植口径），单株穗数 12 → <b>45</b>
     *       ⇒ 单株负载 45 × 200g = 9 kg，回到真实「单株 5–8 kg」量级；</li>
     *   <li>{@code yieldRef} 1200 → <b>600</b>（露地常规栽培中上水平）。</li>
     * </ul>
     * 改后：80 × 45 × 0.85 × 200 / 1e3 = <b>612 kg/亩</b>，与 yieldRef 600 比值 1.02 ✔
     */
    public static CropConfig grape() {
        return new CropConfig(
                "grape", "葡萄（休眠→春梢）",
                5, 26, 1600, 2.3, 1.23,
                80,     // 亩株数（鲜食葡萄露地疏植口径：60–80 株/亩）
                45,     // 单株穗数（单株挂果 5–8 kg ÷ 单穗 200g）
                0.85,   // 商品率（落花落果/裂果/病果扣这里）
                200,    // 单穗重 g
                900,    // 产量上限 kg/亩（露地疏植园的天花板，不是设施栽培水平）
                List.of(
                        new CropConfig.HeatProfile("休眠越冬", 0.00, 0.35, 28, 34, 26, 0.2, "PANICLE"),
                        new CropConfig.HeatProfile("萌芽开花", 0.35, 0.62, 30, 35, 28, 1.4, "FLORET"),
                        new CropConfig.HeatProfile("坐果膨大", 0.60, 0.90, 33, 38, 31, 1.2, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟采收", 0.90, 1.30, 34, 39, 32, 0.6, "FERTILITY")),
                0.025, 0.020, 0.026, 0.018,
                0.20, 0.25, 0.35, 0.25,
                List.of(
                        new CropConfig.PhenoStage("定植休眠", 0.00, 0.35),
                        new CropConfig.PhenoStage("萌芽展叶", 0.35, 0.50),
                        new CropConfig.PhenoStage("开花坐果", 0.50, 0.68),
                        new CropConfig.PhenoStage("果实膨大", 0.68, 0.90),
                        new CropConfig.PhenoStage("成熟采收", 0.90, 1.30)),
                true, -15, 45, 9.0, 600, "grape", 1);
    }

    /**
     * 梨（秋播，仁果）。
     *
     * <p><b>🔴 同样按一年生简化处理</b>（同葡萄）：真实梨树要定植 3–5 年才结果，
     * 本作把它当成「秋播→春梢→夏果」的一季，
     * 走 {@code winterHardy + vernalizationDays}（梨的芽也需要冬季低温解除休眠）。
     *
     * <p><b>形态族是独立的 TREE</b>：梨是乔木，与藤本（葡萄）、灌木（茄果）
     * 在株距、光照需求上完全不同，前端贴图也不该共用。
     *
     * <p>单果重 250g 是鸭梨/雪梨的中位水平；梨的<b>疏果</b>是产量控制的关键，
     * 所以商品率高（0.85）。
     *
     * <p><b>🔴 2026-10-24 数值校正（产量自洽性）</b>：原「80 株/亩 × 25 果」折算
     * 亩产只有 425 kg，而 {@code yieldRef} 写 1400 kg ⇒ 达标线 700 kg 永远达不到，
     * pPass 恒为 0（与葡萄同病）。
     *
     * <p><b>梨树是丰产型果树</b>：盛果期乔木梨园亩产 1500–2500 kg 是真实水平
     * （不像葡萄那样受疏植密度限制），单株负载 30–50 kg。
     * 所以改的是<b>单株果数</b>而不是参照产量：
     * <ul>
     *   <li>亩株数 80 → <b>50</b>（盛果期乔木梨园的常见密度）；</li>
     *   <li>单株果数 25 → <b>130</b>（单株 130 × 250g = 32.5 kg，回到真实量级）。</li>
     * </ul>
     * 改后：50 × 130 × 0.85 × 250 / 1e3 = <b>1381 kg/亩</b>，与 yieldRef 1400 比值 0.99 ✔
     */
    public static CropConfig pear() {
        return new CropConfig(
                "pear", "梨（秋播）",
                5, 26, 1700, 2.3, 1.23,
                50,     // 亩株数（盛果期乔木梨园密度）
                130,    // 单株果数（单株负载 130 × 250g ≈ 32.5 kg）
                0.85,   // 商品率
                250,    // 单果重 g
                2000,   // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("休眠越冬", 0.00, 0.38, 28, 34, 26, 0.2, "PANICLE"),
                        new CropConfig.HeatProfile("萌芽开花", 0.38, 0.60, 30, 35, 28, 1.5, "FLORET"),
                        new CropConfig.HeatProfile("果实膨大", 0.60, 0.90, 33, 38, 31, 1.1, "WEIGHT"),
                        new CropConfig.HeatProfile("成熟采收", 0.90, 1.30, 34, 39, 32, 0.6, "FERTILITY")),
                0.024, 0.020, 0.026, 0.018,
                0.20, 0.25, 0.35, 0.25,
                List.of(
                        new CropConfig.PhenoStage("定植休眠", 0.00, 0.38),
                        new CropConfig.PhenoStage("萌芽展叶", 0.38, 0.52),
                        new CropConfig.PhenoStage("开花坐果", 0.52, 0.70),
                        new CropConfig.PhenoStage("果实膨大", 0.70, 0.90),
                        new CropConfig.PhenoStage("成熟采收", 0.90, 1.30)),
                true, -18, 50, 10.0, 1400, "pear", 1);
    }

    /**
     * 月季（切花）。
     *
     * <p><b>与菊花的差别</b>：月季是<b>长日照</b>植物（与菊花短日相反），
     * 花期由日照长度而非低温触发；且它是<b>灌木</b>形态而非草本。
     * 切花商品率对<b>花枝长度与畸形花</b>最敏感，折花时「带刺带叶」直接降品质。
     *
     * <p>单花重 30g 是一支切花的商品标准（与菊花 40g 同量级）。
     */
    public static CropConfig rose() {
        return new CropConfig(
                "rose", "月季（切花）",
                10, 27, 1800, 2.4, 1.23,
                12000,  // 亩株数（切花密植）
                6,      // 单株花枝数
                0.90,   // 商品率（畸形花/短枝/带病扣这里）
                30,     // 单支重 g
                1500,   // 产量上限 kg/亩
                List.of(
                        new CropConfig.HeatProfile("苗期", 0.00, 0.25, 31, 36, 30, 0.4, "PANICLE"),
                        new CropConfig.HeatProfile("摘心现蕾", 0.25, 0.55, 32, 37, 31, 0.9, "FLORET"),
                        new CropConfig.HeatProfile("开花期", 0.55, 0.85, 31, 36, 30, 1.5, "FLORET"),
                        new CropConfig.HeatProfile("切花采收", 0.85, 1.30, 31, 36, 30, 0.9, "FERTILITY")),
                0.028, 0.022, 0.026, 0.020,
                0.20, 0.25, 0.35, 0.25,
                List.of(
                        new CropConfig.PhenoStage("定植缓苗", 0.00, 0.20),
                        new CropConfig.PhenoStage("摘心整形", 0.20, 0.45),
                        new CropConfig.PhenoStage("现蕾期", 0.45, 0.65),
                        new CropConfig.PhenoStage("开花期", 0.65, 0.88),
                        new CropConfig.PhenoStage("切花采收", 0.88, 1.30)),
                false, 0, 0, 13.0, 1000, "rose", 1);
    }
}
