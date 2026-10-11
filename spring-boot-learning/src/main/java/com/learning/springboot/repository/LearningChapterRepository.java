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
}
