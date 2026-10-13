package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.common.Result;
import com.learning.springboot.entity.UserDataRecord;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.PersonalCenterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 个人数据导入导出 API
 * <p>
 * 导出: 收藏 + Wiki(目录/文章) + 学习进度,生成 JSON 并落一条导出记录(可下载);
 * 导入: 合并模式(去重),落一条导入记录(可查看)。
 */
@RestController
@RequestMapping("/api/user-data")
public class UserDataController {

    @Autowired private PersonalCenterService personalCenterService;

    /** 导出我的全部个人数据(同时生成导出记录,返回内容供下载) */
    @PostMapping("/export")
    @OperationLog(module = "个人中心", action = "导出个人数据")
    public ResponseEntity<?> export(@CurrentUser Long userId, @CurrentUser("username") String username) {
        Map<String, Object> data = personalCenterService.createExportRecord(userId, username);
        return Result.ok("导出成功", data);
    }

    /** 导入个人数据(合并,同时生成导入记录) */
    @PostMapping("/import")
    @OperationLog(module = "个人中心", action = "导入个人数据")
    public ResponseEntity<?> importData(@RequestBody Map<String, Object> payload,
                                        @CurrentUser Long userId, @CurrentUser("username") String username) {
        Map<String, Object> data = personalCenterService.createImportRecord(userId, username, payload);
        return Result.ok("导入完成", data);
    }

    /** 导入导出记录列表(不含内容快照) */
    @GetMapping("/records")
    public ResponseEntity<?> records(@CurrentUser Long userId) {
        List<Map<String, Object>> data = personalCenterService.listRecords(userId);
        return Result.ok(data);
    }

    /** 记录详情(含内容,查看/下载用) */
    @GetMapping("/records/{id}")
    public ResponseEntity<?> recordDetail(@PathVariable Long id, @CurrentUser Long userId) {
        UserDataRecord r = personalCenterService.getRecord(userId, id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", r.getId());
        data.put("type", r.getType());
        data.put("summary", r.getSummary());
        data.put("sizeBytes", r.getSizeBytes());
        data.put("createTime", r.getCreateTime());
        data.put("content", r.getContent());
        return Result.ok(data);
    }
}
