package com.learning.springboot.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * SQLite 数据源前置修复配置（2026-10-26）。
 *
 * <p>负责在<b>所有数据源 Bean 实例化之前</b>把 SQLite 库文件的父目录建出来。
 * 详见 {@link SqliteDirectoryBootstrap} 的类注释（为什么需要、为什么用 BFPP）。
 *
 * <p>这里刻意用 {@code static @Bean} 而不是 {@code @Component}：
 * BFPP 作为 {@code @Component} 时，它自己也要走 Bean 创建流程，
 * 而负责注入 {@code Environment} 的 {@code ApplicationContextAwareProcessor}
 * 此时尚未注册 → 拿不到 Environment。用 static {@code @Bean} + 构造器注入
 * 可彻底绕开这个时序问题。
 */
@Configuration
public class SqliteDataSourceConfig {

    /**
     * 注册 SQLite 父目录兜底创建器。
     *
     * <p>{@code static} 是必须的：非 static 的 {@code @Bean} 方法会让配置类提前实例化，
     * 可能触发「BFPP 尚未注册就创建依赖它的 Bean」的循环问题。
     */
    @Bean
    public static SqliteDirectoryBootstrap sqliteDirectoryBootstrap(Environment environment) {
        return new SqliteDirectoryBootstrap(environment);
    }
}
