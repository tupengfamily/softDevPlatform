package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 单局游戏得分记录(每次「游戏结束 / 主动重开」写一条)
 * <p>
 * 与 {@link GameProgress} 的分工:
 * <ul>
 *   <li>t_game_score: 已结束对局的历史流水, 用于「最近十次」与排行榜(取每人最高分)</li>
 *   <li>t_game_progress: 每个用户每款游戏只保留一份进行中存档, 用于「下次进入继续」</li>
 * </ul>
 * 排行榜按 user_id + MAX(score) 聚合, 所以同一人可以有多条记录但只取最高分进榜。
 */
@Entity
@Table(name = "t_game_score", indexes = {
        @Index(name = "idx_game_score_user", columnList = "user_id,game_code"),
        @Index(name = "idx_game_score_code", columnList = "game_code")
})
public class GameScore {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 游戏标识(目前只有 2048) */
    @Column(name = "game_code", nullable = false, length = 32)
    private String gameCode;

    /** 本局得分 */
    @Column(nullable = false)
    private Integer score = 0;

    /** 本局达到的最大方块(2/4/8...2048+) */
    @Column(name = "max_tile", nullable = false)
    private Integer maxTile = 0;

    /** 移动步数 */
    @Column(nullable = false)
    private Integer moves = 0;

    /** 本局耗时(秒) */
    @Column(name = "play_seconds", nullable = false)
    private Integer playSeconds = 0;

    /** 结束原因: 1=无路可走 2=达成 2048 后主动结束 3=主动新开一局 */
    @Column(nullable = false)
    private Integer finished = 1;

    /**
     * 游戏特定扩展信息 (JSON 字符串).
     * 不同游戏的额外指标:
     *   - 2048: { bestStreak, undoCount, timeOver200, ... }
     *   - 五子棋: { mode: pvp/pve/online, opponent, result: win/lose/draw, moves }
     *   - 猜数字: { length, attempts, result: win/lose }
     *   - 数独: { difficulty, mistakes, hintsUsed, result: solved/abandoned }
     *   - 一笔画完: { difficulty, levelId, levelTitle, hintsUsed }
     */
    @Column(name = "extra_json", columnDefinition = "TEXT")
    private String extraJson;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    public void prePersist() {
        if (this.createTime == null) this.createTime = LocalDateTime.now();
        if (this.score == null) this.score = 0;
        if (this.maxTile == null) this.maxTile = 0;
        if (this.moves == null) this.moves = 0;
        if (this.playSeconds == null) this.playSeconds = 0;
        if (this.finished == null) this.finished = 1;
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getGameCode() { return gameCode; }
    public void setGameCode(String gameCode) { this.gameCode = gameCode; }

    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }

    public Integer getMaxTile() { return maxTile; }
    public void setMaxTile(Integer maxTile) { this.maxTile = maxTile; }

    public Integer getMoves() { return moves; }
    public void setMoves(Integer moves) { this.moves = moves; }

    public Integer getPlaySeconds() { return playSeconds; }
    public void setPlaySeconds(Integer playSeconds) { this.playSeconds = playSeconds; }

    public Integer getFinished() { return finished; }
    public void setFinished(Integer finished) { this.finished = finished; }

    public String getExtraJson() { return extraJson; }
    public void setExtraJson(String extraJson) { this.extraJson = extraJson; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}
