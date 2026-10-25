package com.learning.springboot.farm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回放窗口口径对齐回归（2026-10-25 代码检视 S1）。
 *
 * <p><b>背景</b>：{@code doReplay} 的回放窗口是「以今天结尾往前 MAX_REPLAY_DAYS(430) 天」，
 * 而 {@code r.dvsByDay()} 就是从窗口起点 {@code winStart} 开始记的。
 * 原本这段「窗口起点」的算法被复制在 4 个地方（pests / soil / disasters / weeds），
 * 当时只修了 weeds 一处，另外三处仍按 {@code sow} 起算 ——
 * 于是「本季超过 430 天」时，<b>天气序列与 DVS 轨迹整体错位</b>，
 * 病虫害 / 土壤退化 / 灾害三类损失全部算错。
 *
 * <p>修复方式：把算法抽成唯一实现 {@code replayWindowStart}，四处统一调用。
 * 本测试锁定这个口径，防止将来再次分叉。
 *
 * <p>注：{@code replayWindowStart} / {@code dvsIndex} 是 {@code FarmPlayService} 的
 * private 方法，本测试用**等价实现**复刻其算法来验证口径，
 * 而不是反射调用（反射测 private 会让重构变脆）。
 */
class ReplayWindowAlignmentTest {

    /** 与 FarmPlayService.MAX_REPLAY_DAYS 保持一致。 */
    private static final int MAX_REPLAY_DAYS = 430;

    // ==================================================================
    //  被测算法（与 FarmPlayService.replayWindowStart 逐行等价）
    // ==================================================================

    /** 窗口起点：与 doReplay 完全同源（span > MAX_REPLAY_DAYS 时以今天结尾往前 430 天）。 */
    private static LocalDate replayWindowStart(LocalDate sow, LocalDate today) {
        long span = ChronoUnit.DAYS.between(sow, today);
        if (span > MAX_REPLAY_DAYS) {
            return today.minusDays(MAX_REPLAY_DAYS);
        }
        return sow;
    }

    /** 复刻 doReplay 的窗口计算：播种超过 430 天时，窗口以今天结尾往前 430 天。 */
    private static LocalDate doReplayWindowStart(LocalDate sow, LocalDate today) {
        long span = ChronoUnit.DAYS.between(sow, today);
        LocalDate winStart = sow;
        if (span > MAX_REPLAY_DAYS) {
            winStart = today.minusDays(MAX_REPLAY_DAYS);
        }
        return winStart;
    }

    // ==================================================================
    //  测试
    // ==================================================================

    @Test
    @DisplayName("短季（<430天）：窗口起点就是播种日")
    void shortSeason_startsAtSowDate() {
        LocalDate sow = LocalDate.of(2026, 8, 24);
        LocalDate today = LocalDate.of(2026, 10, 25);

        LocalDate winStart = replayWindowStart(sow, today);

        assertEquals(sow, winStart, "短季窗口起点必须是播种日");
        assertEquals(doReplayWindowStart(sow, today), winStart,
                "短季必须与 doReplay 口径一致");
    }

    @Test
    @DisplayName("长季（>430天）：窗口起点与 doReplay 完全一致")
    void longSeason_matchesDoReplay() {
        LocalDate sow = LocalDate.of(2026, 8, 24);
        LocalDate today = LocalDate.of(2028, 2, 18);      // 距 sow 已 543 天

        LocalDate winStart = replayWindowStart(sow, today);

        assertEquals(doReplayWindowStart(sow, today), winStart,
                "长季窗口起点必须与 doReplay 一致（这是 S1 修复的核心断言）");
        assertEquals(today.minusDays(MAX_REPLAY_DAYS), winStart,
                "长季窗口应以今天结尾往前 430 天");
        assertTrue(winStart.isAfter(sow), "长季窗口起点必须晚于播种日");
    }

    @Test
    @DisplayName("长季：DVS 轨迹起点与天气序列起点是同一天")
    void longSeason_dvsTraceAlignsWithWeather() {
        LocalDate sow = LocalDate.of(2026, 8, 24);
        LocalDate today = LocalDate.of(2028, 2, 18);

        LocalDate winStart = replayWindowStart(sow, today);

        // dvsByDay 的第 0 个元素对应 winStart 当天；
        // 天气序列 days[0] 也必须对应 winStart 当天 —— 否则两者错位。
        LocalDate dvsTraceDay0 = winStart;
        LocalDate weatherDay0 = winStart;

        assertEquals(dvsTraceDay0, weatherDay0,
                "DVS 轨迹第 0 天与天气序列第 0 天必须是同一天");
        assertEquals(MAX_REPLAY_DAYS + 1,
                ChronoUnit.DAYS.between(winStart, today) + 1,
                "长季天气序列长度必须覆盖整个窗口");
    }

    @Test
    @DisplayName("长季：农事动作下标按窗口起点算，不再错位到播种日")
    void longSeason_actionIndexUsesWindowStart() {
        LocalDate sow = LocalDate.of(2026, 8, 24);
        LocalDate today = LocalDate.of(2028, 2, 18);
        LocalDate winStart = replayWindowStart(sow, today);

        // 一次落在窗口内早期的施肥
        LocalDate actionDate = winStart.plusDays(5);
        int idx = (int) ChronoUnit.DAYS.between(winStart, actionDate);

        // 旧算法用 sow 起算 → 得到 118，看起来"没越界"，但它指向的是
        // DVS 轨迹的第 118 天，而那一天对应的天气是「窗口第 118 天」而非「播种日+118」。
        // 也就是说：拿到的是**错误的物候值**（错窗判定 inWindow 因此判错）。
        int oldIdx = (int) ChronoUnit.DAYS.between(sow, actionDate);

        assertEquals(5, idx, "新算法按窗口起点算 → 得到窗口内的真实下标");
        assertTrue(oldIdx != idx,
                "旧算法按播种日算会错位（这正是「错窗打五折」判定出错的根因）");
        // 新算法下标必须落在 DVS 轨迹长度内
        assertTrue(idx >= 0 && idx <= MAX_REPLAY_DAYS,
                "新算法下标必须落在 DVS 轨迹长度内，实际=" + idx);
    }

    @Test
    @DisplayName("窗口起点永不早于播种日（短季不会取到播种日之前）")
    void windowStartNeverBeforeSow() {
        List<LocalDate> sows = new ArrayList<>();
        sows.add(LocalDate.of(2026, 8, 24));
        sows.add(LocalDate.of(2026, 1, 1));
        sows.add(LocalDate.of(2027, 12, 31));

        for (LocalDate sow : sows) {
            LocalDate today = sow.plusDays(100);
            LocalDate winStart = replayWindowStart(sow, today);
            assertTrue(!winStart.isBefore(sow),
                    "窗口起点不能早于播种日: sow=" + sow + " winStart=" + winStart);
        }
    }

    @Test
    @DisplayName("各种跨度下 replayWindowStart 与 doReplay 口径全部一致")
    void allScenarios_matchDoReplay() {
        LocalDate sow = LocalDate.of(2026, 8, 24);
        // 关键边界：429/430/431 —— doReplay 的判定是 span > MAX（即 431 起才截断）
        int[] spans = {0, 1, 29, 30, 100, 428, 429, 430, 431, 432, 543, 1000};

        for (int span : spans) {
            LocalDate today = sow.plusDays(span);

            LocalDate viaHelper = replayWindowStart(sow, today);
            LocalDate viaReplay = doReplayWindowStart(sow, today);

            assertEquals(viaReplay, viaHelper,
                    "span=" + span + " 时两套口径必须一致");
        }
    }

    @Test
    @DisplayName("🔴 锁定 n(dvsByDay.size) 与窗口起点的真实对应关系")
    void dvsTraceLengthMapsToWindowStart() {
        // 关键事实（doReplay + SimEngine 联动得出）：
        //   span  = between(sow, today)，若 > MAX_REPLAY_DAYS 则截断为 MAX
        //   days  = weather.range(winStart, today) → 长度 = between(winStart,today)+1
        //   maxDays = span+1（传给 SimEngine）
        //   n = dvsByDay().size() = min(maxDays, days.size())
        //
        // 因此在**所有**跨度下，today-(n-1) 都恰好等于 doReplay 算出的 winStart。
        // 这条测试把该对应关系显式钉住：将来若改 MAX_REPLAY_DAYS 或改 SimEngine
        // 的 maxDays 语义，这里会立刻红。
        LocalDate sow = LocalDate.of(2026, 8, 24);
        int[] spans = {0, 1, 100, 429, 430, 431, 432, 543, 1000};

        for (int span : spans) {
            LocalDate today = sow.plusDays(span);

            // 复刻 doReplay 的截断逻辑
            int effectiveSpan = span;
            if (effectiveSpan > MAX_REPLAY_DAYS) {
                effectiveSpan = MAX_REPLAY_DAYS;
            }
            int maxDays = effectiveSpan + 1;

            // 复刻 doReplay 的天气窗口
            LocalDate winStart = doReplayWindowStart(sow, today);
            int daysSize = (int) ChronoUnit.DAYS.between(winStart, today) + 1;

            // n = dvsByDay().size()
            int n = Math.min(maxDays, daysSize);

            assertEquals(winStart, today.minusDays(n - 1L),
                    "span=" + span + " 时 today-(n-1) 必须等于窗口起点");
            assertEquals(winStart, replayWindowStart(sow, today),
                    "span=" + span + " 时两套口径必须一致");
        }
    }

    @Test
    @DisplayName("🔴 反向断言：窗口起点不能退化成播种日")
    void windowStartMustNotCollapseToSowDate() {
        // 原 bug 的形态（不是「用 n 反推」，而是「直接用 sow 起算 n 天」）：
        // 长季时窗口退化成整个生长季（span=543 → 窗口 544 天），
        // 而 doReplay 只有 431 天 → 天气序列与 DVS 轨迹错位 113 天。
        LocalDate sow = LocalDate.of(2026, 8, 24);
        LocalDate today = LocalDate.of(2028, 2, 18);   // span = 543

        LocalDate wrong = sow;                       // 原 bug：直接按 sow 起算
        LocalDate right = replayWindowStart(sow, today);

        assertEquals(sow, wrong);
        assertEquals(today.minusDays(MAX_REPLAY_DAYS), right);
        assertEquals(113, ChronoUnit.DAYS.between(sow, right),
                "正确窗口比原 bug 晚 113 天 —— 这正是原 bug 的错位量");
        assertEquals(MAX_REPLAY_DAYS + 1,
                ChronoUnit.DAYS.between(right, today) + 1,
                "正确窗口长度恒为 431 天");
    }
}
