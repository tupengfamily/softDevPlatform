package com.learning.springboot.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 管理员专属接口标记
 * <p>
 * 标注在 Controller 类或方法上,由 {@code AdminInterceptor} 在进入控制器前校验:
 * 当前用户角色必须包含 ADMIN,否则返回 403。
 * <p>
 * 例: 用户管理、角色管理、数据字典、操作日志等后台功能均属管理员专属。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {

    /** 拒绝访问时返回给前端的提示 */
    String value() default "该功能仅管理员可访问";
}
