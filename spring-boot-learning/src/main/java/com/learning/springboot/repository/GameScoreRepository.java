package com.learning.springboot.repository;

import com.learning.springboot.entity.GameScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 游戏单局得分仓库
 * <p>
 * 排行榜需要按 user 聚合最大分并 join 用户表, 那种多表聚合交给 GameService 用 JdbcTemplate 做
 * (JPA @Query 多字段聚合 + Object[] 在 SQLite 下容易踩坑)。
 */
public interface GameScoreRepository extends JpaRepository<GameScore, Long> {

    /** 最近 10 局(倒序), 用于「最近十次的得分纪录」 */
    List<GameScore> findTop10ByUserIdAndGameCodeOrderByIdDesc(Long userId, String gameCode);

    /** 最近 N 局(倒序, 上限由调用方控制;用于「对局记录」页面,默认 100) */
    List<GameScore> findTop100ByUserIdAndGameCodeOrderByIdDesc(Long userId, String gameCode);

    long countByUserIdAndGameCode(Long userId, String gameCode);

    /** 个人历史最高分(单聚合, 无记录时 coalesce 返回 0) */
    @Query("select coalesce(max(s.score), 0) from GameScore s where s.userId = :userId and s.gameCode = :gameCode")
    int findBestScore(@Param("userId") Long userId, @Param("gameCode") String gameCode);

    void deleteByUserIdAndGameCode(Long userId, String gameCode);
}
