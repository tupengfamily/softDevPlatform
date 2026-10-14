package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.entity.WikiArticle;
import com.learning.springboot.security.JwtUtil;
import com.learning.springboot.service.PersonalCenterService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wiki 分享公开只读接口(凭分享令牌访问)。
 * <p>
 * 文章可选设置"需要登录才能查看"(shareRequireLogin):
 * - 默认 false: 任何拿到链接的人都能看(游客可读)
 * - 置为 true: 必须带有效 JWT 才能看, 否则 401
 */
@RestController
@RequestMapping("/api/public/wiki")
public class PublicWikiController {

    @Autowired private PersonalCenterService personalCenterService;
    @Autowired private JwtUtil jwtUtil;

    /** 凭分享令牌查看文章(计一次浏览; 文章要求登录时校验 token) */
    @GetMapping("/{token}")
    public ResponseEntity<?> view(@PathVariable String token, HttpServletRequest request) {
        WikiArticle a = personalCenterService.viewShared(token, isAuthenticated(request));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", a.getTitle());
        data.put("content", a.getContent());
        data.put("contentType", a.getContentType());
        data.put("viewCount", a.getViewCount());
        data.put("updateTime", a.getUpdateTime());
        data.put("requireLogin", a.getShareRequireLogin());
        return Result.ok(data);
    }

    /** 判断当前请求是否携带有效登录态(该接口免鉴权, 故手动解析可选 token) */
    private boolean isAuthenticated(HttpServletRequest request) {
        try {
            String token = jwtUtil.resolveToken(request);
            return token != null && !token.isEmpty() && jwtUtil.isValid(token);
        } catch (Exception ignore) {
            return false;
        }
    }
}
