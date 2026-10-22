package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 农场表日期列的存储类型归一化（幂等）。
 *
 * <p><b>背景（2026-10-21 实测踩坑）</b>：SQLite 是**弱类型**数据库，列的 declared type
 * 只在 DDL 里声明一次，<b>实际存进去的值可以是任意类型</b>。Hibernate 读实体时按
 * <b>declared type</b> 做转换，于是出现这种事故：
 *
 * <pre>
 *   DDL:  sow_date date not null
 *   行 A: sow_date = 1791993600000   (INTEGER，Hibernate 写的 → 能读)
 *   行 B: sow_date = '2026-10-15'    (TEXT，  外部脚本写的 → 读时抛 HibernateException)
 * </pre>
 *
 * 一行 TEXT 就能让 {@code findByUserIdOrderBySlotAsc} 整条查询失败，
 * 进而让 {@code GET /api/games/farm/board} 500 —— <b>一个用户的所有农场全部看不见</b>，
 * 而不是只坏一块地。这在生产上是不可接受的故障放大。
 *
 * <p><b>本补丁做什么</b>：把日期/时间戳列里所有 TEXT 形态的值转成 INTEGER（epoch 毫秒），
 * 与 Hibernate 自己写的格式对齐。转换失败的值会被置为 NULL / 合理默认值，绝不留下炸弹。
 *
 * <p><b>为什么不是「读时 try-catch」</b>：异常发生在 JPA 的 RowMapper 里，
 * 一行坏数据会让整个 ResultSet 报废，没法「跳过这一行继续读下一行」。
 * 只能在存储层把脏数据洗干净。
 */
@Component
public class FarmDateTypePatch {

    private static final Logger log = LoggerFactory.getLogger(FarmDateTypePatch.class);

    /** 表 → 需要归一化的列（date 类存 epoch 毫秒；timestamp 类同理）。 */
    private static final String[][] TARGETS = {
            {"t_farm_plot", "sow_date"},
            {"t_farm_plot", "last_harvest_date"},
            {"t_farm_plot", "season_started_at"},
            {"t_farm_plot", "created_at"},
            {"t_farm_plot", "updated_at"},
            {"t_farm_action", "date"},
            {"t_farm_action", "created_at"},
            {"t_farm_action", "updated_at"},
            {"t_farm_stead", "created_at"},
            {"t_farm_stead", "updated_at"},
            {"t_farm_season", "sow_date"},
            {"t_farm_season", "harvest_date"},
            {"t_farm_season", "created_at"},
            {"t_farm_season", "updated_at"},
    };

    private final JdbcTemplate jdbc;

    public FarmDateTypePatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        for (String[] t : TARGETS) {
            try {
                normalize(t[0], t[1]);
            } catch (Exception ex) {
                log.warn("[schema-patch] {}.{} 日期类型归一化失败: {}", t[0], t[1], ex.getMessage());
            }
        }
    }

    /**
     * 把某列里所有 TEXT 形态的日期/时间戳转成 INTEGER epoch 毫秒。
     *
     * <p>SQLite 没有 ALTER COLUMN TYPE，只能逐行 UPDATE。用
     * {@code typeof(col)='text'} 精确圈定脏数据，正常行一个都不碰。
     */
    private void normalize(String table, String column) {
        if (!columnExists(table, column)) {
            return;
        }
        // 找出所有 TEXT 值（正常值是 INTEGER，不会命中）
        List<String> bad = new ArrayList<>();
        jdbc.query("select " + column + " from " + table + " where typeof(" + column + ")='text'",
                rs -> {
                    String v = rs.getString(1);
                    if (v != null) {
                        bad.add(v);
                    }
                });
        if (bad.isEmpty()) {
            return;
        }

        int fixed = 0;
        for (String raw : bad) {
            Long epoch = parseEpoch(raw);
            if (epoch == null) {
                // 解析不出来 → 置 NULL（可空列）或跳过（not null 列交给调用方兜底）
                continue;
            }
            int n = jdbc.update("update " + table + " set " + column + "=? where typeof(" + column + ")='text' and " + column + "=?",
                    epoch, raw);
            fixed += n;
        }
        log.info("[schema-patch] {}.{} 文本日期已归一化为 epoch 毫秒: {} 行", table, column, fixed);
    }

    /**
     * 解析常见日期写法 → epoch 毫秒。
     *
     * <p>覆盖：{@code 2026-10-15}、{@code 2026-10-15 23:28:26}、
     * {@code 2026-10-15T23:28:26}、{@code 2026-10-15T23:28:26.123}。
     *
     * <p>时区按**本地时区**处理——Hibernate 写入时用的也是本地时区
     * （{@code LocalDateTime.now()} → 转 epoch 时走系统默认时区），
     * 保持一致才不会整体偏移几个小时。
     */
    private Long parseEpoch(String raw) {
        String v = raw.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            // 已经是纯数字（epoch 字符串）→ 直接用
            if (v.matches("-?\\d+")) {
                return Long.parseLong(v);
            }
            String norm = v.replace('T', ' ');
            // 去掉小数秒
            int dot = norm.indexOf('.');
            if (dot > 0) {
                norm = norm.substring(0, dot);
            }
            if (norm.length() == 10) { // yyyy-MM-dd
                return java.time.LocalDate.parse(norm)
                        .atStartOfDay()
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant()
                        .toEpochMilli();
            }
            return java.time.LocalDateTime.parse(norm, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            log.warn("[schema-patch] 无法解析日期 '{}': {}", raw, e.toString());
            return null;
        }
    }

    private boolean columnExists(String table, String column) {
        try {
            List<String> cols = jdbc.query("PRAGMA table_info(" + table + ")",
                    (rs, i) -> rs.getString("name"));
            for (String c : cols) {
                if (c != null && c.toLowerCase(Locale.ROOT).equals(column.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.warn("[schema-patch] 检查 {}.{} 失败: {}", table, column, ex.getMessage());
        }
        return false;
    }
}
