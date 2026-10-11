package com.learning.springboot.sandbox;

import java.util.List;

/**
 * Java 代码沙箱的输出结果(给前端的统一结构)
 * <p>
 * 三种结束状态:
 * - COMPILE_ERROR: 编译失败,errors 非空
 * - RUNTIME_ERROR: 编译成功但运行抛异常
 * - OK: 正常运行完成
 */
public class SandboxResult {

    public enum Status { OK, COMPILE_ERROR, RUNTIME_ERROR, TIMEOUT }

    public Status status;
    public String stdout;                  // System.out 全部输出
    public String stderr;                  // System.err + 异常栈
    public long elapsedMs;                 // 编译+运行总耗时
    public List<CompileError> errors;      // 编译错误(可能为空)
    public String exitCode;                // "0" / 异常类名 / "TIMEOUT"

    /** 单个编译错误(给 Monaco 标记用) */
    public static class CompileError {
        public int line;                   // 1-based
        public int column;                 // 1-based
        public String message;

        public CompileError(int line, int column, String message) {
            this.line = line;
            this.column = column;
            this.message = message;
        }
    }
}