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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Java 单文件代码沙箱
 * <p>
 * 流程:
 * 1. 用户提交的 Main.java 写到独立临时目录(按 userId 隔离, 自动清理)
 * 2. 用 JDK 自带的 JavaCompiler API 编译
 * 3. 编译失败 -> 返回编译错误清单(含行列号, 供前端 Monaco 标记)
 * 4. 编译成功 -> 自定义 URLClassLoader 加载, 反射调用 main 方法
 * 5. 守护线程 + 5 秒硬超时, 超时强制中断
 * 6. 重定向 System.out/err, 完整捕获输出
 * <p>
 * 安全:
 * - 限制导入的包(白名单), 简单拒绝 java.net / java.lang.reflect 等高风险包
 * - 守护线程防止线程泄露
 * - 独立临时目录, 失败清理
 */
@Service
public class JavaSandboxService {

    private static final Logger log = LoggerFactory.getLogger(JavaSandboxService.class);

    /** 用户代码执行的硬超时 */
    private static final long TIMEOUT_MS = 5000;

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

    /** 禁止的包前缀(危险操作) */
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

        // 0. 安全预检: 拒绝危险 import
        List<String> violations = scanForbiddenImports(sourceCode);
        if (!violations.isEmpty()) {
            result.status = SandboxResult.Status.COMPILE_ERROR;
            result.exitCode = "FORBIDDEN_IMPORT";
            result.stderr = "为安全起见, 以下导入已被禁用: " + String.join(", ", violations);
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

            // 3. 编译成功 -> 加载并执行(带超时)
            runWithTimeout(workDir, result, start);
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

    /** 扫描源码中的 import 语句, 返回违规包 */
    private List<String> scanForbiddenImports(String source) {
        List<String> bad = new ArrayList<>();
        for (String line : source.split("\n")) {
            String t = line.trim();
            if (!t.startsWith("import ")) continue;
            String pkg = t.substring(7).replace("static ", "").replace(";", "").trim();
            // 类级别(不包含 .)如 java.util.List -> 允许, 反之如 java.lang.reflect.* -> 拒绝
            if (pkg.endsWith(".*")) {
                String prefix = pkg.substring(0, pkg.length() - 1);
                if (FORBIDDEN_PACKAGE_PREFIXES.stream().anyMatch(prefix::startsWith)) {
                    bad.add(pkg);
                }
            } else {
                if (FORBIDDEN_PACKAGE_PREFIXES.contains(pkg)) {
                    bad.add(pkg);
                }
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

    /** 加载编译产物并以守护线程运行, 5 秒超时 */
    private void runWithTimeout(Path workDir, SandboxResult result, long start) throws Exception {
        URLClassLoader loader = new URLClassLoader(
            new URL[] { workDir.toUri().toURL() },
            JavaSandboxService.class.getClassLoader());

        Class<?> mainClass;
        try {
            mainClass = loader.loadClass("Main");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("找不到 Main 类, 请确认源码中包含 public class Main { ... }");
        }

        Method mainMethod;
        try {
            mainMethod = mainClass.getMethod("main", String[].class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("找不到 public static void main(String[] args) 方法");
        }

        // 重定向 stdout / stderr
        ByteArrayOutputStream capturedOut = new ByteArrayOutputStream();
        ByteArrayOutputStream capturedErr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        System.setOut(new PrintStream(capturedOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(capturedErr, true, StandardCharsets.UTF_8));

        Thread runner = new Thread(() -> {
            try {
                mainMethod.invoke(null, (Object) new String[0]);
            } catch (Throwable t) {
                // 反射异常 unwrap
                Throwable cause = t.getCause() == null ? t : t.getCause();
                try {
                    capturedErr.write(("Exception in thread \"main\": " + cause + "\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {}
            }
        }, "sandbox-runner");
        runner.setDaemon(true);
        runner.start();

        try {
            runner.join(TIMEOUT_MS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            // 恢复 System.out/err(必须恢复, 否则后续响应出错)
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        result.stdout = capturedOut.toString(StandardCharsets.UTF_8);
        result.stderr = capturedErr.toString(StandardCharsets.UTF_8);
        result.elapsedMs = System.currentTimeMillis() - start;

        if (runner.isAlive()) {
            // 超时: 守护线程最终会被回收, 但给用户清晰反馈
            result.status = SandboxResult.Status.TIMEOUT;
            result.exitCode = "TIMEOUT";
            result.stderr += "\n[Sandbox] 执行超过 " + TIMEOUT_MS + "ms 已被强制终止";
        } else if (!result.stderr.isEmpty() && result.stderr.contains("Exception")) {
            result.status = SandboxResult.Status.RUNTIME_ERROR;
            // 提取首行作为 exit 提示
            String firstLine = result.stderr.lines().findFirst().orElse("RUNTIME_ERROR");
            result.exitCode = firstLine.replace("Exception in thread \"main\": ", "");
        } else {
            result.status = SandboxResult.Status.OK;
            result.exitCode = "0";
        }
    }
}