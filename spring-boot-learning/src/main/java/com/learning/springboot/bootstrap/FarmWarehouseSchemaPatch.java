package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 仓库表建表 / 补列（S24）。
 *
 * <p><b>为什么不用 {@code hibernate.ddl-auto: update}</b>：SQLite 下它不可靠，
 * 老库缺列时不会补齐（NOT NULL 无默认值的列更是直接补不上）。
 * 项目里所有农场相关新表都用「Patch 类手写 DDL」这一套
 * （见 {@link FarmActionSchemaPatch}），本类沿用同一范式。
 *
 * <p>DDL 与 JPA 实体 {@code FarmWarehouse} 保持一致，避免 ddl-auto 反复改表。
 */
@Component
public class FarmWarehouseSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(FarmWarehouseSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public FarmWarehouseSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        migrateWarehouse();
        repairStatusColumn();
    }

    /**
     * 修复 {@code status} 列被存成序数（tinyint）的老库。
     *
     * <p><b>背景（2026-10-22 实测踩坑）</b>：{@code FarmWarehouse.status} 一开始漏了
     * {@code @Enumerated(EnumType.STRING)}，Hibernate 按<b>序数</b>建列，
     * 于是 {@code status} 变成 {@code tinyint}。而项目约定（{@code FarmSeason.status}）
     * 是字符串枚举，两种口径混用时读出来全是错的。
     *
     * <p><b>SQLite 不能 ALTER COLUMN TYPE</b>，只能重建表：
     * 建新表 → 拷贝数据（把序数翻译成字符串）→ 删旧表 → 改名。
     * 顺带把 {@code status} 的历史值按枚举名对齐。
     */
    private void repairStatusColumn() {
        try {
            String type = columnType("t_farm_warehouse", "status");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("int")) {
                return; // 已是字符串列（或列不存在），无需修
            }
            log.warn("[schema-patch] t_farm_warehouse.status 是序数列({})，重建为字符串枚举", type);

            executeQuietly("""
                    CREATE TABLE IF NOT EXISTS t_farm_warehouse_new (
                        id integer primary key autoincrement,
                        user_id bigint not null,
                        crop_id varchar(32) not null,
                        crop_name varchar(64),
                        zone_id varchar(32),
                        plot_id bigint,
                        farm_id bigint,
                        quantity float not null default 0,
                        stored_date date not null,
                        status varchar(16) not null default 'STORED',
                        quality_factor float not null default 1,
                        sold_price float,
                        sold_amount float,
                        sold_kg float not null default 0,
                        sold_date date,
                        create_time timestamp not null,
                        update_time timestamp
                    )
                    """);
            // 序数 → 枚举名：0=STORED 1=SOLD 2=DISCARDED
            executeQuietly("""
                    INSERT INTO t_farm_warehouse_new
                        (id, user_id, crop_id, crop_name, zone_id, plot_id, farm_id, quantity,
                         stored_date, status, quality_factor, sold_price, sold_amount, sold_kg,
                         sold_date, create_time, update_time)
                    SELECT id, user_id, crop_id, crop_name, zone_id, plot_id, farm_id, quantity,
                           stored_date,
                           CASE CAST(status AS INTEGER)
                               WHEN 0 THEN 'STORED' WHEN 1 THEN 'SOLD' WHEN 2 THEN 'DISCARDED'
                               ELSE 'STORED' END,
                           COALESCE(quality_factor, 1),
                           sold_price, sold_amount, COALESCE(sold_kg, 0),
                           sold_date, create_time, update_time
                    FROM t_farm_warehouse
                    """);
            executeQuietly("DROP TABLE t_farm_warehouse");
            executeQuietly("ALTER TABLE t_farm_warehouse_new RENAME TO t_farm_warehouse");
            executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_warehouse_user ON t_farm_warehouse (user_id)");
            executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_warehouse_crop ON t_farm_warehouse (user_id, crop_id)");
            log.info("[schema-patch] t_farm_warehouse.status 已重建为字符串枚举");
        } catch (Exception ex) {
            log.warn("[schema-patch] 修复 t_farm_warehouse.status 失败: {}", ex.getMessage());
        }
    }

    /** 取列的 declared type（不存在返回 null）。 */
    private String columnType(String table, String column) {
        try {
            var rows = jdbc.queryForList("PRAGMA table_info(" + table + ")");
            for (var row : rows) {
                Object name = row.get("name");
                if (name != null && name.toString().equalsIgnoreCase(column)) {
                    Object t = row.get("type");
                    return t == null ? null : t.toString();
                }
            }
        } catch (Exception ex) {
            log.warn("[schema-patch] 检查 {}.{} 类型失败: {}", table, column, ex.getMessage());
        }
        return null;
    }

    /**
     * 建 {@code t_farm_warehouse}（幂等）。
     *
     * <p>⚠️ {@code stored_date} / {@code sold_date} 是 date 列，
     * 实际存 epoch 毫秒 INTEGER（见 {@link FarmDateTypePatch}）。
     */
    private void migrateWarehouse() {
        executeQuietly("""
                CREATE TABLE IF NOT EXISTS t_farm_warehouse (
                    id integer primary key autoincrement,
                    user_id bigint not null,
                    crop_id varchar(32) not null,
                    crop_name varchar(64),
                    zone_id varchar(32),
                    plot_id bigint,
                    farm_id bigint,
                    quantity float not null default 0,
                    stored_date date not null,
                    status varchar(16) not null default 'STORED',
                    quality_factor float not null default 1,
                    sold_price float,
                    sold_amount float,
                    sold_kg float not null default 0,
                    sold_date date,
                    create_time timestamp not null,
                    update_time timestamp
                )
                """);
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_warehouse_user ON t_farm_warehouse (user_id)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_warehouse_crop ON t_farm_warehouse (user_id, crop_id)");
        // 老库补列（表已存在但缺列的场景）
        ensureColumn("t_farm_warehouse", "zone_id", "varchar(32)");
        ensureColumn("t_farm_warehouse", "plot_id", "bigint");
        ensureColumn("t_farm_warehouse", "farm_id", "bigint");
        ensureColumn("t_farm_warehouse", "quality_factor", "float not null default 1");
        ensureColumn("t_farm_warehouse", "sold_price", "float");
        ensureColumn("t_farm_warehouse", "sold_amount", "float");
        ensureColumn("t_farm_warehouse", "sold_kg", "float not null default 0");
        ensureColumn("t_farm_warehouse", "sold_date", "date");
        ensureColumn("t_farm_warehouse", "update_time", "timestamp");
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
