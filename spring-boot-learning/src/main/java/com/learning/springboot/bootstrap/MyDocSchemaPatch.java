package com.learning.springboot.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 个人文档表的列迁移补丁(幂等)。
 *
 * <p>背景: 项目用 {@code hibernate.ddl-auto: update}, 但 SQLite 方言不一定会把新增字段
 * 转成 ALTER TABLE ADD COLUMN。为了确保老库(已存在的 sandbox.db)也能用上新字段,
 * 这里在应用就绪后检查列是否存在, 缺失即用 SQLite 支持的 ADD COLUMN 补上。
 *
 * <p>只做「加列」, 不做任何删列/改类型: SQLite 对 ALTER 的支持很有限, 但 ADD COLUMN 是安全的。
 */
@Component
public class MyDocSchemaPatch {

    private static final Logger log = LoggerFactory.getLogger(MyDocSchemaPatch.class);

    private final JdbcTemplate jdbc;

    public MyDocSchemaPatch(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void patch() {
        ensureColumn("t_my_doc_file", "last_access_time", "datetime");
        // SSO 单点登出字段: 实体标注 nullable=false, SQLite 老库无法由 ddl-auto 自动补建
        ensureColumn("t_user", "token_version", "INTEGER NOT NULL DEFAULT 0");
    }

    /** 列不存在时补建 */
    private void ensureColumn(String table, String column, String type) {
        try {
            for (String existing : columnsOf(table)) {
                if (existing.equalsIgnoreCase(column)) return;
            }
            jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("[schema-patch] {}.{} 已补建", table, column);
        } catch (Exception ex) {
            // 迁移失败不能阻断启动; 相关功能会退化, 但已有数据不受影响
            log.warn("[schema-patch] 补建 {}.{} 失败: {}", table, column, ex.getMessage());
        }
    }

    /**
     * SQLite 的列清单。
     *
     * <p>注意: {@code PRAGMA table_info} 返回 cid/name/type/notnull/dflt_value/pk 共 6 列,
     * 不能用 {@code queryForList(sql, String.class)} 取单列(会抛 Incorrect column count),
     * 必须自行从结果 Map 中取 name。
     */
    private List<String> columnsOf(String table) {
        List<Map<String, Object>> rows = jdbc.queryForList("PRAGMA table_info(" + table + ")");
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Object name = row.get("name");
            if (name != null) names.add(name.toString());
        }
        return names;
    }
}
