package com.learning.springboot.farm;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 地块的「一季」记录——种植与收获的完整档案。
 *
 * <p><b>为什么需要这张表（2026-10-22）</b>：原先 {@code t_farm_plot} 只存**当前**这一季的
 * 状态（当前作物、当前播种日、最近一次产量），收完一季就把上一季的细节覆盖掉了。
 * 玩家在农场详情里看不到「这块地历史上种过什么、收了多少、为什么减产」。
 *
 * <p>本表按「地块 + 季次」存档，每次建田 / 收割各写一条：
 * <ul>
 *   <li><b>建田时</b>写一条 {@link Status#GROWING}，记录选了哪个作物、什么时候播的；</li>
 *   <li><b>收割时</b>把这条补全（产量、收获日、归因明细、最终状态）。</li>
 * </ul>
 * 所以一行同时承载「种植记录」和「收获记录」两段信息。
 *
 * <p><b>状态取值</b>：
 * <ul>
 *   <li>{@code GROWING} —— 在长的这一季；</li>
 *   <li>{@code HARVESTED} —— 正常收获完成；</li>
 *   <li>{@code FAILED} —— 这一季没活下来（枯死 / 绝收 / 灾害毁田）；</li>
 *   <li>{@code ABANDONED} —— 玩家主动放弃（重开一季）。</li>
 * </ul>
 */
@Entity
@Table(name = "t_farm_season", indexes = {
        @Index(name = "idx_farm_season_user", columnList = "user_id"),
        @Index(name = "idx_farm_season_plot", columnList = "plot_id,season_no"),
        @Index(name = "idx_farm_season_farm", columnList = "farm_id,user_id")
})
public class FarmSeason {

    /** 一季的最终状态。 */
    public enum Status {
        /** 在长（还没收割） */
        GROWING,
        /** 正常收获 */
        HARVESTED,
        /** 失败：枯死 / 绝收 / 灾害毁田 */
        FAILED,
        /** 玩家主动放弃 */
        ABANDONED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 地块 id */
    @Column(name = "plot_id", nullable = false)
    private Long plotId;

    /** 所属农场（可空：v1 历史地块无归属） */
    @Column(name = "farm_id")
    private Long farmId;

    /** 槽位号，方便前端直接排序显示 */
    @Column(name = "slot", nullable = false)
    private Integer slot = 0;

    /** 第几季（从 1 开始；同一块地内递增） */
    @Column(name = "season_no", nullable = false)
    private Integer seasonNo = 1;

    /** 作物 id */
    @Column(name = "crop_id", length = 32)
    private String cropId;

    /** 作物名（快照，防止作物库改名后历史对不上） */
    @Column(name = "crop_name", length = 64)
    private String cropName;

    /** 3D 形态族（快照） */
    @Column(name = "visual", length = 16)
    private String visual;

    /** 农业区 id */
    @Column(name = "zone_id", length = 32)
    private String zoneId;

    /** 地块名（快照） */
    @Column(name = "field_name", length = 64)
    private String fieldName;

    /** 播种日 */
    @Column(name = "sow_date", nullable = false)
    private LocalDate sowDate;

    /** 收获日（未收获为 null） */
    @Column(name = "harvest_date")
    private LocalDate harvestDate;

    /** 本季最终 DVS */
    @Column(name = "dvs")
    private Double dvs;

    /** 实际产量 kg/亩（未收获为 null） */
    @Column(name = "yield_kg_per_mu")
    private Double yieldKgPerMu;

    /** 理论产量 kg/亩（未封顶的原始测算值，用于展示"被封顶了多少"） */
    @Column(name = "yield_raw")
    private Double yieldRaw;

    /** 出苗率 0..1（发芽率；未收获为 null） */
    @Column(name = "germination")
    private Double germination;

    /** 最终状态 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.GROWING;

    /** 异常类型（正常为 null）：WITHERED 枯死 / FROST 霜冻 / DISASTER 灾害 / DROUGHT 旱情 等 */
    @Column(name = "failure_type", length = 24)
    private String failureType;

    /** 异常说明（人类可读，如「播期偏晚，缺苗 42%」） */
    @Column(name = "failure_note", length = 240)
    private String failureNote;

    /** 归因明细 JSON（产量三要素损伤、投入产出、农残等） */
    @Column(name = "detail", length = 4000)
    private String detail;

    /** 投入成本（元/亩） */
    @Column(name = "input_cost")
    private Double inputCost;

    /** 产值（元/亩） */
    @Column(name = "revenue")
    private Double revenue;

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
    public void setUserId(Long v) { this.userId = v; }
    public Long getPlotId() { return plotId; }
    public void setPlotId(Long v) { this.plotId = v; }
    public Long getFarmId() { return farmId; }
    public void setFarmId(Long v) { this.farmId = v; }
    public Integer getSlot() { return slot; }
    public void setSlot(Integer v) { this.slot = v; }
    public Integer getSeasonNo() { return seasonNo; }
    public void setSeasonNo(Integer v) { this.seasonNo = v; }
    public String getCropId() { return cropId; }
    public void setCropId(String v) { this.cropId = v; }
    public String getCropName() { return cropName; }
    public void setCropName(String v) { this.cropName = v; }
    public String getVisual() { return visual; }
    public void setVisual(String v) { this.visual = v; }
    public String getZoneId() { return zoneId; }
    public void setZoneId(String v) { this.zoneId = v; }
    public String getFieldName() { return fieldName; }
    public void setFieldName(String v) { this.fieldName = v; }
    public LocalDate getSowDate() { return sowDate; }
    public void setSowDate(LocalDate v) { this.sowDate = v; }
    public LocalDate getHarvestDate() { return harvestDate; }
    public void setHarvestDate(LocalDate v) { this.harvestDate = v; }
    public Double getDvs() { return dvs; }
    public void setDvs(Double v) { this.dvs = v; }
    public Double getYieldKgPerMu() { return yieldKgPerMu; }
    public void setYieldKgPerMu(Double v) { this.yieldKgPerMu = v; }
    public Double getYieldRaw() { return yieldRaw; }
    public void setYieldRaw(Double v) { this.yieldRaw = v; }
    public Double getGermination() { return germination; }
    public void setGermination(Double v) { this.germination = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }
    public String getFailureType() { return failureType; }
    public void setFailureType(String v) { this.failureType = v; }
    public String getFailureNote() { return failureNote; }
    public void setFailureNote(String v) { this.failureNote = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { this.detail = v; }
    public Double getInputCost() { return inputCost; }
    public void setInputCost(Double v) { this.inputCost = v; }
    public Double getRevenue() { return revenue; }
    public void setRevenue(Double v) { this.revenue = v; }
}
