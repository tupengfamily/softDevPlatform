package com.learning.springboot.repository;

import com.learning.springboot.entity.MyDocDirectory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 个人文档目录 Repository
 */
public interface MyDocDirectoryRepository extends JpaRepository<MyDocDirectory, Long> {

    /** 用户全部目录(按排序序号) */
    List<MyDocDirectory> findByUserIdOrderBySortNoAscIdAsc(Long userId);

    /** 某父目录下的直接子目录(parentId 为 null 时用 findByUserIdAndParentIdIsNull...) */
    List<MyDocDirectory> findByUserIdAndParentIdOrderBySortNoAscIdAsc(Long userId, Long parentId);

    List<MyDocDirectory> findByUserIdAndParentIdIsNullOrderBySortNoAscIdAsc(Long userId);

    Optional<MyDocDirectory> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    void deleteByUserId(Long userId);
}
