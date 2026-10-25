package com.learning.springboot.farm.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *
 * <h2>🔴 2026-10-26：定级契约的修复（M5）</h2>
 *
 * <p>本类的注释一直写着「等级由 {@code P_pass} 唯一决定」，
 * 但代码里多了一句 {@code level = Math.max(level, levelFromYieldRatio(median / yRef))}
 * —— 把「中位产量落在哪个产量带」也掺进了定级。
 *
 * <p>实测后果：radish 在江南 {@code pPass = 1.00}（十年年年达标），
 * 却因中位产量只有 71% {@code Y_ref} 被判成 L1「适宜区」。
 * 面板上同时显示「达标率 100%」和「适宜区」，玩家无从理解。
 *
 * <p>两者回答的是不同的问题：
 * <ul>
 *   <li>{@code P_pass} → 「这十年里有多少年能达标」（稳定性 / 风险）；</li>
 *   <li>产量带 → 「中位年能收多少」（收益水平）。</li>
 * </ul>
 *
 * <p>现在<b>产量带只作展示</b>（{@code yieldBandLo/Hi}），不参与定级；
 * 两者差 ≥2 级时在 summary 里说明，但等级仍以回放为准。
 */
public final class SuitabilityEngine {

    private SuitabilityEngine() {
    }

    /** 缺省参照产量（水稻）。实际取 {@link CropConfig#yieldRef()}。 */
    public static final double Y_REF = 600.0;

    /**
     * 作物可耐受的最短无霜期（数据卡缺值时按此给）。
     *
     * <p>🔴 <b>2026-10-24 改造</b>：本方法<b>不能</b>直接 {@code switch (visual)}。
     * visual 的语义已从「通用形态族」变成「作物 id」（见 {@link CropVisual}），
     * 直接 switch 会让所有园艺作物落到 default —— 白菜的 pH 会从
     * {@code 6.0–7.5} 掉回 {@code 5.5–7.0}，评级无声地变差。
     *
     * <p>现在统一走 {@link CropVisual#minFrostFreeFor}：先把 visual 解析成形态族
     * （存量旧值 {@code leafy/vine/bush/flower} 与新作物 id 都能解析），
     * 再取参数。数值口径与改造前<b>逐个一致</b>。
     */
    static int minFrostFree(String visual) {
        return CropVisual.minFrostFreeFor(visual, CropVisual.formOf(visual));
    }

    /**
     * 作物适宜 pH 区间。
     *
     * <p>🔴 <b>2026-10-24 改造</b>：同 {@link #minFrostFree}，
     * 改走 {@link CropVisual#phRangeFor}，理由与取值一致性说明同上。
     */
    static double[] phRange(String visual) {
        return CropVisual.phRangeFor(visual, CropVisual.formOf(visual));
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
    //  种植风险（S23 风险提示，2026-10-22）
    // ==================================================================

    /**
     * 一条风险提示。
     *
     * @param code   AREA / TIMING / STRUCTURE
     * @param level  0（无）~ 5（极高）；用于前端配色与「是否二次确认」
     * @param detail <b>针对性</b>说明：必须带上具体数字（差多少天、差几级），不能是通用套话
     * @param advice 可操作的建议（能干什么），不是「注意风险」
     */
    public record RiskItem(String code, String title, String detail, int level, String advice) {
    }

    /**
     * 种植风险总表：把「选错区」和「选错时机」<b>分开</b>说。
     *
     * <p><b>为什么要分开</b>：真实农业里这是两个完全不同的决策——
     * 「换地」是换区县（改不了气候，只能换作物），「改播期」是同一块地换个日子播。
     * 混成一句「不适区」的话，玩家不知道该换作物还是该改日期。
     *
     * <p><b>数据来源全是回放</b>：
     * <ul>
     *   <li>AREA：拿该作物在四个区、<b>各自推荐播期</b>下的评级，找最优区；</li>
     *   <li>TIMING：同一区、推荐播期 vs 实际播期的评级差 + 播期窗口偏移天数；</li>
     *   <li>STRUCTURE：结构性硬闸（pH / 盐碱 / 土层 / 水源）。</li>
     * </ul>
     *
     * <p>🔴 <b>2026-10-23</b>：本 record 明确区分<b>两个参照系</b>，
     * 两者用途不同、不可混用：
     * <ul>
     *   <li><b>本期</b>（{@code seasonSow} / {@code windowFrom} / {@code windowTo} / {@code offsetDays}）
     *       —— 「距实际播期最近的那次该月日」及其窗口。风险评估的
     *       「早了/晚了 N 天」全部基于它，因为必须<b>同期比较</b>
     *       （拿明年 5/5 比今年 6/15 会得出「晚了 41 天」这种跨年误导）。</li>
     *   <li><b>最近窗口</b>（{@code nearestWindowFrom} / {@code nearestWindowTo} / {@code recommendedSow}）
     *       —— <b>距今最近</b>的那段适合区间，起点永远是今天以后，
     *       供前端展示「什么时候种」与「一键回到推荐播期」。</li>
     * </ul>
     * 混用两者的后果：2026-10-23 修复前，「推荐播期」显示 2027-09-20（已过去），
     * 而世界日期是 2027-11-21 —— 点一下按钮就把日期设到过去。
     *
     * <p>🔴 <b>2026-10-23 再修正</b>（用户反馈「推荐窗口期应该是距今最近的适合日期」）：
     * 「最近窗口」的锚点取「<b>窗口末端还没过</b>」的那次该月日 ——
     * 本期窗口的尾巴还在收尾时就用本期，而不是无脑跳到明年。
     */
    public record PlantingRisk(
            String zoneId,
            String zoneName,
            String cropId,
            String cropName,
            String sowDate,
            int riskLevel,
            String riskLevelName,
            List<RiskItem> items,
            String bestZoneId,
            String bestZoneName,
            int bestZoneLevel,
            String bestZoneLevelName,
            int zoneLevelAtRecommendedSow,
            String recommendedSow,
            String seasonSow,
            String windowFrom,
            String windowTo,
            int offsetDays,
            boolean inWindow,
            String nearestWindowFrom,
            String nearestWindowTo,
            String summary) {
    }

    /** 风险等级中文名。 */
    private static final String[] RISK_NAMES = {
            "无风险", "轻微", "注意", "警戒", "严重", "极高"};

    /**
     * 计算种植风险。
     *
     * @param zoneId  当前农业区
     * @param sow     计划播期
     * @param crop    作物（含分区推荐播期与窗口）
     * @param today   今天（决定推荐播期落今年还是明年；<b>必须是世界日期</b>）
     * @param report  该区该播期的评级报告（由调用方算好，避免重复回放）
     */
    public static PlantingRisk plantingRisk(String zoneId, LocalDate sow, CropCatalog.Crop crop,
                                           LocalDate today, Report report) {
        ClimateModel.Zone zone = ClimateModel.zone(zoneId);
        List<RiskItem> items = new ArrayList<>();

        // 🔴 2026-10-23：**距今最近**的适合日期 —— 一定 ≥ today。
        //
        // 为什么要另算一个：sowWindow 的锚点是「距用户所选播期最近的该月日」，
        // 它服务于**同期比较**（这是风险评估的正确口径）。但玩家在面板上点
        // 「回到推荐播期」需要的是一个**能立刻用的未来日期**——
        // 世界日期 2027-11-21 时，冬小麦东北的同期锚点是 2027-09-20（已过去），
        // 直接把它当推荐值推给前端，点一下就会把日期设到过去。
        //
        // 🔴 2026-10-23 再修正（用户反馈「推荐窗口期应该是距今最近的适合日期」）：
        // 这里不再用 nextSow（该月日的下一次发生），改用 nearestWindow ——
        // 它的锚点是「**窗口末端还没过**」的那次该月日。
        // 区别在大白菜江南：推荐 8/20、窗口 7/21~9/24，世界日期 2028-09-20 时
        // 本期窗口还剩 4 天可选，nextSow 会跳到 2029-08-20（等一年），
        // nearestWindow 则给出 2028-09-20 ~ 2028-09-24（今天就能种）。
        //
        // 所以这里算两套：
        //   · win   = sowWindow 的同期锚点 → 只用于「早了/晚了多少天」的比较
        //   · near  = nearestWindow        → 推给前端，供展示与「一键回到推荐播期」
        CropCatalog.Crop.SowWindow near = crop.nearestWindow(zone.id(), today);
        // 推荐日 = 最近窗口的锚点（锚点已过时 = 今天，即「该赶紧种了」）。
        LocalDate recommended = near.recommended();

        // ---------- 1. 区域风险：与最佳区对比 ----------
        String bestZoneId = null;
        String bestZoneName = null;
        int bestLevel = 99;
        String bestLevelName = null;
        // 各区「在推荐播期下」的评级 —— 它既是最佳区的判据，也是时机风险的基准线。
        // 用 sowWindow 的同期锚点（而非 defaultSow 的未来化结果），保证与用户选的播期同期比较。
        Map<String, Integer> levelByZone = new LinkedHashMap<>();
        for (ClimateModel.Zone z : ClimateModel.zones().values()) {
            LocalDate rec = crop.sowWindow(z.id(), sow, today).recommended();
            Report r = evaluate(z.id(), rec, crop.config());
            levelByZone.put(z.id(), r.level());
            if (r.level() < bestLevel) {
                bestLevel = r.level();
                bestZoneId = z.id();
                bestZoneName = z.name();
                bestLevelName = r.levelName();
            }
        }
        int zoneLevelAtRec = levelByZone.getOrDefault(zone.id(), report.level());
        // 本区实际播期评级 vs 本区推荐播期评级 → 播期本身贡献了多少级差
        int timingCost = report.level() - zoneLevelAtRec;

        if (!zone.id().equals(bestZoneId) && bestLevel < zoneLevelAtRec) {
            int gap = zoneLevelAtRec - bestLevel;
            int level = Math.min(5, 1 + gap);
            items.add(new RiskItem("AREA",
                    "不是这块地的最优选择",
                    String.format("%s在本区算「%s」，但在%s才是「%s」——同一种作物，评级差 %d 级。"
                                    + "换句话说，这里的气候不是这个品种最舒服的地方。",
                            crop.shortName(), LEVEL_NAMES[zoneLevelAtRec], bestZoneName,
                            bestLevelName, gap),
                    level,
                    String.format("要么改种%s更擅长的作物，要么换到%s一带的地块；"
                                    + "硬种也能种，这一季的期望收益会低 %d 个等级。",
                            zone.name(), bestZoneName, gap)));
        } else if (zone.id().equals(bestZoneId)) {
            items.add(new RiskItem("AREA", "这就是最适区",
                    String.format("%s在四个农业区里，%s的条件最好（评级「%s」）。"
                            + "这一季不用担心「地方不对」，只需盯住播期与农事。",
                            crop.shortName(), zone.name(), bestLevelName),
                    0, "放心种，剩下的变量是天气和你自己。"));
        }

        // ---------- 2. 时机风险 ----------
        CropCatalog.Crop.SowWindow win = crop.sowWindow(zone.id(), sow, today);
        int offset = win.offsetDays();
        boolean early = offset < 0;
        // 「早了 / 晚了」必须由符号决定，不能写死——写死过一次，
        // 结果把「晚 41 天」显示成「早了 41 天」，方向完全反了。
        String dirWord = early ? "早了" : "晚了";
        // 🔴 2026-10-23：这里提到日期时**不能**写「推荐 X 播」。
        //
        // win.recommended() 是**本期锚点**（距所选播期最近的那次该月日），
        // 而「下一季推荐播期」是另一个值（recommended）。两者可能差一整年：
        // 世界日期 2027-11-21 时，江南冬小麦本期锚点是 2027-10-25，
        // 下一季推荐是 2028-10-25。
        //
        // 原来写「推荐 2027-10-25 播」，面板顶部却显示「下一季推荐 2028-10-25」，
        // 同一个面板出现两个「推荐」，玩家无从取舍。
        // 现在统一叫「本期适期」，与顶部的「下一季推荐」明确区分。
        //
        // 注意措辞里只出现一次日期 —— 早期版本写成「本期适期 2027-10-25晚了 27 天
        // （2027-10-25，窗口…）」，同一个日期出现两次，读起来磕巴。
        String seasonWord = "本期适期";
        if (!win.inWindow()) {
            int days = Math.abs(offset);
            int level;
            String detail;
            String advice;
            if (timingCost >= 2) {
                level = 5;
                detail = String.format(
                        "播期比%s的%s%s %d 天（%s，窗口 %s ~ %s）。"
                                + "在这个区，这种播期下十年里有 %d 年达不到产量门槛，评级从「%s」掉到「%s」。",
                        zone.name(), seasonWord, dirWord, days, win.recommended(), win.from(), win.to(),
                        (int) Math.round((1 - report.pPass()) * report.samples()),
                        LEVEL_NAMES[zoneLevelAtRec], report.levelName());
                advice = early
                        ? "早播省不了多少天，风险全在「霜前积温不够」——把播期往推荐期挪，或改种生育期更短的品种。"
                        : "晚播直接压缩了灌浆期——往后挪等于跟初霜抢时间。把播期往推荐期挪，或改种早熟品种。";
            } else if (timingCost == 1) {
                level = 3;
                detail = String.format(
                        "播期比%s的%s%s %d 天（%s）。评级从「%s」降到「%s」——多数年份还行，冷年容易出问题。",
                        zone.name(), seasonWord, dirWord, days, win.recommended(),
                        LEVEL_NAMES[zoneLevelAtRec], report.levelName());
                advice = early
                        ? "往推荐期挪 10 天左右就能回到适期，风险主要来自倒春寒。"
                        : "往推荐期挪 10 天左右就能回到适期，风险主要来自秋旱与早霜。";
            } else {
                level = 2;
                detail = String.format(
                        "播期比%s的%s%s %d 天（%s，窗口 %s ~ %s）。"
                                + "评级仍在「%s」，但已经偏离最佳状态。",
                        zone.name(), seasonWord, dirWord, days, win.recommended(), win.from(), win.to(),
                        report.levelName());
                advice = "还没到危险线，但把日期挪回窗口内，这一季的期望会明显好看。";
            }
            items.add(new RiskItem("TIMING",
                    early ? "播得太早" : "播得太晚",
                    detail, level, advice));
        } else if (offset != 0) {
            items.add(new RiskItem("TIMING", "在适期窗口内",
                    String.format("%s是 %s，你选了 %s，%s %d 天——还在适期窗口（%s ~ %s）内，"
                            + "评级「%s」。",
                            seasonWord, win.recommended(), sow, dirWord, Math.abs(offset),
                            win.from(), win.to(), report.levelName()),
                    1, "不用改，窗口之内就是安全区。"));
        }

        // ---------- 3. 结构性风险（硬闸一票否决） ----------
        if (!report.gates().isEmpty()) {
            String gateText = gateText(report.gates(), zone, crop);
            items.add(new RiskItem("STRUCTURE", "这块地本身有硬伤",
                    gateText, 5,
                    "硬闸不是「注意一下」就能过的：这里的结构性条件与这个品种的要求冲突。"
                            + "换作物、换地，或者先改地（改 pH、洗盐、客土、深翻），否则这一季大概率颗粒无收。"));
        }

        // ---------- 4. 汇总 ----------
        int riskLevel = 0;
        for (RiskItem it : items) {
            riskLevel = Math.max(riskLevel, it.level());
        }
        // 硬闸存在时直接拉满
        if (!report.gates().isEmpty()) {
            riskLevel = 5;
        }
        String riskName = RISK_NAMES[Math.max(0, Math.min(5, riskLevel))];

        // ⚠️ 一条都不生成时补「无风险」条目。
        // 否则前端 `items.length` 为 0 → 整个风险区块不渲染 → 玩家看不到任何反馈，
        // 分不清是「评估过没问题」还是「系统根本没算」。
        if (items.isEmpty()) {
            items.add(new RiskItem("OK", "这一季没有明显风险",
                    String.format("%s在%s、正好落在本期适期 %s 上，"
                                    + "评级「%s」——十年回放里 %d 年有 %d 年能达标。"
                                    + "剩下的变量是天气和你自己的农事，不是「地方不对」或「时机不对」。",
                            crop.shortName(), zone.name(), win.recommended(),
                            report.levelName(), report.samples(),
                            (int) Math.round(report.pPass() * report.samples())),
                    0, "按推荐节奏种即可：出苗后追一次关键期肥料，抽穗灌浆期盯紧天气与水肥。"));
        }

        return new PlantingRisk(
                zone.id(), zone.name(), crop.id(), crop.name(), sow.toString(),
                riskLevel, riskName, List.copyOf(items),
                bestZoneId, bestZoneName, bestLevel, bestLevelName,
                zoneLevelAtRec,
                // 🔴 2026-10-23：距今最近的推荐播期（≥ today），供前端「回到推荐播期」用。
                recommended.toString(),
                // 本期适期锚点（同期比较用），窗口围绕它展开。
                win.recommended().toString(), win.from().toString(), win.to().toString(),
                offset, win.inWindow(),
                // 🔴 2026-10-23：距今最近的适合窗口（玩家能选的那段日子）
                near.from().toString(), near.to().toString(),
                riskSummary(crop, zone, report, bestZoneName, bestLevelName, win, riskName,
                        zoneLevelAtRec, recommended));
    }

    /** 硬闸的中文说明（带上具体数值）。 */
    private static String gateText(List<String> gates, ClimateModel.Zone zone, CropCatalog.Crop crop) {
        double[] ph = phRange(crop.config().visual());
        List<String> parts = new ArrayList<>();
        for (String g : gates) {
            switch (g) {
                case "G_PH" -> parts.add(String.format(
                        "土壤 pH %.1f，超出%s适宜的 %.1f–%.1f",
                        zone.ph(), crop.shortName(), ph[0], ph[1]));
                case "G_SALT" -> parts.add(String.format(
                        "盐碱度 EC %.1f dS/m（安全上限 4.0），这是盐碱地", zone.ec()));
                case "G_DEPTH" -> parts.add(String.format(
                        "有效土层只有 %d cm（需要 25 cm 以上），根系扎不下去", zone.depthCm()));
                case "G_WATER" -> parts.add(String.format(
                        "全季水源不足：需水 %.0f mm，这块地只能供上不到 35%%",
                        zone.etcSeasonMm()));
                case "G_ZONE" -> parts.add("该农业区尚未开放");
                default -> parts.add(g);
            }
        }
        return String.join("；", parts) + "。这不是「小心点就行」的问题，是结构上就不成立。";
    }

    /**
     * 总述：一句话说清「这一季最大的问题是什么」。
     *
     * <p>🔴 <b>2026-10-23 修正</b>：原来这里无条件说「播期选得也合适」，
     * 但那是 <b>else 分支</b>——只有在「本区评级 == 推荐播期评级」时才走到。
     * 问题在于它引用的 {@code win.recommended()} 是**同期锚点**（可能早于今天），
     * 玩家会看到「按推荐播期（2027-09-20）种…播期选得也合适」，
     * 而他自己的播期是 2027-11-21 —— 明明晚了 62 天却说「合适」，自相矛盾。
     *
     * <p>现在改用「距今最近推荐播期」（≥ 今天），并在播期确实偏离时如实说明。
     */
    private static String riskSummary(CropCatalog.Crop crop, ClimateModel.Zone zone, Report report,
                                      String bestZoneName, String bestLevelName,
                                      CropCatalog.Crop.SowWindow win, String riskName,
                                      int zoneLevelAtRec, LocalDate recommended) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s在%s的风险等级：%s。", crop.shortName(), zone.name(), riskName));
        if (zoneLevelAtRec > 0 && bestLevelName != null && report.level() > zoneLevelAtRec) {
            // 🔴 2026-10-23：措辞与 TIMING 条目统一 —— 这里的日期是**本期锚点**
            // （同期比较基准），不能叫「推荐播期」（那是另一个参照系的值）。
            sb.append(String.format("按本期适期（%s）种，本区评级「%s」；", win.recommended(),
                    LEVEL_NAMES[zoneLevelAtRec]));
            sb.append(String.format("选在这个播期则变成「%s」。", report.levelName()));
        } else if (zoneLevelAtRec > 0) {
            // 播期偏离时不能说「合适」——那是矛盾的。改成如实说明偏移量。
            if (win.offsetDays() == 0) {
                sb.append(String.format("按本期适期（%s）种，本区评级「%s」，播期选得也合适。",
                        win.recommended(), LEVEL_NAMES[zoneLevelAtRec]));
            } else {
                sb.append(String.format("本区在适期播期（%s ~ %s）下评级「%s」；",
                        win.from(), win.to(), LEVEL_NAMES[zoneLevelAtRec]));
                // 🔴 2026-10-23：措辞改为「最近推荐」—— 它不一定是「下一季」，
                // 也可能是「本期窗口尾巴还没过时的今天」（大白菜江南 2028-09-20 的情形）。
                sb.append(String.format("你的播期%s了 %d 天，最近推荐 %s 播。",
                        win.offsetDays() < 0 ? "早" : "晚", Math.abs(win.offsetDays()), recommended));
            }
        }
        if (bestLevelName != null && report.level() >= 3) {
            sb.append(String.format("最适区在%s（评级「%s」）。", bestZoneName, bestLevelName));
        }
        return sb.toString();
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
        // 🔴 2026-10-26 修复 M7：按播期挑真正的「中位年」（原实现恒取 2021）
        // 🔴 2026-10-26 修复 M6：用**作物基准温度**算积温供给（原实现写死 10℃）
        double aatSupply = ClimateModel.aatSupply(zone.id(),
                medianYearOf(zone.id(), plannedSow), sowDoy, crop);
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
        //
        // 🔴 2026-10-26 修复 M5：等级<b>只由 P_pass 决定</b>（结构性硬闸一票 L5 除外）。
        //
        // 原实现多了一句：
        //     level = Math.max(level, levelFromYieldRatio(median / yRef));
        // 它把「中位产量落在哪个产量带」也掺进了定级，与本类注释
        // 「等级由 P_pass 唯一决定」<b>直接矛盾</b>。
        // 后果（实测）：radish 在江南 pPass = 1.00（十年年年达标），
        //   却因为中位产量只有 71% Y_ref 被判成 L1「适宜区」——
        //   面板上写着「达标率 100%」却不是「最适区」，玩家完全无法理解。
        //
        // 两者回答的是<b>不同的问题</b>：
        //   · P_pass  → 「这十年里有多少年能达标」（稳定性 / 风险）
        //   · 产量带  → 「中位年能收多少」（收益水平）
        // 混在一起会让「等级」既像概率又像收益，自相矛盾。
        //
        // 现在：**产量带只作为展示字段**（yieldBandLo/Hi），不参与定级；
        // 若两者差 ≥2 级，在归因里提示（§4.5 的「不一致告警」），
        // 但仍以回放为准 —— 这正是本类注释「若两者差 ≥2 级，以回放为准」的意思。
        int level = gates.isEmpty() ? levelFromPPass(pPass) : 5;
        // L5 的定义是「结构上不成」，不是「收成差」。若十年里一次都没达标、但中位年
        // 仍有 10% 以上收成（可转青贮），按 S23 §2.2 应落 L4 而不是 L5。
        if (level == 5 && gates.isEmpty() && median >= 0.10 * yRef) {
            level = 4;
        }
        // §4.5 一致性告警：产量带与 P_pass 差 ≥2 级时，以回放为准（等级不变），
        // 但把差异写进 summary，让玩家看到「达标率高但收成一般」这件事本身。
        int yieldLevel = levelFromYieldRatio(median / yRef);
        boolean bandMismatch = Math.abs(yieldLevel - level) >= 2;
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
        // 🔴 M5：产量带与 P_pass 定级差 ≥2 级时，把差异说清楚（等级仍以回放为准）
        if (bandMismatch) {
            summary = summary + String.format(
                    " 注意：达标率与中位年收成不在同一档（收成落在「%s」档），"
                            + "说明这十年里多能达标，但收成水平一般。",
                    LEVEL_NAMES[yieldLevel]);
        }

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
            // 🔴 2026-10-26 修复 M6：用作物自身的积温基准（原实现写死 10℃）
            double aatAtFrost = ClimateModel.aatSupply(zoneId, year, idx + 1, crop);
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

    /**
     * 取气候<b>中位年</b>的年份。
     *
     * <p>🔴 2026-10-26 修复 M7：原实现接收 {@code sow} 形参却<b>完全没用它</b>，
     * 恒返回 {@code firstYear() + years()/2} = 2016 + 5 = <b>2021</b>。
     *
     * <p>为什么这不只是「未使用的形参」：这个年份被用来取 AAT 供给（{@code aatSupply}），
     * 而 10 年回放（{@code replayOne}）是<b>逐年</b>跑的。
     * 用一个固定的 2021 年代表全部年份，等于让「热量供给归因」永远只看一年 ——
     * 而 2021 恰好是 10 年里偏暖的一年，于是：
     * <ul>
     *   <li>暖年/冷年的差异被抹平 → F1「热量不足」的归因失真；</li>
     *   <li>播期落在年末时，取 2021 年的 12 月之后就没有数据可用了。</li>
     * </ul>
     *
     * <p>现在的做法：按<b>该播期</b>在 10 年里的热量供给挑「中位年」——
     * 即供给量最接近中位数的那一年。这样「中位年」名副其实，
     * 且对跨年播期仍然成立。
     *
     * @param zoneId 目标农业区
     * @param sow    计划播期（决定用哪一年的气候）
     * @return 10 年区间内的代表年份
     */
    private static int medianYearOf(String zoneId, LocalDate sow) {
        int first = ClimateModel.firstYear();
        int n = ClimateModel.years();
        if (sow == null || zoneId == null) {
            return first + n / 2;
        }
        int sowDoy = sow.getDayOfYear();
        double[] supplies = new double[n];
        for (int i = 0; i < n; i++) {
            supplies[i] = ClimateModel.aatSupply(zoneId, first + i, sowDoy);
        }
        double[] sorted = supplies.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[n / 2];
        int bestIdx = 0;
        double bestDiff = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double diff = Math.abs(supplies[i] - median);
            if (diff < bestDiff) {
                bestDiff = diff;
                bestIdx = i;
            }
        }
        return first + bestIdx;
    }

    /**
     * 生长季降水（mm）：从播期起 150 天内的累计降水。
     *
     * <p>🔴 2026-10-26 修复 M4：原实现只取<b>当年</b>的序列，
     * 于是<b>跨年播期被截断</b>：
     * <pre>
     *   12/25 播种 → end = min(当年天数, doy-1+150) = min(365, 334+150) = 365
     *              → 只累加了 12/25–12/31 共 7 天的降水
     *              → 少算 288–585mm → waterSupply 被严重低估
     *              → 「水分不足」闸门误判 → 评级凭空降级
     * </pre>
     *
     * <p>正确做法：跨年时把<b>次年</b>的序列接上。
     * （越冬作物冬小麦本来就跨年，这个 bug 对它影响最直接。）
     */
    private static double seasonPrecip(String zoneId, int year, int sowDoy) {
        List<DayWeather> s = ClimateModel.series(zoneId, year);
        double sum = 0;
        int end = sowDoy - 1 + 150;
        int limit = Math.min(s.size(), end);
        for (int i = Math.max(0, sowDoy - 1); i < limit; i++) {
            sum += s.get(i).precip();
        }
        // 跨年：接上一年
        if (end > s.size()) {
            List<DayWeather> next = ClimateModel.series(zoneId, year + 1);
            int need = end - s.size();
            for (int i = 0; i < Math.min(next.size(), need); i++) {
                sum += next.get(i).precip();
            }
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
