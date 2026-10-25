package com.learning.springboot.farm;

import com.learning.springboot.farm.engine.DayWeather;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 一块地「本季」的请求级上下文（2026-10-26 P0-3）。
 *
 * <p>🔴 <b>为什么需要它</b>：一次 {@code view()} 请求里，
 * {@code seasonActions(...)} 被调 <b>8 次</b>（8 次 DB 查询），
 * {@code weather.range(...)} 被调 <b>5 次</b>（同一段 431 天天气被构造 5 遍）。
 * 单看每次查询都不慢，但乘上「多地块 × 多接口同帧」的放大效应，
 * 一次刷新就能把 SQLite 拖成瓶颈 —— 而且这种重复<b>永远不会被发现</b>，
 * 因为它不报错，只是慢。
 *
 * <p><b>更重要的是口径一致性</b>：天气是「同一段区间」还是「不同段」，
 * 直接决定 DVS 轨迹与病虫害/土壤/灾害/杂草四个子引擎的天气序列能否对齐。
 * 只要有一处窗口口径漂移（哪怕只差一天），子引擎的损失就算错了 ——
 * 而这跟性能无关，是<b>正确性</b>问题。
 *
 * <p><b>设计取舍</b>：
 * <ul>
 *   <li>不做全局缓存（那会引入失效地狱）；只做<b>请求级</b>复用，
 *       生命周期 = 一次 Service 方法调用。</li>
 *   <li>上下文是<b>不可变</b>的：所有字段在构造时算好，之后只读。</li>
 *   <li>窗口口径<b>只有一个来源</b>：{@link #windowStart(LocalDate, LocalDate)}，
 *       与 {@code doReplay} 的窗口严格一致（这是 2026-10-25 那次
 *       「同型代码只修一处」教训的直接延续）。</li>
 * </ul>
 *
 * <p><b>两套农事列表的分工</b>（不要混用）：
 * <ul>
 *   <li>{@link #actions()} —— <b>窗口内</b>的农事（{@code [winStart, today]}）。
 *       引擎（病虫害/土壤/灾害/杂草）与 {@code doReplay} 用这一份，
 *       因为它们的 DVS 轨迹从 {@code winStart} 起记。</li>
 *   <li>{@link #seasonActions()} —— <b>全季</b>的农事（{@code [sow, today]}）。
 *       工序链、成本核算、货架面板用这一份，
 *       因为「这一季投入了多少钱」不能因为窗口截断而少算。</li>
 * </ul>
 *
 * @param winStart      回放窗口起点（含），与 {@code doReplay} 的窗口严格一致
 * @param today         生长时钟的「今天」（地块级时钟，不是用户级世界日期）
 * @param days          窗口内逐日天气，只构造一次
 * @param actions       窗口内农事
 * @param seasonActions 全季农事
 */
public record SeasonContext(
        LocalDate winStart,
        LocalDate today,
        List<DayWeather> days,
        List<FarmAction> actions,
        List<FarmAction> seasonActions) {

    public SeasonContext {
        days = days == null ? List.of() : List.copyOf(days);
        actions = actions == null ? List.of() : List.copyOf(actions);
        seasonActions = seasonActions == null ? List.of() : List.copyOf(seasonActions);
    }

    /**
     * 回放窗口起点：<b>以今天结尾往前推最多 430 天</b>，且不早于播种日。
     *
     * <p>🔴 这个口径必须与 {@code FarmPlayService.doReplay} <b>完全一致</b>。
     * 2026-10-25 的代码检视发现，这段逻辑曾被复制在 4 个地方
     * （pests / soil / disasters / weeds），当时只修了 weeds 一处，
     * 另外三处仍按 {@code sow} 起算 —— 播种超过 430 天时
     * 「天气序列」与「DVS 轨迹」整体错位，三类损失全算错。
     *
     * <p>现在窗口口径只有这一个来源。
     */
    public static LocalDate windowStart(LocalDate sow, LocalDate today) {
        if (sow == null || today == null) {
            return today;
        }
        long span = ChronoUnit.DAYS.between(sow, today);
        if (span > FarmPlayService.MAX_REPLAY_DAYS) {
            return today.minusDays(FarmPlayService.MAX_REPLAY_DAYS);
        }
        return sow;
    }

    /** 窗口内天数（= DVS 轨迹长度）。 */
    public int n() {
        return days.size();
    }

    /**
     * 把日期映射到 {@link #days()} 的下标（越界返回 -1）。
     *
     * <p>{@code days} 是从 {@link #winStart()} 起的<b>连续</b>逐日序列
     * （{@code weather.range} 的语义保证），所以下标可以直接算出来，
     * 不用遍历比对 —— 这是把「同一份天气复用给 5 个消费者」的关键。
     */
    public int indexOf(LocalDate date) {
        if (date == null || winStart == null) {
            return -1;
        }
        long off = ChronoUnit.DAYS.between(winStart, date);
        if (off < 0 || off >= days.size()) {
            return -1;
        }
        return (int) off;
    }

    /** 窗口内某一年的天气（按天索引，越界钳到最后一天）。 */
    public DayWeather dayAt(int idx) {
        if (days.isEmpty()) {
            return null;
        }
        int i = Math.max(0, Math.min(days.size() - 1, idx));
        return days.get(i);
    }
}
