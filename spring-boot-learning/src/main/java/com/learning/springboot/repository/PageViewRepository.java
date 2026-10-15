package com.learning.springboot.repository;

import com.learning.springboot.entity.PageView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface PageViewRepository extends JpaRepository<PageView, Long> {

    /** 站点总览: 区间内 PV / UV / 平均时长 */
    @Query(value = """
        SELECT
            COUNT(*)        AS pv,
            COUNT(DISTINCT session_id) AS uv,
            COALESCE(AVG(duration_ms), 0) AS avg_duration_ms,
            COUNT(DISTINCT user_id) AS logged_uv
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to
        """, nativeQuery = true)
    java.util.List<Object[]> overviewStats(LocalDateTime from, LocalDateTime to);

    /** 页面排行: 按 PV 降序 */
    @Query(value = """
        SELECT
            route_name AS route,
            url,
            COUNT(*) AS pv,
            COUNT(DISTINCT session_id) AS uv,
            COALESCE(AVG(duration_ms), 0) AS avg_duration_ms,
            SUM(CASE WHEN leave_at IS NULL THEN 0 ELSE 1 END) AS completed_count
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to AND route_name IS NOT NULL
        GROUP BY route_name, url
        ORDER BY pv DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> pageRanking(LocalDateTime from, LocalDateTime to, int limit);

    /** 每日趋势: 用于仪表板曲线图(7 天)
     *  - enter_at 在 SQLite 里是 ms epoch 字符串(13 位), 所以除 1000 转秒再 datetime() 转 yyyy-MM-dd
     */
    @Query(value = """
        SELECT
            strftime('%Y-%m-%d', enter_at / 1000, 'unixepoch', 'localtime') AS day,
            COUNT(*) AS pv,
            COUNT(DISTINCT session_id) AS uv
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to
        GROUP BY day
        ORDER BY day ASC
        """, nativeQuery = true)
    List<Object[]> dailyTrend(LocalDateTime from, LocalDateTime to);

    /** 用户旅程: 同一 session 内按 enter_at 排序的全部页面浏览 */
    @Query(value = """
        SELECT
            route_name, url, page_title, referrer, enter_at, leave_at, duration_ms
        FROM t_page_view
        WHERE session_id = :sessionId
        ORDER BY enter_at ASC
        """, nativeQuery = true)
    List<Object[]> userJourneyBySession(String sessionId);

    /** 指定用户在某时间段内的全部页面浏览(用于单用户旅程) */
    @Query(value = """
        SELECT
            id, session_id, url, route_name, page_title, referrer,
            enter_at, leave_at, duration_ms, os, browser, device_type, ip
        FROM t_page_view
        WHERE user_id = :userId
          AND enter_at >= :from AND enter_at < :to
        ORDER BY enter_at DESC
        LIMIT 500
        """, nativeQuery = true)
    List<Object[]> userJourneyByUserId(Long userId, LocalDateTime from, LocalDateTime to);

    /** 操作系统分布: 区间内每种 OS 的 PV / UV */
    @Query(value = """
        SELECT
            COALESCE(os, 'Unknown') AS os,
            COUNT(*) AS pv,
            COUNT(DISTINCT session_id) AS uv
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to AND os IS NOT NULL
        GROUP BY os
        ORDER BY pv DESC
        """, nativeQuery = true)
    List<Object[]> osDistribution(LocalDateTime from, LocalDateTime to);

    /** 浏览器分布 */
    @Query(value = """
        SELECT
            COALESCE(browser, 'Unknown') AS browser,
            COUNT(*) AS pv,
            COUNT(DISTINCT session_id) AS uv
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to AND browser IS NOT NULL
        GROUP BY browser
        ORDER BY pv DESC
        """, nativeQuery = true)
    List<Object[]> browserDistribution(LocalDateTime from, LocalDateTime to);

    /** 设备类型分布 */
    @Query(value = """
        SELECT
            COALESCE(device_type, 'Unknown') AS dt,
            COUNT(*) AS pv
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to AND device_type IS NOT NULL
        GROUP BY device_type
        ORDER BY pv DESC
        """, nativeQuery = true)
    List<Object[]> deviceDistribution(LocalDateTime from, LocalDateTime to);

    /** 活跃用户排行: top N 用户 */
    @Query(value = """
        SELECT
            user_id,
            COUNT(*) AS pv,
            COUNT(DISTINCT session_id) AS uv,
            COALESCE(AVG(duration_ms), 0) AS avg_dur,
            MAX(enter_at) AS last_seen
        FROM t_page_view
        WHERE enter_at >= :from AND enter_at < :to AND user_id IS NOT NULL
        GROUP BY user_id
        ORDER BY pv DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> activeUsers(LocalDateTime from, LocalDateTime to, int limit);

    /** 页面详情: 该页面每天 PV / UV 趋势 */
    @Query(value = """
        SELECT
            strftime('%Y-%m-%d', enter_at / 1000, 'unixepoch', 'localtime') AS day,
            COUNT(*) AS pv,
            COUNT(DISTINCT session_id) AS uv
        FROM t_page_view
        WHERE route_name = :routeName AND enter_at >= :from AND enter_at < :to
        GROUP BY day
        ORDER BY day ASC
        """, nativeQuery = true)
    List<Object[]> pageDetailTrend(String routeName, LocalDateTime from, LocalDateTime to);

    /** 页面详情: 该页面最近的访问者(去重) */
    @Query(value = """
        SELECT DISTINCT session_id, user_id, ip, os, browser, device_type, enter_at
        FROM t_page_view
        WHERE route_name = :routeName AND enter_at >= :from AND enter_at < :to
        ORDER BY enter_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> pageDetailVisitors(String routeName, LocalDateTime from, LocalDateTime to, int limit);

    /** 页面详情: 该页面相关错误 */
    @Query(value = """
        SELECT id, error_type, error_message, filename, lineno, occur_at
        FROM t_page_error
        WHERE route_name = :routeName AND occur_at >= :from AND occur_at < :to
        ORDER BY occur_at DESC
        LIMIT 20
        """, nativeQuery = true)
    List<Object[]> pageDetailErrors(String routeName, LocalDateTime from, LocalDateTime to);

    /** 用户旅程样本: 最近的 N 个 session 旅程(每 session 第一条) */
    @Query(value = """
        SELECT DISTINCT session_id
        FROM t_page_view
        WHERE enter_at >= :from
        ORDER BY session_id DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<String> recentSessionIds(LocalDateTime from, int limit);

    /** 错误率: 错误数 / PV */
    @Query(value = """
        SELECT COUNT(*) FROM t_page_error
        WHERE occur_at >= :from AND occur_at < :to
        """, nativeQuery = true)
    long errorCount(LocalDateTime from, LocalDateTime to);
}