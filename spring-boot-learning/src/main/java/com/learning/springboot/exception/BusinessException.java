package com.learning.springboot.exception;

/**
 * 业务异常
 * <p>
 * 与 java-learning 项目的 CustomException 类似,但作为统一异常基类。
 * <p>
 * 默认 code 为 400 (业务校验错 = 客户端错, 应归 4xx)。
 * 真正的服务器内部异常应该用其他 RuntimeException, 由 GlobalExceptionHandler 兜底 500。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    /** 默认 400: 业务校验错(参数非法 / 资源不存在 / 状态不允许 / 权限不足等) */
    public BusinessException(String message) {
        super(message);
        this.code = 400;
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}