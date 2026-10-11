package com.learning.springboot.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL 沙箱
 * <p>
 * 教学场景下学生可以随意执行 SQL 不会破坏真实数据, 所以使用独立的 SQLite 库。
 * <p>
 * 安全策略:
 * - 仅允许 SELECT/INSERT/UPDATE/DELETE(白名单首关键字)
 * - 禁止多语句(; 后还有内容)
 * - 禁止 PRAGMA / ATTACH 等元命令
 * - DDL(CREATE/DROP/ALTER) 也允许, 单独走"重建"接口触发, 默认执行接口拒绝
 * <p>
 * 教学数据初始化由 {@link SandboxDataInitializer} 完成(在 AdminBootstrap 之后建表灌种子)
 */
@Service
public class SqlSandboxService {

    private static final Logger log = LoggerFactory.getLogger(SqlSandboxService.class);

    /** 允许的语句类型(首关键字); EXPLAIN 为只读查询计划, 教学索引章节需要 */
    private static final List<String> ALLOWED_VERBS = List.of("SELECT", "INSERT", "UPDATE", "DELETE", "EXPLAIN");

    /** 拒绝的语句类型 */
    private static final List<String> REJECTED_VERBS = List.of(
        "CREATE", "DROP", "ALTER", "TRUNCATE", "PRAGMA", "ATTACH", "DETACH", "REPLACE",
        "VACUUM", "REINDEX", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE");

    /** 匹配多个 SQL 语句: ; 后还跟非空白字符(忽略末尾分号) */
    private static final Pattern MULTI_STATEMENT = Pattern.compile(";[^;]*[^\\s;]");

    private final JdbcTemplate jdbc;

    public SqlSandboxService(@Qualifier("sandboxJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 执行用户提交的 SQL(教学沙箱)
     *
     * @param sql 用户原始 SQL
     * @return 结果集 / 受影响行数
     */
    public SqlResult execute(String sql) {
        long start = System.currentTimeMillis();
        SqlResult r = new SqlResult();
        String trimmed = sql == null ? "" : sql.trim();
        r.sql = trimmed;

        if (trimmed.isEmpty()) {
            return errorResult(r, "SQL 不能为空", start);
        }
        // 取首个关键字
        String firstWord = trimmed.split("\\s+")[0].toUpperCase(Locale.ROOT);
        // 多语句检查
        if (MULTI_STATEMENT.matcher(trimmed).find()) {
            return errorResult(r, "教学沙箱不支持多条语句, 请一次只执行一条", start);
        }
        if (REJECTED_VERBS.contains(firstWord)) {
            return errorResult(r, "教学沙箱不支持 " + firstWord + " 语句(请用'重置数据库'恢复初始结构)", start);
        }
        if (!ALLOWED_VERBS.contains(firstWord)) {
            return errorResult(r, "仅支持 SELECT/INSERT/UPDATE/DELETE/EXPLAIN 语句", start);
        }

        try {
            if ("SELECT".equals(firstWord) || "EXPLAIN".equals(firstWord)) {
                executeSelect(trimmed, r);
            } else {
                r.rowCount = jdbc.update(trimmed);
            }
            r.elapsedMs = System.currentTimeMillis() - start;
            return r;
        } catch (Exception e) {
            log.warn("[SQL Sandbox] 执行失败: {}", e.getMessage());
            return errorResult(r, e.getMessage(), start);
        }
    }

    private void executeSelect(String sql, SqlResult r) {
        jdbc.query(sql, rs -> {
            int colCount = rs.getMetaData().getColumnCount();
            if (r.columns == null) {
                r.columns = new ArrayList<>();
                for (int i = 1; i <= colCount; i++) {
                    r.columns.add(rs.getMetaData().getColumnLabel(i));
                }
                r.rows = new ArrayList<>();
            }
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                Object v = rs.getObject(i);
                row.put(r.columns.get(i - 1), v);
            }
            r.rows.add(row);
        });
        r.rowCount = r.rows == null ? 0 : r.rows.size();
    }

    private SqlResult errorResult(SqlResult r, String msg, long start) {
        r.error = msg;
        r.elapsedMs = System.currentTimeMillis() - start;
        return r;
    }

    /** 结果结构(给前端) */
    public static class SqlResult {
        public String sql;
        public List<String> columns;        // SELECT 时非空
        public List<Map<String, Object>> rows;
        public long rowCount;               // SELECT=行数 / DML=受影响行数
        public long elapsedMs;
        public String error;                // 失败时填充
    }
}