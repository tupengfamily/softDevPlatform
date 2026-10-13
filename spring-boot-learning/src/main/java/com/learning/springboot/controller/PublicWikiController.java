package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.entity.WikiArticle;
import com.learning.springboot.service.PersonalCenterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Wiki 分享公开只读接口(无需登录,凭分享令牌访问)
 */
@RestController
@RequestMapping("/api/public/wiki")
public class PublicWikiController {

    @Autowired private PersonalCenterService personalCenterService;

    /** 凭分享令牌查看文章(游客可读,计一次浏览) */
    @GetMapping("/{token}")
    public ResponseEntity<?> view(@PathVariable String token) {
        WikiArticle a = personalCenterService.viewShared(token);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("title", a.getTitle());
        data.put("content", a.getContent());
        data.put("contentType", a.getContentType());
        data.put("viewCount", a.getViewCount());
        data.put("updateTime", a.getUpdateTime());
        return Result.ok(data);
    }
}
