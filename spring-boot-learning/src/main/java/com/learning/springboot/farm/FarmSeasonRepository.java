package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface FarmSeasonRepository extends JpaRepository<FarmSeason, Long> {

    /** 某用户全部季节记录（按地块、季次排序）。 */
    List<FarmSeason> findByUserIdOrderBySlotAscSeasonNoAsc(Long userId);

    /** 某农场的全部季节记录。 */
    List<FarmSeason> findByUserIdAndFarmIdOrderBySlotAscSeasonNoAsc(Long userId, Long farmId);

    /**
     * 按<b>地块</b>批量取季节记录（2026-10-22）。
     *
     * <p><b>为什么不按 {@code farm_id} 查</b>：{@code t_farm_season.farm_id} 是
     * <b>建田那一刻的快照</b>。玩家把一块地从 A 农场转到 B 农场后，
     * 旧档案仍挂着 A 的 farm_id，而新一季挂着 B 的 farm_id——
     * 于是同一块地的历史被劈成两半，A 农场显示「上一季」、
     * B 农场显示「这一季」，两边看到的都不是完整的这块地。
     *
     * <p>档案的归属应当跟地块走：<b>地块现在在哪个农场，它的历史就属于哪个农场</b>。
     * 所以按地块 id 集合查，而不是按 farm_id 查。
     */
    List<FarmSeason> findByUserIdAndPlotIdInOrderBySlotAscSeasonNoAsc(Long userId, List<Long> plotIds);

    /** 某地块当前「在长」的那一季（同一块地最多一条 GROWING）。 */
    Optional<FarmSeason> findFirstByUserIdAndPlotIdAndStatus(Long userId, Long plotId, FarmSeason.Status status);

    /** 某地块的最大季次号（用于算下一季是第几季）。 */
    @Query("select max(s.seasonNo) from FarmSeason s where s.userId = :userId and s.plotId = :plotId")
    Integer maxSeasonNo(@Param("userId") Long userId, @Param("plotId") Long plotId);

    /** 某农场的季节记录条数（用于详情页统计）。 */
    long countByUserIdAndFarmId(Long userId, Long farmId);
}
