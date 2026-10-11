package com.learning.springboot.repository;

import com.learning.springboot.entity.LearningLesson;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 课时 Repository
 */
public interface LearningLessonRepository extends JpaRepository<LearningLesson, Long> {

    /** 某章节下全部课时 */
    List<LearningLesson> findByChapterIdOrderByLessonNoAsc(Long chapterId);

    /** 全部课时,按"章节顺序 + 课时序号"排序(用于上一课/下一课导航) */
    List<LearningLesson> findAllByOrderByChapterIdAscLessonNoAsc();

    /** 某章节课时数 */
    long countByChapterId(Long chapterId);

    /** 章节下的课时详情 */
    Optional<LearningLesson> findByIdAndChapterId(Long id, Long chapterId);

    /** 删除章节时级联删除课时 */
    void deleteByChapterId(Long chapterId);
}
