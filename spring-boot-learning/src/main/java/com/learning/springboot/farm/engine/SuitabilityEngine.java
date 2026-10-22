package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 适区评级与失败归因（S23）。
 *
 * <p><b>核心口径（不可改）</b>：等级由 {@code P_pass} 唯一决定——近 10 年逐年回放中
 * 「亩产 ≥ 50% × Y_ref 且没死苗」的年数比例；七维因子打分<b>只用于归因</b>，
 * 不参与定级。结构性硬闸（盐碱 / pH / 水源 / 土层）一票 L5。
 *
 * <p><b>为什么不是打分制</b>：真实农业没有「适区许可」，只有「这一季大概率会发生什么」。
 * 概率来自物理回放，打分只是解释。若两者差 ≥2 级，以回放为准并记不一致告警（§4.5）。
 *
 * <p><b>不持有任何产量乘子</b>（T13 定论）：S23 只预测，不改写 S7 的产量公式；
 * 地块固有属性（盐分 / pH / 土层）已经由 S6 表达，这里再乘一次就是重复扣分。
 */
public final class SuitabilityEngine {

    private SuitabilityEngine() {
    }

    /** 缺省参照产量（水稻）。实际取 {@link CropConfig#yieldRef()}。 */
    public static final double Y_REF = 600.0;

    /** 作物可耐受的最短无霜期（数据卡缺值时按此给）。 */
    static int minFrostFree(String visual) {
        return switch (visual == null ? "rice" : visual) {
            case "wheat" -> 200;   // 冬小麦全季跨越冬春，需要更长的无霜窗口
            case "corn" -> 120;
            case "soybean" -> 110;
            case "leafy" -> 85;    // 大白菜短季，但怕初霜，窗口比生长期略长
            case "vine" -> 110;    // 瓜类全生育期怕霜
            case "bush" -> 125;    // 茄果类/草莓：花期遇霜直接绝收
            case "flower" -> 100;
            default -> 130;
        };
    }

    /** 作物适宜 pH 区间。 */
    static double[] phRange(String visual) {
        return switch (visual == null ? "rice" : visual) {
            case "wheat" -> new double[]{6.0, 7.5};
            case "corn" -> new double[]{5.5, 7.5};
            case "soybean" -> new double[]{6.0, 7.0};
            case "leafy" -> new double[]{6.0, 7.5};
            case "vine" -> new double[]{6.0, 7.0};
            case "bush" -> new double[]{5.8, 7.0};
            case "flower" -> new double[]{6.0, 7.2};
            default -> new double[]{5.5, 7.0};
        };
    }

    /** 达标线：低于参照产量一半，按中国大田成本结构基本等于亏本（§4.2）。 */
    public static final double PASS_RATIO = 0.50;



    /** 新田地力系数：SFI=66 → 0.60 + 0.40 × 0.66（S6 §4.7）。 */
    private static final double SOIL_COEF = 0.60 + 0.40 * 66 / 100.0;

    /** 播前投入（物化 + 人工，元/亩），来自数据卡成本口径。 */
    public static final double SOW_COST = 1150.0;

    // ==================================================================
    //  输出结构
    // ==================================================================

    public record Factor(String code, String name, int level, String metric,
                         double actual, double required, String unit) {
    }

    public record Gap(String factor, String metric, double actual, double required,
                      double gap, String unit, double gapInDays, double severity, String text) {
    }

    public record Report(
            String zoneId,
            String zoneName,
            String site,
            String dataSource,
            String cropName,
            String sowDate,
            int level,
            String levelCode,
            String levelName,
            double pPass,
            int samples,
            boolean provisional,
            double yieldRef,
            double yieldMedian,
            double yieldMedianPct,
            double yieldWorstPct,
            double yieldBandLo,
            double yieldBandHi,
            String primaryFactor,
            String primaryFactorName,
            List<Factor> factors,
            List<Gap> gaps,
            List<String> gates,
            String failureMode,
            String failureName,
            String failureText,
            String summary,
            String noticeLevel,
            double sowCost) {
    }

    // ==================================================================
    //  主流程
    // ==================================================================

    public static Report evaluate(String zoneId, LocalDate plannedSow, CropConfig crop) {
        ClimateModel.Zone zone = ClimateModel.zone(zoneId);
        double yRef = crop.yieldRef();
        int minFrostFree = minFrostFree(crop.visual());
        double[] ph = phRange(crop.visual());
        double phLo = ph[0];
        double phHi = ph[1];
        int sowDoy = plannedSow.getDayOfYear();
        int sowYear = plannedSow.getYear();

        // ---- 1. 十年回放（唯一决定等级的量） ----
        List<Double> yields = new ArrayList<>();
        List<SeasonRun> runs = new ArrayList<>();
        int pass = 0;
        for (int i = 0; i < ClimateModel.years(); i++) {
            int year = ClimateModel.firstYear() + i;
            SeasonRun run = replayOne(zone.id(), year, plannedSow.getMonthValue(),
                    plannedSow.getDayOfMonth(), crop);
            if (run == null) {
                continue;
            }
            runs.add(run);
            yields.add(run.yieldAdj());
            if (run.yieldAdj() >= PASS_RATIO * yRef) {
                pass++;
            }
        }
        int samples = yields.size();
        double pPass = samples == 0 ? 0 : (double) pass / samples;
        double median = percentile(yields, 50);
        double worst = percentile(yields, 10);
        SeasonRun medianRun = pickMedianRun(runs, median);
        SimEngine.SimResult sim = medianRun == null ? null : medianRun.sim();

        // ---- 2. 结构性硬闸 ----
        List<String> gates = new ArrayList<>();
        if (!zone.open()) {
            gates.add("G_ZONE");
        }
        if (zone.ph() < phLo - 1.0 || zone.ph() > phHi + 1.0) {
            gates.add("G_PH");
        }
        if (zone.ec() > 4.0) {
            gates.add("G_SALT");
        }
        if (zone.depthCm() < 25) {
            gates.add("G_DEPTH");
        }
        double seasonPrecip = seasonPrecip(zone.id(), sowYear, sowDoy);
        double waterSupply = seasonPrecip * 0.85 + zone.irrigationMm() * zone.reliability();
        if (waterSupply < zone.etcSeasonMm() * 0.35) {
            gates.add("G_WATER");
        }

        // ---- 3. 七维归因（只解释，不定级） ----
        ClimateModel.Stats st = ClimateModel.stats(zone.id());
        double aatSupply = ClimateModel.aatSupply(zone.id(), medianYearOf(plannedSow), sowDoy);
        double kHeat = aatSupply / crop.aatReq();
        int deltaFrost = st.frostFreeP50() - minFrostFree;
        // 有高温发育加速（f_dev_T）时，裸积温达到 92% 也可能正常成熟，
        // 所以 F1 的主判据取「初霜时的 DVS」，积温只作展示与缺口折算。
        // 但春化不足会让 DVS 卡在 0.42，那不是"热量不够"，不能算到 F1 头上。
        double dvsFrost = medianRun == null ? 0 : medianRun.dvsFrost();
        double vernReqF = crop.vernalizationDays();
        double vernDoneF = vernReqF <= 0 ? 1.0
                : Math.min(1.0, (medianRun == null ? 0 : medianRun.sim().vernalizationDays()) / vernReqF);
        int dF1;
        if (vernReqF > 0 && vernDoneF < 0.60) {
            dF1 = stepDown(deltaFrost, new int[]{15, 5, -3, -12, -30});
        } else {
            dF1 = Math.max(stepDown(dvsFrost, new double[]{1.0, 0.98, 0.92, 0.85, 0.65}),
                    stepDown(deltaFrost, new int[]{15, 5, -3, -12, -30}));
        }

        int h35 = medianRun == null ? 0 : medianRun.h35();
        int dF2 = stepWorse(h35, new double[]{5, 10, 15, 22, 30});
        // 春化不足（S23 §5.2 F2a）：冬性品种在暖冬区"只长草不抽穗"，比高温逼熟更致命
        double vernReq = crop.vernalizationDays();
        double vernDone = vernReq <= 0 ? 1.0
                : Math.min(1.0, (medianRun == null ? 0 : medianRun.sim().vernalizationDays()) / vernReq);
        if (vernReq > 0) {
            dF2 = Math.max(dF2, stepDown(vernDone, new double[]{1.0, 0.95, 0.85, 0.70, 0.50}));
        }

        int daysBeforeLastFrost = (int) Math.max(0, st.lastFrostP50() - sowDoy);
        int dF3 = stepWorse(daysBeforeLastFrost, new double[]{1, 6, 11, 16, 26});
        if (crop.winterHardy()) {
            // 越冬作物：冬季极端低温 vs 品种抗寒极限（留 4℃ 判定余量）
            double margin = st.winterTminP10() - crop.winterKillTmin();
            dF3 = Math.max(dF3, stepDown(margin, new double[]{6, 3, 1, -1, -4}));
        }
        double minTmin = medianRun == null ? 20 : medianRun.tminFlower();

        double kWater = waterSupply / zone.etcSeasonMm();
        int dF4 = stepUp(kWater, new double[]{1.15, 1.05, 0.98, 0.85, 0.65});

        int dF5 = 0; // 水稻在本区纬度带内，光周期不构成限制

        int dF6 = soilLevel(zone, phLo, phHi);

        double wetShare = medianRun == null ? 0 : medianRun.wetShare();
        int dF7 = stepWorse(wetShare, new double[]{0.15, 0.25, 0.35, 0.45, 0.55});

        List<Factor> factors = List.of(
                new Factor("F1", "热量不足", dF1, "AAT", round(aatSupply), crop.aatReq(), "℃·d"),
                new Factor("F2", vernReq > 0 ? "春化/高温" : "高温逼熟", dF2,
                        vernReq > 0 ? "VERN" : "H35",
                        vernReq > 0 ? round1(vernDone * 100) : h35,
                        vernReq > 0 ? 100 : 5,
                        vernReq > 0 ? "%" : "天"),
                new Factor("F3", crop.winterHardy() ? "越冬冻害" : "苗期霜冻", dF3,
                        crop.winterHardy() ? "TMIN" : "FROST_RISK",
                        crop.winterHardy() ? round1(st.winterTminP10()) : daysBeforeLastFrost,
                        crop.winterHardy() ? crop.winterKillTmin() : 0,
                        crop.winterHardy() ? "℃" : "天"),
                new Factor("F4", "水分不足", dF4, "WATER", round(waterSupply), zone.etcSeasonMm(), "mm"),
                new Factor("F5", "光周期", dF5, "DAYLENGTH", 0, 0, "天"),
                new Factor("F6", "土壤不匹配", dF6, "PH", zone.ph(), phHi, "pH"),
                new Factor("F7", "病虫害压力", dF7, "WET_DAYS", round(wetShare * 100), 15, "%"));

        // 平局顺序：致死速度由快到慢（F3 > F1 > F2 > F4 > F7 > F5 > F6）
        String[] tieOrder = {"F3", "F1", "F2", "F4", "F7", "F5", "F6"};
        String primary = bestFactor(factors, tieOrder);

        // ---- 4. 定级 ----
        int level = gates.isEmpty() ? levelFromPPass(pPass) : 5;
        // L5 的定义是「结构上不成」，不是「收成差」。若十年里一次都没达标、但中位年
        // 仍有 10% 以上收成（可转青贮），按 S23 §2.2 应落 L4 而不是 L5。
        if (level == 5 && gates.isEmpty() && median >= 0.10 * yRef) {
            level = 4;
        }
        // 自洽性：P_pass 达标但中位产量落在更低的产量带时，取较差者——
        // 否则会出现"L0 最适区，但中位产量只有 71%"这种自相矛盾。
        level = Math.max(level, levelFromYieldRatio(median / yRef));
        double[] band = YIELD_BAND[level];

        // ---- 5. 缺口与失败归因 ----
        List<Gap> gaps = buildGaps(factors, aatSupply, crop.aatReq(), sim, zone, waterSupply,
                st, wetShare, crop, phLo, phHi);
        double medianRatio = medianRun == null ? 1.0 : medianRun.ratio();
        String failureMode = failureMode(level, sim, medianRatio, h35);
        if (failureMode != null && !"M2".equals(failureMode) && vernReq > 0 && vernDone < 0.60
                && (sim == null || sim.matureDate() == null)) {
            failureMode = "M6";
        }
        String failureText = failureText(failureMode, zone, sim, aatSupply, crop.aatReq(),
                gapDays(aatSupply, crop.aatReq()), crop);

        String levelCode = LEVEL_CODES[level];
        String levelName = LEVEL_NAMES[level];
        String summary = summary(zone, level, levelName, samples, pass, median, sim, yRef);

        return new Report(
                zone.id(), zone.name(), zone.site(), dataSource(zone),
                crop.name(), plannedSow.toString(),
                level, levelCode, levelName,
                round3(pPass), samples, samples < 5,
                yRef, round1(median), round1(median / yRef * 100), round1(worst / yRef * 100),
                band[0], band[1],
                primary, primaryName(factors, primary),
                factors, gaps, gates,
                failureMode, failureName(failureMode), failureText,
                summary, level <= 1 ? "NONE" : (level == 2 ? "LIGHT" : "FULL"),
                SOW_COST);
    }

    // ==================================================================
    //  回放
    // ==================================================================

    /**
     * 一季回放的完整结果。
     *
     * <p>{@code yieldAdj} 才是用于评级的产量：在 S7 引擎输出之上补了 M1「霜前没熟」通道
     * （{@code MaturityRatio = AAT@初霜 / aat_req}，{@code f_maturity = 0.6 + 0.4 × ratio}，
     * {@code ratio < 0.55} 视为无经济产量）。S7 引擎本身不判"熟没熟"，这条通道属于 S23。
     */
    private record SeasonRun(SimEngine.SimResult sim, double yieldAdj, double ratio, double dvsFrost,
                             int h35, double tminFlower, double wetShare) {
    }

    /**
     * 单年回放：播种日固定为 {@code plannedSow} 的月日（不随年份偏移），
     * 用该年逐日天气跑 S7 引擎。
     */
    private static SeasonRun replayOne(String zoneId, int year, int month, int day, CropConfig crop) {
        try {
            int len = LocalDate.of(year, 1, 1).lengthOfYear();
            LocalDate sow = LocalDate.of(year, month, Math.min(day, 28));
            int idx = sow.getDayOfYear() - 1;
            if (idx >= len) {
                return null;
            }
            List<DayWeather> two = new ArrayList<>(ClimateModel.series(zoneId, year));
            two.addAll(ClimateModel.series(zoneId, year + 1));

            // ① 模拟必须停在初霜：越过霜期继续“长”是引擎不会自己判断的事，
            //    不截断就会把第二年春天算进这一季，产量虚高。
            //    越冬作物（冬小麦）例外——它的收获在次年，要跑满 ~300 天。
            int frostDoy = ClimateModel.firstAutumnFrost(ClimateModel.series(zoneId, year));
            int maxDays = crop.winterHardy()
                    ? 300
                    : Math.max(1, Math.min(200, frostDoy - idx));
            SimEngine.SimResult r = SimEngine.simulate(two, idx, crop, null, 0.0,
                    java.util.Collections.emptyMap(), maxDays, true);

            // ② M1「霜前没熟」：按停止时的 DVS 折算灌浆完成度
            double dvsFrost = r.dvsByDay().isEmpty()
                    ? 0 : r.dvsByDay().get(r.dvsByDay().size() - 1);
            double aatAtFrost = ClimateModel.aatSupply(zoneId, year, idx + 1);
            double ratio = crop.winterHardy()
                    ? Math.min(1.0, r.aatCum() / crop.aatReq())
                    : Math.min(1.0, aatAtFrost / crop.aatReq());
            // 灌浆折算已在 SimEngine 内完成（fillFactor），这里不再二次打折；
            // 只保留 §4.2 的「积温 < 55% 视为无经济产量」兜底。
            // 评级衡量的是「这块地的气候 + 品种潜力」，不乘地力系数——
            // 地力是玩家施肥/轮作的结果（S6），把它算进评级会让"同一块地"年年不同级。
            double yieldAdj = (ratio < 0.55) ? 0 : r.ey();

            // ---- 抽穗—灌浆期的归因指标 ----
            int h35 = 0;
            double tminFlower = 99;
            int wet = 0;
            int total = 0;
            for (int i = 0; i < r.dvsByDay().size(); i++) {
                int wi = idx + i;
                if (wi >= two.size()) {
                    break;
                }
                DayWeather d = two.get(wi);
                double dvs = r.dvsByDay().get(i);
                if (dvs >= 0.63 && dvs <= 0.92) {
                    if (d.tmax() >= 35) {
                        h35++;
                    }
                }
                if (dvs >= 0.63 && dvs <= 0.80) {
                    tminFlower = Math.min(tminFlower, d.tmin());
                }
                if (d.tmean() >= 24 && d.tmean() <= 28 && d.precip() > 0.4) {
                    wet++;
                }
                total++;
            }
            return new SeasonRun(r, yieldAdj, ratio, dvsFrost, h35,
                    tminFlower == 99 ? 20 : tminFlower,
                    total == 0 ? 0 : (double) wet / total);
        } catch (Exception e) {
            return null;
        }
    }

    private static SeasonRun pickMedianRun(List<SeasonRun> runs, double median) {
        SeasonRun best = null;
        double bestDiff = Double.MAX_VALUE;
        for (SeasonRun r : runs) {
            double d = Math.abs(r.yieldAdj() - median);
            if (d < bestDiff) {
                bestDiff = d;
                best = r;
            }
        }
        return best;
    }

    private static int medianYearOf(LocalDate sow) {
        // 用于取气候中位年的 AAT 供给；直接用中位年份（列表中间那个）
        return ClimateModel.firstYear() + ClimateModel.years() / 2;
    }

    private static double seasonPrecip(String zoneId, int year, int sowDoy) {
        List<DayWeather> s = ClimateModel.series(zoneId, year);
        double sum = 0;
        int end = Math.min(s.size(), sowDoy - 1 + 150);
        for (int i = sowDoy - 1; i < end; i++) {
            sum += s.get(i).precip();
        }
        return sum;
    }

    private static int heat35Days(SimEngine.SimResult r) {
        if (r.dvsByDay() == null) {
            return 0;
        }
        int n = 0;
        for (int i = 0; i < r.dvsByDay().size(); i++) {
            double dvs = r.dvsByDay().get(i);
            if (dvs >= 0.63 && dvs <= 0.92) {
                n++;
            }
        }
        return n;
    }

    private static double seasonMinTmin(String zoneId, LocalDate sow, int days) {
        List<DayWeather> s = ClimateModel.series(zoneId, sow.getYear());
        int start = Math.max(0, sow.getDayOfYear() - 1);
        double min = 99;
        for (int i = start; i < Math.min(s.size(), start + Math.max(1, days)); i++) {
            min = Math.min(min, s.get(i).tmin());
        }
        return min == 99 ? 20 : min;
    }

    /** 生长季内「日均 24–28℃ 且降雨」的天数占比——稻瘟/纹枯的气候适宜日。 */
    private static double wetWarmShare(String zoneId, LocalDate sow, int days) {
        List<DayWeather> s = ClimateModel.series(zoneId, sow.getYear());
        int start = Math.max(0, sow.getDayOfYear() - 1);
        int n = 0;
        int total = 0;
        for (int i = start; i < Math.min(s.size(), start + Math.max(1, days)); i++) {
            DayWeather d = s.get(i);
            total++;
            if (d.tmean() >= 24 && d.tmean() <= 28 && d.precip() > 0.4) {
                n++;
            }
        }
        return total == 0 ? 0 : (double) n / total;
    }

    private static int soilLevel(ClimateModel.Zone z, double phLo, double phHi) {
        int ph = 0;
        if (z.ph() < phLo) {
            ph = z.ph() > phLo - 0.5 ? 1 : (z.ph() > phLo - 1.0 ? 2 : 4);
        } else if (z.ph() > phHi) {
            ph = z.ph() < phHi + 0.5 ? 1 : (z.ph() < phHi + 1.0 ? 2 : 4);
        }
        int ec = z.ec() <= 1 ? 0 : (z.ec() <= 2 ? 1 : (z.ec() <= 4 ? 3 : 5));
        int depth = z.depthCm() >= 60 ? 0 : (z.depthCm() >= 45 ? 2 : (z.depthCm() >= 30 ? 3 : 5));
        return Math.max(ph, Math.max(ec, depth));
    }

    // ==================================================================
    //  归因
    // ==================================================================

    private static List<Gap> buildGaps(List<Factor> factors, double aatSupply, double aatReq,
                                       SimEngine.SimResult run, ClimateModel.Zone zone,
                                       double waterSupply, ClimateModel.Stats st, double wetShare,
                                       CropConfig crop, double phLo, double phHi) {
        List<Gap> out = new ArrayList<>();
        for (Factor f : factors) {
            if (f.level() < 2) {
                continue;
            }
            switch (f.code()) {
                case "F1" -> {
                    double gap = aatReq - aatSupply;
                    double days = gap / 29.0;
                    out.add(new Gap("F1", "AAT", round(aatSupply), aatReq, round(gap), "℃·d",
                            round1(days), norm(gap, 800),
                            String.format("播种到初霜只有 %.0f ℃·d，比需要的 %.0f 少 %.0f ℃·d，折合晚熟约 %.0f 天",
                                    aatSupply, aatReq, Math.max(0, gap), Math.max(0, days))));
                }
                case "F2" -> {
                    int h = (int) f.actual();
                    out.add(new Gap("F2", "H35", h, 5, h - 5, "天", 0, norm(h - 5, 25),
                            String.format("灌浆期有 %d 天日最高温 ≥35℃，超过可忍受的 5 天，千粒重会被压下来", h)));
                }
                case "F3" -> {
                    double t = f.actual();
                    out.add(new Gap("F3", "TMIN", round(t), 0, round(-t), "℃", 0, norm(-t, 6),
                            String.format("生长季最低气温到过 %.1f℃，水稻在苗期/抽穗期遇到 0℃ 以下会直接受伤", t)));
                }
                case "F4" -> {
                    double gap = zone.etcSeasonMm() - waterSupply;
                    out.add(new Gap("F4", "WATER", round(waterSupply), zone.etcSeasonMm(), round(gap), "mm",
                            0, norm(gap, 300),
                            String.format("全季需要 %.0f mm 水，这块地能供 %.0f mm（降水 + 灌溉），差 %.0f mm",
                                    zone.etcSeasonMm(), waterSupply, Math.max(0, gap))));
                }
                case "F6" -> {
                    out.add(new Gap("F6", "PH", round1(zone.ph()), phHi, round1(zone.ph() - phHi), "pH",
                            0, 0.5,
                            String.format("%s适宜 pH %.1f–%.1f，这块地是 %.1f",
                                    crop.name(), phLo, phHi, zone.ph())));
                }
                case "F7" -> {
                    out.add(new Gap("F7", "WET_DAYS", round(wetShare * 100), 15, round(wetShare * 100 - 15), "%",
                            0, norm(wetShare * 100 - 15, 30),
                            String.format("生长季有 %.0f%% 的日子高温高湿，稻瘟病与纹枯病的压力偏高，破口期要盯紧",
                                    wetShare * 100)));
                }
                default -> {
                }
            }
        }
        out.sort(Comparator.comparingDouble(Gap::severity).reversed());
        return out.size() > 3 ? new ArrayList<>(out.subList(0, 3)) : out;
    }

    private static String failureMode(int level, SimEngine.SimResult run, double matureRatio, int h35) {
        if (level < 3) {
            return null;
        }
        if (run != null && run.winterKilled()) {
            return "M2";
        }
        if (matureRatio < 0.95) {
            return "M1";
        }
        if (h35 >= 15) {
            return "M3";
        }
        if (run != null && run.fertility() < 0.45) {
            return "M3";
        }
        return "M1";
    }

    private static String failureName(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "M1" -> "不熟（贪青晚熟）";
            case "M2" -> "冻死";
            case "M3" -> "高温逼熟";
            case "M4" -> "旱死";
            case "M5" -> "涝死";
            case "M6" -> "不抽穗";
            case "M7" -> "病虫绝收";
            default -> null;
        };
    }

    private static String failureText(String code, ClimateModel.Zone zone, SimEngine.SimResult run,
                                      double aatSupply, double aatReq, double gapDays,
                                      CropConfig crop) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "M1" -> String.format(
                    "初霜到来时积温还差 %.0f ℃·d（约 %.0f 天），灌浆被迫中断，千粒重掉、青米率升。"
                            + "这是%s最典型的失败方式——植株一直绿着，收成却没了。",
                    Math.max(0, aatReq - aatSupply), Math.max(0, gapDays), zone.name());
            case "M3" -> String.format(
                    "抽穗开花期撞上连续高温，花粉失活、结实率掉到 %.0f%%。植株还活着、叶子还绿，穗子是空的。",
                    run == null ? 40 : run.fertility() * 100);
            case "M2" -> String.format(
                    "这个品种在%s过不了冬：冬季极端低温低于它能承受的 %.0f℃，分蘖节冻死，来年没有返青。"
                            + "越冬作物不是「种下去就行」，抗寒性是品种的第一道门槛。",
                    zone.name(), crop.winterKillTmin());
            case "M6" -> String.format(
                    "这个品种要经历足够长的 0–7℃ 低温才能抽穗，而%s一冬没有这么多冷天。"
                            + "整季只长叶不抽穗——这不是缺水缺肥，是品种与地区不匹配。", zone.name());
            default -> "本季存在结构性风险，详见限制因子。";
        };
    }

    private static String summary(ClimateModel.Zone zone, int level, String levelName, int samples,
                                  int pass, double median, SimEngine.SimResult run, double yRef) {
        String head = String.format("%s · %s：近 %d 年里有 %d 年亩产达到 %.0f kg 以上，评级「%s」。",
                zone.name(), zone.site(), samples, pass, PASS_RATIO * yRef, levelName);
        String tail = switch (level) {
            case 0 -> "正常年份稳收，限制因子不构成决策。";
            case 1 -> "多数年份稳收，个别年份会为天气付一次代价。";
            case 2 -> "能种，但每年都要为某个因子做一次决策。";
            case 3 -> "赌天气：赢的年份不差，输的年份很惨。";
            case 4 -> "大概率失败、小概率回本，教学价值大于经济价值。";
            default -> "结构上不成立：要么熟不了，要么会被冻死/烧死。";
        };
        String third = "";
        if (run != null && run.headingDate() != null && run.matureDate() != null) {
            third = String.format(" 中位年抽穗 %s、成熟 %s。", run.headingDate(), run.matureDate());
        }
        return head + tail + third;
    }

    private static String dataSource(ClimateModel.Zone zone) {
        return "jiangnan".equals(zone.id())
                ? "南昌 2019 年实测为基线年，其余年份叠加气温距平推算"
                : zone.name() + "气候推算（月均值 + 随机天气发生器，非实测）";
    }

    // ==================================================================
    //  等级表
    // ==================================================================

    private static final String[] LEVEL_CODES = {
            "OPTIMAL", "SUITABLE", "SUBOPTIMAL", "RISKY", "MARGINAL", "INFEASIBLE"};
    private static final String[] LEVEL_NAMES = {
            "最适区", "适宜区", "次适宜区", "风险区", "勉强可种", "理论不可行"};
    /** 产量带（相对 Y_ref 的百分比，中位年口径）。 */
    private static final double[][] YIELD_BAND = {
            {85, 100}, {70, 85}, {50, 70}, {30, 50}, {10, 30}, {0, 10}};

    /** 中位产量相对 Y_ref 的比例 → 等级（与 §2.2 的产量带对齐）。 */
    private static int levelFromYieldRatio(double r) {
        if (r >= 0.85) return 0;
        if (r >= 0.70) return 1;
        if (r >= 0.50) return 2;
        if (r >= 0.30) return 3;
        if (r >= 0.10) return 4;
        return 5;
    }

    private static int levelFromPPass(double p) {
        if (p >= 0.85) {
            return 0;
        }
        if (p >= 0.65) {
            return 1;
        }
        if (p >= 0.45) {
            return 2;
        }
        if (p >= 0.25) {
            return 3;
        }
        if (p >= 0.05) {
            return 4;
        }
        return 5;
    }

    private static String bestFactor(List<Factor> factors, String[] order) {
        int max = 0;
        for (Factor f : factors) {
            max = Math.max(max, f.level());
        }
        if (max == 0) {
            return null;
        }
        for (String code : order) {
            for (Factor f : factors) {
                if (f.code().equals(code) && f.level() == max) {
                    return code;
                }
            }
        }
        return "F1";
    }

    private static String primaryName(List<Factor> factors, String code) {
        if (code == null) {
            return "无明显限制因子";
        }
        for (Factor f : factors) {
            if (f.code().equals(code)) {
                return f.name();
            }
        }
        return factorName(code);
    }

    public static String factorName(String code) {
        return switch (code) {
            case "F1" -> "热量不足";
            case "F2" -> "高温逼熟";
            case "F3" -> "霜冻";
            case "F4" -> "水分不足";
            case "F5" -> "光周期";
            case "F6" -> "土壤不匹配";
            case "F7" -> "病虫害压力";
            default -> "无";
        };
    }

    // ==================================================================
    //  工具
    // ==================================================================

    /** 值越大越差；`bounds` 递减，返回 0–5。 */
    private static int stepUp(double v, double[] bounds) {
        for (int i = 0; i < bounds.length; i++) {
            if (v >= bounds[i]) {
                return i;
            }
        }
        return bounds.length;
    }

    /** 值越大越差；`bounds` 递增，返回 0–5。 */
    private static int stepWorse(double v, double[] bounds) {
        for (int i = 0; i < bounds.length; i++) {
            if (v < bounds[i]) {
                return i;
            }
        }
        return bounds.length;
    }

    /** 值越小越差；`bounds` 递减，返回 0–5。 */
    private static int stepDown(double v, double[] bounds) {
        for (int i = 0; i < bounds.length; i++) {
            if (v >= bounds[i]) {
                return i;
            }
        }
        return bounds.length;
    }

    /** 值越小越差；`bounds` 递减，返回 0–5。 */
    private static int stepDown(double v, int[] bounds) {
        for (int i = 0; i < bounds.length; i++) {
            if (v >= bounds[i]) {
                return i;
            }
        }
        return bounds.length;
    }

    private static double gapDays(double aatSupply, double aatReq) {
        return Math.max(0, (aatReq - aatSupply) / 29.0);
    }

    private static double norm(double v, double scale) {
        return Math.max(0, Math.min(1, v / scale));
    }

    private static double percentile(List<Double> v, int p) {
        if (v.isEmpty()) {
            return 0;
        }
        List<Double> c = new ArrayList<>(v);
        c.sort(Double::compare);
        double pos = (p / 100.0) * (c.size() - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        double f = pos - lo;
        return c.get(lo) * (1 - f) + c.get(hi) * f;
    }

    private static double round(double v) {
        return Math.round(v);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
