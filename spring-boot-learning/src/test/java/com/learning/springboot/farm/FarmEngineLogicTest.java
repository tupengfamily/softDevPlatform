package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.DayWeather;
import com.learning.springboot.farm.engine.PestEngine;
import com.learning.springboot.farm.engine.SimEngine;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.CropConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 「稼穑」核心玩法逻辑回归（S12 修复批）。
 *
 * <p>覆盖本次审查落地修复的 6 个点：
 * <ol>
 *   <li>预防性打药必须有效（保护期不再依赖"病虫是否已发生"）；</li>
 *   <li>打错药仍然只有 5% 误伤（不能因为修 1 就把闸门一放水）；</li>
 *   <li>药害剂量（power=0）不授予保护期；</li>
 *   <li>旧数据（无 itemId）按全靶标兼容，不被判成"打错药"；</li>
 *   <li>工序链：做过的就是 DONE，不因"做得早"被判 MISSED；</li>
 *   <li>积温不足区间（0.55–1.0）必须有连续惩罚，不能与"刚好够"同产。</li>
 * </ol>
 */
class FarmEngineLogicTest {

    // ==================================================================
    //  工具：造一段可控天气
    // ==================================================================

    /** 造 n 天天气：日均 tmean，日较差 10℃，降水 precip。 */
    private static List<DayWeather> weather(int n, double tmean, double precip, LocalDate from) {
        List<DayWeather> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new DayWeather(from.plusDays(i).toString(),
                    tmean + 5, tmean - 5, precip));
        }
        return out;
    }

    /** 逐日 DVS 轨迹（线性爬升到 dvsEnd）。 */
    private static List<Double> dvsTrace(int n, double dvsEnd) {
        List<Double> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(dvsEnd * (i + 1) / n);
        }
        return out;
    }

    // ==================================================================
    //  1. 预防性打药必须有效
    // ==================================================================

    /**
     * 统计「某病虫的初发次数」在多次随机种子下的合计。
     *
     * <p>为什么不用单 seed：病虫初发是逐日伯努利抽样，单个 seed 下
     * "打药组恰好早发病、保护期没赶上"这种偶然会让断言极脆。
     * 跨 seed 聚合才能测出机制的**平均效果**。
     */
    private static final int ONSET_SEEDS = 60;

    private static int onsetCount(List<DayWeather> days, int n, List<Double> trace,
                                  List<PestEngine.Spray> sprays, String pestId) {
        int count = 0;
        for (long seed = 1; seed <= ONSET_SEEDS; seed++) {
            PestEngine.Result r = PestEngine.simulate(days, 0, n, trace, seed, sprays, false, "rice");
            count += r.instances().stream().filter(p -> p.id().equals(pestId)).count();
        }
        return count;
    }

    @Test
    @DisplayName("预防性打药：在显症前施药，稻瘟病初发次数应下降")
    void preventiveSpraySuppressesOnset() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        // 温暖多雨 → 病害三角成立（26℃ 落在稻瘟病 24–28℃；日降水 25mm → 湿度代理 95）
        List<DayWeather> days = weather(40, 26, 25, sow);
        List<Double> trace = dvsTrace(40, 0.6);

        int noSpray = onsetCount(days, 40, trace, List.of(), "blast");
        // 第 5 天打对症药 —— 此时稻瘟病通常尚未显症，属"预防性用药"
        int withSpray = onsetCount(days, 40, trace, List.of(
                new PestEngine.Spray(sow.plusDays(4), "TRICYCLAZOLE", Set.of("blast"), 1.0, 10)), "blast");

        assertTrue(withSpray < noSpray,
                "预防性打药应降低稻瘟病初发次数：打药 " + withSpray + " vs 不打药 " + noSpray);
    }

    @Test
    @DisplayName("保护期对未发生病虫同样生效（查靶标表而非查实例）")
    void protectionCoversUnoccurredPests() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        List<DayWeather> days = weather(40, 26, 25, sow);
        List<Double> trace = dvsTrace(40, 0.6);

        int noSpray = onsetCount(days, 40, trace, List.of(), "blast");
        int sprayed = onsetCount(days, 40, trace, List.of(
                new PestEngine.Spray(sow.plusDays(1), "TRICYCLAZOLE", Set.of("blast"), 1.0, 10)), "blast");

        assertTrue(sprayed <= noSpray,
                "有保护期时初发次数不应高于无保护期：" + sprayed + " vs " + noSpray);
    }

    // ==================================================================
    //  2. 闸门一：打错药仍只有 5% 误伤
    // ==================================================================

    @Test
    @DisplayName("打错药不得优于对症药（闸门一不能放水）")
    void wrongTargetIsWeakerThanRightTarget() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        List<DayWeather> days = weather(45, 28, 25, sow);
        List<Double> trace = dvsTrace(45, 0.7);

        // 稻瘟病是此时最易发生的（24–28℃、日降水 25mm）
        int rightTarget = onsetCount(days, 45, trace, List.of(
                new PestEngine.Spray(sow.plusDays(20), "TRICYCLAZOLE", Set.of("blast"), 1.0, 10)), "blast");
        // 打错药：治稻飞虱的药，对稻瘟病无保护期
        int wrongTarget = onsetCount(days, 45, trace, List.of(
                new PestEngine.Spray(sow.plusDays(20), "IMIDACLOPRID", Set.of("planthopper"), 1.0, 10)), "blast");

        assertTrue(rightTarget <= wrongTarget,
                "对症药的稻瘟病初发次数应不高于打错药：对症 " + rightTarget + " vs 打错 " + wrongTarget);
    }

    // ==================================================================
    //  3. 药害剂量不授予保护期
    // ==================================================================

    @Test
    @DisplayName("power=0（药害剂量）不产生保护期")
    void zeroPowerGrantsNoProtection() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        List<DayWeather> days = weather(40, 26, 25, sow);
        List<Double> trace = dvsTrace(40, 0.6);

        // power = 0：相当于药害剂量（doseFactor 为负 → clamp 到 0）
        int withZero = onsetCount(days, 40, trace, List.of(
                new PestEngine.Spray(sow.plusDays(4), "TRICYCLAZOLE", Set.of("blast"), 0.0, 10)), "blast");
        int without = onsetCount(days, 40, trace, List.of(), "blast");

        // 不打药与"药害剂量"应当没有区别（不能因为交了钱就白拿保护期）
        assertEquals(without, withZero,
                "power=0 不应产生任何保护期效果");
    }

    // ==================================================================
    //  4. 旧数据兼容
    // ==================================================================

    @Test
    @DisplayName("旧数据（无靶标）按全靶标兼容，不被判成打错药")
    void legacySprayCoversAllTargets() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        List<DayWeather> days = weather(40, 26, 25, sow);
        List<Double> trace = dvsTrace(40, 0.6);

        // legacyAllTargets = true + 空靶标 = 全靶标满效力
        int legacyOnsets = 0;
        int asWrongOnsets = 0;
        for (long seed = 1; seed <= 60; seed++) {
            PestEngine.Result legacyRes = PestEngine.simulate(days, 0, 40, trace, seed, List.of(
                    new PestEngine.Spray(sow.plusDays(4), null, Set.of(), 1.0, 10)), true, "rice");
            // 同样的施药但当成"打错药"（legacyAllTargets = false）
            PestEngine.Result asWrong = PestEngine.simulate(days, 0, 40, trace, seed, List.of(
                    new PestEngine.Spray(sow.plusDays(4), null, Set.of(), 1.0, 10)), false, "rice");
            legacyOnsets += legacyRes.instances().stream().filter(p -> p.id().equals("blast")).count();
            asWrongOnsets += asWrong.instances().stream().filter(p -> p.id().equals("blast")).count();
        }

        assertTrue(legacyOnsets <= asWrongOnsets,
                "旧数据按全靶标兼容时初发次数不应高于'打错药'：" + legacyOnsets + " vs " + asWrongOnsets);
    }

    // ==================================================================
    //  5. 工序链：DONE 不掺入时机
    // ==================================================================

    @Test
    @DisplayName("做过的工序就是 DONE（做得早也算数）")
    void doneOperationIsNotMissed() {
        LocalDate sow = LocalDate.of(2024, 5, 20);
        List<Double> trace = dvsTrace(60, 0.5);
        // TILLER_FERT 窗口 0.16–0.34；故意在 DVS 0.15（窗口之前）做
        FarmAction a = new FarmAction();
        a.setType(FarmAction.Type.TILLER_FERT);
        a.setDate(sow.plusDays(0));   // 播种当天 = DVS 0，窗口之前

        List<OpChain.Step> steps = OpChain.build(sow, trace, List.of(a), 0.5, false, "rice_mid");
        OpChain.Step tiller = steps.stream()
                .filter(s -> s.id().equals("TILLER_FERT")).findFirst().orElse(null);
        assertNotNull(tiller, "工序链里应有 TILLER_FERT");
        assertEquals(OpChain.Status.DONE, tiller.status(),
                "已经做过的工序不应被判成 MISSED（否则产量被误扣 6%）");
    }

    @Test
    @DisplayName("没做且窗口已过的工序仍是 MISSED")
    void skippedOperationIsMissed() {
        LocalDate sow = LocalDate.of(2024, 5, 20);
        List<Double> trace = dvsTrace(60, 0.8);
        List<OpChain.Step> steps = OpChain.build(sow, trace, List.of(), 0.8, false, "rice_mid");
        OpChain.Step tiller = steps.stream()
                .filter(s -> s.id().equals("TILLER_FERT")).findFirst().orElse(null);
        assertNotNull(tiller);
        assertEquals(OpChain.Status.MISSED, tiller.status(), "没做且窗口已过 → MISSED");
    }

    @Test
    @DisplayName("施肥工序类型按各作物窗口判定，不用水稻硬编码阈值")
    void fertStepFollowsCropWindow() {
        // 小麦返青肥窗口 0.42–0.52；用水稻阈值会判成 PANICLE_FERT
        assertEquals("TILLER_FERT", OpChain.fertStepFor(0.45, "wheat_winter"));
        // 玉米苗期提苗肥 0.15–0.30
        assertEquals("TILLER_FERT", OpChain.fertStepFor(0.20, "corn_spring"));
        // 瓜类膨瓜肥 0.50–0.75
        assertEquals("PANICLE_FERT", OpChain.fertStepFor(0.60, "cucumber"));
        // 水稻分蘖肥 0.16–0.34
        assertEquals("TILLER_FERT", OpChain.fertStepFor(0.20, "rice_mid"));
        // 水稻穗肥 0.41–0.55
        assertEquals("PANICLE_FERT", OpChain.fertStepFor(0.45, "rice_mid"));
    }

    // ==================================================================
    //  6. 积温不足区间必须有连续惩罚
    // ==================================================================

    @Test
    @DisplayName("积温 0.55–1.0 区间产量必须低于积温刚好够的情况")
    void maturityShortfallHasContinuousPenalty() {
        CropConfig rice = CropCatalog.get("rice_mid").config();
        // 同样的 DVS 轨迹下，比较"成熟"与"没熟但积温 70%"的产量
        // 构造：不给足积温（低温），让 dvs 卡在半程
        LocalDate sow = LocalDate.of(2024, 5, 20);

        // 场景 1：正常温度 → 能成熟
        List<DayWeather> warm = weather(200, 26, 5, sow);
        SimEngine.SimResult mature = SimEngine.simulate(warm, 0, rice);

        // 场景 2：低温 → 积温不足，finalSeason=true 触发未熟惩罚
        List<DayWeather> cold = weather(200, 14, 5, sow);
        SimEngine.SimResult immature = SimEngine.simulate(
                cold, 0, rice, null, 0.0, java.util.Collections.emptyMap(), 200, true);

        assertTrue(mature.matureDate() != null, "常温暖天气应当能成熟");
        assertTrue(immature.matureDate() == null, "低温天气应当不能成熟");

        // 未熟的产量必须明显低于成熟的（修复前 0.55~1.0 区间无惩罚，可能几乎持平）
        assertTrue(immature.ey() < mature.ey() * 0.75,
                "未熟产量应显著低于成熟产量：未熟 " + immature.ey() + " vs 成熟 " + mature.ey());
    }

    @Test
    @DisplayName("没熟且积温极低时无经济产量（grains 归零）")
    void extremeShortfallYieldsNoGrain() {
        CropConfig rice = CropCatalog.get("rice_mid").config();
        LocalDate sow = LocalDate.of(2024, 5, 20);
        List<DayWeather> cold = weather(200, 8, 2, sow);
        SimEngine.SimResult r = SimEngine.simulate(
                cold, 0, rice, null, 0.0, java.util.Collections.emptyMap(), 200, true);
        assertTrue(r.grains() <= 1e-9, "积温不到 55% 时粒数应归零，实际 " + r.grains());
    }

    // ==================================================================
    //  7. 剂量上限与药害阈值必须一致
    // ==================================================================

    @Test
    @DisplayName("药害阈值：超过 1.5 倍推荐量即判为过量")
    void overdoseThresholdIsOnePointFiveTimes() {
        var urea = com.learning.springboot.farm.engine.InputCatalog.get("UREA");
        assertNotNull(urea);
        assertFalse(com.learning.springboot.farm.engine.InputCatalog.overdose(urea, urea.doseOpt()),
                "推荐量不算过量");
        assertTrue(com.learning.springboot.farm.engine.InputCatalog.overdose(urea, urea.doseOpt() * 1.5 + 0.1),
                "超过 1.5 倍推荐量应判过量");
        // 药害剂量下 doseFactor 必须是负的（无脑加倍变负收益）
        assertTrue(com.learning.springboot.farm.engine.InputCatalog.doseFactor(urea, urea.doseOpt() * 2) < 0,
                "2 倍剂量应产生负效力（药害）");
    }

    // ==================================================================
    //  8. 湿度适宜度方向（修复前最隐蔽的一个漏洞）
    // ==================================================================

    @Test
    @DisplayName("湿度达到病害需求下限时适宜度应为 1，而不是 0")
    void humiditySuitabilityIsMonotonic() {
        var blast = com.learning.springboot.farm.engine.PestEngine.RICE.stream()
                .filter(c -> c.id().equals("blast")).findFirst().orElseThrow();
        // 湿度刚好等于需求下限 → 需求已被满足 → 适宜度应为 1
        double atMin = com.learning.springboot.farm.engine.PestEngine.envSuitability(
                blast, 26, blast.humidityMin(), true, 1.0);
        assertTrue(atMin > 0.9,
                "湿度等于需求下限时适宜度应接近 1，实际 " + atMin);
        // 湿度越低越不适宜（单调不增）
        double below = com.learning.springboot.farm.engine.PestEngine.envSuitability(
                blast, 26, blast.humidityMin() - 10, true, 1.0);
        assertTrue(below < atMin, "湿度更低时适宜度应更低");
        // 湿度更高不应反而变差
        double above = com.learning.springboot.farm.engine.PestEngine.envSuitability(
                blast, 26, blast.humidityMin() + 5, true, 1.0);
        assertTrue(above >= atMin, "湿度更高时适宜度不应更低");
    }

    @Test
    @DisplayName("连续阴雨（日降水 5–20mm）下水稻病害必须能发生")
    void diseasesDoOccurInContinuousRain() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        // 12mm/天 = 典型连续阴雨，稻瘟病最易流行的条件
        List<DayWeather> days = weather(60, 26, 12, sow);
        List<Double> trace = dvsTrace(60, 0.7);
        int onsets = 0;
        for (long seed = 1; seed <= 40; seed++) {
            PestEngine.Result r = PestEngine.simulate(days, 0, 60, trace, seed, List.of(), false, "rice");
            onsets += r.instances().stream().filter(p -> p.id().equals("blast")).count();
        }
        assertTrue(onsets > 0,
                "日降水 12mm 的连续阴雨下稻瘟病必须能发生（修复前恒为 0 次）");
    }

    @Test
    @DisplayName("病害发生率必须落在合理区间（不能必然发生，也不能永不发生）")
    void diseaseRateIsBalanced() {
        LocalDate sow = LocalDate.of(2024, 4, 1);
        // 最适宜条件：26℃（稻瘟病 24–28℃）、日降水 25mm
        List<DayWeather> days = weather(60, 26, 25, sow);
        List<Double> trace = dvsTrace(60, 0.7);
        int onsets = onsetCount(days, 60, trace, List.of(), "blast");

        double rate = (double) onsets / ONSET_SEEDS;
        // 上限：不能接近 100%（否则"防治"完全没意义）
        assertTrue(rate < 0.90,
                "最适宜条件下稻瘟病发生率应低于 90%，实际 " + (rate * 100) + "%");
        // 下限：不能太低（否则玩家不打药也没损失）
        assertTrue(rate > 0.30,
                "最适宜条件下稻瘟病发生率应高于 30%，实际 " + (rate * 100) + "%");
    }
}
