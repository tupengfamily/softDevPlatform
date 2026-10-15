package com.learning.springboot.dto;

import java.util.Map;

/**
 * 前端埋点批量上报(Beacon API 单次请求体,数组格式)
 *
 * <p>每条事件包含 type 字段,可选类型:
 * <ul>
 *   <li>page_view: 页面进入(由 SPA 路由 afterEach 触发)</li>
 *   <li>page_leave: 页面离开(beacon 在 unload 触发)</li>
 *   <li>page_error: JS 错误(window.onerror / unhandledrejection)</li>
 *   <li>page_perf: 性能指标(PerformanceObserver 上报)</li>
 *   <li>custom: 自定义业务事件(预留)</li>
 * </ul>
 */
public class TrackEventDto {

    public String sessionId;          // 必填,前端生成(uuid)
    public Long   userId;              // 可选(登录用户)
    public String type;                // 必填,见上
    public String url;                 // 当前页 URL
    public String routeName;           // 路由名
    public String pageTitle;           // document.title
    public String referrer;            // 上一个页 URL
    public String userAgent;           // navigator.userAgent
    public String screen;              // 1920x1080
    public Long   timestamp;         // 客户端采集时间(ms epoch)

    /** type=page_leave / page_perf 时必填 */
    public Long durationMs;            // 页面停留毫秒
    public Long enterAt;               // 进入时间(ms epoch)
    public Long leaveAt;               // 离开时间(ms epoch)

    /** type=page_perf 时填 */
    public Double fcp, lcp, fid, ttfb, domInteractive, loadComplete;  // ms
    public Double cls;                  // 浮点

    /** type=page_error 时填 */
    public String errorType;            // Error / UnhandledRejection
    public String errorMessage;
    public String stack;
    public String filename;
    public Integer lineno, colno;

    /** 自定义扩展字段 */
    public Map<String, Object> extra;
}