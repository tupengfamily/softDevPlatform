package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.ApiKeyRequest;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.security.JwtAuthFilter;
import com.learning.springboot.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * API Key 管理 API(管理员专属)
 *
 * <ul>
 *   <li>GET    /api/api-keys          — 列表(只含掩码, 无明文)</li>
 *   <li>GET    /api/api-keys/grouped  — 按提供方分组</li>
 *   <li>POST   /api/api-keys          — 新增</li>
 *   <li>PUT    /api/api-keys/{id}     — 修改(secret 留空表示不改密钥本体)</li>
 *   <li>DELETE /api/api-keys/{id}     — 删除</li>
 *   <li>POST   /api/api-keys/{id}/test — 探活</li>
 * </ul>
 *
 * <p><b>安全</b>: 全接口 {@code @RequireAdmin}; 响应体只含元数据与掩码;
 * 密文与明文都不出后端。操作日志切面会把请求参数里的 secret 脱敏(见 OperationLogAspect)。
 */
@RestController
@RequestMapping("/api/api-keys")
@RequireAdmin("API 密钥配置仅管理员可维护")
public class ApiKeyController {

    private final ApiKeyService service;

    public ApiKeyController(ApiKeyService service) {
        this.service = service;
    }

    @GetMapping
    public org.springframework.http.ResponseEntity<?> list() {
        return Result.ok(service.listAll());
    }

    @GetMapping("/grouped")
    public org.springframework.http.ResponseEntity<?> grouped() {
        return Result.ok(service.listGrouped());
    }

    @PostMapping
    @OperationLog(module = "API密钥", action = "新增")
    public org.springframework.http.ResponseEntity<?> create(@RequestBody ApiKeyRequest req,
                                                           HttpServletRequest http) {
        if (req == null) throw new BusinessException("请求体不能为空");
        return Result.ok("已保存", service.create(
                req.getKeyCode(), req.getDisplayName(), req.getProvider(),
                req.getSecret(), req.getSecretHint(), req.getEnabled(), operator(http)));
    }

    @PutMapping("/{id}")
    @OperationLog(module = "API密钥", action = "修改")
    public org.springframework.http.ResponseEntity<?> update(@PathVariable("id") Long id,
                                                             @RequestBody ApiKeyRequest req,
                                                             HttpServletRequest http) {
        if (req == null) throw new BusinessException("请求体不能为空");
        return Result.ok("已保存", service.update(
                id, req.getDisplayName(), req.getProvider(),
                req.getSecret(), req.getSecretHint(), req.getEnabled(), operator(http)));
    }

    @DeleteMapping("/{id}")
    @OperationLog(module = "API密钥", action = "删除")
    public org.springframework.http.ResponseEntity<?> delete(@PathVariable("id") Long id) {
        service.delete(id);
        return Result.ok("已删除", null);
    }

    @PostMapping("/{id}/test")
    @OperationLog(module = "API密钥", action = "探活")
    public org.springframework.http.ResponseEntity<?> test(@PathVariable("id") Long id) {
        return Result.ok(service.test(id));
    }

    private static String operator(HttpServletRequest http) {
        if (http == null) return null;
        Object name = http.getAttribute(JwtAuthFilter.ATTR_USERNAME);
        return name == null ? null : name.toString();
    }
}
