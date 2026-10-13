package com.learning.springboot.repository;

import com.learning.springboot.entity.Todo;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 个人待办仓库(仅本人数据:查询一律带 userId)
 */
public interface TodoRepository extends JpaRepository<Todo, Long> {

    List<Todo> findByUserIdOrderByCreateTimeDesc(Long userId);

    Optional<Todo> findByIdAndUserId(Long id, Long userId);
}
