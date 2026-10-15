package com.learning.springboot.repository;

import com.learning.springboot.entity.MyDocFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 个人文档文件 Repository
 */
public interface MyDocFileRepository extends JpaRepository<MyDocFile, Long> {

    List<MyDocFile> findByUserIdOrderBySortNoAscIdAsc(Long userId);

    List<MyDocFile> findByUserIdAndDirectoryIdOrderBySortNoAscIdAsc(Long userId, Long directoryId);

    List<MyDocFile> findByUserIdAndDirectoryIdIsNullOrderBySortNoAscIdAsc(Long userId);

    Optional<MyDocFile> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);

    long countByDirectoryId(Long directoryId);

    void deleteByDirectoryId(Long directoryId);

    void deleteByUserId(Long userId);
}
