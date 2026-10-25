package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 农资库存仓库（S25）。 */
public interface FarmStockRepository extends JpaRepository<FarmStock, Long> {

    /** 该用户的全部农资库存（按剩余量倒序，用完的排最后）。 */
    List<FarmStock> findByUserIdOrderByQuantityDescIdAsc(Long userId);

    /** 查某种农资的库存行（可能不存在）。 */
    Optional<FarmStock> findByUserIdAndItemId(Long userId, String itemId);

    /** 查某种农资的全部行（可能分属不同农场）。 */
    List<FarmStock> findByUserIdAndItemIdOrderByIdAsc(Long userId, String itemId);

    /** 某农场的农资库存。 */
    List<FarmStock> findByUserIdAndFarmIdOrderByQuantityDescIdAsc(Long userId, Long farmId);
}
