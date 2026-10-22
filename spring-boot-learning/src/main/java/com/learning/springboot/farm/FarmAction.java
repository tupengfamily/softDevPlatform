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
 * 玩家在某日做的一次农事操作。
 *
 * <p>粒度是"单日单动作"：施肥有持续天数（{@code durationDays}），其他操作（灌水/排水/打药/揭帘）持续 1 天。
 * 引擎回放时按 (date, type) 命中该操作，应用相应的胁迫修正。
 *
 * <p>查询模式：{@code findByUserIdAndDateBetweenOrderByDate} 拉取一段回放窗口的全部 action，
 * 引擎逐日叠加。
 */
@Entity
@Table(name = "t_farm_action", indexes = {
        @Index(name = "idx_farm_action_user", columnList = "user_id"),
        @Index(name = "idx_farm_action_range", columnList = "user_id,date"),
        @Index(name = "idx_farm_action_plot", columnList = "plot_id,date")
})
public class FarmAction {

    /** 农事类型（决定引擎内做哪种修正） */
    public enum Type {
        /** 灌水：补 50mm 水层（可重复） */
        IRRIGATE,
        /** 排水：排空水层（晒田/收前排水，可重复） */
        DRAIN,
        /** 通用施肥（旧版接口，保留兼容） */
        FERTILIZE,
        /** 打药（可重复） */
        PESTICIDE,
        /** 揭帘（冬季大棚保温） */
        UNCOVER,

        // ==== S10 水稻工序链（14 道里的可执行项）====
        /** 泡田 + 耙地：整地并建立浅水层 */
        PUDDLE,
        /** 施基肥 */
        BASE_FERT,
        /** 插秧（移栽） */
        TRANSPLANT,
        /** 分蘖肥（DVS 0.16–0.34） */
        TILLER_FERT,
        /** 够苗晒田（DVS 0.36–0.44） */
        DRY_FIELD,
        /** 穗肥（DVS 0.41–0.55） */
        PANICLE_FERT,
        /** 收前排水（DVS ≥0.92） */
        DRAIN_FINAL,
        /** 烘干（收后） */
        DRY_GRAIN,

        // ==== 需求 O（2026-10-22）：除草 ====
        /**
         * 人工除草：即时把杂草压力压下去。
         *
         * <p>与「打除草剂」（走 S12 农资通道，{@link Type#PESTICIDE} 之外单独记 itemId）
         * 的区别：人工除草立即生效但只能做一次；化学除草有 2–3 天延迟且有保护期。
         *
         * <p><b>为什么必须有它</b>：没有除草这个动作，玩家就少了一整条「田间管理」维度——
         * 而且点了除草按钮跟没点一样（假操作），比没有这个按钮更糟。
         */
        WEED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 所属地块 id。多地块下动作必须能区分"哪块地打的药"，历史数据按 null 归到 slot 0。 */
    @Column(name = "plot_id")
    private Long plotId;

    /** 操作发生的自然日（演示模式会被 demoOffsetDays 偏移） */
    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Type type;

    /** 持续天数。灌水/排水/打药/揭帘 = 1；施肥 = 默认 7。 */
    @Column(name = "duration_days", nullable = false)
    private Integer durationDays = 1;

    /** 农资 id（S12）：如 UREA / TRICYCLAZOLE；普通农事为 null */
    @Column(name = "item_id", length = 32)
    private String itemId;

    /** 实际用量（单位见农资目录：kg / g / ml 每亩） */
    @Column(name = "dose")
    private Double dose;

    /** 本次投入（元/亩），用于成本核算 */
    @Column(name = "cost")
    private Double cost;

    @Column(length = 80)
    private String note;

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
    public LocalDate getDate() { return date; }
    public void setDate(LocalDate v) { this.date = v; }
    public Type getType() { return type; }
    public void setType(Type v) { this.type = v; }
    public Integer getDurationDays() { return durationDays; }
    public void setDurationDays(Integer v) { this.durationDays = v; }
    public String getItemId() { return itemId; }
    public void setItemId(String v) { this.itemId = v; }
    public Double getDose() { return dose; }
    public void setDose(Double v) { this.dose = v; }
    public Double getCost() { return cost; }
    public void setCost(Double v) { this.cost = v; }
    public String getNote() { return note; }
    public void setNote(String v) { this.note = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
