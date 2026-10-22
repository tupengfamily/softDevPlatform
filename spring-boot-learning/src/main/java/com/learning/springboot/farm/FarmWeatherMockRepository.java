package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FarmWeatherMockRepository extends JpaRepository<FarmWeatherMock, Long> {

    List<FarmWeatherMock> findByUserIdOrderByFromDate(Long userId);

    @Modifying
    @Query("delete from FarmWeatherMock m where m.userId = :userId")
    int deleteByUserId(@Param("userId") Long userId);
}
