package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 农场表的迁移补丁（幂等）。
 *
 * <p>两块历史包袱：
 * <ol>
 *   <li>{@code t_farm_action.type} 建表时被写成了
 *       {@code check (type in ('IRRIGATE','DRAIN',...))}，S10 工序链新增的类型会撞约束。
 *       SQLite 无法 ALTER 掉 CHECK，只能重建表。</li>
 *   <li>v1 是「一个用户一块田」，{@code t_farm_plot.user_id} 上有唯一索引。
 *       多地块（上限 100）要求唯一约束变成 {@code (user_id, slot)}。</li>
 * </ol>
 *
 * <p>顺序很重要：先跑 CHECK 重建（会重建整表，丢掉所有列），再补列与索引，
 * 否则重建会把刚补的列冲掉。
 */
@Component
public class FarmActionSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(FarmActionSchemaPatch.class);

    private static final String LEGACY_CHECK = "check (type in ('IRRIGATE'";

    private final JdbcTemplate jdbc;

    public FarmActionSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        // ① 旧表重建（必须在补列之前）
        try {
            String ddl = jdbc.queryForObject(
                    "select sql from sqlite_master where type='table' and name='t_farm_action'",
                    String.class);
            if (ddl != null && ddl.toLowerCase().contains(LEGACY_CHECK.toLowerCase())) {
                rebuildActionTable();
            }
        } catch (Exception ex) {
            log.warn("[schema-patch] t_farm_action 重建失败: {}", ex.getMessage());
        }

        // ② 赛季边界列
        ensureColumn("t_farm_plot", "season_started_at", "timestamp");
        // ③ 建田 / 适区（S2 / S23）
        ensureColumn("t_farm_plot", "zone_id", "varchar(32)");
        ensureColumn("t_farm_plot", "field_name", "varchar(64)");
        ensureColumn("t_farm_plot", "zone_chosen", "boolean not null default 0");
        // ④ 农资（S12）
        ensureColumn("t_farm_action", "item_id", "varchar(32)");
        ensureColumn("t_farm_action", "dose", "real");
        ensureColumn("t_farm_action", "cost", "real");
        // ⑤ 作物库（S1）
        ensureColumn("t_farm_plot", "crop_id", "varchar(32)");

        // ⑥ 多地块
        migrateMultiPlot();

        // ⑦ 农场（地图选点）：地块挂农场 + 农场表
        migrateFarmStead();

        // ⑧ 季节档案（2026-10-22）：每块地每一季的种植与收获记录
        migrateFarmSeason();

        // ⑨ 待播种标志（2026-10-22）：收割 / 绝收 / 重开后置 1，建田清 0
        // 老库没有这列 → 全部补 0（历史地块都按「在长的地」处理，与旧行为一致）
        ensureColumn("t_farm_plot", "awaiting_sow", "boolean not null default 0");
    }

    /**
     * 季节档案表（幂等）。
     *
     * <p>{@code t_farm_season} 一行 = 一块地的一季：建田时写一条 GROWING，
     * 收割时补全为 HARVESTED / FAILED。它是农场详情页「种植与收获记录」的数据源。
     *
     * <p>DDL 与 JPA 实体 {@code FarmSeason} 保持一致，避免 ddl-auto 反复改表。
     */
    private void migrateFarmSeason() {
        executeQuietly("""
                CREATE TABLE IF NOT EXISTS t_farm_season (
                    id integer primary key autoincrement,
                    user_id bigint not null,
                    plot_id bigint not null,
                    farm_id bigint,
                    slot integer not null default 0,
                    season_no integer not null default 1,
                    crop_id varchar(32),
                    crop_name varchar(64),
                    visual varchar(16),
                    zone_id varchar(32),
                    field_name varchar(64),
                    sow_date date not null,
                    harvest_date date,
                    dvs float,
                    yield_kg_per_mu float,
                    yield_raw float,
                    germination float,
                    status varchar(16) not null,
                    failure_type varchar(24),
                    failure_note varchar(240),
                    detail varchar(4000),
                    input_cost float,
                    revenue float,
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_season_user ON t_farm_season (user_id)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_season_plot ON t_farm_season (plot_id, season_no)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_season_farm ON t_farm_season (farm_id, user_id)");
    }

    /**
     * 农场迁移（幂等）：
     * 1. t_farm_plot 补 farm_id（可空——v1 的历史地块保留「未归属」）；
     * 2. 建 t_farm_stead 表（JPA 建表语句与这里保持一致，避免 ddl-auto 反复改表）。
     */
    private void migrateFarmStead() {
        ensureColumn("t_farm_plot", "farm_id", "integer");
        executeQuietly("""
                CREATE TABLE IF NOT EXISTS t_farm_stead (
                    id integer primary key autoincrement,
                    user_id bigint not null,
                    adcode integer not null,
                    name varchar(64) not null,
                    district_name varchar(64),
                    city_name varchar(64),
                    province_name varchar(64),
                    lng float not null,
                    lat float not null,
                    zone_id varchar(32),
                    created_at timestamp not null,
                    updated_at timestamp not null
                )
                """);
        executeQuietly("CREATE UNIQUE INDEX IF NOT EXISTS uk_farm_stead_user_adcode "
                + "ON t_farm_stead (user_id, adcode)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_stead_user ON t_farm_stead (user_id)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_plot_farm ON t_farm_plot (farm_id)");
    }

    /**
     * 多地块迁移（幂等）：
     * 1. t_farm_plot 补 slot 列；
     * 2. 解掉 user_id 上的唯一索引（它会让一个用户只能有一块田）；
     * 3. t_farm_action 补 plot_id，并把历史动作挂到该用户 slot 0 的地块上。
     */
    private void migrateMultiPlot() {
        ensureColumn("t_farm_plot", "slot", "integer not null default 0");
        ensureColumn("t_farm_action", "plot_id", "integer");

        // 关键：v1 建表时 user_id 是「列级 unique」，SQLite 为它生成隐式索引
        // sqlite_autoindex_*，DROP INDEX 删不掉，只能重建表。
        rebuildPlotTableIfNeeded();

        executeQuietly("CREATE UNIQUE INDEX IF NOT EXISTS idx_farm_plot_user_slot "
                + "ON t_farm_plot (user_id, slot)");
        executeQuietly("CREATE INDEX IF NOT EXISTS idx_farm_action_plot "
                + "ON t_farm_action (plot_id, date)");

        try {
            int n = jdbc.update(
                    "UPDATE t_farm_action SET plot_id = (SELECT p.id FROM t_farm_plot p "
                            + "WHERE p.user_id = t_farm_action.user_id AND p.slot = 0) "
                            + "WHERE plot_id IS NULL");
            if (n > 0) {
                log.info("[schema-patch] 历史农事已回填 plot_id: {} 条", n);
            }
        } catch (Exception ex) {
            log.warn("[schema-patch] 回填 plot_id 失败: {}", ex.getMessage());
        }
    }

    /** 旧表（带 CHECK 约束）重建：改名 → 建新表 → 拷数据 → 删旧表 → 重建索引。 */
    private void rebuildActionTable() {
        log.info("[schema-patch] t_farm_action 仍是旧 CHECK 约束，开始重建以支持 S10 工序类型");
        jdbc.execute("ALTER TABLE t_farm_action RENAME TO t_farm_action_old");
        jdbc.execute("""
                CREATE TABLE t_farm_action (
                    id integer primary key autoincrement,
                    created_at timestamp not null,
                    date date not null,
                    duration_days integer not null,
                    note varchar(80),
                    type varchar(16) not null,
                    updated_at timestamp not null,
                    user_id bigint not null
                )
                """);
        jdbc.execute("""
                INSERT INTO t_farm_action (id, created_at, date, duration_days, note, type, updated_at, user_id)
                SELECT id, created_at, date, duration_days, note, type, updated_at, user_id
                FROM t_farm_action_old
                """);
        jdbc.execute("DROP TABLE t_farm_action_old");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_farm_action_user ON t_farm_action (user_id)");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_farm_action_range ON t_farm_action (user_id, date)");
        log.info("[schema-patch] t_farm_action 重建完成（已去掉 type 的 CHECK 约束）");
    }

    /**
     * 地块表重建：把 {@code user_id ... unique} 这个列级约束去掉，换成 (user_id, slot) 唯一索引。
     * 只有检测到旧约束时才动，重复启动无副作用。
     */
    private void rebuildPlotTableIfNeeded() {
        try {
            String ddl = jdbc.queryForObject(
                    "select sql from sqlite_master where type='table' and name='t_farm_plot'",
                    String.class);
            if (ddl == null) {
                return;
            }
            boolean legacy = ddl.toLowerCase()
                    .replaceAll("\\s+", " ")
                    .matches("(?s).*user_id bigint not null unique.*");
            if (!legacy) {
                return;
            }
            log.info("[schema-patch] t_farm_plot 仍是 user_id 单列唯一，重建以支持多地块");
            jdbc.execute("ALTER TABLE t_farm_plot RENAME TO t_farm_plot_old");
            jdbc.execute("""
                    CREATE TABLE t_farm_plot (
                        id integer primary key autoincrement,
                        user_id bigint not null,
                        slot integer not null default 0,
                        sow_date date not null,
                        crop_id varchar(32),
                        field_name varchar(64),
                        zone_id varchar(32),
                        zone_chosen boolean not null default 0,
                        awaiting_sow boolean not null default 0,
                        seasons integer not null,
                        best_yield float not null,
                        last_harvest_date date,
                        last_yield float,
                        demo_offset_days integer not null,
                        season_started_at timestamp,
                        created_at timestamp not null,
                        updated_at timestamp not null
                    )
                    """);
            jdbc.execute("""
                    INSERT INTO t_farm_plot (id, user_id, slot, sow_date, crop_id, field_name, zone_id,
                                             zone_chosen, awaiting_sow, seasons, best_yield, last_harvest_date,
                                             last_yield, demo_offset_days, season_started_at,
                                             created_at, updated_at)
                    SELECT id, user_id, COALESCE(slot, 0), sow_date, crop_id, field_name, zone_id,
                           COALESCE(zone_chosen, 0), COALESCE(awaiting_sow, 0), seasons, best_yield, last_harvest_date,
                           last_yield, demo_offset_days, season_started_at,
                           created_at, updated_at
                    FROM t_farm_plot_old
                    """);
            jdbc.execute("DROP TABLE t_farm_plot_old");
            jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_farm_plot_user_slot "
                    + "ON t_farm_plot (user_id, slot)");
            log.info("[schema-patch] t_farm_plot 重建完成（唯一约束改为 user_id + slot）");
        } catch (Exception ex) {
            log.warn("[schema-patch] t_farm_plot 重建失败: {}", ex.getMessage());
        }
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
