package com.learning.springboot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Python 单文件代码沙箱(教学用)
 * <p>
 * 流程:
 * 1. 用户提交的 Python 源码写到独立临时目录(自动清理)
 * 2. 用本机 python3 以隔离模式(-I)执行, 5 秒硬超时, 超时强制杀进程
 * 3. 完整捕获 stdout / stderr, 语法错误(SyntaxError)归类为 COMPILE_ERROR 并解析行号
 * <p>
 * 安全:
 * - import 白名单: 仅允许教学常用的标准库(math/random/datetime/collections 等),
 *   os/subprocess/socket/shutil/ctypes/importlib 等高风险模块一律拒绝
 * - 拒绝 __import__/eval/exec/compile 等动态执行入口
 * - -I 隔离模式: 忽略 PYTHONPATH/PYTHONHOME 等环境变量与用户 site-packages
 * - 独立临时目录, 执行完清理
 */
@Service
public class PythonSandboxService {

    private static final Logger log = LoggerFactory.getLogger(PythonSandboxService.class);

    /** 用户代码执行的硬超时 */
    private static final long TIMEOUT_MS = 5000;

    /** 允许导入的标准库模块(白名单, 只放教学必需) */
    private static final List<String> ALLOWED_MODULES = List.of(
        "math", "random", "datetime", "time", "collections", "itertools",
        "functools", "re", "json", "csv", "string", "statistics", "decimal",
        "fractions", "typing", "heapq", "bisect", "copy", "operator",
        "textwrap", "enum", "dataclasses", "logging", "pathlib", "threading",
        "asyncio"
    );

    /** 禁止出现的动态执行入口 */
    private static final Pattern FORBIDDEN_CALLS =
        Pattern.compile("__(?:import|builtins|loader)__|\\beval\\s*\\(|\\bexec\\s*\\(|\\bcompile\\s*\\(|\\bglobals\\s*\\(");

    /** import/from 语句提取 */
    private static final Pattern IMPORT_STMT =
        Pattern.compile("(?m)^\\s*(?:import|from)\\s+([A-Za-z_][\\w.]*)");

    /** SyntaxError 行号提取:  File "xxx.py", line 3 */
    private static final Pattern SYNTAX_LINE = Pattern.compile("line (\\d+)");

    /** 解释器候选(优先环境变量, 次之 PATH 上的 python3) */
    private static final List<String> PYTHON_CANDIDATES = List.of(
        "python3", "python",
        "/opt/homebrew/bin/python3",
        "/usr/bin/python3",
        "/Users/tupengfamily/.workbuddy/binaries/python/versions/3.13.12/bin/python3"
    );

    private volatile String resolvedBin;

    /**
     * 执行 Python 源码
     *
     * @param source 用户代码
     * @param userId 用户 id(隔离临时目录)
     */
    public SandboxResult run(String source, Long userId) {
        SandboxResult result = new SandboxResult();
        result.errors = List.of();
        long start = System.currentTimeMillis();

        // 1. 安全检查
        String denied = checkSafety(source);
        if (denied != null) {
            result.status = SandboxResult.Status.COMPILE_ERROR;
            result.stderr = denied;
            result.exitCode = "FORBIDDEN";
            result.elapsedMs = System.currentTimeMillis() - start;
            result.errors = List.of(new SandboxResult.CompileError(0, 0, denied));
            return result;
        }

        // 2. 解释器不可用 -> 优雅报错
        String bin = resolvePython();
        if (bin == null) {
            result.status = SandboxResult.Status.RUNTIME_ERROR;
            result.stderr = "运行环境未安装 Python 3, 无法在线执行。请在本机安装 Python 3 后练习(代码与讲解不受影响)。";
            result.exitCode = "NO_PYTHON";
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        }

        // 3. 写临时文件并执行
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("pysandbox-" + (userId == null ? "guest" : userId) + "-");
            Path src = workDir.resolve("main.py");
            Files.writeString(src, source, StandardCharsets.UTF_8);

            ProcessBuilder pb = new ProcessBuilder(bin, "-I", "-X", "utf8", src.toString());
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);
            pb.environment().put("PYTHONNOUSERSITE", "1");
            pb.environment().remove("PYTHONPATH");
            pb.environment().remove("PYTHONHOME");

            Process proc = pb.start();
            // 超时兜底: 独立线程读 stdout/stderr 防止管道阻塞
            StreamGobbler outGob = new StreamGobbler(proc.getInputStream());
            StreamGobbler errGob = new StreamGobbler(proc.getErrorStream());
            outGob.start();
            errGob.start();

            boolean finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!finished) {
                proc.destroyForcibly();
                result.status = SandboxResult.Status.TIMEOUT;
                result.exitCode = "TIMEOUT";
                result.stderr = "执行超时(>5s), 已强制终止。检查是否存在死循环。";
            } else {
                outGob.join(500);
                errGob.join(500);
                String stdout = outGob.getText();
                String stderr = errGob.getText();
                result.stdout = stdout;
                result.stderr = stderr;
                int code = proc.exitValue();
                if (code == 0) {
                    result.status = SandboxResult.Status.OK;
                    result.exitCode = "0";
                } else if (stderr.contains("SyntaxError")) {
                    result.status = SandboxResult.Status.COMPILE_ERROR;
                    result.exitCode = "SYNTAX_ERROR";
                    Matcher m = SYNTAX_LINE.matcher(stderr);
                    int line = m.find() ? Integer.parseInt(m.group(1)) : 0;
                    result.errors = List.of(new SandboxResult.CompileError(line, 0, "语法错误(详见下方输出)"));
                } else {
                    result.status = SandboxResult.Status.RUNTIME_ERROR;
                    result.exitCode = String.valueOf(code);
                }
            }
        } catch (IOException e) {
            result.status = SandboxResult.Status.RUNTIME_ERROR;
            result.stderr = "Python 执行失败: " + e.getMessage();
            result.exitCode = "IO_ERROR";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            result.status = SandboxResult.Status.TIMEOUT;
            result.exitCode = "INTERRUPTED";
        } finally {
            if (workDir != null) {
                try (var paths = Files.walk(workDir)) {
                    paths.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignore) {}
                    });
                } catch (IOException ignore) {}
            }
        }
        result.elapsedMs = System.currentTimeMillis() - start;
        log.info("[PySandbox] user={} status={} elapsed={}ms", userId, result.status, result.elapsedMs);
        return result;
    }

    /** 安全检查: 不通过返回拒绝理由, 通过返回 null */
    private String checkSafety(String source) {
        Matcher m = IMPORT_STMT.matcher(source);
        while (m.find()) {
            String root = m.group(1).split("\\.")[0];
            if (!ALLOWED_MODULES.contains(root)) {
                return "不允许导入模块 '" + root + "'(教学沙箱白名单: " + String.join(", ", ALLOWED_MODULES) + ")";
            }
        }
        if (FORBIDDEN_CALLS.matcher(source).find()) {
            return "代码包含禁止的动态执行入口(eval/exec/compile/__import__ 等)";
        }
        return null;
    }

    /** 解析可用的 Python 解释器(惰性, 缓存结果) */
    private String resolvePython() {
        if (resolvedBin != null) return resolvedBin.isEmpty() ? null : resolvedBin;
        synchronized (this) {
            if (resolvedBin != null) return resolvedBin.isEmpty() ? null : resolvedBin;
            String env = System.getenv("PYTHON_BIN");
            java.util.ArrayList<String> cands = new java.util.ArrayList<>();
            if (env != null && !env.isBlank()) cands.add(env.trim());
            cands.addAll(PYTHON_CANDIDATES);
            for (String cand : cands) {
                try {
                    Process p = new ProcessBuilder(cand, "--version").start();
                    if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                        resolvedBin = cand;
                        log.info("[PySandbox] 使用解释器: {}", cand);
                        return cand;
                    }
                } catch (Exception ignore) {}
            }
            resolvedBin = "";
            return null;
        }
    }

    /** 读流的小工具 */
    private static class StreamGobbler extends Thread {
        private final java.io.InputStream in;
        private final StringBuilder buf = new StringBuilder();

        StreamGobbler(java.io.InputStream in) { this.in = in; setDaemon(true); }

        @Override public void run() {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) buf.append(line).append('\n');
            } catch (IOException ignore) {}
        }

        String getText() { return buf.toString(); }
    }
}
