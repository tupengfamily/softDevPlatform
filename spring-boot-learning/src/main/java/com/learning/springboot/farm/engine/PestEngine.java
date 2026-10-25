package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 病虫害发生与危害模型（S9 的精简实现）。
 *
 * <p>三条真实农学原理（与 GDD 一致，别改味道）：
 * <ol>
 *   <li><b>病害三角</b>：寄主感病期 × 环境适宜 × 菌源基数，三者相乘才是发生概率；</li>
 *   <li><b>潜伏期</b>：侵入到显症有 3–6 天时差 —— 这就是"预防优于治疗"的机制来源；</li>
 *   <li><b>逻辑增长</b>：显症后 severity 按 Logistic 逼近 1，早期窗口便宜、晚期打药事倍功半。</li>
 * </ol>
 *
 * <p>本类只做纯计算：输入逐日天气 + 逐日 DVS 轨迹 + 打药日期，输出病虫害实例与产量损失，
 * 不读写数据库，也不决定 UI 文案。
 */
public final class PestEngine {

    private PestEngine() {
    }

    /** 病虫害配置（参数为真实参考值，待平衡）。 */
    public record PestConfig(
            String id,
            String name,
            String type,          // DISEASE / INSECT
            String pathogen,      // FUNGUS / BACTERIA / INSECT
            double tLo,
            double tHi,
            double humidityMin,
            boolean leafWetness,
            double dvsLo,
            double dvsHi,
            int latencyDays,
            double spreadRate,
            String target,        // LEAF / STEM / FLOWER / GRAIN / WHOLE
            double yieldLossMax,
            double qualityImpact,
            double inoculum
    ) {
    }

    /** 一个已发生的病虫害实例。 */
    public record Instance(
            String id,
            String name,
            String type,
            String pathogen,
            double severity,
            double incidence,
            boolean detected,
            String onsetDate,
            int latencyLeft,
            String target
    ) {
    }

    /** 模拟结果：实例列表 + 分通道产量损失（0..1）。 */
    public record Result(
            List<Instance> instances,
            double leafLoss,
            double stemLoss,
            double flowerLoss,
            double grainLoss,
            double qualityImpact
    ) {
        public static final Result EMPTY = new Result(List.of(), 0, 0, 0, 0, 0);

        /** 综合减产率（给 UI 一个总数字，归因表拆开看）。 */
        public double totalLoss() {
            return Math.min(0.60, leafLoss * 0.6 + stemLoss + flowerLoss + grainLoss * 0.7);
        }
    }

    /** 江西中稻的 6 种主要病虫害（真实清单，见 S9 §3.2）。 */
    public static final List<PestConfig> RICE = List.of(
            new PestConfig("blast", "稻瘟病", "DISEASE", "FUNGUS",
                    24, 28, 90, true, 0.16, 0.72, 4, 0.085,
                    "LEAF", 0.35, 0.25, 0.060),
            new PestConfig("sheath_blight", "纹枯病", "DISEASE", "FUNGUS",
                    28, 32, 90, true, 0.36, 0.92, 5, 0.075,
                    "STEM", 0.30, 0.10, 0.070),
            new PestConfig("bacterial_blight", "白叶枯病", "DISEASE", "BACTERIA",
                    25, 30, 85, true, 0.41, 0.92, 3, 0.095,
                    "LEAF", 0.40, 0.15, 0.030),
            new PestConfig("stem_borer", "二化螟", "INSECT", "INSECT",
                    22, 30, 60, false, 0.16, 0.63, 6, 0.045,
                    "STEM", 0.25, 0.05, 0.055),
            new PestConfig("planthopper", "稻飞虱", "INSECT", "INSECT",
                    25, 30, 70, false, 0.36, 1.00, 3, 0.080,
                    "WHOLE", 0.35, 0.05, 0.065),
            new PestConfig("leaf_roller", "稻纵卷叶螟", "INSECT", "INSECT",
                    22, 28, 75, false, 0.55, 0.92, 3, 0.070,
                    "LEAF", 0.20, 0.03, 0.050)
    );

    /** 冬小麦的主要病虫害（S9 §3.2）。 */
    public static final List<PestConfig> WHEAT = List.of(
            new PestConfig("wheat_scab", "小麦赤霉病", "DISEASE", "FUNGUS",
                    15, 28, 80, true, 0.58, 0.78, 4, 0.090,
                    "GRAIN", 0.45, 0.40, 0.065),
            new PestConfig("stripe_rust", "条锈病", "DISEASE", "FUNGUS",
                    9, 16, 85, true, 0.30, 0.85, 6, 0.080,
                    "LEAF", 0.35, 0.15, 0.050),
            new PestConfig("powdery_mildew", "白粉病", "DISEASE", "FUNGUS",
                    15, 22, 70, false, 0.20, 0.80, 5, 0.060,
                    "LEAF", 0.25, 0.10, 0.045),
            new PestConfig("wheat_aphid", "麦蚜", "INSECT", "INSECT",
                    15, 25, 50, false, 0.40, 0.92, 3, 0.070,
                    "WHOLE", 0.25, 0.10, 0.060)
    );

    /** 玉米的主要病虫害。 */
    public static final List<PestConfig> CORN = List.of(
            new PestConfig("northern_leaf_blight", "玉米大斑病", "DISEASE", "FUNGUS",
                    20, 27, 88, true, 0.40, 0.92, 5, 0.075,
                    "LEAF", 0.35, 0.15, 0.060),
            new PestConfig("corn_borer", "玉米螟", "INSECT", "INSECT",
                    22, 30, 60, false, 0.35, 0.78, 5, 0.050,
                    "STEM", 0.25, 0.05, 0.055),
            new PestConfig("fall_armyworm", "草地贪夜蛾", "INSECT", "INSECT",
                    20, 30, 70, false, 0.15, 0.65, 3, 0.080,
                    "LEAF", 0.40, 0.05, 0.045)
    );

    /** 大豆的主要病虫害。 */
    public static final List<PestConfig> SOYBEAN = List.of(
            new PestConfig("root_rot", "根腐病", "DISEASE", "FUNGUS",
                    20, 28, 85, true, 0.00, 0.25, 5, 0.070,
                    "STEM", 0.30, 0.10, 0.055),
            new PestConfig("soybean_aphid", "大豆蚜", "INSECT", "INSECT",
                    18, 25, 60, false, 0.30, 0.85, 3, 0.065,
                    "WHOLE", 0.25, 0.05, 0.060),
            new PestConfig("cyst_nematode", "胞囊线虫", "INSECT", "INSECT",
                    20, 28, 60, false, 0.00, 0.40, 7, 0.040,
                    "STEM", 0.25, 0.05, 0.040)
    );


    // ==================================================================
    //  园艺作物（蔬菜 / 水果 / 花卉）
    // ==================================================================

    /**
     * 叶菜（大白菜）。霜霉病在叶面结露时爆发最快；软腐病是细菌性、随伤口侵入，
     * 一旦发生基本只能整株拔除 —— 所以它的 target 是 WHOLE、品质扣分最高。
     */
    public static final List<PestConfig> LEAFY = List.of(
            new PestConfig("leafy_downy", "霜霉病", "DISEASE", "FUNGUS",
                    8, 20, 90, true, 0.15, 0.85, 4, 0.100,
                    "LEAF", 0.35, 0.25, 0.060),
            new PestConfig("soft_rot", "软腐病", "DISEASE", "BACTERIA",
                    20, 30, 90, true, 0.45, 1.00, 5, 0.090,
                    "WHOLE", 0.40, 0.35, 0.045),
            new PestConfig("cabbage_worm", "菜青虫", "INSECT", "INSECT",
                    15, 28, 60, false, 0.10, 0.80, 3, 0.070,
                    "LEAF", 0.30, 0.10, 0.055)
    );

    /** 瓜类（黄瓜 / 西瓜 / 甜瓜）。霜霉与白粉常常同期发生，打药要覆盖两类靶标。 */
    public static final List<PestConfig> VINE = List.of(
            new PestConfig("cuc_downy", "霜霉病", "DISEASE", "FUNGUS",
                    15, 25, 88, true, 0.20, 0.90, 3, 0.110,
                    "LEAF", 0.40, 0.20, 0.070),
            new PestConfig("cuc_powdery", "白粉病", "DISEASE", "FUNGUS",
                    20, 28, 65, false, 0.25, 0.90, 4, 0.075,
                    "LEAF", 0.30, 0.15, 0.050),
            new PestConfig("melon_aphid", "瓜蚜", "INSECT", "INSECT",
                    18, 30, 55, false, 0.15, 1.00, 3, 0.085,
                    "WHOLE", 0.30, 0.10, 0.060)
    );

    /**
     * 茄果类（番茄 / 辣椒 / 草莓）。灰霉病靶标是花与果，直接打商品率 ——
     * 这也是为什么"花期打药"在茄果类上是关键决策，而不是例行公事。
     */
    public static final List<PestConfig> BUSH = List.of(
            new PestConfig("early_blight", "早疫病", "DISEASE", "FUNGUS",
                    20, 28, 85, true, 0.20, 0.90, 5, 0.085,
                    "LEAF", 0.35, 0.20, 0.055),
            new PestConfig("gray_mold", "灰霉病", "DISEASE", "FUNGUS",
                    15, 23, 90, true, 0.40, 1.00, 4, 0.095,
                    "FLOWER", 0.35, 0.45, 0.060),
            new PestConfig("spider_mite", "红蜘蛛", "INSECT", "INSECT",
                    25, 33, 40, false, 0.20, 1.00, 4, 0.075,
                    "LEAF", 0.30, 0.20, 0.045)
    );

    /** 花卉（菊花 / 向日葵 / 郁金香）。病害直接毁商品性，品质扣分高于减产扣分。 */
    public static final List<PestConfig> FLOWER = List.of(
            new PestConfig("flower_powdery", "白粉病", "DISEASE", "FUNGUS",
                    18, 26, 70, false, 0.25, 0.95, 4, 0.070,
                    "LEAF", 0.30, 0.35, 0.050),
            new PestConfig("flower_aphid", "蚜虫", "INSECT", "INSECT",
                    16, 26, 55, false, 0.20, 1.00, 3, 0.080,
                    "FLOWER", 0.25, 0.40, 0.055),
            new PestConfig("flower_gray", "灰霉病", "DISEASE", "FUNGUS",
                    15, 22, 92, true, 0.50, 1.00, 4, 0.090,
                    "FLOWER", 0.30, 0.50, 0.058)
    );

    /**
     * 禾谷·杂粮（谷子）。谷子最典型的病是<b>谷瘟病</b>与<b>白粉病</b>，
     * 另有一种独特的「谷子白发病」（菌核菌侵染，表现为主穗不结实）。
     */
    public static final List<PestConfig> MILLET = List.of(
            new PestConfig("millet_blast", "谷子瘟病", "DISEASE", "FUNGUS",
                    22, 30, 88, true, 0.30, 0.80, 4, 0.080,
                    "LEAF", 0.35, 0.20, 0.055),
            new PestConfig("millet_powdery", "谷子白粉病", "DISEASE", "FUNGUS",
                    18, 26, 65, false, 0.25, 0.85, 5, 0.060,
                    "LEAF", 0.25, 0.10, 0.045),
            new PestConfig("millet_moth", "谷子螟", "INSECT", "INSECT",
                    20, 30, 60, false, 0.45, 0.90, 3, 0.070,
                    "GRAIN", 0.30, 0.10, 0.055)
    );

    /**
     * 块茎（马铃薯）。<b>晚疫病</b>是马铃薯的存亡线——它可在 10 天内毁掉整块地，
     * 而「马铃薯晚疫病」在中国是法定报告的检疫性病害。
     * 环腐病是细菌性的，随带病种薯传播，无法靠打药治。
     */
    public static final List<PestConfig> TUBER = List.of(
            new PestConfig("potato_late_blight", "马铃薯晚疫病", "DISEASE", "FUNGUS",
                    16, 26, 92, true, 0.25, 0.90, 3, 0.120,
                    "LEAF", 0.45, 0.25, 0.075),
            new PestConfig("potato_early_blight", "马铃薯早疫病", "DISEASE", "FUNGUS",
                    20, 28, 85, true, 0.30, 0.85, 4, 0.075,
                    "LEAF", 0.30, 0.15, 0.050),
            new PestConfig("potato_ringrot", "马铃薯环腐病", "DISEASE", "BACTERIA",
                    15, 25, 80, false, 0.10, 0.70, 6, 0.060,
                    "WHOLE", 0.35, 0.30, 0.045),
            new PestConfig("potato_aphid", "马铃薯蚜虫", "INSECT", "INSECT",
                    18, 26, 55, false, 0.20, 0.90, 3, 0.070,
                    "WHOLE", 0.25, 0.10, 0.055)
    );

    /**
     * 果树（梨）。<b>黑星病</b>是梨最重要的真菌病害，直接在果面形成黑斑、
     * 造成落果，是商品性的头号杀手；梨树还常见<b>梨锈病</b>与<b>介壳虫</b>。
     *
     * <p><b>为什么梨黑星病打的是 FLOWER 通道</b>：引擎的损伤通道只有
     * LEAF / STEM / FLOWER / GRAIN / WHOLE 五条，其中 FLOWER 这一条在
     * {@code FarmPlayService.yields()} 里乘在「单株果数」上（花果期的损失
     * 直接扣果数）。梨黑星病的后果正是「果面病斑 + 落果」，
     * 走 FLOWER 通道比走 WHOLE（均摊到穗/粒/叶）更贴近真实机制。
     */
    public static final List<PestConfig> TREE = List.of(
            new PestConfig("pear_scab", "梨黑星病", "DISEASE", "FUNGUS",
                    15, 25, 88, true, 0.30, 0.90, 5, 0.100,
                    "FLOWER", 0.40, 0.35, 0.065),
            new PestConfig("pear_rust", "梨锈病", "DISEASE", "FUNGUS",
                    12, 22, 85, true, 0.35, 0.75, 5, 0.070,
                    "LEAF", 0.30, 0.15, 0.050),
            new PestConfig("pear_scale", "梨介壳虫", "INSECT", "INSECT",
                    20, 30, 60, false, 0.25, 0.80, 4, 0.060,
                    "STEM", 0.30, 0.15, 0.050)
    );

    /**
     * 按作物取病虫害清单。
     *
     * <p><b>🔴 2026-10-24 改造</b>：本方法<b>不能</b>直接 {@code switch (visual)}。
     * visual 的语义已从「通用形态族」变成「作物 id」，直接 switch 会让
     * 大白菜、番茄、黄瓜……全部落到 default 分支、拿到<b>水稻的病虫害清单</b>——
     * 病虫压力凭空归零，等于给所有园艺作物开了无敌。
     *
     * <p>现在统一走 {@link CropVisual#formOf}：先把 visual 解析成形态族
     * （存量旧值 {@code leafy/vine/bush/flower} 与新作物 id 都能解析），
     * 再取对应清单。粮食作物有专属清单，按 visual 特判保留。
     */
    public static List<PestConfig> forCrop(String visual) {
        if (visual == null) {
            return RICE;
        }
        // 粮食作物有专属清单（不是通用禾谷清单），按 visual 特判。
        switch (visual) {
            case "wheat", "wheat_winter" -> {
                return WHEAT;
            }
            case "corn", "corn_spring" -> {
                return CORN;
            }
            case "soybean" -> {
                return SOYBEAN;
            }
            case "millet" -> {
                return MILLET;
            }
            default -> {
                // 其余按形态族取。
            }
        }
        return switch (CropVisual.formOf(visual)) {
            case GRAIN -> RICE;
            case LEAFY -> LEAFY;
            case VINE -> VINE;
            case BUSH -> BUSH;
            case FLOWER -> FLOWER;
            case TUBER -> TUBER;
            case TREE -> TREE;
        };
    }

    /** 打药后的持效期（天） */
    private static final int PROTECT_DAYS = 10;

    /**
     * 逐日模拟。
     *
     * @param days      天气序列（与生长回放同一份）
     * @param startIdx  播种日在 days 中的下标
     * @param nDays     已生长的天数（= dvsTrace.size()）
     * @param dvsTrace  逐日 DVS（由 SimEngine 输出，保证与生长同一口径）
     * @param seed      赛季种子（userId + 播种日），保证同一块地同一天结果可复现
     * @param sprays    打药日期（PESTICIDE 操作）
     */
    /**
     * 一次施药（S12）：产品、靶标、综合效力（对症 × 剂量 × 时机 × 抗性）、持效期。
     *
     * <p>把"打药"从布尔量变成有靶标的量，是 S12 的核心：打错药 = 白花钱，
     * 打对药但剂量不足/超窗 = 打折，打对药且踩准窗口 = 全额防效。
     */
    public record Spray(LocalDate date, String itemId, Set<String> targets,
                        double power, int protectDays) {
    }

    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  long seed,
                                  Set<LocalDate> sprays) {
        // 兼容旧调用：旧数据没有药剂信息，按"全靶标、满效力"处理
        List<Spray> list = new ArrayList<>();
        if (sprays != null) {
            for (LocalDate d : sprays) {
                list.add(new Spray(d, null, Set.of(), 1.0, PROTECT_DAYS));
            }
        }
        return simulate(days, startIdx, nDays, dvsTrace, seed, list, true);
    }

    /**
     * @param legacyAllTargets 旧数据的施药没有靶标清单，按"对所有病虫有效"处理
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  long seed,
                                  List<Spray> sprays,
                                  boolean legacyAllTargets) {
        return simulate(days, startIdx, nDays, dvsTrace, seed, sprays, legacyAllTargets, "rice");
    }

    /**
     * @param cropVisual 作物形态 id，决定用哪张病虫害清单
     */
    public static Result simulate(List<DayWeather> days,
                                  int startIdx,
                                  int nDays,
                                  List<Double> dvsTrace,
                                  long seed,
                                  List<Spray> sprays,
                                  boolean legacyAllTargets,
                                  String cropVisual) {
        if (days == null || nDays <= 0 || dvsTrace == null) {
            return Result.EMPTY;
        }
        java.util.Map<LocalDate, List<Spray>> sprayByDate = new java.util.HashMap<>();
        if (sprays != null) {
            for (Spray sp : sprays) {
                sprayByDate.computeIfAbsent(sp.date(), k -> new ArrayList<>()).add(sp);
            }
        }
        List<PestConfig> configs = forCrop(cropVisual);
        List<MutablePest> active = new ArrayList<>(configs.size());
        java.util.Map<String, MutablePest> byId = new java.util.HashMap<>();

        // 🔴 S12 修复：保护期必须独立于"是否已发生"存在。
        // 原来的实现把 protectUntil 挂在 MutablePest（已发生的病虫实例）上，
        // 于是"显症前打的预防药"找不到任何实例可挂 → protectedNow() 恒 false →
        // 菌源不打折 → 病害照常发生。结论：预防性打药的实际收益为 0，
        // 而 UI 文案恰恰写着"显症前预防效果最好"，机制与引导完全相反。
        //
        // 修法：维护一张「靶标 → 保护截止日」表，由施药记录直接写入，
        // 与实例是否已发生无关。保护期内该靶标的初发概率按 0.25 折算。
        java.util.Map<String, LocalDate> protectUntil = new java.util.HashMap<>();

        double leaf = 0, stem = 0, flower = 0, grain = 0, quality = 0;

        for (int k = 0; k < nDays; k++) {
            int idx = startIdx + k;
            if (idx >= days.size()) {
                break;
            }
            DayWeather w = days.get(idx);
            LocalDate date = LocalDate.parse(w.date());
            double dvs = k < dvsTrace.size() ? dvsTrace.get(k) : 0.0;
            double tmean = w.tmean();
            double rh = humidityProxy(w.precip());
            boolean wet = w.precip() > 0.2;

            // ---- 施药（S12 三道闸门已在 Service 折算成 power）----
            List<Spray> today = sprayByDate.get(date);
            if (today != null) {
                for (Spray sp : today) {
                    for (MutablePest p : active) {
                        if (p.latencyLeft > 0) {
                            continue;
                        }
                        boolean matched = matches(sp, p.cfg.id(), legacyAllTargets);
                        // 闸门一：打错药只有 5% 的误伤，白花钱但不至于完全没动作
                        double agent = matched ? 1.0 : 0.05;
                        double base = p.severity < 0.15 ? 0.80 : (p.severity < 0.40 ? 0.60 : 0.35);
                        double eff = Math.max(0, base * agent * sp.power());
                        p.severity *= (1 - eff);
                        p.treated = p.treated || eff > 0.05;
                    }
                    // 🔴 保护期：写进「靶标 → 截止日」表（与实例是否已发生无关），
                    //    这样"显症前打的预防药"同样能压住初发菌源。
                    //    仅覆盖本产品的靶标：打虫药不防病。
                    //    ⚠️ power <= 0（药害剂量：doseFactor 为负 → 折算成 0）时不授予保护期，
                    //    否则"多打药拿到的保护期"会让药害变成净收益。
                    if (sp.power() > 0) {
                        LocalDate until = date.plusDays(Math.max(1, sp.protectDays()));
                        for (String tgt : sp.targets()) {
                            LocalDate cur = protectUntil.get(tgt);
                            if (cur == null || cur.isBefore(until)) {
                                protectUntil.put(tgt, until);
                            }
                        }
                        if (sp.targets().isEmpty() && legacyAllTargets) {
                            // 旧数据：全靶标保护
                            LocalDate cur = protectUntil.get(ALL_TARGETS);
                            if (cur == null || cur.isBefore(until)) {
                                protectUntil.put(ALL_TARGETS, until);
                            }
                        }
                    }
                }
            }

            // ① 已有病害：潜伏期结束 → Logistic 蔓延
            for (MutablePest p : active) {
                if (p.latencyLeft > 0) {
                    p.latencyLeft--;
                    continue;
                }
                double env = envSuitability(p.cfg, tmean, rh, wet, p.stormBoost);
                p.severity += p.cfg.spreadRate() * env * (1 - p.severity);
                p.incidence += p.cfg.spreadRate() * 0.35 * env * (1 - p.incidence);
                if (p.severity > 1) p.severity = 1;
                if (p.incidence > 1) p.incidence = 1;
                if (p.severity >= 0.10) {
                    p.detected = true;
                }
            }

            // ② 未发生的：每日做一次确定性伯努利抽样
            for (PestConfig cfg : configs) {
                if (byId.containsKey(cfg.id())) {
                    continue;
                }
                double pHost = (dvs >= cfg.dvsLo() && dvs < cfg.dvsHi()) ? 1.0 : 0.15;
                double pEnv = envSuitability(cfg, tmean, rh, wet, 1.0);
                double pInoc = cfg.inoculum();
                // 保护期：压低初发菌源。查「靶标 → 截止日」表，与该病虫是否已发生无关，
                // 所以预防性打药（显症前施药）同样有效。
                if (inProtectWindow(cfg.id(), date, protectUntil, legacyAllTargets)) {
                    pInoc *= 0.25;
                }
                // 🔴 S12 修复：日初发概率上限从 0.06 收到 0.02。
                // 原来 inoculum 本身就有 0.045–0.070，而上限 0.06 会把它截断；
                // 在湿度 bug 修好之前，pEnv 常常为 0，病害压根不发生，这个上限看不出问题。
                // 湿度修好后 pEnv 恒为 1（适宜），p 就等于 inoculum（被截到 0.06）——
                // 敏感期 40 天里日均 6% 的初发概率 ≈ 必然发生（实测 60/60 次全中）。
                // 现在：日上限 0.02 → 40 天累积约 55% 的发生率，
                // 配合"环境持续适宜才高发"的语义，才是"大概率发生"而不是"必然发生"，
                // 也让"预防性打药"有真实的经济价值（把 55% 压到 14%）。
                double p = Math.min(0.02, pHost * pEnv * pInoc);
                if (p > 0 && rnd(seed, k, cfg.id()) < p) {
                    MutablePest np = new MutablePest(cfg, date.toString(), cfg.latencyDays());
                    // 白叶枯病是"台风暴雨后伤口入侵"，发生前一周内有暴雨则菌源更强
                    np.stormBoost = hadStorm(days, idx - 7, idx) ? 1.6 : 1.0;
                    active.add(np);
                    byId.put(cfg.id(), np);
                }
            }

            // ③ 累计危害（按当日病情指数，不重复计已损失部分由最终值代表）
            leaf = 0; stem = 0; flower = 0; grain = 0; quality = 0;
            for (MutablePest p : active) {
                if (p.latencyLeft > 0) {
                    continue;
                }
                double dmg = p.severity * Math.max(0.15, p.incidence) * p.cfg.yieldLossMax();
                switch (p.cfg.target()) {
                    case "LEAF" -> {
                        leaf += dmg * 0.5;
                        grain += dmg * 0.5;
                    }
                    case "STEM" -> stem += dmg;
                    case "FLOWER" -> flower += dmg;
                    case "GRAIN" -> grain += dmg;
                    default -> {
                        stem += dmg * 0.34;
                        grain += dmg * 0.33;
                        leaf += dmg * 0.33;
                    }
                }
                quality += p.severity * p.cfg.qualityImpact();
            }
        }

        List<Instance> list = new ArrayList<>();
        for (MutablePest p : active) {
            list.add(new Instance(
                    p.cfg.id(), p.cfg.name(), p.cfg.type(), p.cfg.pathogen(),
                    round(p.severity), round(p.incidence), p.detected, p.onsetDate,
                    Math.max(0, p.latencyLeft), p.cfg.target()));
        }
        return new Result(list,
                Math.min(0.55, leaf), Math.min(0.55, stem),
                Math.min(0.55, flower), Math.min(0.55, grain),
                Math.min(0.60, quality));
    }

    // ------------------------------------------------------------------

    private static final class MutablePest {
        final PestConfig cfg;
        final String onsetDate;
        double severity = 0.02;
        double incidence = 0.05;
        boolean detected = false;
        int latencyLeft;
        boolean treated = false;
        double stormBoost = 1.0;
        // 🔴 S12 修复：protectUntil 已移到「靶标 → 截止日」表（见 simulate 的 protectUntil），
        // 挂在实例上会让"显症前打的预防药"无处可挂 —— 预防性打药收益为 0。

        MutablePest(PestConfig cfg, String onsetDate, int latency) {
            this.cfg = cfg;
            this.onsetDate = onsetDate;
            this.latencyLeft = latency;
        }
    }

    /** 保护期表里"全靶标"的哨兵 key（仅旧数据兼容用）。 */
    private static final String ALL_TARGETS = "*";

    /**
     * 某病虫当日是否处在保护期内（S12：保护期按产品靶标分别记）。
     *
     * <p>🔴 S12 修复：改为查「靶标 → 截止日」表，不再依赖"该病虫是否已发生"。
     * 原实现遍历已发生实例，尚未发病的靶标永远查不到 → 预防性打药完全无效。
     *
     * @param legacyAllTargets 旧数据没有靶标清单，按"覆盖所有病虫"处理
     */
    private static boolean inProtectWindow(String pestId, LocalDate date,
                                          java.util.Map<String, LocalDate> protectUntil,
                                          boolean legacyAllTargets) {
        LocalDate until = protectUntil.get(pestId);
        if (until != null && !date.isAfter(until)) {
            return true;
        }
        if (legacyAllTargets) {
            LocalDate all = protectUntil.get(ALL_TARGETS);
            if (all != null && !date.isAfter(all)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 药剂是否对某病虫对症。
     *
     * <p>🔴 S12 修复：旧数据（无 itemId）传入的 targets 是空集，原来靠
     * {@code legacyAllTargets && sp.targets().isEmpty()} 兜底；但新链路里
     * {@code legacyAllTargets} 恒为 false，于是"打对药"被判成"打错药"，
     * 防效只剩 5%。这里把判定拆开：空靶标只有在 legacy 模式下才视为全覆盖。
     */
    private static boolean matches(Spray sp, String pestId, boolean legacyAllTargets) {
        if (sp.targets() == null || sp.targets().isEmpty()) {
            return legacyAllTargets;
        }
        return sp.targets().contains(pestId);
    }

    /** 环境适宜度 = 温度梯形 × 湿度 × 叶面水膜。 */
    public static double envSuitability(PestConfig cfg, double tmean, double rh, boolean wet, double boost) {
        double ft;
        if (tmean >= cfg.tLo() && tmean <= cfg.tHi()) {
            ft = 1.0;
        } else {
            double dev = tmean < cfg.tLo() ? cfg.tLo() - tmean : tmean - cfg.tHi();
            ft = dev > 6 ? 0 : Math.max(0, 1 - dev * 0.15);
        }
        // 🔴 S12 修复：湿度适宜度的方向原来是反的。
        // 原式 (rh - humidityMin) / 15 意味着「湿度刚好达到病害需求下限时适宜度 = 0」，
        // 而 humidityProxy 只有 68/82/90/95 四档 —— 于是 humidityMin=90 的三种主要
        // 水稻病害（稻瘟病/纹枯病/白叶枯病）在 rh=90（日降水 5–20mm）时 fh 恒为 0，
        // 病害**永远不会发生**；只有日降水 ≥20mm（rh=95）才可能发生，且只有 1/3。
        // 这与现实完全相反：稻瘟病恰恰在连续阴雨（日降 5–15mm）时最重。
        //
        // 现在改成：湿度达到需求下限 → 适宜度 1（需求已满足）；低于下限时按差值线性衰减。
        // 温度那边用的也是同一个思路（落在 [tLo, tHi] 区间内 ft=1）。
        double fh = rh >= cfg.humidityMin()
                ? 1.0
                : Math.max(0, 1 - (cfg.humidityMin() - rh) / 15.0);
        double fw = cfg.leafWetness() ? (wet ? 1.0 : 0.25) : 1.0;
        return ft * fh * fw * boost;
    }

    /** 用降水反推相对湿度档位（无 RH 实测时的保守代理）。 */
    public static double humidityProxy(double precip) {
        if (precip >= 20) return 95;
        if (precip >= 5) return 90;
        if (precip >= 0.5) return 82;
        return 68;
    }

    private static boolean hadStorm(List<DayWeather> days, int from, int to) {
        for (int i = Math.max(0, from); i <= to && i < days.size(); i++) {
            if (days.get(i).precip() >= 50) {
                return true;
            }
        }
        return false;
    }

    /** 确定性随机：同一块地同一天永远同一个结果（可回放）。 */
    static double rnd(long seed, int day, String pestId) {
        long z = seed + day * 0x9E3779B97F4A7C15L + pestId.hashCode();
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return ((z >>> 11) & ((1L << 53) - 1)) / (double) (1L << 53);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }

    /** 便于测试：生成一个空结果 */
    public static Result empty() {
        return Result.EMPTY;
    }

    /** 未使用的占位，保持 Set 导入语义清晰 */
    static Set<String> ids(List<PestConfig> list) {
        Set<String> s = new HashSet<>();
        for (PestConfig c : list) s.add(c.id());
        return s;
    }
}
