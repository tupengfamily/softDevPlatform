package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 农资库存表建表 / 补列（S25）。
 *
 * <p>沿用 {@link FarmWarehouseSchemaPatch} 的同一范式：
 * SQLite 下 {@code hibernate.ddl-auto: update} 不可靠，新表一律手写 DDL。
 *
 * <p>DDL 与 JPA 实体 {@code FarmStock} 保持一致，避免 ddl-auto 反复改表。
 */
@Component
public class FarmStockSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(FarmStockSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public FarmStockSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        migrateStock();
        repairStatusColumn();
    }

    /**
     * 修复 {@code status} 列被存成序数（tinyint）的老库。
     *
     * <p><b>为什么也要防这一手</b>：{@code FarmWarehouse} 就是这么翻车的
     * （2026-10-22 实测）——漏 {@code @Enumerated(EnumType.STRING)} 时 Hibernate
     * 按序数建列，而 SQLite 不能 ALTER COLUMN TYPE，只能重建表。
     * 这里在建表时就直接写字符串类型，正常路径不会触发；
     * 保留重建逻辑是为了防「实体改动前的老库」。
     */
    private void repairStatusColumn() {
        try {
            String type = columnType("t_farm_stock", "status");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).contains("int")) {
                return; // 已是字符串列（或列不存在），无需修
            }
            log.warn("[schema-patch] t_farm_stock.status 是序数列({})，重建为字符串枚举", type);
            executeQuietly("""
                    CREATE TABLE IF NOT EXISTS t_farm_stock_new (
                        id integer primary key autoincrement,
                        user_id bigint not null,
                        item_id varchar(32) not null,
                        item_name varchar(64),
                        item_kind varchar(16),
                        unit varchar(8),
                        farm_id bigint,
                        quantity float not null default 0,
                        total_bought float not null default 0,
                        price_per_unit float,
                        total_cost float not null default 0,
                        status varchar(16) not null default 'IN_STOCK',
                        create_time timestamp not null,
                        update_time timestamp
                    )
                    """);
            // 序数 → 枚举名：0=IN_STOCK 1=DEPLETED
            executeQuietly("""
                    INSERT INTO t_farm_stock_new
                        (id, user_id, item_id, item_name, item_kind, unit, farm_id, quantity,
                         total_bought, price_per_unit, total_cost, status, create_time, update_time)
                    SELECT id, user_id, item_id, item_name, item_kind, unit, farm_id, quantity,
                           COALESCE(total_bought, 0), price_per_unit, COALESCE(total_cost, 0),
                           CASE CAST(status AS INTEGER)
                               WHEN 0 THEN 'IN_STOCK' WHEN 1 THEN 'DEPLETED'
                               ELSE 'IN_STOCK' END,
                           create_time, update_time
                    FROM t_farm_stock
                    """);
            executeQuietly("DROP TABLE t_farm_stock");
            executeQuietly("ALTER TABLE t_farm_stock_new RENAME TO t_farm_stock");
            executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_stock_user ON t_farm_stock (user_id)");
            executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_stock_item ON t_farm_stock (user_id, item_id)");
            log.info("[schema-patch] t_farm_stock.status 已重建为字符串枚举");
        } catch (Exception ex) {
            log.warn("[schema-patch] 修复 t_farm_stock.status 失败: {}", ex.getMessage());
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

    /** 建 {@code t_farm_stock}（幂等）。 */
    private void migrateStock() {
        executeQuietly("""
                CREATE TABLE IF NOT EXISTS t_farm_stock (
                    id integer primary key autoincrement,
                    user_id bigint not null,
                    item_id varchar(32) not null,
                    item_name varchar(64),
                    item_kind varchar(16),
                    unit varchar(8),
                    farm_id bigint,
                    quantity float not null default 0,
                    total_bought float not null default 0,
                    price_per_unit float,
                    total_cost float not null default 0,
                    status varchar(16) not null default 'IN_STOCK',
                    create_time timestamp not null,
                    update_time timestamp
                )
                """);
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_stock_user ON t_farm_stock (user_id)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_stock_item ON t_farm_stock (user_id, item_id)");
        // 老库补列
        ensureColumn("t_farm_stock", "item_name", "varchar(64)");
        ensureColumn("t_farm_stock", "item_kind", "varchar(16)");
        ensureColumn("t_farm_stock", "unit", "varchar(8)");
        ensureColumn("t_farm_stock", "farm_id", "bigint");
        ensureColumn("t_farm_stock", "total_bought", "float not null default 0");
        ensureColumn("t_farm_stock", "price_per_unit", "float");
        ensureColumn("t_farm_stock", "total_cost", "float not null default 0");
        ensureColumn("t_farm_stock", "update_time", "timestamp");
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
