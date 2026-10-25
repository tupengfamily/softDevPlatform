package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 资金账户表建表 / 补列（2026-10-23 需求 R · 商店）。
 *
 * <p>沿用 {@link FarmStockSchemaPatch} 的同一范式：
 * SQLite 下 {@code hibernate.ddl-auto: update} 不可靠，新表一律手写 DDL。
 *
 * <p>DDL 与 JPA 实体 {@code FarmWallet} 保持一致，避免 ddl-auto 反复改表。
 */
@Component
public class FarmWalletSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(FarmWalletSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public FarmWalletSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        migrateWallet();
    }

    /** 建 {@code t_farm_wallet}（幂等）。 */
    private void migrateWallet() {
        executeQuietly("""
                CREATE TABLE IF NOT EXISTS t_farm_wallet (
                    id integer primary key autoincrement,
                    user_id bigint not null unique,
                    balance float not null default 800,
                    total_income float not null default 0,
                    total_expense float not null default 0,
                    create_time timestamp not null,
                    update_time timestamp
                )
                """);
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_wallet_user ON t_farm_wallet (user_id)");
        // 老库补列
        ensureColumn("t_farm_wallet", "total_income", "float not null default 0");
        ensureColumn("t_farm_wallet", "total_expense", "float not null default 0");
        ensureColumn("t_farm_wallet", "update_time", "timestamp");
    }

    private void executeQuietly(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ex) {
            log.warn("[schema-patch] 执行失败 [{}]: {}", sql, ex.getMessage());
        }
    }

    /** 列不存在时补建（SQLite 支持 ADD COLUMN）。 */
    private void ensureColumn(String table, String column, String type) {
        try {
            var rows = jdbc.queryForList("PRAGMA table_info(" + table + ")");
            for (var row : rows) {
                Object name = row.get("name");
                if (name != null && name.toString().equalsIgnoreCase(column)) {
                    return;
                }
            }
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("[schema-patch] {}.{} 已补建", table, column);
        } catch (Exception ex) {
            log.warn("[schema-patch] 补建 {}.{} 失败: {}", table, column, ex.getMessage());
        }
    }
}
