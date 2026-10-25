package com.learning.springboot.farm;

import java.time.LocalDate;
import java.util.List;

/**
 * 世界日期工具（S24）。
 *
 * <p><b>为什么要抽出来</b>：「今天」在演示模式下不是 {@code LocalDate.now()}，
 * 而是 {@code LocalDate.now() + 推进天数}。而
 * {@link FarmPlayService} 与 {@link FarmWarehouseService} 都需要它——
 * 若后者注入前者就形成<b>循环依赖</b>（Spring 构造器注入会直接启动失败）。
 * 抽成无状态工具类后，两边各持一份 {@code demoMode} 开关即可。
 *
 * <p>⚠️ <b>两种「今天」必须分清</b>：
 * <ul>
 *   <li><b>真实今天</b> = {@code LocalDate.now()}，正式模式（1:1 实时）用；</li>
 *   <li><b>世界今天</b> = 真实今天 + 推进量，演示模式用。</li>
 * </ul>
 * 混用会导致「天气显示 10 月 22 日，旁边地块却写着 12 月」这种口径打架。
 *
 * <h2>🔴 仓库口径的教训（2026-10-22 实测踩坑）</h2>
 * 仓库入库日期用「<b>该地块</b>的世界日期」，而仓储老化基准若用
 * 「<b>用户级</b>的最靠后世界日期」，两者就会打架：
 * <pre>
 *   地块 A 推进了 400 天 → 收割入库，storedDate = 2027-11-20
 *   地块 B 没推进      → 用户世界日期仍取 A 的 2027-11-20
 *   刚入仓的菜立刻算「已仓储 329 天」→ 番茄(仓储期 14 天)直接显示「已变质」
 * </pre>
 * 玩家刚收的菜秒变垃圾，仓库功能等于报废。
 *
 * <p><b>正确的口径</b>：仓库是<b>用户级</b>的，所以「现在」也必须是用户级的
 * （{@link #userWorldDate}）；而<b>入库日期</b>必须与它同源——
 * 收割时用<b>用户级</b>世界日期入库，而不是地块自己的日期。
 * 这样「入库 → 老化」永远在同一条时间轴上。
 *
 * <h2>🔴 用户级时钟：口径污染隐患的根治（2026-10-26）</h2>
 *
 * <p>修复前 {@link #userWorldDate} 是「<b>取所有地块里最靠后的那个</b>」——
 * 这意味着<b>任何一个无关地块的偏移都会污染整个用户级的日期口径</b>。
 *
 * <p>2026-10-25 实测踩坑：{@code plot27} 残留旧 bug 写入的
 * {@code demo_offset_days = 660}，把整个用户的世界日期推到 2028-08-15，
 * 仓库 6 件商品全部「已变质 ¥0」。
 *
 * <p><b>根治方案</b>：把「用户推进了多少天」提到<b>用户级</b>（{@link FarmUserClock}），
 * 用户级口径只读这张表；地块级偏移只影响<b>该地块自己</b>的世界日期。
 * 两级时钟在 {@code advance} 时同步推进，因此仍然保持一致。
 */
public final class FarmWorldDate {

    private FarmWorldDate() {
    }

    /** 单块地的世界日期（地块级口径）。 */
    public static LocalDate effectiveToday(FarmPlot plot, boolean demoMode) {
        LocalDate today = LocalDate.now();
        if (demoMode && plot != null && plot.getDemoOffsetDays() != null && plot.getDemoOffsetDays() > 0) {
            return today.plusDays(plot.getDemoOffsetDays());
        }
        return today;
    }

    /**
     * <b>生长时钟</b>：作物模拟专用的「今天」。
     *
     * <p>🔴 2026-10-26：生长时钟现在<b>锚在用户级世界日期</b>上
     * （不再锚在「地块自己的偏移」上），理由见类注释「两级时钟」一节：
     * 地块级偏移会让「推进时间」变成「只推进某一块地」，
     * 而仓库老化等用户级语义却按全局推进 —— 两套口径打架。
     *
     * <p>🔴 2026-10-23 需求：「演示模式只是预测作物的走向，不应该实际改变游戏的时间轴」。
     *
     * <p>改造前：{@link #effectiveToday} 同时承担两个职责 ——
     * ① 玩家主动推进的天数；② <b>建田时把「今天」自动对齐到播期</b>。
     * ② 是问题根源：建一块 2027-05-20 的水稻田，整块地的世界日期就被推到 2027-05-20，
     * 于是「计划播期 2029 年」「仓库老化 500 天」这类现象全都冒出来了。
     *
     * <p>现在的模型：
     * <ul>
     *   <li><b>世界日期</b> = 现实今天 + <b>用户级</b>推进量 → 时间轴不被建田偷偷改动；</li>
     *   <li><b>生长时钟</b>额外处理「播期在未来」的情况：此时作物从<b>播期当天</b>起算，
     *       走一条独立于世界日期的时钟（这正是「预测走向」的语义 ——
     *       站在今天看「如果那时种下，会怎么长」）。</li>
     * </ul>
     *
     * @param userToday 用户级世界日期（已含推进量）
     * @param plot 地块
     * @return 生长模拟用的「今天」
     */
    public static LocalDate growthToday(LocalDate userToday, FarmPlot plot) {
        LocalDate world = userToday == null ? LocalDate.now() : userToday;
        LocalDate sow = plot == null ? null : plot.getSowDate();
        if (sow == null) {
            return world;
        }
        if (!sow.isAfter(world)) {
            // 播期在过去（或就是今天）→ 与世界日期同源
            return world;
        }
        // 播期在未来 → 独立时钟：今天 = 播期 + 本季推进天数
        //   （玩家在「+N 天」按钮上推进的是**本季生长进度**，不是世界日期）
        int progress = plot.getSeasonProgressDays() == null ? 0 : plot.getSeasonProgressDays();
        return sow.plusDays(Math.max(0, progress));
    }

    /**
     * <b>用户级的世界日期</b>。
     *
     * <p>🔴 2026-10-26 起本方法<b>只读用户级时钟</b>（{@link FarmUserClock}），
     * 不再从地块反推 —— 详见类注释「用户级时钟」一节。
     *
     * <p><b>老口径保留在 {@link #userWorldDateFromPlots}，仅供迁移与回退使用</b>：
     * 它是「取所有地块里最靠后的那个」，正是那个会污染全局口径的实现。
     *
     * @param userOffsetDays 用户级推进天数（来自 {@link FarmUserClock#getOffsetDays()}）
     * @param demoMode 是否演示模式
     */
    public static LocalDate userWorldDate(Integer userOffsetDays, boolean demoMode) {
        LocalDate today = LocalDate.now();
        if (!demoMode || userOffsetDays == null || userOffsetDays <= 0) {
            return today;
        }
        return today.plusDays(userOffsetDays);
    }

    /**
     * 🔴 <b>老口径</b>：用户级世界日期 = 所有地块里<b>最靠后</b>的那个。
     *
     * <p>⚠️ <b>新代码不要用这个</b> —— 它就是 2026-10-25「plot27 污染全用户」的根因：
     * 任何一个无关地块的偏移都会把整个用户级日期口径推走。
     *
     * <p>仅保留两个用途：
     * <ol>
     *   <li><b>数据迁移</b>：老库没有用户级时钟时，取地块偏移最大值作为迁移初值
     *       （这样迁移当天口径与旧口径一致，不跳变）；</li>
     *   <li><b>回退</b>：用户级时钟记录缺失（极老的数据）时兜底。</li>
     * </ol>
     *
     * @param plots 该用户的地块
     * @param demoMode 是否演示模式
     */
    public static LocalDate userWorldDateFromPlots(List<FarmPlot> plots, boolean demoMode) {
        LocalDate max = null;
        if (plots != null) {
            for (FarmPlot p : plots) {
                LocalDate d = effectiveToday(p, demoMode);
                if (d != null && (max == null || d.isAfter(max))) {
                    max = d;
                }
            }
        }
        return max == null ? LocalDate.now() : max;
    }
}
