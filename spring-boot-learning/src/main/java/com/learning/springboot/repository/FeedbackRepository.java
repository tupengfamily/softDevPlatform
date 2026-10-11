package com.learning.springboot.repository;

import com.learning.springboot.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

/**
 * 用户反馈 Repository
 */
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

    /** 某用户的全部反馈(最新在前) */
    List<Feedback> findByUserIdOrderByCreateTimeDescIdDesc(Long userId);

    /** 管理端分页: 可选按类型/状态过滤 */
    @Query("SELECT f FROM Feedback f WHERE (:type IS NULL OR f.type = :type) AND (:status IS NULL OR f.status = :status)")
    Page<Feedback> pageQuery(String type, String status, Pageable pageable);

    /** 评分平均分(无评分时返回 null) */
    @Query("SELECT AVG(f.score) FROM Feedback f WHERE f.type = 'RATING'")
    Double averageScore();

    /** 评分数 */
    long countByType(String type);
}
