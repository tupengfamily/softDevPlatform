package com.learning.springboot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 天地图（Tianditu）后端代理配置。
 *
 * <p><b>为什么走服务端代理</b>：天地图有「浏览器端」和「服务端」两种 key。
 * 浏览器端 key 直接在浏览器里调会暴露给任何用户（DevTools 一看即得），
 * 而服务端 key 放在前端 JS 里等于把 key 公开。折中做法是<b>服务端持 key 代调</b>，
 * 浏览器只跟自己的后端说话 —— 这是本类存在的唯一理由。
 *
 * <p><b>两种 key 的区别（实测）</b>：
 * <ul>
 *   <li>服务端 key：可直接在服务端 curl 调通全部接口（瓦片 / 地名搜索 / 地理编码）</li>
 *   <li>浏览器端 key：放到服务端调用会返回 <code>403 {"code":301012,"msg":"权限类型错误"}</code>，
 *       提示「Key权限类型为:浏览器端，请使用浏览器访问！」—— 所以代理层必须用服务端 key</li>
 * </ul>
 *
 * <p><b>环境变量约定</b>：
 * <ul>
 *   <li>{@code TIANDITU_SERVER_KEY}: 服务端 key（<b>必填</b>，代理层用）</li>
 *   <li>{@code TIANDITU_BROWSER_KEY}: 浏览器端 key（仅供前端直连瓦片时的兜底，本后端不用）</li>
 *   <li>{@code TIANDITU_ENABLED}: 总开关（默认 true；关掉后地图相关端点直接 503）</li>
 *   <li>{@code TIANDITU_BASE_URL}: API 根地址（默认 https://api.tianditu.gov.cn）</li>
 *   <li>{@code TIANDITU_TILE_HOST}: 瓦片域名模板（默认 https://t{0-7}.tianditu.gov.cn）</li>
 *   <li>{@code TIANDITU_CONNECT_TIMEOUT_MS}: 连接超时（默认 3000）</li>
 *   <li>{@code TIANDITU_READ_TIMEOUT_MS}: 读取超时（默认 8000）</li>
 *   <li>{@code TIANDITU_MAX_CACHE_ENTRIES}: 搜索/地理编码结果内存缓存条目上限（默认 500）</li>
 * </ul>
 *
 * <p><b>安全约定</b>：{@code serverKey} 只在服务端内存中存在，<b>绝不能</b>写进
 * {@code application.yml} 提交到仓库，也绝不能出现在任何响应体里。
 * 瓦片代理层不下发 key，只下发瓦片字节流。
 */
@ConfigurationProperties(prefix = "tianditu")
public class TianDiTuProperties {

    /** 总开关：关掉后所有天地图端点返回 503（避免无 key 时疯狂打上游报错） */
    private boolean enabled = true;

    /** 服务端 key（天地图控制台申请的「服务端」类型 key）。为空时地图功能不可用。 */
    private String serverKey = "";

    /** 浏览器端 key（备用：仅当未来前端要直连瓦片时使用；本代理层不使用） */
    private String browserKey = "";

    /** API 根地址（地名搜索 / 地理编码） */
    private String baseUrl = "https://api.tianditu.gov.cn";

    /** 瓦片域名模板，{0} 为 0-7 的机器编号（天地图有 t0~t7 共 8 台，可轮询分流） */
    private String tileHostTemplate = "https://t{0}.tianditu.gov.cn";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读取超时（毫秒） */
    private int readTimeoutMs = 8000;

    /** 搜索 / 地理编码结果内存缓存条目上限（LRU 淘汰） */
    private int maxCacheEntries = 500;

    /** 缓存条目存活时长（毫秒），默认 30 分钟；天地图的行政区划/POI 数据几乎不变 */
    private long cacheTtlMs = 30 * 60 * 1000L;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getServerKey() { return serverKey; }
    public void setServerKey(String serverKey) { this.serverKey = serverKey; }

    public String getBrowserKey() { return browserKey; }
    public void setBrowserKey(String browserKey) { this.browserKey = browserKey; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getTileHostTemplate() { return tileHostTemplate; }
    public void setTileHostTemplate(String tileHostTemplate) { this.tileHostTemplate = tileHostTemplate; }

    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }

    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }

    public int getMaxCacheEntries() { return maxCacheEntries; }
    public void setMaxCacheEntries(int maxCacheEntries) { this.maxCacheEntries = maxCacheEntries; }

    public long getCacheTtlMs() { return cacheTtlMs; }
    public void setCacheTtlMs(long cacheTtlMs) { this.cacheTtlMs = cacheTtlMs; }

    /**
     * 服务端 key 是否可用。
     * <p>没有 key 时所有上游调用都会 4xx 失败，不如直接短路返回可读错误。
     */
    public boolean hasServerKey() {
        return serverKey != null && !serverKey.isBlank();
    }

    /**
     * 取瓦片域名：把模板里的 {0} 替换成 0-7 的机器编号。
     *
     * @param index 机器编号（任意整数，内部取模落到 0-7）
     */
    public String tileHost(int index) {
        int i = Math.floorMod(index, 8);
        return tileHostTemplate.replace("{0}", String.valueOf(i));
    }
}
