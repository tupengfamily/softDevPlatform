package com.learning.springboot.config;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * SQLite PRAGMA 优化(连接级,每次新建连接自动重设)
 * <p>
 * 通过 {@code spring.datasource.hikari.connection-init-sql} 与
 * {@code sandbox.datasource.init-sql} 两个 yml 配置项,让 Hikari 在
 * 启动时为每个新连接执行 PRAGMA。
 * <p>
 * 本类只在启动时打一条日志确认,不再尝试在 BeanPostProcessor 阶段修改
 * HikariDataSource(connectionInitSql 字段在 Hikari 启动后 sealed,会抛
 * "pool is sealed" 异常 — 见 Spring Cloud Config 同样的陷阱)。
 * <p>
 * 实际 PRAGMA 内容在 application.yml 中集中维护:
 * <ul>
 *   <li>journal_mode = WAL       读写并发,写不阻塞读;多个读 + 单个写并行</li>
 *   <li>synchronous = NORMAL     牺牲一点崩溃安全性换 ~2x 写性能</li>
 *   <li>busy_timeout = 5000     5 秒内自动重试,避免瞬时锁冲突</li>
 *   <li>foreign_keys = ON       SQLite 默认关闭外键约束</li>
 *   <li>cache_size = -64000     64MB 页面缓存(默认 2MB 太保守)</li>
 *   <li>temp_store = MEMORY     临时表/索引放内存</li>
 * </ul>
 */
@Configuration
public class SqlitePragmaInitializer {

    private static final Logger log = LoggerFactory.getLogger(SqlitePragmaInitializer.class);

    /**
     * 启动期仅打印提示,实际 PRAGMA 通过 yml 的 connection-init-sql 注入
     * <p>
     * 留这个 Bean 是为了在日志里留个明显的"SQLite 优化已启用"标记,便于排错
     */
    @Bean
    public org.springframework.boot.ApplicationRunner sqlitePragmaLog() {
        return args -> log.info("[SQLite] 连接级 PRAGMA 已通过 Hikari connection-init-sql 配置生效"
            + " (WAL/NORMAL/5s/FK=ON/cache=64MB/temp=MEMORY)");
    }
}
