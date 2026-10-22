package com.learning.springboot.farm.engine;

import java.util.List;

/**
 * 作物热时间与胁迫配置（中稻）。
 *
 * <p>所有数值来自 {@code design/gdd/S7-crop-development-simulation.md} 与
 * {@code design/gdd/crop-data-cards.md} 卡 1，并经 {@code prototype/} 用南昌 2015–2023
 * 真实气象标定。**改任何一个数都必须重跑回归**（{@code SimRegressionTest}）。
 *
 * <p>🔴 两个口径不得混用：
 * <ul>
 *   <li>{@code aat_ref} / {@code tOptUpper} 是**日均温**口径，用于 AAT 与发育；</li>
 *   <li>{@link HeatProfile#tStart()} / {@code tSevere} 是**百叶箱日最高温 T_max** 口径，用于产量损伤。</li>
 * </ul>
 * 混用的后果：玉米阈值被从 38℃ 压到 28℃，夏天天天"高温胁迫"。
 */
public record CropConfig(
        String id,
        String name,
        double aatRef,
        double tOptUpper,
        double aatReq,
        double kForce,
        double fDevTCap,
        double paniclesPot,
        double floretsPot,
        double fertilityBase,
        double weightPot,
        double yieldCap,
        List<HeatProfile> heatProfiles,
        double kHPan,
        double kHFloret,
        double kHFert,
        double kHWt,
        double capPan,
        double capFloret,
        double capFert,
        double capWt,
        List<PhenoStage> phenology,
        /** 能否越冬：true = 冬季不死、来年继续长（冬小麦）；false = 遇 0℃ 即终止（水稻/玉米/大豆） */
        boolean winterHardy,
        /** 越冬致死温度（℃）。winterHardy=false 时忽略 */
        double winterKillTmin,
        /** 春化需求（0–7℃ 天数）。>0 表示必须完成春化才能抽穗（冬性品种） */
        int vernalizationDays,
        /** 全季需氮（折纯 kg/亩），用于 S6 氮素账本 */
        double nNeed,
        /** 参照产量 kg/亩（S23 的 Y_ref，也是达标线的分母） */
        double yieldRef,
        /** 3D 形态族：rice / wheat / corn / soybean / leafy / vine / bush / flower */
        String visual,
        /**
         * 重量基准：{@code weightPot} 是「每多少个单果的重量」。
         *
         * <p>禾谷类用<b>千粒重</b>（1000），园艺作物用<b>单果重</b>（1）。
         * 这个字段存在的原因是产量公式 {@code panicles × grains × weight / 1e6} 里
         * 隐含了「千粒」这个 1000 的除数 —— 园艺作物直接填单果重会差 1000 倍
         * （白菜 5000kg/亩 会算成 5kg/亩，评级一律 L5）。
         */
        int weightPer) {

    /**
     * 分期高温胁迫配置。
     *
     * @param target  损伤对象：PANICLE 穗数 / FLORET 颖花数（孕穗）/ FERTILITY 结实率 / WEIGHT 千粒重
     * @param tStart  **百叶箱 T_max** 伤害起点（35℃）。注意 33℃ 只用于展示层，不进产量积分
     * @param tDisplay 展示层起点（33℃），只驱动热害徽标，**严禁进产量积分**
     */
    public record HeatProfile(
            String name, double lo, double hi,
            double tStart, double tSevere, double tDisplay,
            double sens, String target) {
    }

    /** 物候期边界（DVS）。lo/hi 为 DVS 区间，最后一个 hi 可超 1。 */
    public record PhenoStage(String name, double lo, double hi) {
    }
}
