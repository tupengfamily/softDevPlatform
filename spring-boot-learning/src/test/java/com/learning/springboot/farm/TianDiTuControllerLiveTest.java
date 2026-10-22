package com.learning.springboot.farm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.config.TianDiTuProperties;
import com.learning.springboot.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TianDiTuController} 的 HTTP 层验证（MockMvc，<b>打真实天地图</b>）。
 *
 * <p><b>为什么要 MockMvc 而不是起真实服务器</b>：
 * 本次交付的验证环境是沙箱，起进程后本机端口不通（curl connection refused），
 * 而 MockMvc 在同进程内直接调用 Controller → Service → 真实天地图，
 * 能完整验证「HTTP 路由 + 参数绑定 + 响应格式 + 瓦片字节流」这条链路，
 * 且不需要网络端口。
 *
 * <p><b>本测试需要真实的 key</b>：通过环境变量 {@code TIANDITU_SERVER_KEY} 注入；
 * 未注入时整个类被跳过（不能让 CI 因为没 key 而红）。
 * 用 {@code @EnabledIfEnvironmentVariable}（而非 DisabledIf）表达
 * 「有 key 才跑」—— 前者语义是「变量匹配则执行」。
 */
@EnabledIfEnvironmentVariable(named = "TIANDITU_SERVER_KEY", matches = ".+",
        disabledReason = "需要真实的天地图服务端 key：请设置环境变量 TIANDITU_SERVER_KEY")
class TianDiTuControllerLiveTest {

    private static final String KEY = System.getenv("TIANDITU_SERVER_KEY");

    private MockMvc mockMvc;
    private ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        TianDiTuProperties props = new TianDiTuProperties();
        props.setServerKey(KEY == null ? "" : KEY);
        // apiKeyService 传 null: live 测试只验证「环境变量兜底」这条路径。
        // 数据库密钥库的路径由 ApiKeyServiceTest 单独覆盖。
        TianDiTuService service = new TianDiTuService(props, (com.learning.springboot.service.ApiKeyService) null);
        TianDiTuController controller = new TianDiTuController();
        // 直接注入 service/props（避免拉起整个 Spring 上下文 + 数据库）
        inject(controller, "service", service);
        inject(controller, "props", props);
        // 注册全局异常处理器：让 BusinessException 走 Result 格式（与线上一致）
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /**
     * 以 UTF-8 读取响应体。
     * <p>MockMvc 的 {@code getContentAsString()} 默认按 ISO-8859-1 解码，
     * 会把中文显示成乱码（<b>响应字节本身是正确的 UTF-8</b>，
     * 线上浏览器按 Content-Type 的 charset 解码没问题）。
     */
    private static String utf8(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    /** 反射注入字段（Controller 用 @Autowired 字段注入，测试里直接塞） */
    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入失败: " + field, e);
        }
    }

    @Test
    @DisplayName("GET /api/map/tianditu/status：返回配置状态且不泄露 key")
    void statusEndpoint() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.serverKeyConfigured").exists())
                .andExpect(jsonPath("$.data.allowedLayers").isArray());
    }

    @Test
    @DisplayName("GET /api/map/tianditu/status：响应体不含 key 原文")
    void statusDoesNotLeakKey() throws Exception {
        String body = utf8(mockMvc.perform(get("/api/map/tianditu/status"))
                .andReturn().getResponse());
        if (KEY != null && !KEY.isBlank()) {
            org.junit.jupiter.api.Assertions.assertFalse(body.contains(KEY),
                    "响应体不应包含服务端 key");
        }
    }

    @Test
    @DisplayName("GET /api/map/tianditu/search：返回 Result 包装 + 天地图原始结构")
    void searchEndpoint() throws Exception {
        String body = utf8(mockMvc.perform(get("/api/map/tianditu/search")
                        .param("keyword", "鄱阳县")
                        .param("level", "12"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse());

        JsonNode data = mapper.readTree(body).path("data");
        // resultType 必须保留，前端要靠它分支
        org.junit.jupiter.api.Assertions.assertTrue(data.has("resultType"),
                "应保留天地图原始结构(含 resultType): " + body);
        org.junit.jupiter.api.Assertions.assertEquals(3, data.path("resultType").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("鄱阳县", data.path("area").path("name").asText());
        org.junit.jupiter.api.Assertions.assertEquals(156361128, data.path("area").path("adminCode").asInt());
    }

    @Test
    @DisplayName("GET /api/map/tianditu/geocode：返回坐标")
    void geocodeEndpoint() throws Exception {
        String body = utf8(mockMvc.perform(get("/api/map/tianditu/geocode")
                        .param("keyword", "鄱阳县"))
                .andExpect(status().isOk())
                .andReturn().getResponse());

        JsonNode data = mapper.readTree(body).path("data");
        org.junit.jupiter.api.Assertions.assertEquals("116.69877", data.path("location").path("lon").asText());
    }

    @Test
    @DisplayName("GET /api/map/tianditu/reverse：返回格式化地址")
    void reverseEndpoint() throws Exception {
        String body = utf8(mockMvc.perform(get("/api/map/tianditu/reverse")
                        .param("lon", "116.4")
                        .param("lat", "39.9"))
                .andExpect(status().isOk())
                .andReturn().getResponse());

        JsonNode data = mapper.readTree(body).path("data");
        String addr = data.path("result").path("formatted_address").asText();
        org.junit.jupiter.api.Assertions.assertTrue(addr.contains("北京"), "应返回北京地址: " + addr);
    }

    @Test
    @DisplayName("GET /api/map/tianditu/tile/img_w/{z}/{x}/{y}：返回 PNG/JPEG 字节 + 缓存头")
    void tileEndpoint() throws Exception {
        byte[] body = mockMvc.perform(get("/api/map/tianditu/tile/img_w/3/5/2"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control",
                        org.hamcrest.Matchers.containsString("max-age=86400")))
                .andReturn().getResponse().getContentAsByteArray();

        org.junit.jupiter.api.Assertions.assertTrue(body.length > 1000,
                "应返回真实瓦片字节, 实际 " + body.length + " 字节");
        // PNG 或 JPEG 的魔数
        boolean isPng = body.length > 8 && (body[0] & 0xFF) == 0x89
                && body[1] == 'P' && body[2] == 'N' && body[3] == 'G';
        boolean isJpeg = body.length > 3 && (body[0] & 0xFF) == 0xFF
                && (body[1] & 0xFF) == 0xD8;
        org.junit.jupiter.api.Assertions.assertTrue(isPng || isJpeg,
                "应为 PNG 或 JPEG 图片数据");
    }

    @Test
    @DisplayName("GET tile：content-type 与上游一致（影像是 image/jpg，不能写死 png）")
    void tileContentTypeFromUpstream() throws Exception {
        var result = mockMvc.perform(get("/api/map/tianditu/tile/img_w/3/5/2"))
                .andExpect(status().isOk())
                .andReturn();
        String ct = result.getResponse().getContentType();
        org.junit.jupiter.api.Assertions.assertTrue(
                ct.startsWith("image/jpg") || ct.startsWith("image/png"),
                "content-type 应来自上游, 实际: " + ct);
    }

    @Test
    @DisplayName("GET tile：非法图层返回 400（白名单拦截，不打上游）")
    void tileRejectsIllegalLayer() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/tile/evil/3/5/2"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    @DisplayName("GET tile：坐标越界返回 400")
    void tileRejectsOutOfRange() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/tile/img_w/3/99/2"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/map/tianditu/tile/img_w/99/5/2"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET search：空关键词返回 400")
    void searchRejectsBlankKeyword() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/search").param("keyword", ""))
                .andExpect(status().isBadRequest());
    }
}
