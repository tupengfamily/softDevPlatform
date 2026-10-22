package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface FarmActionRepository extends JpaRepository<FarmAction, Long> {

    /** 拉取某用户在 [from, to] 区间内做过的所有农事（跨全部地块）。 */
    List<FarmAction> findByUserIdAndDateBetweenOrderByDate(Long userId, LocalDate from, LocalDate to);

    /** 单块地的农事。多地块下回放必须只取本块地的操作。 */
    List<FarmAction> findByUserIdAndPlotIdAndDateBetweenOrderByDate(
            Long userId, Long plotId, LocalDate from, LocalDate to);

    List<FarmAction> findByUserIdAndPlotIdOrderByDateDescIdDesc(Long userId, Long plotId);

    /** 拉取该用户做过的所有农事（按时间倒序），给 UI 展示"我的操作历史"。 */
    List<FarmAction> findByUserIdOrderByDateDescIdDesc(Long userId);
}
