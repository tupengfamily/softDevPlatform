package com.learning.springboot.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.annotation.OperationLog;
import com.learning.springboot.security.JwtAuthFilter;
import com.learning.springboot.service.OperationLogService;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;

/**
 * 操作日志切面
 * <p>
 * 拦截标注了 @OperationLog 的 Controller 方法:
 * 1. 记录用户/IP/URL/方法/参数
 * 2. 调用目标方法
 * 3. 成功: status=1;失败: status=0 + 错误消息
 * 4. 通过 OperationLogService.save 异步落库(REQUIRES_NEW)
 */
@Aspect
@Component
public class OperationLogAspect {

    private static final Logger log = LoggerFactory.getLogger(OperationLogAspect.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> SENSITIVE_KEYS = Set.of(
        "password", "oldPassword", "newPassword",
        "token", "accessToken", "refreshToken", "expiresIn");

    @Autowired private OperationLogService operationLogService;

    @Around("@annotation(operationLog)")
    public Object around(ProceedingJoinPoint pjp, OperationLog operationLog) throws Throwable {
        long start = System.currentTimeMillis();
        com.learning.springboot.entity.OperationLog record = new com.learning.springboot.entity.OperationLog();
        record.setModule(operationLog.module());
        record.setAction(operationLog.action());

        // 解析请求
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            record.setIp(req.getRemoteAddr());
            record.setRequestUrl(req.getRequestURI());
            record.setRequestMethod(req.getMethod());
            record.setUserAgent(truncate(req.getHeader("User-Agent"), 250));
            record.setUsername((String) req.getAttribute(JwtAuthFilter.ATTR_USERNAME));
            Object uid = req.getAttribute(JwtAuthFilter.ATTR_USER_ID);
            if (uid instanceof Long n) record.setUserId(n);

            // method field removed; method info goes into requestUrl/action
        }

        if (operationLog.recordParams()) {
            record.setRequestParams(truncate(serializeArgs(pjp.getArgs()), 2000));
        }

        Throwable error = null;
        Object result = null;
        try {
            result = pjp.proceed();
            record.setStatus(1);
            record.setResponseResult(truncate(serializeResult(result), 2000));
            return result;
        } catch (Throwable t) {
            error = t;
            record.setStatus(0);
            record.setErrorMsg(truncate(t.getMessage(), 2000));
            throw t;
        } finally {
            record.setCostMs(System.currentTimeMillis() - start);
            try {
                operationLogService.save(record);
            } catch (Exception logEx) {
                // 日志失败不应影响主业务
                OperationLogAspect.log.warn("[AOP] 写入操作日志失败: {}", logEx.getMessage());
            }
        }
    }

    private String serializeArgs(Object[] args) {
        if (args == null || args.length == 0) return "";
        try {
            // 过滤 HttpServletRequest / HttpServletResponse(是请求/响应对象则排除, 两者都不才是保留条件)
            Object[] filtered = Arrays.stream(args)
                .filter(a -> a == null
                    || (!(a instanceof jakarta.servlet.ServletRequest)
                        && !(a instanceof jakarta.servlet.ServletResponse)))
                .toArray();
            return MAPPER.writeValueAsString(filtered);
        } catch (Exception e) {
            return "serialize-error: " + e.getMessage();
        }
    }

    private String serializeResult(Object result) {
        if (result == null) return "";
        try {
            String json = MAPPER.writeValueAsString(result);
            // 简单过滤敏感字段(字符串与数值两种形态都脱敏, 如 LoginResponse 的 token/expiresIn)
            for (String key : SENSITIVE_KEYS) {
                json = json.replaceAll("(?i)\"" + key + "\"\\s*:\\s*(\"[^\"]*\"|-?\\d+(?:\\.\\d+)?)",
                    "\"" + key + "\":\"***\"");
            }
            return json;
        } catch (Exception e) {
            return "serialize-error";
        }
    }

    private String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}