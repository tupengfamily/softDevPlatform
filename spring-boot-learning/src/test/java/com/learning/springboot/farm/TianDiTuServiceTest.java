package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.learning.springboot.config.TianDiTuProperties;
import com.learning.springboot.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TianDiTuService} 单元测试。
 *
 * <p>通过注入 {@link TianDiTuService.HttpExecutor} 完全替换网络层，
 * 验证：
 * <ul>
 *   <li>URL 拼装正确（ds/postStr 参数通道、tk 鉴权、postStr 已 URL 编码）</li>
 *   <li>上游错误被翻译成可读的 {@link BusinessException}（而不是把 JSON 透传出去）</li>
 *   <li>瓦片白名单拦截非法 layerType（防 SSRF）</li>
 *   <li>缓存命中不再打上游</li>
 * </ul>
 *
 * <p><b>不依赖真实网络</b>：所有上游响应都是构造出来的，
 * 保证测试离线可跑、结果稳定。
 */
class TianDiTuServiceTest {

    /** 记录请求并按脚本返回响应的假执行器 */
    static class FakeExecutor implements TianDiTuService.HttpExecutor {
        final List<String> urls = new ArrayList<>();
        /** 依次返回的响应；用完后重复最后一个 */
        final List<TianDiTuService.RawResponse> responses = new ArrayList<>();
        /** 模拟抛出的异常（网络故障 / 超时） */
        Exception failWith;

        @Override
        public TianDiTuService.RawResponse execute(String url) throws IOException, InterruptedException {
            urls.add(url);
            if (failWith != null) {
                if (failWith instanceof IOException io) throw io;
                if (failWith instanceof InterruptedException ie) throw ie;
                if (failWith instanceof RuntimeException re) throw re;
            }
            if (responses.isEmpty()) throw new IllegalStateException("FakeExecutor 没有预置响应");
            int idx = Math.min(urls.size() - 1, responses.size() - 1);
            return responses.get(idx);
        }

        String lastUrl() {
            return urls.get(urls.size() - 1);
        }
    }

    private TianDiTuProperties props;
    private FakeExecutor executor;
    private TianDiTuService service;

    @BeforeEach
    void setUp() {
        props = new TianDiTuProperties();
        props.setServerKey("test-server-key");
        props.setBaseUrl("https://api.tianditu.gov.cn");
        executor = new FakeExecutor();
        service = new TianDiTuService(props, executor);
    }

    private static TianDiTuService.RawResponse ok(String body) {
        return new TianDiTuService.RawResponse(200, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static TianDiTuService.RawResponse ok(int status, String body, String contentType) {
        return new TianDiTuService.RawResponse(status, body.getBytes(StandardCharsets.UTF_8), contentType);
    }

    private static TianDiTuService.RawResponse err(int status, String body) {
        return new TianDiTuService.RawResponse(status, body.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    // ==================================================================
    //  URL 拼装
    // ==================================================================

    @Test
    @DisplayName("搜索：URL 含 tk 鉴权参数 + postStr 已 URL 编码")
    void searchUrlShape() {
        executor.responses.add(ok("{\"resultType\":3,\"area\":{\"name\":\"鄱阳县\"}}"));
        service.search("鄱阳县", 12, "73.66,3.86,135.05,53.55", 0, 10);

        String url = executor.lastUrl();
        assertTrue(url.startsWith("https://api.tianditu.gov.cn/v2/search?"), url);
        assertTrue(url.contains("tk=test-server-key"), "必须用 tk 而非 key: " + url);
        assertTrue(url.contains("type=query"), url);
        assertTrue(url.contains("postStr=%7B"), "postStr 应被编码: " + url);
        assertTrue(!url.contains("postStr={"), "postStr 不应是裸 JSON: " + url);
    }

    @Test
    @DisplayName("地址编码：走 ds= 参数（不是 postStr=）")
    void geocodeUsesDsParameter() {
        executor.responses.add(ok("{\"status\":\"0\",\"location\":{\"lon\":\"116.69877\",\"lat\":\"29.00868\"}}"));
        JsonNode r = service.geocode("鄱阳县", 12);

        String url = executor.lastUrl();
        assertTrue(url.contains("ds=%7B"), "地址编码必须走 ds=: " + url);
        assertTrue(!url.contains("postStr="), "地址编码不该用 postStr=: " + url);
        assertTrue(url.contains("tk=test-server-key"), url);
        assertEquals("116.69877", r.path("location").path("lon").asText());
    }

    @Test
    @DisplayName("逆地理编码：走 postStr= 参数（不是 ds=）")
    void reverseGeocodeUsesPostStrParameter() {
        executor.responses.add(ok("{\"status\":\"0\",\"result\":{\"formatted_address\":\"北京市东城区\"}}"));
        JsonNode r = service.reverseGeocode(116.4, 39.9);

        String url = executor.lastUrl();
        assertTrue(url.contains("postStr=%7B"), "逆地理编码必须走 postStr=: " + url);
        assertTrue(!url.contains("ds="), "逆地理编码不该用 ds=: " + url);
        assertEquals("北京市东城区", r.path("result").path("formatted_address").asText());
    }

    @Test
    @DisplayName("搜索：返回原始 JSON 结构，保留 resultType 供前端分支")
    void searchPreservesRawStructure() {
        executor.responses.add(ok(
                "{\"resultType\":3,\"count\":1,\"area\":{\"name\":\"鄱阳县\",\"adminCode\":156361128,"
                        + "\"lonlat\":\"116.698516,29.008606\"}}"));
        JsonNode r = service.search("鄱阳县", 12, null, 0, 10);

        assertEquals(3, r.path("resultType").asInt());
        assertEquals("鄱阳县", r.path("area").path("name").asText());
        assertEquals(156361128, r.path("area").path("adminCode").asInt());
        assertNotNull(r.path("area"));
    }

    @Test
    @DisplayName("搜索：未传 mapBound 时使用中国全境默认值")
    void searchUsesDefaultMapBound() {
        executor.responses.add(ok("{\"resultType\":1,\"pois\":[]}"));
        service.search("超市", 18, null, 0, 10);
        assertTrue(executor.lastUrl().contains("73.66"), "应带默认 mapBound");
    }

    @Test
    @DisplayName("搜索：关键词含特殊字符被正确转义（不会破坏 JSON）")
    void keywordWithSpecialCharsIsEscaped() {
        executor.responses.add(ok("{\"resultType\":1,\"pois\":[]}"));
        service.search("a\"b\\c", 12, null, 0, 10);
        // 解码后应是合法的 JSON 字符串，不能出现裸引号
        String encoded = executor.lastUrl().split("postStr=")[1].split("&")[0];
        String decoded = java.net.URLDecoder.decode(encoded, StandardCharsets.UTF_8);
        assertTrue(decoded.contains("a\\\"b\\\\c"), "应转义引号和反斜杠: " + decoded);
    }

    // ==================================================================
    //  错误处理
    // ==================================================================

    @Test
    @DisplayName("上游 400：翻译成可读错误，而不是透传 JSON")
    void upstream400BecomesReadableError() {
        executor.responses.add(err(400, "{\"msg\":\"缺少参数：mapBound\",\"status\":400}"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.search("鄱阳县", 12, null, 0, 10));
        assertEquals(400, e.getCode());
        assertTrue(e.getMessage().contains("缺少参数：mapBound"), e.getMessage());
    }

    @Test
    @DisplayName("上游 403（key 权限类型错误）：转 502 而非 400")
    void upstream403Becomes502() {
        executor.responses.add(err(403, "{\"code\":301012,\"msg\":\"权限类型错误\","
                + "\"resolve\":\"Key权限类型为:浏览器端，请使用浏览器访问！\"}"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.geocode("北京", 12));
        assertEquals(502, e.getCode());
        assertTrue(e.getMessage().contains("权限类型错误"), e.getMessage());
    }

    @Test
    @DisplayName("上游 5xx：转 502")
    void upstream5xxBecomes502() {
        executor.responses.add(err(500, "boom"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.reverseGeocode(116.4, 39.9));
        assertEquals(502, e.getCode());
    }

    @Test
    @DisplayName("上游 429（tk 限流）：转 429 提示退避重试")
    void upstream429Becomes429() {
        executor.responses.add(err(429, "{\"msg\":\"该tk已限流\",\"code\":302010,"
                + "\"resolve\":\"请求已限流，请稍后重试!\"}"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.search("北京", 12, null, 0, 10));
        assertEquals(429, e.getCode());
        assertTrue(e.getMessage().contains("限流"), e.getMessage());
    }

    @Test
    @DisplayName("WAF HTML 拦截页：不把整页塞进错误信息")
    void wafHtmlIsNotLeaked() {
        executor.responses.add(err(403, "<!DOCTYPE html><html><title>访问被拦截！</title></html>"));
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.search("北京", 12, null, 0, 10));
        assertTrue(e.getMessage().contains("安全网关"), e.getMessage());
        assertTrue(!e.getMessage().contains("<html>"), "不应泄露 HTML: " + e.getMessage());
    }

    @Test
    @DisplayName("网络异常：转 502，不抛裸 IOException")
    void networkErrorBecomes502() {
        executor.failWith = new java.io.IOException("connection reset");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.search("北京", 12, null, 0, 10));
        assertEquals(502, e.getCode());
        assertTrue(e.getMessage().contains("无法连接"), e.getMessage());
    }

    @Test
    @DisplayName("超时：转 504")
    void timeoutBecomes504() {
        executor.failWith = new java.net.http.HttpTimeoutException("timeout");
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.search("北京", 12, null, 0, 10));
        assertEquals(504, e.getCode());
    }

    @Test
    @DisplayName("空关键词：本地直接拒绝，不打上游")
    void blankKeywordRejectedLocally() {
        assertThrows(BusinessException.class, () -> service.search("  ", 12, null, 0, 10));
        assertThrows(BusinessException.class, () -> service.geocode("", 12));
        assertEquals(0, executor.urls.size(), "参数非法时不应打上游");
    }

    @Test
    @DisplayName("坐标越界：本地直接拒绝")
    void outOfRangeCoordsRejected() {
        assertThrows(BusinessException.class, () -> service.reverseGeocode(200, 39.9));
        assertThrows(BusinessException.class, () -> service.reverseGeocode(116.4, 95));
        assertEquals(0, executor.urls.size());
    }

    @Test
    @DisplayName("未配置 key：直接 503，不打上游")
    void missingKeyShortCircuits() {
        props.setServerKey("");
        TianDiTuService noKey = new TianDiTuService(props, executor);
        BusinessException e = assertThrows(BusinessException.class,
                () -> noKey.search("北京", 12, null, 0, 10));
        assertEquals(503, e.getCode());
        assertEquals(0, executor.urls.size());
    }

    @Test
    @DisplayName("enabled=false：直接 503")
    void disabledShortCircuits() {
        props.setEnabled(false);
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.search("北京", 12, null, 0, 10));
        assertEquals(503, e.getCode());
    }

    // ==================================================================
    //  瓦片白名单（SSRF 防护）
    // ==================================================================

    @Test
    @DisplayName("瓦片：6 个白名单图层全部放行")
    void allWhitelistedLayersAllowed() {
        for (String layer : TianDiTuService.ALLOWED_LAYERS) {
            executor.responses.add(ok(200, "PNGDATA", "image/png"));
            TianDiTuService.TileResponse t = service.fetchTile(layer, 3, 5, 2, 0);
            assertEquals("PNGDATA", new String(t.bytes(), StandardCharsets.UTF_8));
            String url = executor.lastUrl();
            assertTrue(url.contains("/" + layer + "/wmts"), url);
            assertTrue(url.contains("tk=test-server-key"), url);
        }
    }

    @Test
    @DisplayName("瓦片：LAYER 参数是去掉 _w 的版本（img_w → LAYER=img）")
    void tileLayerParamIsStripped() {
        executor.responses.add(ok(200, "PNG", "image/png"));
        service.fetchTile("img_w", 3, 5, 2, 0);
        String url = executor.lastUrl();
        assertTrue(url.contains("LAYER=img&"), "LAYER 必须是 img 而不是 img_w: " + url);
        assertTrue(url.contains("/img_w/wmts"), "URL 路径段是 img_w: " + url);
    }

    @Test
    @DisplayName("瓦片：非法 layerType 被拒绝（防 SSRF），且不打上游")
    void illegalLayerTypeRejected() {
        assertThrows(BusinessException.class,
                () -> service.fetchTile("../../../etc/passwd", 3, 5, 2, 0));
        assertThrows(BusinessException.class,
                () -> service.fetchTile("evil", 3, 5, 2, 0));
        assertThrows(BusinessException.class,
                () -> service.fetchTile("", 3, 5, 2, 0));
        assertEquals(0, executor.urls.size(), "非法图层不应打上游");
    }

    @Test
    @DisplayName("瓦片：层级与行列号越界被拒绝")
    void tileCoordinateBoundsEnforced() {
        assertThrows(BusinessException.class, () -> service.fetchTile("img_w", -1, 0, 0, 0));
        assertThrows(BusinessException.class, () -> service.fetchTile("img_w", 19, 0, 0, 0));
        assertThrows(BusinessException.class, () -> service.fetchTile("img_w", 3, 8, 0, 0));
        assertThrows(BusinessException.class, () -> service.fetchTile("img_w", 3, 0, 8, 0));
        assertThrows(BusinessException.class, () -> service.fetchTile("img_w", 3, -1, 0, 0));
        assertEquals(0, executor.urls.size());
    }

    @Test
    @DisplayName("瓦片：content-type 用上游实际值（image/jpg 不能写死 png）")
    void tileContentTypePassedThrough() {
        executor.responses.add(ok(200, "JPEGDATA", "image/jpg"));
        TianDiTuService.TileResponse t = service.fetchTile("img_w", 3, 5, 2, 0);
        assertEquals("image/jpg", t.contentType());
    }

    @Test
    @DisplayName("瓦片：8 台机器轮询分流（hostIndex 取模 0-7）")
    void tileHostSharding() {
        for (int i = 0; i < 8; i++) {
            executor.responses.add(ok(200, "PNG", "image/png"));
            service.fetchTile("img_w", 3, i, 0, Math.floorMod(i, 8));
        }
        for (int i = 0; i < 8; i++) {
            assertTrue(executor.urls.get(i).startsWith("https://t" + i + ".tianditu.gov.cn/"),
                    executor.urls.get(i));
        }
    }

    // ==================================================================
    //  User-Agent 约束（实测踩坑点，务必锁死）
    // ==================================================================

    @Test
    @DisplayName("请求 UA 不含 Mozilla（服务端 key 配浏览器 UA 会被天地图 403 拒绝）")
    void userAgentIsNotBrowserLike() {
        HttpRequest req = service.buildRequest("https://api.tianditu.gov.cn/v2/search?x=1");
        String ua = req.headers().firstValue("User-Agent").orElse("");
        assertTrue(!ua.contains("Mozilla"),
                "UA 绝不能含 Mozilla，否则天地图判定为浏览器端并拒绝服务端 key: " + ua);
        assertTrue(ua.contains("TianDiTu"), "UA 应表明是本代理: " + ua);
    }

    @Test
    @DisplayName("请求带 Accept 与超时设置")
    void requestHasAcceptAndTimeout() {
        HttpRequest req = service.buildRequest("https://api.tianditu.gov.cn/v2/search?x=1");
        assertTrue(req.headers().firstValue("Accept").isPresent());
        assertTrue(req.timeout().isPresent(), "必须设置超时，否则上游挂起会拖死线程池");
    }

    // ==================================================================
    //  缓存
    // ==================================================================

    @Test
    @DisplayName("缓存：相同搜索第二次命中，不再打上游")
    void searchIsCached() {
        executor.responses.add(ok("{\"resultType\":3,\"area\":{\"name\":\"鄱阳县\"}}"));
        service.search("鄱阳县", 12, null, 0, 10);
        service.search("鄱阳县", 12, null, 0, 10);
        assertEquals(1, executor.urls.size(), "第二次应命中缓存");
        assertEquals(1, service.cacheSize());
    }

    @Test
    @DisplayName("缓存：不同关键词不共享缓存")
    void differentKeywordsNotShared() {
        executor.responses.add(ok("{\"resultType\":1,\"pois\":[]}"));
        service.search("超市", 18, null, 0, 10);
        service.search("医院", 18, null, 0, 10);
        assertEquals(2, executor.urls.size());
    }

    @Test
    @DisplayName("缓存：逆地理编码按坐标取整后归一化（微小抖动仍命中）")
    void reverseGeocodeCacheNormalizesCoords() {
        executor.responses.add(ok("{\"status\":\"0\",\"result\":{}}"));
        service.reverseGeocode(116.40001, 39.90001);
        service.reverseGeocode(116.40002, 39.90002);
        assertEquals(1, executor.urls.size(), "坐标抖动应命中同一缓存条目");
    }

    @Test
    @DisplayName("缓存：TTL 过期后重新打上游")
    void cacheExpiresAfterTtl() throws InterruptedException {
        props.setCacheTtlMs(50);
        executor.responses.add(ok("{\"resultType\":3,\"area\":{}}"));
        service.search("鄱阳县", 12, null, 0, 10);
        Thread.sleep(80);
        service.search("鄱阳县", 12, null, 0, 10);
        assertEquals(2, executor.urls.size(), "TTL 过期后应重新请求");
    }

    @Test
    @DisplayName("缓存：超过上限时清表，不无限增长")
    void cacheIsBounded() {
        props.setMaxCacheEntries(3);
        executor.responses.add(ok("{\"resultType\":1,\"pois\":[]}"));
        for (int i = 0; i < 10; i++) {
            service.search("kw" + i, 12, null, 0, 10);
        }
        assertTrue(service.cacheSize() <= 3, "缓存应受限，实际 " + service.cacheSize());
    }
}
