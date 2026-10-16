package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.SystemSetting;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.service.SystemSettingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统 KV 配置 API
 * <ul>
 *   <li>GET /api/system-settings         — 公开的几个键(配额等), 给所有登录用户看</li>
 *   <li>GET /api/system-settings/all     — 全量(管理员)</li>
 *   <li>PUT /api/system-settings/{key}   — 修改(管理员)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/system-settings")
public class SystemSettingController {

    private final SystemSettingService service;

    public SystemSettingController(SystemSettingService service) {
        this.service = service;
    }

    @GetMapping
    public org.springframework.http.ResponseEntity<?> publicView() {
        return Result.ok(service.publicView());
    }

    @GetMapping("/all")
    @RequireAdmin("系统设置仅管理员可维护")
    public org.springframework.http.ResponseEntity<?> listAll() {
        return Result.ok(toView(service.listAll()));
    }

    @PutMapping("/{key}")
    @RequireAdmin("系统设置仅管理员可维护")
    public org.springframework.http.ResponseEntity<?> update(@PathVariable("key") String key,
                                                            @RequestBody Map<String, Object> body) {
        Object v = body == null ? null : body.get("value");
        if (v == null) throw new BusinessException("value 不能为空");
        SystemSetting s = service.upsert(key, String.valueOf(v));
        return Result.ok("已保存", toViewOne(s));
    }

    private static List<Map<String, Object>> toView(List<SystemSetting> list) {
        return list.stream().map(SystemSettingController::toViewOne).toList();
    }

    private static Map<String, Object> toViewOne(SystemSetting s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.getKey());
        m.put("value", s.getValue());
        m.put("description", s.getDescription());
        m.put("updateTime", s.getUpdateTime());
        return m;
    }
}
