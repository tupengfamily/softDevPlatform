package com.learning.springboot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 页面性能数据(FCP / LCP / FID / CLS / TTFB / domInteractive / loadComplete)
 *
 * <p>Web Vitals: 业界通用指标
 * <ul>
 *   <li>FCP (First Contentful Paint) 首次内容绘制</li>
 *   <li>LCP (Largest Contentful Paint) 最大内容绘制</li>
 *   <li>FID (First Input Delay) 首次输入延迟</li>
 *   <li>CLS (Cumulative Layout Shift) 累计布局偏移</li>
 *   <li>TTFB (Time To First Byte) 首字节时间</li>
 * </ul>
 */
@Entity
@Table(name = "t_page_perf", indexes = {
    @Index(name = "idx_pp_route", columnList = "route_name"),
    @Index(name = "idx_pp_at", columnList = "sample_at")
})
public class PagePerf {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "url", nullable = false, length = 500)
    private String url;

    @Column(name = "route_name", length = 100)
    private String routeName;

    /** 首次内容绘制(FCP) - ms */
    @Column(name = "fcp")
    private Long fcp;

    /** 最大内容绘制(LCP) - ms */
    @Column(name = "lcp")
    private Long lcp;

    /** 首次输入延迟(FID) - ms */
    @Column(name = "fid")
    private Long fid;

    /** 累计布局偏移(CLS) - 浮点 × 1000 存整数 */
    @Column(name = "cls")
    private Integer cls;

    /** 首字节(TTFB) - ms */
    @Column(name = "ttfb")
    private Long ttfb;

    /** DOM 可交互 - ms */
    @Column(name = "dom_interactive")
    private Long domInteractive;

    /** 页面完全加载 - ms */
    @Column(name = "load_complete")
    private Long loadComplete;

    @Column(name = "sample_at", nullable = false)
    private LocalDateTime sampleAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getRouteName() { return routeName; }
    public void setRouteName(String routeName) { this.routeName = routeName; }
    public Long getFcp() { return fcp; }
    public void setFcp(Long fcp) { this.fcp = fcp; }
    public Long getLcp() { return lcp; }
    public void setLcp(Long lcp) { this.lcp = lcp; }
    public Long getFid() { return fid; }
    public void setFid(Long fid) { this.fid = fid; }
    public Integer getCls() { return cls; }
    public void setCls(Integer cls) { this.cls = cls; }
    public Long getTtfb() { return ttfb; }
    public void setTtfb(Long ttfb) { this.ttfb = ttfb; }
    public Long getDomInteractive() { return domInteractive; }
    public void setDomInteractive(Long domInteractive) { this.domInteractive = domInteractive; }
    public Long getLoadComplete() { return loadComplete; }
    public void setLoadComplete(Long loadComplete) { this.loadComplete = loadComplete; }
    public LocalDateTime getSampleAt() { return sampleAt; }
    public void setSampleAt(LocalDateTime sampleAt) { this.sampleAt = sampleAt; }
}