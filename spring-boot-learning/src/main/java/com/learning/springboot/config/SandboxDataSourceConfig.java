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
 * 教学 SQL 沙箱使用独立的连接池, 学生在「SQL 沙箱」页里随便执行
 * SELECT/INSERT/UPDATE/DELETE 而不污染真实业务数据 ——
 * 沙箱只操作 classes / students / courses / scores 四张教学表。
 * <p>
 * ⚠️ 注意：沙箱池与主池<b>目前指向同一个 sandbox.db 文件</b>
 * （教学表与业务表同居一库）。这是历史既有行为 —— 主数据源 yml
 * 已改为指向 {@code data/sandbox.db}（见 application.yml 注释），
 * 沙箱池沿用同一路径以确保教学表可见。
 * <p>
 * 两个池指向同一 SQLite 文件时，靠 WAL + busy_timeout 化解写锁竞争；
 * 若日后要拆库，只需改 {@code sandbox.datasource.url} 指向新文件，
 * 本类无需改动。
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