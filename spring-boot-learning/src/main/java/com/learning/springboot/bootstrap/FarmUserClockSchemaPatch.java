package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 用户级世界时钟建表 + 老数据迁移（2026-10-26）。
 *
 * <p><b>为什么必须手写 DDL</b>：SQLite 下 {@code hibernate.ddl-auto: update} 不可靠，
 * 新表一律手写（与 {@link FarmWarehouseSchemaPatch} / {@link FarmStockSchemaPatch} 同范式）。
 *
 * <h2>迁移为什么是「取地块偏移的最大值」</h2>
 *
 * <p>老库的推进量散在各地块的 {@code t_farm_plot.demo_offset_days} 里，
 * 而旧的用户级口径 = {@code max(所有地块偏移)}。
 * 迁移时取同一个最大值 → <b>迁移当天的玩家口径与旧口径逐日相同</b>，
 * 仓库老化基准、天气取段起点、推荐播期都不会跳变。
 *
 * <p>幂等：已存在于表中的用户不覆盖（避免每次重启把玩家推进的天数抹回去）。
 *
 * <h2>执行顺序</h2>
 *
 * <p>本 patch 依赖 {@code t_farm_plot.demo_offset_days} 这一列存在。
 * 该列由 {@link FarmActionSchemaPatch} 负责补建，但多个 patch 都监听
 * {@code ApplicationReadyEvent}，Spring <b>不保证</b>它们的先后顺序。
 * 因此：
 * <ul>
 *   <li>用 {@code @Order(LOWEST_PRECEDENCE)} 让本 patch <b>最后</b>跑；</li>
 *   <li>迁移前先检查列是否存在，缺列就跳过迁移（而不是整段炸掉）。</li>
 * </ul>
 */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.LOWEST_PRECEDENCE)
public class FarmUserClockSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(FarmUserClockSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public FarmUserClockSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        createTable();
        migrateFromPlots();
    }

    /** 建 {@code t_farm_user_clock}（幂等）。 */
    private void createTable() {
        executeQuietly("""
                CREATE TABLE IF NOT EXISTS t_farm_user_clock (
                    id integer primary key autoincrement,
                    user_id bigint not null,
                    offset_days integer not null default 0,
                    update_time timestamp
                )
                """);
        executeQuietly("CREATE UNIQUE INDEX IF NOT EXISTS idx_farm_user_clock_user ON t_farm_user_clock (user_id)");
    }

    /**
     * 老数据迁移：把每个用户的「地块偏移最大值」写进用户级时钟。
     *
     * <p>只迁移**表里还没有**的用户（幂等）。
     */
    private void migrateFromPlots() {
        try {
            // 依赖列存在性检查：t_farm_plot 由 FarmActionSchemaPatch 补列，
            // 但两个 patch 的执行顺序不保证 —— 缺列就跳过本次迁移（下次启动会补上）。
            if (!columnExists("t_farm_plot", "demo_offset_days")) {
                log.info("[schema-patch] t_farm_plot.demo_offset_days 尚未就绪，本次跳过用户时钟迁移");
                return;
            }
            List<java.util.Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT p.user_id AS user_id, MAX(p.demo_offset_days) AS max_offset
                    FROM t_farm_plot p
                    WHERE NOT EXISTS (SELECT 1 FROM t_farm_user_clock c WHERE c.user_id = p.user_id)
                    GROUP BY p.user_id
                    """);
            if (rows.isEmpty()) {
                return;
            }
            int n = 0;
            for (java.util.Map<String, Object> row : rows) {
                Object uid = row.get("user_id");
                Object off = row.get("max_offset");
                if (uid == null) {
                    continue;
                }
                long userId = ((Number) uid).longValue();
                int offset = off == null ? 0 : ((Number) off).intValue();
                jdbc.update("""
                        INSERT OR IGNORE INTO t_farm_user_clock (user_id, offset_days, update_time)
                        VALUES (?, ?, ?)
                        """, userId, offset, System.currentTimeMillis());
                n++;
            }
            if (n > 0) {
                log.info("[schema-patch] 已迁移 {} 个用户的推进量到用户级世界时钟", n);
            }
        } catch (Exception ex) {
            log.warn("[schema-patch] 迁移用户级世界时钟失败: {}", ex.getMessage());
        }
    }

    private void executeQuietly(String sql) {
        try {
            jdbc.execute(sql);
        } catch (Exception ex) {
            log.warn("[schema-patch] 执行失败 [{}]: {}", sql, ex.getMessage());
        }
    }

    /**
     * 列是否存在（SQLite 的 {@code PRAGMA table_info} 返回的是列名列表）。
     *
     * <p>⚠️ 取值要用 {@code Map} 的 {@code name} 键 —— SQLite 的 pragma 结果里
     * 第一列叫 {@code name}，用下标 0 取会拿到 {@code cid}。
     */
    private boolean columnExists(String table, String column) {
        try {
            List<java.util.Map<String, Object>> cols = jdbc.queryForList(
                    "PRAGMA table_info(" + table + ")");
            for (java.util.Map<String, Object> c : cols) {
                Object n = c.get("name");
                if (n != null && column.equalsIgnoreCase(String.valueOf(n))) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            return false;
        }
    }
}
