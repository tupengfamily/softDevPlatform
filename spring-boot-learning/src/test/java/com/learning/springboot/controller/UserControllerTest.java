package com.learning.springboot.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.dto.UserDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserController Web 层测试
 * <p>
 * 测试时先用 admin 登录拿到 token,然后所有受保护接口都带 Bearer Token。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@Rollback
@DisplayName("UserController Web 测试")
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    private String token;

    @BeforeEach
    void login() throws Exception {
        Map<String, String> loginReq = new HashMap<>();
        loginReq.put("username", "admin");
        loginReq.put("password", "admin123");
        MvcResult r = mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(loginReq)))
            .andExpect(status().isOk())
            .andReturn();
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        token = body.path("data").path("token").asText();
        assertTrue(token != null && !token.isEmpty(), "登录 token 不应为空");
    }

    @Test
    @DisplayName("未带 token 访问受保护资源 → 401")
    void testNoTokenUnauthorized() throws Exception {
        mockMvc.perform(get("/api/users"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/users - 带 token 查询所有用户")
    void testListAll() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    @DisplayName("GET /api/users/page - 分页查询")
    void testPageUsers() throws Exception {
        mockMvc.perform(get("/api/users/page?page=1&size=10").header("Authorization", "Bearer " + token))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.total").exists())
            .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("POST /api/users - 创建用户成功")
    void testCreateUser() throws Exception {
        UserDTO dto = new UserDTO();
        dto.setUsername("newuser");
        dto.setEmail("new@example.com");
        dto.setAge(25);
        dto.setAddress("测试地址");
        dto.setPassword("password123");

        mockMvc.perform(post("/api/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.message").value("创建成功"))
            .andExpect(jsonPath("$.data.username").value("newuser"));
    }

    @Test
    @DisplayName("POST /api/users - 邮箱格式错误返回 400")
    void testCreateUserInvalidEmail() throws Exception {
        UserDTO dto = new UserDTO();
        dto.setUsername("invaliduser");
        dto.setEmail("invalid-email");
        dto.setAge(25);
        dto.setPassword("password123");

        MvcResult result = mockMvc.perform(post("/api/users")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(dto)))
            .andExpect(status().isBadRequest())
            .andReturn();

        String response = result.getResponse().getContentAsString();
        assertTrue(response.contains("邮箱") || response.contains("email"));
    }

    @Test
    @DisplayName("GET /api/auth/public-key - 公开接口无需 token")
    void testPublicEndpointNeedsNoToken() throws Exception {
        // 🔴 原来这条测的是 /api/hello，但 HelloController 已在 d9c4663
        // 「删除 7 处废弃代码」里整文件删掉了 —— 测试没跟着删，
        // 于是它一直是个「打 500 也算过」的僵尸断言（mockMvc 找不到映射 → 500）。
        // 现在改测真实存在的公开端点：取 RSA 公钥（前端登录前就要拉它解密密码）。
        mockMvc.perform(get("/api/auth/public-key"))
            .andExpect(status().isOk());
    }
}