package com.learning.springboot.exception;

import com.learning.springboot.common.Result;
import io.jsonwebtoken.JwtException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * 全局异常处理器
 * <p>
 * 把异常映射为统一的 {code, message, data, timestamp} 格式。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<?> handleBusiness(BusinessException e) {
        int code = e.getCode();
        HttpStatusCode status = switch (code) {
            case 401 -> HttpStatusCode.valueOf(401);
            case 403 -> HttpStatusCode.valueOf(403);
            case 404 -> HttpStatusCode.valueOf(404);
            case 409 -> HttpStatusCode.valueOf(409);
            case 429 -> HttpStatusCode.valueOf(429);
            default -> HttpStatusCode.valueOf(400);
        };
        log.warn("[Business] code={} msg={}", code, e.getMessage());
        return Result.error(status, code, e.getMessage());
    }

    /** 参数校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fe : e.getBindingResult().getFieldErrors()) {
            errors.put(fe.getField(), fe.getDefaultMessage());
        }
        return Result.badRequest("参数校验失败: " + errors);
    }

    /** 业务参数非法(统一 400,避免落入 500 兜底泄漏内部异常) */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("[IllegalArgument] {}", e.getMessage());
        return Result.badRequest(e.getMessage());
    }

    /** JWT 异常 */
    @ExceptionHandler(JwtException.class)
    public ResponseEntity<?> handleJwt(JwtException e) {
        log.warn("[JWT] {}", e.getMessage());
        return Result.unauthorized("Token 无效或已过期");
    }

    /** 数据完整性冲突(唯一键 / 外键) */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<?> handleDataIntegrity(DataIntegrityViolationException e) {
        String msg = e.getMostSpecificCause().getMessage();
        if (msg != null && msg.contains("UNIQUE")) {
            log.warn("[DB] 唯一约束冲突: {}", msg);
            return Result.conflict("数据已存在,违反唯一约束");
        }
        // 不向客户端返回内部异常细节, 统一固定文案, 详情进日志
        log.error("[DB] 数据完整性冲突", e);
        return Result.conflict("服务器内部错误");
    }

    /** 请求体格式错误(JSON 解析失败等) */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("[BadRequest] 请求体格式错误: {}", e.getMessage());
        return Result.badRequest("请求体格式错误");
    }

    /** 资源不存在 */
    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<?> handleNoSuchElement(NoSuchElementException e) {
        log.warn("[NotFound] {}", e.getMessage());
        return Result.error(HttpStatusCode.valueOf(404), 404, "资源不存在");
    }

    /** 参数类型转换错误 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return Result.badRequest("参数类型错误: " + e.getName());
    }

    /** 上传文件超过大小限制(multipart) */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<?> handleMaxUpload(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        log.warn("[Upload] 文件超过大小限制: {}", e.getMessage());
        return Result.badRequest("文件过大, 最大支持 10MB");
    }

    // ==========================================================
    //  🔴 框架级 HTTP 异常(2026-10-26)
    //
    //  背景: Spring MVC 对「请求本身不合法」的异常会直接抛到容器层,
    //  不经过任何 @ExceptionHandler, 于是落进兜底的 handleAll → 全部返回 500。
    //  实测被误报成 500 的三种(全部是**客户端错**, 不是服务端错):
    //    1. NoResourceFoundException        静态资源不存在   → 应 404
    //    2. HttpRequestMethodNotSupported   方法不允许       → 应 405
    //    3. HttpMediaTypeNotSupported       媒体类型不支持   → 应 415
    //
    //  为什么必须单独处理: 500 会让前端弹出「服务器内部错误」并进错误上报,
    //  把「客户端打错 URL」这类日常噪声伪装成线上故障, 污染告警口径。
    // ==========================================================

    /** 静态资源不存在(404 语义, 不是 500) */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<?> handleNoResourceFound(NoResourceFoundException e) {
        // 用 info 而不是 warn/error: 这是客户端打错路径的日常噪声, 不该惊动告警
        log.info("[NotFound] 静态资源不存在: {}", e.getResourcePath());
        return Result.error(HttpStatusCode.valueOf(404), 404, "资源不存在");
    }

    /** 请求方法不允许(405 语义) */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<?> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.info("[MethodNotAllowed] {} 不支持的方法: {}", e.getMethod(), String.join("/", e.getSupportedMethods() == null ? new String[0] : e.getSupportedMethods()));
        return Result.error(HttpStatusCode.valueOf(405), 405, "请求方法不支持");
    }

    /** 媒体类型不支持(415 语义) */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<?> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.info("[UnsupportedMediaType] {}", e.getContentType());
        return Result.error(HttpStatusCode.valueOf(415), 415, "不支持的请求内容类型");
    }

    /** 兜底 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleAll(Exception e) {
        // 不向客户端返回内部异常细节, 统一固定文案, 详情进日志
        log.error("[ERROR] 未处理异常", e);
        return Result.error(HttpStatusCode.valueOf(500), 500, "服务器内部错误");
    }
}