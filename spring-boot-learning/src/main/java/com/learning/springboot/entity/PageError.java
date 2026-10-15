package com.learning.springboot.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 页面 JS 错误日志(window.onerror + unhandledrejection)
 */
@Entity
@Table(name = "t_page_error", indexes = {
    @Index(name = "idx_pe_session", columnList = "session_id"),
    @Index(name = "idx_pe_route", columnList = "route_name"),
    @Index(name = "idx_pe_type", columnList = "error_type"),
    @Index(name = "idx_pe_at", columnList = "occur_at")
})
public class PageError {

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

    /** Error / UnhandledRejection */
    @Column(name = "error_type", nullable = false, length = 50)
    private String errorType;

    /** 错误消息 */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 堆栈(截前 4000 字符) */
    @Column(name = "stack", length = 4000)
    private String stack;

    /** 出错文件名 */
    @Column(name = "filename", length = 500)
    private String filename;

    /** 行号 */
    @Column(name = "lineno")
    private Integer lineno;

    /** 列号 */
    @Column(name = "colno")
    private Integer colno;

    @Column(name = "occur_at", nullable = false)
    private LocalDateTime occurAt;

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
    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getStack() { return stack; }
    public void setStack(String stack) { this.stack = stack; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public Integer getLineno() { return lineno; }
    public void setLineno(Integer lineno) { this.lineno = lineno; }
    public Integer getColno() { return colno; }
    public void setColno(Integer colno) { this.colno = colno; }
    public LocalDateTime getOccurAt() { return occurAt; }
    public void setOccurAt(LocalDateTime occurAt) { this.occurAt = occurAt; }
}