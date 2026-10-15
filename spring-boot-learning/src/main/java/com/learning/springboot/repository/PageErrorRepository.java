package com.learning.springboot.repository;

import com.learning.springboot.entity.PageError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.List;

public interface PageErrorRepository extends JpaRepository<PageError, Long> {

    /** 错误日志: 区间内最近的 N 条 */
    @Query(value = """
        SELECT * FROM t_page_error
        WHERE occur_at >= :from AND occur_at < :to
        ORDER BY occur_at DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<PageError> recentErrors(LocalDateTime from, LocalDateTime to, int limit);

    /** 错误按类型分组(用于错误面板饼图) */
    @Query(value = """
        SELECT error_type, COUNT(*) AS cnt
        FROM t_page_error
        WHERE occur_at >= :from AND occur_at < :to
        GROUP BY error_type
        ORDER BY cnt DESC
        """, nativeQuery = true)
    List<Object[]> errorTypeDistribution(LocalDateTime from, LocalDateTime to);
}