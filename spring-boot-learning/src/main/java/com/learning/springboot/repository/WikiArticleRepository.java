package com.learning.springboot.repository;

import com.learning.springboot.entity.WikiArticle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/**
 * Wiki 文章 Repository
 */
public interface WikiArticleRepository extends JpaRepository<WikiArticle, Long> {

    List<WikiArticle> findByUserIdOrderBySortNoAscIdAsc(Long userId);

    List<WikiArticle> findByDirectoryIdOrderBySortNoAscIdAsc(Long directoryId);

    Optional<WikiArticle> findByIdAndUserId(Long id, Long userId);

    Optional<WikiArticle> findByShareToken(String shareToken);

    /** 浏览计数原子自增(并发安全;clear 后调用方需重查实体) */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE WikiArticle a SET a.viewCount = COALESCE(a.viewCount,0) + 1 WHERE a.id = :id")
    void incrementViewCount(@Param("id") Long id);

    long countByUserId(Long userId);

    void deleteByDirectoryId(Long directoryId);

    void deleteByUserId(Long userId);
}
