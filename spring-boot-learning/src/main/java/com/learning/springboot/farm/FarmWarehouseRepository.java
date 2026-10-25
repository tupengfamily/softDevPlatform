package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface FarmWarehouseRepository extends JpaRepository<FarmWarehouse, Long> {

    /** 某用户全部在库批次（先入先出排序，便于按入库先后变卖）。 */
    List<FarmWarehouse> findByUserIdAndStatusOrderByStoredDateAscIdAsc(Long userId, FarmWarehouse.Status status);

    /** 某用户全部批次（含已变卖/已丢弃，用于统计总资产）。 */
    List<FarmWarehouse> findByUserIdOrderByStoredDateAscIdAsc(Long userId);

    /**
     * 某用户某作物的在库总重量（kg）。
     *
     * <p>供给压力模型用：囤得越多，同一作物价格越低。
     */
    @Query("select coalesce(sum(w.quantity), 0) from FarmWarehouse w "
            + "where w.userId = :userId and w.cropId = :cropId and w.status = com.learning.springboot.farm.FarmWarehouse.Status.STORED")
    Double totalKg(@Param("userId") Long userId, @Param("cropId") String cropId);

    /** 某用户某作物在库的总重量（按农业区分开，地区价差用）。 */
    @Query("select coalesce(sum(w.quantity), 0) from FarmWarehouse w "
            + "where w.userId = :userId and w.cropId = :cropId and w.zoneId = :zoneId "
            + "and w.status = com.learning.springboot.farm.FarmWarehouse.Status.STORED")
    Double totalKgByZone(@Param("userId") Long userId, @Param("cropId") String cropId,
                         @Param("zoneId") String zoneId);
}
