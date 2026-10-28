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

    /**
     * 某用户做过的施肥次数（2026-10-28 P2，任务链「完成一次追肥」的判定依据）。
     *
     * <p>🔴 <b>为什么单独一个方法而不是在 service 里 filter</b>：
     * 施肥动作有 4 种（基肥/通用肥/分蘖肥/穗肥），全量拉历史再过滤是全表扫描。
     * 而这个判定每次进农场都要跑一次。
     */
    long countByUserIdAndTypeIn(Long userId, List<FarmAction.Type> types);

    /**
     * 删除某用户的全部农事记录（测试清理用）。
     *
     * <p>🔴 <b>为什么需要它</b>：P2 的测试刻意<b>不加类级 {@code @Transactional}</b> ——
     * 「实操即课时」的进度写入排在农事事务提交之后，
     * 跑在大事务里落库根本不会发生，断言就变成在测假象。
     * 代价是测试之间数据不会自动回滚，只能手工清理。
     */
    void deleteByUserId(Long userId);
}
