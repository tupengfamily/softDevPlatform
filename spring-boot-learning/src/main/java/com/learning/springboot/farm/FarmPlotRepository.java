package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FarmPlotRepository extends JpaRepository<FarmPlot, Long> {

    /** 兼容旧调用：等价于 slot 0。新代码请用 {@link #findByUserIdAndSlot}。 */
    Optional<FarmPlot> findByUserId(Long userId);

    Optional<FarmPlot> findByUserIdAndSlot(Long userId, Integer slot);

    List<FarmPlot> findByUserIdOrderBySlotAsc(Long userId);

    long countByUserId(Long userId);

    long countByUserIdAndFarmId(Long userId, Long farmId);

    List<FarmPlot> findByUserIdAndFarmIdOrderBySlotAsc(Long userId, Long farmId);
}
