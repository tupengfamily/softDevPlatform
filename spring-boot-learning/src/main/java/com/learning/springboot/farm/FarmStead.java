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
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 农场：玩家在中国地图上选定的一个区县。
 *
 * <p><b>为什么要有这一层</b>：地块（{@link FarmPlot}）原本只有 {@code slot}，
 * 隐含「所有地都在同一个农业区」。一旦允许玩家在任意区县建场，
 * 就必须把「在哪儿」表达出来——区县决定气候（积温、无霜期、降水），
 * 气候决定能种什么、什么时候种。所以农场是地块的地理与气候上下文，不是装饰。
 *
 * <p>唯一约束 {@code (user_id, adcode)}：同一区县一个农场。
 * 这既符合直觉（在地图上标一个点），也避免同一块地上重复建场。
 */
@Entity
@Table(name = "t_farm_stead",
        uniqueConstraints = @UniqueConstraint(name = "uk_farm_stead_user_adcode",
                columnNames = {"user_id", "adcode"}),
        indexes = @Index(name = "idx_farm_stead_user", columnList = "user_id"))
public class FarmStead {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 区县行政区划代码（6 位）。地图选点的最小单位。 */
    @Column(nullable = false)
    private Integer adcode;

    /** 玩家给农场起的名字，默认「{区县名}农场」 */
    @Column(nullable = false, length = 64)
    private String name;

    @Column(name = "district_name", length = 64)
    private String districtName;

    @Column(name = "city_name", length = 64)
    private String cityName;

    @Column(name = "province_name", length = 64)
    private String provinceName;

    /** 区县中心点（地图打点用） */
    @Column(nullable = false)
    private Double lng;

    @Column(nullable = false)
    private Double lat;

    /** 由区县推导出的农业区：jiangnan / huanan / huanghuaihai / dongbei */
    @Column(name = "zone_id", length = 32)
    private String zoneId;

    /**
     * 开垦日期（世界日期口径，YYYY-MM-DD）。
     *
     * <p><b>为什么农场也需要日期</b>（2026-10-22 需求）：玩家建农场是在「某个时候」
     * 开垦的，这个时间决定了农场所在地的农时起点（比如 3 月建场 vs 9 月建场，
     * 当季能种什么完全不同）。而且需求明确要求「新建农场时不能选今天之前的日期」。
     *
     * <p>⚠️ 存 {@code LocalDate} → Hibernate 写 epoch 毫秒 INTEGER。
     * SQLite 是弱类型数据库，外部脚本写进去的 TEXT 日期会让整个查询报废
     * （见 {@code FarmDateTypePatch}），所以这一列必须进 {@code TARGETS}。
     *
     * <p>老农场没有这个值 → 视为「今天建立」（null 时前端按今天显示）。
     */
    @Column(name = "opened_on")
    private LocalDate openedOn;

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

    public Integer getAdcode() { return adcode; }
    public void setAdcode(Integer v) { this.adcode = v; }

    public String getName() { return name; }
    public void setName(String v) { this.name = v; }

    public String getDistrictName() { return districtName; }
    public void setDistrictName(String v) { this.districtName = v; }

    public String getCityName() { return cityName; }
    public void setCityName(String v) { this.cityName = v; }

    public String getProvinceName() { return provinceName; }
    public void setProvinceName(String v) { this.provinceName = v; }

    public Double getLng() { return lng; }
    public void setLng(Double v) { this.lng = v; }

    public Double getLat() { return lat; }
    public void setLat(Double v) { this.lat = v; }

    public String getZoneId() { return zoneId; }
    public void setZoneId(String v) { this.zoneId = v; }

    public LocalDate getOpenedOn() { return openedOn; }
    public void setOpenedOn(LocalDate v) { this.openedOn = v; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    /** 地图上显示的一行文字：省·市·县 */
    public String getLocationText() {
        StringBuilder sb = new StringBuilder();
        if (provinceName != null && !provinceName.equals(districtName)) {
            sb.append(provinceName);
        }
        if (cityName != null && !cityName.equals(districtName) && !cityName.equals(provinceName)) {
            if (sb.length() > 0) sb.append("·");
            sb.append(cityName);
        }
        if (districtName != null && !districtName.equals(provinceName)) {
            if (sb.length() > 0) sb.append("·");
            sb.append(districtName);
        }
        return sb.toString();
    }
}
