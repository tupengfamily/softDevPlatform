package com.learning.springboot.bootstrap;

import com.learning.springboot.entity.Permission;
import com.learning.springboot.entity.Role;
import com.learning.springboot.entity.User;
import com.learning.springboot.repository.PermissionRepository;
import com.learning.springboot.repository.RoleRepository;
import com.learning.springboot.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 数据种子 Service
 * <p>
 * 使用 JPA 保存主表数据,用 JdbcTemplate 显式写入多对多/一对多关系表,
 * 绕开 JPA + SQLite 在 @ManyToMany / @OneToMany 上静默不写关系表的问题。
 */
@Service
public class DataSeedService {

    private static final Logger log = LoggerFactory.getLogger(DataSeedService.class);

    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JdbcTemplate jdbc;

    private static final List<String[]> PERMISSION_SEEDS = List.of(
        new String[]{"user:list",    "用户查看", "MENU"},
        new String[]{"user:edit",    "用户编辑", "BUTTON"},
        new String[]{"role:list",    "角色查看", "MENU"},
        new String[]{"role:edit",    "角色编辑", "BUTTON"},
        new String[]{"dict:list",    "字典查看", "MENU"},
        new String[]{"dict:edit",    "字典编辑", "BUTTON"},
        new String[]{"log:list",     "日志查看", "MENU"},
        new String[]{"dashboard:view","仪表盘",  "MENU"}
    );

    private static final List<String[]> DICT_SEEDS = List.of(
        // typeCode, typeName, description, [itemCode, itemValue, itemValueEn...]
        // 用户状态: 编码参与权限逻辑(拦截器),字典仅维护展示文案(中/英)
        new String[]{"user_status", "用户状态", "启用/禁用/只读", "1", "启用", "Enabled", "0", "禁用", "Disabled", "2", "只读", "Read-only"},
        // 反馈类型/状态: 文案可由管理员在数据字典维护
        new String[]{"feedback_type", "反馈类型", "意见反馈的类型", "RATING", "评分", "Rating", "BUG", "问题反馈", "Bug Report", "SUGGESTION", "功能建议", "Suggestion"},
        new String[]{"feedback_status", "反馈状态", "意见反馈的处理状态", "OPEN", "待处理", "Open", "PROCESSING", "处理中", "In Progress", "DONE", "已处理", "Resolved"},
        // 课时难度
        new String[]{"lesson_difficulty", "课时难度", "课程难度等级", "1", "简单", "Easy", "2", "中等", "Medium", "3", "较难", "Hard"},
        // 消息类型
        new String[]{"notification_type", "消息类型", "站内消息分类", "SYSTEM", "系统通知", "System Notice", "REPLY", "回复通知", "Reply", "TASK", "任务通知", "Task"},
        // 论坛版块
        new String[]{"forum_board", "论坛版块", "论坛分区", "0", "综合讨论", "General Discussion"}
    );

    /** 已废弃的历史字典(业务无对应字段),启动时清理 */
    private static final List<String> OBSOLETE_DICT_TYPES = List.of("gender");

    @Transactional(rollbackFor = Exception.class)
    public void seedPermissions() {
        for (String[] p : PERMISSION_SEEDS) {
            String code = p[0];
            boolean exists = permissionRepository.findAll().stream()
                .anyMatch(x -> code.equals(x.getCode()));
            if (!exists) {
                permissionRepository.save(new Permission(code, p[1], p[2]));
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void seedRoles() {
        if (!roleRepository.existsByCode("ADMIN")) {
            jdbc.update(
                "INSERT INTO t_role (code, name, description, create_time, update_time) VALUES (?, ?, ?, datetime('now', 'localtime'), datetime('now', 'localtime'))",
                "ADMIN", "管理员", "系统管理员,拥有全部权限");
            Long adminId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            for (String code : List.of("user:list", "user:edit", "role:list", "role:edit",
                                       "dict:list", "dict:edit", "log:list", "dashboard:view")) {
                Long permId = jdbc.queryForObject(
                    "SELECT id FROM t_permission WHERE code = ?", Long.class, code);
                jdbc.update("INSERT OR IGNORE INTO t_role_permission (role_id, permission_id) VALUES (?, ?)",
                    adminId, permId);
            }
        }
        if (!roleRepository.existsByCode("USER")) {
            jdbc.update(
                "INSERT INTO t_role (code, name, description, create_time, update_time) VALUES (?, ?, ?, datetime('now', 'localtime'), datetime('now', 'localtime'))",
                "USER", "普通用户", "默认注册用户,只读权限");
            Long userId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            for (String code : List.of("user:list", "dict:list", "dashboard:view")) {
                Long permId = jdbc.queryForObject(
                    "SELECT id FROM t_permission WHERE code = ?", Long.class, code);
                jdbc.update("INSERT OR IGNORE INTO t_role_permission (role_id, permission_id) VALUES (?, ?)",
                    userId, permId);
            }
        }
        // 运营管理角色: 仅含 dashboard:view(用于运营概览)
        if (!roleRepository.existsByCode("OPERATION")) {
            jdbc.update(
                "INSERT INTO t_role (code, name, description, create_time, update_time) VALUES (?, ?, ?, datetime('now', 'localtime'), datetime('now', 'localtime'))",
                "OPERATION", "运营管理员", "只能访问运营管理模块(系统管理仍需 ADMIN)");
            Long opId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            for (String code : List.of("dashboard:view")) {
                Long permId = jdbc.queryForObject(
                    "SELECT id FROM t_permission WHERE code = ?", Long.class, code);
                jdbc.update("INSERT OR IGNORE INTO t_role_permission (role_id, permission_id) VALUES (?, ?)",
                    opId, permId);
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void seedDictTypes() {
        // 清理废弃字典(如 gender: User 实体无性别字段)
        for (String obsolete : OBSOLETE_DICT_TYPES) {
            jdbc.update("DELETE FROM t_dict_item WHERE type_code = ?", obsolete);
            jdbc.update("DELETE FROM t_dict_type WHERE type_code = ?", obsolete);
        }
        for (String[] ds : DICT_SEEDS) {
            String typeCode = ds[0];
            Long typeId;
            if (!existsDictType(typeCode)) {
                jdbc.update(
                    "INSERT INTO t_dict_type (type_code, type_name, description, status, create_time, update_time) VALUES (?, ?, ?, 1, datetime('now', 'localtime'), datetime('now', 'localtime'))",
                    ds[0], ds[1], ds[2]);
                typeId = jdbc.queryForObject("SELECT last_insert_rowid()", Long.class);
            } else {
                typeId = jdbc.queryForObject(
                    "SELECT id FROM t_dict_type WHERE type_code = ?", Long.class, typeCode);
            }
            // 幂等补插缺失项: 已有类型也能追加新项(如 user_status 后加的"只读");
            // 存量项回填英文值(仅当 item_value_en 为空,不覆盖管理员改过的值)
            int sort = 1;
            for (int i = 3; i < ds.length; i += 3) {
                Integer exists = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM t_dict_item WHERE type_code = ? AND item_code = ?",
                    Integer.class, typeCode, ds[i]);
                if (exists == null || exists == 0) {
                    jdbc.update(
                        "INSERT INTO t_dict_item (type_id, type_code, item_code, item_value, item_value_en, sort, status, create_time, update_time) VALUES (?, ?, ?, ?, ?, ?, 1, datetime('now', 'localtime'), datetime('now', 'localtime'))",
                        typeId, ds[0], ds[i], ds[i + 1], ds[i + 2], sort);
                } else {
                    jdbc.update(
                        "UPDATE t_dict_item SET item_value_en = ? WHERE type_code = ? AND item_code = ? AND (item_value_en IS NULL OR item_value_en = '')",
                        ds[i + 2], typeCode, ds[i]);
                }
                sort++;
            }
        }
    }

    private boolean existsDictType(String typeCode) {
        Integer c = jdbc.queryForObject(
            "SELECT COUNT(*) FROM t_dict_type WHERE type_code = ?", Integer.class, typeCode);
        return c != null && c > 0;
    }

    @Transactional(rollbackFor = Exception.class)
    public void seedDefaultUsers() {
        Role adminRole = roleRepository.findByCode("ADMIN").orElseThrow();
        Role userRole = roleRepository.findByCode("USER").orElseThrow();

        // 初始口令支持环境变量覆盖(默认值仅限本地开发; 启动日志不打印口令明文)
        String adminPassword = envOrDefault("ADMIN_INITIAL_PASSWORD", "admin123");
        String userPassword = envOrDefault("USER_INITIAL_PASSWORD", "user123");

        if (!userRepository.existsByUsername("admin")) {
            User admin = new User("admin",
                passwordEncoder.encode(adminPassword),
                "admin@example.com", 30, "系统");
            admin.setStatus(1);
            admin.setRoles(new HashSet<>(Set.of(adminRole)));
            User saved = userRepository.save(admin);
            // JPA 已自动写 t_user_role,无需 JDBC 干预
        }
        if (!userRepository.existsByUsername("user")) {
            User u = new User("user",
                passwordEncoder.encode(userPassword),
                "user@example.com", 25, "默认账号");
            u.setStatus(1);
            u.setRoles(new HashSet<>(Set.of(userRole)));
            userRepository.save(u);
        }
    }

    /** 读取环境变量, 未配置或为空时返回默认值 */
    private String envOrDefault(String name, String defaultValue) {
        String v = System.getenv(name);
        return (v == null || v.isBlank()) ? defaultValue : v;
    }
}