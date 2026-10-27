package com.learning.springboot.farm.achievement;

import com.learning.springboot.farm.FarmSeason;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 成就 / 目标引擎（纯计算，无 Spring 依赖）。
 *
 * <p><b>设计原则：成就必须「可验证、可复现、可解释」</b>。
 * 任何一个成就都只用玩家自己的历史档案（{@code t_farm_season}）来判定，
 * 不依赖任何「玩家当时是否看过弹窗」这类状态 —— 于是成就状态永远是
 * 「重算一遍就得到同样的结果」，不需要额外的持久化表，也不会出现
 * 「成就没弹出来所以没记录」这类静默失效。
 *
 * <p>🔴 <b>为什么不用「产量绝对值」当阈值</b>：22 种作物的产量单位不可比
 * （水稻 600 kg/亩 vs 白菜 5000 kg/亩 vs 郁金香 500 kg/亩 但按「支」计）。
 * 用绝对公斤数当目标，等于「只有种水稻的人能得」。所以统一用
 * <b>「实收 / 该作物参照产量」的比值</b>做跨作物可比的口径，
 * 这与游戏内评级用的 {@code yieldRef} 是同一个分母。
 *
 * <p>🔴 <b>参照系必须分清</b>：
 * <ul>
 *   <li>{@code yieldRaw}（{@code t_farm_season.yield_raw}）= 引擎算出的<b>原始</b>产量，
 *       还没扣病虫害 / 杂草 / 出苗率 —— 它更接近「理论上限」；</li>
 *   <li>{@code yieldKgPerMu}（{@code t_farm_season.yield_kg_per_mu}）= <b>实收</b>产量，
 *       扣完全部损失与草率。</li>
 * </ul>
 * 成就判「种得好不好」用<b>实收比值</b>（把管理能力算进去），
 * 判「潜力挖尽了没」用<b>原始比值</b>。两个口径分开，避免混用。
 *
 * <p>本类不依赖 Spring / JPA，可直接单元测试（与 {@code farm.engine} 同款约束）。
 */
public final class AchievementEngine {

    private AchievementEngine() {
    }

    // ==================================================================
    //  成就定义
    // ==================================================================

    /** 成就类别。 */
    public enum Category {
        /** 起步：第一次种地、第一次收获 */
        BEGINNER,
        /** 技艺：产量与管理水平 */
        SKILL,
        /** 持续：连季不断茬 */
        PERSISTENCE,
        /** 收藏：作物图鉴 */
        COLLECTION,
        /** 经营：净收益与盈利 */
        ECONOMY
    }

    /**
     * 一条成就定义。
     *
     * @param code       成就编码（前端 i18n key 用它）
     * @param category   类别
     * @param target     达成所需的目标值（进度分母）
     * @param unit       单位（{@code "season"} / {@code "crop"} / {@code "ratio"} / {@code "cny"}）
     * @param nameKey    名称 i18n key
     * @param descKey    描述 i18n key
     */
    public record Def(String code, Category category, double target, String unit,
                      String nameKey, String descKey) {
    }

    /**
     * 全部成就定义。
     *
     * <p>🔴 <b>阈值不是拍脑袋定的</b>，而是从真实引擎数据反推的
     * （2026-10-26 用 {@code SimEngine.fullPotentialYield} 跑完 22 种作物）：
     * <pre>
     *   满产/yieldRef 比值区间：0.756（向日葵）~ 1.178（冬小麦）
     *   实收（扣草率+损失后）/yieldRef：白菜 0.52、番茄 0.35、郁金香 0.25（实测档案）
     * </pre>
     * 所以：
     * <ul>
     *   <li><b>达标线 0.60</b>：需要「草率基本满 + 损失不大」，普通玩家够得着；</li>
     *   <li><b>优秀线 0.85</b>：需要管理到位（防住主要病虫害 + 补齐工序）；</li>
     *   <li><b>极限线 0.95</b>：接近理论上限，是给「玩明白的人」的目标。</li>
     * </ul>
     * 三个台阶都挂在**比值**上，于是 22 种作物通用 —— 不会出现
     * 「种水稻的人才能拿成就，种白菜的人永远 0 分」。
     */
    public static final List<Def> DEFS = List.of(
            // ---------- 起步 ----------
            new Def("first_sow", Category.BEGINNER, 1, "season",
                    "farm.ach.firstSow", "farm.ach.firstSowDesc"),
            new Def("first_harvest", Category.BEGINNER, 1, "season",
                    "farm.ach.firstHarvest", "farm.ach.firstHarvestDesc"),
            new Def("harvest_10", Category.BEGINNER, 10, "season",
                    "farm.ach.harvest10", "farm.ach.harvest10Desc"),

            // ---------- 技艺（比值口径，跨作物可比）----------
            new Def("yield_60", Category.SKILL, 0.60, "ratio",
                    "farm.ach.yield60", "farm.ach.yield60Desc"),
            new Def("yield_85", Category.SKILL, 0.85, "ratio",
                    "farm.ach.yield85", "farm.ach.yield85Desc"),
            new Def("yield_95", Category.SKILL, 0.95, "ratio",
                    "farm.ach.yield95", "farm.ach.yield95Desc"),
            new Def("yield_perfect_5", Category.SKILL, 5, "season",
                    "farm.ach.yieldPerfect5", "farm.ach.yieldPerfect5Desc"),

            // ---------- 持续 ----------
            new Def("streak_3", Category.PERSISTENCE, 3, "season",
                    "farm.ach.streak3", "farm.ach.streak3Desc"),
            new Def("streak_5", Category.PERSISTENCE, 5, "season",
                    "farm.ach.streak5", "farm.ach.streak5Desc"),

            // ---------- 收藏 ----------
            new Def("crops_5", Category.COLLECTION, 5, "crop",
                    "farm.ach.crops5", "farm.ach.crops5Desc"),
            new Def("crops_10", Category.COLLECTION, 10, "crop",
                    "farm.ach.crops10", "farm.ach.crops10Desc"),
            new Def("crops_all", Category.COLLECTION, 22, "crop",
                    "farm.ach.cropsAll", "farm.ach.cropsAllDesc"),

            // ---------- 经营 ----------
            // 🔴 profit_first 用 flag 单位：它的语义是「有一季赚了钱」（门槛 > 0），
            // 不是「攒够 N 块」。若用 cny 单位，进度会显示成「¥7119 / ¥1」，
            // 分母是 1 元，读起来像 bug。
            new Def("profit_first", Category.ECONOMY, 1, "flag",
                    "farm.ach.profitFirst", "farm.ach.profitFirstDesc"),
            new Def("profit_10000", Category.ECONOMY, 10000, "cny",
                    "farm.ach.profit10k", "farm.ach.profit10kDesc"),
            new Def("profit_50000", Category.ECONOMY, 50000, "cny",
                    "farm.ach.profit50k", "farm.ach.profit50kDesc")
    );

    // ==================================================================
    //  统计口径
    // ==================================================================

    /**
     * 从玩家全部季节档案算出的统计快照。
     *
     * <p>所有字段都只由 {@code t_farm_season} 派生 —— 不读内存态、不读前端传参，
     * 因此同一份档案任何时候重算都得到同样的结果。
     *
     * @param seasons            该用户全部季节档案
     * @param yieldRefs          作物 id → 参照产量（{@code yieldRef}），缺 0 表示未知作物
     * @param totalCropCount     作物库总数（图鉴总进度用）
     */
    public record Stats(
            List<FarmSeason> seasons,
            Map<String, Double> yieldRefs,
            int totalCropCount) {

        /** 首次播种日期（最早一条档案的播种日），没播种过则 null。 */
        public java.time.LocalDate firstSowDate() {
            return seasons.stream()
                    .map(FarmSeason::getSowDate)
                    .filter(java.util.Objects::nonNull)
                    .min(java.time.LocalDate::compareTo)
                    .orElse(null);
        }

        /** 首次收获日期（最早一条 HARVESTED 档案的收获日）。 */
        public java.time.LocalDate firstHarvestDate() {
            return seasons.stream()
                    .filter(s -> s.getStatus() == FarmSeason.Status.HARVESTED)
                    .map(FarmSeason::getHarvestDate)
                    .filter(java.util.Objects::nonNull)
                    .min(java.time.LocalDate::compareTo)
                    .orElse(null);
        }

        /** 正常收获的季数。 */
        public long harvestedCount() {
            return seasons.stream().filter(s -> s.getStatus() == FarmSeason.Status.HARVESTED).count();
        }

        /** 累计净收益（营收 - 投入）。失败季只扣投入，不加营收。 */
        public double totalNet() {
            double sum = 0;
            for (FarmSeason s : seasons) {
                double revenue = s.getRevenue() == null ? 0 : s.getRevenue();
                double cost = s.getInputCost() == null ? 0 : s.getInputCost();
                sum += revenue - cost;
            }
            return sum;
        }

        /** 单季最高净收益。 */
        public double bestNet() {
            return seasons.stream()
                    .filter(s -> s.getStatus() == FarmSeason.Status.HARVESTED)
                    .mapToDouble(s -> {
                        double revenue = s.getRevenue() == null ? 0 : s.getRevenue();
                        double cost = s.getInputCost() == null ? 0 : s.getInputCost();
                        return revenue - cost;
                    })
                    .max().orElse(0);
        }

        /** 单季最高实收比值（实收 / 该作物 yieldRef）。无有效档案则 0。 */
        public double bestYieldRatio() {
            return seasons.stream()
                    .filter(s -> s.getStatus() == FarmSeason.Status.HARVESTED)
                    .mapToDouble(s -> ratioOf(s.getYieldKgPerMu(), yieldRefs.get(s.getCropId())))
                    .max().orElse(0);
        }

        /** 实收比值 ≥ 0.60 的季数（累计口径，用于概览展示）。 */
        public long goodRatioCount(double threshold) {
            return seasons.stream()
                    .filter(s -> s.getStatus() == FarmSeason.Status.HARVESTED)
                    .filter(s -> ratioOf(s.getYieldKgPerMu(), yieldRefs.get(s.getCropId())) >= threshold)
                    .count();
        }

        /** 已成功收获过的不同作物数（图鉴）。 */
        public int distinctHarvestedCrops() {
            Set<String> ids = new LinkedHashSet<>();
            for (FarmSeason s : seasons) {
                if (s.getStatus() == FarmSeason.Status.HARVESTED && s.getCropId() != null) {
                    ids.add(s.getCropId());
                }
            }
            return ids.size();
        }

        /** 已成功收获过的作物 id 集合（图鉴明细）。 */
        public Set<String> harvestedCropIds() {
            Set<String> ids = new LinkedHashSet<>();
            for (FarmSeason s : seasons) {
                if (s.getStatus() == FarmSeason.Status.HARVESTED && s.getCropId() != null) {
                    ids.add(s.getCropId());
                }
            }
            return ids;
        }

        /**
         * 最长「连季不断茬」长度 —— <b>当前</b>的连续成功季数。
         *
         * <p>🔴 <b>口径必须是「当前连续」而不是「历史最长段」</b>：
         * 「连季不断茬」这个成就的意义就是<b>惩罚断茬</b>。如果用历史最长段，
         * 玩家连续种成 3 季后故意放弃一季，成就会永久挂着、界面继续显示「3 连季」，
         * 那这个成就就完全没有约束力了。用「当前连续」的话，断一次茬进度就回退，
         * 玩家会真切感到「我断档了，得补回来」。
         *
         * <p>🔴 <b>「断茬」的定义</b>：同一块地上，一季失败（FAILED / ABANDONED）
         * 之后又重新种起来，中间就断了一茬 —— 玩家没能把那季撑下来。
         *
         * <p>🔴 <b>为什么按「地块」而不是按「全局时间线」统计</b>：
         * 玩家有 12 块地，它们各自独立推进。地块 A 第 1 季失败不影响地块 B 第 2 季。
         * 所以「连季」是<b>每块地各自的连续成功次数</b>，再取全地块最大值。
         * 混成全局时间线会得出「同时种地反而更难连季」这种荒谬结论。
         *
         * <p>「在长（GROWING）」的一季既不算成功也不算断茬 —— 跳过它继续往前数，
         * 因为这一季的结果还没出来。
         */
        public int longestStreak() {
            return longestRunOn(s -> s.getStatus() == FarmSeason.Status.HARVESTED);
        }

        /**
         * 最长「连续达标」季数 —— 每季实收比值都 ≥ threshold。
         *
         * <p>与 {@link #longestStreak()} 同款口径（当前连续、按地块），
         * 只是判定条件换成「这一季的实收比值达标」。
         */
        public int longestGoodStreak(double threshold) {
            return longestRunOn(s -> s.getStatus() == FarmSeason.Status.HARVESTED
                    && ratioOf(s.getYieldKgPerMu(), yieldRefs.get(s.getCropId())) >= threshold);
        }

        /**
         * 通用「当前连续段」计算：按地块分组、按季次排序，从最新一季往前数。
         *
         * @param ok 判定「这一季算不算数」的谓词
         * @return 全地块最大的连续段长度
         */
        private int longestRunOn(java.util.function.Predicate<FarmSeason> ok) {
            Map<Long, List<FarmSeason>> byPlot = new LinkedHashMap<>();
            for (FarmSeason s : seasons) {
                byPlot.computeIfAbsent(s.getPlotId(), k -> new ArrayList<>()).add(s);
            }
            int best = 0;
            for (List<FarmSeason> list : byPlot.values()) {
                list.sort(Comparator.comparing(
                        s -> s.getSeasonNo() == null ? 0 : s.getSeasonNo()));
                int cur = 0;
                // 从最新一季往前数
                for (int i = list.size() - 1; i >= 0; i--) {
                    FarmSeason s = list.get(i);
                    if (ok.test(s)) {
                        cur++;
                    } else if (s.getStatus() == FarmSeason.Status.GROWING) {
                        // 在长的这季还没结果，跳过（不算断茬）
                        continue;
                    } else {
                        // 失败 / 放弃 → 断了，连段到此为止
                        break;
                    }
                }
                best = Math.max(best, cur);
            }
            return best;
        }
    }

    /** 实收比值：参照产量缺失（未知作物）时返回 0，不参与任何成就。 */
    static double ratioOf(Double actual, Double ref) {
        if (actual == null || ref == null || ref <= 0) {
            return 0;
        }
        return actual / ref;
    }

    // ==================================================================
    //  成就计算
    // ==================================================================

    /**
     * 一条成就的达成状态。
     *
     * @param code       成就编码
     * @param category   类别
     * @param target     目标值
     * @param progress   当前进度（未达成时 < target）
     * @param unit       单位
     * @param nameKey    名称 i18n key
     * @param descKey    描述 i18n key
     * @param unlocked   是否已达成
     */
    public record Achievement(String code, Category category, double target, double progress,
                              String unit, String nameKey, String descKey, boolean unlocked) {

        /** 进度百分比（0–100），用于前端进度条。 */
        public int percent() {
            if (target <= 0) {
                return unlocked ? 100 : 0;
            }
            return (int) Math.max(0, Math.min(100, Math.round(progress / target * 100)));
        }
    }

    /**
     * 算出全部成就的达成状态。
     *
     * @param stats 统计快照
     * @return 按定义顺序排列的成就列表
     */
    public static List<Achievement> evaluate(Stats stats) {
        List<Achievement> out = new ArrayList<>(DEFS.size());
        for (Def d : DEFS) {
            double progress = progressOf(d, stats);
            out.add(new Achievement(d.code(), d.category(), d.target(), progress, d.unit(),
                    d.nameKey(), d.descKey(), progress >= d.target()));
        }
        return out;
    }

    /** 各成就的当前进度。 */
    private static double progressOf(Def d, Stats stats) {
        return switch (d.code()) {
            case "first_sow" -> stats.firstSowDate() == null ? 0 : 1;
            case "first_harvest" -> stats.firstHarvestDate() == null ? 0 : 1;
            case "harvest_10" -> stats.harvestedCount();
            // 技艺：比值类成就进度用「比值本身」，达标线 0.60 / 0.85 / 0.95
            case "yield_60" -> stats.bestYieldRatio();
            case "yield_85" -> stats.bestYieldRatio();
            case "yield_95" -> stats.bestYieldRatio();
            // 「连续 5 季达标」用的是达标线 0.60，且是**当前连续**而非累计
            case "yield_perfect_5" -> stats.longestGoodStreak(0.60);
            case "streak_3" -> stats.longestStreak();
            case "streak_5" -> stats.longestStreak();
            case "crops_5" -> stats.distinctHarvestedCrops();
            case "crops_10" -> stats.distinctHarvestedCrops();
            case "crops_all" -> stats.distinctHarvestedCrops();
            case "profit_first" -> stats.bestNet() > 0 ? 1 : 0;
            case "profit_10000" -> stats.totalNet();
            case "profit_50000" -> stats.totalNet();
            default -> 0;
        };
    }

    /**
     * 汇总面板用的概览数字。
     *
     * @param achievements 已算好的成就列表
     * @param stats        统计快照
     */
    public record Summary(int unlockedCount, int totalCount, int percent,
                          int cropsCollected, int cropsTotal,
                          double bestYieldRatio, double totalNet, int longestStreak,
                          java.time.LocalDate firstSowDate, java.time.LocalDate firstHarvestDate,
                          long harvestedCount) {
    }

    /** 生成概览。 */
    public static Summary summarize(List<Achievement> achievements, Stats stats) {
        int unlocked = (int) achievements.stream().filter(Achievement::unlocked).count();
        int total = achievements.size();
        return new Summary(
                unlocked, total,
                total == 0 ? 0 : (int) Math.round(unlocked * 100.0 / total),
                stats.distinctHarvestedCrops(), stats.totalCropCount(),
                stats.bestYieldRatio(), stats.totalNet(), stats.longestStreak(),
                stats.firstSowDate(), stats.firstHarvestDate(),
                stats.harvestedCount());
    }
}
