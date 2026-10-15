package com.learning.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 运营管理角色专属接口标记
 *
 * <p>标注在 Controller 类或方法上, 由 {@code AdminInterceptor} 在进入控制器前校验:
 * 当前用户角色必须包含 ADMIN 或 OPERATION, 否则返回 403。
 *
 * <p>权限模型:
 * <ul>
 *   <li>ADMIN: 系统管理 + 运营管理(全权)</li>
 *   <li>OPERATION: 仅运营管理</li>
 * </ul>
 *
 * <p>例: 运营概览/页面清单/用户旅程/错误日志/性能监控/客户端画像等
 * 仅 OPERATION 角色即可访问。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireOperation {

    /** 拒绝访问时返回给前端的提示 */
    String value() default "该功能仅管理员或运营管理员可访问";
}