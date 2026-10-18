package com.learning.springboot.aop;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 日志切面(AOP)
 * <p>
 * 仅用 @Around 计算方法耗时,避免 @Before + @Around 双通知对同一 service 包造成重复日志。
 */
@Aspect
@Component
public class LoggingAspect {

    private static final Logger log = LoggerFactory.getLogger(LoggingAspect.class);

    /**
     * 环绕通知:计算方法执行耗时;异常也记录耗时与原因
     */
    @Around("execution(* com.learning.springboot.service..*.*(..))")
    public Object aroundMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        String methodName = joinPoint.getSignature().getName();

        try {
            Object result = joinPoint.proceed(); // 执行目标方法
            long elapsed = System.currentTimeMillis() - start;
            log.info("[AOP] {} 执行完成, 耗时 {} ms", methodName, elapsed);
            return result;
        } catch (Throwable e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[AOP] {} 执行失败, 耗时 {} ms, 异常: {}", methodName, elapsed, e.getMessage());
            throw e;
        }
    }

    /**
     * 后置通知:controller 入口,debug 级别,默认不上日志
     */
    @After("execution(* com.learning.springboot.controller..*.*(..))")
    public void afterController(JoinPoint joinPoint) {
        log.debug("[AOP] Controller 方法执行结束: {}",
                joinPoint.getSignature().getName());
    }
}