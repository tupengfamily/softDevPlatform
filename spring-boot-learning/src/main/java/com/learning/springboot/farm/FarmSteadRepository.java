package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FarmSteadRepository extends JpaRepository<FarmStead, Long> {

    List<FarmStead> findByUserIdOrderByIdAsc(Long userId);

    Optional<FarmStead> findByUserIdAndAdcode(Long userId, Integer adcode);

    Optional<FarmStead> findByIdAndUserId(Long id, Long userId);

    long countByUserId(Long userId);
}
