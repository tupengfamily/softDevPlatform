package com.learning.springboot.service.impl;

import com.learning.springboot.dto.ChangePasswordRequest;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.dto.UserVO;
import com.learning.springboot.entity.Role;
import com.learning.springboot.entity.User;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.RoleRepository;
import com.learning.springboot.repository.UserRepository;
import com.learning.springboot.service.UserService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * UserService 实现类
 * <p>
 * - @Transactional(rollbackFor = Exception.class): 任何异常都回滚
 * - @Transactional(readOnly = true): 只读事务,提升查询性能
 */
@Service
public class UserServiceImpl implements UserService {

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO createUser(String username, String password, String email, Integer age, String address) {
        if (userRepository.existsByUsername(username)) {
            throw new BusinessException(409, "用户名已存在: " + username);
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setAge(age);
        user.setAddress(address);
        user.setStatus(1);

        // 默认赋予 USER 角色
        roleRepository.findByCode("USER").ifPresent(r ->
            user.setRoles(new HashSet<>(Set.of(r))));

        return UserVO.from(userRepository.save(user));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserVO updateUser(Long id, String email, Integer age, String address, Integer status) {
        User existing = userRepository.findById(id)
            .orElseThrow(() -> new BusinessException(404, "用户不存在: id=" + id));
        if (email != null) existing.setEmail(email);
        if (age != null) existing.setAge(age);
        if (address != null) existing.setAddress(address);
        if (status != null) existing.setStatus(status);
        return UserVO.from(userRepository.save(existing));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long id) {
        if (!userRepository.existsById(id)) {
            throw new BusinessException(404, "用户不存在: id=" + id);
        }
        // 🔴 2026-10-25：删号必须先撤销 token。
        // 否则用户被删除后，其旧 JWT 依然能通过 JwtAuthFilter
        // （userTv == null 时 tv 比对被跳过）继续访问接口。
        revokeTokens(id);
        userRepository.deleteById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public UserVO getById(Long id) {
        User user = userRepository.findByUsernameWithRoles(
                userRepository.findById(id)
                    .orElseThrow(() -> new BusinessException(404, "用户不存在: id=" + id))
                    .getUsername())
            .orElseThrow(() -> new BusinessException(404, "用户不存在: id=" + id));
        return UserVO.from(user);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<UserVO> pageUsers(String keyword, Integer status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page - 1, 0), Math.min(Math.max(size, 1), 100),
            Sort.by(Sort.Direction.DESC, "id"));
        Page<User> p = userRepository.search(blankToNull(keyword), status, pageable);
        Page<UserVO> mapped = p.map(UserVO::from);
        return PageResult.of(mapped);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(Long userId, ChangePasswordRequest req) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        if (!passwordEncoder.matches(req.getOldPassword(), user.getPassword())) {
            throw new BusinessException(400, "旧密码错误");
        }
        user.setPassword(passwordEncoder.encode(req.getNewPassword()));
        userRepository.save(user);
        // 🔴 2026-10-25：改密码必须撤销旧 token。
        // 否则用户改密后，攻击者手里的旧 JWT 仍可继续用最长 24 小时
        // （jwt.expiration-millis=86400000）。
        // 顺序：先 incrementTokenVersion（@Modifying 直写 DB），再 save(user)。
        // 反过来的话，JPA 一级缓存里 user 的旧 tokenVersion 会被 save 写回，把刚递增的值覆盖掉。
        revokeTokens(userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, Set<String> roleCodes) {
        if (roleCodes == null) roleCodes = new HashSet<>();
        User user = userRepository.findByUsernameWithRoles(
                userRepository.findById(userId)
                    .orElseThrow(() -> new BusinessException(404, "用户不存在"))
                    .getUsername())
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));

        Set<Role> newRoles = new HashSet<>(roleRepository.findByCodeIn(roleCodes));
        if (newRoles.size() != roleCodes.size()) {
            throw new BusinessException(400, "部分角色编码无效");
        }
        user.setRoles(newRoles);
        userRepository.save(user);
        // 🔴 2026-10-25：改角色必须撤销旧 token。
        // JWT 里带的是角色快照，AdminInterceptor 只读快照不查库，
        // 撤销管理员权限后旧 JWT 最长 24 小时仍能访问 @RequireAdmin 接口。
        revokeTokens(userId);
    }

    /**
     * 撤销该用户所有已签发的 token（递增 tokenVersion）。
     *
     * <p>🔴 2026-10-25：所有**改变用户权限或凭证**的入口都必须调用它：
     * 改密码、撤销角色、禁用账号。
     * 漏掉任何一处，被撤销的权限就还能被旧 token 用一整天。
     */
    private void revokeTokens(Long userId) {
        userRepository.incrementTokenVersion(userId, java.time.LocalDateTime.now());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(Long userId, Integer status) {
        if (status == null || (status != User.STATUS_DISABLED && status != User.STATUS_ENABLED && status != User.STATUS_READONLY)) {
            throw new BusinessException(400, "status 只能为 0(禁用)、1(启用) 或 2(只读)");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BusinessException(404, "用户不存在"));
        user.setStatus(status);
        userRepository.save(user);
        // 🔴 2026-10-25：禁用账号必须撤销 token。
        // UserStatusInterceptor 虽然会拦已禁用用户，但读操作若走游客可读路径仍可能放行；
        // 撤销 token 是唯一彻底的收口。
        if (status == User.STATUS_DISABLED) {
            revokeTokens(userId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> listAll() {
        // JOIN FETCH 预加载 roles,避免序列化阶段的懒加载异常
        return userRepository.findAllWithRoles();
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> searchByUsername(String keyword) {
        return userRepository.searchByUsernameLike(keyword == null ? "" : keyword);
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> findByAgeRange(Integer min, Integer max) {
        return userRepository.findByAgeBetween(min, max);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}