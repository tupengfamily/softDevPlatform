package com.learning.springboot.service;

import com.learning.springboot.dto.LoginRequest;
import com.learning.springboot.dto.LoginResponse;
import com.learning.springboot.dto.ProfileUpdateRequest;
import com.learning.springboot.dto.RegisterRequest;
import org.springframework.web.multipart.MultipartFile;

/**
 * 认证服务接口
 */
public interface AuthService {

    /** 用户登录,返回 Token + 用户信息 */
    LoginResponse login(LoginRequest req, String clientIp);

    /** 用户注册 */
    LoginResponse register(RegisterRequest req, String clientIp);

    /** 获取当前登录用户的信息 */
    LoginResponse me(Long userId);

    /** 本人更新个人信息(昵称/头像/个性签名/邮箱/年龄/地址) */
    LoginResponse updateProfile(Long userId, ProfileUpdateRequest req);

    /** 上传头像图片(校验类型/大小后落盘 data/uploads),返回新头像 URL */
    String updateAvatar(Long userId, MultipartFile file);
}