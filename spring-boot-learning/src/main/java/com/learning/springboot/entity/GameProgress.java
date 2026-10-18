package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 游戏进度存档(每个用户每款游戏只有一份, 用于「下次进入继续」)
 * <p>
 * 采用 upsert 语义: 有则更新, 无则插入。userId + gameCode 唯一由 service 层保证
 * (JPA + SQLite 在复合唯一约束上有静默问题, 不用 @UniqueConstraint)。
 */
@Entity
@Table(name = "t_game_progress", indexes = {
        @Index(name = "idx_game_progress_user", columnList = "user_id,game_code")
})
public class GameProgress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "game_code", nullable = false, length = 32)
    private String gameCode;

    /** 棋盘快照: 4x4 二维数组的 JSON, 例 [[2,4,0,0],...] */
    @Lob
    @Column(name = "board_json", nullable = false)
    private String boardJson;

    @Column(nullable = false)
    private Integer score = 0;

    @Column(name = "max_tile", nullable = false)
    private Integer maxTile = 0;

    @Column(nullable = false)
    private Integer moves = 0;

    @Column(name = "play_seconds", nullable = false)
    private Integer playSeconds = 0;

    /** 1=已死局(进度作废, 仅留档) 0=进行中 */
    @Column(nullable = false)
    private Integer finished = 0;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createTime == null) this.createTime = now;
        this.updateTime = now;
        if (this.score == null) this.score = 0;
        if (this.maxTile == null) this.maxTile = 0;
        if (this.moves == null) this.moves = 0;
        if (this.playSeconds == null) this.playSeconds = 0;
        if (this.finished == null) this.finished = 0;
    }

    @PreUpdate
    public void preUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    // ============== Getter / Setter ==============

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getGameCode() { return gameCode; }
    public void setGameCode(String gameCode) { this.gameCode = gameCode; }

    public String getBoardJson() { return boardJson; }
    public void setBoardJson(String boardJson) { this.boardJson = boardJson; }

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

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
