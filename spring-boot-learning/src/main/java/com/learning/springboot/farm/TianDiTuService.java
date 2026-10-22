package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.config.TianDiTuProperties;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 天地图后端代理服务。
 *
 * <p><b>职责</b>：持服务端 key 代调天地图，把天地图的原始 JSON 结构透传给前端，
 * 同时把上游的各种脏活（鉴权失败、参数错误、网络抖动、限流）翻译成
 * 统一的可读错误。
 *
 * <p><b>为什么用 JDK 自带 HttpClient</b>：本项目不引入 RestTemplate/WebClient，
 * 避免为「一个代理」增加 Maven 依赖（离线构建环境拉不到新依赖会直接构建失败）。
 * JDK 11+ 的 {@link HttpClient} 足够，且零依赖。
 *
 * <p><b>关键 API 事实（全部实测确认，不要凭印象改）</b>：
 * <ol>
 *   <li><b>鉴权参数是 {@code tk}，不是 {@code key}</b>。用 {@code key} 会返回
 *       {@code 400 {"code":308010,"msg":"请求参数错误"}}</li>
 *   <li><b>地址编码（地名→坐标）用 {@code ds=} 参数，不是 {@code postStr=}</b>。
 *       实测 {@code postStr={"keyWord":...}} 会返回
 *       {@code 400 {"msg":"lon/lat 参数未填写"}} —— 因为 postStr 通道只认逆地理编码。
 *       正确姿势：{@code /geocoder?ds={urlencoded json}&type=geocode&tk=...}</li>
 *   <li><b>逆地理编码（坐标→地址）用 {@code postStr=} 参数</b>：
 *       {@code /geocoder?postStr={urlencoded json}&type=geocode&tk=...}</li>
 *   <li><b>地名搜索必须带 {@code mapBound}</b>，否则返回
 *       {@code 400 {"msg":"缺少参数：mapBound","status":400}}</li>
 *   <li><b>搜索的 {@code level} 也必填</b>，否则
 *       {@code 400 {"msg":"缺少参数：level"}}</li>
 *   <li><b>瓦片路径与 LAYER 是「去掉 _w 后缀」的一对</b>：
 *       路径 {@code img_w} 对应 {@code LAYER=img}。传 {@code LAYER=img_w} 也能 200，
 *       但返回的是空白占位瓦片（同样 4769 字节的纯色 PNG）—— 地图会「有图无内容」。</li>
 *   <li><b>瓦片 content-type 不固定</b>：影像/地形图层返回 {@code image/jpg}，
 *       矢量/注记图层返回 {@code image/png}。所以透传时必须用上游的实际 content-type，
 *       不能硬写 image/png。</li>
 * </ol>
 */
@Service
public class TianDiTuService {

    private static final Logger log = LoggerFactory.getLogger(TianDiTuService.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 代理层对外声明的 User-Agent（服务端形态）。
     *
     * <p><b>绝对不能包含 "Mozilla"</b>：天地图按 UA 判断请求方是不是浏览器，
     * 服务端 key 配浏览器 UA 会被拒（403 / code 301013
     * 「Key权限类型为:服务器端，请使用服务器端访问！」）。
     */
    private static final String USER_AGENT = "SpringBootTianDiTuProxy/1.0";

    /**
     * 浏览器形态的 User-Agent（用于适配「浏览器端 key」）。
     *
     * <p><b>为什么需要它</b>：天地图的 key 分「浏览器端 / 服务端」两种，<b>与 UA 严格互斥</b>：
     * <ul>
     *   <li>服务端 key + 服务端 UA → 正常；</li>
     *   <li>服务端 key + 浏览器 UA → 403 / code 301013；</li>
     *   <li>浏览器端 key + 服务端 UA → 403 / code 301012；</li>
     *   <li>浏览器端 key + 浏览器 UA → 正常。</li>
     * </ul>
     * 管理员在控制台申请 key 时不一定清楚自己拿的是哪一种, 而本项目是「后端代理」形态,
     * 天然要求服务端 key。但如果管理员手头只有浏览器端 key, 整张地图就全废了。
     *
     * <p><b>解法</b>: 上游在报错里会明确写出 key 的真实类型
     * （{@code resolve: "Key权限类型为:浏览器端，请使用浏览器访问！"}）,
     * 据此自动切换 UA 重试一次, 并把结论缓存下来, 后续请求直接用对的 UA。
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * 已探测出的 key 类型 → 应当使用的 UA。
     *
     * <p>key 是固定的一把, 类型不会变, 所以探测一次就够, 不必每次请求都试错。
     * key 为 null（未配置）时不会走到这里。
     */
    private final Map<String, String> uaByKey = new ConcurrentHashMap<>();

    /**
     * 瓦片图层白名单 —— 同时给出「URL 路径段」和「WMTS LAYER 参数」的对应关系。
     *
     * <p><b>这是安全边界，不是配置项</b>：瓦片代理会把请求转发到
     * {@code https://t{n}.tianditu.gov.cn/{layerType}/wmts}，
     * 若 layerType 允许任意字符串，攻击者就能构造
     * {@code ../../other-host/path} 之类的路径做 SSRF。
     * 因此 layerType 必须是固定枚举值。
     */
    private static final Map<String, String> LAYER_WHITELIST = new LinkedHashMap<>();

    static {
        LAYER_WHITELIST.put("vec_w", "vec");   // 矢量底图
        LAYER_WHITELIST.put("img_w", "img");   // 卫星影像
        LAYER_WHITELIST.put("ter_w", "ter");   // 地形晕渲
        LAYER_WHITELIST.put("cva_w", "cva");   // 矢量中文注记
        LAYER_WHITELIST.put("cia_w", "cia");   // 影像中文注记
        LAYER_WHITELIST.put("cta_w", "cta");   // 地形中文注记
    }

    /** 允许的图层名集合（对外暴露，供 Controller 做白名单提示） */
    public static final Set<String> ALLOWED_LAYERS = LAYER_WHITELIST.keySet();

    /** 默认 mapBound：中国全境（含周边）范围，lon_min,lat_min,lon_max,lat_max */
    private static final String DEFAULT_MAP_BOUND = "73.66,3.86,135.05,53.55";

    /** 统一密钥库中的天地图服务端密钥标识 */
    public static final String API_KEY_TIANDITU_SERVER = "tianditu.serverKey";

    private final TianDiTuProperties props;
    private final HttpClient httpClient;
    /** 可替换的 HTTP 执行器（测试用；生产环境为 null，走默认 HttpClient） */
    private final HttpExecutor executor;
    /**
     * 统一密钥库（管理员在「系统管理 → API 密钥」里配置）。
     *
     * <p>为 null 时（单元测试构造路径）退化为只读环境变量。
     */
    private final ApiKeyService apiKeyService;

    /**
     * 搜索/地理编码结果缓存：key → (缓存值, 写入时间戳)。
     *
     * <p><b>为什么缓存</b>：天地图的行政区划/POI 数据几乎是静态的，
     * 同一个关键词反复查是纯浪费上游配额（天地图按调用量计费/限流）。
     * <b>为什么不用 Spring Cache</b>：项目已引入 Caffeine，但 {@code @Cacheable}
     * 需要 SpEL key 表达式，这里直接手写更直白，且缓存的是「已解析好的 JSON 树」
     * 这类非标准返回值，注解方式反而绕。
     *
     * <p><b>淘汰策略</b>：{@code ConcurrentHashMap + 时间戳}，
     * 写满 {@code maxCacheEntries} 时清空整表（简单的"全清"式 LRU 近似），
     * 避免复杂的链表维护开销 —— 教学项目里 500 条量级完全够用。
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(Object value, long storedAt) {
        boolean expired(long now, long ttl) {
            return now - storedAt > ttl;
        }
    }

    /**
     * 生产构造器（Spring 注入用）。
     * <p>类里有两个构造器，必须显式标注本个，否则 Spring 无法决定用哪个
     * （会去找无参构造器并失败）。
     */
    @Autowired
    public TianDiTuService(TianDiTuProperties props, ApiKeyService apiKeyService) {
        this.props = props;
        this.apiKeyService = apiKeyService;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, props.getConnectTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.executor = null;
    }

    /**
     * 可注入 HTTP 执行器的构造器（供单元测试用）。
     *
     * <p>把「发请求」抽象成一个函数式接口，测试可以完全替换掉网络层：
     * 既不用起真实服务器，也不用 mock 整个 {@link HttpClient}
     * （HttpClient 是抽象类，mock 需要实现二十多个方法，噪音大于收益）。
     *
     * @param props     配置
     * @param executor  HTTP 执行器；传 null 则使用内部 HttpClient
     */
    TianDiTuService(TianDiTuProperties props, HttpExecutor executor) {
        this(props, executor, null);
    }

    TianDiTuService(TianDiTuProperties props, HttpExecutor executor, ApiKeyService apiKeyService) {
        this.props = props;
        this.executor = executor;
        this.httpClient = null;
        this.apiKeyService = apiKeyService;
    }

    /**
     * HTTP 执行器（函数式接口）。
     *
     * @param url 完整请求 URL
     * @return 响应（状态码 + 字节体 + content-type）
     */
    @FunctionalInterface
    interface HttpExecutor {
        RawResponse execute(String url) throws IOException, InterruptedException;
    }

    /**
     * 上游原始响应。
     *
     * @param status      HTTP 状态码
     * @param body        响应字节
     * @param contentType content-type 响应头
     */
    record RawResponse(int status, byte[] body, String contentType) {}

    // ==================================================================
    //  公开 API
    // ==================================================================

    /**
     * 地名搜索（v2/search）。
     *
     * <p>返回天地图的<b>原始 JSON 结构</b>（已解析成 JsonNode），因为前端要按
     * {@code resultType} 分支处理：
     * <ul>
     *   <li>{@code resultType=1} → POI 列表（{@code pois[]}）</li>
     *   <li>{@code resultType=2} → 按城市统计（{@code statistics.priorityCitys}）</li>
     *   <li>{@code resultType=3} → 行政区划（{@code area} 对象：
     *       {@code name}/{@code adminCode}/{@code lonlat}/{@code bound}）</li>
     * </ul>
     * 前端自己决定怎么渲染，代理层不做语义裁剪。
     *
     * @param keyword  搜索关键词（行政区名 / POI 名）
     * @param level    搜索层级：12=区县、18=POI 兴趣点、4=省市统计（实测语义）
     * @param mapBound 搜索范围 {@code lon_min,lat_min,lon_max,lat_max}；为空则用中国全境
     * @param start    分页起始
     * @param count    返回条数
     * @return 天地图返回的 JSON 树
     */
    public JsonNode search(String keyword, int level, String mapBound, int start, int count) {
        String kw = requireText(keyword, "搜索关键词不能为空");
        String bound = (mapBound == null || mapBound.isBlank()) ? DEFAULT_MAP_BOUND : mapBound.trim();
        int from = Math.max(0, start);
        int size = Math.min(Math.max(1, count), 50);   // 防御：防止一次拉爆上游

        String cacheKey = "search|" + kw + "|" + level + "|" + bound + "|" + from + "|" + size;
        JsonNode cached = getCached(cacheKey);
        if (cached != null) {
            log.debug("[天地图] 搜索命中缓存: {}", kw);
            return cached;
        }

        String postStr = buildJson(
                "keyWord", kw,
                "level", String.valueOf(level),
                "mapBound", bound,
                "queryType", "1",
                "start", String.valueOf(from),
                "count", String.valueOf(size)
        );

        String url = props.getBaseUrl() + "/v2/search"
                + "?postStr=" + urlEncode(postStr)
                + "&type=query"
                + "&tk=" + urlEncode(resolveServerKey());

        JsonNode result = getJson(url, "地名搜索");
        putCached(cacheKey, result);
        return result;
    }

    /**
     * 地址编码（地名 → 坐标）。
     *
     * <p><b>注意参数通道</b>：天地图的地址编码走 {@code ds=}，
     * 而逆地理编码走 {@code postStr=}。用错通道会得到
     * {@code "lon/lat 参数未填写"} 这种看起来毫不相关的报错。
     *
     * <p>返回结构（实测）：{@code {"msg":"ok","location":{"score":100,
     * "level":"区县及以上级行政区划","lon":"116.69877","lat":"29.00868",
     * "keyWord":"鄱阳县"},"searchVersion":"7.5.0V","status":"0"}}
     *
     * @param keyword 地址/地名关键词
     * @param level   层级（12=区县及以上行政区划；不传则由天地图自动判定）
     * @return 天地图返回的 JSON 树
     */
    public JsonNode geocode(String keyword, int level) {
        String kw = requireText(keyword, "地址关键词不能为空");

        String cacheKey = "geocode|" + kw + "|" + level;
        JsonNode cached = getCached(cacheKey);
        if (cached != null) {
            log.debug("[天地图] 地址编码命中缓存: {}", kw);
            return cached;
        }

        StringBuilder ds = new StringBuilder("{\"keyWord\":").append(jsonString(kw));
        if (level > 0) {
            ds.append(",\"level\":").append(level);
        }
        ds.append('}');

        String url = props.getBaseUrl() + "/geocoder"
                + "?ds=" + urlEncode(ds.toString())
                + "&type=geocode"
                + "&tk=" + urlEncode(resolveServerKey());

        JsonNode result = getJson(url, "地址编码");
        putCached(cacheKey, result);
        return result;
    }

    /**
     * 逆地理编码（坐标 → 地址）。
     *
     * <p><b>注意参数通道</b>：走 {@code postStr=}，与地址编码相反。
     *
     * <p>返回结构（实测）：{@code {"result":{"formatted_address":"北京市东城区...",
     * "location":{"lon":116.4,"lat":39.9},"addressComponent":{"province","city",
     * "county","county_code","town",...}},"msg":"ok","status":"0"}}
     *
     * @param lon 经度（度）
     * @param lat 纬度（度）
     * @return 天地图返回的 JSON 树
     */
    public JsonNode reverseGeocode(double lon, double lat) {
        if (lon < -180.0 || lon > 180.0) {
            throw new BusinessException("经度超出范围: " + lon + "（应在 -180 ~ 180 之间）");
        }
        if (lat < -90.0 || lat > 90.0) {
            throw new BusinessException("纬度超出范围: " + lat + "（应在 -90 ~ 90 之间）");
        }

        // 坐标做 4 位小数取整后再查：11cm 精度足够，缓存命中率显著提升
        double roundedLon = Math.round(lon * 10000.0) / 10000.0;
        double roundedLat = Math.round(lat * 10000.0) / 10000.0;

        String cacheKey = "reverse|" + roundedLon + "|" + roundedLat;
        JsonNode cached = getCached(cacheKey);
        if (cached != null) {
            log.debug("[天地图] 逆地理编码命中缓存: {},{}", roundedLon, roundedLat);
            return cached;
        }

        String postStr = "{\"lon\":" + roundedLon + ",\"lat\":" + roundedLat + ",\"ver\":1}";
        String url = props.getBaseUrl() + "/geocoder"
                + "?postStr=" + urlEncode(postStr)
                + "&type=geocode"
                + "&tk=" + urlEncode(resolveServerKey());

        JsonNode result = getJson(url, "逆地理编码");
        putCached(cacheKey, result);
        return result;
    }

    /**
     * 取瓦片（代理透传）。
     *
     * <p>返回<b>字节流 + content-type</b>，而不是 String —— 瓦片是二进制，
     * 转成 String 会破坏图片数据。
     *
     * <p><b>content-type 必须用上游实际返回值</b>：实测影像/地形图层返回
     * {@code image/jpg}，矢量/注记图层返回 {@code image/png}。硬写 image/png 会让
     * 浏览器在 <code>nosniff</code> 响应头下拒绝渲染 JPEG 瓦片。
     *
     * @param layerType 图层类型（必须是白名单里的 6 个之一）
     * @param z         缩放层级
     * @param x         瓦片列号
     * @param y         瓦片行号
     * @param hostIndex 瓦片机器编号（0-7，内部取模；用于 8 台机器轮询分流）
     * @return 瓦片响应（字节数组 + content-type）
     */
    public TileResponse fetchTile(String layerType, int z, int x, int y, int hostIndex) {
        String wmtsLayer = LAYER_WHITELIST.get(layerType);
        if (wmtsLayer == null) {
            // 白名单校验失败 = 拒绝，而不是"透传看看天地图怎么说"
            throw new BusinessException("不支持的瓦片图层: " + layerType
                    + "（允许: " + String.join(", ", ALLOWED_LAYERS) + "）");
        }
        if (z < 0 || z > 18) {
            throw new BusinessException("缩放层级超出范围: z=" + z + "（应在 0 ~ 18 之间）");
        }
        // 单级下瓦片数是 2^z，z=18 时上限 262144，用 int 安全
        int maxIndex = (1 << z) - 1;
        if (x < 0 || x > maxIndex || y < 0 || y > maxIndex) {
            throw new BusinessException("瓦片坐标超出范围: x=" + x + ", y=" + y
                    + "（z=" + z + " 时应在 0 ~ " + maxIndex + " 之间）");
        }

        String host = props.tileHost(hostIndex);
        String url = host + "/" + layerType + "/wmts"
                + "?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0"
                + "&LAYER=" + wmtsLayer
                + "&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles"
                + "&TILEMATRIX=" + z
                + "&TILEROW=" + y
                + "&TILECOL=" + x
                + "&tk=" + urlEncode(resolveServerKey());

        RawResponse raw = sendRaw(url, "瓦片请求");
        byte[] body = raw.body();
        // content-type 必须用上游实际返回值：实测影像/地形图层返回 image/jpg，
        // 矢量/注记图层返回 image/png。硬写 png 会让浏览器在 nosniff 下拒绝渲染 JPEG 瓦片。
        String contentType = raw.contentType() != null ? raw.contentType() : "image/png";
        return new TileResponse(body, contentType);
    }

    /**
     * 瓦片响应体（字节 + content-type）。
     *
     * @param bytes       瓦片字节
     * @param contentType 上游实际返回的 content-type
     */
    public record TileResponse(byte[] bytes, String contentType) {}

    // ==================================================================
    //  内部工具
    // ==================================================================

    /**
     * 发起 GET 并解析 JSON。
     * <p>把上游的各类失败翻译成 {@link BusinessException}（可读中文提示）。
     */
    private JsonNode getJson(String url, String action) {
        String body = sendText(url, action);
        if (body == null || body.isBlank()) {
            throw new BusinessException(502, action + "失败: 上游返回空响应");
        }
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            log.warn("[天地图] {} 响应非 JSON: {}", action, abbreviate(body));
            throw new BusinessException(502, action + "失败: 上游返回了非预期的数据格式");
        }
    }

    /**
     * 发起 GET 并返回文本（非 2xx 时抛 BusinessException）。
     *
     * <p><b>错误翻译规则</b>：
     * <ul>
     *   <li>HTTP 400：上游说参数/key 有问题 → 400，附上天地图的 msg（已脱敏，不含 key）</li>
     *   <li>HTTP 403/404：天地图的 403 多为「key 权限类型错误」或 WAF 拦截 → 502
     *       （属于配置/上游故障，不是调用方的错）</li>
     *   <li>HTTP 429：天地图按 tk 限流 → 429（调用方可退避重试）</li>
     *   <li>HTTP 5xx：上游故障 → 502</li>
     *   <li>超时：504</li>
     *   <li>网络异常：502</li>
     * </ul>
     */
    private String sendText(String url, String action) {
        byte[] raw = sendBytes(url, action);
        if (raw == null) return "";
        return new String(raw, StandardCharsets.UTF_8);
    }

    /**
     * 发起 GET 并返回字节（JSON 走这个，JSON 再自己解码）。
     */
    private byte[] sendBytes(String url, String action) {
        return sendRaw(url, action).body();
    }

    /**
     * 当前是否有可用的服务端密钥(数据库或环境变量任一)。
     */
    public boolean hasUsableServerKey() {
        String k = resolveServerKey();
        return k != null && !k.isBlank();
    }

    /**
     * 当前密钥的来源: {@code database} / {@code env} / {@code none}。
     * 只暴露来源, 不暴露内容。
     */
    public String serverKeySource() {
        // 用轻量判断, 不解密也不更新 lastUsedTime —— status 是高频接口
        if (apiKeyService != null && apiKeyService.hasUsable(API_KEY_TIANDITU_SERVER)) {
            return "database";
        }
        String env = props.getServerKey();
        if (env != null && !env.isBlank()) return "env";
        return "none";
    }

    /**
     * 解析当前生效的服务端密钥。
     *
     * <p><b>优先级</b>：数据库（管理员在「系统管理 → API 密钥」配置的）→ 环境变量
     * {@code TIANDITU_SERVER_KEY}。
     *
     * <p>数据库优先是刻意的：管理员在界面上改一下就立刻生效，不用重启服务；
     * 环境变量作为部署期兜底（容器里注入、或临时救火）。
     */
    private String resolveServerKey() {
        if (apiKeyService != null) {
            String fromDb = apiKeyService.resolveSecret(API_KEY_TIANDITU_SERVER, props.getServerKey());
            if (fromDb != null && !fromDb.isBlank()) return fromDb;
        }
        return props.getServerKey();
    }

    /**
     * 发起 GET 的公共实现：构造请求 → 发送 → 状态码翻译。
     */
    private RawResponse sendRaw(String url, String action) {
        if (!props.isEnabled()) {
            throw new BusinessException(503, "天地图服务已关闭（tianditu.enabled=false）");
        }
        String serverKey = resolveServerKey();
        if (serverKey == null || serverKey.isBlank()) {
            throw new BusinessException(503,
                    "天地图服务端 key 未配置：请在「系统管理 → API 密钥」中配置 "
                            + API_KEY_TIANDITU_SERVER + "，或设置环境变量 TIANDITU_SERVER_KEY");
        }

        // 第一轮用「已探测出的 UA」（没探测过就用服务端 UA）
        RawResponse raw = execute(url, action, uaFor(serverKey));
        int status = raw.status();

        // 天地图的 key 类型错误：403 + code 301012(浏览器端 key 配服务端 UA) / 301013(反之)
        // → 换 UA 重试一次，并把结论记下来，后续请求直接用对的 UA。
        if (status == 403 && isKeyTypeMismatch(raw)) {
            String correctUa = switch (detectKeyType(raw)) {
                case "browser" -> BROWSER_USER_AGENT;
                case "server" -> USER_AGENT;
                default -> null; // 认不出来, 不瞎试
            };
            if (correctUa != null && !correctUa.equals(uaFor(serverKey))) {
                log.info("[天地图] 检测到 key 类型与 UA 不匹配({}), 切换 UA 重试: {}",
                        detectKeyType(raw), correctUa.contains("Mozilla") ? "浏览器端" : "服务端");
                uaByKey.put(serverKey, correctUa);
                raw = execute(url, action, correctUa);
                status = raw.status();
            }
        }

        if (status >= 200 && status < 300) {
            return raw;
        }
        String upstreamMsg = extractErrorMessage(new String(raw.body(), StandardCharsets.UTF_8));
        if (status == 400) {
            // 上游明确说是参数/鉴权问题 → 400，把天地图的 msg 透给调用方
            throw new BusinessException(400, action + "失败: " + upstreamMsg);
        }
        if (status == 429) {
            // 天地图按 tk 限流（实测：连续请求会返回 429 "该tk已限流"）
            // → 429，让调用方知道要退避重试（前端瓦片加载会自然重试）
            throw new BusinessException(429, action + "被天地图限流，请稍后重试");
        }
        if (status == 403 || status == 404) {
            // key 权限类型错误 / WAF 拦截 → 配置或上游故障，502
            throw new BusinessException(502, action + "失败(" + status + "): " + upstreamMsg);
        }
        throw new BusinessException(502, action + "失败: 上游返回 HTTP " + status
                + (upstreamMsg.isEmpty() ? "" : " - " + upstreamMsg));
    }

    /**
     * 构造 GET 请求。
     *
     * <p><b>User-Agent 必须是「非浏览器」标识</b> —— 这是天地图的一个硬性约束，
     * 实测踩过坑：把 UA 写成 {@code Mozilla/5.0 (compatible; ...)} 会收到
     * <pre>
     * 403 {"code":301013,"msg":"权限类型错误",
     *      "resolve":"Key权限类型为:服务器端，请使用服务器端访问！"}
     * </pre>
     * 逻辑很反直觉但规则明确：<b>服务端 key 配浏览器 UA 被拒</b>，
     * 反过来「浏览器端 key 配服务端 UA」也会被拒（code 301012，
     * 实测浏览器 key 在服务端调用返回的正是这个）。
     * 所以 UA 本身就是 key 类型的一部分，两边必须匹配。
     */
    HttpRequest buildRequest(String url) {
        return buildRequest(url, USER_AGENT);
    }

    /**
     * 构造 GET 请求（可指定 UA）。
     *
     * <p>UA 的形态必须与 key 类型匹配, 详见 {@link #USER_AGENT} 与 {@link #BROWSER_USER_AGENT}。
     */
    HttpRequest buildRequest(String url, String userAgent) {
        try {
            return HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(1000, props.getReadTimeoutMs())))
                    .header("Accept", "*/*")
                    // UA 必须与 key 类型匹配（服务端 key 配浏览器 UA 会 403 / 301013）
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            // URI.create 失败 = URL 拼装有问题
            log.error("[天地图] URL 非法: {}", e.getMessage());
            throw new BusinessException(500, "请求失败: 内部请求地址构造错误");
        }
    }

    // --------------------------------------------------------------
    //  key 类型自适应（服务端 key / 浏览器端 key 与 UA 互斥）
    // --------------------------------------------------------------

    /** 这把 key 当前应当使用的 UA：探测过就用探测结果，没探测过默认服务端 UA */
    private String uaFor(String key) {
        return uaByKey.getOrDefault(key, USER_AGENT);
    }

    /**
     * 执行一次上游请求（不含重试逻辑）。
     */
    private RawResponse execute(String url, String action, String userAgent) {
        try {
            if (executor != null) {
                // 测试路径：执行器被替换
                return executor.execute(url);
            }
            HttpRequest request = buildRequest(url, userAgent);
            HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            String ct = resp.headers().firstValue("content-type").orElse("application/json");
            return new RawResponse(resp.statusCode(), resp.body(), ct);
        } catch (java.net.http.HttpTimeoutException e) {
            log.warn("[天地图] {} 超时: {}", action, e.getMessage());
            throw new BusinessException(504, action + "超时，请稍后重试");
        } catch (IOException e) {
            log.warn("[天地图] {} 网络异常: {}", action, e.getMessage());
            throw new BusinessException(502, action + "失败: 无法连接天地图服务");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(502, action + "失败: 请求被中断");
        }
    }

    /**
     * 判断这个 403 是不是「key 类型与 UA 不匹配」导致的。
     *
     * <p>天地图的权限类型错误有专属 code：
     * <ul>
     *   <li>301012 —— key 是浏览器端, 请用浏览器访问（我们配了服务端 UA）；</li>
     *   <li>301013 —— key 是服务端, 请用服务端访问（我们配了浏览器 UA）。</li>
     * </ul>
     * 这两个 code 非常明确, 可以据此自动纠正; 其它 403（如 WAF 拦截、key 失效）不能乱猜。
     */
    private boolean isKeyTypeMismatch(RawResponse raw) {
        if (raw.status() != 403) return false;
        String body = new String(raw.body(), StandardCharsets.UTF_8);
        return body.contains("301012") || body.contains("301013")
                || body.contains("权限类型错误") || body.contains("Key权限类型为");
    }

    /**
     * 从上游错误响应里读出 key 的真实类型。
     *
     * @return {@code "browser"} / {@code "server"} / {@code null}(认不出来)
     */
    private String detectKeyType(RawResponse raw) {
        String body = new String(raw.body(), StandardCharsets.UTF_8);
        // 上游 resolve 字段形如: "Key权限类型为:浏览器端，请使用浏览器访问！"
        if (body.contains("浏览器端")) return "browser";
        if (body.contains("服务器端")) return "server";
        // 退而求其次看 code：301012=浏览器端 key, 301013=服务端 key
        if (body.contains("301012")) return "browser";
        if (body.contains("301013")) return "server";
        return null;
    }

    /**
     * 从天地图错误响应体中提取可读信息。
     * <p>天地图的错误格式不统一：有的是 {@code {"msg":"...","status":400}}，
     * 有的是 {@code {"code":308011,"message":"..."}}，还有的直接返回 WAF 的 HTML 拦截页。
     */
    private String extractErrorMessage(String body) {
        if (body == null || body.isBlank()) return "";
        String trimmed = body.trim();
        if (trimmed.startsWith("<")) {
            // WAF 拦截页（HTML），不把整页塞给用户
            return "请求被天地图安全网关拦截";
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            String msg = node.path("msg").asText(null);
            if (msg == null || msg.isBlank()) {
                msg = node.path("message").asText(null);
            }
            if (msg == null || msg.isBlank()) {
                msg = node.path("cndesc").asText(null);
            }
            if (msg == null || msg.isBlank()) {
                msg = node.path("resolve").asText(null);
            }
            if (msg != null && !msg.isBlank()) {
                // 天地图的报错里会回显我们传进去的参数 JSON（含用户输入），截断防止刷屏
                return abbreviate(msg);
            }
        } catch (Exception ignored) {
            // 不是 JSON，落到下面按原文截断
        }
        return abbreviate(trimmed);
    }

    /**
     * 截断长文本（防日志/响应刷屏）。
     */
    private static String abbreviate(String s) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 200 ? oneLine : oneLine.substring(0, 200) + "…";
    }

    /**
     * URL 编码（UTF-8）。
     * <p>天地图的 postStr/ds 是 JSON，必须整体编码成一个参数值。
     */
    private static String urlEncode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8);
    }

    /**
     * 拼一个简单 JSON 对象（key/value 交替）。
     */
    private static String buildJson(String... kv) {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < kv.length; i += 2) {
            if (i > 0) sb.append(',');
            sb.append(jsonString(kv[i])).append(':').append(jsonString(kv[i + 1]));
        }
        return sb.append('}').toString();
    }

    /**
     * JSON 字符串字面量（含转义）。
     */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /**
     * 必填文本校验。
     */
    private static String requireText(String s, String message) {
        if (s == null || s.isBlank()) {
            throw new BusinessException(message);
        }
        return s.trim();
    }

    // ---------------- 缓存 ----------------

    /**
     * 读缓存（过期即视为未命中并顺手清掉）。
     */
    private JsonNode getCached(String key) {
        CacheEntry e = cache.get(key);
        if (e == null) return null;
        if (e.expired(System.currentTimeMillis(), props.getCacheTtlMs())) {
            cache.remove(key);
            return null;
        }
        return (JsonNode) e.value();
    }

    /**
     * 写缓存。
     * <p>超过上限时整表清空（简单的 LRU 近似）—— 避免维护链表的开销，
     * 在几百条量级下完全可接受；真正的 LRU 建议上 Caffeine。
     */
    private void putCached(String key, JsonNode value) {
        int max = Math.max(1, props.getMaxCacheEntries());
        if (cache.size() >= max) {
            log.debug("[天地图] 缓存写满({} 条), 清空重来", max);
            cache.clear();
        }
        cache.put(key, new CacheEntry(value, System.currentTimeMillis()));
    }

    /**
     * 当前缓存条目数（供监控/测试）。
     */
    public int cacheSize() {
        return cache.size();
    }
}
