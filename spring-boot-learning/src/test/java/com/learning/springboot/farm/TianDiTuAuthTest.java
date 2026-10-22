package com.learning.springboot.farm;

import com.learning.springboot.config.JwtProperties;
import com.learning.springboot.config.TianDiTuProperties;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.security.JwtAuthFilter;
import com.learning.springboot.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 天地图端点的<b>鉴权策略验证</b>。
 *
 * <p><b>要证明的事</b>：瓦片/搜索等端点<b>不需要登录</b>，
 * 匿名请求能拿到 200 而不是 401。
 *
 * <p><b>为什么必须这样验证</b>：瓦片是浏览器 {@code <img>} 直接加载的，
 * 浏览器不会给 {@code <img>} 带 {@code Authorization} 头。
 * 一旦这些端点走鉴权流程，地图会直接空白（401 破图），
 * 而且这种问题在「已登录的开发者本机」上完全看不出来 ——
 * 所以必须用<b>不带 token 的请求</b>来验证。
 *
 * <p><b>不���用 Mockito</b>：本机 JDK 27 下 Mockito 的 inline mock maker
 * 无法 mock {@link JwtUtil}（"Could not modify all classes"），
 * 故改用真实的 {@link JwtUtil} 实例（本地构造一个临时密钥即可）。
 */
class TianDiTuAuthTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TianDiTuProperties props = new TianDiTuProperties();
        props.setServerKey("test-key");

        TianDiTuController controller = new TianDiTuController();
        set(controller, "service", new TianDiTuService(props, url -> new TianDiTuService.RawResponse(
                200, "{\"resultType\":1,\"pois\":[]}".getBytes(StandardCharsets.UTF_8),
                "application/json")));
        set(controller, "props", props);

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters((OncePerRequestFilter) newFilter())
                .build();
    }

    /**
     * 构造一个可用的 JwtAuthFilter（内部用真实 JwtUtil + 一个空 UserRepository 桩）。
     */
    private JwtAuthFilter newFilter() {
        JwtAuthFilter filter = new JwtAuthFilter();

        JwtProperties jwtProps = new JwtProperties();
        jwtProps.setSecret(java.util.Base64.getEncoder().encodeToString(new byte[32]));
        JwtUtil jwtUtil = new JwtUtil();
        set(jwtUtil, "props", jwtProps);
        jwtUtil.init();

        set(filter, "jwtUtil", jwtUtil);
        // UserRepository 方法很多，用动态代理占位即可：
        // 匿名请求（无 token）在 filter 里就放行，根本不会查库
        set(filter, "userRepository", newStubRepository());
        return filter;
    }

    /**
     * UserRepository 的动态代理桩：任何方法都返回 null。
     * <p>匿名请求不会调用到它（有 token 才会查 tokenVersion）。
     */
    private static UserRepository newStubRepository() {
        return (UserRepository) java.lang.reflect.Proxy.newProxyInstance(
                UserRepository.class.getClassLoader(),
                new Class<?>[]{UserRepository.class},
                (proxy, method, args) -> null);
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入失败: " + field, e);
        }
    }

    @Test
    @DisplayName("匿名请求（无 token）访问瓦片 → 200 而非 401")
    void anonymousTileAccessAllowed() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/tile/img_w/3/5/2"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名请求访问 search → 200 而非 401")
    void anonymousSearchAllowed() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/search").param("keyword", "北京"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("匿名请求访问 geocode / reverse → 200 而非 401")
    void anonymousGeocodeAllowed() throws Exception {
        mockMvc.perform(get("/api/map/tianditu/geocode").param("keyword", "北京"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/map/tianditu/reverse").param("lon", "116.4").param("lat", "39.9"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("天地图路径不进 shouldNotFilter 白名单（走 isGuestReadable 分支，保留 userId 能力）")
    void filterDoesNotSkipButAllowsGuest() throws Exception {
        JwtAuthFilter filter = newFilter();
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/map/tianditu/tile/img_w/3/5/2");
        Method snf = JwtAuthFilter.class.getDeclaredMethod("shouldNotFilter",
                jakarta.servlet.http.HttpServletRequest.class);
        snf.setAccessible(true);
        assertEquals(false, snf.invoke(filter, req),
                "天地图路径不应进 shouldNotFilter 白名单（那样带 token 的请求也拿不到 userId）");
    }

    @Test
    @DisplayName("isGuestReadable：GET 天地图路径命中；非 GET 不放行")
    void guestReadableMatchesMapPath() throws Exception {
        JwtAuthFilter filter = newFilter();
        Method m = JwtAuthFilter.class.getDeclaredMethod("isGuestReadable",
                jakarta.servlet.http.HttpServletRequest.class);
        m.setAccessible(true);

        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/map/tianditu/tile/img_w/3/5/2");
        assertTrue((Boolean) m.invoke(filter, get), "GET 天地图路径应被判定为游客可读");

        // 天地图只有读接口，非 GET 不放行
        MockHttpServletRequest post = new MockHttpServletRequest("POST", "/api/map/tianditu/search");
        assertEquals(false, m.invoke(filter, post), "非 GET 不应放行");
    }
}
