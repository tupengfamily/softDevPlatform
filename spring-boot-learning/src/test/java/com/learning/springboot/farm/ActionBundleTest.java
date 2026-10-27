package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.InputCatalog;
import com.learning.springboot.farm.engine.SimEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ActionBundle} 回归（2026-10-26 P2）。
 *
 * <p><b>为什么这个类值得单独测</b>：
 * 它是「五条平行农事通路」收口后的<b>唯一分类点</b>。
 * 改造前，{@code FarmPlayService} 里有五处各自遍历 {@code ctx.actions()}、
 * 各自判断一次类型 —— 它们之间没有任何测试锁定「这几处口径一致」，
 * 所以 2026-10-25 那次「只修了 weeds 一处、另外三处仍按 sow 起算」才能发生。
 *
 * <p>现在口径写在<b>一个有名字的类</b>里，就必须把它测实。
 *
 * <p>🔴 <b>本测试锁三条不变量</b>：
 * <ol>
 *   <li><b>分类互斥且完备</b>：一条农事要么进农药、要么进除草、要么进施肥，
 *       要么进水层管理，绝不重复进。</li>
 *   <li><b>两套排水/灌水口径的差异是显式的</b>：
 *       土壤口径含 {@code DRY_FIELD}/{@code DRAIN_FINAL}/{@code PUDDLE}，
 *       灾害口径不含 —— 且这是<b>有意</b>的差异，不是 bug。</li>
 *   <li><b>legacy 兼容</b>：老数据没有 {@code itemId}，必须走「全靶标、满效力」分支，
 *       不能被当成「打错药」。</li>
 * </ol>
 */
class ActionBundleTest {

    private static final LocalDate D1 = LocalDate.of(2026, 8, 24);
    private static final LocalDate D2 = LocalDate.of(2026, 8, 30);
    private static final LocalDate D3 = LocalDate.of(2026, 9, 10);

    // ==================================================================
    //  构造工具
    // ==================================================================

    private static FarmAction action(FarmAction.Type type, LocalDate date, String itemId,
                                     Integer durationDays, Double dose) {
        FarmAction a = new FarmAction();
        a.setType(type);
        a.setDate(date);
        a.setItemId(itemId);
        a.setDurationDays(durationDays);
        a.setDose(dose);
        return a;
    }

    private static FarmAction action(FarmAction.Type type, LocalDate date) {
        return action(type, date, null, 1, null);
    }

    // ==================================================================
    //  不变式一：分类互斥且完备
    // ==================================================================

    @Test
    @DisplayName("P2：农药/除草/施肥三类互斥 —— 一条农事只进一个桶")
    void categoriesAreMutuallyExclusive() {
        List<FarmAction> acts = List.of(
                action(FarmAction.Type.PESTICIDE, D1, "TRICYCLAZOLE", 1, 30.0),
                action(FarmAction.Type.WEED, D2, "QUIZALOFOP", 1, 20.0),
                action(FarmAction.Type.FERTILIZE, D3, "UREA", 7, 12.0),
                action(FarmAction.Type.BASE_FERT, D3, "COMPOUND", 7, 20.0),
                action(FarmAction.Type.TILLER_FERT, D3, "UREA", 7, 10.0),
                action(FarmAction.Type.PANICLE_FERT, D3, "UREA", 7, 8.0));

        ActionBundle b = ActionBundle.of(acts);

        assertEquals(1, b.pesticides().size(), "只有 PESTICIDE 进农药桶");
        assertEquals(1, b.weeds().size(), "只有 WEED 进除草桶");
        assertEquals(4, b.fertilizes().size(), "FERTILIZE/BASE_FERT/TILLER_FERT/PANICLE_FERT 都进施肥桶");
    }

    @Test
    @DisplayName("P2：除草动作不会混进农药桶（除草剂不是农药）")
    void herbicideIsNotPesticide() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.WEED, D1, "QUIZALOFOP", 1, 20.0)));

        assertTrue(b.pesticides().isEmpty(),
                "除草剂走 WeedEngine 通道，不该出现在农药桶里");
        assertEquals(1, b.weeds().size());
    }

    @Test
    @DisplayName("P2：非农事类型（揭帘/移栽/烘干）不进任何农事桶")
    void unrelatedTypesAreIgnored() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.UNCOVER, D1),
                action(FarmAction.Type.TRANSPLANT, D1),
                action(FarmAction.Type.DRY_GRAIN, D1)));

        assertTrue(b.pesticides().isEmpty());
        assertTrue(b.weeds().isEmpty());
        assertTrue(b.fertilizes().isEmpty());
        assertTrue(b.drainDates().isEmpty());
        assertTrue(b.irrigateDates().isEmpty());
        // 但它们仍要有工序效果（揭帘保温是实打实的减温）
        assertFalse(b.effectsByDate().isEmpty(), "揭帘/移栽/烘干仍要有 ActionEffect");
    }

    // ==================================================================
    //  不变式二：两套排水/灌水口径（土壤 vs 灾害）
    // ==================================================================

    /**
     * 🔴 核心：土壤口径与灾害口径<b>确实不同</b>，且差异是<b>有意</b>的。
     *
     * <p>土壤引擎看的是「水层有没有被排空」→ 晒田（DRY_FIELD）与收前排水（DRAIN_FINAL）
     * 都会真的排空，所以要算。
     *
     * <p>灾害引擎的「排水缓解」是<b>清沟防涝</b> —— 泡田不防涝、收前排水更不防涝，
     * 把它们算进去等于让玩家「收前排水」白拿渍涝减免。
     */
    @Test
    @DisplayName("P2：土壤排水含 DRY_FIELD/DRAIN_FINAL，灾害排水只认 DRAIN")
    void drainCaliberDiffersBetweenSoilAndDisaster() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.DRAIN, D1),
                action(FarmAction.Type.DRY_FIELD, D2),
                action(FarmAction.Type.DRAIN_FINAL, D3)));

        assertEquals(Set.of(D1, D2, D3), b.drainDates(),
                "土壤口径：排水 + 晒田 + 收前排水都算");
        assertEquals(Set.of(D1), b.disasterDrainDates(),
                "灾害口径：只有真正的清沟排水算防涝缓解");
    }

    @Test
    @DisplayName("P2：土壤灌水含 PUDDLE，灾害灌水只认 IRRIGATE")
    void irrigateCaliberDiffersBetweenSoilAndDisaster() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.IRRIGATE, D1),
                action(FarmAction.Type.PUDDLE, D2)));

        assertEquals(Set.of(D1, D2), b.irrigateDates(),
                "土壤口径：泡田建浅水层也是加水");
        assertEquals(Set.of(D1), b.disasterIrrigateDates(),
                "灾害口径：泡田不调温，只认真正的 IRRIGATE");
    }

    /**
     * 🔴 回归：这正是本轮自查发现并修掉的那个 bug。
     *
     * <p>第一版实现里 {@code disasterIrrigateDates()} 直接返回了土壤灌水集合，
     * 与它自己的方法注释（「灾害口径不含 PUDDLE」）<b>直接矛盾</b>。
     * 这类「注释说了 A、代码做了 B」最危险 —— 因为读代码的人会相信注释。
     */
    @Test
    @DisplayName("P2：灾害灌水口径不含 PUDDLE（回归第一版的自相矛盾 bug）")
    void disasterIrrigateExcludesPuddle() {
        ActionBundle b = ActionBundle.of(List.of(action(FarmAction.Type.PUDDLE, D2)));

        assertTrue(b.irrigateDates().contains(D2), "PUDDLE 必须在土壤灌水里");
        assertFalse(b.disasterIrrigateDates().contains(D2),
                "PUDDLE 不该进灾害灌水 —— 泡田不调温");
    }

    @Test
    @DisplayName("P2：两套口径是独立集合，改一个不影响另一个")
    void calipersAreIndependentSets() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.DRAIN, D1),
                action(FarmAction.Type.IRRIGATE, D2)));

        assertEquals(Set.of(D1), b.drainDates());
        assertEquals(Set.of(D1), b.disasterDrainDates());
        assertEquals(Set.of(D2), b.irrigateDates());
        assertEquals(Set.of(D2), b.disasterIrrigateDates());
    }

    // ==================================================================
    //  不变式三：legacy 兼容
    // ==================================================================

    /**
     * 🔴 回归 S12 修复：老数据没有 {@code itemId}，必须按「全靶标、满效力」兼容。
     *
     * <p>真实事故：历史打药记录（{@code targets} 为空集）被判成「打错药」，
     * 防效只剩 5% —— 玩家在旧存档里补打一次药，药效几乎为零。
     */
    @Test
    @DisplayName("P2：没有 itemId 的打药记录被标记为 legacy")
    void pesticideWithoutItemIdIsLegacy() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.PESTICIDE, D1, null, 1, null)));

        assertEquals(1, b.pesticides().size());
        ActionBundle.SprayAction sp = b.pesticides().get(0);
        assertTrue(sp.legacy(), "查不到 item 必须是 legacy");
        assertNull(sp.item());
        assertNull(sp.itemId());
    }

    @Test
    @DisplayName("P2：能查到 item 的打药记录不是 legacy")
    void pesticideWithKnownItemIsNotLegacy() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.PESTICIDE, D1, "TRICYCLAZOLE", 1, 30.0)));

        ActionBundle.SprayAction sp = b.pesticides().get(0);
        assertFalse(sp.legacy());
        assertNotNull(sp.item());
        assertEquals("TRICYCLAZOLE", sp.itemId());
        assertEquals(30.0, sp.dose());
    }

    @Test
    @DisplayName("P2：legacy 施肥不被丢弃，仍进施肥桶（按 3kg N 兼容）")
    void legacyFertilizeIsKept() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.FERTILIZE, D1, null, 7, null)));

        assertEquals(1, b.fertilizes().size());
        assertTrue(b.fertilizes().get(0).legacy());
        assertNull(b.fertilizes().get(0).item());
    }

    // ==================================================================
    //  除草：人工 vs 化学
    // ==================================================================

    @Test
    @DisplayName("P2：HERB 类农资 = 化学除草；无 itemId = 人工除草")
    void weedChemicalVsManual() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.WEED, D1, "QUIZALOFOP", 1, 20.0),
                action(FarmAction.Type.WEED, D2, null, 1, null)));

        assertEquals(2, b.weeds().size());
        assertTrue(b.weeds().get(0).chemical(), "HERB 类农资 → 化学除草");
        assertFalse(b.weeds().get(1).chemical(), "无 itemId → 人工除草");
    }

    // ==================================================================
    //  工序效果：持续天数摊到多天
    // ==================================================================

    @Test
    @DisplayName("P2：施肥持续 7 天 → 摊到 7 天各享一次 effect")
    void fertilizerDurationSpansMultipleDays() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.FERTILIZE, D1, "UREA", 7, 12.0)));

        assertEquals(7, b.effectsByDate().size(), "7 天持续 → 7 个日期各有一次 effect");
        for (int d = 0; d < 7; d++) {
            assertTrue(b.effectsByDate().containsKey(D1.plusDays(d)),
                    "第 " + d + " 天应有 effect");
            assertEquals(1, b.effectsByDate().get(D1.plusDays(d)).size());
        }
    }

    @Test
    @DisplayName("P2：durationDays 为 null 时按 1 天算（不 NPE）")
    void nullDurationFallsBackToOneDay() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.FERTILIZE, D1, "UREA", null, 12.0)));

        assertEquals(1, b.effectsByDate().size());
        assertTrue(b.effectsByDate().containsKey(D1));
    }

    @Test
    @DisplayName("P2：durationDays = 0 不产生任何 effect（不产生空日期条目）")
    void zeroDurationProducesNoEffect() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.FERTILIZE, D1, "UREA", 0, 12.0)));

        assertTrue(b.effectsByDate().isEmpty());
    }

    @Test
    @DisplayName("P2：同一天多条农事 → 同一天的 effect 累积成列表")
    void multipleActionsSameDayAccumulate() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.FERTILIZE, D1, "UREA", 1, 12.0),
                action(FarmAction.Type.PESTICIDE, D1, "BT", 1, 20.0),
                action(FarmAction.Type.IRRIGATE, D1)));

        List<SimEngine.ActionEffect> effs = b.effectsByDate().get(D1);
        assertNotNull(effs);
        assertEquals(3, effs.size(), "同一天 3 条农事 → 3 份 effect");
    }

    // ==================================================================
    //  防御性：脏数据
    // ==================================================================

    @Test
    @DisplayName("P2：null / 无类型 / 无日期的农事被跳过（不 NPE）")
    void dirtyRowsAreSkipped() {
        FarmAction noType = new FarmAction();
        noType.setDate(D1);
        FarmAction noDate = new FarmAction();
        noDate.setType(FarmAction.Type.IRRIGATE);

        ActionBundle b = ActionBundle.of(java.util.Arrays.asList(
                null, noType, noDate, action(FarmAction.Type.IRRIGATE, D1)));

        assertEquals(1, b.irrigateDates().size());
        assertEquals(1, b.effectsByDate().size());
    }

    @Test
    @DisplayName("P2：null 列表返回空 bundle（不 NPE）")
    void nullListReturnsEmptyBundle() {
        ActionBundle b = ActionBundle.of(null);

        assertTrue(b.pesticides().isEmpty());
        assertTrue(b.weeds().isEmpty());
        assertTrue(b.fertilizes().isEmpty());
        assertTrue(b.drainDates().isEmpty());
        assertTrue(b.irrigateDates().isEmpty());
        assertTrue(b.disasterDrainDates().isEmpty());
        assertTrue(b.disasterIrrigateDates().isEmpty());
        assertTrue(b.effectsByDate().isEmpty());
    }

    // ==================================================================
    //  不可变性
    // ==================================================================

    @Test
    @DisplayName("P2：投影结果是不可变的（外部改不动）")
    void projectionsAreImmutable() {
        ActionBundle b = ActionBundle.of(List.of(action(FarmAction.Type.DRAIN, D1)));

        assertThrowsUnsupported(() -> b.drainDates().add(D2));
        assertThrowsUnsupported(() -> b.pesticides().clear());
        assertThrowsUnsupported(() -> b.effectsByDate().clear());
    }

    private static void assertThrowsUnsupported(Runnable r) {
        try {
            r.run();
            org.junit.jupiter.api.Assertions.fail("应当抛 UnsupportedOperationException");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }
    }

    // ==================================================================
    //  跨引擎口径一致性（防「同型代码只修一处」复发）
    // ==================================================================

    /**
     * 🔴 回归：{@code soil()} 与 {@code disasters()} 现在都从 bundle 取，
     * 所以它们的差异<b>必须</b>与 {@link ActionBundle} 里声明的一致。
     *
     * <p>本测试不直接调私有方法（那需要 Spring 上下文），
     * 而是把「土壤口径 vs 灾害口径」的不变量写死在这里 ——
     * 以后谁改了 {@link ActionBundle} 的分类，本测试立刻红。
     */
    @Test
    @DisplayName("P2：土壤与灾害的排水/灌水口径差异被显式锁定")
    void caliberDifferenceIsLocked() {
        ActionBundle b = ActionBundle.of(List.of(
                action(FarmAction.Type.DRAIN, D1),
                action(FarmAction.Type.DRY_FIELD, D2),
                action(FarmAction.Type.DRAIN_FINAL, D3),
                action(FarmAction.Type.IRRIGATE, D1),
                action(FarmAction.Type.PUDDLE, D2)));

        // 土壤：全都要（排水 + 晒田 + 收前排水；灌水 + 泡田）
        assertEquals(Set.of(D1, D2, D3), b.drainDates());
        assertEquals(Set.of(D1, D2), b.irrigateDates());
        // 灾害：只认 DRAIN 与 IRRIGATE
        assertEquals(Set.of(D1), b.disasterDrainDates());
        assertEquals(Set.of(D1), b.disasterIrrigateDates());
    }

    /**
     * 🔴 回归：分类必须<b>覆盖全部 15 种</b> {@link FarmAction.Type}。
     *
     * <p>为什么要这条：{@code FarmAction.Type} 以后加新枚举值时，
     * 如果忘了在 {@link ActionBundle} 里处理，它会静默落到 {@code default} 分支 ——
     * 「新工序不生效」这种 bug 不会报错，只会表现为「点了按钮像没点」。
     *
     * <p>本测试用反射扫一遍枚举，确保每个类型至少在 {@code effectsByDate()} 里留下痕迹
     * （工序效果对所有类型都生成）。
     */
    @Test
    @DisplayName("P2：新增的农事类型不会静默丢失（每个类型都有工序效果）")
    void everyActionTypeProducesEffect() {
        Set<FarmAction.Type> covered = new HashSet<>();
        for (FarmAction.Type t : FarmAction.Type.values()) {
            ActionBundle b = ActionBundle.of(List.of(action(t, D1)));
            assertFalse(b.effectsByDate().isEmpty(),
                    "类型 " + t + " 没有生成任何工序效果 —— 会变成「点了没反应」");
            covered.add(t);
        }
        assertEquals(FarmAction.Type.values().length, covered.size());
    }

    // ==================================================================
    //  与 SeasonContext 的集成
    // ==================================================================

    @Test
    @DisplayName("P2：SeasonContext 持久的 bundle 与单独构造的一致")
    void contextBundleMatchesStandaloneBundle() {
        List<FarmAction> acts = List.of(
                action(FarmAction.Type.PESTICIDE, D1, "BT", 1, 20.0),
                action(FarmAction.Type.DRAIN, D2),
                action(FarmAction.Type.FERTILIZE, D3, "UREA", 7, 12.0));

        SeasonContext ctx = new SeasonContext(D1, D3, List.of(), acts, acts);
        ActionBundle standalone = ActionBundle.of(acts);

        assertEquals(standalone.pesticides().size(), ctx.bundle().pesticides().size());
        assertEquals(standalone.drainDates(), ctx.bundle().drainDates());
        assertEquals(standalone.irrigateDates(), ctx.bundle().irrigateDates());
        assertEquals(standalone.disasterDrainDates(), ctx.bundle().disasterDrainDates());
        assertEquals(standalone.disasterIrrigateDates(), ctx.bundle().disasterIrrigateDates());
        assertEquals(standalone.effectsByDate().keySet(), ctx.bundle().effectsByDate().keySet());
    }

    @Test
    @DisplayName("P2：SeasonContext 的 bundle 只用窗口内农事（不用全季）")
    void contextBundleUsesWindowActionsOnly() {
        List<FarmAction> window = List.of(action(FarmAction.Type.IRRIGATE, D2));
        List<FarmAction> season = List.of(
                action(FarmAction.Type.IRRIGATE, D2),
                action(FarmAction.Type.PESTICIDE, D1, "BT", 1, 20.0));

        SeasonContext ctx = new SeasonContext(D1, D3, List.of(), window, season);

        assertEquals(0, ctx.bundle().pesticides().size(),
                "窗口外的农药不该进 bundle —— 引擎的 DVS 轨迹从 winStart 起记");
        assertEquals(1, ctx.bundle().irrigateDates().size());
        // 但全季列表里它确实还在（成本核算要用）
        assertEquals(2, ctx.seasonActions().size());
    }

    @Test
    @DisplayName("P2：全季农事仍可单独取到（bundle 不吞掉它）")
    void seasonActionsStillAvailable() {
        List<FarmAction> window = List.of(action(FarmAction.Type.IRRIGATE, D2));
        List<FarmAction> season = List.of(
                action(FarmAction.Type.IRRIGATE, D2),
                action(FarmAction.Type.PESTICIDE, D1, "BT", 1, 20.0));

        SeasonContext ctx = new SeasonContext(D1, D3, List.of(), window, season);

        assertEquals(2, ctx.seasonActions().size(),
                "成本核算 / 货架面板要用全季农事，不能被 bundle 取代");
    }

    // ==================================================================
    //  抗性公式收口（P2 顺带修的「同型代码 4 份」）
    // ==================================================================

    @Test
    @DisplayName("P2：抗性系数只有一个来源 —— 生物农药不吃抗性")
    void resistanceFormulaHasSingleSource() {
        // 化学农药：连用 3 次 → 0.85^3 = 0.614125
        InputCatalog.Item chem = InputCatalog.get("TRICYCLAZOLE");
        assertNotNull(chem);
        assertEquals(Math.max(0.60, Math.pow(0.85, 3)),
                FarmPlayService.resistanceOf(chem, 3), 1e-9);

        // 生物农药：无论用几次都是 1.0
        InputCatalog.Item bio = InputCatalog.get("BT");
        assertNotNull(bio);
        assertEquals(1.0, FarmPlayService.resistanceOf(bio, 99), 1e-9);

        // 下限：连用 20 次也不低于 0.60
        assertEquals(0.60, FarmPlayService.resistanceOf(chem, 20), 1e-9);
        // 负数次数按 0 次算
        assertEquals(1.0, FarmPlayService.resistanceOf(chem, -1), 1e-9);
    }
}
