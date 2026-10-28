package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.CropConfig;
import com.learning.springboot.farm.engine.CropVisual;
import com.learning.springboot.farm.engine.DisasterEngine;
import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.PestEngine;
import com.learning.springboot.farm.engine.RiceConfig;
import com.learning.springboot.farm.engine.SimEngine;
import com.learning.springboot.farm.engine.SoilEngine;
import com.learning.springboot.farm.engine.SoybeanConfig;
import com.learning.springboot.farm.engine.WheatConfig;
import com.learning.springboot.farm.insight.FarmInsightService;
import com.learning.springboot.farm.insight.FarmYieldAttribution;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 科学解释层（2026-10-27 P1）口径回归。
 *
 * <p><b>为什么这些断言这么「较真」</b>：解释层是<b>文案层</b>，但它绑着一堆
 * 农学口径。口径错了玩家看到的是「正确的废话」—— 读起来通顺，但说的不是这季发生的事。
 * 所以这里测的<b>不是「函数返回了什么」，而是不变式</b>：
 * <ol>
 *   <li><b>系数链与原表达式逐位一致</b>（抽唯一实现不能改变任何产量数值）；</li>
 *   <li><b>积温比 ≠ 成熟度</b>（高温发育加速会让积温比 &lt; 1 也成熟）；</li>
 *   <li><b>豆科的氮账本是反过来的</b>（外源氮压制根瘤，不能套通用话术）；</li>
 *   <li><b>「主要来源」用边际贡献</b>（各来源连乘，直接相加会高估）；</li>
 *   <li><b>缺数据不编造</b>（拿不到引擎数据的概念整条不出现，而不是显示 0）。</li>
 * </ol>
 */
class FarmInsightServiceTest {

    // ==================================================================
    //  1. 系数链与原表达式逐位一致（抽唯一实现的底线）
    // ==================================================================

    /**
     * 🔴 <b>这是本次改造最重要的一条断言</b>。
     *
     * <p>产量公式里的「病虫害/灾害/旱涝 → 四要素」映射被抽到了
     * {@link FarmYieldAttribution}，供产量计算与解释层共用。
     * 抽唯一实现最大的风险是<b>顺手改了数值口径</b>（浮点结合律不成立，
     * 把左到右相乘改成先乘好再乘会有 1 ULP 漂移，而 ey 最后要 round(x,1)，
     * 理论上能翻转一位小数）。
     *
     * <p>所以这里用<b>随机 20 万组</b>覆盖各通道的取值域，
     * 断言新实现与原表达式 {@code Double.compare == 0}（逐位一致，不只是近似）。
     */
    @Test
    @DisplayName("P1：系数链与原产量表达式逐位一致（抽唯一实现不改变任何产量数值）")
    void chainMustBeBitIdenticalToOriginalExpression() {
        Random rnd = new Random(20261027);
        int mismatches = 0;

        for (int i = 0; i < 200_000; i++) {
            double stem = clamp01(rnd.nextDouble(), 0.60);
            double leaf = clamp01(rnd.nextDouble(), 0.60);
            double grainL = clamp01(rnd.nextDouble(), 0.60);
            double flower = clamp01(rnd.nextDouble(), 0.60);
            double dis = clamp01(rnd.nextDouble(), 0.60);
            double drought = clamp01(rnd.nextDouble(), 0.30);
            double waterlog = clamp01(rnd.nextDouble(), 0.20);
            double soil = clamp01(rnd.nextDouble(), 1.0);
            // 滴灌系统：干旱损失减半
            double relief = rnd.nextBoolean() ? 0.5 : 0.0;
            drought = drought * (1 - relief);

            double rPan = rnd.nextDouble() * 200_000;
            double rGrain = rnd.nextDouble() * 200;
            double rFert = rnd.nextDouble();
            double rWt = rnd.nextDouble() * 60;

            // ---- 原表达式（FarmPlayService.yields() 的逐字拷贝）----
            double oPan = rPan * (1 - stem) * (1 - waterlog * 0.6);
            double oGrain = rGrain * (1 - flower) * (1 - grainL * 0.8 - leaf * 0.2)
                    * (1 - dis * 0.6) * (1 - drought * 0.8);
            double oFert = rFert * (1 - flower);
            double oWt = rWt * (1 - leaf * 0.5 - grainL * 0.3) * (1 - dis * 0.4);

            // ---- 新实现（FarmYieldAttribution 的系数链）----
            FarmYieldAttribution.Factors f = new FarmYieldAttribution.Factors(
                    stem, leaf, grainL, flower, dis, drought, waterlog, soil);
            FarmYieldAttribution.ElementFactors ef = FarmYieldAttribution.elementFactors(f);

            if (Double.compare(oPan, ef.panicle().apply(rPan)) != 0) mismatches++;
            if (Double.compare(oGrain, ef.grain().apply(rGrain)) != 0) mismatches++;
            if (Double.compare(oFert, ef.fertility().apply(rFert)) != 0) mismatches++;
            if (Double.compare(oWt, ef.weight().apply(rWt)) != 0) mismatches++;
        }

        assertEquals(0, mismatches,
                "系数链与原表达式出现 " + mismatches + " 处偏差 —— "
                        + "抽唯一实现改变了产量数值，必须逐位一致");
    }

    /**
     * 干���减免必须真的作用在<b>干旱</b>通道上，且只作用在干旱上。
     *
     * <p>🔴 滴灌（{@code TOOL_IRRIGATION}）减半的是干旱损失，
     * 不该顺带减半渍水或病虫害 —— 那是另一个农具的效果。
     */
    @Test
    @DisplayName("P1：农具干旱减免只作用于干旱通道")
    void droughtReliefOnlyAffectsDroughtChannel() {
        FarmYieldAttribution.Factors withRelief = FarmYieldAttribution.factorsOf(
                null, null, null, 0.5);
        FarmYieldAttribution.Factors noRelief = FarmYieldAttribution.factorsOf(
                null, null, null, 0.0);

        // sr 为 null → 干旱损失取 0，这里主要验证「其它通道不受影响」
        assertEquals(noRelief.stemLoss(), withRelief.stemLoss(), 1e-12);
        assertEquals(noRelief.leafLoss(), withRelief.leafLoss(), 1e-12);
        assertEquals(noRelief.grainLoss(), withRelief.grainLoss(), 1e-12);
        assertEquals(noRelief.flowerLoss(), withRelief.flowerLoss(), 1e-12);
        assertEquals(noRelief.disasterLoss(), withRelief.disasterLoss(), 1e-12);
        assertEquals(noRelief.waterlogLoss(), withRelief.waterlogLoss(), 1e-12);
    }

    // ==================================================================
    //  2. 「主要来源」用边际贡献（各来源连乘，不能相加）
    // ==================================================================

    /**
     * 🔴 各损失来源是<b>连乘</b>的，所以「主要来源」必须用边际贡献算：
     * 拿掉某来源后剩余部分能补回多少损失。
     *
     * <p>反例：两个来源各损失 50%，直接相加得到「总损失 100%」，
     * 而实际连乘后总损失是 75%。如果按相加判「主要来源」，
     * 两个来源会并列，无法告诉玩家「先修哪个」。
     */
    @Test
    @DisplayName("P1：主要来源按边际贡献判定（连乘损失不能按相加口径）")
    void mainSourceUsesMarginalContribution() {
        // 构造：花器受损 50%（PEST）、颖花受损 50%（PEST 同源）、
        // 灾害 50%、干旱 50% —— 四个来源各 50%
        FarmYieldAttribution.Factors f = new FarmYieldAttribution.Factors(
                0.0,   // stem
                0.0,   // leaf
                0.5,   // grain
                0.5,   // flower
                0.5,   // disaster
                0.5,   // drought
                0.0,   // waterlog
                1.0);  // soil
        FarmYieldAttribution.Element[] els = FarmYieldAttribution.elements(
                RiceConfig.indicaMidSeason(), new double[]{0, 0, 0, 0}, f);

        FarmYieldAttribution.Element floret = els[1];
        // 总损失 = 1 - (1-0.5) × (1-0.5×0.8-0) × (1-0.5×0.6) × (1-0.5×0.8)
        //        = 1 - 0.5 × 0.6 × 0.7 × 0.6 = 1 - 0.126 = 0.874
        assertEquals(0.874, floret.totalLoss(), 1e-9,
                "连乘口径的总损失算错了");
        // 边际贡献：拿掉灾害 → 0.5×0.6×0.6 = 0.18，补回 0.18-0.18×0.7 = 0.054
        //            拿掉干旱 → 0.5×0.6×0.7 = 0.21，补回 0.21-0.21×0.6 = 0.084
        //            拿掉 PEST → 0.7×0.6 = 0.42，补回 0.42-0.42×0.3 = 0.294
        assertEquals(FarmYieldAttribution.SRC_PEST, floret.mainSource(),
                "PEST 的边际贡献应最大（两个通道都受损），不该按『谁先出现』判定");
    }

    /**
     * 🔴 高温损伤必须按<b>各通道自己的 cap</b> 裁剪，不能取四个 cap 的最大值。
     *
     * <p>水稻四个 cap 分别是 {@code capPan=0.30 / capFloret=0.25 / capFert=0.60 / capWt=0.45}。
     * 如果取 max（0.60），颖花通道的高温损失会被放大一倍多。
     *
     * <p>🔴 <b>cap 是上限不是固定值</b>：原始损伤 0.9 时，PANICLE 通道裁到 0.30，
     * 但 FERTILITY 通道（cap 0.60）只裁到 0.60 —— 断言要按 {@code min(原始, cap)} 算。
     */
    @Test
    @DisplayName("P1：高温损伤按各通道自己的 cap 裁剪")
    void heatDamageClippedByPerChannelCap() {
        CropConfig rice = RiceConfig.indicaMidSeason();
        // 原始高温损伤 0.9（超过所有 cap，四个通道裁剪结果各不相同）
        FarmYieldAttribution.Element[] els = FarmYieldAttribution.elements(
                rice, new double[]{0.9, 0.9, 0.9, 0.9},
                new FarmYieldAttribution.Factors(0, 0, 0, 0, 0, 0, 0, 1));

        assertEquals(Math.min(0.9, rice.capPan()), els[0].heatLoss(), 1e-9);
        assertEquals(Math.min(0.9, rice.capFloret()), els[1].heatLoss(), 1e-9);
        assertEquals(Math.min(0.9, rice.capFert()), els[2].heatLoss(), 1e-9);
        assertEquals(Math.min(0.9, rice.capWt()), els[3].heatLoss(), 1e-9);

        // 🔴 关键：四个通道的裁剪结果必须<b>互不相同</b> ——
        // 如果实现里取了 max(cap)，这里四个值会全等于 0.60。
        assertTrue(els[0].heatLoss() < els[2].heatLoss(),
                "PANICLE 通道的 cap（" + rice.capPan() + "）应小于 FERTILITY 通道（"
                        + rice.capFert() + "），若相等说明取了 max(cap)");
        assertTrue(els[1].heatLoss() < els[3].heatLoss(),
                "FLORET 通道的 cap（" + rice.capFloret() + "）应小于 WEIGHT 通道（"
                        + rice.capWt() + "），若相等说明取了 max(cap)");
    }

    // ==================================================================
    //  3. 积温比 ≠ 成熟度（口径铁律）
    // ==================================================================

    /**
     * 🔴 <b>有效积温比不能当成熟度用</b>。
     *
     * <p>{@code SimEngine} 里高温发育加速（{@code fDevT}）会让
     * {@code aatCum / aatReq < 1} 也可能成熟（{@code matureDate != null}）。
     * 所以解释层必须看 {@code matureDate}，而不是只看积温比。
     *
     * <p>这条断言锁的是：成熟后 severity 必须是 0（不该报警），
     * 哪怕积温比看起来还没满。
     */
    @Test
    @DisplayName("P1：已成熟的作物即使积温比 < 1 也不应报严重度")
    void maturedCropHasZeroSeverityEvenWhenRatioBelowOne() {
        // 造一个「已成熟」但积温比明显不足的结果：
        // 直接构造 SimResult（matureDate 非 null = 已成熟）
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 100, 1.0,
                "2026-05-20", "2026-08-10", "2026-09-15",
                2000.0,        // aatCum —— 只有 2000，远低于 3850（比值 0.52）
                0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 100.0, 100.0,
                List.of(0.1, 0.5, 1.0),
                false, false, 0);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, null, null, null, 1, 0.0);

        FarmInsightService.Insight aat = find(list, FarmInsightService.C_AAT);
        assertNotNull(aat, "有回放结果时，AAT 解释必须存在");
        assertEquals(0, aat.severity(),
                "已成熟的作物不应报严重度（高温发育加速会让积温比 < 1 也成熟）");
        assertTrue(aat.quantity().contains("52%") || aat.quantity().contains("51%"),
                "量段应如实显示积温比约 52%，实际是：" + aat.quantity());
    }

    /**
     * 🔴 初霜截断的季：积温可能远不够，但那是<b>不可逆</b>的 —— 严重度应最高。
     */
    @Test
    @DisplayName("P1：初霜截断的季严重度为 5（不可逆）")
    void frostStoppedSeasonHasMaxSeverity() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 60, 0.4,
                "2026-05-20", null, null,
                900.0, 0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.3, 0.4),
                true, false, 0);   // frostStop = true

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, null, null, null, 1, 0.0);

        FarmInsightService.Insight aat = find(list, FarmInsightService.C_AAT);
        assertNotNull(aat);
        assertEquals(5, aat.severity(), "初霜截断是不可逆的，严重度应为最高档");
    }

    // ==================================================================
    //  4. 氮素：豆科是反过来的
    // ==================================================================

    /**
     * 🔴 <b>豆科的氮账本与禾谷相反</b>：大豆 60–70% 的氮靠根瘤固氮，
     * 外源氮超过阈值会<b>压制根瘤</b>（氮阻遏）。
     *
     * <p>如果豆科沿用通用话术（施得越多越好），就等于教错。
     * 判定必须走 {@link CropVisual#isLegume(String)}，不能靠形态族
     * （{@code Form.GRAIN} 含 5 种作物，会把大豆和水稻混为一谈）。
     *
     * <p>🔴 <b>对照口径</b>：用<b>各自需求的同一比例</b>（1.6 倍）对比 ——
     * 豆科 1.6 倍 = 氮阻遏（severity 4），禾谷 1.6 倍 = 超量施肥（severity 4）。
     * 两者 severity 相同，但<b>文案与建议完全不同</b>：豆科必须讲根瘤被压制，
     * 禾谷必须讲报酬递减。这才是真正要断言的差异 ——
     * 用 severity 区分两者是错的（两个都该是「有问题」）。
     */
    @Test
    @DisplayName("P1：豆科氮阻遏与禾谷报酬递减的文案完全不同（不能套同一套话术）")
    void legumeUsesNitrogenFixationNarrativeNotGenericOne() {
        SoilEngine.Result soybean = soilResult(2.5, 4.0, 0.0, 0.0);   // 需求的 1.6 倍
        SoilEngine.Result rice = soilResult(11.0, 17.6, 0.0, 0.0);    // 需求的 1.6 倍

        FarmInsightService.Insight soy = find(FarmInsightService.insights(
                SoybeanConfig.summerSoybean(), null, soybean, null, null, 1, 0.0),
                FarmInsightService.C_NITROGEN);
        FarmInsightService.Insight riceIns = find(FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), null, rice, null, null, 1, 0.0),
                FarmInsightService.C_NITROGEN);

        assertNotNull(soy, "有土壤数据时，氮素解释必须存在");
        assertNotNull(riceIns);

        // 🔴 豆科：必须讲根瘤 / 氮阻遏
        assertTrue(soy.advice().contains("根瘤"),
                "豆科的建议应提到根瘤（固氮机制），实际：" + soy.advice());
        assertTrue(soy.principle().contains("根瘤"),
                "豆科的原理应讲根瘤固氮，实际：" + soy.principle());

        // 🔴 禾谷：必须讲报酬递减 / 过量浪费钱（不能讲根瘤）
        assertTrue(riceIns.advice().contains("报酬递减")
                        || riceIns.advice().contains("甜点区")
                        || riceIns.advice().contains("不建议继续加")
                        || riceIns.advice().contains("过量"),
                "禾谷的建议应讲报酬递减 / 过量浪费，实际：" + riceIns.advice());
        assertFalse(riceIns.advice().contains("根瘤"),
                "禾谷不该提根瘤（那是豆科的机制），实际：" + riceIns.advice());
        // 🔴 关键：严重度与文案必须一致 ——
        // 严重度 4（严重）时文案不能说「还差一点到需求量」（那是「不足」的说法）。
        assertFalse(riceIns.advice().contains("还差一点"),
                "施氮 1.6 倍时文案不该说「还差一点到需求量」—— "
                        + "严重度与文案分支错位了，实际：" + riceIns.advice());
        assertEquals(4, riceIns.severity(), "禾谷施氮 1.6 倍应属「明显过量」档");
    }

    /**
     * 🔴 <b>豆科施氮过量确实是严重问题</b>（氮阻遏，severity=4），
     * 而豆科<b>完全没施氮</b>反而不是问题（靠固氮自给，severity=0）。
     *
     * <p>这条单独锁「豆科的严重度方向是反的」——
     * 如果实现里豆科分支写反了（过量=0、没施=4），这条会红。
     */
    @Test
    @DisplayName("P1：豆科「施太多」比「没施」严重得多（严重度方向不能写反）")
    void legumeSeverityDirectionIsReversed() {
        SoilEngine.Result over = soilResult(2.5, 4.0, 0.0, 0.0);    // 1.6 倍 → 氮阻遏
        SoilEngine.Result none = soilResult(2.5, 0.2, 0.0, 0.0);    // 0.08 倍 → 靠固氮自给

        FarmInsightService.Insight overIns = find(FarmInsightService.insights(
                SoybeanConfig.summerSoybean(), null, over, null, null, 1, 0.0),
                FarmInsightService.C_NITROGEN);
        FarmInsightService.Insight noneIns = find(FarmInsightService.insights(
                SoybeanConfig.summerSoybean(), null, none, null, null, 1, 0.0),
                FarmInsightService.C_NITROGEN);

        assertNotNull(overIns);
        assertNotNull(noneIns);
        assertEquals(4, overIns.severity(),
                "豆科施氮 1.6 倍 = 氮阻遏，严重度应为 4");
        assertEquals(0, noneIns.severity(),
                "豆科几乎不施氮（靠根瘤固氮自给）不该是问题，严重度应为 0");
    }

    /**
     * 🔴 施到需求量时，氮素满足度约 83%（不是 100%）—— 这是 Mitscherlich 曲线。
     *
     * <p>这里用<b>真实公式</b>复算：{@code 1 - exp(-1.8 × N/need)}。
     */
    @Test
    @DisplayName("P1：施到需求量时氮素满足度约 83%（Mitscherlich 曲线，非 100%）")
    void nSatisfactionAtNeedIsAbout83Percent() {
        double sat = InputCatalog.nSatisfaction(11.0, 11.0);
        assertEquals(1 - Math.exp(-1.8), sat, 1e-12);
        assertTrue(sat > 0.83 && sat < 0.84,
                "施到需求量时满足度应约 83%，实际 " + sat);
    }

    /**
     * 🔴 报酬递减的「甜点区」：施到需求量（1.0–1.3 倍）严重度应为 1（正常）。
     *
     * <p>施得更多不是更好，但也不是「出问题」—— 只是性价比下降。
     */
    @Test
    @DisplayName("P1：施氮在需求量附近属甜点区（severity=1，不是故障）")
    void nitrogenAtNeedIsSweetSpot() {
        SoilEngine.Result sr = soilResult(11.0, 12.0, 0.0, 0.0);  // 1.09 倍
        FarmInsightService.Insight n = find(FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), null, sr, null, null, 1, 0.0),
                FarmInsightService.C_NITROGEN);
        assertNotNull(n);
        assertEquals(1, n.severity(),
                "施氮在需求量附近是「甜点区」，只是性价比下降，不是故障");
    }

    // ==================================================================
    //  5. 缺数据不编造
    // ==================================================================

    /**
     * 🔴 <b>拿不到引擎数据的概念，整条不出现在列表里</b>。
     *
     * <p>如果返回一条 severity=0 的「解释」，等于说「这一项一切正常」——
     * 而事实是<b>没数据</b>。这正是本项目反复踩过的「没记录」与「是零」混淆。
     */
    @Test
    @DisplayName("P1：缺数据时对应概念整条不出现（不编造『一切正常』）")
    void missingDataMeansConceptAbsentNotZeroSeverity() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 30, 0.3,
                "2026-05-20", null, null,
                1200.0, 0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.2, 0.3),
                false, false, 0);

        // 土壤为 null → 氮素与水分两个概念都拿不到数据
        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, null, null, null, 1, 0.0);

        assertNull(find(list, FarmInsightService.C_NITROGEN),
                "没有土壤数据时，氮素概念必须整条缺席（不能返回 severity=0）");
        assertNull(find(list, FarmInsightService.C_WATER),
                "没有土壤数据时，水分概念必须整条缺席");
        assertNotNull(find(list, FarmInsightService.C_AAT),
                "有回放结果时，AAT 概念必须存在");
    }

    /**
     * 🔴 完全没有作物 → 空列表（前端据此显示「本季暂无解释」）。
     *
     * <p>🔴 <b>但「没有回放结果」不等于「空列表」</b>：氮素与水分两个概念
     * 只依赖土壤数据，没有回放结果时<b>照样能给</b>。
     * 早前实现用 {@code r == null} 整体提前返回，等于「回放结果一缺，
     * 连土壤能回答的问题也答不了」—— 那是把「缺数据」误当成「全都缺」。
     */
    @Test
    @DisplayName("P1：无作物时返回空列表；无回放结果时土壤类解释仍应给出")
    void noCropYieldsEmptyListButSoilInsightsSurviveWithoutReplay() {
        assertTrue(FarmInsightService.insights(null, null, null, null, null, 1, 0.0).isEmpty());

        SoilEngine.Result sr = soilResult(11.0, 8.0, 0.0, 0.0);
        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), null, sr, null, null, 1, 0.0);

        assertNull(find(list, FarmInsightService.C_AAT),
                "没有回放结果时，积温概念必须缺席（不能编造积温进度）");
        assertNotNull(find(list, FarmInsightService.C_NITROGEN),
                "没有回放结果时，氮素解释仍应给出（它只依赖土壤数据）");
        assertNotNull(find(list, FarmInsightService.C_WATER),
                "没有回放结果时，水分解释仍应给出（它只依赖土壤数据）");
    }

    /**
     * 🔴 概念列表必须<b>按严重度降序</b> —— 玩家最先看到的应该是最该处理的。
     */
    @Test
    @DisplayName("P1：解释列表按严重度降序（最该处理的排最前）")
    void insightsAreSortedBySeverityDescending() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 60, 0.5,
                "2026-05-20", null, null,
                1500.0, 0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.3, 0.5),
                false, false, 0);
        // 氮严重不足（ratio 0.1 → severity 4），土壤严重干旱
        SoilEngine.Result sr = soilResult(11.0, 1.0, 0.15, 0.0);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, sr, null, null, 1, 0.0);

        assertTrue(list.size() >= 3, "应至少返回 3 条解释，实际 " + list.size());
        for (int i = 1; i < list.size(); i++) {
            assertTrue(list.get(i - 1).severity() >= list.get(i).severity(),
                    "解释未按严重度降序：" + list.get(i - 1).severity()
                            + " → " + list.get(i).severity());
        }
    }

    // ==================================================================
    //  6. 结构契约（前端按这些字段渲染）
    // ==================================================================

    /**
     * 🔴 <b>每条解释都必须四段齐全 + 带 metrics</b>。
     *
     * <p>前端 {@code FarmInsightPanel.vue} 直接渲染这四段；
     * 缺一段就会出现空白行（看起来像没内容，实际是没数据）。
     */
    @Test
    @DisplayName("P1：每条解释都有完整的四段文案与 metrics")
    void everyInsightHasAllFourSectionsAndMetrics() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", "2026-08-01", null,
                2500.0, 0.0, 0,
                new SimEngine.Damage(0.1, 0.05, 0.02, 0.0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 0);
        SoilEngine.Result sr = soilResult(11.0, 8.0, 0.0, 0.0);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, sr, null, null, 1, 0.0);

        assertFalse(list.isEmpty());
        for (FarmInsightService.Insight ins : list) {
            assertNotNull(ins.conceptId(), "conceptId 不能为 null");
            assertFalse(ins.title().isBlank(), "标题不能为空：" + ins.conceptId());
            assertFalse(ins.observation().isBlank(), "现象段不能为空：" + ins.conceptId());
            assertFalse(ins.quantity().isBlank(), "量段不能为空：" + ins.conceptId());
            assertFalse(ins.principle().isBlank(), "原理段不能为空：" + ins.conceptId());
            assertFalse(ins.advice().isBlank(), "建议段不能为空：" + ins.conceptId());
            assertNotNull(ins.metrics(), "metrics 不能为 null：" + ins.conceptId());
            assertFalse(ins.metrics().isEmpty(), "metrics 不能为空：" + ins.conceptId());
            assertTrue(ins.severity() >= 0 && ins.severity() <= 5,
                    "severity 必须落在 0..5（与 RiskItem.level 同一刻度），实际 "
                            + ins.severity() + " @ " + ins.conceptId());
        }
    }

    /**
     * 🔴 <b>量段必须只含数字与单位，不含解释文字</b>。
     *
     * <p>前端把它渲染成一行等宽数字（{@code fi-quantity}），
     * 混进文字会破坏排版。
     */
    @Test
    @DisplayName("P1：量段只含数字与单位（不含解释文字）")
    void quantitySectionContainsOnlyNumbersAndUnits() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", null, null,
                2500.0, 0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 0);
        SoilEngine.Result sr = soilResult(11.0, 8.0, 0.0, 0.0);

        for (FarmInsightService.Insight ins : FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, sr, null, null, 1, 0.0)) {
            // 不应包含中文句子性字符（。，；！？与「」）
            assertFalse(ins.quantity().contains("。"), "量段含句号：" + ins.quantity());
            assertFalse(ins.quantity().contains("，"), "量段含逗号：" + ins.quantity());
            assertFalse(ins.quantity().contains("；"), "量段含分号：" + ins.quantity());
            assertFalse(ins.quantity().contains("！"), "量段含感叹号：" + ins.quantity());
        }
    }

    /**
     * 🔴 <b>所有文案分支都必须能被 {@code String.format} 安全渲染</b>。
     *
     * <p>真实事故 1：豆科建议文案里写了「根瘤菌剂（{@code RHIZOBIUM}）拌种」——
     * {@code {@code ...}} 是 Javadoc 标记，出现在<b>运行时字符串</b>里会被
     * {@code String.format} 当成格式占位符，抛
     * {@code IllegalFormatConversionException}。
     *
     * <p>真实事故 2：豆科原理段写的是 {@code %.0f%%} 但实参传了<b>整型字面量</b> {@code 20}
     * → {@code IllegalFormatConversionException: f != java.lang.Integer}。
     *
     * <p>🔴 <b>这两个 bug 极隐蔽</b>：编译期完全正常，只在<b>走到那一行</b>时
     * （豆科 + 施氮过量 / 豆科原理段）才抛异常 —— 大部分测试跑不到。
     *
     * <p>所以这里<b>遍历所有分支</b>：{@code insights()} 内部会调 {@code String.format}，
     * 格式符与实参类型不匹配会<b>直接从调用里抛出来</b>，测试自然红。
     * 这是比「事后解析文案」更可靠的守门方式 —— 文案是<b>结果</b>，
     * 格式错误发生在<b>生成</b>过程中。
     */
    @Test
    @DisplayName("P1：遍历所有文案分支均不抛格式异常（防 IllegalFormatConversionException）")
    void allTextBranchesFormatSafely() {
        java.util.Set<Double> nRatios = java.util.Set.of(0.0, 0.3, 0.9, 1.1, 1.5, 3.0);
        int branches = 0;
        for (CropConfig crop : new CropConfig[]{
                RiceConfig.indicaMidSeason(), SoybeanConfig.summerSoybean()}) {
            for (double ratio : nRatios) {
                double nNeed = crop.nNeed();
                SoilEngine.Result sr = soilResult(nNeed, nNeed * ratio, 0.05, 0.02);
                // 🔴 这一行就是断言本身：格式错会在这里抛 IllegalFormatConversionException
                List<FarmInsightService.Insight> list = FarmInsightService.insights(
                        crop, null, sr, null, null, 1, 0.0);
                branches++;
                for (FarmInsightService.Insight ins : list) {
                    assertNoBraces(ins.observation(), ins.conceptId());
                    assertNoBraces(ins.quantity(), ins.conceptId());
                    assertNoBraces(ins.principle(), ins.conceptId());
                    assertNoBraces(ins.advice(), ins.conceptId());
                    assertNoBraces(ins.title(), ins.conceptId());
                    // 渲染后的文案不该残留未替换的格式符
                    assertFalse(ins.observation().contains("%f")
                                    || ins.observation().contains("%d")
                                    || ins.observation().contains("%s")
                                    || ins.observation().contains("%.0"),
                            "文案里残留了未替换的格式符：" + ins.observation());
                }
            }
        }
        assertTrue(branches >= 12, "应覆盖到全部施氮比例分支，实际 " + branches);
    }

    private static void assertNoBraces(String text, String conceptId) {
        assertTrue(text == null || (!text.contains("{") && !text.contains("}")),
                "文案里不能有花括号（会被 String.format 当占位符）：[" + conceptId + "] " + text);
    }

    // ==================================================================
    //  4. P2 新增的 4 个概念（地力轮作 / 热害 / 春化 / PHI）
    // ==================================================================

    /**
     * 🔴 <b>4 个新概念不能只是「有数据就出，没数据就消失」</b>。
     *
     * <p>P2 把农业科学科目扩到 8 章，这 4 章的内容已经写进课程，
     * 但如果解释层讲不出这 4 个概念，就会出现「章节口径」与「面板口径」分叉：
     * 面板上永远看不到这 4 个概念，玩家会以为这 4 章是空话。
     *
     * <p>所以这里逐个用<b>能触发该概念的真实数据</b>断言它存在。
     */
    @Test
    @DisplayName("P2：地力轮作 / 热害 / 春化 / PHI 四个概念在对应数据下都会出现")
    void p2NewConceptsAppearWithRealData() {
        // 地力：土壤结果 + 连作 3 季
        // 热害：有超最适温的日子（heatForce > 0, daysOverOpt > 0）
        // 春化：需要春化的作物（小麦）且春化天数不足
        // PHI：有违规施药
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", null, null,
                2500.0, 12.5, 6,          // heatForce / daysOverOpt → 热害
                new SimEngine.Damage(0.25, 0.05, 0.02, 0.0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 5);          // vernalizationDays=5
        SoilEngine.Result sr = soilResult(11.0, 3.0, 0.0, 0.0);

        FarmInsightService.PhiInfo phi = new FarmInsightService.PhiInfo(
                true, 1, 6, "稻瘟灵", 15);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                WheatConfig.winterWheat(), r, sr, null, null, 4, 0.0, phi);

        assertNotNull(find(list, FarmInsightService.C_SOIL_ROTATION),
                "有土壤数据时，地力轮作概念必须出现");
        assertNotNull(find(list, FarmInsightService.C_HEAT_STRESS),
                "有超最适温天数时，热害概念必须出现");
        assertNotNull(find(list, FarmInsightService.C_VERNALIZATION),
                "越冬作物必须有春化概念");
        assertNotNull(find(list, FarmInsightService.C_PHI),
                "有 PHI 违规时，安全间隔期概念必须出现");
    }

    /**
     * 🔴 <b>PHI 概念在「从未施药」时必须整条缺席</b>。
     *
     * <p>这是「缺数据 ≠ 零值」纪律：没施过药就没有 PHI 这回事，
     * 显示一条「违规 0 次」的 PHI 解释是在编造一个不存在的问题。
     */
    @Test
    @DisplayName("P2：从未施药时 PHI 概念整条缺席（不编造「违规 0 次」）")
    void phiConceptAbsentWhenNeverSprayed() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", null, null,
                2500.0, 0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 0);
        SoilEngine.Result sr = soilResult(11.0, 8.0, 0.0, 0.0);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, sr, null, null, 1, 0.0,
                FarmInsightService.PhiInfo.NONE);

        assertNull(find(list, FarmInsightService.C_PHI),
                "从未施药却出现 PHI 解释 —— 那是编造了一个不存在的问题");
    }

    /**
     * 🔴 <b>一季没遇到高温，热害概念整条缺席</b>。
     *
     * <p>同理：{@code daysOverOpt <= 0 && heatForce <= 0} 时
     * 显示「热害 0 风险」等于凭空造了一个概念。
     */
    @Test
    @DisplayName("P2：一季没遇到超最适温时热害概念整条缺席")
    void heatStressConceptAbsentWithoutHeat() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", null, null,
                2500.0, 0.0, 0,
                new SimEngine.Damage(0, 0, 0, 0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 0);
        SoilEngine.Result sr = soilResult(11.0, 8.0, 0.0, 0.0);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                RiceConfig.indicaMidSeason(), r, sr, null, null, 1, 0.0);

        assertNull(find(list, FarmInsightService.C_HEAT_STRESS),
                "没遇到高温却出现热害解释 —— 那是编造了一个不存在的问题");
    }

    /**
     * 🔴 <b>4 个新概念的文案也必须过「四段式 + 可被 String.format 渲染」的关</b>。
     *
     * <p>新增概念容易只补数据不补文案检查 —— 结果就是某个分支里
     * 少一个实参，运行时才炸。这里对 4 个概念逐个走一遍
     * 「现象/量/原理/建议 都非空 + 不含花括号」。
     */
    @Test
    @DisplayName("P2：4 个新概念的四段式文案齐全且可安全渲染")
    void p2NewConceptsHaveCompleteSections() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", null, null,
                2500.0, 12.5, 6,
                new SimEngine.Damage(0.25, 0.05, 0.02, 0.0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 5);
        SoilEngine.Result sr = soilResult(11.0, 3.0, 0.0, 0.0);
        FarmInsightService.PhiInfo phi = new FarmInsightService.PhiInfo(
                true, 1, 6, "稻瘟灵", 15);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                WheatConfig.winterWheat(), r, sr, null, null, 4, 0.0, phi);

        String[] newConcepts = {
                FarmInsightService.C_SOIL_ROTATION,
                FarmInsightService.C_HEAT_STRESS,
                FarmInsightService.C_VERNALIZATION,
                FarmInsightService.C_PHI};

        for (String cid : newConcepts) {
            FarmInsightService.Insight ins = find(list, cid);
            assertNotNull(ins, "概念 " + cid + " 必须出现");
            assertFalse(ins.title().isBlank(), "标题不能为空：" + cid);
            assertFalse(ins.observation().isBlank(), "现象段不能为空：" + cid);
            assertFalse(ins.quantity().isBlank(), "量段不能为空：" + cid);
            assertFalse(ins.principle().isBlank(), "原理段不能为空：" + cid);
            assertFalse(ins.advice().isBlank(), "建议段不能为空：" + cid);
            assertNotNull(ins.metrics(), "metrics 不能为 null：" + cid);
            assertFalse(ins.metrics().isEmpty(), "metrics 不能为空：" + cid);
            assertTrue(ins.severity() >= 0 && ins.severity() <= 5,
                    "severity 必须落在 0..5，实际 " + ins.severity() + " @ " + cid);
            assertNoBraces(ins.observation(), cid);
            assertNoBraces(ins.quantity(), cid);
            assertNoBraces(ins.principle(), cid);
            assertNoBraces(ins.advice(), cid);
            assertNoBraces(ins.title(), cid);
        }
    }

    // ==================================================================
    //  工具
    // ==================================================================

    /**
     * 🔴 <b>4 个新概念必须带 0..1 的归一化指标</b>（2026-10-28 P2 收尾）。
     *
     * <p><b>为什么这是硬约束</b>：前端的进度条只认 0..1 的比值。
     * 早期这 4 个概念只下发了绝对量（{@code sfi}=76、{@code soilCoef}=0.90、
     * {@code vernalizationDays}=12、{@code phiDaysShort}=5），
     * 于是前端要么画不出条，要么<b>自己编阈值</b>（如「地力 60 分算好」）——
     * 后者等于<b>在前端复制一份农学口径</b>，迟早与后端分叉，
     * 而且分叉后不会报错，只会悄悄显示错误的结论。
     *
     * <p>所以归一化必须在<b>后端</b>做完再下发，且分母全是引擎已有的量：
     * <ul>
     *   <li>{@code sfiRatio} = sfi / 100（地力指数的定义域上界）；</li>
     *   <li>{@code soilCoefRatio} = (coef − 0.60) / 0.40（soilCoef 公式的定义域）；</li>
     *   <li>{@code heatForceRatio} = heatForce / aatReq（引擎算发育加速的输入）；</li>
     *   <li>{@code vernalizationRatio} = 已积累 / 需求（引擎算发育推进的输入）；</li>
     *   <li>{@code phiShortRatio} = 缺口天数 / 该药要求间隔天数。</li>
     * </ul>
     */
    @Test
    @DisplayName("P2 收尾：4 个新概念都带 0..1 归一化指标（前端不用自己编阈值）")
    void p2NewConceptsExposeNormalizedRatios() {
        SimEngine.SimResult r = new SimEngine.SimResult(
                3850, 80, 0.6,
                "2026-05-20", null, null,
                2500.0, 12.5, 6,
                new SimEngine.Damage(0.25, 0.05, 0.02, 0.0),
                100000, 150, 0.8, 120, 27.0, 0.0, 0.0,
                List.of(0.1, 0.4, 0.6),
                false, false, 5);
        SoilEngine.Result sr = soilResult(11.0, 3.0, 0.0, 0.0);
        FarmInsightService.PhiInfo phi = new FarmInsightService.PhiInfo(
                true, 1, 6, "稻瘟灵", 15);

        List<FarmInsightService.Insight> list = FarmInsightService.insights(
                WheatConfig.winterWheat(), r, sr, null, null, 4, 0.0, phi);

        // ---- 地力：sfiRatio 与 soilCoefRatio ----
        FarmInsightService.Insight soil = find(list, FarmInsightService.C_SOIL_ROTATION);
        assertNotNull(soil, "地力概念必须出现");
        double sfi = soil.metrics().get("sfi");
        double sfiRatio = soil.metrics().get("sfiRatio");
        assertNotNull(sfiRatio, "地力概念缺少 sfiRatio —— 前端只能自己编阈值");
        assertEquals(sfi / 100.0, sfiRatio, 1e-9,
                "sfiRatio 必须等于 sfi/100（地力指数的定义域上界）");
        double coef = soil.metrics().get("soilCoef");
        double coefRatio = soil.metrics().get("soilCoefRatio");
        assertNotNull(coefRatio, "地力概念缺少 soilCoefRatio");
        assertEquals((coef - 0.60) / 0.40, coefRatio, 1e-9,
                "soilCoefRatio 必须等于 (coef−0.60)/0.40（soilCoef 公式的定义域）");

        // ---- 热害：heatForceRatio = heatForce / aatReq ----
        FarmInsightService.Insight heat = find(list, FarmInsightService.C_HEAT_STRESS);
        assertNotNull(heat, "热害概念必须出现");
        double heatForceRatio = heat.metrics().get("heatForceRatio");
        assertNotNull(heatForceRatio, "热害概念缺少 heatForceRatio");
        // 🔴 SimResult 的字段顺序是 aatReq / days / dvs / sowDate / headingDate / matureDate /
        //    aatCum / heatForce / daysOverOpt —— 这里 heatForce = 12.5，aatReq = 3850。
        assertEquals(12.5 / 3850.0, heatForceRatio, 1e-9,
                "heatForceRatio 必须等于 heatForce/aatReq（与引擎 f_dev_T 的输入一致）");

        // ---- 春化：vernalizationRatio = 已积累 / 需求 ----
        FarmInsightService.Insight vern = find(list, FarmInsightService.C_VERNALIZATION);
        assertNotNull(vern, "春化概念必须出现");
        double vernRatio = vern.metrics().get("vernalizationRatio");
        assertNotNull(vernRatio, "春化概念缺少 vernalizationRatio");
        // 小麦冬性品种 vernalizationDays 需求 > 0，引擎已积累 5 天
        double vernDays = vern.metrics().get("vernalizationDays");
        double vernReq = vern.metrics().get("vernalizationReq");
        assertTrue(vernReq > 0, "小麦应有春化需求");
        assertEquals(Math.min(1.0, vernDays / vernReq), vernRatio, 1e-9,
                "vernalizationRatio 必须等于 已积累/需求（与引擎 vc 同一式子）");

        // ---- PHI：phiShortRatio = 缺口 / 该药要求间隔 ----
        FarmInsightService.Insight phiIns = find(list, FarmInsightService.C_PHI);
        assertNotNull(phiIns, "PHI 概念必须出现");
        double shortRatio = phiIns.metrics().get("phiShortRatio");
        assertNotNull(shortRatio, "PHI 概念缺少 phiShortRatio");
        assertEquals(6.0 / 15.0, shortRatio, 1e-9,
                "phiShortRatio 必须等于 缺口天数/该药要求间隔天数");

        // ---- 所有归一化键必须落在 0..1（前端进度条直接用它 ×100）----
        String[] ratioKeys = {"sfiRatio", "soilCoefRatio", "heatForceRatio",
                "vernalizationRatio", "phiShortRatio"};
        String[] concepts = {
                FarmInsightService.C_SOIL_ROTATION, FarmInsightService.C_HEAT_STRESS,
                FarmInsightService.C_VERNALIZATION, FarmInsightService.C_PHI};
        for (String cid : concepts) {
            FarmInsightService.Insight ins = find(list, cid);
            for (String k : ratioKeys) {
                Double v = ins.metrics().get(k);
                if (v == null) {
                    continue;
                }
                assertTrue(v >= 0.0 && v <= 1.0,
                        cid + " 的 " + k + " 必须落在 0..1，实际 " + v);
            }
        }
    }

    private static FarmInsightService.Insight find(List<FarmInsightService.Insight> list,
                                                   String conceptId) {
        for (FarmInsightService.Insight ins : list) {
            if (conceptId.equals(ins.conceptId())) {
                return ins;
            }
        }
        return null;
    }

    /** 造一个土壤结果（只填解释层关心的字段）。 */
    private static SoilEngine.Result soilResult(double nNeed, double nApplied,
                                                 double droughtLoss, double waterlogLoss) {
        double sat = InputCatalog.nSatisfaction(nApplied, nNeed);
        double sfi = 52 + 26.0 * sat;
        return new SoilEngine.Result(
                40.0, 0.8, "MOIST", 0, 0,
                sfi, 0.60 + 0.40 * (sfi / 100.0),
                droughtLoss, waterlogLoss,
                3.0, 2.0, null,
                nApplied, nNeed, sat);
    }

    private static double clamp01(double v, double max) {
        return Math.max(0, Math.min(max, v));
    }
}
