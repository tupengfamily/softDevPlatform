package com.learning.springboot.farm.engine;

import java.util.List;

/** 中稻 / 一季稻（籼）配置。数值口径见 {@link CropConfig} 的类注释。 */
public final class RiceConfig {

    private RiceConfig() {
    }

    /** 南昌 2015–2023 标定值。改它 = 改全部历史回放结果，必须跑回归。 */
    public static CropConfig indicaMidSeason() {
        return new CropConfig(
                "rice_mid", "水稻·中稻/一季稻（籼）",
                10,      // aat_ref  ℃（日均口径）
                30,      // t_opt_upper ℃（日均口径，AAT 在此截断）
                3850,    // aat_req   ℃·d（播种→成熟；= 卡值 3100 本田 + 秧田 618 + 热加速补偿）
                3.0,     // k_force（累积型 f_dev_T）
                1.30,    // f_dev_T 上限
                160000,  // panicles_pot 穗/亩
                176,     // florets_pot 总粒/穗（= 140.8 实粒 ÷ 0.8 结实率）
                0.80,    // fertility_base
                28.0,    // weight_pot 千粒重 g
                800,     // yield_cap  kg/亩
                List.of(
                        // 分蘖期：耐热，只伤穗数
                        new CropConfig.HeatProfile("分蘖期", 0.16, 0.36, 35, 40, 33, 0.4, "PANICLE"),
                        // 孕穗期：伤颖花数（不是结实率！S7 §4.6.3a）
                        new CropConfig.HeatProfile("孕穗期", 0.55, 0.63, 35, 40, 33, 1.3, "FLORET"),
                        // 抽穗开花期：全期最敏感
                        new CropConfig.HeatProfile("抽穗开花期", 0.63, 0.72, 35, 40, 33, 1.5, "FERTILITY"),
                        // 灌浆期：伤千粒重
                        new CropConfig.HeatProfile("灌浆期", 0.72, 0.92, 35, 40, 33, 1.2, "WEIGHT")),
                0.065,   // k_h_pan
                0.022,   // k_h_floret
                0.025,   // k_h_fert
                0.014,   // k_h_wt
                0.30,    // cap_pan
                0.25,    // cap_floret
                0.60,    // cap_fert
                0.45,    // cap_wt
                List.of(
                        new CropConfig.PhenoStage("秧苗期", 0.00, 0.16),
                        new CropConfig.PhenoStage("返青分蘖始", 0.16, 0.36),
                        new CropConfig.PhenoStage("够苗晒田期", 0.36, 0.41),
                        new CropConfig.PhenoStage("拔节孕穗期", 0.41, 0.63),
                        new CropConfig.PhenoStage("抽穗开花期", 0.63, 0.72),
                        new CropConfig.PhenoStage("灌浆期", 0.72, 0.92),
                        new CropConfig.PhenoStage("黄熟期", 0.92, 1.30)),
                false, 0, 0, 11.0, 600, "rice", 1000);
    }
}
