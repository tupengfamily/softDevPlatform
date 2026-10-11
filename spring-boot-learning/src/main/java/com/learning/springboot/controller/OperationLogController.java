package com.learning.springboot.controller;

import com.learning.springboot.annotation.RequireAdmin;
import com.learning.springboot.common.Result;
import com.learning.springboot.dto.OperationLogQuery;
import com.learning.springboot.dto.PageResult;
import com.learning.springboot.entity.OperationLog;
import com.learning.springboot.service.OperationLogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作日志 API
 */
@RestController
@RequestMapping("/api/logs")
@RequireAdmin("操作日志仅管理员可操作")
public class OperationLogController {

    @Autowired private OperationLogService operationLogService;

    @GetMapping("/page")
    public ResponseEntity<?> page(OperationLogQuery query) {
        PageResult<OperationLog> p = operationLogService.pageQuery(query);
        return Result.ok(p);
    }

    @DeleteMapping("/clean")
    public ResponseEntity<?> clean(@RequestParam String before) {
        int n = operationLogService.cleanBefore(LocalDateTime.parse(before));
        return Result.ok("已清理 " + n + " 条日志", n);
    }
}