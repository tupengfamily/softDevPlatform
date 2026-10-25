package com.learning.springboot.farm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 农场资金账户仓库（2026-10-23 需求 R · 商店）。
 */
@Repository
public interface FarmWalletRepository extends JpaRepository<FarmWallet, Long> {

    Optional<FarmWallet> findByUserId(Long userId);
}
