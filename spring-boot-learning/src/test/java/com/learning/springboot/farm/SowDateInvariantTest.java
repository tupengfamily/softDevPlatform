package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.ClimateModel;
import com.learning.springboot.farm.engine.CropCatalog;
import com.learning.springboot.farm.engine.SuitabilityEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「今天」的口径回归（2026-10-23 修复批）。
 *
 * <p><b>背景</b>：演示模式下玩家的世界日期已被推进（实测 2027-11-21），
 * 而现实日期是 2026-10-23。播期相关的代码曾经用 {@code LocalDate.now()}
 * （现实日期）当「今天」，于是算出「2027-09-20」这种<b>已经过去</b>的推荐播期：
 *
 * <pre>
 *   defaultSow(冬小麦, 东北, 现实今天 2026-10-23)
 *     → 9/20 距今 33 天 &gt; 20 天阈值 → 顺延到 2027-09-20
 *   玩家世界日期 2027-11-21 → 跟「2027-09-20」比 → 偏移 +62 天
 *   面板显示：推荐播期 2027-09-20（过去）、偏移 +62 天、风险「播得太晚」
 * </pre>
 *
 * <p>三个自相矛盾同时出现：推荐日期在过去、点「回到推荐播期」会把日期设到过去、
 * 风险文案说「晚了 62 天」却又说「播期选得也合适」。
 *
 * <p><b>不变式</b>：凡是推给玩家的「推荐播期」，都必须 <b>≥ 今天</b>。
 *
 * <h2>🔴 第二批修正（用户反馈「推荐窗口期应该是距今最近的适合日期」）</h2>
 *
 * <p>第一批把「推荐日期在过去」修好了，但<b>锚点的选法</b>仍有问题：
 * 取「该月日的下一次发生」，于是本期窗口<b>尾巴还没过</b>时也跳到明年。
 * 大白菜江南（推荐 8/20、窗口 7/21~9/24），今天 2028-09-20 明明还能种到 9/24，
 * 却被告知要等到 2029 年。
 *
 * <p>本批引入 {@link CropCatalog.Crop#nearestWindow}：锚点 = 第一个
 * <b>窗口末端还没过</b>的那次该月日，窗口起点钳到今天。
 */
class SowDateInvariantTest {

    /** 演示模式下实测到的世界日期（比现实日期晚一年多）。 */
    private static final LocalDate WORLD_TODAY = LocalDate.of(2027, 11, 21);

    // ==================================================================
    //  1. nextSow：该月日的下一次发生，必须 ≥ 今天
    // ==================================================================

    /**
     * 🔴 核心不变式：{@code nextSow} 永远返回一个<b>不在过去</b>的日期。
     *
     * <p>这条如果破了，玩家的日期选择器会显示一个禁用态的默认值，
     * 而且「一键回到推荐播期」按钮会把日期设到过去 —— 那是 2026-10-23 的原始 bug。
     *
     * <p>⚠️ <b>2026-10-23 再修正</b>：{@code nextSow} 现在<b>不再是</b>前端
     * 「最近推荐」的来源 —— 前端改用 {@link CropCatalog.Crop#nearestWindow}，
     * 因为后者在「本期窗口尾巴还没过」时不会跳到明年。
     * 本方法保留为「该月日的下一次发生」这一纯语义的工具，
     * 并作为 {@code nearestWindow} 行为的对照组。
     */
    @Test
    @DisplayName("nextSow：该月日的下一次发生必须 ≥ 今天（跨全年边界也要成立）")
    void nextSowIsNeverInThePast() {
        // 遍历全部 14 种作物 × 4 个农业区 × 全年的每一天
        for (CropCatalog.Crop crop : CropCatalog.list()) {
            for (ClimateModel.Zone zone : ClimateModel.zones().values()) {
                for (int dayOffset = 0; dayOffset < 400; dayOffset++) {
                    LocalDate today = WORLD_TODAY.plusDays(dayOffset);
                    LocalDate next = CropCatalog.nextSow(crop.id(), zone.id(), today);
                    assertTrue(!next.isBefore(today),
                            crop.id() + " @ " + zone.id() + "：today=" + today
                                    + " 时 nextSow 返回了过去的日期 " + next
                                    + " —— 推荐播期不能早于今天");
                }
            }
        }
    }

    /**
     * 回归：2026-10-23 的原始场景。
     *
     * <p>世界日期 2027-11-21，冬小麦在东北的代表播期是 9/20 ——
     * 这一茬是<b>上一季</b>播的、现在正长着，所以「这一季的推荐播期」
     * （{@code defaultSow}）确实可以是过去（2027-09-20）。
     * 但「下一季推荐播期」（{@code nextSow}）必须是 2028-09-20。
     */
    @Test
    @DisplayName("回归：世界日期 2027-11-21 时，冬小麦东北的下一季推荐应是 2028-09-20")
    void winterWheatNextSeasonInNortheast() {
        LocalDate next = CropCatalog.nextSow("wheat_winter", "dongbei", WORLD_TODAY);
        assertEquals(LocalDate.of(2028, 9, 20), next,
                "冬小麦东北代表播期 9/20，2027-11-21 之后应顺延到 2028-09-20");

        // 反过来：9/20 之前，「下一季推荐」仍在今年
        LocalDate before = CropCatalog.nextSow("wheat_winter", "dongbei", LocalDate.of(2027, 9, 1));
        assertEquals(LocalDate.of(2027, 9, 20), before,
                "2027-09-01 时今年 9/20 还没到，不该顺延");
    }

    /**
     * 边界：今天恰好就是推荐播期 → 推荐播期就是今天（允许，不顺延）。
     */
    @Test
    @DisplayName("边界：今天正好是推荐播期时，nextSow 就返回今天")
    void nextSowOnTheExactDateReturnsToday() {
        LocalDate md = LocalDate.of(2027, 10, 25); // 冬小麦江南代表播期
        assertEquals(md, CropCatalog.nextSow("wheat_winter", "jiangnan", md));
    }

    // ==================================================================
    //  2. defaultSow 仍保留「这一季代表播期」的语义
    // ==================================================================

    /**
     * {@code defaultSow} 的语义**不能**改成「≥ 今天」。
     *
     * <p>它表达的是「<b>这一季</b>（正在长的那茬）的代表播期」，
     * 允许落在今天之前 —— 冬小麦跨冬就是这种情况。
     * {@link DistrictCropService#currentStage} 靠它做跨年判定：
     * 若今年播期还没到，就去看去年播下的那茬是否还在长。
     *
     * <p>所以这里钉住的是「它<b>可以</b>是过去」这个既有语义，
     * 防止有人「顺手」把它也改成 nextSow 的语义而破坏跨年判定。
     *
     * <p>注意它与 {@code nextSow} 的区别体现在<b>阈值</b>上：
     * {@code defaultSow} 的顺延阈值是 20 天 ——「今年播期离今天不到 20 天」就还用今年，
     * 这样「刚过播期 10 天」的地块仍指向今年那一茬；
     * 而 {@code nextSow} 严格「≥ 今天」。
     *
     * <p>本例：冬小麦东北 9/20，世界日期 2027-11-21（离 9/20 已 62 天 > 20 天）
     * → 两者都顺延到 2028-09-20。真正的区别要在「刚过播期不久」时体现。
     */
    @Test
    @DisplayName("defaultSow 保留「这一季代表播期」语义（20 天阈值，与 nextSow 严格 ≥ 今天 不同）")
    void defaultSowKeepsThisSeasonSemantics() {
        // 世界日期 2027-11-21：离 9/20 已 62 天 > 20 天阈值 → 顺延到 2028
        LocalDate ds = CropCatalog.defaultSow("wheat_winter", "dongbei", WORLD_TODAY);
        assertEquals(LocalDate.of(2028, 9, 20), ds,
                "这一季的代表播期顺延到 2028-09-20（距今天超过 20 天阈值）");

        // 🔴 关键对比：刚过播期 10 天时，两者不同 ——
        //   defaultSow → 仍是今年（那一茬正在长）
        //   nextSow    → 明年（下一季）
        LocalDate soon = LocalDate.of(2027, 9, 30); // 距 9/20 只过了 10 天
        assertEquals(LocalDate.of(2027, 9, 20), CropCatalog.defaultSow("wheat_winter", "dongbei", soon),
                "刚过播期 10 天（< 20 天阈值）时，defaultSow 应仍指向今年那一茬");
        assertEquals(LocalDate.of(2028, 9, 20), CropCatalog.nextSow("wheat_winter", "dongbei", soon),
                "而 nextSow 严格取「≥ 今天」的最近一次 → 明年");
    }

    // ==================================================================
    //  3. 风险评估里的「下一季推荐」也必须 ≥ 今天
    // ==================================================================

    /**
     * 风险表推给前端的 {@code recommendedSow} 必须 ≥ 今天。
     *
     * <p>这是前端「回到推荐播期」按钮的数据源 ——
     * 前端虽然有一层 {@code clampToToday} 兜底，但兜底会把播期改成「今天」，
     * 丢失「真正的推荐日」这个信息。根子上必须在后端给对。
     */
    @Test
    @DisplayName("风险表的 recommendedSow 必须 ≥ 今天")
    void plantingRiskRecommendedSowIsNotInThePast() {
        for (CropCatalog.Crop crop : CropCatalog.list()) {
            for (ClimateModel.Zone zone : ClimateModel.zones().values()) {
                // 用玩家实际会选的播期：今天
                LocalDate sow = WORLD_TODAY;
                SuitabilityEngine.Report rep = SuitabilityEngine.evaluate(
                        zone.id(), sow, crop.config());
                SuitabilityEngine.PlantingRisk risk = SuitabilityEngine.plantingRisk(
                        zone.id(), sow, crop, WORLD_TODAY, rep);
                assertNotNull(risk);
                assertTrue(!LocalDate.parse(risk.recommendedSow()).isBefore(WORLD_TODAY),
                        crop.id() + " @ " + zone.id() + "：recommendedSow="
                                + risk.recommendedSow() + " 早于今天 " + WORLD_TODAY);
            }
        }
    }

    /**
     * 风险表不能出现「播期选得也合适」这种与实际偏移矛盾的话。
     *
     * <p>2026-10-23 的原始输出：
     * <pre>
     *   冬小麦在东北的风险等级：极高。按推荐播期（2027-09-20）种，本区评级「理论不可行」，播期选得也合适。
     * </pre>
     * 同一句话里，TIMING 条目说「播期比东北的适期晚了 62 天」，
     * 总结却说「播期选得也合适」—— 自相矛盾。
     */
    @Test
    @DisplayName("播期明显偏离时，总结里不能出现「播期选得也合适」")
    void summaryDoesNotContradictOffset() {
        LocalDate sow = WORLD_TODAY;
        CropCatalog.Crop crop = CropCatalog.get("wheat_winter");
        SuitabilityEngine.Report rep = SuitabilityEngine.evaluate("dongbei", sow, crop.config());
        SuitabilityEngine.PlantingRisk risk = SuitabilityEngine.plantingRisk(
                "dongbei", sow, crop, WORLD_TODAY, rep);

        int offset = risk.offsetDays();
        if (offset != 0) {
            assertTrue(!risk.summary().contains("播期选得也合适"),
                    "播期偏移了 " + offset + " 天，总结却写「播期选得也合适」—— 自相矛盾。实际总结："
                            + risk.summary());
        }
    }

    // ==================================================================
    //  4. 偏移方向必须由符号决定
    // ==================================================================

    /**
     * 「早了 / 晚了」不能写死（记忆里的铁律）。
     *
     * <p>写死过一次：把「晚 41 天」显示成「早了 41 天」，方向完全反了。
     */
    @Test
    @DisplayName("播期偏移方向由符号决定：offset>0 是晚，offset<0 是早")
    void offsetDirectionFollowsSign() {
        CropCatalog.Crop crop = CropCatalog.get("rice_mid");
        // 江南中稻代表播期 5/20，earlyDays=35 / lateDays=40
        LocalDate anchor = LocalDate.of(2027, 5, 20);

        CropCatalog.Crop.SowWindow late = crop.sowWindow("jiangnan", anchor.plusDays(30), anchor);
        assertTrue(late.offsetDays() > 0, "选了锚点之后 30 天，应判定为「晚」");
        assertEquals("LATE", late.direction());

        CropCatalog.Crop.SowWindow early = crop.sowWindow("jiangnan", anchor.minusDays(20), anchor);
        assertTrue(early.offsetDays() < 0, "选了锚点之前 20 天，应判定为「早」");
        assertEquals("EARLY", early.direction());

        CropCatalog.Crop.SowWindow on = crop.sowWindow("jiangnan", anchor, anchor);
        assertEquals(0, on.offsetDays());
        assertEquals("ON", on.direction());
    }

    /**
     * 窗口的「早/晚」边界必须严格按 {@code earlyDays} / {@code lateDays}。
     */
    @Test
    @DisplayName("适期窗口边界：早 earlyDays / 晚 lateDays 都算窗口内")
    void sowWindowBoundaries() {
        CropCatalog.Crop crop = CropCatalog.get("rice_mid");
        int early = crop.earlyDays();
        int late = crop.lateDays();
        LocalDate anchor = LocalDate.of(2027, 5, 20);

        assertTrue(crop.sowWindow("jiangnan", anchor.minusDays(early), anchor).inWindow(),
                "正好早 earlyDays 天应仍在窗口内");
        assertTrue(crop.sowWindow("jiangnan", anchor.plusDays(late), anchor).inWindow(),
                "正好晚 lateDays 天应仍在窗口内");
        assertTrue(!crop.sowWindow("jiangnan", anchor.minusDays(early + 1), anchor).inWindow(),
                "早 earlyDays+1 天应出窗口");
        assertTrue(!crop.sowWindow("jiangnan", anchor.plusDays(late + 1), anchor).inWindow(),
                "晚 lateDays+1 天应出窗口");
    }

    // ==================================================================
    //  5. 四区推荐播期齐备性
    // ==================================================================

    /**
     * 每个作物在四个区都必须能算出推荐播期，且互不相同的月日各自独立。
     *
     * <p>防的是「某个区的 {@code sowByZone} 漏了 key」导致
     * 全部落回江南默认值 —— 那种 bug 不会报错，只会让地图上四个区的
     * 推荐播期长得一模一样。
     */
    @Test
    @DisplayName("每个作物在四个区都能算出 ≥ 今天的推荐播期")
    void everyCropHasRecommendationInEveryZone() {
        for (CropCatalog.Crop crop : CropCatalog.list()) {
            for (ClimateModel.Zone zone : ClimateModel.zones().values()) {
                LocalDate next = CropCatalog.nextSow(crop.id(), zone.id(), WORLD_TODAY);
                assertNotNull(next, crop.id() + " @ " + zone.id() + " 算不出推荐播期");
                assertTrue(!next.isBefore(WORLD_TODAY),
                        crop.id() + " @ " + zone.id() + " 推荐播期 " + next + " 早于今天");
            }
        }
    }

    /**
     * 回归：世界日期必须比现实日期靠后 —— 这是「演示模式推进过」的证据。
     *
     * <p>如果哪天 demo 模式被关掉（{@code farm.demo-mode=false}），
     * 这条会失败，提醒开发者「世界日期口径」的测试前提变了，
     * 需要重新审视这些断言。
     */
    @Test
    @DisplayName("前提校验：测试用的世界日期确实晚于现实日期")
    void worldTodayIsAheadOfRealToday() {
        assertTrue(WORLD_TODAY.isAfter(LocalDate.now()),
                "WORLD_TODAY=" + WORLD_TODAY + " 应该晚于现实日期 "
                        + LocalDate.now() + " —— 若演示模式已关闭，本测试的前提需要重新审视");
    }

    // ==================================================================
    //  6. 距今最近的适合窗口（玩家能选的那段日子）
    // ==================================================================

    /**
     * 🔴 2026-10-23 核心新增（用户反馈「推荐窗口期应该是距今最近的适合日期」）。
     *
     * <p>「距今最近」的定义：找第一个<b>窗口末端还没过</b>的那次该月日。
     *
     * <p>老实现（锚点 = 该月日的下一次发生）有个致命缺陷：
     * 只要本期的推荐月日刚过、但窗口<b>尾巴</b>还没走完，它就<b>直接跳到明年</b> ——
     * 明明眼前还有几天可以种，它却把玩家推到一年之后。
     *
     * <p>回归案例：大白菜江南（推荐 8/20，可提前 30 天 / 可推迟 35 天
     * → 本期窗口 7/21 ~ 9/24）。今天 2028-09-20 时，本期窗口还剩 9/20~9/24 可选，
     * 老实现却给 2029-07-21 ~ 2029-09-24 —— 玩家明明今天就能种，却被告知要等一年。
     */
    @Test
    @DisplayName("回归：本期窗口尾巴还没过时，最近窗口必须留在本期（不能跳到明年）")
    void nearestWindowStaysInCurrentSeasonWhenTailStillOpen() {
        // 大白菜江南：推荐 8/20，early=30 / late=35 → 本期窗口 7/21 ~ 9/24
        LocalDate today = LocalDate.of(2028, 9, 20);
        CropCatalog.Crop.SowWindow w = CropCatalog.get("cabbage").nearestWindow("jiangnan", today);

        assertEquals(LocalDate.of(2028, 9, 20), w.from(),
                "窗口起点应钳到今天（本期窗口从 7/21 开始，但过去的日子玩家选不了）");
        assertEquals(LocalDate.of(2028, 9, 24), w.to(),
                "本期窗口尾巴 9/24 还没过 → 必须留在本期，不能跳到明年");
        assertEquals(LocalDate.of(2028, 9, 20), w.recommended(),
                "锚点已过 → 距今最近的适合日期就是今天（该赶紧种了）");

        // 🔴 关键对比：老逻辑（nextSow = 该月日的下一次发生）会跳到明年
        assertEquals(LocalDate.of(2029, 8, 20), CropCatalog.nextSow("cabbage", "jiangnan", today),
                "对照组：nextSow 确实会跳到明年 —— 这正是要修的行为");
    }

    /**
     * 本期窗口尾巴<b>已经过</b>时，最近窗口必须落到下一季。
     *
     * <p>与上一条互补：不能无条件留在本期，否则会给出「窗口整体在过去」的答案。
     */
    @Test
    @DisplayName("本期窗口尾巴已过时，最近窗口必须落到下一季")
    void nearestWindowMovesToNextSeasonWhenTailExpired() {
        // 大白菜江南本期窗口到 9/24；今天 9/25 → 尾巴已过 → 下一季
        LocalDate today = LocalDate.of(2028, 9, 25);
        CropCatalog.Crop.SowWindow w = CropCatalog.get("cabbage").nearestWindow("jiangnan", today);

        assertEquals(LocalDate.of(2029, 7, 21), w.from(), "下一季窗口起点 = 2029-08-20 提前 30 天");
        assertEquals(LocalDate.of(2029, 9, 24), w.to(), "下一季窗口终点 = 2029-08-20 推迟 35 天");
        assertEquals(LocalDate.of(2029, 8, 20), w.recommended(),
                "锚点未过 → 推荐日就是下一季的推荐播期");
    }

    /**
     * 最近窗口的**起点永远是今天以后**（或就是今天）。
     *
     * <p>这是前端日期选择器的硬约束（{@code disabledDate} 禁掉今天之前）。
     * 窗口起点落在过去就没有意义 —— 展示了也选不了。
     */
    @Test
    @DisplayName("最近窗口的起点必须 ≥ 今天（玩家选得了）")
    void nearestWindowStartsFromTodayOrLater() {
        for (CropCatalog.Crop crop : CropCatalog.list()) {
            for (ClimateModel.Zone zone : ClimateModel.zones().values()) {
                for (int dayOffset = 0; dayOffset < 400; dayOffset += 7) {
                    LocalDate today = WORLD_TODAY.plusDays(dayOffset);
                    CropCatalog.Crop.SowWindow w = crop.nearestWindow(zone.id(), today);
                    assertTrue(!w.from().isBefore(today),
                            crop.id() + " @ " + zone.id() + "：today=" + today
                                    + " 时最近窗口起点 " + w.from() + " 早于今天 —— 玩家选不了");
                    assertTrue(!w.from().isAfter(w.to()),
                            crop.id() + " @ " + zone.id() + "：窗口起止颠倒 " + w.from() + " ~ " + w.to());
                    assertTrue(!w.to().isBefore(today),
                            crop.id() + " @ " + zone.id() + "：today=" + today
                                    + " 时最近窗口 " + w.from() + " ~ " + w.to() + " 整体落在过去");
                }
            }
        }
    }

    /**
     * 最近窗口的推荐日必须 ≥ 今天，且落在窗口内。
     */
    @Test
    @DisplayName("最近窗口的推荐日必须 ≥ 今天且落在窗口区间内")
    void nearestWindowRecommendedIsUsable() {
        for (CropCatalog.Crop crop : CropCatalog.list()) {
            for (ClimateModel.Zone zone : ClimateModel.zones().values()) {
                for (int dayOffset = 0; dayOffset < 400; dayOffset += 7) {
                    LocalDate today = WORLD_TODAY.plusDays(dayOffset);
                    CropCatalog.Crop.SowWindow w = crop.nearestWindow(zone.id(), today);
                    assertTrue(!w.recommended().isBefore(today),
                            crop.id() + " @ " + zone.id() + "：推荐日 " + w.recommended()
                                    + " 早于今天 " + today);
                    assertTrue(!w.recommended().isBefore(w.from())
                                    && !w.recommended().isAfter(w.to()),
                            crop.id() + " @ " + zone.id() + "：推荐日 " + w.recommended()
                                    + " 不在窗口 " + w.from() + " ~ " + w.to() + " 内");
                }
            }
        }
    }

    /**
     * 风险表推给前端的窗口字段必须是「距今最近」的那一段。
     *
     * <p>{@code nearestWindowFrom/To} 是前端日期选择器旁「适期窗口」那行的数据源，
     * 必须与 {@code CropCatalog.nearestWindow} 一致，且起点 ≥ 今天。
     */
    @Test
    @DisplayName("风险表的最近窗口必须 ≥ 今天且与后端计算一致")
    void plantingRiskNearestWindowIsUsable() {
        for (CropCatalog.Crop crop : CropCatalog.list()) {
            for (ClimateModel.Zone zone : ClimateModel.zones().values()) {
                LocalDate sow = WORLD_TODAY;
                SuitabilityEngine.Report rep = SuitabilityEngine.evaluate(
                        zone.id(), sow, crop.config());
                SuitabilityEngine.PlantingRisk risk = SuitabilityEngine.plantingRisk(
                        zone.id(), sow, crop, WORLD_TODAY, rep);

                CropCatalog.Crop.SowWindow w = crop.nearestWindow(zone.id(), WORLD_TODAY);
                assertEquals(w.from().toString(), risk.nearestWindowFrom(),
                        crop.id() + " @ " + zone.id() + "：风险表的最近窗口起点与后端计算不一致");
                assertEquals(w.to().toString(), risk.nearestWindowTo(),
                        crop.id() + " @ " + zone.id() + "：风险表的最近窗口终点与后端计算不一致");
                assertTrue(!LocalDate.parse(risk.nearestWindowFrom()).isBefore(WORLD_TODAY),
                        crop.id() + " @ " + zone.id() + "：最近窗口起点早于今天");
                assertTrue(!LocalDate.parse(risk.recommendedSow()).isBefore(WORLD_TODAY),
                        crop.id() + " @ " + zone.id() + "：recommendedSow 早于今天");
            }
        }
    }

    /** 避免 IDE 提示未使用 import。 */
    @Test
    @DisplayName("烟雾：作物库非空")
    void catalogNotEmpty() {
        List<CropCatalog.Crop> crops = CropCatalog.list();
        assertTrue(crops.size() >= 14, "作物库应有 14 种，实际 " + crops.size());
    }
}
