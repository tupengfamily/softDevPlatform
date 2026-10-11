package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.RoleDTO;
import com.learning.springboot.dto.RoleUpsertRequest;
import com.learning.springboot.service.RoleService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
import java.util.Set;

/**
 * 角色管理 API
 */
@RestController
@RequestMapping("/api/roles")
@RequireAdmin("角色管理仅管理员可操作")
public class RoleController {

    @Autowired private RoleService roleService;

    @GetMapping
    public ResponseEntity<?> list() {
        List<RoleDTO> all = roleService.listAll();
        return Result.ok(all);
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return Result.ok(roleService.getById(id));
    }

    @PostMapping
    public ResponseEntity<?> create(@Valid @RequestBody RoleUpsertRequest req) {
        return Result.created("创建成功", roleService.create(req));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody RoleUpsertRequest req) {
        return Result.ok("更新成功", roleService.update(id, req));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        roleService.delete(id);
        return Result.ok("删除成功", null);
    }

    /** 给角色分配权限 */
    @PutMapping("/{id}/permissions")
    public ResponseEntity<?> assignPermissions(@PathVariable Long id, @RequestBody Map<String, Set<Long>> body) {
        Set<Long> ids = body.getOrDefault("permissionIds", Set.of());
        roleService.assignPermissions(id, ids);
        return Result.ok("权限已更新", null);
    }
}