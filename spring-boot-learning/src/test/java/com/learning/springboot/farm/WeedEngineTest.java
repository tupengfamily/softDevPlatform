package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.DayWeather;
import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.WeedEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「稼穑」杂草引擎回归（2026-10-22 需求 O）。
 *
 * <p>覆盖 5 个点：
 * <ol>
 *   <li>不除草的旱地，杂草压力会随裸地期累积并升级；</li>
 *   <li>人工除草<b>即时</b>降压（当天就能看到压力掉下来）；</li>
 *   <li>化学除草<b>延迟</b>生效（喷完当天没效果，2 天后才降），并且真的降了；</li>
 *   <li>水田有水层，杂草压力显著低于旱地；</li>
 *   <li>封行后作物郁闭，杂草自然受抑（不再持续升级）。</li>
 * </ol>
 */
class WeedEngineTest {

    private static final LocalDate SOW = LocalDate.of(2026, 4, 1);

    // ==================================================================
    //  工具
    // ==================================================================

    /** 造 n 天天气：日均 tmean，日较差 10℃，降水 precip。 */
    private static List<DayWeather> weather(int n, double tmean, double precip) {
        List<DayWeather> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            out.add(new DayWeather(SOW.plusDays(i).toString(),
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

    private static WeedEngine.Result run(List<DayWeather> days,
                                          int nDays,
                                          double dvsEnd,
                                          List<WeedEngine.WeedControl> controls,
                                          boolean paddy) {
        return WeedEngine.simulate(days, 0, nDays, dvsTrace(nDays, dvsEnd),
                controls, paddy, 20260401L);
    }

    // ==================================================================
    //  1. 不除草 → 杂草累积
    // ==================================================================

    @Test
    @DisplayName("不除草的旱地：杂草压力随裸地期累积并升级")
    void weedsAccumulateInDryField() {
        List<DayWeather> days = weather(40, 22, 8);
        WeedEngine.Result r = run(days, 40, 0.30,
                Collections.emptyList(), false);

        assertTrue(r.pressure() > 0.05,
                "40 天旱地不除草应该已经见到杂草，实际 pressure=" + r.pressure());
        assertNotNull(r.onsetDate(), "应该记录首次出现杂草的日期");
        assertEquals(0, r.manualWeeds());
        assertEquals(0, r.herbicideDays());
        // 旱地争水损失会吃掉一部分产量
        assertTrue(r.waterLoss() > 0, "旱地杂草应该造成争水损失");
    }

    // ==================================================================
    //  2. 人工除草即时生效
    // ==================================================================

    @Test
    @DisplayName("人工除草：当天就把压力压下去（即时生效）")
    void manualWeedIsImmediate() {
        List<DayWeather> days = weather(40, 22, 8);

        WeedEngine.Result none = run(days, 40, 0.30,
                Collections.emptyList(), false);

        // 第 25 天人工除草一次
        List<WeedEngine.WeedControl> ctrls = new ArrayList<>();
        ctrls.add(new WeedEngine.WeedControl(SOW.plusDays(24), true));
        WeedEngine.Result after = run(days, 40, 0.30, ctrls, false);

        assertEquals(1, after.manualWeeds(), "应该记一次人工除草");
        assertTrue(after.pressure() < none.pressure(),
                "人工除草后压力必须低于不除草：after=" + after.pressure()
                        + " none=" + none.pressure());
    }

    @Test
    @DisplayName("人工除草：当天的压力立刻掉，不是等几天")
    void manualWeedSameDayEffect() {
        List<DayWeather> days = weather(30, 22, 8);
        int n = 30;
        List<Double> dvs = dvsTrace(n, 0.30);

        // 前 20 天完全不除草
        WeedEngine.Result noWeed = WeedEngine.simulate(
                days, 0, 20, dvs.subList(0, 20), Collections.emptyList(), false, 1L);

        // 第 20 天（第 21 天）人工除草
        List<WeedEngine.WeedControl> ctrls = new ArrayList<>();
        ctrls.add(new WeedEngine.WeedControl(SOW.plusDays(20), true));
        WeedEngine.Result weeded = WeedEngine.simulate(
                days, 0, 21, dvs.subList(0, 21), ctrls, false, 1L);

        assertTrue(weeded.pressure() < noWeed.pressure() * 0.5,
                "人工除草后当天压力应至少腰斩：weeded=" + weeded.pressure()
                        + " noWeed=" + noWeed.pressure());
    }

    // ==================================================================
    //  3. 化学除草延迟生效且真的降压
    // ==================================================================

    @Test
    @DisplayName("化学除草：延迟 2 天生效，且真的降低压力")
    void herbicideIsDelayedButEffective() {
        List<DayWeather> days = weather(40, 22, 8);
        int n = 40;

        WeedEngine.Result none = run(days, n, 0.30,
                Collections.emptyList(), false);

        List<WeedEngine.WeedControl> ctrls = new ArrayList<>();
        ctrls.add(new WeedEngine.WeedControl(SOW.plusDays(19), false));
        WeedEngine.Result sprayed = run(days, n, 0.30, ctrls, false);

        assertEquals(0, sprayed.manualWeeds());
        assertTrue(sprayed.herbicideDays() > 0, "应该记录药剂保护期天数");
        assertTrue(sprayed.pressure() < none.pressure(),
                "化学除草后压力必须低于不除草：sprayed=" + sprayed.pressure()
                        + " none=" + none.pressure());
    }

    @Test
    @DisplayName("化学除草：喷药当天还没生效，生效日之后才掉")
    void herbicideDelayTiming() {
        List<DayWeather> days = weather(30, 22, 8);
        int n = 30;
        List<Double> dvs = dvsTrace(n, 0.30);

        // 第 10 天喷药（index 10 = 第 11 天）
        List<WeedEngine.WeedControl> ctrls = new ArrayList<>();
        ctrls.add(new WeedEngine.WeedControl(SOW.plusDays(10), false));

        // 只跑到喷药当天（第 11 天）——延迟还没到，压力应当与不除草一致
        WeedEngine.Result atSpray = WeedEngine.simulate(
                days, 0, 11, dvs.subList(0, 11), ctrls, false, 7L);
        WeedEngine.Result noWeedAtSpray = WeedEngine.simulate(
                days, 0, 11, dvs.subList(0, 11), Collections.emptyList(), false, 7L);

        assertEquals(noWeedAtSpray.pressure(), atSpray.pressure(), 1e-6,
                "喷药当天（延迟期内）压力不应变化");

        // 跑到第 13 天（喷药后 2 天）——此时应该已经生效
        WeedEngine.Result afterDelay = WeedEngine.simulate(
                days, 0, 13, dvs.subList(0, 13), ctrls, false, 7L);
        WeedEngine.Result noWeedAfter = WeedEngine.simulate(
                days, 0, 13, dvs.subList(0, 13), Collections.emptyList(), false, 7L);

        assertTrue(afterDelay.pressure() < noWeedAfter.pressure(),
                "延迟期后化学除草应该已经降压：after=" + afterDelay.pressure()
                        + " none=" + noWeedAfter.pressure());
    }

    // ==================================================================
    //  4. 水田 vs 旱地
    // ==================================================================

    @Test
    @DisplayName("水田有水层：杂草压力明显低于旱地")
    void paddyHasLessWeedThanDryField() {
        List<DayWeather> days = weather(40, 22, 8);

        WeedEngine.Result dry = run(days, 40, 0.30,
                Collections.emptyList(), false);
        WeedEngine.Result paddy = run(days, 40, 0.30,
                Collections.emptyList(), true);

        assertTrue(paddy.pressure() < dry.pressure(),
                "水田杂草压力应低于旱地：paddy=" + paddy.pressure()
                        + " dry=" + dry.pressure());
        assertTrue(paddy.waterLoss() < dry.waterLoss(),
                "水田争水损失应低于旱地");
    }

    // ==================================================================
    //  5. 封行后杂草自然受抑
    // ==================================================================

    @Test
    @DisplayName("封行后作物郁闭：杂草不再升级")
    void canopySuppressesWeedAfterClosing() {
        List<DayWeather> days = weather(60, 22, 8);
        int n = 60;

        // 前 30 天裸地期（DVS 到 0.36），后 30 天封行郁闭（DVS 0.36→0.60）
        List<Double> dvs = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            dvs.add(i < 30 ? 0.36 * (i + 1) / 30.0 : 0.36 + 0.24 * (i - 29) / 30.0);
        }

        WeedEngine.Result r = WeedEngine.simulate(
                days, 0, n, dvs, Collections.emptyList(), false, 11L);

        assertNotNull(r.onsetDate());
        // 封行后杂草压力不应该达到「成灾」
        assertFalse(r.level() == WeedEngine.Level.SEVERE,
                "封行后杂草不应成灾，实际 level=" + r.level()
                        + " pressure=" + r.pressure());
    }

    // ==================================================================
    //  6. 分档与文案
    // ==================================================================

    @Test
    @DisplayName("压力分档边界")
    void levelBoundaries() {
        assertEquals(WeedEngine.Level.NONE, WeedEngine.levelOf(0.0));
        assertEquals(WeedEngine.Level.NONE, WeedEngine.levelOf(0.079));
        assertEquals(WeedEngine.Level.LIGHT, WeedEngine.levelOf(0.08));
        assertEquals(WeedEngine.Level.LIGHT, WeedEngine.levelOf(0.249));
        assertEquals(WeedEngine.Level.MEDIUM, WeedEngine.levelOf(0.25));
        assertEquals(WeedEngine.Level.MEDIUM, WeedEngine.levelOf(0.549));
        assertEquals(WeedEngine.Level.SEVERE, WeedEngine.levelOf(0.55));
        assertEquals(WeedEngine.Level.SEVERE, WeedEngine.levelOf(1.0));
    }

    @Test
    @DisplayName("空输入返回 EMPTY（不抛异常）")
    void emptyInput() {
        WeedEngine.Result r = WeedEngine.simulate(
                null, 0, 0, null, null, false, 0L);
        assertEquals(0.0, r.pressure(), 1e-9);
        assertEquals(WeedEngine.Level.NONE, r.level());
        assertNull(r.onsetDate());
    }

    // ==================================================================
    //  农资归类（2026-10-22 需求 O）
    // ==================================================================

    @Test
    @DisplayName("除草剂既不算肥也不算药（不进农药统计 / 不参与 PHI）")
    void herbicideIsNeitherFertNorPest() {
        assertEquals(1, FarmPlayService.inputClassOf(InputCatalog.Kind.FERT));
        assertEquals(2, FarmPlayService.inputClassOf(InputCatalog.Kind.PEST));
        // 关键回归点：原来是「不是 FERT 就是药」的二分法，
        // 除草剂被当成农药 → 农药次数虚增 + lastSpray 撑到除草剂那天 → 误报农残
        assertEquals(0, FarmPlayService.inputClassOf(InputCatalog.Kind.HERB));
    }

    @Test
    @DisplayName("目录里确实有除草剂（否则上面的归类没意义）")
    void herbicideCatalogHasItems() {
        assertFalse(InputCatalog.herbicides().isEmpty(),
                "InputCatalog.herbicides() 为空 —— 悬浮面板的「除」按钮就没有化学除草可用了");
        for (InputCatalog.Item it : InputCatalog.herbicides()) {
            assertEquals(InputCatalog.Kind.HERB, it.kind());
            assertTrue(it.targets().contains("weed"),
                    it.id() + " 的靶标里没有 weed，PestEngine/WeedEngine 匹配不上");
        }
    }
}
