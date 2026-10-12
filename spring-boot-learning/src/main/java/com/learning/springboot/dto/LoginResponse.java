package com.learning.springboot.dto;

import java.util.Set;

/**
 * 登录响应
 * <p>
 * 包含 token、过期时间、当前用户信息(不含密码)。
 */
public class LoginResponse {

    private String token;
    private long expiresIn;
    private Long userId;
    private String username;
    private String email;
    private String nickname;
    private String avatar;
    private String signature;
    private Integer age;
    private String address;
    private Integer status;
    private Set<String> roles;
    private Set<String> permissions;

    public LoginResponse() {}

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public long getExpiresIn() { return expiresIn; }
    public void setExpiresIn(long expiresIn) { this.expiresIn = expiresIn; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

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

    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }

    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = permissions; }
}