package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.learning.springboot.common.Result;
import com.learning.springboot.config.TianDiTuProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 天地图后端代理 API。
 *
 * <p><b>为什么挂在 {@code /api/map/tianditu} 而不是 {@code /api/games/farm}</b>：
 * 底图/瓦片/地名搜索是<b>通用地图能力</b>，不止农场小游戏在用
 * （学习中心的地理课程、其他模块的选址功能都可能复用）。
 * 挂在农场前缀下会让这个通用服务被 farm 模块的语义绑架。
 *
 * <p><b>关于是否要求登录</b>：<b>不要求</b>。理由如下：
 * <ul>
 *   <li>瓦片是浏览器 {@code <img>} 直接加载的，浏览器不会带 Authorization 头
 *       —— 加了鉴权前端地图直接空白（401 图片破图）</li>
 *   <li>底图本身是公开地理数据，登录与否不构成敏感信息边界</li>
 *   <li>真正的边界在<b>服务端 key</b>：key 只存在于后端内存，
 *       前端永远拿不到；代理层也只下发瓦片字节，不下发 key</li>
 * </ul>
 * 因此这些端点已加入 {@code JwtAuthFilter} 的公开白名单
 * （见 {@code security/JwtAuthFilter} 的 {@code shouldNotFilter}）。
 * 注意：登录用户请求这些端点时，{@code @CurrentUser} 依然能拿到 userId
 * （JWT 过滤器虽然放行，但仍会解析 token 并写入 attribute），
 * 所以需要用户身份的场景可以自行注入 {@code @CurrentUser}。
 */
@RestController
@RequestMapping("/api/map/tianditu")
public class TianDiTuController {

    @Autowired
    private TianDiTuService service;

    @Autowired
    private TianDiTuProperties props;

    /**
     * 地名搜索。
     *
     * <p>返回天地图<b>原始 JSON 结构</b>，前端按 {@code resultType} 分支：
     * <ul>
     *   <li>1 → POI 列表（{@code pois[]}）</li>
     *   <li>2 → 按城市统计（{@code statistics.priorityCitys}）</li>
     *   <li>3 → 行政区划（{@code area}：{@code name}/{@code adminCode}/{@code lonlat}/{@code bound}）</li>
     * </ul>
     *
     * @param keyword  搜索关键词
     * @param level    搜索层级（12=区县，18=POI 兴趣点；默认 12）
     * @param mapBound 搜索范围 {@code lon_min,lat_min,lon_max,lat_max}；不传则用中国全境
     * @param start    分页起始（默认 0）
     * @param count    返回条数（默认 10，上限 50）
     */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam String keyword,
                                    @RequestParam(defaultValue = "12") int level,
                                    @RequestParam(required = false) String mapBound,
                                    @RequestParam(defaultValue = "0") int start,
                                    @RequestParam(defaultValue = "10") int count) {
        JsonNode result = service.search(keyword, level, mapBound, start, count);
        return Result.ok(result);
    }

    /**
     * 地址编码（地名 → 坐标）。
     *
     * <p>返回：{@code {"msg":"ok","location":{"lon":..,"lat":..,"level":..,"score":..},
     * "status":"0"}}
     *
     * @param keyword 地址/地名关键词
     * @param level   层级（默认 12 = 区县及以上行政区划；传 0 表示交给天地图自动判定）
     */
    @GetMapping("/geocode")
    public ResponseEntity<?> geocode(@RequestParam String keyword,
                                     @RequestParam(defaultValue = "12") int level) {
        JsonNode result = service.geocode(keyword, level);
        return Result.ok(result);
    }

    /**
     * 逆地理编码（坐标 → 地址）。
     *
     * <p>返回：{@code {"result":{"formatted_address":..,"location":{..},
     * "addressComponent":{"province","city","county","county_code","town",..}},"status":"0"}}
     *
     * @param lon 经度（度）
     * @param lat 纬度（度）
     */
    @GetMapping("/reverse")
    public ResponseEntity<?> reverse(@RequestParam double lon, @RequestParam double lat) {
        JsonNode result = service.reverseGeocode(lon, lat);
        return Result.ok(result);
    }

    /**
     * 瓦片代理（透传图片字节）。
     *
     * <p>前端把原来的
     * {@code https://t{n}.tianditu.gov.cn/img_w/wmts?...&tk=KEY}
     * 换成
     * {@code /api/map/tianditu/tile/img_w/{z}/{x}/{y}}，
     * 密钥不再出现在前端代码/网络请求里。
     *
     * <p><b>缓存头</b>：{@code public, max-age=86400}（1 天）。
     * 瓦片是不可变资源（同一 z/x/y 永远同一张图），可以放心长缓存；
     * 浏览器缓存命中后连后端都不用打。
     *
     * <p><b>content-type</b>：用上游实际返回值透传（影像/地形是 image/jpg，
     * 矢量/注记是 image/png）。因为 {@code SecurityHeadersFilter} 会加
     * {@code X-Content-Type-Options: nosniff}，写死 png 会导致 JPEG 瓦片被浏览器拒绝渲染。
     *
     * @param layerType 图层类型（白名单：vec_w/img_w/ter_w/cva_w/cia_w/cta_w）
     * @param z         缩放层级（0-18）
     * @param x         瓦片列号
     * @param y         瓦片行号
     */
    @GetMapping(value = "/tile/{layerType}/{z}/{x}/{y}",
            produces = {MediaType.IMAGE_PNG_VALUE, MediaType.IMAGE_JPEG_VALUE})
    public ResponseEntity<byte[]> tile(@PathVariable String layerType,
                                        @PathVariable int z,
                                        @PathVariable int x,
                                        @PathVariable int y) {
        // 机器编号用 (x+y) 散列：同一行相邻瓦片分到不同机器，8 台均衡负载
        int hostIndex = Math.floorMod(x + y, 8);
        TianDiTuService.TileResponse tile = service.fetchTile(layerType, z, x, y, hostIndex);

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(tile.contentType()))
                // Cache-Control 只设一次：瓦片是不可变资源（同一 z/x/y 永远同一张图），
                // 可以放心长缓存；浏览器命中后连后端都不用打。
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic())
                .body(tile.bytes());
    }

    /**
     * 代理层自检接口：返回当前配置状态（<b>不含 key 内容</b>）。
     *
     * <p>用于前端/运维确认「代理是否可用、key 是否已注入、允许哪些图层」。
     * 只暴露布尔状态和枚举，绝不返回 key 本身。
     */
    @GetMapping("/status")
    public ResponseEntity<?> status() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", props.isEnabled());
        // 用「实际能取到的密钥」判断, 而不是 props.hasServerKey()——
        // 管理员在「API 密钥」页面配好 key 后, 这里必须立刻显示已配置,
        // 否则会误以为没配上(环境变量仍然是兜底)。
        out.put("serverKeyConfigured", service.hasUsableServerKey());
        out.put("serverKeySource", service.serverKeySource());
        out.put("allowedLayers", TianDiTuService.ALLOWED_LAYERS);
        out.put("baseUrl", props.getBaseUrl());
        out.put("cacheSize", service.cacheSize());
        return Result.ok(out);
    }
}
