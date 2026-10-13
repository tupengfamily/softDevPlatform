package com.learning.springboot.repository;

import com.learning.springboot.entity.UserFavorite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 个人收藏 Repository
 */
public interface UserFavoriteRepository extends JpaRepository<UserFavorite, Long> {

    List<UserFavorite> findByUserIdOrderBySortNoAscIdAsc(Long userId);

    Optional<UserFavorite> findByIdAndUserId(Long id, Long userId);
}
