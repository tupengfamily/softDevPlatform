package com.learning.springboot.repository;

import com.learning.springboot.entity.PagePerf;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface PagePerfRepository extends JpaRepository<PagePerf, Long> {

    /** 性能平均: 区间内 FCP / LCP / FID / TTFB 平均值 */
    @Query(value = """
        SELECT
            COALESCE(AVG(fcp), 0) AS avg_fcp,
            COALESCE(AVG(lcp), 0) AS avg_lcp,
            COALESCE(AVG(fid), 0) AS avg_fid,
            COALESCE(AVG(cls), 0) AS avg_cls,
            COALESCE(AVG(ttfb), 0) AS avg_ttfb,
            COALESCE(AVG(dom_interactive), 0) AS avg_dom,
            COALESCE(AVG(load_complete), 0) AS avg_load,
            COUNT(*) AS sample_count
        FROM t_page_perf
        WHERE sample_at >= :from AND sample_at < :to
        """, nativeQuery = true)
    java.util.List<Object[]> perfAverages(LocalDateTime from, LocalDateTime to);

    /** 性能按页面分组: 各路由平均 LCP / FCP / 错误率 */
    @Query(value = """
        SELECT
            route_name,
            url,
            COALESCE(AVG(fcp), 0) AS avg_fcp,
            COALESCE(AVG(lcp), 0) AS avg_lcp,
            COALESCE(AVG(cls), 0) AS avg_cls,
            COALESCE(AVG(ttfb), 0) AS avg_ttfb,
            COUNT(*) AS sample_count
        FROM t_page_perf
        WHERE sample_at >= :from AND sample_at < :to AND route_name IS NOT NULL
        GROUP BY route_name, url
        ORDER BY avg_lcp DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> perfByRoute(LocalDateTime from, LocalDateTime to, int limit);
}