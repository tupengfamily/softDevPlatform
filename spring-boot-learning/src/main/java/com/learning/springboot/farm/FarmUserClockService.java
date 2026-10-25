package com.learning.springboot.farm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <b>用户级世界时钟服务</b>（2026-10-26 新增）。
 *
 * <h2>它解决的问题</h2>
 *
 * <p>修复前，用户级的「今天」= <b>所有地块里最靠后的世界日期</b>。
 * 这意味着<b>任何一个无关地块的偏移都会污染整个用户级的日期口径</b> ——
 * 仓库老化、天气取段、推荐播期、商店世界日期全都跟着被推走。
 *
 * <p>2026-10-25 实测踩坑：{@code plot27} 残留旧 bug（「建田自动对齐播期」）
 * 写入的 {@code demo_offset_days = 660}，把整个用户的世界日期推到 2028-08-15，
 * 仓库 6 件商品全部「仓储 800+ 天 → 已变质 → 不可卖 / ¥0」。
 *
 * <h2>现在的模型：两级时钟，彻底分工</h2>
 *
 * <pre>
 *   用户级世界日期  = 现实今天 + 用户级推进量(t_farm_user_clock.offset_days)
 *                     → 仓库老化 / 天气取段 / 推荐播期 / 商店世界日期
 *
 *   地块级生长时钟  = 播期 + 本季推进量(plot.season_progress_days)
 *                     → 作物回放 / 物候 / 出苗率 / 产量
 * </pre>
 *
 * <p>两者<b>不再由同一个字段派生</b>：世界日期只有一个来源（用户时钟），
 * 生长时钟只跟地块自己的播期与本季进度有关。
 *
 * <p>因此「一块无关地块的偏移污染全用户」这件事<b>在结构上不再可能发生</b>：
 * 地块上根本没有「世界日期偏移」这个概念了。
 */
@Service
public class FarmUserClockService {

    /** 演示推进上限：约 10 年，够跑十季；1:1 正式模式不生效。 */
    public static final int MAX_OFFSET_DAYS = 3650;

    private final FarmUserClockRepository clockRepo;
    private final boolean demoMode;

    public FarmUserClockService(FarmUserClockRepository clockRepo,
                                 @Value("${farm.demo-mode:true}") boolean demoMode) {
        this.clockRepo = clockRepo;
        this.demoMode = demoMode;
    }

    /**
     * 该用户的推进量（天）。
     *
     * <p>记录缺失（极老的数据、或刚建号还没推过）时返回 0。
     */
    public int offsetDays(Long userId) {
        if (userId == null) {
            return 0;
        }
        return clockRepo.findByUserId(userId)
                .map(c -> c.getOffsetDays() == null ? 0 : c.getOffsetDays())
                .orElse(0);
    }

    /**
     * 该用户的<b>世界日期</b>（= 现实今天 + 推进量）。
     *
     * <p>这是<b>用户级</b>语义（仓库老化 / 天气取段 / 推荐播期）的唯一入口。
     */
    public LocalDate today(Long userId) {
        return FarmWorldDate.userWorldDate(offsetDays(userId), demoMode);
    }

    /**
     * 推进用户级时钟。
     *
     * @return 推进后的累计天数
     */
    @Transactional
    public int advance(Long userId, int days) {
        int next = offsetDays(userId) + days;
        if (next > MAX_OFFSET_DAYS) {
            throw new com.learning.springboot.exception.BusinessException(
                    "演示推进已达上限（约 10 年），请重开一季");
        }
        FarmUserClock clock = clockRepo.findByUserId(userId)
                .orElseGet(() -> new FarmUserClock(userId, 0));
        clock.setOffsetDays(next);
        clock.setUpdateTime(LocalDateTime.now());
        clockRepo.save(clock);
        return next;
    }

    /** 演示模式开关（供调用方判断推进是否有效）。 */
    public boolean isDemoMode() {
        return demoMode;
    }
}
