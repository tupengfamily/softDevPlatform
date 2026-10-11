package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.bootstrap.SandboxDataInitializer;
import com.learning.springboot.common.Result;
import com.learning.springboot.sandbox.JavaSandboxService;
import com.learning.springboot.sandbox.SqlSandboxService;
import com.learning.springboot.sandbox.SqlSandboxService.SqlResult;
import com.learning.springboot.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 教学代码 / SQL 沙箱 API
 * <p>
 * - /api/sandbox/run-java  提交 Java 单文件源码, 编译并执行, 返回 stdout/stderr/编译错误
 * - /api/sandbox/run-sql   在独立教学库上执行 SELECT/INSERT/UPDATE/DELETE
 * - /api/sandbox/reset-sql 一键重置教学库(恢复 students/courses/scores 种子)
 * <p>
 * 全部接口需要登录; 调用均写入操作日志(便于观察与限制滥用)
 */
@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {

    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);

    @Autowired private JavaSandboxService javaSandbox;
    @Autowired private SqlSandboxService sqlSandbox;
    @Autowired private SandboxDataInitializer dataInitializer;
    @Autowired @Qualifier("sandboxJdbcTemplate") private JdbcTemplate sandboxJdbc;

    /** 编译并执行 Java 代码 */
    @PostMapping("/run-java")
    @OperationLog(module = "代码沙箱", action = "运行 Java")
    public ResponseEntity<?> runJava(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String code = body.getOrDefault("source", "");
        if (code.isBlank()) return Result.badRequest("代码不能为空");
        if (code.length() > 20_000) return Result.badRequest("代码长度不能超过 20000 字符");

        var result = javaSandbox.compileAndRun(code, userId);
        log.info("[Sandbox] user={} status={} elapsed={}ms", userId, result.status, result.elapsedMs);
        return Result.ok(result);
    }

    /** 执行 SQL */
    @PostMapping("/run-sql")
    @OperationLog(module = "SQL 沙箱", action = "执行 SQL")
    public ResponseEntity<?> runSql(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String sql = body.getOrDefault("sql", "");
        if (sql.isBlank()) return Result.badRequest("SQL 不能为空");
        if (sql.length() > 5000) return Result.badRequest("SQL 长度不能超过 5000 字符");

        SqlResult r = sqlSandbox.execute(sql);
        // 错误也返回 200, 由前端根据 result.error 字段判断(更友好)
        return Result.ok(r);
    }

    /** 重置 SQL 教学库(删除并重建全部表) */
    @PostMapping("/reset-sql")
    @OperationLog(module = "SQL 沙箱", action = "重置教学库")
    public ResponseEntity<?> resetSql(@CurrentUser Long userId) {
        sandboxJdbc.execute("DROP TABLE IF EXISTS scores");
        sandboxJdbc.execute("DROP TABLE IF EXISTS students");
        sandboxJdbc.execute("DROP TABLE IF EXISTS courses");
        // 重建 + 灌种子
        dataInitializer.init();
        Map<String, Object> info = new LinkedHashMap<>();
        Integer sCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM students", Integer.class);
        Integer cCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM courses", Integer.class);
        Integer scCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM scores", Integer.class);
        info.put("students", sCount == null ? 0 : sCount);
        info.put("courses", cCount == null ? 0 : cCount);
        info.put("scores", scCount == null ? 0 : scCount);
        return Result.ok("教学库已重置", info);
    }

    /** 沙箱库概览(给前端展示表与行数) */
    @GetMapping("/sql-info")
    public ResponseEntity<?> sqlInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("students", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM students", Integer.class));
        info.put("courses", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM courses", Integer.class));
        info.put("scores", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM scores", Integer.class));
        return Result.ok(info);
    }
}