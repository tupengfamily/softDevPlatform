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

    /**
     * 指定 subject 内全部课时(已按 chapterNo, lessonNo 排序;只含未归档章节)
     * <p>
     * 跨章节的 prev/next 导航用 — 替代 N+1 的循环按 chapter 取 lesson
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT l FROM LearningLesson l, LearningChapter c " +
        "WHERE l.chapterId = c.id AND c.subjectId = :subjectId AND c.archived = false " +
        "ORDER BY c.chapterNo ASC, l.lessonNo ASC"
    )
    List<LearningLesson> findBySubjectIdOrdered(Long subjectId);
}
