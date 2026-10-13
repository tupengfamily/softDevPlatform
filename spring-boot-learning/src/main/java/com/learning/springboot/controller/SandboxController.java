package com.learning.springboot.controller;

import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.bootstrap.SandboxDataInitializer;
import com.learning.springboot.common.Result;
import com.learning.springboot.sandbox.JavaSandboxService;
import com.learning.springboot.sandbox.NodeSandboxService;
import com.learning.springboot.sandbox.PythonSandboxService;
import com.learning.springboot.sandbox.SqlSandboxService;
import com.learning.springboot.sandbox.SqlSandboxService.SqlResult;
import com.learning.springboot.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 教学代码 / SQL 沙箱 API
 * <p>
 * - /api/sandbox/run-java  提交 Java 单文件源码, 编译并执行, 返回 stdout/stderr/编译错误
 * - /api/sandbox/run-python 提交 Python 单文件源码, 解释执行(5 秒超时, import 白名单)
 * - /api/sandbox/run-sql   在独立教学库上执行 SELECT/INSERT/UPDATE/DELETE
 * - /api/sandbox/reset-sql 一键重置教学库(恢复 students/courses/scores 种子)
 * <p>
 * 全部接口需要登录; 调用均写入操作日志(便于观察与限制滥用);
 * run-* 执行接口额外做并发与频率限制, 防止沙箱资源被滥用。
 */
@RestController
@RequestMapping("/api/sandbox")
public class SandboxController {

    private static final Logger log = LoggerFactory.getLogger(SandboxController.class);

    /** 全局并发上限: 同时执行的沙箱任务数 */
    private static final Semaphore CONCURRENCY = new Semaphore(4);

    /** 每 IP 每分钟最多执行次数 */
    private static final int MAX_RUNS_PER_MINUTE = 20;

    /** 限流窗口长度(毫秒) */
    private static final long WINDOW_MS = 60_000L;

    /** 限流计数上限(防垃圾键撑爆内存) */
    private static final int MAX_RATE_KEYS = 5000;

    /** ip -> [窗口起始时间, 窗口内次数] */
    private static final Map<String, long[]> RATE_WINDOWS = new ConcurrentHashMap<>();

    @Autowired private JavaSandboxService javaSandbox;
    @Autowired private PythonSandboxService pythonSandbox;
    @Autowired private NodeSandboxService nodeSandbox;
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

        return guard(() -> {
            var result = javaSandbox.compileAndRun(code, userId);
            log.info("[Sandbox] user={} status={} elapsed={}ms", userId, result.status, result.elapsedMs);
            return Result.ok(result);
        });
    }

    /** 解释执行 Python 代码 */
    @PostMapping("/run-python")
    @OperationLog(module = "代码沙箱", action = "运行 Python")
    public ResponseEntity<?> runPython(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String code = body.getOrDefault("source", "");
        if (code.isBlank()) return Result.badRequest("代码不能为空");
        if (code.length() > 20_000) return Result.badRequest("代码长度不能超过 20000 字符");

        return guard(() -> {
            var result = pythonSandbox.run(code, userId);
            // 运行结果全部 200 返回, 前端按 status 展示(更友好)
            return Result.ok(result);
        });
    }

    /** 解释执行 Node.js 代码 */
    @PostMapping("/run-node")
    @OperationLog(module = "代码沙箱", action = "运行 Node.js")
    public ResponseEntity<?> runNode(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String code = body.getOrDefault("source", "");
        if (code.isBlank()) return Result.badRequest("代码不能为空");
        if (code.length() > 20_000) return Result.badRequest("代码长度不能超过 20000 字符");

        return guard(() -> {
            var result = nodeSandbox.run(code, userId);
            // 运行结果全部 200 返回, 前端按 status 展示(更友好)
            return Result.ok(result);
        });
    }

    /** 执行 SQL */
    @PostMapping("/run-sql")
    @OperationLog(module = "SQL 沙箱", action = "执行 SQL")
    public ResponseEntity<?> runSql(@RequestBody Map<String, String> body, @CurrentUser Long userId) {
        String sql = body.getOrDefault("sql", "");
        if (sql.isBlank()) return Result.badRequest("SQL 不能为空");
        if (sql.length() > 5000) return Result.badRequest("SQL 长度不能超过 5000 字符");

        return guard(() -> {
            SqlResult r = sqlSandbox.execute(sql);
            // 错误也返回 200, 由前端根据 result.error 字段判断(更友好)
            return Result.ok(r);
        });
    }

    /** 重置 SQL 教学库(删除并重建全部表) */
    @PostMapping("/reset-sql")
    @OperationLog(module = "SQL 沙箱", action = "重置教学库")
    public ResponseEntity<?> resetSql(@CurrentUser Long userId) {
        sandboxJdbc.execute("DROP TABLE IF EXISTS scores");
        sandboxJdbc.execute("DROP TABLE IF EXISTS students");
        sandboxJdbc.execute("DROP TABLE IF EXISTS courses");
        sandboxJdbc.execute("DROP TABLE IF EXISTS classes");
        // 重建 + 灌种子
        dataInitializer.init();
        Map<String, Object> info = new LinkedHashMap<>();
        Integer clCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM classes", Integer.class);
        Integer sCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM students", Integer.class);
        Integer cCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM courses", Integer.class);
        Integer scCount = sandboxJdbc.queryForObject("SELECT COUNT(*) FROM scores", Integer.class);
        info.put("classes", clCount == null ? 0 : clCount);
        info.put("students", sCount == null ? 0 : sCount);
        info.put("courses", cCount == null ? 0 : cCount);
        info.put("scores", scCount == null ? 0 : scCount);
        return Result.ok("教学库已重置", info);
    }

    /** 沙箱库概览(给前端展示表与行数) */
    @GetMapping("/sql-info")
    public ResponseEntity<?> sqlInfo() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("classes", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM classes", Integer.class));
        info.put("students", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM students", Integer.class));
        info.put("courses", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM courses", Integer.class));
        info.put("scores", sandboxJdbc.queryForObject("SELECT COUNT(*) FROM scores", Integer.class));
        return Result.ok(info);
    }

    /**
     * 沙箱执行统一护栏: 先过 IP 限流, 再占全局并发信号量, 执行完释放。
     * 限流/并发超限均返回 429。
     */
    private ResponseEntity<?> guard(Supplier<ResponseEntity<?>> action) {
        // 1. 每 IP 每分钟最多 MAX_RUNS_PER_MINUTE 次执行
        String ip = clientIp();
        long now = System.currentTimeMillis();
        long[] window = RATE_WINDOWS.compute(ip, (k, prev) -> {
            if (prev == null || now - prev[0] >= WINDOW_MS) {
                return new long[] { now, 1 };
            }
            prev[1]++;
            return prev;
        });
        if (window[1] > MAX_RUNS_PER_MINUTE) {
            log.warn("[Sandbox] IP {} 触发限流", ip);
            return Result.error(HttpStatus.TOO_MANY_REQUESTS, 429, "操作过于频繁,请稍后再试");
        }
        // 防垃圾键撑爆内存: 超上限先清理过期窗口
        if (RATE_WINDOWS.size() > MAX_RATE_KEYS) {
            RATE_WINDOWS.entrySet().removeIf(e -> now - e.getValue()[0] >= WINDOW_MS);
        }

        // 2. 全局并发上限(拿不到名额直接 429, 不排队)
        if (!CONCURRENCY.tryAcquire()) {
            return Result.error(HttpStatus.TOO_MANY_REQUESTS, 429, "沙箱繁忙,请稍后再试");
        }
        try {
            return action.get();
        } finally {
            CONCURRENCY.release();
        }
    }

    /** 取客户端 IP(限流维度) */
    private String clientIp() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            String ip = sra.getRequest().getRemoteAddr();
            return ip == null ? "" : ip;
        }
        return "";
    }
}