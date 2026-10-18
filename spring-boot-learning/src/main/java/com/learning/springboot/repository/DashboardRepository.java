package com.learning.springboot.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仪表盘聚合查询
 * <p>
 * 全部使用原生 SQL,因为聚合/日期函数高度依赖方言(SQLite / MySQL 各异)。
 * 不继承 JpaRepository,直接使用 EntityManager。
 * <p>
 * 关键约定(避免再次踩坑):create_time 是 Java 端 LocalDateTime,JDBC 写入 SQLite
 * 时是 **Unix 毫秒**(Long 类型),不能直接 date(create_time),必须先 /1000 转秒再加 'unixepoch':
 *   错: date(create_time)                  → NULL
 *   对: date(create_time/1000, 'unixepoch')  → 'YYYY-MM-DD'
 */
@Repository
public class DashboardRepository {

    @PersistenceContext
    private EntityManager em;

    /**
     * 仪表盘首页统计(扩展:加游戏/学习/反馈/沙箱的模块指标)
     * 一次查询返回多行,前端按需取值。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> dashboardSummary() {
        String sql = """
            SELECT
              (SELECT COUNT(*) FROM t_user)                                  AS totalUsers,
              (SELECT COUNT(*) FROM t_user WHERE status = 1)                 AS activeUsers,
              (SELECT COUNT(*) FROM t_user WHERE status = 0)                 AS disabledUsers,
              (SELECT COUNT(*) FROM t_user WHERE status = 2)                 AS readonlyUsers,
              (SELECT COUNT(*) FROM t_role)                                  AS totalRoles,
              (SELECT COUNT(*) FROM t_permission)                            AS totalPermissions,
              (SELECT COUNT(*) FROM t_operation_log
                 WHERE date(create_time/1000, 'unixepoch', 'localtime')
                       = date('now', 'localtime'))                            AS todayOps,
              (SELECT COUNT(*) FROM t_operation_log)                         AS totalOps,

              -- 模块指标:游戏
              (SELECT COUNT(*) FROM t_game_score)                           AS totalGamePlays,
              (SELECT COUNT(DISTINCT user_id) FROM t_game_score)            AS gameUsers,
              (SELECT COUNT(DISTINCT game_code) FROM t_game_score)          AS activeGames,

              -- 模块指标:学习
              (SELECT COUNT(*) FROM t_learning_subject WHERE enabled = 1)  AS activeSubjects,
              (SELECT COUNT(*) FROM t_learning_chapter WHERE archived = false) AS activeChapters,
              (SELECT COUNT(*) FROM t_learning_lesson)                      AS totalLessons,
              (SELECT COUNT(*) FROM t_lesson_progress)                      AS totalLearned,

              -- 模块指标:反馈 / 待处理
              (SELECT COUNT(*) FROM t_feedback)                             AS totalFeedback,
              (SELECT COUNT(*) FROM t_feedback WHERE status = '0')          AS pendingFeedback
            """;
        List<Object[]> rows = em.createNativeQuery(sql).getResultList();
        Map<String, Object> result = new LinkedHashMap<>();
        if (!rows.isEmpty()) {
            Object[] r = rows.get(0);
            String[] keys = {
                "totalUsers", "activeUsers", "disabledUsers", "readonlyUsers",
                "totalRoles", "totalPermissions", "todayOps", "totalOps",
                "totalGamePlays", "gameUsers", "activeGames",
                "activeSubjects", "activeChapters", "totalLessons", "totalLearned",
                "totalFeedback", "pendingFeedback"
            };
            for (int i = 0; i < keys.length && i < r.length; i++) {
                result.put(keys[i], toLong(r[i]));
            }
        }
        return result;
    }

    /**
     * 最近 7 天每天新增用户(SQLite: strftime 按 YYYY-MM-DD 分桶)
     * 自动补齐 7 天中缺失的日期(count=0)
     * <p>
     * 关键:create_time 存的是 Unix 毫秒,必须先 /1000 转秒
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> last7DaysRegistrations() {
        return last7DaysBucket("t_user");
    }

    /** 最近 7 天每天操作次数 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> last7DaysOperations() {
        return last7DaysBucket("t_operation_log");
    }

    private List<Map<String, Object>> last7DaysBucket(String table) {
        // 关键: create_time/1000 转秒, 'unixepoch' 转日期, 'localtime' 转本地时区
        // 用 Java 端算"7 天前秒数"传参,避免 SQLite 'now'/localtime 时区错位
        long sevenDaysAgoSec = java.time.Instant.now().getEpochSecond() - 7L * 86400;
        String sql = String.format("""
            SELECT date(create_time/1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS cnt
            FROM %s
            WHERE create_time/1000 >= ?
            GROUP BY day
            ORDER BY day ASC
            """, table);
        Query q = em.createNativeQuery(sql);
        q.setParameter(1, sevenDaysAgoSec);
        List<Object[]> rows = q.getResultList();

        Map<LocalDate, Long> bucket = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String day = (String) r[0];
            Long cnt = toLong(r[1]);
            if (day != null) {
                try { bucket.put(LocalDate.parse(day), cnt); } catch (Exception ignored) {}
            }
        }

        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", d.toString());
            item.put("count", bucket.getOrDefault(d, 0L));
            result.add(item);
        }
        return result;
    }

    /** 用户状态分布(0=禁用 1=启用 2=只读) */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> userStatusDistribution() {
        Query q = em.createNativeQuery("SELECT status, COUNT(*) FROM t_user GROUP BY status");
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("status", r[0]);
            item.put("count", toLong(r[1]));
            result.add(item);
        }
        return result;
    }

    /**
     * 最近 N 条反馈(用于 dashboard "最近反馈"列表)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recentFeedback(int limit) {
        Query q = em.createNativeQuery("""
            SELECT id, username, type, status, title, create_time
            FROM t_feedback
            ORDER BY id DESC
            LIMIT ?
            """);
        q.setParameter(1, limit);
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", toLong(r[0]));
            item.put("userName", r[1]);
            item.put("type", r[2]);
            item.put("status", r[3]);
            item.put("title", r[4]);
            item.put("createTime", r[5]);
            result.add(item);
        }
        return result;
    }

    /**
     * 最近 N 条游戏对局(用于 dashboard "最近对局"列表)
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recentGameScores(int limit) {
        Query q = em.createNativeQuery("""
            SELECT id, user_id, game_code, score, moves, play_seconds, finished, create_time
            FROM t_game_score
            ORDER BY id DESC
            LIMIT ?
            """);
        q.setParameter(1, limit);
        List<Object[]> rows = q.getResultList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", toLong(r[0]));
            item.put("userId", toLong(r[1]));
            item.put("gameCode", r[2]);
            item.put("score", toLong(r[3]));
            item.put("moves", toLong(r[4]));
            item.put("playSeconds", toLong(r[5]));
            item.put("finished", toLong(r[6]));
            item.put("createTime", r[7]);
            result.add(item);
        }
        return result;
    }

    /**
     * 每日对局数 7 日趋势
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> last7DaysGamePlays() {
        String sql = """
            SELECT date(create_time/1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS cnt
            FROM t_game_score
            WHERE create_time/1000 >= strftime('%s', 'now', '-7 days')
            GROUP BY day
            ORDER BY day ASC
            """;
        return bucketize(sql);
    }

    /** 每日学习完成数 7 日趋势(以 learned_at 算) */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> last7DaysLearning() {
        String sql = """
            SELECT date(learned_at/1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS cnt
            FROM t_lesson_progress
            WHERE learned_at IS NOT NULL AND learned_at/1000 >= strftime('%s', 'now', '-7 days')
            GROUP BY day
            ORDER BY day ASC
            """;
        return bucketize(sql);
    }

    /** 每日反馈数 7 日趋势 */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> last7DaysFeedback() {
        String sql = """
            SELECT date(create_time/1000, 'unixepoch', 'localtime') AS day, COUNT(*) AS cnt
            FROM t_feedback
            WHERE create_time/1000 >= strftime('%s', 'now', '-7 days')
            GROUP BY day
            ORDER BY day ASC
            """;
        return bucketize(sql);
    }

    /**
     * 通用 7 日桶补齐
     */
    private List<Map<String, Object>> bucketize(String rawSql) {
        long sevenDaysAgoSec = java.time.Instant.now().getEpochSecond() - 7L * 86400;
        String sql = rawSql.replace("strftime('%s', 'now', '-7 days')", "?");
        Query q = em.createNativeQuery(sql);
        q.setParameter(1, sevenDaysAgoSec);
        List<Object[]> rows = q.getResultList();
        Map<LocalDate, Long> bucket = new LinkedHashMap<>();
        for (Object[] r : rows) {
            String day = (String) r[0];
            Long cnt = toLong(r[1]);
            if (day != null) {
                try { bucket.put(LocalDate.parse(day), cnt); } catch (Exception ignored) {}
            }
        }
        List<Map<String, Object>> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("date", d.toString());
            item.put("count", bucket.getOrDefault(d, 0L));
            result.add(item);
        }
        return result;
    }

    private static long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
}