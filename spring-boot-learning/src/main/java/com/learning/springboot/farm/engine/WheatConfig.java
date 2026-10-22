package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 冬小麦（半冬性）。数值口径见 {@code crop-data-cards.md} 卡 2 与 S7。
 *
 * <p><b>与水稻最关键的三处不同</b>：
 * <ol>
 *   <li>基点温度 0℃（不是 10℃）：冬季 3℃ 也在缓慢积累，所以积温需求按「0℃ 以上」计；</li>
 *   <li><b>能越冬</b>：冬季不死，来年返青继续长，全季跨越两个日历年（10 月播 → 次年 5–6 月收）；</li>
 *   <li><b>必须春化</b>：0–7℃ 累计天数不够就不抽穗——这是"华南种冬小麦只长草"的机制来源。</li>
 * </ol>
 */
public final class WheatConfig {

    private WheatConfig() {
    }

    /** 江南 / 黄淮海 半冬性冬小麦。 */
    public static CropConfig winterWheat() {
        return new CropConfig(
                "wheat_winter", "冬小麦（半冬性）",
                0,       // aat_ref  ℃（0℃ 以上开始计）
                30,      // t_opt_upper ℃
                2280,    // aat_req（0℃ 以上，10 月播 → 次年 5 月底成熟）
                2.4,     // k_force
                1.25,    // f_dev_T 上限
                400000,  // paniclesPot 穗/亩（40 万穗是丰产田量级）
                33,      // floretsPot 穗粒数
                0.85,    // fertility_base
                42.0,    // weight_pot 千粒重 g
                700,     // yield_cap
                List.of(
                        new CropConfig.HeatProfile("抽穗期", 0.63, 0.72, 32, 38, 30, 1.2, "FERTILITY"),
                        new CropConfig.HeatProfile("灌浆期", 0.72, 0.92, 30, 36, 28, 1.0, "WEIGHT")),
                0.02, 0.010, 0.020, 0.012,
                0.20, 0.15, 0.50, 0.40,
                List.of(
                        new CropConfig.PhenoStage("出苗期", 0.00, 0.12),
                        new CropConfig.PhenoStage("分蘖期", 0.12, 0.30),
                        new CropConfig.PhenoStage("越冬期", 0.30, 0.42),
                        new CropConfig.PhenoStage("返青拔节", 0.42, 0.63),
                        new CropConfig.PhenoStage("抽穗扬花", 0.63, 0.72),
                        new CropConfig.PhenoStage("灌浆期", 0.72, 0.92),
                        new CropConfig.PhenoStage("成熟期", 0.92, 1.30)),
                true, -15, 45, 9.0, 400, "wheat", 1000);
    }
}
