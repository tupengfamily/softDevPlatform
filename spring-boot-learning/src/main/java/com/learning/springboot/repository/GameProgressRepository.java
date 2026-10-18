package com.learning.springboot.repository;

import com.learning.springboot.entity.GameProgress;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 游戏进度存档仓库(每用户每游戏唯一一份, 由 service 层保证不重复)
 */
public interface GameProgressRepository extends JpaRepository<GameProgress, Long> {

    Optional<GameProgress> findByUserIdAndGameCode(Long userId, String gameCode);

    void deleteByUserIdAndGameCode(Long userId, String gameCode);
}
