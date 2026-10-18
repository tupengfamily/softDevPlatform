package com.learning.springboot.controller;

import com.learning.springboot.common.Result;
import com.learning.springboot.security.CurrentUser;
import com.learning.springboot.service.GomokuService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 五子棋联机对战 API(短轮询模式)
 * <p>
 * 前端每 1 秒 GET /state; 落子 POST /place-move; 轮到自己时前端才允许点击棋盘。
 * 由于五子棋联机场景玩家极少(自研项目内测规模), 不引入 WebSocket 减少依赖与运维成本。
 */
@RestController
@RequestMapping("/api/games/gomoku/rooms")
public class GomokuController {

    @Autowired private GomokuService gomokuService;

    /** 创建房间(boardSize 可选, 默认 15) */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody(required = false) Map<String, Object> body,
                                    @CurrentUser Long userId) {
        Integer size = body == null ? null : (body.get("boardSize") instanceof Number n ? n.intValue() : null);
        Map<String, Object> data = gomokuService.createRoom(userId, size);
        return Result.created("房间已创建", data);
    }

    /** 加入房间(通过房间码) */
    @PostMapping("/{roomCode}/join")
    public ResponseEntity<?> join(@PathVariable String roomCode, @CurrentUser Long userId) {
        Map<String, Object> data = gomokuService.joinRoom(userId, roomCode);
        return Result.ok("已加入房间", data);
    }

    /** 离开房间(前端 dispose 时调用) */
    @DeleteMapping("/{roomCode}/leave")
    public ResponseEntity<?> leave(@PathVariable String roomCode, @CurrentUser Long userId) {
        return Result.ok("已离开", gomokuService.leaveRoom(userId, roomCode));
    }

    /** 落子 */
    @PostMapping("/{roomCode}/place")
    public ResponseEntity<?> place(@PathVariable String roomCode,
                                   @RequestBody Map<String, Object> body,
                                   @CurrentUser Long userId) {
        Integer r = intOrNull(body.get("r"));
        Integer c = intOrNull(body.get("c"));
        if (r == null || c == null) throw new IllegalArgumentException("r/c 必填");
        Map<String, Object> data = gomokuService.placeMove(userId, roomCode, r, c);
        return Result.ok("已落子", data);
    }

    /**
     * 查询状态(前端轮询入口)
     * <p>
     * sinceVersion 是上一次拿到的 moveCount, 若服务端 moveCount 没变则不返回 board 字段
     */
    @GetMapping("/{roomCode}/state")
    public ResponseEntity<?> state(@PathVariable String roomCode,
                                   @RequestParam(required = false) Integer sinceVersion,
                                   @CurrentUser Long userId) {
        Map<String, Object> data = gomokuService.state(roomCode, userId, sinceVersion);
        return Result.ok(data);
    }

    /** 大厅列表: 等待中的房间 */
    @GetMapping
    public ResponseEntity<?> listWaiting() {
        return Result.ok(gomokuService.listWaiting());
    }

    private Integer intOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(o).trim()); } catch (NumberFormatException e) { return null; }
    }
}