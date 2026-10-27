package com.learning.springboot.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * GlobalExceptionHandler 回归测试
 * <p>
 * 🔴 2026-10-26：本类锁定「客户端错的 HTTP 语义不得被兜底成 500」。
 * <p>
 * 修复前实测：
 * <ul>
 *   <li>静态资源不存在 → <b>500</b>（应 404）</li>
 *   <li>请求方法不允许 → <b>500</b>（应 405）</li>
 *   <li>媒体类型不支持 → <b>500</b>（应 415）</li>
 * </ul>
 * 这些都是 Spring MVC 直接抛到容器层、不经过任何 {@code @ExceptionHandler} 的异常，
 * 于是落进兜底 {@code handleAll} → 全部变成 500。
 * <p>
 * 为什么值得锁：500 会让前端弹「服务器内部错误」并计入错误上报，
 * 把「客户端打错 URL」这类日常噪声伪装成线上故障，污染告警口径。
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("GlobalExceptionHandler HTTP 语义映射测试")
class GlobalExceptionHandlerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    /**
     * 断言 HTTP 状态码与响应体里的 code 一致，且 message 是可读文案（不是「服务器内部错误」）。
     */
    private void assertStatus(int expected, MvcResult r) throws Exception {
        assertEquals(expected, r.getResponse().getStatus(),
                "HTTP 状态码应为 " + expected + "，实际 " + r.getResponse().getStatus());
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        assertEquals(expected, body.path("code").asInt(),
                "响应体 code 字段应与 HTTP 状态码一致");
        String msg = body.path("message").asText("");
        assertTrue(!msg.isBlank(), "message 不应为空");
        assertTrue(!"服务器内部错误".equals(msg),
                "🔴 客户端错误被兜底成 500：message = " + msg);
    }

    @Test
    @DisplayName("静态资源不存在 → 404（修复前是 500）")
    void missingStaticResourceReturns404() throws Exception {
        MvcResult r = mockMvc.perform(get("/totally-missing-file-xyz.js"))
                .andReturn();
        assertStatus(404, r);
    }

    @Test
    @DisplayName("请求方法不允许 → 405（修复前是 500）")
    void methodNotSupportedReturns405() throws Exception {
        // /api/auth/public-key 只支持 GET，DELETE 应得 405
        MvcResult r = mockMvc.perform(delete("/api/auth/public-key"))
                .andReturn();
        assertStatus(405, r);
    }

    @Test
    @DisplayName("媒体类型不支持 → 415（修复前是 500）")
    void unsupportedMediaTypeReturns415() throws Exception {
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_XML)
                        .content("<a/>"))
                .andReturn();
        assertStatus(415, r);
    }

    @Test
    @DisplayName("业务异常仍按自身 code 返回（不被新处理器截胡）")
    void businessExceptionStillMapsToItsOwnCode() throws Exception {
        // 登录参数缺失 → BusinessException(400) → 应保持 400
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        assertStatus(400, r);
    }

    @Test
    @DisplayName("鉴权失败仍是 401（不被兜底吞掉）")
    void unauthorizedStillReturns401() throws Exception {
        MvcResult r = mockMvc.perform(get("/api/games/farm/state?slot=0"))
                .andReturn();
        assertEquals(401, r.getResponse().getStatus(),
                "未带 token 访问受保护接口应仍为 401");
    }
}
