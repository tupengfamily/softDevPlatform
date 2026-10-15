package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

/**
 * 用户实体类
 * <p>
 * 关键注解:
 * - @Entity: 标识这是一个 JPA 实体,会被映射到数据库表
 * - @Table: 指定对应的表名
 * - @Id: 主键
 * - @GeneratedValue: 主键生成策略
 * - @Column: 字段约束(非空、长度等)
 * - @ManyToMany: 多对多关系,配合 @JoinTable 指定中间表
 * <p>
 * 重要字段:
 * - password: BCrypt 加密后的密码,严禁明文
 * - status: 1=启用,0=禁用,2=只读(可浏览,禁止编辑/发言等写操作)
 * - roles: 用户拥有的角色(懒加载)
 */
@Entity
@Table(name = "t_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    /** BCrypt 加密后的密码 */
    @Column(nullable = false, length = 100)
    private String password;

    @Column(nullable = false, length = 100)
    private String email;

    /** 昵称(展示名,可空;为空时前端回退显示 username) */
    @Column(length = 50)
    private String nickname;

    /** 头像:图片 URL 或 emoji 字符(为空时前端显示首字母) */
    @Column(length = 500)
    private String avatar;

    /** 个性签名 */
    @Column(length = 200)
    private String signature;

    @Column(nullable = false)
    private Integer age;

    @Column(length = 200)
    private String address;

    /** 0=禁用 */
    public static final int STATUS_DISABLED = 0;
    /** 1=启用 */
    public static final int STATUS_ENABLED = 1;
    /** 2=只读: 可登录可浏览,禁止编辑/发言等一切写操作 */
    public static final int STATUS_READONLY = 2;

    /** 1=启用,0=禁用,2=只读 */
    @Column(nullable = false)
    private Integer status = 1;

    /** 创建时间 */
    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    /** 更新时间 */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    /** 最近登录时间 */
    @Column(name = "last_login_time")
    private LocalDateTime lastLoginTime;

    /** 最近登录 IP */
    @Column(name = "last_login_ip", length = 64)
    private String lastLoginIp;

    /**
     * Token 版本号(用于单点登出 / 退出所有设备)
     * <p>登录时把当前值写入 JWT claim "tv"; JwtAuthFilter 每次请求比对 token.tv == user.tv;
     * 不匹配则视为旧 token 拒绝。一键"退出所有设备" = 递增此字段,所有旧 token 立即失效。
     */
    @Column(name = "token_version", nullable = false)
    private Long tokenVersion = 0L;

    /**
     * 用户拥有的角色(多对多,通过 t_user_role 关联)
     * 懒加载: 只有显式访问时才会查询
     */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "t_user_role",
        joinColumns = @JoinColumn(name = "user_id"),
        inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new HashSet<>();

    public User() {}

    public User(String username, String password, String email, Integer age, String address) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.age = age;
        this.address = address;
    }

    /** JPA 回调: 持久化前自动调用 */
    @jakarta.persistence.PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        this.createTime = now;
        this.updateTime = now;
        if (this.status == null) this.status = 1;
    }

    /** JPA 回调: 更新前自动调用 */
    @jakarta.persistence.PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    // ============== 业务方法 ==============

    /** 添加一个角色 */
    public void addRole(Role role) {
        this.roles.add(role);
    }

    /** 移除一个角色 */
    public void removeRole(Role role) {
        this.roles.remove(role);
    }

    /** 获取所有角色编码(便于签发 JWT) */
    public Set<String> getRoleCodes() {
        Set<String> codes = new HashSet<>();
        for (Role r : roles) codes.add(r.getCode());
        return codes;
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getSignature() { return signature; }
    public void setSignature(String signature) { this.signature = signature; }

    public Integer getAge() { return age; }
    public void setAge(Integer age) { this.age = age; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }

    public LocalDateTime getLastLoginTime() { return lastLoginTime; }
    public void setLastLoginTime(LocalDateTime lastLoginTime) { this.lastLoginTime = lastLoginTime; }

    public String getLastLoginIp() { return lastLoginIp; }
    public void setLastLoginIp(String lastLoginIp) { this.lastLoginIp = lastLoginIp; }
    public Long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(Long tokenVersion) { this.tokenVersion = tokenVersion; }

    public Set<Role> getRoles() { return roles; }
    public void setRoles(Set<Role> roles) { this.roles = roles; }

    @Override
    public String toString() {
        return "User{id=" + id + ", username='" + username + "', email='" + email
                + "', age=" + age + ", status=" + status + "}";
    }
}