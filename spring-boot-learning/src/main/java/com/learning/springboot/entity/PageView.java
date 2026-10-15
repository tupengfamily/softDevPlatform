package com.learning.springboot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 页面访问日志(每条记录一次页面浏览)
 *
 * <p>字段:
 * <ul>
 *   <li>sessionId + userId: 关联会话和用户, 用于 UV 计算</li>
 *   <li>url + routeName + pageTitle: 业务标识</li>
 *   <li>referrer: 来源页面(上一跳), 用于用户旅程还原</li>
 *   <li>enterAt / leaveAt / durationMs: 停留时长</li>
 *   <li>userAgent + ip + screen: 设备/网络指纹, 用于多维分析</li>
 * </ul>
 *
 * <p>业界通用: 类似 Google Analytics / Sentry Breadcrumb / 百度统计的事件流水
 */
@Entity
@Table(name = "t_page_view", indexes = {
    @Index(name = "idx_pv_session", columnList = "session_id"),
    @Index(name = "idx_pv_user", columnList = "user_id"),
    @Index(name = "idx_pv_route", columnList = "route_name"),
    @Index(name = "idx_pv_enter", columnList = "enter_at")
})
public class PageView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 会话 ID(浏览器关闭或 30 分钟无活动过期) */
    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    /** 登录用户 ID(未登录为 null) */
    @Column(name = "user_id")
    private Long userId;

    /** 当前页 URL */
    @Column(name = "url", nullable = false, length = 500)
    private String url;

    /** 路由名(Vue route.name, 如 'LearningCenter') */
    @Column(name = "route_name", length = 100)
    private String routeName;

    /** 页面标题(document.title) */
    @Column(name = "page_title", length = 200)
    private String pageTitle;

    /** 来源 URL(上一跳页面 URL, 用于用户旅程还原) */
    @Column(name = "referrer", length = 500)
    private String referrer;

    /** User-Agent(浏览器 + OS 信息) */
    @Column(name = "user_agent", length = 500)
    private String userAgent;

    /** 解析后的操作系统(如 Windows 11 / macOS 14 / iOS 17 / Android 14) */
    @Column(name = "os", length = 50)
    private String os;

    /** 解析后的浏览器名 + 版本(如 Chrome 130 / Safari 17 / Edge 130) */
    @Column(name = "browser", length = 50)
    private String browser;

    /** 设备类型(Desktop / Mobile / Tablet) */
    @Column(name = "device_type", length = 20)
    private String deviceType;

    /** 客户端 IP */
    @Column(name = "ip", length = 64)
    private String ip;

    /** 屏幕分辨率(1920x1080 等) */
    @Column(name = "screen", length = 50)
    private String screen;

    /** 进入时间 */
    @Column(name = "enter_at", nullable = false)
    private LocalDateTime enterAt;

    /** 离开时间(null=当前还在页面) */
    @Column(name = "leave_at")
    private LocalDateTime leaveAt;

    /** 停留时长(毫秒), leave 时填写 */
    @Column(name = "duration_ms")
    private Long durationMs;

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
    public String getPageTitle() { return pageTitle; }
    public void setPageTitle(String pageTitle) { this.pageTitle = pageTitle; }
    public String getReferrer() { return referrer; }
    public void setReferrer(String referrer) { this.referrer = referrer; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getOs() { return os; }
    public void setOs(String os) { this.os = os; }
    public String getBrowser() { return browser; }
    public void setBrowser(String browser) { this.browser = browser; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getScreen() { return screen; }
    public void setScreen(String screen) { this.screen = screen; }
    public LocalDateTime getEnterAt() { return enterAt; }
    public void setEnterAt(LocalDateTime enterAt) { this.enterAt = enterAt; }
    public LocalDateTime getLeaveAt() { return leaveAt; }
    public void setLeaveAt(LocalDateTime leaveAt) { this.leaveAt = leaveAt; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
}