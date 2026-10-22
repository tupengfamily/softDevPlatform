package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 园艺作物配置：蔬菜 / 水果 / 花卉。
 *
 * <p><b>为什么单独一个类</b>：{@link CropConfig} 是纯参数 record，新增作物不需要新类型；
 * 但 10 种作物的参数（积温、产量构成、热害档、物候名）差异很大，散在 {@link CropCatalog}
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
                false, 0, 0, 15.0, 5000, "leafy", 1);
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
                false, 0, 0, 18.0, 4500, "bush", 1);
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
                false, 0, 0, 16.0, 5000, "vine", 1);
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
                false, 0, 0, 15.0, 2500, "bush", 1);
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
                true, -12, 40, 12.0, 1500, "bush", 1);
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
                false, 0, 0, 12.0, 3500, "vine", 1);
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
                false, 0, 0, 11.0, 2500, "vine", 1);
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
                false, 0, 0, 14.0, 800, "flower", 1);
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
                false, 0, 0, 10.0, 250, "flower", 1);
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
                true, -18, 60, 10.0, 500, "flower", 1);
    }
}
