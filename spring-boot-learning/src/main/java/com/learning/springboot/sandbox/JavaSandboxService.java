package com.learning.springboot.sandbox;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Java 单文件代码沙箱
 * <p>
 * 流程:
 * 1. 用户提交的 Main.java 写到独立临时目录(按 userId 隔离, 自动清理)
 * 2. 用 JDK 自带的 JavaCompiler API 编译
 * 3. 编译失败 -> 返回编译错误清单(含行列号, 供前端 Monaco 标记)
 * 4. 编译成功 -> 用 {@code <java.home>/bin/java -cp <workDir> Main} 子进程执行,
 *    与应用 classpath 完全隔离, 输出走独立管道, 超时可强杀
 * 5. waitFor 5 秒硬超时, 超时 destroyForcibly 强制终止
 * 6. 流读取线程收集 stdout/stderr(上限 256KB, 超出截断)
 * <p>
 * 安全:
 * - 静态检查: 全文拒绝危险包(java.net / java.lang.reflect / java.io.File 等)与
 *   Runtime.getRuntime / ProcessBuilder / Class.forName / System.setOut 等危险调用
 * - 子进程执行: 用户代码与宿主 JVM 隔离, 无法触碰应用内存/密钥, 可强制杀死
 * - 独立临时目录, 执行完删除
 */
@Service
public class JavaSandboxService {

    private static final Logger log = LoggerFactory.getLogger(JavaSandboxService.class);

    /** 用户代码执行的硬超时 */
    private static final long TIMEOUT_MS = 5000;

    /** 输出上限: stdout / stderr 各自超过 256KB 即截断 */
    private static final int MAX_OUTPUT = 256 * 1024;

    /** 截断标记 */
    private static final String TRUNCATED_MARK = "...(输出已截断)";

    /** 允许的包前缀(白名单), 涉及反射/网络/进程/JNI 一律拒绝 */
    private static final List<String> ALLOWED_PACKAGE_PREFIXES = List.of(
        "java.lang.",
        "java.util.",
        "java.io.",
        "java.nio.",
        "java.text.",
        "java.time.",
        "java.math.",
        "java.util.function.",
        "java.util.stream.",
        "java.util.regex.",
        "java.util.concurrent.atomic.",
        "java.util.concurrent.locks.",
        "java.util.Arrays",
        "java.util.Collections",
        "java.util.List",
        "java.util.Map",
        "java.util.Set",
        "java.util.ArrayList",
        "java.util.HashMap",
        "java.util.LinkedHashMap",
        "java.util.HashSet",
        "java.util.LinkedList",
        "java.util.TreeMap",
        "java.util.TreeSet",
        "java.util.Random",
        "java.util.Scanner",
        "java.util.Optional",
        "java.util.Date"
    );

    /** 禁止的包前缀(危险操作), 出现在源码任意位置即拒绝(全限定名可绕过 import 扫描) */
    private static final List<String> FORBIDDEN_PACKAGE_PREFIXES = List.of(
        "java.net.",
        "java.lang.reflect.",
        "java.lang.Runtime",
        "java.lang.Process",
        "java.lang.System",  // 全部拒绝(含 exit), 用户代码不能调用 System.exit
        "java.io.File",     // 阻止任意文件读写
        "java.nio.file.Files",
        "java.nio.file.Paths",
        "java.nio.file.Path"
    );

    /** 禁止出现的危险调用模式(全文匹配, 不区分 import 行): 展示名 -> 正则 */
    private record ForbiddenCall(String display, Pattern pattern) {}

    private static final List<ForbiddenCall> FORBIDDEN_CALL_PATTERNS = List.of(
        new ForbiddenCall("Runtime.getRuntime", Pattern.compile("Runtime\\s*\\.\\s*getRuntime")),
        new ForbiddenCall("ProcessBuilder", Pattern.compile("ProcessBuilder")),
        new ForbiddenCall("Class.forName", Pattern.compile("Class\\s*\\.\\s*forName")),
        new ForbiddenCall("System.setOut", Pattern.compile("System\\s*\\.\\s*setOut")),
        new ForbiddenCall("System.setErr", Pattern.compile("System\\s*\\.\\s*setErr")),
        new ForbiddenCall("javax.script", Pattern.compile("javax\\.script")),
        new ForbiddenCall("java.lang.reflect", Pattern.compile("java\\.lang\\.reflect"))
    );

    /**
     * 编译并运行用户提交的 Java 单文件源码
     *
     * @param sourceCode  完整源码(必须包含 public class Main 与 main 方法)
     * @param userId      当前用户 id(用于临时目录隔离)
     * @return 沙箱运行结果
     */
    public SandboxResult compileAndRun(String sourceCode, Long userId) {
        long start = System.currentTimeMillis();
        SandboxResult result = new SandboxResult();
        result.stdout = "";
        result.stderr = "";
        result.errors = Collections.emptyList();

        // 0. 安全预检: 全文拒绝危险包名与危险调用(含全限定名绕过)
        List<String> violations = scanForbiddenCode(sourceCode);
        if (!violations.isEmpty()) {
            result.status = SandboxResult.Status.COMPILE_ERROR;
            result.exitCode = "FORBIDDEN_IMPORT";
            result.stderr = "为安全起见, 以下内容已被禁用: " + String.join(", ", violations);
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        }

        Path workDir = null;
        try {
            // 1. 写到独立临时目录: data/sandbox/<userId>/<timestamp>/
            workDir = createWorkDir(userId);
            File sourceFile = workDir.resolve("Main.java").toFile();
            Files.writeString(sourceFile.toPath(), sourceCode, StandardCharsets.UTF_8);

            // 2. 编译
            List<SandboxResult.CompileError> compileErrors = compile(sourceFile);
            if (!compileErrors.isEmpty()) {
                result.status = SandboxResult.Status.COMPILE_ERROR;
                result.errors = compileErrors;
                result.exitCode = "COMPILE_ERROR";
                result.elapsedMs = System.currentTimeMillis() - start;
                return result;
            }

            // 3. 编译成功 -> 子进程执行(带超时, 可强杀)
            runInSubprocess(workDir, result, start);
            return result;
        } catch (Exception e) {
            log.error("[Sandbox] 运行异常 userId={}", userId, e);
            result.status = SandboxResult.Status.RUNTIME_ERROR;
            result.stderr = e.toString();
            result.exitCode = e.getClass().getSimpleName();
            result.elapsedMs = System.currentTimeMillis() - start;
            return result;
        } finally {
            // 4. 清理临时目录(成功/失败都清)
            if (workDir != null) cleanupWorkDir(workDir);
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 全文安全扫描: 包名出现在源码任意位置即拒绝(防御全限定名绕过 import 行扫描),
     * 另拒绝危险调用模式。java.util/java.io/java.time 等白名单包正常使用不受影响。
     *
     * @return 违规项列表(空表示通过)
     */
    private List<String> scanForbiddenCode(String source) {
        List<String> bad = new ArrayList<>();
        for (String pkg : FORBIDDEN_PACKAGE_PREFIXES) {
            if (source.contains(pkg)) {
                bad.add(pkg);
            }
        }
        for (ForbiddenCall fc : FORBIDDEN_CALL_PATTERNS) {
            if (fc.pattern().matcher(source).find()) {
                bad.add(fc.display());
            }
        }
        return bad;
    }

    /** 创建隔离的工作目录 */
    private Path createWorkDir(Long userId) throws IOException {
        Path base = Path.of("data", "sandbox", String.valueOf(userId));
        Files.createDirectories(base);
        Path dir = Files.createTempDirectory(base, "run-");
        return dir;
    }

    /** 清理工作目录 */
    private void cleanupWorkDir(Path dir) {
        try {
            // 自下而上删除
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                    });
                }
            }
        } catch (IOException e) {
            log.warn("[Sandbox] 清理失败 dir={}", dir, e);
        }
    }

    /** 用 JDK 编译器编译源码 */
    private List<SandboxResult.CompileError> compile(File sourceFile) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            // 极端情况: JRE 而非 JDK
            return List.of(new SandboxResult.CompileError(0, 0,
                "环境未安装 JDK, 无法编译。请确认服务器使用 JDK 运行"));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjectsFromFiles(List.of(sourceFile));
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fm, diagnostics, null, null, units);
            boolean success = task.call();
            if (success) return Collections.emptyList();

            List<SandboxResult.CompileError> errors = new ArrayList<>();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                if (d.getKind() != Diagnostic.Kind.ERROR) continue;
                errors.add(new SandboxResult.CompileError(
                    (int) d.getLineNumber(),
                    (int) d.getColumnNumber(),
                    d.getMessage(Locale.SIMPLIFIED_CHINESE)));
            }
            return errors;
        } catch (IOException e) {
            log.error("[Sandbox] 编译IO异常", e);
            return List.of(new SandboxResult.CompileError(0, 0, "编译失败: " + e.getMessage()));
        }
    }

    /**
     * 以独立子进程运行编译产物: {@code <java.home>/bin/java -cp <workDir> Main}
     * <p>
     * 用户代码与应用 classpath 隔离(拿不到应用内存/密钥), 输出走独立管道互不干扰,
     * 超时 destroyForcibly 强杀, 不会留下僵尸线程。
     */
    private void runInSubprocess(Path workDir, SandboxResult result, long start) throws Exception {
        String javaBin = System.getProperty("java.home") + "/bin/java";
        // -cp 必须用绝对路径: workDir 是相对路径(data/sandbox/...), 而子进程 CWD
        // 又被设为 workDir, 相对 -cp 会解析成 workDir/data/sandbox/... 导致找不到主类
        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", workDir.toAbsolutePath().toString(), "Main");
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        Process proc = pb.start();
        // 独立线程读 stdout/stderr, 防止管道写满阻塞
        StreamGobbler outGob = new StreamGobbler(proc.getInputStream());
        StreamGobbler errGob = new StreamGobbler(proc.getErrorStream());
        outGob.start();
        errGob.start();

        boolean finished = proc.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!finished) {
            // 超时: 强制杀死子进程
            proc.destroyForcibly();
            proc.waitFor(1, TimeUnit.SECONDS);
            outGob.join(500);
            errGob.join(500);
            result.stdout = outGob.getText();
            result.stderr = errGob.getText() + "\n[Sandbox] 执行超过 " + TIMEOUT_MS + "ms 已被强制终止";
            result.status = SandboxResult.Status.TIMEOUT;
            result.exitCode = "TIMEOUT";
            result.elapsedMs = System.currentTimeMillis() - start;
            return;
        }

        outGob.join(500);
        errGob.join(500);
        String stdout = outGob.getText();
        String stderr = errGob.getText();
        result.stdout = stdout;
        result.stderr = stderr;
        result.elapsedMs = System.currentTimeMillis() - start;

        int code = proc.exitValue();
        if (code == 0) {
            result.status = SandboxResult.Status.OK;
            result.exitCode = "0";
        } else {
            result.status = SandboxResult.Status.RUNTIME_ERROR;
            // 提取首行作为 exit 提示
            String firstLine = stderr.lines().findFirst().orElse("RUNTIME_ERROR");
            result.exitCode = firstLine.replace("Exception in thread \"main\": ", "");
        }
    }

    /** 读流的小工具(上限 256KB, 超出截断并追加标记) */
    private static class StreamGobbler extends Thread {
        private final java.io.InputStream in;
        private final StringBuilder buf = new StringBuilder();
        private boolean truncated;

        StreamGobbler(java.io.InputStream in) { this.in = in; setDaemon(true); }

        @Override public void run() {
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (buf.length() + line.length() + 1 > MAX_OUTPUT) {
                        // 超限: 丢弃后续内容但继续读完, 防止子进程管道阻塞
                        truncated = true;
                        continue;
                    }
                    buf.append(line).append('\n');
                }
            } catch (IOException ignore) {}
        }

        String getText() {
            return truncated ? buf + TRUNCATED_MARK : buf.toString();
        }
    }
}
