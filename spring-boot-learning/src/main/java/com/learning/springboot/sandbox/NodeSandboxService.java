package com.learning.springboot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Node.js 单文件代码沙箱(教学用)
 * <p>
 * 流程:
 * 1. 用户提交的 JS 源码写到独立临时目录(自动清理);含 import/export 语句时以 .mjs(ESM)执行,否则 CommonJS
 * 2. 用本机 node 以权限模型执行(--permission):文件系统只读放开、写入仅限临时目录,
 *    child_process/worker_threads 等被运行时拒绝,5 秒硬超时强制杀进程
 * 3. 完整捕获 stdout / stderr,语法错误(SyntaxError)归类为 COMPILE_ERROR 并解析行号
 * <p>
 * 安全(与 Python 沙箱同思路,双层防护):
 * - 模块白名单: 仅允许教学常用内置模块(path/os/fs/events/stream/buffer/url/http 等),
 *   child_process/cluster/worker_threads/vm/inspector 等一律拒绝
 * - 拒绝 eval/Function 构造器/process.binding 等动态执行入口
 * - Node 权限模型兜底: fs 写入限定临时目录、子进程与 worker 运行时拒绝
 */
@Service
public class NodeSandboxService {

    private static final Logger log = LoggerFactory.getLogger(NodeSandboxService.class);

    /** 用户代码执行的硬超时 */
    private static final long TIMEOUT_MS = 5000;

    /** 允许 require/import 的内置模块(白名单, 只放教学必需) */
    private static final List<String> ALLOWED_MODULES = List.of(
        "fs", "path", "os", "util", "events", "stream", "buffer", "url",
        "querystring", "assert", "crypto", "zlib", "http", "https", "net",
        "timers", "string_decoder", "punycode", "process"
    );

    /** 明确危险的内置模块(硬拒绝; 权限模型亦会拦截) */
    private static final List<String> DANGEROUS_MODULES = List.of(
        "child_process", "cluster", "worker_threads", "vm", "v8", "inspector",
        "repl", "module", "trace_events", "perf_hooks"
    );

    /** 禁止出现的动态执行入口 */
    private static final Pattern FORBIDDEN_CALLS =
        Pattern.compile("\\beval\\s*\\(|\\bnew\\s+Function\\s*\\(|process\\.binding\\s*\\(|require\\s*\\(\\s*['\"](?:child_process|cluster|worker_threads|vm|v8|inspector|repl)['\"]");

    /** require('xxx') / require("xxx") 模块提取 */
    private static final Pattern REQUIRE_STMT =
        Pattern.compile("require\\s*\\(\\s*['\"]([\\w@/\\-.]+)['\"]");

    /** ESM: import ... from 'xxx' / import 'xxx' / export ... 检测 */
    private static final Pattern ESM_IMPORT =
        Pattern.compile("(?m)^\\s*import\\s+(?:[\\w${},*\\s]+\\s+from\\s+)?['\"]([\\w@/\\-.]+)['\"]");
    private static final Pattern ESM_SYNTAX =
        Pattern.compile("(?m)^\\s*(?:import\\s|export\\s|import\\{)");

    /** 语法错误行号提取:  /tmp/.../main.js:2  (Node 报错首行格式) */
    private static final Pattern SYNTAX_LINE = Pattern.compile("main\\.m?js:(\\d+)");

    /** node 可执行候选(优先环境变量与真实二进制绝对路径; PATH 上的 node 可能是代理 shim, 放最后) */
    private static final List<String> NODE_CANDIDATES = List.of(
        "/Users/tupengfamily/.workbuddy/binaries/node/versions/22.22.2-6/bin/node",
        "/opt/homebrew/bin/node",
        "/usr/local/bin/node",
        "node"
    );

    private volatile String resolvedBin;

    /**
     * 执行 Node.js 源码
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

        // 2. node 不可用 -> 优雅报错
        String bin = resolveNode();
        if (bin == null) {
            result.status = SandboxResult.Status.RUNTIME_ERROR;
            result.stderr = "运行环境未安装 Node.js, 无法在线执行。请在本机安装 Node.js 后练习(代码与讲解不受影响)。";
            result.exitCode = "NO_NODE";
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        }

        // 3. 写临时文件并执行
        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("nodesandbox-" + (userId == null ? "guest" : userId) + "-");
            // 含 import/export 视为 ESM, 用 .mjs 后缀; 否则 CommonJS .js
            boolean esm = ESM_SYNTAX.matcher(source).find();
            Path src = workDir.resolve(esm ? "main.mjs" : "main.js");
            Files.writeString(src, source, StandardCharsets.UTF_8);

            // 写入放行需同时给逻辑路径与 realpath(macOS /var -> /private/var 符号链接会导致相对路径写被拒)
            List<String> cmd = new ArrayList<>(List.of(
                bin, "--no-warnings", "--permission",
                "--allow-fs-read=*",
                "--allow-fs-write=" + workDir
            ));
            try {
                String real = workDir.toRealPath().toString();
                if (!real.equals(workDir.toString())) cmd.add("--allow-fs-write=" + real);
            } catch (IOException ignore) {}
            cmd.add(src.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(false);
            // 清理 NODE_OPTIONS 等注入(环境可能带 fs 代理 shim, 干扰沙箱执行)
            pb.environment().remove("NODE_OPTIONS");
            pb.environment().remove("NODE_PATH");

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
                result.stderr = "执行超时(>5s), 已强制终止。检查是否存在死循环或未结束的服务器/定时器。";
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
            result.stderr = "Node.js 执行失败: " + e.getMessage();
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
        log.info("[NodeSandbox] user={} status={} elapsed={}ms", userId, result.status, result.elapsedMs);
        return result;
    }

    /** 安全检查: 不通过返回拒绝理由, 通过返回 null */
    private String checkSafety(String source) {
        // require('xxx') 模块白名单
        Matcher m = REQUIRE_STMT.matcher(source);
        while (m.find()) {
            String denied = checkModule(m.group(1));
            if (denied != null) return denied;
        }
        // ESM import 模块白名单
        Matcher im = ESM_IMPORT.matcher(source);
        while (im.find()) {
            String denied = checkModule(im.group(1));
            if (denied != null) return denied;
        }
        if (FORBIDDEN_CALLS.matcher(source).find()) {
            return "代码包含禁止的动态执行入口(eval/new Function/process.binding/child_process 等)";
        }
        return null;
    }

    /** 模块名合规检查(node: 前缀等价内置模块) */
    private String checkModule(String raw) {
        String name = raw.startsWith("node:") ? raw.substring(5) : raw;
        if (ALLOWED_MODULES.contains(name)) return null;
        if (DANGEROUS_MODULES.contains(name)) {
            return "不允许导入危险模块 '" + raw + "'(教学沙箱已禁用进程/虚拟机相关能力)";
        }
        // 其余视为 npm 第三方包或相对路径: 给出本地练习指引而非安全口吻
        return "沙箱暂不支持模块 '" + raw + "'。内置模块请使用教学白名单(" + String.join(", ", ALLOWED_MODULES)
            + ");express/mongoose 等第三方包请在本地项目 npm install 后练习。";
    }

    /** 解析可用的 node 可执行(惰性, 缓存结果) */
    private String resolveNode() {
        if (resolvedBin != null) return resolvedBin.isEmpty() ? null : resolvedBin;
        synchronized (this) {
            if (resolvedBin != null) return resolvedBin.isEmpty() ? null : resolvedBin;
            String env = System.getenv("NODE_BIN");
            ArrayList<String> cands = new ArrayList<>();
            if (env != null && !env.isBlank()) cands.add(env.trim());
            cands.addAll(NODE_CANDIDATES);
            for (String cand : cands) {
                try {
                    Process p = new ProcessBuilder(cand, "--version").start();
                    if (p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0) {
                        resolvedBin = cand;
                        log.info("[NodeSandbox] 使用运行时: {}", cand);
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
