package com.learning.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OnlyOffice Document Server 集成配置
 *
 * <p>环境变量约定:
 * <ul>
 *   <li>OO_ENABLED=true|false: 总开关(默认 false → 走 v5 自绘编辑器)</li>
 *   <li>OO_URL=http://localhost:8088: Document Server 容器对外地址 (iframe src)</li>
 *   <li>OO_INTERNAL_URL=http://onlyoffice:80: 容器间通讯地址 (WOPI callback 用)</li>
 *   <li>OO_JWT_SECRET=...: 与容器 /etc/onlyoffice/local.json 的 services.CoAuthoring.secret 一致</li>
 *   <li>OO_JWT_HEADER=Authorization: JWT 在 HTTP 头中的字段名</li>
 * </ul>
 *
 * <p>部署方式: docker run -d --name onlyoffice -p 8088:80 \
 *   -e JWT_SECRET=... -e JWT_ENABLED=true onlyoffice/documentserver:9.4.0
 *
 * <p>License: AGPLv3 — 本项目(个人/内部/学习用途)闭源使用合规;SaaS 化需开源完整栈或购买 Enterprise.
 */
@ConfigurationProperties(prefix = "onlyoffice")
public class OnlyOfficeProperties {

    /** 总开关 */
    private boolean enabled = false;

    /** Document Server 浏览器可访问地址(渲染 iframe src) */
    private String url = "http://localhost:8088";

    /** 容器间通讯地址(WOPI callback 由 Document Server 内部调用,需用容器名) */
    private String internalUrl = "http://onlyoffice:80";

    /** JWT 共享密钥(必须与容器的 services.CoAuthoring.secret 匹配) */
    private String jwtSecret = "";

    /** JWT 是否启用(容器配置 JWT_ENABLED=true 后此项必须一致) */
    private boolean jwtEnabled = true;

    /** JWT 在 HTTP 头中的字段名 */
    private String jwtHeader = "Authorization";

    /** 公开的内网地址(浏览器使用,用于 redirect) */
    public String getPublicUrl() { return url; }
    public String getInternalUrl() { return internalUrl; }
    public boolean isJwtEnabled() { return jwtEnabled; }
    public String getJwtSecret() { return jwtSecret; }
    public String getJwtHeader() { return jwtHeader; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setUrl(String url) { this.url = url; }
    public void setInternalUrl(String internalUrl) { this.internalUrl = internalUrl; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public void setJwtEnabled(boolean jwtEnabled) { this.jwtEnabled = jwtEnabled; }
    public void setJwtHeader(String jwtHeader) { this.jwtHeader = jwtHeader; }
}
