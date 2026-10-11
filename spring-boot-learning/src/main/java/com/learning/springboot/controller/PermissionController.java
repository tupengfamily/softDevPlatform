package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.PermissionDTO;
import com.learning.springboot.entity.Permission;
import com.learning.springboot.service.PermissionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 权限管理 API
 */
@RestController
@RequestMapping("/api/permissions")
@RequireAdmin("权限管理仅管理员可操作")
public class PermissionController {

    @Autowired private PermissionService permissionService;

    @GetMapping
    public ResponseEntity<?> list() {
        return Result.ok(permissionService.listAll());
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Permission p) {
        return Result.created("创建成功", permissionService.create(p));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        permissionService.delete(id);
        return Result.ok("删除成功", null);
    }
}