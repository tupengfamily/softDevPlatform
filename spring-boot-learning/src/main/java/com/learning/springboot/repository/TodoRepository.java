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

    /**
     * 按稳定引用键查（2026-10-28 P2，系统预置任务的幂等入口）。
     *
     * <p>🔴 <b>为什么查得到「唯一一条」</b>：系统预置任务用
     * {@code (user_id, task_type, ref_key)} 三元组保证唯一，
     * 写入前必须先查这条 —— 直接 insert 会插出重复任务。
     *
     * <p>玩家自建待办的 {@code refKey} 为 null，不会被这个查询命中。
     */
    Optional<Todo> findFirstByUserIdAndTaskTypeAndRefKey(Long userId, String taskType, String refKey);

    /** 某用户的全部系统预置任务（按创建顺序）。 */
    List<Todo> findByUserIdAndTaskTypeIsNotNullOrderByIdAsc(Long userId);
}
