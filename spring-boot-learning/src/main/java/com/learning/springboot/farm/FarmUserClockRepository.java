package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 用户级世界时钟仓储（2026-10-26）。
 *
 * <p>见 {@link FarmUserClock} 的类注释：这个表承载「用户推进了多少天」，
 * 是**用户级**日期口径（仓库老化 / 天气取段 / 推荐播期）的唯一来源。
 */
public interface FarmUserClockRepository extends JpaRepository<FarmUserClock, Long> {

    Optional<FarmUserClock> findByUserId(Long userId);
}
