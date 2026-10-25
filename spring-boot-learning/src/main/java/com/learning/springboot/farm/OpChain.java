package com.learning.springboot.farm;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 水稻工序链（S10 精简实现）。
 *
 * <p>14 道完整链里，本版把「播种时已带过」的三道（泡田耙地 / 基肥 / 插秧）标为 AUTO，
 * 其余按物候窗口给出 DONE / AVAILABLE / LOCKED / MISSED 四种状态。
 *
 * <p>窗口只写 DVS，不写日历 —— 与 S10 §2.1 一致：玩家侧的时间单位是"会话"，
 * 提醒必须由状态触发，不能假设玩家每天在线。
 */
public final class OpChain {

    private OpChain() {
    }

    public enum Status {
        /** 本季已完成 */
        DONE,
        /** 播种作业已带过，不可重复 */
        AUTO,
        /** 当前窗口内，可以执行 */
        AVAILABLE,
        /** 还没到窗口 */
        LOCKED,
        /** 窗口已过且没做 */
        MISSED
    }

    public record Step(
            String id,          // ACTION 类型名，或 HARVEST
            String name,
            String group,       // 整地移栽 / 田间管理 / 收获
            double from,
            double to,
            boolean repeatable,
            Status status,
            String date,
            double quality,
            int count,
            String hint
    ) {
    }

    private record Def(String id, String name, String group, double from, double to,
                       boolean repeatable, boolean auto) {
    }


    // ==================================================================
    //  园艺作物（蔬菜 / 水果 / 花卉）
    //
    //  与粮食作物共用同一套 Action 类型，只换工序名与 DVS 窗口：
    //  新增枚举会连带改 CHECK 约束、前端与历史数据迁移，收益不抵成本。
    // ==================================================================

    /** 叶菜（大白菜）：短季，关键是莲座期追肥与结球期水分均匀（忽干忽湿会裂球）。 */
    private static List<Def> leafyDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥（有机肥为主）", "整地播种", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "直播/育苗移栽", "整地播种", 0.00, 0.08, false, true),
                new Def("TILLER_FERT", "莲座期追氮", "田间管理", 0.25, 0.50, false, false),
                new Def("IRRIGATE", "灌水（结球期忌忽干忽湿）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "防虫防病（菜青虫/霜霉）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "采收", "收获", 0.85, 1.30, false, false),
                new Def("DRY_GRAIN", "预冷入窖", "收获", 0.92, 1.30, false, false)
        );
    }

    /** 茄果类（番茄/辣椒）：连续采收，追肥分两次，盛果期最怕脱肥。 */
    private static List<Def> fruitVegDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥", "整地定植", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "定植", "整地定植", 0.00, 0.08, false, true),
                new Def("TILLER_FERT", "搭架绑蔓 + 提苗肥", "田间管理", 0.15, 0.40, false, false),
                new Def("PANICLE_FERT", "盛果期追肥", "田间管理", 0.45, 0.70, false, false),
                new Def("IRRIGATE", "灌水（忌大水漫灌）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（灰霉/红蜘蛛）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "分批采收", "收获", 0.82, 1.30, false, false),
                new Def("DRY_GRAIN", "分级包装", "收获", 0.88, 1.30, false, false)
        );
    }

    /** 瓜类（黄瓜/西瓜/甜瓜）：整枝压蔓决定坐果，膨瓜期需水最多。 */
    private static List<Def> vineDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥", "整地播种", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "直播/定植", "整地播种", 0.00, 0.08, false, true),
                new Def("TILLER_FERT", "整枝压蔓", "田间管理", 0.18, 0.45, false, false),
                new Def("PANICLE_FERT", "膨瓜肥", "田间管理", 0.50, 0.75, false, false),
                new Def("IRRIGATE", "灌水（膨瓜期不能缺）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（霜霉/白粉/瓜蚜）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "采收", "收获", 0.88, 1.30, false, false),
                new Def("DRY_GRAIN", "分级装运", "收获", 0.92, 1.30, false, false)
        );
    }

    /** 草莓：秋植越冬，返青后连追两次肥，果期怕灰霉。 */
    private static List<Def> berryDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥", "整地定植", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "定植（带土坨）", "整地定植", 0.05, 0.35, false, true),
                new Def("TILLER_FERT", "返青追肥", "田间管理", 0.35, 0.55, false, false),
                new Def("PANICLE_FERT", "花果肥", "田间管理", 0.55, 0.75, false, false),
                new Def("IRRIGATE", "滴灌（忌叶面结水）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（灰霉/红蜘蛛）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "分批采收", "收获", 0.88, 1.30, false, false),
                new Def("DRY_GRAIN", "预冷包装", "收获", 0.92, 1.30, false, false)
        );
    }

    /** 花卉：摘心/整枝决定花枝数与整齐度，现蕾后追肥直接影响花径。 */
    private static List<Def> flowerDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥", "整地定植", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "定植/下种球", "整地定植", 0.00, 0.25, false, true),
                new Def("TILLER_FERT", "摘心整枝", "田间管理", 0.20, 0.50, false, false),
                new Def("PANICLE_FERT", "现蕾追肥", "田间管理", 0.50, 0.75, false, false),
                new Def("IRRIGATE", "灌水", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（白粉/蚜虫/灰霉）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "切花采收", "收获", 0.88, 1.30, false, false),
                new Def("DRY_GRAIN", "分级保鲜", "收获", 0.92, 1.30, false, false)
        );
    }

    /** 块茎（马铃薯）：培土与垄作是核心，中耕除草次数决定薯形与产量。 */
    private static List<Def> tuberDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥（有机肥+钾肥）", "整地播种", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "切块催芽播种", "整地播种", 0.00, 0.10, false, true),
                new Def("TILLER_FERT", "苗期追肥 + 第一次培土", "田间管理", 0.15, 0.40, false, false),
                new Def("PANICLE_FERT", "块茎膨大期追钾", "田间管理", 0.45, 0.75, false, false),
                new Def("IRRIGATE", "灌水（现蕾至块茎膨大最关键）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（晚疫病/早疫病）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "收获", "收获", 0.90, 1.30, false, false),
                new Def("DRY_GRAIN", "窖藏（避光防发芽）", "收获", 0.94, 1.30, false, false)
        );
    }

    /** 豆角（荚菜）：搭架摘心是产量关键，抽蔓前不搭架等于白种。 */
    private static List<Def> beanDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥", "整地播种", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "直播/育苗移栽", "整地播种", 0.00, 0.08, false, true),
                new Def("TILLER_FERT", "搭架 + 摘心", "田间管理", 0.15, 0.40, false, false),
                new Def("PANICLE_FERT", "盛荚期追肥", "田间管理", 0.50, 0.75, false, false),
                new Def("IRRIGATE", "灌水（花荚期缺水会落荚）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（锈病/豆荚螟）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "分批采收", "收获", 0.85, 1.30, false, false),
                new Def("DRY_GRAIN", "分级整理", "收获", 0.90, 1.30, false, false)
        );
    }

    /** 果树（梨）：疏果是产量控制的关键，留果太多反而压品质。 */
    private static List<Def> treeDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥（秋施有机肥）", "整地定植", 0.00, 0.15, false, true),
                new Def("TRANSPLANT", "定植", "整地定植", 0.00, 0.10, false, true),
                new Def("TILLER_FERT", "萌芽前追肥 + 疏果", "田间管理", 0.35, 0.60, false, false),
                new Def("PANICLE_FERT", "果实膨大期追肥", "田间管理", 0.60, 0.85, false, false),
                new Def("IRRIGATE", "灌水（花期与膨大期）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（黑星病/介壳虫）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "采收", "收获", 0.90, 1.30, false, false),
                new Def("DRY_GRAIN", "分级预冷", "收获", 0.94, 1.30, false, false)
        );
    }

    /** 谷子（杂粮）：蹲苗期不能追肥，灌浆期是决定粒重的关键。 */
    private static List<Def> milletDefs() {
        return List.of(
                new Def("BASE_FERT", "施基肥（种肥同播）", "整地播种", 0.00, 0.10, false, true),
                new Def("TRANSPLANT", "播种", "整地播种", 0.00, 0.06, false, true),
                new Def("TILLER_FERT", "拔节期追氮（蹲苗后再追）", "田间管理", 0.30, 0.50, false, false),
                new Def("PANICLE_FERT", "抽穗期追肥", "田间管理", 0.50, 0.70, false, false),
                new Def("IRRIGATE", "灌水（谷子耐旱，但灌浆期不能断）", "田间管理", 0.00, 1.00, true, false),
                new Def("PESTICIDE", "植保（谷瘟病/谷子螟）", "田间管理", 0.00, 1.00, true, false),
                new Def("HARVEST", "收获", "收获", 0.92, 1.30, false, false),
                new Def("DRY_GRAIN", "脱粒晾晒", "收获", 0.96, 1.30, false, false)
        );
    }

    private static final Map<String, List<Def>> DEFS_BY_CROP = new HashMap<>();

    private static final List<Def> RICE_DEFS = List.of(
            new Def("PUDDLE", "泡田耙地", "整地移栽", 0.00, 0.06, false, true),
            new Def("BASE_FERT", "施基肥", "整地移栽", 0.00, 0.10, false, true),
            new Def("TRANSPLANT", "插秧", "整地移栽", 0.00, 0.06, false, true),
            new Def("TILLER_FERT", "分蘖肥", "田间管理", 0.16, 0.34, false, false),
            new Def("DRY_FIELD", "够苗晒田", "田间管理", 0.36, 0.44, false, false),
            new Def("PANICLE_FERT", "穗肥", "田间管理", 0.41, 0.55, false, false),
            new Def("IRRIGATE", "灌水", "田间管理", 0.00, 1.00, true, false),
            new Def("PESTICIDE", "植保打药", "田间管理", 0.00, 1.00, true, false),
            new Def("DRAIN_FINAL", "收前排水", "收获", 0.92, 1.05, false, false),
            new Def("HARVEST", "收割", "收获", 0.92, 1.30, false, false),
            new Def("DRY_GRAIN", "烘干入库", "收获", 0.98, 1.30, false, false)
    );

    /** 冬小麦：秋播 → 越冬 → 返青 → 抽穗 → 收。施肥节点与水稻完全不同。 */
    private static final List<Def> WHEAT_DEFS = List.of(
            new Def("BASE_FERT", "施基肥（底肥）", "整地播种", 0.00, 0.10, false, true),
            new Def("TRANSPLANT", "播种", "整地播种", 0.00, 0.06, false, true),
            new Def("TILLER_FERT", "返青肥", "田间管理", 0.42, 0.52, false, false),
            new Def("PANICLE_FERT", "拔节肥", "田间管理", 0.52, 0.63, false, false),
            new Def("IRRIGATE", "灌水", "田间管理", 0.00, 1.00, true, false),
            new Def("PESTICIDE", "一喷三防", "田间管理", 0.00, 1.00, true, false),
            new Def("HARVEST", "收割", "收获", 0.92, 1.30, false, false),
            new Def("DRY_GRAIN", "晾晒入库", "收获", 0.96, 1.30, false, false)
    );

    /** 玉米：大喇叭口期是需肥临界期。 */
    private static final List<Def> CORN_DEFS = List.of(
            new Def("BASE_FERT", "施基肥", "整地播种", 0.00, 0.10, false, true),
            new Def("TRANSPLANT", "播种", "整地播种", 0.00, 0.06, false, true),
            new Def("TILLER_FERT", "苗期提苗肥", "田间管理", 0.15, 0.30, false, false),
            new Def("PANICLE_FERT", "大喇叭口追肥", "田间管理", 0.40, 0.55, false, false),
            new Def("IRRIGATE", "灌水", "田间管理", 0.00, 1.00, true, false),
            new Def("PESTICIDE", "植保（玉米螟/叶斑）", "田间管理", 0.00, 1.00, true, false),
            new Def("HARVEST", "收获", "收获", 0.95, 1.30, false, false),
            new Def("DRY_GRAIN", "晾晒脱粒", "收获", 0.98, 1.30, false, false)
    );

    /** 大豆：少量启动氮，重点是根瘤与控旺。 */
    private static final List<Def> SOYBEAN_DEFS = List.of(
            new Def("BASE_FERT", "施基肥（少量启动氮）", "整地播种", 0.00, 0.10, false, true),
            new Def("TRANSPLANT", "播种", "整地播种", 0.00, 0.06, false, true),
            new Def("TILLER_FERT", "分枝期追肥", "田间管理", 0.15, 0.40, false, false),
            new Def("IRRIGATE", "灌水（花荚期）", "田间管理", 0.00, 1.00, true, false),
            new Def("PESTICIDE", "植保（蚜虫/根腐）", "田间管理", 0.00, 1.00, true, false),
            new Def("HARVEST", "收获", "收获", 0.90, 1.30, false, false),
            new Def("DRY_GRAIN", "晾晒入库", "收获", 0.96, 1.30, false, false)
    );

    static {
        DEFS_BY_CROP.put("rice_mid", RICE_DEFS);
        DEFS_BY_CROP.put("wheat_winter", WHEAT_DEFS);
        DEFS_BY_CROP.put("corn_spring", CORN_DEFS);
        DEFS_BY_CROP.put("soybean", SOYBEAN_DEFS);
        // 园艺作物：按栽培型共用工序链，作物之间的差异体现在参数而不是工序顺序
        List<Def> leafy = leafyDefs();
        List<Def> fruitVeg = fruitVegDefs();
        List<Def> vine = vineDefs();
        List<Def> berry = berryDefs();
        List<Def> flower = flowerDefs();
        DEFS_BY_CROP.put("cabbage", leafy);
        DEFS_BY_CROP.put("tomato", fruitVeg);
        DEFS_BY_CROP.put("chili", fruitVeg);
        DEFS_BY_CROP.put("cucumber", vine);
        DEFS_BY_CROP.put("watermelon", vine);
        DEFS_BY_CROP.put("melon", vine);
        DEFS_BY_CROP.put("strawberry", berry);
        DEFS_BY_CROP.put("chrysanthemum", flower);
        DEFS_BY_CROP.put("sunflower", flower);
        DEFS_BY_CROP.put("tulip", flower);
        // ---------------- 2026-10-24 新增 8 种 ----------------
        List<Def> tuber = tuberDefs();
        List<Def> bean = beanDefs();
        List<Def> tree = treeDefs();
        List<Def> millet = milletDefs();
        DEFS_BY_CROP.put("potato", tuber);
        DEFS_BY_CROP.put("eggplant", fruitVeg);
        DEFS_BY_CROP.put("yardlong_bean", bean);
        DEFS_BY_CROP.put("radish", leafy);
        DEFS_BY_CROP.put("grape", vine);
        DEFS_BY_CROP.put("pear", tree);
        DEFS_BY_CROP.put("rose", flower);
        DEFS_BY_CROP.put("millet", millet);
    }

    private static List<Def> defsOf(String cropId) {
        return DEFS_BY_CROP.getOrDefault(cropId == null ? "rice_mid" : cropId, RICE_DEFS);
    }

    /**
     * 按当前 DVS 判定这次施肥属于哪一道工序。
     *
     * <p>🔴 S12 修复：原来 {@code applyInput} 用硬编码阈值
     * （0.10 / 0.34 / 0.55）来区分基肥 / 分蘖肥 / 穗肥，但各作物的工序窗口并不相同：
     * 小麦返青肥 0.42–0.52、玉米苗期肥 0.15–0.30、瓜类整枝 0.18–0.45。
     * 结果是：小麦在 DVS 0.45 追返青肥，会被硬编码判成「穗肥」，
     * 工序链上找不到对应条目 → 漏工序惩罚触发，产量凭空少 6%。
     *
     * <p>现在改为：按当前作物的工序定义，取"当前 DVS 落在窗口内、且优先级最高"的那道。
     * 找不到匹配窗口时，退回原硬编码逻辑，保证行为不倒退。
     *
     * @return {@code BASE_FERT} / {@code TILLER_FERT} / {@code PANICLE_FERT} / {@code FERTILIZE}
     */
    public static String fertStepFor(double dvs, String cropId) {
        String best = null;
        double bestFrom = -1;
        for (Def d : defsOf(cropId)) {
            if (!"BASE_FERT".equals(d.id()) && !"TILLER_FERT".equals(d.id())
                    && !"PANICLE_FERT".equals(d.id())) {
                continue;
            }
            if (dvs < d.from() || dvs > d.to()) {
                continue;
            }
            // 同一时刻命中多道时取窗口下界最大的（更靠后的工序 = 更高优先级）
            if (d.from() > bestFrom) {
                bestFrom = d.from();
                best = d.id();
            }
        }
        return best;
    }

    /**
     * @param sowDate  播种日
     * @param trace    逐日 DVS（与生长同一口径）
     * @param actions  本季农事
     * @param dvs      当前 DVS
     */
    public static List<Step> build(LocalDate sowDate,
                                   List<Double> trace,
                                   List<FarmAction> actions,
                                   double dvs,
                                   boolean harvestable) {
        return build(sowDate, trace, actions, dvs, harvestable, "rice_mid");
    }

    public static List<Step> build(LocalDate sowDate,
                                   List<Double> trace,
                                   List<FarmAction> actions,
                                   double dvs,
                                   boolean harvestable,
                                   String cropId) {
        Map<String, FarmAction> latest = new HashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Double> quality = new HashMap<>();
        Map<String, Double> dvsAt = new HashMap<>();

        for (FarmAction a : actions) {
            String id = a.getType().name();
            counts.merge(id, 1, Integer::sum);
            // 最后一次（列表按日期升序传入）
            latest.put(id, a);
            long idx = ChronoUnit.DAYS.between(sowDate, a.getDate());
            double atDvs = (idx >= 0 && idx < trace.size()) ? trace.get((int) idx) : dvs;
            dvsAt.put(id, atDvs);
        }

        List<Step> out = new ArrayList<>();
        for (Def d : defsOf(cropId)) {
            boolean done = latest.containsKey(d.id());
            double at = dvsAt.getOrDefault(d.id(), dvs);
            double q = done ? qualityOf(at, d.from(), d.to()) : 0;

            Status st;
            if (d.auto()) {
                st = Status.AUTO;
            } else if (done) {
                // 🔴 S12 修复：已经做过的工序就是 DONE，不该掺入时机判定。
                // 原判定是 `done && (!repeatable || at >= from)`：非重复工序也要求
                // "做的时候 DVS ≥ 窗口下界"。于是玩家在 DVS 0.15 做了分蘖肥
                // （窗口 0.16–0.34），明明做对了，却因为 at(0.15) < from(0.16)
                // 落进 MISSED 分支 → 产量被扣 0.94。
                // "做得早"应该体现在 qualityOf() 的时机质量折扣上（那是它该待的地方），
                // 而不是把"已做"改写成"漏做"。
                st = Status.DONE;
            } else if (d.id().equals("HARVEST")) {
                st = harvestable ? Status.AVAILABLE : (dvs > d.to() ? Status.MISSED : Status.LOCKED);
            } else if (dvs < d.from()) {
                st = Status.LOCKED;
            } else if (dvs <= d.to()) {
                st = Status.AVAILABLE;
            } else {
                st = Status.MISSED;
            }

            String date = latest.containsKey(d.id()) ? latest.get(d.id()).getDate().toString() : null;
            out.add(new Step(d.id(), d.name(), d.group(), d.from(), d.to(), d.repeatable(),
                    st, date, round(q), counts.getOrDefault(d.id(), 0),
                    hintOf(d, st)));
        }
        return out;
    }

    /** 时机质量：窗口中间 60% 记 1.0，边缘 0.75，窗口外 0.5（允许做，但效果打折）。 */
    public static double qualityOf(double dvs, double from, double to) {
        if (dvs < from || dvs > to) {
            return 0.5;
        }
        double mid = (from + to) / 2;
        double half = (to - from) * 0.30;
        return Math.abs(dvs - mid) <= half ? 1.0 : 0.75;
    }

    private static String hintOf(Def d, Status st) {
        String win = String.format("DVS %.2f–%.2f", d.from(), d.to());
        if (d.id().equals("IRRIGATE")) {
            return "缺水就补，花荚期/灌浆期最敏感";
        }
        if (d.id().equals("PESTICIDE")) {
            return "显症前预防效果最好，可重复";
        }
        return switch (st) {
            case AUTO -> "播种作业已带过";
            case DONE -> win;
            case AVAILABLE -> "窗口内：" + win;
            case LOCKED -> "未到窗口：" + win;
            case MISSED -> "窗口已过（" + win + "），补做收益有限";
        };
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
