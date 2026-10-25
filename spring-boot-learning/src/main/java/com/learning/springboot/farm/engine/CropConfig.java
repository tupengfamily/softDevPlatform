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
        /**
         * 3D 形态标识（贴图键）：<b>每种作物自己的标识</b>。
         *
         * <p><b>🔴 语义在 2026-10-24 变过</b>：
         * <ul>
         *   <li><b>旧</b>：通用形态族 —— {@code rice / wheat / corn / soybean / leafy / vine / bush / flower}。
         *       10 种园艺作物挤在 4 个族里，番茄/辣椒都叫 {@code bush}、黄瓜/西瓜都叫 {@code vine}，
         *       前端只能按族加载贴图，于是「草莓长得像番茄」。</li>
         *   <li><b>新</b>：<b>作物 id</b>（园艺作物如 {@code cabbage} / {@code grape} / {@code rose}），
         *       前端可以按作物 id 加载专属贴图。</li>
         * </ul>
         *
         * <p><b>粮食作物例外</b>：水稻/冬小麦/玉米/大豆的 visual 保持
         * {@code rice / wheat / corn / soybean} 不变 —— 前端已有这 4 套贴图，
         * 且存量数据库 {@code t_farm_season.visual} 存的就是这些值，
         * 改动会让历史数据失效。
         *
         * <p><b>存量兼容</b>：历史季节记录里的 visual 仍是旧值（{@code leafy/vine/bush/flower}），
         * <b>不做数据库迁移</b>（风险大且没必要，前端会做兜底映射）。
         * 因此凡是「按形态族取参数」的逻辑（无霜期、适宜 pH、病虫害清单），
         * <b>不能直接 switch 这个字段</b>，必须先经 {@link CropVisual#formOf} 解析。
         */
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
