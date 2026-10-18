package com.learning.springboot.repository;

import com.learning.springboot.entity.LessonProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 学习进度 Repository
 */
public interface LessonProgressRepository extends JpaRepository<LessonProgress, Long> {

    /** 某用户某课时的进度记录 */
    Optional<LessonProgress> findByUserIdAndLessonId(Long userId, Long lessonId);

    /** 某用户是否已学完某课时 */
    boolean existsByUserIdAndLessonId(Long userId, Long lessonId);

    /** 某用户全部进度(用于统计) */
    List<LessonProgress> findByUserId(Long userId);

    /** 某用户全部进度,按完成时间倒序(用于"最近学习") */
    List<LessonProgress> findByUserIdOrderByLearnedAtDesc(Long userId);

    /** 取消学习标记 */
    void deleteByUserIdAndLessonId(Long userId, Long lessonId);

    /** 批量删除某些课时的全部进度(删除章节时级联清理) */
    void deleteByLessonIdIn(Collection<Long> lessonIds);

    /**
     * 全局最近 N 条学习完成记录(只取已完成的)
     * <p>
     * 配合 join lesson + user 在 service 层做,避免 N+1
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT p FROM LessonProgress p WHERE p.learnedAt IS NOT NULL ORDER BY p.learnedAt DESC"
    )
    List<LessonProgress> findTop100ByLearnedAtNotNullOrderByLearnedAtDesc();

    /**
     * 限定到某批课时内的全部进度(看板统计用,避免全表加载)
     * <p>
     * 配合 idx_lesson 索引,即使总进度表很大,按 lesson_id IN (...) 也能快速命中。
     */
    List<LessonProgress> findByLessonIdIn(Collection<Long> lessonIds);

    /** 某用户限定到某批课时的进度 */
    List<LessonProgress> findByUserIdAndLessonIdIn(Long userId, Collection<Long> lessonIds);
}
