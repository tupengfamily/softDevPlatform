package com.learning.springboot.repository;

import com.learning.springboot.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    Optional<ApiKey> findByKeyCode(String keyCode);

    boolean existsByKeyCode(String keyCode);

    /**
     * 只更新「最近使用时间」这一个字段。
     *
     * <p><b>为什么不用 {@code repo.save(entity)}</b>: save 会把整行所有列写回去
     * (create_time / display_name / enabled / secret_value ... 全带上), 在 SQLite 单写模型下
     * 极易撞写锁, 而且有把并发修改覆盖掉的风险(读-改-写竞态)。
     * 这里用一条只碰 last_used_time 的 UPDATE, 写面最小、冲突面也最小。
     */
    @Modifying
    @Query("update ApiKey k set k.lastUsedTime = :t where k.id = :id")
    int touchLastUsed(@Param("id") Long id, @Param("t") LocalDateTime t);
}
