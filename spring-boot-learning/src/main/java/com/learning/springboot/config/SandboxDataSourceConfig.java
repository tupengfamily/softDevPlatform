package com.learning.springboot.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 沙箱库独立数据源配置
 * <p>
 * 教学 SQL 沙箱使用独立的 sandbox.db 文件, 与主库 admin.db 完全隔离,
 * 学生可以随便执行 SELECT/INSERT/UPDATE/DELETE 而不污染真实数据。
 */
@Configuration
public class SandboxDataSourceConfig {

    /**
     * 沙箱数据源 Bean(独立连接池, name 区别于主数据源, 避免冲突)
     */
    @Bean(name = "sandboxDataSource", destroyMethod = "close")
    public HikariDataSource sandboxDataSource(
        @Value("${sandbox.datasource.url}") String url,
        @Value("${sandbox.datasource.driver-class-name}") String driver,
        @Value("${sandbox.datasource.pool-size:5}") int poolSize
    ) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setDriverClassName(driver);
        cfg.setUsername("");
        cfg.setPassword("");
        cfg.setPoolName("SandboxHikariPool");
        cfg.setMaximumPoolSize(poolSize);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(10_000);
        // 连接级 PRAGMA: 与主库保持一致(WAL/NORMAL/5s/FK/cache/temp)
        cfg.setConnectionInitSql(
            "PRAGMA journal_mode = WAL;"
          + "PRAGMA synchronous = NORMAL;"
          + "PRAGMA busy_timeout = 5000;"
          + "PRAGMA foreign_keys = ON;"
          + "PRAGMA cache_size = -64000;"
          + "PRAGMA temp_store = MEMORY;"
        );
        return new HikariDataSource(cfg);
    }

    /** 沙箱专用 JdbcTemplate */
    @Bean(name = "sandboxJdbcTemplate")
    public JdbcTemplate sandboxJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("sandboxDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}