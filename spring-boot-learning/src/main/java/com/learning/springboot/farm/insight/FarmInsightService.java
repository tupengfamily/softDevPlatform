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
    /** 概念：地力与轮作（2026-10-28 P2 补齐）。 */
    public static final String C_SOIL_ROTATION = "SOIL_ROTATION";
    /** 概念：热害与积温胁迫（2026-10-28 P2 补齐）。 */
    public static final String C_HEAT_STRESS = "HEAT_STRESS";
    /** 概念：春化与越冬（2026-10-28 P2 补齐）。 */
    public static final String C_VERNALIZATION = "VERNALIZATION";
    /** 概念：安全间隔期 PHI（2026-10-28 P2 补齐）。 */
    public static final String C_PHI = "PHI";

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
     * 安全间隔期（PHI）的本季事实（2026-10-28 P2 新增）。
     *
     * <p><b>为什么单独传进来而不是让解释层自己去查农事表</b>：
     * PHI 的判定是「<b>距采收还剩几天</b> < 药剂要求间隔天数」，
     * 而「距采收还剩几天」依赖**收割日**——那是只有收割那一刻才知道的事实。
     * 如果解释层自己按当前日期算，会得出「还有 20 天，PHI 安全」，
     * 而玩家实际是在收割当天被判定超标 —— 解释与结果对不上。
     *
     * <p>所以口径是：<b>收割时用真实采收日算一次</b>，把这个结论传给解释层。
     *
     * @param violated  本季是否发生过 PHI 违规（超安全间隔期施药）
     * @param violations 违规次数
     * @param daysShort 最短的一次差了多少天（0 = 无违规）
     * @param itemName  差得最多的那一次用的农药名（null = 无违规）
     * @param phiDays   该农药要求的安全间隔期天数
     */
    public record PhiInfo(boolean violated, int violations, int daysShort,
                           String itemName, int phiDays) {
        /** 「从未施药 / 从未违规」的 PHI 事实。 */
        public static final PhiInfo NONE = new PhiInfo(false, 0, 0, null, 0);
    }

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
        return insights(crop, r, sr, pr, dr, seasons, droughtRelief, PhiInfo.NONE);
    }

    /**
     * 计算一个地块本季的全部科学解释（含 PHI）。
     *
     * @param phi 本季 PHI 事实（可为 null，等价于 {@link PhiInfo#NONE}）
     */
    public static List<Insight> insights(CropConfig crop, SimEngine.SimResult r,
                                         SoilEngine.Result sr, PestEngine.Result pr,
                                         DisasterEngine.Result dr, int seasons,
                                         double droughtRelief, PhiInfo phi) {
        List<Insight> out = new ArrayList<>(8);
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
        // 🔴 2026-10-28 P2：补齐 8 概念中原本缺失的 4 个。
        // 它们的引擎数据早就有了（sfi/soilCoef、heatForce/daysOverOpt、
        // vernalizationDays/winterKilled、phiViolated），只是没有独立解释文案 ——
        // 章节要讲这 4 个概念，解释层就得能讲，否则「章节口径」与「面板口径」会分叉。
        Insight soil = soilRotationInsight(crop, sr, seasons);
        if (soil != null) {
            out.add(soil);
        }
        Insight heat = heatStressInsight(crop, r);
        if (heat != null) {
            out.add(heat);
        }
        Insight vern = vernalizationInsight(crop, r);
        if (vern != null) {
            out.add(vern);
        }
        Insight phiInsight = phiInsight(phi);
        if (phiInsight != null) {
            out.add(phiInsight);
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
    //  概念 5 · 地力与轮作
    // ==================================================================

    /**
     * 地力与轮作（{@code sfi / soilCoef / seasons}）。
     *
     * <p><b>原理</b>：地力（ Soil Fertility Index，0–100）由三部分决定：
     * <ul>
     *   <li><b>基础地力</b> 52 分（不施肥的地）；</li>
     *   <li><b>养分满足度贡献</b> 最多 26 分（按 Mitscherlich 曲线，
     *       施到需求量只拿到约 21.6 分 —— 这就是报酬递减的直接体现）；</li>
     *   <li><b>连作惩罚</b>：每多连作一季 −1 分；</li>
     *   <li><b>过量施肥惩罚</b>：施氮超过需求量 1.3 倍，禾谷 −8、豆科 −12。</li>
     * </ul>
     * 产量系数 {@code soilCoef = 0.60 + 0.40 × sfi/100}，
     * 所以地力 40 分 → 系数 0.76，95 分 → 0.98 —— <b>地力直接乘在产量上</b>。
     *
     * <p>🔴 <b>教学重点</b>：轮作不是「换个作物种」这么轻 —— 换的是<b>根系类型与吸肥层</b>。
     * 豆科固氮还能给下一茬留下氮，水稻连作则把同一种病原菌养在土里。
     */
    private static Insight soilRotationInsight(CropConfig crop, SoilEngine.Result sr, int seasons) {
        if (sr == null) {
            return null;
        }
        double sfi = sr.sfi();
        double coef = sr.soilCoef();
        int continuous = Math.max(0, seasons - 1);
        boolean legume = CropVisual.isLegume(crop.visual());
        double nRatio = sr.nNeed() <= 0 ? 0 : sr.nApplied() / sr.nNeed();

        int severity;
        if (sfi < 50) {
            severity = 4;
        } else if (sfi < 60) {
            severity = 3;
        } else if (continuous >= 3) {
            severity = 2;
        } else if (sfi < 70) {
            severity = 1;
        } else {
            severity = 0;
        }

        String observation = String.format(
                "地力指数 %.0f / 100，产量系数 %.2f；这块地已连作 %d 季，"
                        + "本季施氮 %.1f kg/亩（需求的 %.0f%%）。",
                sfi, coef, continuous, sr.nApplied(), nRatio * 100);

        String quantity = String.format("SFI %.0f / 100 · 系数 %.2f · 连作 %d 季",
                sfi, coef, continuous);

        String principle = String.format(
                "地力 = 52 分基础 + 最多 26 分养分贡献 − 连作 %d 分 − 过量施肥惩罚。"
                        + "产量系数 = 0.60 + 0.40 × 地力/100，直接乘在产量上。"
                        + "所以地力 40 分只拿 0.76 的系数，95 分能拿 0.98 —— "
                        + "连年重茬的地，再怎么施肥也补不回被病原菌与养分单一吸收拖走的那部分。",
                continuous);

        String advice;
        if (nRatio > 1.3) {
            advice = String.format(
                    "施氮过量（是需求的 %.1f 倍）正在<b>反噬地力</b>：过量化肥与连作一样会扣分。"
                            + "下一季把总施氮压回 %.1f kg/亩左右，并安排一次轮作。",
                    nRatio, sr.nNeed());
        } else if (continuous >= 3) {
            advice = String.format(
                    "已经连作 %d 季，地力被逐季扣分。下一季换一种<b>根系类型不同</b>的作物"
                            + "（如水稻连作后种大豆：豆科固氮能补回一部分氮，还打断了同种病原菌的寄主链）。",
                    continuous);
        } else if (sfi < 60) {
            advice = String.format(
                    "地力偏低（%.0f 分）。本季的当务之急是<b>把养分补到需求量</b>"
                            + "（%.1f kg N/亩），而不是继续在同一块地上重茬。",
                    sfi, sr.nNeed());
        } else {
            advice = legume
                    ? "地力状态良好。豆科本身固氮，别忘了两件事：① 根瘤菌剂拌种提高固氮效率；"
                    + "② 下一季轮作时留给水稻，把这份氮用起来。"
                    : "地力状态良好。保持当前施肥量，并在下一季安排轮作，避免连作累积扣分。";
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("sfi", sfi);
        metrics.put("soilCoef", coef);
        metrics.put("continuousSeasons", (double) continuous);
        metrics.put("nApplied", sr.nApplied());
        metrics.put("nNeed", sr.nNeed());
        // 🔴 2026-10-28 P2 收尾：补两个 0..1 的归一化量，供前端画进度条。
        // 🔴 分母不是新造的农学阈值，而是引擎公式里的**上下界**：
        //    · sfiRatio：地力本身就是 0..100 的指数，除以 100 即其定义域上界；
        //    · soilCoefRatio：soilCoef = 0.60 + 0.40 × (sfi/100)，定义域是 [0.60, 1.00]，
        //      所以「达到该区间上限的比例」= (coef − 0.60) / 0.40。
        //    这样前端不需要知道「地力多少算好」，也就不会在前端复制一份标准。
        metrics.put("sfiRatio", sfi / 100.0);
        metrics.put("soilCoefRatio", Math.max(0.0, Math.min(1.0, (coef - 0.60) / 0.40)));

        return new Insight(C_SOIL_ROTATION, "地力与轮作", observation, quantity, principle, advice,
                severity, metrics);
    }

    // ==================================================================
    //  概念 6 · 热害与积温胁迫
    // ==================================================================

    /**
     * 热害与积温胁迫（{@code heatForce / daysOverOpt / dmg.*}）。
     *
     * <p><b>原理</b>：两个方向相反的高温效应，必须分开讲，否则玩家会以为
     * 「热了长得快、还更好」：
     * <ul>
     *   <li><b>积温胁迫（正效应）</b>：日均温超过最适温度（{@code tOptUpper}）的部分
     *       累加成 {@code heatForce}，发育速率 = {@code 1 + kForce × heatForce/aatReq}
     *       —— 高温让作物<b>提前成熟</b>；</li>
     *   <li><b>热害（负效应）</b>：<b>最高温</b>超过物候期的热害阈值
     *       （{@code HeatProfile.tStart/tSevere}）会损伤对应器官 ——
     *       分蘖期伤穗数、孕穗期伤颖花、抽穗开花期伤结实率、灌浆期伤粒重。
     *       注意用的是 {@code T_max}，与积温用的日均温<b>不是一个量</b>。</li>
     * </ul>
     * 所以「高温天数」不等于「减产」：日均温高但最高温不极端 → 只提前成熟；
     * 最高温破阈值 → 才真的伤器官。
     */
    private static Insight heatStressInsight(CropConfig crop, SimEngine.SimResult r) {
        if (r == null) {
            return null;
        }
        double heatForce = r.heatForce();
        int overDays = r.daysOverOpt();
        // 🔴 缺数据不编造：一季都没遇到超最适温的日子，热害概念整条不出现。
        if (overDays <= 0 && heatForce <= 0) {
            return null;
        }
        double[] heat = new double[]{
                r.dmg().panicle(), r.dmg().floret(), r.dmg().fertility(), r.dmg().weight()};
        double worstLoss = 0;
        String worstTarget = null;
        for (double v : heat) {
            if (v > worstLoss) {
                worstLoss = v;
            }
        }
        // 找出损失最大的通道对应的物候段（教学上要指明「哪个阶段受伤」）
        String worstStage = null;
        if (r.dmg() != null) {
            worstTarget = worstTargetOf(heat);
            worstStage = stageNameOf(crop, worstTarget);
        }

        int severity;
        if (worstLoss >= 0.20) {
            severity = 4;
        } else if (worstLoss >= 0.10) {
            severity = 3;
        } else if (worstLoss >= 0.03) {
            severity = 2;
        } else if (overDays >= 5) {
            severity = 1;
        } else {
            severity = 0;
        }

        String observation = String.format(
                "本季有 %d 天的日均温超过最适温度 %.0f℃，累计高温强迫量 %.0f ℃·d；"
                        + "高温造成的最大单项损伤 %.1f%%。",
                overDays, crop.tOptUpper(), heatForce, worstLoss * 100);

        String quantity = String.format("超最适温 %d 天 · 强迫量 %.0f ℃·d · 最大损伤 %.1f%%",
                overDays, heatForce, worstLoss * 100);

        String principle = String.format(
                "高温有两个<b>方向相反</b>的作用，必须分开看：① <b>积温胁迫</b>用日均温 —— "
                        + "超过最适温度 %.0f℃ 的部分累加成强迫量，发育速率 = 1 + %.1f × 强迫量/需求积温，"
                        + "结果是作物<b>提前成熟</b>；② <b>热害</b>用<b>最高温</b> —— "
                        + "破了自己物候期的热害阈值就伤器官：分蘖期伤穗数、孕穗期伤颖花、"
                        + "抽穗开花期伤结实率、灌浆期伤粒重。所以「高温天数多」不等于「一定减产」，"
                        + "关键是最高温有没有破阈值。",
                crop.tOptUpper(), crop.kForce());

        String advice;
        if (worstLoss >= 0.10 && worstStage != null) {
            advice = String.format(
                    "最疼的是<b>%s</b>（−%.1f%%）。这个阶段的器官一旦受伤就<b>不可逆</b> —— "
                            + "过了这个物候窗口再补救已经晚了。下一季遇到这段预报："
                            + "① 有灌溉条件的话提前灌水降温；② 选耐热品种；③ 把播期挪开这个时段。",
                    worstStage, worstLoss * 100);
        } else if (overDays >= 5) {
            advice = String.format(
                    "有 %d 天日均温超过最适温度，但<b>没有明显器官损伤</b> —— "
                            + "这说明高温只让作物提前成熟，没有造成减产。"
                            + "不过提前成熟意味着灌浆期缩短、粒重偏轻，产量上限可能还是被压了一点。",
                    overDays);
        } else {
            advice = "本季高温影响轻微。注意「提前成熟」不等于「提前收」—— 收割仍以黄熟期为准，"
                    + "太早收会掉千粒重。";
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("heatForce", heatForce);
        metrics.put("daysOverOpt", (double) overDays);
        metrics.put("tOptUpper", crop.tOptUpper());
        metrics.put("worstHeatLoss", worstLoss);
        // 🔴 2026-10-28 P2 收尾：补「强迫量占需求积温的比例」。
        // 🔴 这是**归一化，不是新阈值**：分子分母都是引擎已有的量，
        //    且这个比值正是引擎算发育加速的输入 —— SimEngine.fDevT(heatForce, aatReq, c)
        //    = 1 + kForce × (heatForce / aatReq)。前端要画进度条就需要 0..1 的量，
        //    在这里算好下发，避免前端自己拼分母（那等于在前端复制一份农学口径）。
        metrics.put("heatForceRatio", r.aatReq() > 0 ? heatForce / r.aatReq() : 0.0);

        return new Insight(C_HEAT_STRESS, "热害与积温胁迫", observation, quantity, principle, advice,
                severity, metrics);
    }

    /**
     * 四个热害通道里损失最大的那个（返回 {@code FarmYieldAttribution} 的通道键）。
     */
    private static String worstTargetOf(double[] heat) {
        int idx = 0;
        for (int i = 1; i < heat.length; i++) {
            if (heat[i] > heat[idx]) {
                idx = i;
            }
        }
        return switch (idx) {
            case 0 -> FarmYieldAttribution.PANICLE;
            case 1 -> FarmYieldAttribution.FLORET;
            case 2 -> FarmYieldAttribution.FERTILITY;
            default -> FarmYieldAttribution.WEIGHT;
        };
    }

    /**
     * 通道对应的物候段名（从作物的 {@code HeatProfile} 里查 —— 那里存的就是
     * 「哪个阶段伤哪个器官」，不用另建映射表）。
     */
    private static String stageNameOf(CropConfig crop, String target) {
        if (crop == null || target == null || crop.heatProfiles() == null) {
            return null;
        }
        for (CropConfig.HeatProfile p : crop.heatProfiles()) {
            if (target.equals(p.target())) {
                return p.name();
            }
        }
        return null;
    }

    // ==================================================================
    //  概念 7 · 春化与越冬
    // ==================================================================

    /**
     * 春化与越冬（{@code vernalizationDays / winterKilled / winterHardy}）。
     *
     * <p><b>原理</b>：冬性作物（如冬小麦）必须经历一段 <b>0–7℃ 的低温</b>，
     * 才能在春天转入生殖生长（抽穗）。这个过程叫<b>春化</b>，是「经历过的低温记忆」。
     *
     * <p>引擎里的实现：
     * <ul>
     *   <li>日均温落在 0–7℃ 才计入春化天数；</li>
     *   <li>春化完成度 &lt; 60% 时 DVS 被<b>硬卡在 0.42</b>（只长草不抽穗）；</li>
     *   <li>60%–100% 之间按完成度打折推进；</li>
     *   <li>越冬作物日均温 &lt; 3℃ 时<b>不计有效积温</b>（否则整个冬天的
     *       "免费热量"会把发育推进到 2 月就抽穗）。</li>
     * </ul>
     *
     * <p>🔴 <b>越冬致死是另一件事</b>：{@code winterHardy=true} 的作物有耐寒阈值
     * （{@code winterKillTmin}），低于它就冻死；{@code winterHardy=false} 的
     * （水稻/玉米/大豆）遇到 0℃ 以下直接终止。
     */
    private static Insight vernalizationInsight(CropConfig crop, SimEngine.SimResult r) {
        if (r == null) {
            return null;
        }
        boolean needsVernalization = crop.vernalizationDays() > 0;
        boolean winterHardy = crop.winterHardy();
        // 🔴 不需要春化又不越冬的作物（春稻、玉米、大豆）没有这个概念。
        if (!needsVernalization && !winterHardy && !r.winterKilled()) {
            return null;
        }

        int req = crop.vernalizationDays();
        int got = r.vernalizationDays();
        boolean winterKilled = r.winterKilled();

        // 🔴 春化完成度（%）。提前算成一个 double 变量，
        // 不要在 String.format 的实参里写 `req <= 0 ? 0 : ...` ——
        // 那个 `0` 是 int，会让整个条件表达式退化成「装箱 Integer」，
        // 遇到 `%.0f` 就抛 IllegalFormatConversionException: f != java.lang.Integer。
        // （本项目已在这个坑上栽过两次：豆科建议文案、整型误用 %.0f。）
        double vernPct = req <= 0 ? 0.0 : Math.min(100.0, got * 100.0 / req);

        int severity;
        if (winterKilled) {
            severity = 5;
        } else if (needsVernalization && req > 0 && got * 100 / req < 60) {
            severity = 4;
        } else if (needsVernalization && req > 0 && got < req) {
            severity = 2;
        } else {
            severity = 0;
        }

        String observation;
        if (winterKilled) {
            observation = String.format(
                    "%s 越冬时遇上 %.1f℃ 的极端低温，被冻死了 —— 冬季最低温低于它的耐寒阈值 %.1f℃。",
                    shortNameOf(crop), 0.0, crop.winterKillTmin());
        } else if (needsVernalization) {
            observation = String.format(
                    "%s 是冬性品种，需要 %d 天春化（0–7℃ 的低温积累）；"
                            + "本季已积累 %d 天，完成度 %.0f%%。",
                    shortNameOf(crop), req, got, vernPct);
        } else {
            observation = String.format(
                    "%s 不需要春化，但能越冬（耐寒阈值 %.1f℃）；本季最低温未触及致死温度。",
                    shortNameOf(crop), crop.winterKillTmin());
        }

        String quantity = needsVernalization
                ? String.format("春化 %d / %d 天（%.0f%%）",
                got, req, vernPct)
                : String.format("耐寒阈值 %.1f℃ · 未冻死", crop.winterKillTmin());

        String principle = needsVernalization
                ? String.format(
                        "春化是<b>低温记忆</b>：冬性作物必须累计 %d 天 0–7℃ 的低温，"
                                + "才能在春天转入生殖生长（抽穗）。春化完成度不足 60%% 时，"
                                + "发育被<b>硬卡在 DVS 0.42</b> —— 只长草、不抽穗，"
                                + "看起来像「长势不好」，其实是<b>还没到时候</b>。"
                                + "同时越冬期日均温低于 3℃ 不计有效积温，"
                                + "否则整个冬天的免费热量会让它在 2 月就抽穗。",
                        req)
                : String.format(
                        "越冬作物有两个温度关口：① <b>耐寒阈值 %.1f℃</b> —— 低于它就冻死，"
                                + "这一季全损；② 冬季休眠（日均温 &lt; 3℃ 不计积温），"
                                + "保证热量预算只从开春后开始算。",
                        crop.winterKillTmin());

        String advice;
        if (winterKilled) {
            advice = String.format(
                    "这一季被冻死了，本季没有产量。冬季最低温低于 %.1f℃ 意味着"
                            + "这个播期/地块不适合越冬作物。可选：① 换不需越冬的作物；"
                            + "② 往更南的农业区换（无霜期更长）；③ 盖膜保温。",
                    crop.winterKillTmin());
        } else if (needsVernalization && req > 0 && got * 100 / req < 60) {
            advice = String.format(
                    "春化只完成 %.0f%%（%d/%d 天），发育被卡在 DVS 0.42 不抽穗。"
                            + "本季基本收不成穗。下一季要保证<b>足够长的低温期</b>："
                            + "① 选更早播的品种（播期早 = 经历低温的时间长）；"
                            + "② 选冬季更冷的区（春化够了但别冻死）。",
                    Math.min(100.0, got * 100.0 / req), got, req);
        } else if (needsVernalization && req > 0 && got < req) {
            advice = String.format(
                    "春化完成 %.0f%%（%d/%d 天），抽穗会偏晚、穗偏小。"
                            + "本季还能补救的：保持水肥跟上，等春化补满。",
                    Math.min(100.0, got * 100.0 / req), got, req);
        } else {
            advice = "越冬顺利，没有冻害。留意倒春寒 —— 抽穗期的极端低温仍会伤花。";
        }

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("vernalizationDays", (double) got);
        metrics.put("vernalizationReq", (double) req);
        metrics.put("winterKillTmin", crop.winterKillTmin());
        metrics.put("winterKilled", winterKilled ? 1.0 : 0.0);
        // 🔴 2026-10-28 P2 收尾：补「春化完成度」（0..1）。
        // 🔴 这不是新口径 —— 与引擎算发育推进用的是**同一个式子**：
        //    SimEngine 里 vc = Math.min(1.0, vernDays / (double) c.vernalizationDays())。
        //    这里再算一份只是为了让前端能画进度条；写成后端下发，
        //    是为了避免前端自己拼「完成度 = 已积累 / 需求」这条农学公式。
        metrics.put("vernalizationRatio", vernPct / 100.0);

        return new Insight(C_VERNALIZATION, "春化与越冬", observation, quantity, principle, advice,
                severity, metrics);
    }

    // ==================================================================
    //  概念 8 · 安全间隔期（PHI）
    // ==================================================================

    /**
     * 安全间隔期（PHI）。
     *
     * <p><b>原理</b>：农药施用后需要等待若干天，作物才能把残留代谢掉。
     * 这个「必须等的天数」叫<b>安全间隔期</b>（Pre-Harvest Interval）。
     * 距采收不足 PHI 就采收，收购方会检出农残超标，收购价直接打折
     * （本项目按 0.85 计，即少 15%）。
     *
     * <p>🔴 <b>这是「能种」与「能卖」的区别</b>：田里长得再好，
     * 农残超标就只能打折卖。PHI 是<b>唯一会直接扣钱</b>的农学概念。
     *
     * <p>🔴 <b>为什么按「距采收」而不是「距施药」算</b>：
     * PHI 的定义就是「最后一次施药到采收之间至少间隔 N 天」，
     * 所以必须知道采收日。引擎在收割那一刻用真实采收日算一次，
     * 把结论（{@link PhiInfo}）交给解释层 —— 避免「面板说安全、收割被判超标」。
     */
    private static Insight phiInsight(PhiInfo phi) {
        if (phi == null) {
            return null;
        }
        // 🔴 缺数据不编造：从未施用农药 → 没有 PHI 这回事 → 整条不出现。
        if (!phi.violated() && phi.violations() <= 0) {
            return null;
        }

        int severity;
        if (phi.daysShort() >= 10) {
            severity = 4;
        } else if (phi.daysShort() >= 5) {
            severity = 3;
        } else if (phi.daysShort() >= 1) {
            severity = 2;
        } else {
            severity = 1;
        }

        String observation = String.format(
                "本季有 %d 次施药距采收不足安全间隔期；最严重的一次是%s，"
                        + "该药要求间隔 %d 天，实际差 %d 天。",
                phi.violations(), phi.itemName(), phi.phiDays(), phi.daysShort());

        String quantity = String.format("违规 %d 次 · 最短差 %d 天 · 折价 15%%",
                phi.violations(), phi.daysShort());

        String principle = String.format(
                "安全间隔期（PHI）＝最后一次施药到采收之间<b>必须间隔的天数</b>。"
                        + "不足就采收，收购方检出农残超标，收购价打 0.85 折（少 15%%）。"
                        + "它与 PHI 的<b>代谢速度</b>有关：农药降解越快，等待期越短。"
                        + "本项目里 %s 的 PHI 是 %d 天。",
                phi.itemName(), phi.phiDays());

        String advice = String.format(
                "这一季的收成会被打 0.85 折（农残超标）。下季记住：%s 需要等 %d 天，"
                        + "所以<b>最后一次施药日 + %d 天 ≤ 计划采收日</b>。"
                        + "采收前如果必须治病虫害，用<b>短 PHI 的药</b>（如苏云金杆菌 PHI 只有 1 天）"
                        + "或者干脆放弃这一次治疗 —— 一次药钱换 15%% 的收成，账算得过来就不值。",
                phi.itemName(), phi.phiDays(), phi.phiDays());

        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("phiViolations", (double) phi.violations());
        metrics.put("phiDaysShort", (double) phi.daysShort());
        metrics.put("phiDays", (double) phi.phiDays());
        metrics.put("priceFactor", 0.85);
        // 🔴 2026-10-28 P2 收尾：补「间隔缺口比例」（0..1，越大越糟）。
        // 🔴 分母用**该药要求的间隔天数**（不是随便一个数）：
        //    「差 5 天」在要求 7 天的药上是 71% 缺口，在要求 21 天的药上只有 24% ——
        //    只看「差几天」会让人误判严重程度。除零保护：phiDays=0 时无缺口。
        metrics.put("phiShortRatio", phi.phiDays() > 0
                ? Math.min(1.0, (double) phi.daysShort() / phi.phiDays())
                : 0.0);

        return new Insight(C_PHI, "安全间隔期（PHI）", observation, quantity, principle, advice,
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
