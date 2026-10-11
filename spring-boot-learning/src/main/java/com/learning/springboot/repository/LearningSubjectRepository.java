package com.learning.springboot.repository;

import com.learning.springboot.entity.LearningSubject;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 学习科目(语言) Repository
 */
public interface LearningSubjectRepository extends JpaRepository<LearningSubject, Long> {

    /** 全部科目,按排序号升序 */
    List<LearningSubject> findAllByOrderBySortNoAsc();

    /** 启用的科目,按排序号升序(学习中心/看板维度) */
    List<LearningSubject> findByEnabledTrueOrderBySortNoAsc();

    /** 按编码查找 */
    Optional<LearningSubject> findByCode(String code);

    /** 编码是否已存在 */
    boolean existsByCode(String code);

    boolean existsByCodeAndIdNot(String code, Long id);
}
