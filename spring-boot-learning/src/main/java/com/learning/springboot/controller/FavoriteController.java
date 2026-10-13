package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.UserFavorite;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.PersonalCenterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 个人收藏 API(网址收藏: 常用网站/工具/文档)
 */
@RestController
@RequestMapping("/api/favorites")
public class FavoriteController {

    @Autowired private PersonalCenterService personalCenterService;

    /** 我的收藏(按排序) */
    @GetMapping
    public ResponseEntity<?> list(@CurrentUser Long userId) {
        List<UserFavorite> data = personalCenterService.listFavorites(userId);
        return Result.ok(data);
    }

    /** 新增收藏 */
    @PostMapping
    @OperationLog(module = "个人中心", action = "新增收藏")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        UserFavorite data = personalCenterService.createFavorite(userId,
            body.get("title"), body.get("url"), body.get("category"), body.get("remark"));
        return Result.created("收藏成功", data);
    }

    /** 编辑收藏 */
    @PutMapping("/{id}")
    @OperationLog(module = "个人中心", action = "编辑收藏")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body, @CurrentUser Long userId) {
        UserFavorite data = personalCenterService.updateFavorite(userId, id,
            body.get("title"), body.get("url"), body.get("category"), body.get("remark"));
        return Result.ok("已更新", data);
    }

    /** 删除收藏 */
    @DeleteMapping("/{id}")
    @OperationLog(module = "个人中心", action = "删除收藏")
    public ResponseEntity<?> delete(@PathVariable Long id, @CurrentUser Long userId) {
        personalCenterService.deleteFavorite(userId, id);
        return Result.ok("已删除", null);
    }

    /** 排序: 上移/下移 {direction: "up"|"down"} */
    @PutMapping("/{id}/move")
    public ResponseEntity<?> move(@PathVariable Long id, @RequestBody Map<String, String> body, @CurrentUser Long userId) {
        personalCenterService.moveFavorite(userId, id, body.getOrDefault("direction", "down"));
        return Result.ok("已排序", null);
    }
}
