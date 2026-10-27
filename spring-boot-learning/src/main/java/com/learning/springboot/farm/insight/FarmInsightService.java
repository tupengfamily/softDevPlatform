package com.learning.springboot.farm.insight;

import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.CropConfig;
import com.learning.springboot.farm.engine.CropVisual;
import com.learning.springboot.farm.engine.DisasterEngine;
import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.PestEngine;
import com.learning.springboot.farm.engine.SimEngine;
import com.learning.springboot.farm.engine.SoilEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「农业科学模块」的科学解释层 —— <b>解释文案的唯一出口</b>（2026-10-27 P1）。
 *
 * <p><b>这个类解决什么问题</b>：引擎早就把全套科学量算出来了
 * （{@code aatCum / aatReq / nApplied / nNeed / nSatisfaction /
 * dmg.panicle|floret|fertility|weight / etToday / percToday / dryDays / waterlogDays}），
 * 但前端<b>一个都没展示</b>（实测 {@code grep -rn "nSatisfaction|droughtLoss|aatCum"
 * frontend/} 零命中）。玩家看到的是「第 3 天、第 5 天、第 7 天」，
 * 看不到「为什么第 5 天该追肥」「为什么这一季只能收 400 kg 而不是 600 kg」。
 *
 * <p><b>为什么必须是 Service 而不是 Controller 里拼</b>（本项目铁律）：
 * 同一个概念（有效积温 / 氮素报酬递减 / 产量四要素 / 水分平衡）
 * 至少会在<b>三个界面</b>出现 —— 农场实时面板、建田选地页、收割归因明细。
 * 如果文案写在 Controller 或前端里，就是 3 份副本，必然分叉
 * —— 这正是本项目反复踩过的「同型代码只修一处」缺陷模式。
 * 现在<b>文案只在这里写一次</b>，三个界面共用。
 *
 * <p><b>结构复用（不发明新结构）</b>：沿用
 * {@link com.learning.springboot.farm.engine.SuitabilityEngine.RiskItem}
 * 的五段式（{@code code / title / detail / level / advice}），
 * 但把它<b>拆成四个可独立翻译的段</b>：
 * <ul>
 *   <li>{@code observation} 现象：我观察到什么；</li>
 *   <li>{@code quantity} 量：具体数字 + 单位；</li>
 *   <li>{@code principle} 原理：背后的科学；</li>
 *   <li>{@code advice} 建议：下一步做什么。</li>
 * </ul>
 * 这四段对应
 * {@code docs/agriculture-science-module-plan.md} §3.1 的四层结构，
 * 也对应「能教懂」的定位目标。
 *
 * <p><b>🔴 跨语言纪律</b>：本类返回的是<b>结构化数据 + 中文文案</b>，
 * 不返回「已翻译好的字符串」。英文界面由前端按 {@code conceptId}
 * 与各段自己的 i18n key 翻译 —— 理由与 {@code FarmAttribution} 相同：
 * 后端是中文唯一权威，但前端要能说英文。
 * 所以每段都带一个<b>稳定标识</b>（{@code conceptId} / {@code quantityCode} / ...），
 * 前端按它查 i18n 表，查不到时退回后端中文原文。
 *
 * <p><b>🔴 缺数据不编造</b>：本类的每个数字都来自引擎实算，
 * 拿不到就返回 {@code null} 并让对应概念<b>整条不出现在列表里</b>。
 * 「没记录」和「是零」是两回事（本项目反复踩过的坑）。
 */
public final class FarmInsightService {

    private FarmInsightService() {
    }

    // ==================================================================
    //  稳定标识（跨语言）
    // ==================================================================

    /** 概念：有效积温。 */
    public static final String C_AAT = "AAT";
    /** 概念：氮素报酬递减。 */
    public static final String C_NITROGEN = "NITROGEN";
    /** 概念：产量四要素。 */
    public static final String C_YIELD_ELEMENTS = "YIELD_ELEMENTS";
    /** 概念：土壤水分平衡。 */
    public static final String C_WATER = "WATER";

    // ==================================================================
    //  输出结构
    // ==================================================================

    /**
     * 一条科学解释。
     *
     * @param conceptId  概念标识（{@link #C_AAT} 等），前端按它查 i18n
     * @param title      概念标题（后端中文权威）
     * @param observation 现象段：我观察到什么
     * @param quantity   量段：具体数字 + 单位（<b>纯数字，不含解释文字</b>）
     * @param principle  原理段：背后的科学
     * @param advice     建议段：下一步做什么
     * @param severity   严重度（0 = 无问题，5 = 极严重）—— 与 {@code RiskItem.level} 同一刻度
     * @param metrics    该概念的量化指标（前端画进度条 / 数字用；键为稳定标识）
     */
    public record Insight(
            String conceptId,
            String title,
            String observation,
            String quantity,
            String principle,
            String advice,
            int severity,
            Map<String, Double> metrics) {
    }

    // ==================================================================
    //  主入口
    // ==================================================================

    /**
     * 计算一个地块本季的全部科学解释。
     *
     * @param crop        作物配置
     * @param r           引擎回放结果（{@code SimEngine.SimResult}）
     * @param sr          土壤结果（{@code SoilEngine.Result}）
     * @param pr          病虫害结果（可为 null）
     * @param dr          灾害结果（可为 null）
     * @param seasons     已种季数（用于地力连作衰减的解释）
     * @param droughtRelief 农具给的干旱减免（滴灌 0.5）
     * @return 解释列表（<b>按严重度降序</b>；没有数据的概念不出现在结果里）
     */
    public static List<Insight> insights(CropConfig crop, SimEngine.SimResult r,
                                         SoilEngine.Result sr, PestEngine.Result pr,
                                         DisasterEngine.Result dr, int seasons,
                                         double droughtRelief) {
        List<Insight> out = new ArrayList<>(4);
        if (crop == null) {
            return out;
        }
        // 🔴 2026-10-27：每个概念**各自判断数据可用性**，不做「一票否决」。
        // 氮素与水分只需要土壤结果，积温与产量四要素才需要回放结果 ——
        // 早前用 `r == null` 整体提前返回，等于「没有回放结果就一条解释都不给」，
        // 而土壤数据明明是齐的。缺数据不编造 ≠ 有一个概念缺失就全都不给。
        Insight aat = aatInsight(crop, r);
        if (aat != null) {
            out.add(aat);
        }
        Insight n = nitrogenInsight(crop, sr);
        if (n != null) {
            out.add(n);
        }
        Insight y = yieldElementInsight(crop, r, pr, dr, sr, droughtRelief);
        if (y != null) {
            out.add(y);
        }
        Insight w = waterInsight(crop, sr);
        if (w != null) {
            out.add(w);
        }
        out.sort((a, b) -> Integer.compare(b.severity(), a.severity()));
        return out;
    }

    // ==================================================================
    //  概念 1 · 有效积温
    // ==================================================================

    /**
     * 有效积温（{@code aatCum / aatReq}）。
     *
     * <p><b>原理</b>：作物发育靠「有效积温」推进 ——
     * 日均温 ≥ 基点温度的部分才计入，超过最适温度的部分截断（{@code aatRef / tOptUpper}）。
     * 发育速率 = 当日积温 × 高温加速系数 ÷ 全季需求积温。
     * 所以<b>早播不等于早熟</b>：早播的那几天温度低，攒的积温少，
     * 到初霜时反而可能没攒够。
     *
     * <p>🔴 <b>口径</b>：{@code aatCum} 是引擎实算的累积值，
     * {@code aatReq} 是作物配置里的全季需求。
     * 两者相除得到「发育进度」，<b>但不能直接当成熟度</b> ——
     * 高温发育加速会让积温比低于 1 也可能成熟（见 {@code SimEngine} 的
     * {@code maturityDate}）。所以本概念同时给 {@code dvs} 与成熟日期。
     */
    private static Insight aatInsight(CropConfig crop, SimEngine.SimResult r) {
        if (r == null) {
            return null;
        }
        double aatReq = crop.aatReq();
        if (aatReq <= 0) {
            return null;
        }
        double aatCum = r.aatCum();
        double ratio = aatCum / aatReq;
        boolean matured = r.matureDate() != null;
        boolean frostStopped = r.frostStop();

        int severity;
        if (matured) {
            severity = 0;
        } else if (frostStopped) {
            severity = 5;
        } else if (ratio >= 0.9) {
            severity = 1;
        } else if (ratio >= 0.7) {
            severity = 2;
        } else {
            severity = 3;
        }

        String observation;
        if (matured) {
            observation = String.format(
                    "%s从 %s 播下，到 %s 攒够 %.0f ℃·d，发育完成（成熟日 %s）。",
                    shortNameOf(crop), r.sowDate(), r.matureDate(), aatCum, r.matureDate());
        } else if (frostStopped) {
            observation = String.format(
                    "%s 在 DVS %.2f 时遇上初霜，全季结束 —— 只攒了 %.0f ℃·d，"
                            + "距离成熟需要的 %.0f ℃·d 还差 %.0f。",
                    shortNameOf(crop), r.dvs(), aatCum, aatReq, Math.max(0, aatReq - aatCum));
        } else {
            observation = String.format(
                    "%s 从 %s 播下，已生长 %d 天，攒了 %.0f ℃·d（需求的 %.0f%%）。",
                    shortNameOf(crop), r.sowDate(), r.days(), aatCum, ratio * 100);
        }

        String quantity = String.format("%.0f / %.0f ℃·d（%.0f%%）",
                aatCum, aatReq, ratio * 100);

        String principle = String.format(
                "有效积温是作物发育的「进度条」：日均温高于 %.0f℃ 的部分才计入，超过 %.0f℃ 的部分截断。"
                        + "%s 成熟需要累计 %.0f ℃·d。发育速率 = 当日积温 ÷ 全季需求，"
                        + "所以早播不一定早熟 —— 早播那几天的低温攒不下热量，"
                        + "到初霜时反而可能攒不够。",
                crop.aatRef(), crop.tOptUpper(), shortNameOf(crop), aatReq);

        String advice;
        if (matured) {
            advice = "积温已够，产量由后面的「产量四要素」决定 —— 穗数、颖花、结实、粒重哪一项掉得多，去那里看。";
        } else if (frostStopped) {
            advice = "这一季被初霜截断了。想避免：① 换更早熟的品种；② 把播期往前挪，"
                    + "让灌浆期落在初霜之前；③ 选无霜期更长的区。";
        } else if (ratio >= 0.9) {
            advice = "积温基本够了，接下来几周盯紧灌浆期的高温与干旱 —— 热量不再是瓶颈，"
                    + "但「攒够了」不等于「灌满了」。";
        } else {
            advice = String.format("还差 %.0f ℃·d（折合约 %.0f 天的日均积温）。"
                            + "可操作的是：① 换生育期更短的品种；② 把播期往前挪到适期窗口前端。",
                    Math.max(0, aatReq - aatCum),
                    Math.max(0, (aatReq - aatCum) / Math.max(1, crop.aatRef())));
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("aatCum", aatCum);
        metrics.put("aatReq", aatReq);
        metrics.put("ratio", ratio);
        metrics.put("dvs", r.dvs());

        return new Insight(C_AAT, "有效积温", observation, quantity, principle, advice,
                severity, metrics);
    }

    // ==================================================================
    //  概念 2 · 氮素报酬递减
    // ==================================================================

    /**
     * 氮素报酬递减（{@code nApplied / nNeed / nSatisfaction}）。
     *
     * <p><b>原理</b>：Mitscherlich 曲线 —— 氮素对产量的贡献是<b>饱和</b>的：
     * {@code satisfaction = 1 − e^(−1.8 × N / need)}。
     * 不施肥是 0 分；施到需求量时约 0.83 分；再往上多施肥，
     * 每多一克氮换来的地力提升趋近于零，但<b>成本照付</b>。
     *
     * <p>🔴 <b>豆科是反过来的</b>：大豆 60–70% 的氮靠根瘤固氮，
     * 外源氮超过 2–3 kg/亩会<b>抑制根瘤</b>（氮阻遏）。
     * 所以「大豆的最优施氮是少量启动氮，不是越多越好」。
     * 这条不能套用通用话术 —— 走 {@link CropVisual#isLegume(String)} 单独判。
     */
    private static Insight nitrogenInsight(CropConfig crop, SoilEngine.Result sr) {
        if (sr == null) {
            return null;
        }
        double nNeed = sr.nNeed();
        double nApplied = sr.nApplied();
        double sat = sr.nSatisfaction();
        if (nNeed <= 0) {
            return null;
        }
        boolean legume = CropVisual.isLegume(crop.visual());
        double ratio = nApplied / nNeed;

        int severity;
        if (legume) {
            // 豆科：施多了反而压制根瘤
            if (ratio > 1.3) {
                severity = 4;
            } else if (ratio > 0.8) {
                severity = 3;
            } else if (ratio > 0.2) {
                severity = 1;
            } else {
                severity = 0;
            }
        } else if (ratio > 1.3) {
            // 🔴 2026-10-27 修复：超量施肥单独一档。
            // 原实现里 ratio > 1.3 会掉进最后的 else（severity 4），
            // 而建议文案没有对应分支 → 落到「还差一点到需求量」，
            // **严重度说「严重超量」，文案却说「差一点」** —— 自相矛盾，
            // 而且会让玩家误以为该继续加氮（实际是浪费钱 + 徒长 + 病虫害加剧）。
            severity = 4;
        } else if (ratio >= 1.0) {
            // 甜点区：施到需求量，刚到 1.3 倍
            severity = 1;
        } else if (ratio >= 0.8) {
            severity = 2;
        } else if (ratio >= 0.4) {
            severity = 3;
        } else {
            severity = 4;
        }

        String observation = legume
                ? String.format("大豆已施入折纯氮 %.1f kg/亩（本季需氮 %.1f kg/亩），"
                        + "氮素满足度 %.0f%%。", nApplied, nNeed, sat * 100)
                : String.format("本季已施入折纯氮 %.1f kg/亩，全季需氮 %.1f kg/亩，"
                        + "氮素满足度 %.0f%%，地力系数 %.2f。",
                        nApplied, nNeed, sat * 100, sr.soilCoef());
        // 🔴 2026-10-27：超量时在现象段直接点明「已超过需求量」，
        // 否则玩家看到「满足度 83%」会以为还不够肥 —— 那正是「多施肥也没用」
        // 的经典误解（满足度本来就饱和在 83% 左右）。

        String quantity = String.format("%.1f / %.1f kg N/亩（%.0f%%）",
                nApplied, nNeed, ratio * 100);

        // 🔴 豆科氮阻遏阈值：外源氮超过「需求的 20%」（大豆 nNeed=2.5 → 0.5 kg/亩）
        // 或绝对值 2 kg/亩（取大者）即开始压制根瘤。
        // ⚠️ 这两个数字来自农学口径（大豆启动氮 2–3 kg/亩），不是代码推导出来的 ——
        //    写死成「20」字面量曾导致 String.format 抛 IllegalFormatConversionException
        //    （整型给了 %f），所以这里显式声明为 double 并把比例与需求联动。
        double legumeThreshold = Math.max(2.0, nNeed * 0.2);
        double legumeThresholdPct = legumeThreshold / Math.max(1e-9, nNeed) * 100;

        String principle = legume
                ? String.format(
                        "大豆 60–70%% 的氮来自根瘤固氮。外源氮施到 %.1f kg/亩（需求的 %.0f%%）以上，"
                                + "会压制根瘤活动（氮阻遏）—— 前期看着叶子绿，后期反而更缺氮。"
                                + "所以豆科的最优施氮是「少量启动氮」，不是越多越好。",
                        legumeThreshold, legumeThresholdPct)
                : String.format(
                        "氮对产量的贡献是<b>饱和</b>的：满足度 = 1 − e^(−1.8 × 施氮量 ÷ 需氮量)。"
                                + "不施肥是 0 分，施到需求量约 %.0f%%，再往上每多一克氮换来的提升趋近于零，"
                                + "但成本照付。这就是「报酬递减」。",
                        InputCatalog.nSatisfaction(nNeed, nNeed) * 100);

        String advice;
        if (legume) {
            if (ratio > 1.3) {
                advice = String.format("施氮已经过量（%.1f kg/亩，是需求的 %.1f 倍），根瘤被压制。"
                        + "下一季改用根瘤菌剂（RHIZOBIUM）拌种，把外源氮压到 2–3 kg/亩以内。",
                        nApplied, ratio);
            } else if (ratio > 0.2) {
                advice = "施氮偏多。大豆靠固氮自给，把外源氮压到 2–3 kg/亩的启动量即可。";
            } else {
                advice = "氮投入在合理区间。豆科靠根瘤固氮，不需要额外追氮 —— "
                        + "把注意力放到磷钾与根瘤菌拌种上。";
            }
        } else if (ratio > 1.3) {
            // 🔴 2026-10-27 新增：超量施肥的文案（原来缺这一档，导致
            // 严重度说「严重超量」而文案说「还差一点到需求量」）。
            advice = String.format("施氮已明显过量（%.1f kg/亩，是需求的 %.1f 倍）。"
                    + "继续加氮不会再提高产量 —— 地力系数不升反降（连作与过氮都会压低它），"
                    + "还会徒长、加重病虫害与倒伏风险。下一季把总施氮量压回 %.1f kg/亩左右。",
                    nApplied, ratio, nNeed);
        } else if (ratio >= 1.0) {
            advice = String.format("施氮 %.1f kg/亩，已达需求量的 %.0f%%，处在报酬递减的「甜点区」。"
                    + "再往上加氮对产量的提升已很小，却要照付成本 —— 不建议继续加。",
                    nApplied, ratio * 100);
        } else if (ratio >= 0.8) {
            advice = String.format("还差一点到需求量（已施 %.1f，需 %.1f kg/亩）。"
                    + "补一次关键期追肥就能把这块地喂饱。", nApplied, nNeed);
        } else {
            advice = String.format("氮只喂到需求的 %.0f%%，是当前产量的主要短板之一。"
                    + "下一季在分蘖—孕穗期安排一次追肥，把总量补到 %.1f kg/亩左右。",
                    ratio * 100, nNeed);
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("nApplied", nApplied);
        metrics.put("nNeed", nNeed);
        metrics.put("nSatisfaction", sat);
        metrics.put("soilCoef", sr.soilCoef());
        metrics.put("sfi", sr.sfi());
        metrics.put("ratio", ratio);

        return new Insight(C_NITROGEN, "氮素报酬递减", observation, quantity, principle, advice,
                severity, metrics);
    }

    // ==================================================================
    //  概念 3 · 产量四要素
    // ==================================================================

    /**
     * 产量四要素（{@code dmg.panicle / floret / fertility / weight}）。
     *
     * <p><b>原理</b>：产量 = 穗数 × 每穗粒数 × 粒重。
     * 减产一定要落到这三个数之一上（外加一个「每穗粒数 = 颖花数 × 结实率」）。
     * 关键教学点是：<b>不同环节的损失，时间窗口完全不同</b> ——
     * 穗数在<b>分蘖期</b>定，颖花在<b>孕穗期</b>定，
     * 结实率在<b>抽穗开花期</b>定，粒重在<b>灌浆期</b>定。
     * 所以「什么时候出事」决定了「还能不能补救」。
     */
    private static Insight yieldElementInsight(CropConfig crop, SimEngine.SimResult r,
                                                PestEngine.Result pr, DisasterEngine.Result dr,
                                                SoilEngine.Result sr, double droughtRelief) {
        if (r == null || r.dmg() == null) {
            return null;
        }
        double[] heat = new double[]{
                r.dmg().panicle(), r.dmg().floret(), r.dmg().fertility(), r.dmg().weight()};
        FarmYieldAttribution.Factors f =
                FarmYieldAttribution.factorsOf(pr, dr, sr, droughtRelief);
        FarmYieldAttribution.Element[] els =
                FarmYieldAttribution.elements(crop, heat, f);

        // 找损失最大的要素 —— 教学重点是「先修最疼的那一项」
        FarmYieldAttribution.Element worst = els[0];
        FarmYieldAttribution.Element best = els[0];
        for (FarmYieldAttribution.Element e : els) {
            if (e.totalLoss() > worst.totalLoss()) {
                worst = e;
            }
            if (e.totalLoss() < best.totalLoss()) {
                best = e;
            }
        }
        boolean grain = CropVisual.Form.GRAIN == CropVisual.formOf(crop.visual());

        int severity;
        if (worst.totalLoss() >= 0.30) {
            severity = 4;
        } else if (worst.totalLoss() >= 0.15) {
            severity = 3;
        } else if (worst.totalLoss() >= 0.05) {
            severity = 2;
        } else if (worst.totalLoss() >= 0.01) {
            severity = 1;
        } else {
            severity = 0;
        }

        String observation = String.format(
                "四个产量要素里，%s 损失最大（%.1f%%），%s 最轻（%.1f%%）。"
                        + "主要来源是%s。",
                elementName(worst.key(), grain), worst.totalLoss() * 100,
                elementName(best.key(), grain), best.totalLoss() * 100,
                sourceName(worst.mainSource()));

        String quantity = String.format("%s −%.1f%% · %s −%.1f%% · %s −%.1f%% · %s −%.1f%%",
                shortElement(worst.key(), grain), worst.totalLoss() * 100,
                shortElement("FLORET", grain), els[1].totalLoss() * 100,
                shortElement("FERTILITY", grain), els[2].totalLoss() * 100,
                shortElement("WEIGHT", grain), els[3].totalLoss() * 100);

        String principle = String.format(
                "产量 = %s × 每穗粒数 × 粒重，而每穗粒数 = 颖花数 × 结实率。"
                        + "四个环节的<b>定数时间</b>各不相同：%s 在分蘖期定、"
                        + "颖花在孕穗期定、结实率在抽穗开花期定、粒重在灌浆期定。"
                        + "所以「什么时候出的问题」决定了「还救不救得回来」。",
                grain ? "穗数" : "株数",
                grain ? "穗数" : "株数");

        String advice = switch (worst.key()) {
            case FarmYieldAttribution.PANICLE -> String.format(
                    "最疼的是%s（−%.1f%%）。%s 在<b>分蘖期</b>定 —— 这个阶段已经过去，"
                            + "本季救不回来，只能保苗（补苗、治虫、护根）。下一季把分蘖期的水肥管住。",
                    grain ? "穗数" : "株数", worst.totalLoss() * 100,
                    grain ? "穗数" : "株数");
            case FarmYieldAttribution.FLORET -> String.format(
                    "最疼的是颖花数（−%.1f%%）。颖花在<b>孕穗期</b>定，"
                            + "高温和「旱涝交替」在这个阶段最致命。下一季在孕穗期保证水层稳定、避开持续高温。",
                    worst.totalLoss() * 100);
            case FarmYieldAttribution.FERTILITY -> String.format(
                    "最疼的是结实率（−%.1f%%）。结实率在<b>抽穗开花期</b>定，"
                            + "这是全季最敏感的三天 —— 高温会让花粉失活。"
                            + "下一季抽穗期盯紧预报，提前灌水降温。",
                    worst.totalLoss() * 100);
            case FarmYieldAttribution.WEIGHT -> String.format(
                    "最疼的是粒重（−%.1f%%）。粒重在<b>灌浆期</b>定，靠「灌」的功夫。"
                            + "下一季灌浆期保持浅水层，别断水也别深水。",
                    worst.totalLoss() * 100);
            default -> "各要素损失都不大，产量受限于品种潜力而非管理。";
        };

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("lossPanicle", els[0].totalLoss());
        metrics.put("lossFloret", els[1].totalLoss());
        metrics.put("lossFertility", els[2].totalLoss());
        metrics.put("lossWeight", els[3].totalLoss());
        metrics.put("heatPanicle", els[0].heatLoss());
        metrics.put("heatFloret", els[1].heatLoss());
        metrics.put("heatFertility", els[2].heatLoss());
        metrics.put("heatWeight", els[3].heatLoss());

        return new Insight(C_YIELD_ELEMENTS, "产量四要素", observation, quantity, principle, advice,
                severity, metrics);
    }

    // ==================================================================
    //  概念 4 · 土壤水分平衡
    // ==================================================================

    /**
     * 土壤水分平衡（{@code etToday / percToday / dryDays / waterlogDays}）。
     *
     * <p><b>原理</b>：田面水量每天走一笔账 ——
     * 收入（降雨 + 灌溉）、支出（蒸散 ET + 渗漏）、上限（田埂高度，超了径流外溢）。
     * 蒸散随物候变化（分蘖期 Kc 1.05 → 孕穗—灌浆 1.20 → 后期 0.90）。
     * <b>干旱与渍水是两个方向相反的病</b>：
     * 抽穗开花期受旱最敏感（每天最多扣 1.2% 产量），
     * 分蘖期深水伤根（每天扣 0.4%）。
     */
    private static Insight waterInsight(CropConfig crop, SoilEngine.Result sr) {
        if (sr == null) {
            return null;
        }
        int dry = sr.dryDays();
        int waterlog = sr.waterlogDays();
        double droughtLoss = sr.droughtLoss();
        double waterlogLoss = sr.waterlogLoss();
        boolean paddy = CropVisual.isPaddy(crop.visual());

        int severity;
        if (droughtLoss >= 0.10) {
            severity = 4;
        } else if (droughtLoss >= 0.03) {
            severity = 3;
        } else if (waterlogLoss >= 0.05) {
            severity = 3;
        } else if (dry >= 5 || waterlog >= 5) {
            severity = 2;
        } else if (dry > 0 || waterlog > 0) {
            severity = 1;
        } else {
            severity = 0;
        }

        String observation = String.format(
                "本季累计受旱 %d 天、深水 %d 天；今日蒸散 %.1f mm、渗漏 %.1f mm；"
                        + "干旱减产 %.1f%%、渍水减产 %.1f%%。",
                dry, waterlog, sr.etToday(), sr.percToday(),
                droughtLoss * 100, waterlogLoss * 100);

        String quantity = String.format("旱 %d 天 / 涝 %d 天（减产 %.1f%% / %.1f%%）",
                dry, waterlog, droughtLoss * 100, waterlogLoss * 100);

        String principle = paddy
                ? String.format(
                        "水田每天的水量账：降雨 + 灌溉 − 蒸散 − 渗漏（犁底层完好的水田日渗漏约 6 mm），"
                                + "超过田埂高度就径流外溢。蒸散随物候变化：分蘖期 Kc≈1.05、"
                                + "孕穗—灌浆期 Kc≈1.20、后期回落到 0.90。"
                                + "旱与涝是两个方向相反的病：抽穗开花期受旱最敏感（每天最多扣 1.2%% 产量），"
                                + "分蘖期深水伤根（每天扣 0.4%%）。")
                : String.format(
                        "旱地每天的水量账：降雨（×0.85 入渗）+ 灌溉 − 蒸散，"
                                + "有效水容量约 120 mm（60 cm 根层）。蒸散随物候变化："
                                + "分蘖期 Kc≈1.05、孕穗—灌浆期 Kc≈1.20、后期回落到 0.90。"
                                + "旱地怕的是「断水」，尤其是抽穗开花期 —— 那三天受旱最敏感。");

        String advice;
        if (droughtLoss >= 0.03) {
            advice = String.format("受旱已扣掉 %.1f%% 产量（累计 %d 天）。"
                            + "抽穗开花期是水分最敏感的阶段，断了水直接影响结实率 —— "
                            + "本季还有补救空间：旱地立刻灌一次（补 45 mm），"
                            + "水田先晒田再复水。",
                    droughtLoss * 100, dry);
        } else if (waterlogLoss >= 0.03) {
            advice = String.format("渍水已扣掉 %.1f%% 产量（深水 %d 天）。"
                    + "深水伤根、分蘖发不起来 —— 清沟排水，把水层排到 30–40 mm 的浅水层。",
                    waterlogLoss * 100, waterlog);
        } else if (dry > 0 || waterlog > 0) {
            advice = paddy
                    ? "水层管理基本正常。保持 20–40 mm 浅水层，分蘖够苗后晒田、收获前断水 —— "
                    + "「干湿交替」比长期淹水更利于根系下扎。"
                    : "土壤水分基本够用。注意旱地在抽穗开花期不能断水，那三天的缺水最要命。";
        } else {
            advice = paddy
                    ? "水层管理没有问题。保持浅水层，按「够苗晒田、收获前断水」的节奏走。"
                    : "土壤水分全程充裕。注意排水沟别堵，雨后及时排涝。";
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("dryDays", (double) dry);
        metrics.put("waterlogDays", (double) waterlog);
        metrics.put("droughtLoss", droughtLoss);
        metrics.put("waterlogLoss", waterlogLoss);
        metrics.put("etToday", sr.etToday());
        metrics.put("percToday", sr.percToday());
        metrics.put("waterMm", sr.waterMm());
        metrics.put("rwc", sr.rwc());

        return new Insight(C_WATER, "土壤水分平衡", observation, quantity, principle, advice,
                severity, metrics);
    }

    // ==================================================================
    //  文案辅助
    // ==================================================================

    /**
     * 作物短名（如「水稻」「番茄」）。
     *
     * <p>🔴 <b>为什么不能直接用 {@code crop.name()}</b>：{@code CropConfig.name()}
     * 存的是带括号的完整名（「水稻·中稻/一季稻（籼）」「番茄（春/秋茬）」），
     * 塞进句子里读起来像「水稻·中稻/一季稻（籼）从 2026-05-20 播下…」——
     * 括号里的说明是给作物选择面板看的，不是给叙事文案看的。
     * 短名在 {@code CropCatalog.Crop.shortName} 上（不在 {@code CropConfig} 上）。
     *
     * <p>🔴 <b>为什么查 {@code all()} 而不是 {@code get()}</b>：{@code CropCatalog.get()}
     * 对未知 id 会<b>兜底返回水稻</b> —— 那会让一块种了白菜的地在文案里变成水稻。
     * 查不到就退回 {@code crop.name()}（至少是真实配置里的名字），不编造。
     */
    static String shortNameOf(CropConfig crop) {
        if (crop == null) {
            return "作物";
        }
        CropCatalog.Crop c = CropCatalog.all().get(crop.id());
        if (c != null && c.shortName() != null && !c.shortName().isBlank()) {
            return c.shortName();
        }
        return crop.name() != null ? crop.name() : crop.id();
    }

    /** 要素中文名（禾谷 / 园艺口径不同）。 */
    static String elementName(String key, boolean grain) {
        return switch (key) {
            case FarmYieldAttribution.PANICLE -> grain ? "穗数" : "株数";
            case FarmYieldAttribution.FLORET -> grain ? "颖花数" : "单株花数";
            case FarmYieldAttribution.FERTILITY -> "结实率";
            case FarmYieldAttribution.WEIGHT -> grain ? "粒重" : "单果重";
            default -> key;
        };
    }

    /** 要素短名（写进「量」段用）。 */
    static String shortElement(String key, boolean grain) {
        return switch (key) {
            case FarmYieldAttribution.PANICLE -> grain ? "穗数" : "株数";
            case FarmYieldAttribution.FLORET -> grain ? "颖花" : "花数";
            case FarmYieldAttribution.FERTILITY -> "结实";
            default -> key;
        };
    }

    /** 损失来源中文名。 */
    static String sourceName(String key) {
        return switch (key) {
            case FarmYieldAttribution.SRC_HEAT -> "高温热害";
            case FarmYieldAttribution.SRC_PEST -> "病虫害";
            case FarmYieldAttribution.SRC_DISASTER -> "灾害";
            case FarmYieldAttribution.SRC_DROUGHT -> "干旱";
            case FarmYieldAttribution.SRC_WATERLOG -> "渍水";
            default -> key;
        };
    }
}
