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
 * 仓库库存批次（S24）：一次收获进库的货。
 *
 * <p><b>为什么按「批次」而不是按「作物」存</b>：同一批货会随时间变质，
 * 而玩家可能分几天收了同一作物（甚至不同区的同一作物）。合并成一行就没法
 * 算出「这一批还能放几天」——所以每次收割写一行，各自算自己的仓储天数。
 *
 * <p>变卖时优先卖<b>临期</b>的批次（先过期先卖），避免玩家因为排序问题
 * 白扔一批。这一策略在 Service 层实现。
 *
 * <p><b>⚠️ 日期字段一律存 epoch 毫秒 INTEGER</b>：SQLite 是弱类型数据库，
 * declared type 只在 DDL 声明一次，实际存的值可以是任意类型；一行 TEXT 类型
 * 的日期就能让整个查询报废（见 {@code FarmDateTypePatch} 类注释）。
 */
@Entity
@Table(name = "t_farm_warehouse", indexes = {
        @Index(name = "idx_farm_warehouse_user", columnList = "user_id"),
        @Index(name = "idx_farm_warehouse_crop", columnList = "user_id,crop_id")
})
public class FarmWarehouse {

    /** 批次状态。 */
    public enum Status {
        /** 在库中（未变卖） */
        STORED,
        /** 已变卖 */
        SOLD,
        /** 已变质丢弃 */
        DISCARDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 作物 id */
    @Column(name = "crop_id", nullable = false, length = 32)
    private String cropId;

    /** 作物名（快照，防止作物库改名后历史对不上） */
    @Column(name = "crop_name", length = 64)
    private String cropName;

    /** 农业区 id（决定地区价格系数） */
    @Column(name = "zone_id", length = 32)
    private String zoneId;

    /** 来源地块 id */
    @Column(name = "plot_id")
    private Long plotId;

    /** 来源农场 id */
    @Column(name = "farm_id")
    private Long farmId;

    /** 数量（kg） */
    @Column(name = "quantity", nullable = false)
    private Double quantity = 0.0;

    /** 入库日期（世界日期口径） */
    @Column(name = "stored_date", nullable = false)
    private LocalDate storedDate;

    /**
     * 状态。
     *
     * <p>🔴 必须用 {@code EnumType.STRING}：项目约定是字符串存枚举
     * （见 {@code FarmSeason.status} 的 {@code varchar(16)}）。
     * 漏掉 {@code @Enumerated} 时 Hibernate 会按<b>序数</b>存成 tinyint，
     * 一旦枚举里调整过顺序，历史数据的含义就全错了——而且 SQLite 弱类型下
     * 读写还会静默错位，极难排查。
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.STORED;

    /**
     * 入库时的品质折价系数（1.0 = 正常）。
     *
     * <p>用于把「农残超标」这类<b>入库时就已注定</b>的扣价固化到批次上：
     * 农残粮收购价打 0.85 折，这个折价必须跟着货走——不能等到变卖时才算，
     * 否则玩家会看到「收割时提示净收益 X 元，仓库里却卖不出这个价」。
     */
    @Column(name = "quality_factor")
    private Double qualityFactor = 1.0;

    /** 变卖时实际成交单价（元/kg，null = 未变卖） */
    @Column(name = "sold_price")
    private Double soldPrice;

    /** 变卖时实际成交金额（元，null = 未变卖） */
    @Column(name = "sold_amount")
    private Double soldAmount;

    /**
     * 累计卖出重量（kg）。
     *
     * <p>⚠️ 与 {@link #quantity} 分开记：{@code quantity} 是**剩余**库存，
     * 部分卖出后它会变小；但「变卖记录」要展示的是**卖出去多少**，
     * 所以要单独累计（否则第二次卖出会把第一次的金额覆盖掉）。
     */
    @Column(name = "sold_kg")
    private Double soldKg;

    /** 变卖日期 */
    @Column(name = "sold_date")
    private LocalDate soldDate;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createTime == null) {
            this.createTime = now;
        }
        this.updateTime = now;
        if (this.quantity == null) {
            this.quantity = 0.0;
        }
        if (this.status == null) {
            this.status = Status.STORED;
        }
        if (this.qualityFactor == null) {
            this.qualityFactor = 1.0;
        }
        if (this.soldKg == null) {
            this.soldKg = 0.0;
        }
    }

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getCropId() { return cropId; }
    public void setCropId(String cropId) { this.cropId = cropId; }

    public String getCropName() { return cropName; }
    public void setCropName(String cropName) { this.cropName = cropName; }

    public String getZoneId() { return zoneId; }
    public void setZoneId(String zoneId) { this.zoneId = zoneId; }

    public Long getPlotId() { return plotId; }
    public void setPlotId(Long plotId) { this.plotId = plotId; }

    public Long getFarmId() { return farmId; }
    public void setFarmId(Long farmId) { this.farmId = farmId; }

    public Double getQuantity() { return quantity; }
    public void setQuantity(Double quantity) { this.quantity = quantity; }

    public LocalDate getStoredDate() { return storedDate; }
    public void setStoredDate(LocalDate storedDate) { this.storedDate = storedDate; }

    public Double getQualityFactor() { return qualityFactor; }
    public void setQualityFactor(Double qualityFactor) { this.qualityFactor = qualityFactor; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public Double getSoldPrice() { return soldPrice; }
    public void setSoldPrice(Double soldPrice) { this.soldPrice = soldPrice; }

    public Double getSoldAmount() { return soldAmount; }
    public void setSoldAmount(Double soldAmount) { this.soldAmount = soldAmount; }

    public Double getSoldKg() { return soldKg; }
    public void setSoldKg(Double soldKg) { this.soldKg = soldKg; }

    public LocalDate getSoldDate() { return soldDate; }
    public void setSoldDate(LocalDate soldDate) { this.soldDate = soldDate; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
