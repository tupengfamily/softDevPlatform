package com.learning.springboot.common;

import java.util.Set;

/**
 * i18n 共享常量
 * <p>
 * 集中管理平台支持的语言列表、默认语言,避免 Controller/Service 各自硬编码
 * (前后端须保持一致 — 前端对应 /src/i18n/index.js#L23-26)
 */
public final class I18nConstants {

    private I18nConstants() {}

    /** 默认语言(新用户偏好 / 登录兜底) */
    public static final String DEFAULT_LOCALE = "zh-CN";

    /** 平台支持的语言(前后端必须一致) */
    public static final Set<String> SUPPORTED_LOCALES = Set.of("zh-CN", "en-US");

    /** 校验 + 容错:传入 null/不识别的语言 → 返回默认 */
    public static String normalize(String locale) {
        if (locale == null || locale.isBlank()) return DEFAULT_LOCALE;
        return SUPPORTED_LOCALES.contains(locale) ? locale : DEFAULT_LOCALE;
    }

    /** 严格校验:不支持的语言抛业务异常 */
    public static String requireValid(String locale) {
        if (locale != null && !locale.isBlank() && !SUPPORTED_LOCALES.contains(locale)) {
            throw new IllegalArgumentException("不支持的语言: " + locale + " (支持: " + SUPPORTED_LOCALES + ")");
        }
        return normalize(locale);
    }
}