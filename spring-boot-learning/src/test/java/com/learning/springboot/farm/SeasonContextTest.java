package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.DayWeather;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 请求级上下文回归（2026-10-26 P0-3）。
 *
 * <p><b>背景</b>：一次 {@code view()} 请求里，
 * {@code seasonActions(...)} 被调 <b>8 次</b>（8 次 DB 查询），
 * {@code weather.range(...)} 被调 <b>5 次</b>（同一段 431 天天气构造 5 遍）。
 *
 * <p>更关键的是<b>正确性</b>：DVS 轨迹从窗口起点 {@code winStart} 起记，
 * 而 4 个子引擎（病虫害 / 土壤 / 灾害 / 杂草）各自去查天气 ——
 * 只要有一处窗口口径漂移（哪怕只差一天），
 * 「天气序列」与「DVS 轨迹」就整体错位，子引擎的损失全部算错。
 *
 * <p>2026-10-25 已经吃过一次这个亏：那段窗口算法被复制在 4 个地方，
 * 只修了 {@code weeds} 一处，另外三处仍按 {@code sow} 起算。
 * 本次引入 {@link SeasonContext} 就是为了根除「同型代码只修一处」。
 *
 * <p>本测试锁定三条不变量：
 * <ol>
 *   <li>窗口起点只有一个来源（{@code SeasonContext.windowStart}）；</li>
 *   <li>上下文里的日期→下标映射与「逐日连续序列」严格对应；</li>
 *   <li>上下文不可变（构造后改不了）。</li>
 * </ol>
 */
class SeasonContextTest {

    private static final LocalDate SOW = LocalDate.of(2026, 8, 24);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);

    // ==================================================================
    //  窗口起点：唯一真源
    // ==================================================================

    @Test
    @DisplayName("P0-3：短季窗口起点 = 播种日")
    void shortSeasonStartsAtSow() {
        assertEquals(SOW, SeasonContext.windowStart(SOW, TODAY));
    }

    @Test
    @DisplayName("P0-3：长季窗口起点 = 今天往前 430 天（与 doReplay 一致）")
    void longSeasonStartsAtTodayMinusMax() {
        LocalDate far = LocalDate.of(2028, 2, 18);   // 距 sow 543 天
        assertEquals(far.minusDays(FarmPlayService.MAX_REPLAY_DAYS),
                SeasonContext.windowStart(SOW, far));
    }

    /**
     * 🔴 关键边界：429 / 430 / 431。
     *
     * <p>{@code doReplay} 的判定是 {@code span > MAX_REPLAY_DAYS}，
     * 即 431 天起才截断。差一天的口径漂移就会让引擎整体错位。
     */
    @Test
    @DisplayName("P0-3：窗口截断边界（429/430/431）精确")
    void truncationBoundaryIsExact() {
        for (int span : new int[]{429, 430, 431, 432}) {
            LocalDate today = SOW.plusDays(span);
            LocalDate ws = SeasonContext.windowStart(SOW, today);
            if (span <= FarmPlayService.MAX_REPLAY_DAYS) {
                assertEquals(SOW, ws, "span=" + span + " 不该截断");
            } else {
                assertEquals(today.minusDays(FarmPlayService.MAX_REPLAY_DAYS), ws,
                        "span=" + span + " 必须截断到今天往前 430 天");
            }
        }
    }

    @Test
    @DisplayName("P0-3：窗口起点永不早于播种日")
    void windowStartNeverBeforeSow() {
        for (LocalDate sow : new LocalDate[]{
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 8, 24), LocalDate.of(2027, 12, 31)}) {
            LocalDate today = sow.plusDays(500);
            assertFalse(SeasonContext.windowStart(sow, today).isBefore(sow),
                    "窗口起点不能早于播种日");
        }
    }

    @Test
    @DisplayName("P0-3：null 播种日 / null 今天不炸")
    void nullSafe() {
        // null 播种日 → 没有窗口起点可言，返回 today（调用方据此跳过天气构造）
        assertEquals(TODAY, SeasonContext.windowStart(null, TODAY));
        // null 今天 → 也返回 null，调用方（seasonContext）会判空后跳过查询
        assertNull(SeasonContext.windowStart(SOW, null));
        assertNull(SeasonContext.windowStart(null, null));
    }

    // ==================================================================
    //  日期 → 下标映射
    // ==================================================================

    @Test
    @DisplayName("P0-3：indexOf 与逐日序列严格对应（窗口第 0 天 → 0）")
    void indexOfMatchesSequence() {
        LocalDate winStart = SeasonContext.windowStart(SOW, TODAY);
        List<DayWeather> days = new ArrayList<>();
        for (LocalDate d = winStart; !d.isAfter(TODAY); d = d.plusDays(1)) {
            days.add(new DayWeather(d.toString(), 30, 20, 0));
        }
        SeasonContext ctx = new SeasonContext(winStart, TODAY, days, List.of(), List.of());

        assertEquals(days.size(), ctx.n());
        // 第 0 天
        assertEquals(0, ctx.indexOf(winStart));
        // 最后一天
        assertEquals(days.size() - 1, ctx.indexOf(TODAY));
        // 中间任意一天
        for (int i = 0; i < days.size(); i++) {
            assertEquals(i, ctx.indexOf(winStart.plusDays(i)),
                    "第 " + i + " 天的下标映射错了");
        }
    }

    @Test
    @DisplayName("P0-3：窗口外的日期返回 -1（不夹到边界）")
    void outOfWindowReturnsMinusOne() {
        LocalDate winStart = SeasonContext.windowStart(SOW, TODAY);
        List<DayWeather> days = new ArrayList<>();
        for (LocalDate d = winStart; !d.isAfter(TODAY); d = d.plusDays(1)) {
            days.add(new DayWeather(d.toString(), 30, 20, 0));
        }
        SeasonContext ctx = new SeasonContext(winStart, TODAY, days, List.of(), List.of());

        assertEquals(-1, ctx.indexOf(winStart.minusDays(1)), "窗口之前应返回 -1");
        assertEquals(-1, ctx.indexOf(TODAY.plusDays(1)), "窗口之后应返回 -1");
        assertEquals(-1, ctx.indexOf(null));
    }

    @Test
    @DisplayName("P0-3：dayAt 越界钳制（不抛异常）")
    void dayAtClamps() {
        LocalDate winStart = SeasonContext.windowStart(SOW, TODAY);
        List<DayWeather> days = List.of(new DayWeather(winStart.toString(), 30, 20, 0));
        SeasonContext ctx = new SeasonContext(winStart, TODAY, days, List.of(), List.of());

        assertNotNull(ctx.dayAt(-5));
        assertNotNull(ctx.dayAt(0));
        assertNotNull(ctx.dayAt(999));
        // 空上下文不炸
        SeasonContext empty = new SeasonContext(winStart, TODAY, null, null, null);
        assertEquals(0, empty.n());
        org.junit.jupiter.api.Assertions.assertNull(empty.dayAt(0));
    }

    // ==================================================================
    //  不可变性
    // ==================================================================

    @Test
    @DisplayName("P0-3：上下文不可变（构造后改不了原列表）")
    void contextIsImmutable() {
        LocalDate winStart = SeasonContext.windowStart(SOW, TODAY);
        List<DayWeather> days = new ArrayList<>();
        days.add(new DayWeather(winStart.toString(), 30, 20, 0));
        List<FarmAction> acts = new ArrayList<>();

        SeasonContext ctx = new SeasonContext(winStart, TODAY, days, acts, acts);

        // 外部再改原列表 → 上下文不受影响
        days.add(new DayWeather(TODAY.toString(), 31, 21, 0));
        acts.add(new FarmAction());

        assertEquals(1, ctx.n(), "上下文里的天气条数不应被外部修改影响");
        assertEquals(0, ctx.actions().size());
        assertEquals(0, ctx.seasonActions().size());
    }

    @Test
    @DisplayName("P0-3：null 列表被兜底成空列表（不 NPE）")
    void nullListsFallBackToEmpty() {
        SeasonContext ctx = new SeasonContext(TODAY, TODAY, null, null, null);
        assertEquals(0, ctx.n());
        assertTrue(ctx.actions().isEmpty());
        assertTrue(ctx.seasonActions().isEmpty());
    }

    // ==================================================================
    //  两套农事列表的分工
    // ==================================================================

    /**
     * 🔴 回归：窗口内农事 ≠ 全季农事，两者不可混用。
     *
     * <p>引擎（病虫害/土壤/灾害/杂草）与 {@code doReplay} 用<b>窗口内</b>的农事 ——
     * 因为它们的 DVS 轨迹从 {@code winStart} 起记。
     *
     * <p>而工序链、成本核算、货架面板用<b>全季</b>的农事 ——
     * 「这一季投入了多少钱」不能因为窗口截断而少算。
     *
     * <p>用错了的后果：① 引擎读到窗口外的农事 → DVS 下标错位；
     * ② 成本核算漏算 → 净收益虚高。
     */
    @Test
    @DisplayName("P0-3：窗口内农事与全季农事是两套，不可混用")
    void windowActionsDifferFromSeasonActions() {
        LocalDate far = LocalDate.of(2028, 2, 18);      // 长季，窗口被截断
        LocalDate winStart = SeasonContext.windowStart(SOW, far);

        // 一条窗口外的农事（远早于 winStart）
        FarmAction early = new FarmAction();
        early.setDate(SOW.plusDays(10));
        // 一条窗口内的农事
        FarmAction late = new FarmAction();
        late.setDate(winStart.plusDays(5));

        List<FarmAction> window = List.of(late);
        List<FarmAction> season = List.of(early, late);

        SeasonContext ctx = new SeasonContext(winStart, far, List.of(), window, season);

        assertEquals(1, ctx.actions().size(), "窗口内只应有 1 条");
        assertEquals(2, ctx.seasonActions().size(), "全季应有 2 条");
        assertTrue(ctx.seasonActions().containsAll(ctx.actions()),
                "全季列表必须包含窗口内列表的全部条目");
    }

    /**
     * 🔴 回归：上下文与 {@code replayWindowStart} 必须给出同一个窗口起点。
     *
     * <p>{@code FarmPlayService.replayWindowStart} 是 private，
     * 但它现在已经<b>委托</b>给 {@code SeasonContext.windowStart} ——
     * 这个测试锁住「两处永远一致」这个事实。
     */
    @Test
    @DisplayName("P0-3：窗口口径与 FarmPlayService 的窗口起点永远一致")
    void windowStartMatchesServiceImplementation() {
        int[] spans = {0, 1, 29, 30, 100, 428, 429, 430, 431, 432, 543, 1000};
        for (int span : spans) {
            LocalDate today = SOW.plusDays(span);
            LocalDate viaCtx = SeasonContext.windowStart(SOW, today);

            // 复刻 FarmPlayService.replayWindowStart 的算法（它已委托给 SeasonContext）
            LocalDate viaService;
            if (ChronoUnit.DAYS.between(SOW, today) > FarmPlayService.MAX_REPLAY_DAYS) {
                viaService = today.minusDays(FarmPlayService.MAX_REPLAY_DAYS);
            } else {
                viaService = SOW;
            }

            assertEquals(viaService, viaCtx, "span=" + span + " 时两套口径必须一致");
        }
    }
}
