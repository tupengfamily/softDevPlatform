package com.learning.springboot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 五子棋联机对战房间
 * <p>
 * 一局一房间, 用 6 位短码 roomCode 作为分享标识(大基数下冲突概率极低)。
 * <p>
 * 字段约定:
 * <ul>
 *   <li>status: 0=等待第二位玩家加入, 1=对局进行中, 2=已结束</li>
 *   <li>winnerColor: 1=黑, 2=白, 0=和棋/未结束</li>
 *   <li>blackUserId / whiteUserId: 落子方, 加入顺序与 color 一致(先入者执黑)</li>
 *   <li>currentColor: 当前应落子方, 服务端权威, 客户端只读</li>
 * </ul>
 */
@Entity
@Table(name = "t_gomoku_room", indexes = {
        @Index(name = "idx_gomoku_room_code", columnList = "room_code", unique = true),
        @Index(name = "idx_gomoku_room_status", columnList = "status")
})
public class GomokuRoom {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 6 位大写字母+数字短码(去除易混淆的 0/O/1/I), 用于分享加入 */
    @Column(name = "room_code", nullable = false, unique = true, length = 8)
    private String roomCode;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    /** 棋盘大小: 固定 15(支持 13/15/19, 此版本固定 15) */
    @Column(name = "board_size", nullable = false)
    private Integer boardSize = 15;

    /** 0=空, 1=黑, 2=白(0=无子) */
    @Column(name = "status", nullable = false)
    private Integer status = 0;

    @Column(name = "black_user_id")
    private Long blackUserId;

    @Column(name = "white_user_id")
    private Long whiteUserId;

    /** 当前应落子方: 1=黑, 2=白(对局未开始/已结束则为 0) */
    @Column(name = "current_color", nullable = false)
    private Integer currentColor = 0;

    /** 已落子数(对局进度: count 颗棋子落完后棋盘布局 = boardJson) */
    @Column(name = "move_count", nullable = false)
    private Integer moveCount = 0;

    /** 棋盘快照: "[r,c]" 字符串列表 JSON, 例 [["7","7"],["8","7"]]; 用 JSON 而非二维数组, 节省空间 */
    @Column(name = "board_json", nullable = false, length = 10000)
    private String boardJson = "[]";

    /** 胜方: 1=黑, 2=白, 0=和棋/未结束 */
    @Column(name = "winner_color", nullable = false)
    private Integer winnerColor = 0;

    /** 胜利连子的 5 个点位 JSON: [[r,c],[r,c],...] */
    @Column(name = "win_line_json", length = 200)
    private String winLineJson;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    public void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.createTime == null) this.createTime = now;
        this.updateTime = now;
        if (this.boardSize == null) this.boardSize = 15;
        if (this.status == null) this.status = 0;
        if (this.moveCount == null) this.moveCount = 0;
        if (this.currentColor == null) this.currentColor = 0;
        if (this.winnerColor == null) this.winnerColor = 0;
        if (this.boardJson == null) this.boardJson = "[]";
    }

    @PreUpdate
    public void preUpdate() { this.updateTime = LocalDateTime.now(); }

    // ===== Getter / Setter =====

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRoomCode() { return roomCode; }
    public void setRoomCode(String roomCode) { this.roomCode = roomCode; }

    public Long getCreatorId() { return creatorId; }
    public void setCreatorId(Long creatorId) { this.creatorId = creatorId; }

    public Integer getBoardSize() { return boardSize; }
    public void setBoardSize(Integer boardSize) { this.boardSize = boardSize; }

    public Integer getStatus() { return status; }
    public void setStatus(Integer status) { this.status = status; }

    public Long getBlackUserId() { return blackUserId; }
    public void setBlackUserId(Long blackUserId) { this.blackUserId = blackUserId; }

    public Long getWhiteUserId() { return whiteUserId; }
    public void setWhiteUserId(Long whiteUserId) { this.whiteUserId = whiteUserId; }

    public Integer getCurrentColor() { return currentColor; }
    public void setCurrentColor(Integer currentColor) { this.currentColor = currentColor; }

    public Integer getMoveCount() { return moveCount; }
    public void setMoveMoveCount(Integer moveCount) { this.moveCount = moveCount; }
    public void setMoveCount(Integer moveCount) { this.moveCount = moveCount; }

    public String getBoardJson() { return boardJson; }
    public void setBoardJson(String boardJson) { this.boardJson = boardJson; }

    public Integer getWinnerColor() { return winnerColor; }
    public void setWinnerColor(Integer winnerColor) { this.winnerColor = winnerColor; }

    public String getWinLineJson() { return winLineJson; }
    public void setWinLineJson(String winLineJson) { this.winLineJson = winLineJson; }

    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }

    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}