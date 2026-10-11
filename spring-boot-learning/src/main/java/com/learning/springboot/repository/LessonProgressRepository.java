package com.learning.springboot.repository;

import com.learning.springboot.entity.LessonProgress;
import org.springframework.data.jpa.repository.JpaRepository;

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
    void deleteByLessonIdIn(java.util.Collection<Long> lessonIds);
}
