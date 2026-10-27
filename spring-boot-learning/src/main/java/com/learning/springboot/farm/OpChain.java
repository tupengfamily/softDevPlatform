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

    /**
     * 工序定义。
     *
     * <p>🔴 2026-10-27：可见性从 {@code private} 放宽到包级 ——
     * {@code HarvestThresholdTest} 需要直接核对
     * 「收割阈值 == 该作物 HARVEST 步骤下界」，这是「唯一真源」的核心断言。
     * 不放宽到 public，避免把内部结构暴露成对外 API。
     */
    record Def(String id, String name, String group, double from, double to,
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

    /**
     * 该作物的工序定义列表。
     *
     * <p>🔴 2026-10-27：可见性从 {@code private} 放宽到包级 ——
     * {@code HarvestThresholdTest} 需要核对收割阈值与 HARVEST 步骤同源。
     */
    static List<Def> defsOf(String cropId) {
        return DEFS_BY_CROP.getOrDefault(cropId == null ? "rice_mid" : cropId, RICE_DEFS);
    }

    /**
     * 🔴 2026-10-27：<b>收割阈值的唯一出口</b>（按作物取）。
     *
     * <p><b>被修的缺陷</b>：{@code FarmPlayService} 里有一个写死的
     * {@code HARVEST_DVS = 0.92}，被 <b>9 处</b>引用。0.92 是<b>水稻</b>的口径，
     * 而各作物的收割阈值本来就不同：
     * <pre>
     *   番茄 0.82 · 大白菜 0.85 · 瓜类 0.88 · 梨/马铃薯/谷子 0.90
     *   大豆 0.90 · 水稻 0.92 · 小麦 0.92 · 玉米 0.95
     * </pre>
     * 写死 0.92 的后果：
     * <ul>
     *   <li><b>番茄（0.82）</b>：DVS 0.82~0.92 之间本该可收割，却被判「还没到黄熟期」 ——
     *       玩家明明看到番茄红了却收不了，被逼着白等十天；</li>
     *   <li><b>玉米（0.95）</b>：DVS 0.92~0.95 之间被判「可以收割」，
     *       但此时玉米尚未完熟 → 玩家收了一茬<b>不熟的玉米</b>，产量凭空打折，
     *       而提示却说「已到黄熟期」—— 纯误导。</li>
     * </ul>
     * 也就是说：**21 种作物里，除了水稻和小麦，全都判错**，
     * 而且是往两个相反方向错。
     *
     * <p><b>阈值怎么算</b>：取两个口径的<b>较大者</b> ——
     * <ol>
     *   <li>本作物工序链里 {@code HARVEST} 步骤的窗口下界（农事窗口）；</li>
     *   <li>本作物物候段里<b>成熟段</b>的起点（生物学上什么时候才算熟）。</li>
     * </ol>
     *
     * <p>🔴 <b>为什么必须取较大者（2026-10-27 实测发现）</b>：
     * 园艺作物是<b>按栽培型共用工序链</b>的（茄果类共用一条、豆角共用一条…），
     * 但每种作物的物候切点不同，于是出现了 6 种作物的「工序窗口早于成熟段」：
     * <pre>
     *   辣椒/茄子  工序 0.82  vs  成熟段 0.85
     *   豆角/萝卜  工序 0.85  vs  成熟段 0.88
     *   葡萄/向日葵 工序 0.88  vs  成熟段 0.90
     * </pre>
     * 若只看工序口径，玩家在 DVS 0.83 收辣椒 —— 但此时辣椒的物候阶段还写着
     * 「果实膨大」，界面同时显示「果实膨大」和「可收割」，<b>自相矛盾</b>。
     * 生物学上没熟的作物不该能收，所以取两者较大值。
     *
     * <p>取较大者后，实际阈值：
     * <pre>
     *   番茄 0.82 · 大白菜 0.85 · 辣椒/茄子 0.85 · 瓜类 0.88 · 豆角/萝卜 0.88
     *   梨/马铃薯/谷子/大豆 0.90 · 葡萄/向日葵 0.90 · 水稻/小麦 0.92 · 玉米 0.95
     * </pre>
     *
     * @param cropId 作物 id；null / 未知 → 回落到水稻口径（0.92）
     * @return 该作物的收割 DVS 阈值
     */
    public static double harvestDvsOf(String cropId) {
        double opThreshold = opHarvestFrom(cropId);
        double ripeFrom = phenologyRipeFrom(cropId);
        // 两者都没有 → 回落到水稻口径
        if (opThreshold < 0 && ripeFrom < 0) {
            return 0.92;
        }
        // 只知道一个 → 用它
        if (opThreshold < 0) {
            return ripeFrom;
        }
        if (ripeFrom < 0) {
            return opThreshold;
        }
        // 两个都有 → 取较大者（生物学成熟才算可收）
        return Math.max(opThreshold, ripeFrom);
    }

    /**
     * 🔴 2026-10-27：<b>按作物查某道工序的窗口下界</b>（通用出口）。
     *
     * <p><b>被修的缺陷</b>：{@code FarmPlayService.validateActionWindow()} 里
     * {@code DRY_GRAIN} 写死 {@code dvs >= 0.92}、{@code DRAIN_FINAL} 写死
     * {@code dvs >= 0.90} —— 但各作物工序链里的这两个窗口<b>本来就不同</b>：
     * <pre>
     *   DRY_GRAIN：番茄 0.88 · 大白菜 0.88 · 瓜类 0.92 · 葡萄 0.92 · 向日葵 0.92
     *              马铃薯 0.94 · 梨 0.94 · 豆角 0.90 · 萝卜 0.90
     *              水稻 0.98 · 小麦 0.96 · 玉米 0.98 · 谷子 0.96 · 大豆 0.96
     * </pre>
     * 写死 0.92 的后果：<b>番茄在 DVS 0.90 就被允许「分级包装」</b>
     * （它自己的窗口 0.88 之前就该可以了，0.92 反而拦早了），
     * 而<b>水稻在 DVS 0.94 做「烘干入库」会被拒</b>（它的窗口是 0.98）——
     * 玩家在正确时机做的正确事被拒绝，这是比阈值偏高更糟的失败。
     *
     * <p><b>为什么不能直接用「收割阈值」代替</b>：收割阈值是
     * {@code max(工序 HARVEST 下界, 物候成熟段起点)}，
     * 而 {@code DRY_GRAIN} 是「收割<b>之后</b>的下一道工序」，两者语义不同、
     * 数值也不同（多数作物 DRY_GRAIN &gt; HARVEST）。
     *
     * @param cropId 作物 id；null / 未知 → 回落到水稻口径
     * @param stepId  工序 id（如 {@code "DRY_GRAIN"} / {@code "DRAIN_FINAL"}）
     * @return 窗口下界；该作物没有这道工序时返回 -1
     */
    public static double stepFromOf(String cropId, String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return -1.0;
        }
        for (Def d : defsOf(cropId)) {
            if (stepId.equals(d.id())) {
                return d.from();
            }
        }
        return -1.0;
    }

    /**
     * 🔴 2026-10-27：<b>按作物查某道工序的窗口上界</b>。
     *
     * @see #stepFromOf(String, String)
     * @return 窗口上界；该作物没有这道工序时返回 -1
     */
    public static double stepToOf(String cropId, String stepId) {
        if (stepId == null || stepId.isBlank()) {
            return -1.0;
        }
        for (Def d : defsOf(cropId)) {
            if (stepId.equals(d.id())) {
                return d.to();
            }
        }
        return -1.0;
    }

    /**
     * 工序链里 {@code HARVEST} 步骤的窗口下界（找不到返回 -1）。
     *
     * <p>这是「农事窗口」口径 —— 农事上从哪一刻开始可以安排收获作业。
     */
    private static double opHarvestFrom(String cropId) {
        for (Def d : defsOf(cropId)) {
            if ("HARVEST".equals(d.id())) {
                return d.from();
            }
        }
        // 理论上每条工序链都有 HARVEST（defsOf 找不到时回落到 RICE_DEFS）
        return RICE_DEFS.stream()
                .filter(d -> "HARVEST".equals(d.id()))
                .mapToDouble(Def::from)
                .findFirst()
                .orElse(-1.0);
    }

    /**
     * 该作物物候段里<b>成熟段</b>的起点（找不到返回 -1）。
     *
     * <p>「成熟段」= 物候定义里<b>最后一段</b>（每种作物的最后一段都是成熟/采收期，
     * 由 {@code PhenologyIndexTest} 守着「末段 hi &gt; 1.0」）。
     *
     * <p>这是「生物学成熟」口径 —— 作物在物候上真正熟透的时刻。
     */
    private static double phenologyRipeFrom(String cropId) {
        try {
            com.learning.springboot.farm.engine.CropCatalog.Crop c =
                    com.learning.springboot.farm.engine.CropCatalog.get(cropId);
            if (c == null || c.config() == null || c.config().phenology() == null) {
                return -1.0;
            }
            List<com.learning.springboot.farm.engine.CropConfig.PhenoStage> stages =
                    c.config().phenology();
            if (stages.isEmpty()) {
                return -1.0;
            }
            return stages.get(stages.size() - 1).lo();
        } catch (RuntimeException e) {
            // 作物 id 不认识 → CropCatalog.get 会回落到水稻；这里不该炸
            return -1.0;
        }
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
