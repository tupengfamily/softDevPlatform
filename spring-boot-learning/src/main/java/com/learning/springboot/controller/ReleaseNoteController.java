package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.ReleaseNoteDTO;
import com.learning.springboot.entity.ReleaseNote;
import com.learning.springboot.service.ReleaseNoteService;
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

/**
 * 版本更新公告 API
 * <p>
 * - GET /api/releases          更新日志(所有登录用户)
 * - GET /api/releases/latest   最新公告(新版本弹窗)
 * - POST/PUT/DELETE            发布/编辑/删除公告(仅管理员)
 */
@RestController
@RequestMapping("/api/releases")
public class ReleaseNoteController {

    @Autowired private ReleaseNoteService releaseNoteService;

    /** 更新日志列表(最新在前) */
    @GetMapping
    public ResponseEntity<?> list() {
        List<ReleaseNote> data = releaseNoteService.list();
        return Result.ok(data);
    }

    /** 最新公告(前端对比 localStorage 已读版本,决定是否弹窗) */
    @GetMapping("/latest")
    public ResponseEntity<?> latest() {
        ReleaseNote data = releaseNoteService.latest();
        return Result.ok(data);
    }

    /** 发布新版本公告 */
    @PostMapping
    @RequireAdmin
    @OperationLog(module = "更新公告", action = "发布新版本")
    public ResponseEntity<?> create(@RequestBody ReleaseNoteDTO dto) {
        ReleaseNote data = releaseNoteService.create(dto);
        return Result.created("版本 " + data.getVersion() + " 发布成功", data);
    }

    /** 编辑公告 */
    @PutMapping("/{id}")
    @RequireAdmin
    @OperationLog(module = "更新公告", action = "编辑公告")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ReleaseNoteDTO dto) {
        ReleaseNote data = releaseNoteService.update(id, dto);
        return Result.ok("公告已更新", data);
    }

    /** 删除公告 */
    @DeleteMapping("/{id}")
    @RequireAdmin
    @OperationLog(module = "更新公告", action = "删除公告")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        releaseNoteService.delete(id);
        return Result.ok("公告已删除", null);
    }
}
