package com.learning.springboot.farm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 玩家的一块田（「稼穑」小游戏）。
 *
 * <p><b>设计要点：只存"种下去是哪天"，不存"现在长到哪"。</b>
 * 作物的 DVS / 积温 / 损伤全部由 {@code 播种日 → 今天} 的天气重算得出。
 * 这样做的三个好处：
 * <ol>
 *   <li>同一块地在同一个日期永远算出同一个结果（可回放、可对账）；</li>
 *   <li>不需要每天跑批处理推进状态，玩家三个月不来也不会"算出不同的历史"；</li>
 *   <li>调数值（比如改了 aat_req）后，历史地块会按新口径重算，不会出现新旧混杂。</li>
 * </ol>
 *
 * <p>代价：每次请求要重算播种至今的逐日累计（最多 260 天，约 0.1ms），可忽略。
 */
@Entity
@Table(name = "t_farm_plot", indexes = {
        @Index(name = "idx_farm_plot_user_slot", columnList = "user_id,slot", unique = true)
})
public class FarmPlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /**
     * 地块槽位（0..99）。同一用户在同一个农业区可以有多块田，
     * 每块地独立作物 / 播期 / 农事记录，唯一约束是 (user_id, slot)。
     */
    @Column(name = "slot", nullable = false)
    private Integer slot = 0;

    /**
     * 所属农场（{@link FarmStead}）。
     *
     * <p>可空：v1 时期建的地块没有农场归属，那段历史保留为 null，
     * 看板会单独归到「未归属农场」一组，不强行伪造一个地理位置。
     */
    @Column(name = "farm_id")
    private Long farmId;

    /** 本季播种日。引擎从这个日期开始逐日累加，直到今天。 */
    @Column(name = "sow_date", nullable = false)
    private LocalDate sowDate;

    /** 作物 id：rice_mid / wheat_winter / corn_spring / soybean（S1） */
    @Column(name = "crop_id", length = 32)
    private String cropId;

    /** 地块名（建田时玩家可改，默认「{区名}一号田」） */
    @Column(name = "field_name")
    private String fieldName;

    /** 农业区 id：jiangnan / huanan / huanghuaihai / dongbei（S2 / S23） */
    @Column(name = "zone_id")
    private String zoneId;

    /** 是否已完成建田（选过区、确认过播期）。未建田时前端走选地流程。 */
    @Column(name = "zone_chosen", nullable = false)
    private Boolean zoneChosen = false;

    /**
     * 是否处于「待播种」——上一季已结束，等玩家选下一季种什么（2026-10-22）。
     *
     * <p><b>为什么不用 {@code seasons > 0} 代替</b>：
     * {@code seasons} 只在**成功收获**时 +1，苗期枯死（绝收）与中途重开都不增加它，
     * 用它判断会出现「枯死过的地又变回待开垦」的错判。
     *
     * <p>也不能用 {@code cropId != null}：首次进入时 {@code createInherited} 会先写一个
     * 默认作物占位（否则回放无从下手），那时的地其实是「待开垦」。
     *
     * <p>所以这里用一个显式标志位：只在
     * 「收割 / 记为绝收 / 重开一季」三条路径置 true，「建田（开新一季）」置 false。
     */
    @Column(name = "awaiting_sow", nullable = false)
    private Boolean awaitingSow = false;

    /** 已完成的季数（收获一次 +1） */
    @Column(nullable = false)
    private Integer seasons = 0;

    /** 历史最好亩产（kg/亩），用于和田头牌子对比 */
    @Column(name = "best_yield", nullable = false)
    private Double bestYield = 0.0;

    /** 最近一次收获日 */
    @Column(name = "last_harvest_date")
    private LocalDate lastHarvestDate;

    /** 演示模式下的天数偏移（正式模式恒为 0）。1:1 实时下不允许推进时间，见 README 技术债 */
    @Column(name = "demo_offset_days", nullable = false)
    private Integer demoOffsetDays = 0;

    /**
     * <b>本季独立时钟的推进天数</b>（2026-10-23 新增）。
     *
     * <p>🔴 需求：「演示模式只是预测作物的走向，不应该实际改变游戏的时间轴」。
     *
     * <p>改造后两个时钟彻底分离：
     * <ul>
     *   <li>{@link #demoOffsetDays} = <b>世界日期</b>的推进量（玩家主动推进）→ 决定「今天是哪天」，
     *       影响仓库老化、天气数据取哪一段；</li>
     *   <li>本字段 = <b>生长时钟</b>的推进量 → 决定「这株作物长到第几天」。
     *       播期在未来时，作物从播期当天起算，玩家推进 N 天 = 长 N 天。</li>
     * </ul>
     *
     * <p>两者的区别只在「播期在未来」时显现；播期在过去时二者等价（都等于现实今天起的偏移）。
     */
    @Column(name = "season_progress_days", nullable = false)
    private Integer seasonProgressDays = 0;

    /**
     * 本季开始的真实时刻。重开/收割后写 now，用于把「上一季的操作」挡在本季回放之外——
     * 否则同一天数的两季会共用同一批动作（日期相同 → 旧打药/晒田被重放）。
     */
    @Column(name = "season_started_at")
    private LocalDateTime seasonStartedAt;

    /** 最近一次收获亩产 */
    @Column(name = "last_yield")
    private Double lastYield;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getFarmId() { return farmId; }
    public void setFarmId(Long v) { this.farmId = v; }
    public Integer getSlot() { return slot == null ? 0 : slot; }
    public void setSlot(Integer slot) { this.slot = slot == null ? 0 : slot; }
    public LocalDate getSowDate() { return sowDate; }
    public void setSowDate(LocalDate sowDate) { this.sowDate = sowDate; }
    public Integer getSeasons() { return seasons; }
    public void setSeasons(Integer seasons) { this.seasons = seasons; }
    public Double getBestYield() { return bestYield; }
    public void setBestYield(Double bestYield) { this.bestYield = bestYield; }
    public LocalDate getLastHarvestDate() { return lastHarvestDate; }
    public void setLastHarvestDate(LocalDate d) { this.lastHarvestDate = d; }
    public LocalDateTime getSeasonStartedAt() { return seasonStartedAt; }
    public void setSeasonStartedAt(LocalDateTime v) { this.seasonStartedAt = v; }
    public String getCropId() { return cropId; }
    public void setCropId(String v) { this.cropId = v; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String v) { this.fieldName = v; }

    public String getZoneId() { return zoneId; }
    public void setZoneId(String v) { this.zoneId = v; }

    public Boolean getZoneChosen() { return zoneChosen == null ? Boolean.FALSE : zoneChosen; }
    public void setZoneChosen(Boolean v) { this.zoneChosen = v; }

    public Boolean getAwaitingSow() { return awaitingSow == null ? Boolean.FALSE : awaitingSow; }
    public void setAwaitingSow(Boolean v) { this.awaitingSow = v; }

    public Integer getDemoOffsetDays() { return demoOffsetDays; }
    public void setDemoOffsetDays(Integer v) { this.demoOffsetDays = v; }
    public Integer getSeasonProgressDays() { return seasonProgressDays; }
    public void setSeasonProgressDays(Integer v) { this.seasonProgressDays = v; }
    public Double getLastYield() { return lastYield; }
    public void setLastYield(Double y) { this.lastYield = y; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
