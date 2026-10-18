package com.learning.springboot.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.learning.springboot.entity.GomokuRoom;
import com.learning.springboot.exception.BusinessException;
import com.learning.springboot.repository.GomokuRoomRepository;
import com.learning.springboot.service.gomoku.GomokuRules;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 五子棋房间服务
 * <p>
 * 流程: createRoom → joinRoom(对家加入, 服务端把 status 切到 1, 决定执黑执白) →
 * 双方轮替落子 placeMove → 每步服务端权威判胜负 / 切换 currentColor。
 * <p>
 * 实时推送: 暂用 HTTP 轮询(每秒一次), 不上 WebSocket。
 * stateSinceUpdateTime 字段让客户端用 If-Modified-Since 风格只读最新变化, 降低服务端压力。
 */
@Service
public class GomokuService {

    private static final SecureRandom RNG = new SecureRandom();
    /** 房码字符表: 去除易混淆 0/O/1/I/L */
    private static final String CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LEN = 6;
    /** 房间空闲超时: 10 分钟未加入自动 close */
    private static final long IDLE_TIMEOUT_MIN = 10;

    @Autowired private GomokuRoomRepository roomRepo;

    private final ObjectMapper json = new ObjectMapper();

    /** 创建房间并返回房间码 */
    @Transactional
    public Map<String, Object> createRoom(Long userId, Integer boardSize) {
        int size = boardSize == null ? 15 : boardSize;
        if (size != 15) throw new BusinessException("暂仅支持 15x15 棋盘");

        String code;
        int attempts = 0;
        do {
            code = randomCode();
            attempts++;
            if (attempts > 10) throw new BusinessException("无法分配房间码, 请重试");
        } while (roomRepo.findByRoomCode(code).isPresent());

        GomokuRoom room = new GomokuRoom();
        room.setRoomCode(code);
        room.setCreatorId(userId);
        room.setBoardSize(size);
        room.setStatus(0);
        room.setBlackUserId(userId);
        // 留 whiteUserId 为空, 等 joinRoom 写入
        room.setCurrentColor(0);
        room.setMoveCount(0);
        room.setBoardJson("[]");
        room.setWinnerColor(0);
        GomokuRoom saved = roomRepo.save(room);

        return toState(saved, userId, null);
    }

    /**
     * 加入房间: 创房者不能再进自己房(交给 joinRoom 排错);
     * 别人加入则 status=1, 决定执白; 并把 currentColor 设为黑先手
     */
    @Transactional
    public Map<String, Object> joinRoom(Long userId, String roomCode) {
        if (roomCode == null || roomCode.isBlank()) throw new BusinessException("房间码不能为空");
        GomokuRoom room = roomRepo.findByRoomCode(roomCode.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException("房间不存在"));

        if (room.getStatus() == 2) throw new BusinessException("该房间已结束");
        if (room.getCreatorId().equals(userId)) throw new BusinessException("不能加入自己创建的房间");

        if (room.getStatus() == 0) {
            // 第一位加入者 = 执白
            room.setWhiteUserId(userId);
            room.setStatus(1);
            room.setCurrentColor(GomokuRules.BLACK);   // 黑先手
        } else if (room.getStatus() == 1) {
            // 已有人在等, 但没第二位 → 抢位置进入(房主还在)
            if (room.getWhiteUserId() != null) throw new BusinessException("房间已满");
            room.setWhiteUserId(userId);
        }

        return toState(room, userId, null);
    }

    /** 落子: 服务端校验颜色 + 落子合法性 + 判定胜负 + 切换 currentColor */
    @Transactional
    public Map<String, Object> placeMove(Long userId, String roomCode, int r, int c) {
        if (roomCode == null || roomCode.isBlank()) throw new BusinessException("房间码不能为空");
        GomokuRoom room = roomRepo.findByRoomCode(roomCode.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException("房间不存在"));

        if (room.getStatus() != 1) throw new BusinessException("对局未在进行中");
        if (room.getCurrentColor() == 0) throw new BusinessException("对局尚未开始");

        int myColor = colorOf(room, userId);
        if (myColor != room.getCurrentColor()) throw new BusinessException("当前不是你落子");

        // 把 boardJson 还原成 2D 数组, 落子, 再写回
        int[][] board = readBoard(room);
        if (!GomokuRules.isLegalMove(board, r, c)) throw new BusinessException("该位置已有子或越界");

        board[r][c] = myColor;
        int moveCount = (room.getMoveCount() == null ? 0 : room.getMoveCount()) + 1;
        room.setMoveCount(moveCount);
        room.setBoardJson(writeBoard(board));
        room.setUpdateTime(LocalDateTime.now());

        int[][] winLine = GomokuRules.findWinLine(board, r, c);
        int winLineVersion = room.getMoveCount(); // 自增, 便于前端知道「本次落子产生了胜负」
        if (winLine != null) {
            room.setStatus(2);
            room.setWinnerColor(myColor);
            room.setWinLineJson(winLineToJson(winLine));
            room.setCurrentColor(0);
        } else if (GomokuRules.isFull(board)) {
            room.setStatus(2);
            room.setWinnerColor(0);
            room.setCurrentColor(0);
        } else {
            room.setCurrentColor(opponentOf(myColor));
        }

        return toState(room, userId, winLineVersion);
    }

    /** 主动离开房间(用于 dispose / 关闭页面) */
    @Transactional
    public Map<String, Object> leaveRoom(Long userId, String roomCode) {
        if (roomCode == null || roomCode.isBlank()) return Map.of("ok", true);
        GomokuRoom room = roomRepo.findByRoomCode(roomCode.trim().toUpperCase()).orElse(null);
        if (room == null) return Map.of("ok", true);
        if (room.getStatus() == 1 && userId.equals(room.getCreatorId()) && userId.equals(room.getBlackUserId())) {
            // 房主离开 → 关闭房间
            room.setStatus(2);
            room.setWinnerColor(0);
            room.setUpdateTime(LocalDateTime.now());
        }
        return Map.of("ok", true);
    }

    /** 查询房间状态(轮询入口) */
    @Transactional(readOnly = true)
    public Map<String, Object> state(String roomCode, Long userId, Integer sinceVersion) {
        if (roomCode == null || roomCode.isBlank()) throw new BusinessException("房间码不能为空");
        GomokuRoom room = roomRepo.findByRoomCode(roomCode.trim().toUpperCase())
                .orElseThrow(() -> new BusinessException("房间不存在"));
        return toState(room, userId, sinceVersion);
    }

    /** 大厅列表: 等待中的房间(供加入) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listWaiting() {
        return roomRepo.findByStatusInOrderByIdDesc(
                java.util.List.of(0),
                org.springframework.data.domain.PageRequest.of(0, 30)
        ).stream().map(r -> toState(r, null, null)).toList();
    }

    /** 定期清理超时房间(可挂定时任务, 本期先手动调用) */
    @Transactional
    public int sweepTimeoutRooms() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(IDLE_TIMEOUT_MIN);
        int n = 0;
        for (GomokuRoom r : roomRepo.findByStatusInOrderByIdDesc(
                java.util.List.of(0, 1),
                org.springframework.data.domain.PageRequest.of(0, 200))) {
            if (r.getUpdateTime() != null && r.getUpdateTime().isBefore(cutoff)) {
                r.setStatus(2);
                r.setWinnerColor(0);
                n++;
            }
        }
        return n;
    }

    // ============ 内部工具 ============

    private int colorOf(GomokuRoom room, Long userId) {
        if (userId == null) return 0;
        if (userId.equals(room.getBlackUserId())) return GomokuRules.BLACK;
        if (userId.equals(room.getWhiteUserId())) return GomokuRules.WHITE;
        throw new BusinessException("你不是该房间的玩家");
    }

    private int opponentOf(int color) {
        return color == GomokuRules.BLACK ? GomokuRules.WHITE : GomokuRules.BLACK;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LEN);
        for (int i = 0; i < CODE_LEN; i++) sb.append(CODE_CHARS.charAt(RNG.nextInt(CODE_CHARS.length())));
        return sb.toString();
    }

    private int[][] readBoard(GomokuRoom room) {
        int size = room.getBoardSize() == null ? 15 : room.getBoardSize();
        int[][] board = GomokuRules.emptyBoard(size);
        try {
            List<int[]> moves = json.readValue(room.getBoardJson(), new TypeReference<List<int[]>>() {});
            for (int[] p : moves) {
                if (p != null && p.length >= 3
                        && GomokuRules.inBounds(board, p[0], p[1])
                        && p[2] == GomokuRules.BLACK || p[2] == GomokuRules.WHITE) {
                    board[p[0]][p[1]] = p[2];
                }
            }
        } catch (Exception e) {
            // 解析失败视作空棋盘(避免脏数据卡死房间)
        }
        return board;
    }

    private String writeBoard(int[][] board) {
        List<int[]> moves = new ArrayList<>();
        for (int r = 0; r < board.length; r++) {
            for (int c = 0; c < board[0].length; c++) {
                if (board[r][c] != GomokuRules.EMPTY) moves.add(new int[]{r, c, board[r][c]});
            }
        }
        try {
            return json.writeValueAsString(moves);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String winLineToJson(int[][] line) {
        try { return json.writeValueAsString(line); } catch (Exception e) { return null; }
    }

    private int[][] parseWinLine(String s) {
        if (s == null || s.isBlank()) return null;
        try { return json.readValue(s, int[][].class); } catch (Exception e) { return null; }
    }

    /**
     * 序列化房间状态给前端
     * <p>
     * 若 sinceVersion 给出且 board 相对于该 version 没变化, 把 boardJson 留空, 减少 payload
     * (前端按 version 自行对比, 没有改的就不重画棋子)。
     */
    private Map<String, Object> toState(GomokuRoom room, Long currentUserId, Integer sinceVersion) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roomCode", room.getRoomCode());
        out.put("boardSize", room.getBoardSize());
        out.put("status", room.getStatus());
        out.put("blackUserId", room.getBlackUserId());
        out.put("whiteUserId", room.getWhiteUserId());
        out.put("currentColor", room.getCurrentColor());
        out.put("moveCount", room.getMoveCount());
        out.put("winnerColor", room.getWinnerColor());
        out.put("winLine", parseWinLine(room.getWinLineJson()));
        out.put("updateTime", room.getUpdateTime());

        // 我的颜色(若是玩家): 让前端知道自己执黑还是执白
        if (currentUserId != null) {
            int myColor = 0;
            if (currentUserId.equals(room.getBlackUserId())) myColor = GomokuRules.BLACK;
            else if (currentUserId.equals(room.getWhiteUserId())) myColor = GomokuRules.WHITE;
            out.put("myColor", myColor);
            out.put("canMove", myColor != 0 && room.getStatus() == 1 && room.getCurrentColor() != null && room.getCurrentColor() == myColor);
        }

        // 增量传输: 若 sinceVersion 给出且没变化, 把 board 留 null; 否则把全部子传过去
        if (sinceVersion == null || room.getMoveCount() != sinceVersion) {
            out.put("board", parseBoardForJson(room));
        } else {
            out.put("board", null);
            out.put("unchanged", true);
        }
        return out;
    }

    /** 把 board_json 解析为 "color[][]" 二维字符串数组, 便于 JSON 序列化(避免 int 边界) */
    private String[][] parseBoardForJson(GomokuRoom room) {
        int size = room.getBoardSize() == null ? 15 : room.getBoardSize();
        String[][] out = new String[size][size];
        for (int i = 0; i < size; i++) for (int j = 0; j < size; j++) out[i][j] = "0";
        try {
            List<int[]> moves = json.readValue(room.getBoardJson(), new TypeReference<List<int[]>>() {});
            for (int[] p : moves) {
                if (p != null && p.length >= 3 && p[0] >= 0 && p[0] < size && p[1] >= 0 && p[1] < size) {
                    out[p[0]][p[1]] = String.valueOf(p[2]);
                }
            }
        } catch (Exception e) { /* 容错: 返回全 0 棋盘 */ }
        return out;
    }
}