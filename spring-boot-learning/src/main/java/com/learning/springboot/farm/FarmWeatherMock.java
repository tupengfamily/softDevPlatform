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
 * 测试用模拟天气段。
 *
 * <p>玩家或开发者为某一日期段注入一个固定的气象值（tmax / tmin / precip），
 * 回放引擎读取该用户某天天气时优先返回 mock 值，否则回落到真实重放。
 *
 * <p>🔴 <b>这是测试豁免</b>：基线 D2 要求 1:1 真实时间与真实天气，本表只允许在
 * 演示/测试模式（{@code farm.demo-mode=true}）下使用。正式部署必须禁止该表
 * 的写入——见 {@code FarmController#applyMock} 的闸门。
 */
@Entity
@Table(name = "t_farm_weather_mock", indexes = {
        @Index(name = "idx_farm_weather_mock_user", columnList = "user_id"),
        @Index(name = "idx_farm_weather_mock_range", columnList = "user_id,from_date,to_date")
})
public class FarmWeatherMock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 段开始日（包含） */
    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    /** 段结束日（包含） */
    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(nullable = false)
    private Double tmax;

    @Column(nullable = false)
    private Double tmin;

    @Column(nullable = false)
    private Double precip;

    /** 玩家或测试用例填的备注，如「高温逼熟 5 天」 */
    @Column(length = 100)
    private String label;

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
    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate v) { this.fromDate = v; }
    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate v) { this.toDate = v; }
    public Double getTmax() { return tmax; }
    public void setTmax(Double v) { this.tmax = v; }
    public Double getTmin() { return tmin; }
    public void setTmin(Double v) { this.tmin = v; }
    public Double getPrecip() { return precip; }
    public void setPrecip(Double v) { this.precip = v; }
    public String getLabel() { return label; }
    public void setLabel(String v) { this.label = v; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
