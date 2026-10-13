package com.learning.springboot.repository;

import com.learning.springboot.entity.WikiArticle;
import org.springframework.data.jpa.repository.JpaRepository;

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

    long countByUserId(Long userId);

    void deleteByDirectoryId(Long directoryId);

    void deleteByUserId(Long userId);
}
