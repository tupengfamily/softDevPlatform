package com.learning.springboot.farm.achievement;

import com.learning.springboot.farm.FarmSeason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 成就引擎单元测试（纯计算，不启 Spring 容器）。
 *
 * <p>🔴 <b>这些测试锁的是「口径」而不是「数字」</b>：
 * 口径错了（比如连季按全局时间线统计、图鉴把失败的一季算成收集到），
 * 数字再好看也是错的，而且要等玩家投诉才发现。
 */
class AchievementEngineTest {

    // ==================================================================
    //  测试夹具
    // ==================================================================

    /** 22 种作物的参照产量（与 CropCatalog 保持一致，抽几个代表性的即可）。 */
    private static final Map<String, Double> REFS = refsOf(
            "rice_mid", 600.0,
            "wheat_winter", 400.0,
            "cabbage", 5000.0,
            "tomato", 4500.0,
            "tulip", 500.0);

    private static Map<String, Double> refsOf(Object... kv) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], (Double) kv[i + 1]);
        }
        return m;
    }

    /** 造一条季节档案。 */
    private static FarmSeason season(Long plotId, int seasonNo, String cropId,
                                     FarmSeason.Status status, Double actualYield,
                                     Double revenue, Double cost) {
        FarmSeason s = new FarmSeason();
        s.setUserId(1L);
        s.setPlotId(plotId);
        s.setSlot(0);
        s.setSeasonNo(seasonNo);
        s.setCropId(cropId);
        s.setSowDate(LocalDate.of(2026, 3, 1).plusDays(seasonNo * 30L));
        if (status == FarmSeason.Status.HARVESTED) {
            s.setHarvestDate(LocalDate.of(2026, 6, 1).plusDays(seasonNo * 30L));
        }
        s.setStatus(status);
        s.setYieldKgPerMu(actualYield);
        s.setYieldRaw(actualYield == null ? null : actualYield * 1.2);
        s.setRevenue(revenue);
        s.setInputCost(cost);
        return s;
    }

    private static FarmSeason harvested(Long plotId, int no, String crop, double actualYield) {
        return season(plotId, no, crop, FarmSeason.Status.HARVESTED, actualYield,
                actualYield * 2.0, 100.0);
    }

    private static AchievementEngine.Stats stats(List<FarmSeason> seasons) {
        return new AchievementEngine.Stats(seasons, REFS, 22);
    }

    private static AchievementEngine.Achievement find(List<AchievementEngine.Achievement> list, String code) {
        return list.stream().filter(a -> a.code().equals(code)).findFirst().orElseThrow();
    }

    // ==================================================================
    //  1. 基础解锁
    // ==================================================================

    @Test
    @DisplayName("空档案：任何成就都不解锁，进度全为 0")
    void emptyProfileUnlocksNothing() {
        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(stats(List.of()));

        assertEquals(AchievementEngine.DEFS.size(), list.size(), "成就条数应与定义一致");
        for (AchievementEngine.Achievement a : list) {
            assertFalse(a.unlocked(), "空档案下 " + a.code() + " 不该解锁");
            assertEquals(0, a.percent(), "空档案下 " + a.code() + " 进度应为 0%");
        }
    }

    @Test
    @DisplayName("首季：播过种即解锁 first_sow，收获才解锁 first_harvest")
    void firstSowAndHarvest() {
        FarmSeason growing = season(1L, 1, "rice_mid", FarmSeason.Status.GROWING, null, null, 50.0);
        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(stats(List.of(growing)));

        assertTrue(find(list, "first_sow").unlocked(), "播过种就该解锁 first_sow");
        assertFalse(find(list, "first_harvest").unlocked(), "还没收获，first_harvest 不该解锁");
        assertFalse(find(list, "harvest_10").unlocked());
    }

    // ==================================================================
    //  2. 🔴 比值口径（跨作物可比）
    // ==================================================================

    @Test
    @DisplayName("🔴 产量成就用比值而非绝对公斤数 —— 白菜收 3000kg 也算达标")
    void yieldAchievementUsesRatioNotAbsoluteKg() {
        // 白菜参照 5000 kg/亩，收 3000 → 比值 0.60，恰好达标
        // 如果错用绝对公斤数「≥1000kg」→ 白菜会轻松通过，水稻（参照 600）永远达不到 0.60*600=360
        List<FarmSeason> seasons = List.of(harvested(1L, 1, "cabbage", 3000.0));

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(0.6, st.bestYieldRatio(), 0.0001, "白菜 3000/5000 应为比值 0.60");

        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(st);
        assertTrue(find(list, "yield_60").unlocked(), "比值 0.60 应解锁 yield_60");
        assertFalse(find(list, "yield_85").unlocked(), "比值 0.60 不该解锁 yield_85");
    }

    @Test
    @DisplayName("🔴 跨作物可比：水稻 540kg（比值 0.9）与白菜 4500kg（比值 0.9）评级相同")
    void ratioIsComparableAcrossCrops() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 540.0),
                harvested(2L, 1, "cabbage", 4500.0));

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(0.9, st.bestYieldRatio(), 0.0001);
        assertTrue(find(AchievementEngine.evaluate(st), "yield_85").unlocked(),
                "两个作物都是 0.9 比值，都应达到优秀线");
    }

    @Test
    @DisplayName("🔴 yieldRaw 与实收是不同口径 —— 判「种得好不好」只能用实收")
    void rawYieldIsNotUsedForAchievement() {
        // 原始产量很高（raw = 0.99 * ref），但实收很低（0.35 * ref）—— 大量损失
        FarmSeason s = new FarmSeason();
        s.setUserId(1L);
        s.setPlotId(1L);
        s.setSeasonNo(1);
        s.setCropId("cabbage");
        s.setSowDate(LocalDate.of(2026, 3, 1));
        s.setHarvestDate(LocalDate.of(2026, 6, 1));
        s.setStatus(FarmSeason.Status.HARVESTED);
        s.setYieldRaw(4950.0);   // raw/ref = 0.99
        s.setYieldKgPerMu(1750.0); // 实收/ref = 0.35
        s.setRevenue(3500.0);
        s.setInputCost(100.0);

        AchievementEngine.Stats st = stats(List.of(s));
        assertEquals(0.35, st.bestYieldRatio(), 0.0001, "成就口径必须是实收比值");
        assertFalse(find(AchievementEngine.evaluate(st), "yield_60").unlocked(),
                "实收只有 0.35，不该算达标（raw 0.99 是理论上限，不是种得好不好）");
    }

    @Test
    @DisplayName("未知作物（参照缺失）不参与任何成就，比值按 0 计")
    void unknownCropDoesNotCount() {
        FarmSeason s = harvested(1L, 1, "not_a_real_crop", 9999.0);
        AchievementEngine.Stats st = stats(List.of(s));

        assertEquals(0.0, st.bestYieldRatio(), 0.0001, "无参照产量时比值应为 0");
        assertFalse(find(AchievementEngine.evaluate(st), "yield_60").unlocked());
    }

    @Test
    @DisplayName("达标季数用于「连续 5 季达标」成就")
    void goodRatioCount() {
        List<FarmSeason> seasons = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            seasons.add(harvested(1L, i, "rice_mid", 400.0)); // 0.667 达标
        }
        seasons.add(harvested(1L, 5, "rice_mid", 300.0));     // 0.50 不达标

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(4L, st.goodRatioCount(0.60), "应只有 4 季达到 0.60");
        assertEquals(0, st.longestGoodStreak(0.60),
                "最新一季不达标 → 从最新往前数立即断开，当前连续为 0");
        assertFalse(find(AchievementEngine.evaluate(st), "yield_perfect_5").unlocked());
    }

    @Test
    @DisplayName("🔴 「连续 5 季达标」是连续口径 —— 中间掉一季就断")
    void yieldPerfectFiveIsConsecutiveNotCumulative() {
        List<FarmSeason> seasons = new ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            seasons.add(harvested(1L, i, "rice_mid", 400.0)); // 0.667 达标
        }
        seasons.add(harvested(1L, 5, "rice_mid", 300.0));     // 0.50 不达标 → 断
        seasons.add(harvested(1L, 6, "rice_mid", 400.0));

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(5L, st.goodRatioCount(0.60), "累计达标 5 季");
        assertEquals(1, st.longestGoodStreak(0.60), "但当前连续只有 1 季");
        assertFalse(find(AchievementEngine.evaluate(st), "yield_perfect_5").unlocked(),
                "累计够 5 季但连续不够，不该解锁");
    }

    // ==================================================================
    //  3. 🔴 连季不断茬（按地块）
    // ==================================================================

    @Test
    @DisplayName("🔴 连季按地块统计 —— 两块地交错推进不互相拖累")
    void streakIsCountedPerPlot() {
        // 地块 1：成功、成功、成功（3 连）
        // 地块 2：失败、成功
        // 如果错按全局时间线统计，交错排列会得出「连不上」
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                season(2L, 1, "rice_mid", FarmSeason.Status.FAILED, null, null, 80.0),
                harvested(1L, 2, "rice_mid", 500.0),
                harvested(2L, 2, "rice_mid", 500.0),
                harvested(1L, 3, "rice_mid", 500.0));

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(3, st.longestStreak(), "地块 1 应有 3 连季");
        assertTrue(find(AchievementEngine.evaluate(st), "streak_3").unlocked());
        assertFalse(find(AchievementEngine.evaluate(st), "streak_5").unlocked());
    }

    @Test
    @DisplayName("🔴 连季是「当前连续」—— 断过茬后进度回退，不留历史最长段")
    void streakIsCurrentNotHistoricalMax() {
        // 先连成 3 季，再失败一季，然后重种成 1 季
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                harvested(1L, 2, "rice_mid", 500.0),
                harvested(1L, 3, "rice_mid", 500.0),
                season(1L, 4, "rice_mid", FarmSeason.Status.FAILED, null, null, 80.0),
                harvested(1L, 5, "rice_mid", 500.0));

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(1, st.longestStreak(),
                "断茬后当前连续只有 1 季；若返回 3 说明用的是历史最长段，成就就失去约束力");

        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(st);
        assertFalse(find(list, "streak_3").unlocked(),
                "断了茬就不该再算「3 连季」");
    }

    @Test
    @DisplayName("🔴 在长（GROWING）的一季不算断茬 —— 结果还没出来")
    void growingSeasonDoesNotBreakStreak() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                harvested(1L, 2, "rice_mid", 500.0),
                season(1L, 3, "rice_mid", FarmSeason.Status.GROWING, null, null, 50.0));

        assertEquals(2, stats(seasons).longestStreak(),
                "在长的一季跳过不计，但不能因此把已有 2 连切断");
    }

    @Test
    @DisplayName("🔴 断茬把 2 连切成两段，当前连续段重新计数")
    void failedSeasonBreaksStreak() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                harvested(1L, 2, "rice_mid", 500.0),
                season(1L, 3, "rice_mid", FarmSeason.Status.FAILED, null, null, 80.0),
                harvested(1L, 4, "rice_mid", 500.0));

        assertEquals(1, stats(seasons).longestStreak(),
                "失败一季把连段切断，从最新一季往前只数得到 1");
    }

    @Test
    @DisplayName("放弃（ABANDONED）同样算断茬")
    void abandonedSeasonBreaksStreak() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                harvested(1L, 2, "rice_mid", 500.0),
                season(1L, 3, "rice_mid", FarmSeason.Status.ABANDONED, null, null, 80.0),
                harvested(1L, 4, "rice_mid", 500.0));

        assertEquals(1, stats(seasons).longestStreak());
    }

    @Test
    @DisplayName("5 连季解锁 streak_5")
    void fiveStreakUnlocks() {
        List<FarmSeason> seasons = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            seasons.add(harvested(1L, i, "rice_mid", 500.0));
        }
        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(stats(seasons));
        assertTrue(find(list, "streak_3").unlocked());
        assertTrue(find(list, "streak_5").unlocked());
    }

    // ==================================================================
    //  4. 🔴 图鉴只认正常收获
    // ==================================================================

    @Test
    @DisplayName("🔴 图鉴只认 HARVESTED —— 失败的一季不算收集到")
    void albumOnlyCountsHarvestedSeasons() {
        List<FarmSeason> seasons = List.of(
                season(1L, 1, "cabbage", FarmSeason.Status.FAILED, null, null, 80.0),
                season(2L, 1, "tomato", FarmSeason.Status.ABANDONED, null, null, 80.0));

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(0, st.distinctHarvestedCrops(), "失败/放弃不算收集到");
        assertFalse(find(AchievementEngine.evaluate(st), "crops_5").unlocked());
    }

    @Test
    @DisplayName("收获过的作物才进图鉴，且去重")
    void albumCountsDistinctHarvestedCrops() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "cabbage", 3000.0),
                harvested(2L, 1, "cabbage", 2500.0),
                harvested(1L, 2, "tomato", 2000.0));

        assertEquals(2, stats(seasons).distinctHarvestedCrops(), "同作物多次只算一种");
    }

    // ==================================================================
    //  5. 经营类
    // ==================================================================

    @Test
    @DisplayName("净收益 = 营收 - 投入；失败季只扣投入不加营收")
    void netProfitAccounting() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 600.0),   // 营收 1200 - 投入 100 = 1100
                season(2L, 1, "rice_mid", FarmSeason.Status.FAILED, null, null, 200.0)); // -200

        AchievementEngine.Stats st = stats(seasons);
        assertEquals(900.0, st.totalNet(), 0.001, "累计净收益 = 1100 - 200");
        assertEquals(1100.0, st.bestNet(), 0.001, "单季最高净收益只看成功季");
    }

    @Test
    @DisplayName("盈利类成就：单季赚过钱解锁 profit_first，累计到 1 万解锁 profit_10000")
    void profitAchievements() {
        FarmSeason s = new FarmSeason();
        s.setUserId(1L);
        s.setPlotId(1L);
        s.setSeasonNo(1);
        s.setCropId("rice_mid");
        s.setSowDate(LocalDate.of(2026, 3, 1));
        s.setHarvestDate(LocalDate.of(2026, 6, 1));
        s.setStatus(FarmSeason.Status.HARVESTED);
        s.setYieldKgPerMu(600.0);
        s.setRevenue(20000.0);
        s.setInputCost(500.0);

        AchievementEngine.Stats st = stats(List.of(s));
        assertEquals(19500.0, st.totalNet(), 0.001);

        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(st);
        assertTrue(find(list, "profit_first").unlocked());
        assertTrue(find(list, "profit_10000").unlocked());
        assertFalse(find(list, "profit_50000").unlocked());
    }

    @Test
    @DisplayName("🔴 profit_first 是开关型 —— 进度不该显示成「¥7119 / ¥1」")
    void profitFirstIsFlagNotAmount() {
        FarmSeason s = new FarmSeason();
        s.setUserId(1L);
        s.setPlotId(1L);
        s.setSeasonNo(1);
        s.setCropId("rice_mid");
        s.setSowDate(LocalDate.of(2026, 3, 1));
        s.setHarvestDate(LocalDate.of(2026, 6, 1));
        s.setStatus(FarmSeason.Status.HARVESTED);
        s.setYieldKgPerMu(600.0);
        s.setRevenue(20000.0);
        s.setInputCost(500.0);

        AchievementEngine.Achievement pf = find(AchievementEngine.evaluate(stats(List.of(s))), "profit_first");
        assertEquals("flag", pf.unit(), "开关型成就单位必须是 flag，不是 cny");
        assertEquals(1.0, pf.progress(), 0.0001, "进度是「有/无」，不是金额");
        assertTrue(pf.unlocked());
    }

    @Test
    @DisplayName("🔴 没赚过钱 → profit_first 不解锁")
    void profitFirstRequiresPositiveNet() {
        // 营收 0、投入 200 → 净收益 -200（亏了）
        FarmSeason s = new FarmSeason();
        s.setUserId(1L);
        s.setPlotId(1L);
        s.setSeasonNo(1);
        s.setCropId("rice_mid");
        s.setSowDate(LocalDate.of(2026, 3, 1));
        s.setHarvestDate(LocalDate.of(2026, 6, 1));
        s.setStatus(FarmSeason.Status.HARVESTED);
        s.setYieldKgPerMu(100.0);
        s.setRevenue(0.0);
        s.setInputCost(200.0);

        AchievementEngine.Stats st = stats(List.of(s));
        assertEquals(-200.0, st.totalNet(), 0.001);
        assertFalse(find(AchievementEngine.evaluate(st), "profit_first").unlocked(),
                "亏钱的季不算「赚到过钱」");
    }

    // ==================================================================
    //  6. 通用不变式
    // ==================================================================

    @Test
    @DisplayName("每条成就的进度百分比都被夹在 0..100")
    void percentIsClamped() {
        // 超额完成：净收益 999999
        FarmSeason s = harvested(1L, 1, "rice_mid", 600.0);
        s.setRevenue(999999.0);
        s.setInputCost(0.0);

        for (AchievementEngine.Achievement a : AchievementEngine.evaluate(stats(List.of(s)))) {
            assertTrue(a.percent() >= 0 && a.percent() <= 100,
                    a.code() + " 百分比应在 0..100，实际 " + a.percent());
        }
    }

    @Test
    @DisplayName("达成状态与「进度 ≥ 目标」严格一致")
    void unlockedMatchesProgressAgainstTarget() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 570.0),  // 0.95
                harvested(1L, 2, "cabbage", 4250.0)); // 0.85
        for (AchievementEngine.Achievement a : AchievementEngine.evaluate(stats(seasons))) {
            assertEquals(a.progress() >= a.target(), a.unlocked(),
                    a.code() + " 的 unlocked 与进度/目标不自洽");
        }
    }

    @Test
    @DisplayName("成就列表顺序稳定（与定义顺序一致）")
    void achievementOrderIsStable() {
        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(stats(List.of()));
        for (int i = 0; i < AchievementEngine.DEFS.size(); i++) {
            assertEquals(AchievementEngine.DEFS.get(i).code(), list.get(i).code());
        }
    }

    @Test
    @DisplayName("同一份档案重算两次结果完全一致（纯函数性质）")
    void evaluationIsPure() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                season(2L, 1, "rice_mid", FarmSeason.Status.FAILED, null, null, 80.0));

        List<AchievementEngine.Achievement> a1 = AchievementEngine.evaluate(stats(seasons));
        List<AchievementEngine.Achievement> a2 = AchievementEngine.evaluate(stats(seasons));
        assertEquals(a1.size(), a2.size());
        for (int i = 0; i < a1.size(); i++) {
            assertEquals(a1.get(i).code(), a2.get(i).code());
            assertEquals(a1.get(i).progress(), a2.get(i).progress(), 0.0001);
            assertEquals(a1.get(i).unlocked(), a2.get(i).unlocked());
        }
    }

    @Test
    @DisplayName("概览汇总数字与成就列表一致")
    void summaryMatchesAchievements() {
        List<FarmSeason> seasons = List.of(
                harvested(1L, 1, "rice_mid", 500.0),
                harvested(2L, 1, "cabbage", 3000.0));

        List<AchievementEngine.Achievement> list = AchievementEngine.evaluate(stats(seasons));
        AchievementEngine.Summary s = AchievementEngine.summarize(list, stats(seasons));

        long unlocked = list.stream().filter(AchievementEngine.Achievement::unlocked).count();
        assertEquals(unlocked, s.unlockedCount());
        assertEquals(list.size(), s.totalCount());
        assertEquals(2, s.cropsCollected());
        assertEquals(22, s.cropsTotal());
        assertEquals(2L, s.harvestedCount());
    }
}
