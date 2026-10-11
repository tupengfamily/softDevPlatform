package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.AssignRoleRequest;
import com.learning.springboot.dto.ChangePasswordRequest;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.dto.UserDTO;
import com.learning.springboot.dto.UserVO;
import com.learning.springboot.entity.User;
import com.learning.springboot.service.UserService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * 用户 REST 控制器
 * <p>
 * 所有响应统一通过 common.Result 返回,保持 {code, message, data, timestamp} 格式。
 */
@RestController
@RequestMapping("/api/users")
@RequireAdmin("用户管理仅管理员可操作")
public class UserController {

    @Autowired private UserService userService;

    /** 分页查询(支持关键字 + 状态) */
    @GetMapping("/page")
    public ResponseEntity<?> page(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        PageResult<UserVO> p = userService.pageUsers(keyword, status, page, size);
        return Result.ok(p);
    }

    /** 按 ID 查询 */
    @GetMapping("/{id}")
    public ResponseEntity<?> getById(@PathVariable Long id) {
        return Result.ok(userService.getById(id));
    }

    /** 创建用户(POST /api/users) */
    @PostMapping
    @OperationLog(module = "用户管理", action = "新增")
    public ResponseEntity<?> create(@Valid @RequestBody UserDTO dto) {
        UserVO created = userService.createUser(
            dto.getUsername(), dto.getPassword(), dto.getEmail(), dto.getAge(), dto.getAddress());
        return Result.created("创建成功", created);
    }

    /** 更新用户 */
    @PutMapping("/{id}")
    @OperationLog(module = "用户管理", action = "更新")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody UserDTO dto) {
        UserVO updated = userService.updateUser(id, dto.getEmail(), dto.getAge(), dto.getAddress(), null);
        return Result.ok("更新成功", updated);
    }

    /** 启用/禁用用户 */
    @PutMapping("/{id}/status")
    @OperationLog(module = "用户管理", action = "状态变更")
    public ResponseEntity<?> updateStatus(@PathVariable Long id, @RequestBody java.util.Map<String, Integer> body) {
        userService.updateStatus(id, body.get("status"));
        return Result.ok("状态已更新", null);
    }

    /** 修改密码 */
    @PutMapping("/{id}/password")
    @OperationLog(module = "用户管理", action = "修改密码", recordParams = false)
    public ResponseEntity<?> changePassword(@PathVariable Long id, @Valid @RequestBody ChangePasswordRequest req) {
        userService.changePassword(id, req);
        return Result.ok("密码已修改", null);
    }

    /** 分配角色 */
    @PutMapping("/{id}/roles")
    @OperationLog(module = "用户管理", action = "分配角色")
    public ResponseEntity<?> assignRoles(@PathVariable Long id, @Valid @RequestBody AssignRoleRequest req) {
        userService.assignRoles(id, req.getRoleCodes());
        return Result.ok("角色已更新", null);
    }

    /** 删除用户 */
    @DeleteMapping("/{id}")
    @OperationLog(module = "用户管理", action = "删除")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        userService.deleteUser(id);
        return Result.ok("删除成功", null);
    }

    /** 查询所有(兼容旧接口) */
    @GetMapping
    public ResponseEntity<?> listAll() {
        // 返回 UserVO 而非实体: 实体带 Role.permissions 懒加载链,直接序列化会抛 LazyInitializationException
        List<UserVO> data = userService.listAll().stream().map(UserVO::from).toList();
        return Result.ok(data);
    }

    /** 按用户名模糊查询 */
    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam(defaultValue = "") String keyword) {
        return Result.ok(userService.searchByUsername(keyword));
    }

    /** 按年龄范围 */
    @GetMapping("/age")
    public ResponseEntity<?> findByAge(@RequestParam Integer min, @RequestParam Integer max) {
        return Result.ok(userService.findByAgeRange(min, max));
    }
}