package com.learning.springboot.farm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <b>用户级世界时钟</b>（2026-10-26 新增）。
 *
 * <h2>为什么需要它（架构级隐患的修复）</h2>
 *
 * <p>修复前，用户级的「今天」是这样算的：
 * <pre>
 *   userWorldDate(plots) = max{ 现实今天 + 每块地的 demoOffsetDays }
 * </pre>
 * 也就是<b>取所有地块里最靠后的那个日期</b>。这个口径有一个致命问题：
 * <b>任何一个无关地块的偏移都会污染整个用户级的日期口径</b>。
 *
 * <p>2026-10-25 实测踩坑：{@code plot27}（甜瓜田，零农事零季次）残留了旧 bug
 * 「建田自动对齐播期」写入的 {@code demo_offset_days = 660}，
 * 于是整个用户的世界日期被推到 <b>2028-08-15</b>。后果是仓库里 6 件商品
 * 全部「仓储 800+ 天 → 已变质 → 不可卖 / ¥0」——玩家明明刚收的菜秒变垃圾。
 *
 * <p>这暴露的不只是「一条脏数据」，而是<b>口径设计本身</b>：
 * 「仓库老化」「天气取哪一段」「推荐播期」这些**用户级**语义，
 * 不该由某一块**地块级**的偏移决定。
 *
 * <h2>修复后的设计</h2>
 *
 * <p>把「用户推进了多少天」这个状态<b>提到用户级</b>，落到本表：
 * <pre>
 *   t_farm_user_clock
 *     user_id        唯一
 *     offset_days    用户主动推进的天数（世界日期 = 现实今天 + offset_days）
 *     update_time
 * </pre>
 *
 * <p>于是：
 * <ul>
 *   <li><b>用户级口径</b>（仓库老化 / 天气取段 / 推荐播期 / 商店世界日期）
 *       → 走 {@link FarmWorldDate#userWorldDate}，读本表；</li>
 *   <li><b>地块级生长时钟</b>（作物回放 / 物候 / 出苗率）
 *       → 走 {@link FarmWorldDate#growthToday}，锚在<b>播期 + 本季推进天数</b>，
 *       不再读地块的 {@code demoOffsetDays}。</li>
 * </ul>
 *
 * <p>🔴 2026-10-26：原设计里地块级仍读 {@code demoOffsetDays}，
 * 于是「推进时间」会退化成「只推进某一块地」，而仓库老化等用户级语义却按全局推进 ——
 * 两套口径打架。现在地块的 {@code demoOffsetDays} 退化为
 * <b>用户级时钟的冗余镜像</b>（{@code advance} 时统一同步），
 * 不再是「谁被推进了」的独立状态。
 *
 * <h2>🔴 老数据迁移（幂等，必须做）</h2>
 *
 * <p>老库里没有这张表，用户的推进量都散在各地块的 {@code demo_offset_days} 里。
 * 迁移策略：<b>取该用户所有地块偏移的最大值</b>写入用户级时钟 ——
 * 这与旧的 {@code userWorldDate} 口径<b>完全一致</b>，所以迁移当天玩家的
 * 仓库老化、天气取段、推荐播期都不会发生跳变。
 *
 * <p>迁移由 {@code FarmUserClockSchemaPatch} 在 {@code ApplicationReadyEvent} 时执行，
 * 幂等（已存在的用户不覆盖）。
 */
@Entity
@Table(name = "t_farm_user_clock")
public class FarmUserClock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /**
     * 用户主动推进的天数。
     *
     * <p>世界日期 = {@code LocalDate.now() + offsetDays}。
     * 正式模式（{@code farm.demo-mode=false}）下恒为 0。
     */
    @Column(name = "offset_days", nullable = false)
    private Integer offsetDays = 0;

    @Column(name = "update_time")
    private LocalDateTime updateTime = LocalDateTime.now();

    public FarmUserClock() {
    }

    public FarmUserClock(Long userId, Integer offsetDays) {
        this.userId = userId;
        this.offsetDays = offsetDays == null ? 0 : offsetDays;
        this.updateTime = LocalDateTime.now();
    }

    /** 该用户的「今天」（= 现实今天 + 推进天数）。 */
    public LocalDate today() {
        return LocalDate.now().plusDays(offsetDays == null ? 0 : offsetDays);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Integer getOffsetDays() {
        return offsetDays;
    }

    public void setOffsetDays(Integer offsetDays) {
        this.offsetDays = offsetDays == null ? 0 : offsetDays;
    }

    public LocalDateTime getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(LocalDateTime updateTime) {
        this.updateTime = updateTime;
    }
}
