package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 春 / 夏玉米。数值口径见 {@code crop-data-cards.md} 卡 3。
 *
 * <p>三个与水稻不同的点：① 单株大穗，穗数只有几千/亩（不是十几万）；② 需氮最高（13 kg/亩）；
 * ③ 大喇叭口期是需肥临界期，错过追肥直接掉穗粒数。
 */
public final class CornConfig {

    private CornConfig() {
    }

    public static CropConfig springCorn() {
        return new CropConfig(
                "corn_spring", "玉米（春/夏播）",
                10,      // aat_ref
                30,      // t_opt_upper
                2600,    // aat_req（10℃ 以上）
                3.0,
                1.30,
                3800,    // paniclesPot 穗/亩
                480,     // floretsPot 穗粒数
                0.90,    // fertility_base
                300.0,   // weight_pot 千粒重 g
                900,     // yield_cap
                List.of(
                        new CropConfig.HeatProfile("抽雄期", 0.50, 0.68, 35, 40, 33, 1.4, "FERTILITY"),
                        new CropConfig.HeatProfile("灌浆期", 0.68, 0.95, 35, 40, 33, 1.1, "WEIGHT")),
                0.010, 0.008, 0.022, 0.010,
                0.20, 0.15, 0.55, 0.40,
                List.of(
                        new CropConfig.PhenoStage("出苗期", 0.00, 0.15),
                        new CropConfig.PhenoStage("拔节期", 0.15, 0.40),
                        new CropConfig.PhenoStage("大喇叭口期", 0.40, 0.55),
                        new CropConfig.PhenoStage("抽雄吐丝", 0.55, 0.68),
                        new CropConfig.PhenoStage("灌浆期", 0.68, 0.95),
                        new CropConfig.PhenoStage("成熟期", 0.95, 1.30)),
                false, 0, 0, 13.0, 500, "corn", 1000);
    }
}
