package com.learning.springboot.service;

import com.learning.springboot.dto.ChangePasswordRequest;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.dto.UserVO;
import com.learning.springboot.entity.User;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserService 集成测试
 */
@SpringBootTest
@Transactional
@Rollback
@DisplayName("UserService 集成测试")
class UserServiceTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository userRepository;

    @Test
    @DisplayName("创建用户(参数式 API)")
    void testCreateUser() {
        UserVO created = userService.createUser(
            "testUserA", "password123", "ta@example.com", 20, "testAddr");
        assertNotNull(created.getId());
        assertEquals("testUserA", created.getUsername());
    }

    @Test
    @DisplayName("创建用户时用户名重复抛异常")
    void testCreateDuplicateUsername() {
        // admin 是 AdminBootstrap 创建的
        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.createUser("admin", "pw123456", "x@x.com", 20, null));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    @DisplayName("按 ID 查询用户")
    void testGetById() {
        UserVO created = userService.createUser("queryUserA", "pw123456", "q@example.com", 25, null);
        UserVO found = userService.getById(created.getId());
        assertEquals(created.getId(), found.getId());
        assertEquals("queryUserA", found.getUsername());
    }

    @Test
    @DisplayName("按 ID 查询不存在的用户抛异常")
    void testGetByIdNotFound() {
        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.getById(9999999L));
        assertTrue(ex.getMessage().contains("用户不存在"));
    }

    @Test
    @DisplayName("分页查询")
    void testPageUsers() {
        PageResult<UserVO> page = userService.pageUsers(null, null, 1, 10);
        assertNotNull(page.getRecords());
        assertTrue(page.getTotal() >= 1);
    }

    @Test
    @DisplayName("修改密码")
    void testChangePassword() {
        UserVO u = userService.createUser("pwdtest", "old123456", "p@example.com", 20, null);
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("old123456");
        req.setNewPassword("new123456");
        userService.changePassword(u.getId(), req);

        // 反向验证: 旧密码已失效,再用旧密码改应抛 BusinessException
        ChangePasswordRequest again = new ChangePasswordRequest();
        again.setOldPassword("old123456");
        again.setNewPassword("another123456");
        BusinessException ex = assertThrows(BusinessException.class,
            () -> userService.changePassword(u.getId(), again));
        assertTrue(ex.getMessage().contains("旧密码错误"));
    }

    @Test
    @DisplayName("启用/禁用用户")
    void testUpdateStatus() {
        UserVO u = userService.createUser("statustest", "pw123456", "s@example.com", 20, null);
        userService.updateStatus(u.getId(), 0);
        UserVO found = userService.getById(u.getId());
        assertEquals(0, found.getStatus());

        userService.updateStatus(u.getId(), 1);
        found = userService.getById(u.getId());
        assertEquals(1, found.getStatus());
    }

    @Test
    @DisplayName("分配角色")
    void testAssignRoles() {
        UserVO u = userService.createUser("roletest", "pw123456", "r@example.com", 20, null);
        userService.assignRoles(u.getId(), Set.of("ADMIN", "USER"));
        UserVO found = userService.getById(u.getId());
        assertTrue(found.getRoleCodes().contains("ADMIN"));
        assertTrue(found.getRoleCodes().contains("USER"));
    }

    @Test
    @DisplayName("按年龄范围查询(兼容旧接口)")
    void testFindByAgeRange() {
        for (int i = 0; i < 3; i++) {
            userService.createUser("age" + i, "pw123456", "age" + i + "@example.com", 20 + i, null);
        }
        List<User> users = userService.findByAgeRange(20, 22);
        assertTrue(users.size() >= 3);
    }

    @Test
    @DisplayName("按用户名模糊查询(兼容旧接口)")
    void testSearchByUsername() {
        for (int i = 0; i < 3; i++) {
            userService.createUser("searchxx" + i, "pw123456", "s" + i + "@example.com", 20, null);
        }
        List<User> users = userService.searchByUsername("searchxx");
        assertTrue(users.size() >= 3);
    }
}