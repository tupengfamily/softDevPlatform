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

import java.time.LocalDateTime;

/**
 * 仓库里的<b>农资库存</b>（S25）：种子、化肥、农药、除草剂等物资。
 *
 * <p><b>为什么要单独一张表，而不是塞进 {@link FarmWarehouse}</b>：
 * <ul>
 *   <li><b>数据结构不同</b>：作物批次要管「仓储期 / 变质 / 品质系数」，
 *       农资不管——农药过期不会自己变质，尿素放十年还是尿素；</li>
 *   <li><b>计量单位不同</b>：作物按 kg，农资有 kg / g / ml 三种单位，
 *       混在一张表里「数量」这一列就没有统一语义了；</li>
 *   <li><b>来源不同</b>：作物是「收获」产生的，农资是「购买」产生的，
 *       前者有入库日期要算仓储期，后者只有购入时间。</li>
 * </ul>
 *
 * <p><b>⚠️ 枚举必须 {@code @Enumerated(EnumType.STRING)}</b>：漏注解会让 Hibernate
 * 按序数建列（tinyint），与项目约定（{@code FarmSeason.status} 是 varchar）冲突，
 * 而且 SQLite 改不回类型，只能重建表（见 {@code FarmWarehouseSchemaPatch}）。
 *
 * <p><b>⚠️ 日期字段一律存 epoch 毫秒 INTEGER</b>：SQLite 是弱类型数据库，
 * 一行 TEXT 类型的日期就能让整个查询报废（见 {@code FarmDateTypePatch}）。
 */
@Entity
@Table(name = "t_farm_stock", indexes = {
        @Index(name = "idx_farm_stock_user", columnList = "user_id"),
        @Index(name = "idx_farm_stock_item", columnList = "user_id,item_id")
})
public class FarmStock {

    /** 农资库存状态。 */
    public enum Status {
        /** 在库中 */
        IN_STOCK,
        /** 已用尽 */
        DEPLETED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 农资 id（对应 {@code InputCatalog.Item.id()}，如 UREA / TRICYCLAZOLE） */
    @Column(name = "item_id", nullable = false, length = 32)
    private String itemId;

    /** 农资名（快照，防止货架改名后历史对不上） */
    @Column(name = "item_name", length = 64)
    private String itemName;

    /** 农资类别：FERT / PEST / HERB（快照 {@code InputCatalog.Kind}） */
    @Column(name = "item_kind", length = 16)
    private String itemKind;

    /** 计量单位：kg / g / ml（快照） */
    @Column(name = "unit", length = 8)
    private String unit;

    /** 所属农场 id（可空 = 全局库存） */
    @Column(name = "farm_id")
    private Long farmId;

    /** 剩余数量（按 {@link #unit} 计） */
    @Column(name = "quantity", nullable = false)
    private Double quantity = 0.0;

    /** 累计购入数量（与剩余分开记，用于展示「买过多少」） */
    @Column(name = "total_bought", nullable = false)
    private Double totalBought = 0.0;

    /** 单价（元/单位，来自 {@code InputCatalog}） */
    @Column(name = "price_per_unit")
    private Double pricePerUnit;

    /** 累计花费（元） */
    @Column(name = "total_cost", nullable = false)
    private Double totalCost = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status = Status.IN_STOCK;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
        if (quantity == null) {
            this.quantity = 0.0;
        }
        if (totalBought == null) {
            this.totalBought = 0.0;
        }
        if (totalCost == null) {
            this.totalCost = 0.0;
        }
    }

    @PreUpdate
    void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    public Long getId() { return id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long v) { this.userId = v; }

    public String getItemId() { return itemId; }
    public void setItemId(String v) { this.itemId = v; }

    public String getItemName() { return itemName; }
    public void setItemName(String v) { this.itemName = v; }

    public String getItemKind() { return itemKind; }
    public void setItemKind(String v) { this.itemKind = v; }

    public String getUnit() { return unit; }
    public void setUnit(String v) { this.unit = v; }

    public Long getFarmId() { return farmId; }
    public void setFarmId(Long v) { this.farmId = v; }

    public Double getQuantity() { return quantity; }
    public void setQuantity(Double v) { this.quantity = v; }

    public Double getTotalBought() { return totalBought; }
    public void setTotalBought(Double v) { this.totalBought = v; }

    public Double getPricePerUnit() { return pricePerUnit; }
    public void setPricePerUnit(Double v) { this.pricePerUnit = v; }

    public Double getTotalCost() { return totalCost; }
    public void setTotalCost(Double v) { this.totalCost = v; }

    public Status getStatus() { return status; }
    public void setStatus(Status v) { this.status = v; }

    public LocalDateTime getCreateTime() { return createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
}
