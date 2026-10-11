package com.learning.springboot.repository;

import com.learning.springboot.entity.LearningChapter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 章节 Repository
 */
public interface LearningChapterRepository extends JpaRepository<LearningChapter, Long> {

    /** 全部章节,按章节序号升序 */
    List<LearningChapter> findAllByOrderByChapterNoAsc();

    /** 某阶段下的章节 */
    List<LearningChapter> findByStageNoOrderByChapterNoAsc(Integer stageNo);

    /** 某科目下的全部章节,按章节序号升序 */
    List<LearningChapter> findBySubjectIdOrderByChapterNoAsc(Long subjectId);

    /** 某科目下的章节数 */
    long countBySubjectId(Long subjectId);
}
