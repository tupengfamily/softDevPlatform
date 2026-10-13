package com.learning.springboot.repository;

import com.learning.springboot.entity.WikiDirectory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Wiki 目录 Repository
 */
public interface WikiDirectoryRepository extends JpaRepository<WikiDirectory, Long> {

    List<WikiDirectory> findByUserIdOrderBySortNoAscIdAsc(Long userId);

    Optional<WikiDirectory> findByIdAndUserId(Long id, Long userId);

    void deleteByUserId(Long userId);
}
