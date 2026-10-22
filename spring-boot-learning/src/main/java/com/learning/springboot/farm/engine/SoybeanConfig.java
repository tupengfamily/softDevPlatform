package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 大豆（夏播）。数值口径见 {@code crop-data-cards.md} 卡 4。
 *
 * <p><b>本作最反直觉的一条</b>：大豆 60–70% 的氮靠根瘤固氮，外源氮超过 2–3 kg/亩会抑制根瘤
 * （"氮阻遏"，真实生理现象）。所以 nNeed 只有 2.5，多施尿素不仅白花钱，还会让后期缺氮更严重。
 */
public final class SoybeanConfig {

    private SoybeanConfig() {
    }

    public static CropConfig summerSoybean() {
        return new CropConfig(
                "soybean", "大豆（夏播）",
                10,
                30,
                2300,    // aat_req（10℃ 以上）
                2.8,
                1.25,
                16000,   // paniclesPot 株/亩
                70,      // floretsPot 株粒数
                0.75,    // fertility_base
                200.0,   // weight_pot 千粒重 g
                350,     // yield_cap
                List.of(
                        new CropConfig.HeatProfile("开花期", 0.45, 0.62, 35, 40, 33, 1.2, "FERTILITY"),
                        new CropConfig.HeatProfile("结荚鼓粒", 0.62, 0.88, 35, 40, 33, 1.0, "WEIGHT")),
                0.012, 0.006, 0.024, 0.011,
                0.20, 0.15, 0.50, 0.40,
                List.of(
                        new CropConfig.PhenoStage("出苗期", 0.00, 0.15),
                        new CropConfig.PhenoStage("分枝期", 0.15, 0.40),
                        new CropConfig.PhenoStage("开花期", 0.40, 0.62),
                        new CropConfig.PhenoStage("结荚鼓粒", 0.62, 0.90),
                        new CropConfig.PhenoStage("成熟期", 0.90, 1.30)),
                false, 0, 0, 2.5, 180, "soybean", 1000);
    }
}
